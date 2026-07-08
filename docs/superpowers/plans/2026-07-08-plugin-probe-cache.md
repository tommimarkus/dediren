# Plugin Capability-Probe Result Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended, same session) or `superpowers:executing-plans` (separate session with review checkpoints) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do **not** batch tasks; land each behind its own commit with explicit-path staging.

**Goal:** Pay the capability-probe JVM spawn (~50–80 ms/stage, measured) **once per plugin build instead of once per invocation** by memoizing the validated `RuntimeCapabilities` on disk, keyed to a fingerprint of everything the probe attests. A cache hit must yield *exactly* the same semantic checks a live probe yields (runtime-schema validation, id match, capability support), so the change is invisible to agents and preserves the current integrity posture with **no `DEDIREN_TRUST_MANIFEST_CAPABILITIES` opt-in required**. This is the only surviving standalone piece of review idea **I5** (the `dediren pipeline` one-shot subcommand is deliberately *not* built here — it arrives as the facade of the I9 hybrid-host plan).

**Architecture:** Authoritative design source is `docs/superpowers/reviews/2026-07-08-runtime-size-speed-challenge.md` (idea I5 ledger, lines 139–147, and the follow-up ruling lines 29–31 / 63–67 / 105 that demote I5 to "probe cache only"). No product spec file exists for this work; the ruling plus this plan are the design record, and the key decisions are stated inline below. The cache is an internal core concern that sits on the **probe branch** of `PluginRunner.runForCapabilityWithRegistry` (`core/src/main/java/dev/dediren/core/plugins/PluginRunner.java`, lines 66–100). It stores a digest-fingerprinted entry per plugin id under a bundle-local-or-XDG cache directory that mirrors the existing CDS resolution pattern in the generated launchers (`dist-tool/.../DistTool.java#withCdsArchive`, lines 784–822). The cache never becomes a new trust boundary: it substitutes only the *JVM spawn + JSON parse + schema validation* of the probe, and re-applies the runtime-schema validation and the id/capability checks in-process against the cached value on every hit. Unreadable, corrupt, mismatched, or unsafe-permission cache state always falls back to a live probe, never to a failure.

**Tech Stack:** Java 21, Maven Wrapper (`./mvnw`), JUnit 6 (Jupiter API), AssertJ, Jackson (`tools.jackson`), networknt json-schema-validator via `dev.dediren.core.schema.SchemaValidator`, `java.security.MessageDigest` (SHA-256), `java.nio.file` POSIX file attributes. No Mockito — process-boundary plugins run for real in the runtime tests.

## Global Constraints

- **Design ruling (do not relitigate):** From I5 only the probe cache is built. The `pipeline` subcommand is out of scope (folds into the I9 plan). No version bump in this plan — version bumps are separate commits governed by `release-policy` (`CLAUDE.md` → Versioning). Do **not** edit `pom.xml` version, plugin manifest `version` fields, or any known version-assertion surface.
- **Integrity posture must equal the live probe (the load-bearing invariant).** A cache hit is honored **only** when a freshly recomputed `inputs_sha256` equals the stored one **and** the stored `RuntimeCapabilities` still validates against `schemas/runtime-capability.schema.json`. On a hit, `PluginRunner` still performs the id-match check (`DEDIREN_PLUGIN_ID_MISMATCH`) and the capability-support check (`DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`) in-process, identically to the live path. A "changed plugin" (manifest bytes, resolved executable path/size/mtime, or forwarded allowed-env values) changes the fingerprint and therefore is a **miss** — a stale or poisoned entry can never let a changed plugin skip probing.
- **Cache-poisoning + directory safety.** The entry stores only a SHA-256 **digest** of its inputs plus the (non-secret) declared `RuntimeCapabilities` — never raw manifest bytes or raw env values, so no secret (e.g. `HTTPS_PROXY`) is written to disk. The cache directory is created owner-only (POSIX `rwx------`); on read, a directory or entry that is group/world-writable or not owned by the current user is ignored (live probe). When the bundle dir is read-only (shared/system install) the cache falls to the per-user XDG dir, where cross-user poisoning is blocked by ownership/permission checks; same-user "poisoning" is not an escalation (the user can already set `DEDIREN_PLUGIN_<ID>` or `DEDIREN_PLUGIN_DIRS`). **TOCTOU:** the executable fingerprint is computed on the probe branch immediately before use; the cache adds no new window because the un-cached path already re-reads the executable between probe and work command. Neither probe nor cache defends a bundle whose executable an attacker can rewrite in place — that is out of the existing model and unchanged here. All of this is captured in the required `docs/threat-model.md` update, which rides the same commit as the `PluginRunner` wiring (Task 3).
- **Trust-mode interaction.** When trust mode already skips the probe (trusted bundled directory **and** `DEDIREN_TRUST_MANIFEST_CAPABILITIES` truthy — `PluginRunner` lines 60–65), the cache is neither read nor written: that branch returns before the probe path. The cache lives entirely on the probe branch and is orthogonal to trust mode.
- **Explicit `capabilities` command is never cached.** The internal probe that precedes a *work* command is memoized; an explicit `capabilities` subcommand invocation (`args[0] == "capabilities"`) always runs live and returns the plugin's raw stdout bytes, unchanged.
- **Never fail-closed on cache trouble.** Any exception in dir resolution, stat, read, parse, permission check, or write is swallowed → the code path degrades to a live probe (lookup) or a no-op (store). The cache is advisory.
- **Disable + override env (core-side, not forwarded to children).** Read from `options.candidateEnv()` first, then `System.getenv()`, mirroring how `DEDIREN_TRUST_MANIFEST_CAPABILITIES` is resolved (`PluginRunner` lines 117–123). `DEDIREN_PROBE_CACHE=off`/`0`/`false` disables the cache entirely. `DEDIREN_PROBE_CACHE_DIR` overrides the base directory (test/advanced seam; documented only minimally). These are core env reads and must **not** be added to any plugin manifest `allowed_env`.
- **Files-that-move-together.** This touches the plugin runtime boundary → `docs/threat-model.md` update is **required**, in the same commit as the `PluginRunner`/probe-branch wiring it documents (Task 3), per the threat model's Maintenance Rule ("same commit/PR as any change to plugin discovery/execution"). Introducing a bundle-local runtime-written `cache/` directory couples to distribution hermeticity → `dist-tool` archive excludes + tests (Task 4). Documenting a new `DEDIREN_*` env token in `docs/agent-usage.md` is gated by `AgentUsageDocConsistencyTest` (dist-tool): every `DEDIREN_*` token in that doc must exist as a source literal — ensure the literal ships before referencing it, and keep `README.md` consistent (Task 5). No `schemas/`, `contracts`, or public-schema-id change — the cache entry format is a private on-disk detail, not a published contract.
- **Git hygiene.** Direct commits to `main` are allowed. Stage explicit paths only; never `git add -A`. `git diff --check` clean before each commit. Do not stage generated `target/`, `dist/`, or any runtime-written `cache/` residue. One task = one scoped commit.
- **Verification lanes** (run with the sandbox **disabled** — `./mvnw` uses JUnit `@TempDir` under a read-only `/tmp` in the sandbox and will fail spuriously; see MEMORY "Maven tests need sandbox disabled"). Plugin-runtime lane: `./mvnw -pl core,cli -am test`. Distribution lane: `./mvnw -pl dist-tool -am verify -Pdist-smoke`. Docs-only: `git diff --check`. Quality gate before each commit touching Java: `./mvnw -Pquality spotless:apply` then (final) `./mvnw -Pquality verify`. Single class: `./mvnw -pl <module> -am test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false` (the `-am` + `failIfNoSpecifiedTests` flags are required for module-scoped runs — see MEMORY).
- **Audit gates** (work area = *Plugin runtime*): `test-quality-audit` **Deep** on the new core tests/fixtures; `devsecops-audit` **Quick** on the plugin process boundary and the on-disk cache posture. Run both before declaring the plan complete; fix block findings, fix-or-accept warn/info in the handoff.

---

## Task 1: Probe-cache directory resolution + safety guard

Resolve the cache base directory the way the CDS launcher block does — bundle-local if writable, else XDG — but entirely in core (no launcher/env-injection change), and add the ownership/permission guard that the CDS `.jsa` path does not need but this attestation cache does.

**Files**
- Create: `core/src/main/java/dev/dediren/core/plugins/ProbeCache.java` (start with dir-resolution + guard; entry read/write added in Task 2).
- Create (test): `core/src/test/java/dev/dediren/core/plugins/ProbeCacheTest.java`.

**Interfaces**
```java
// All resolution is pure w.r.t. an injected env lookup, mirroring DedirenPaths.productRoot(Function,...).
static boolean enabled(java.util.function.Function<String,String> env);
// Preference: DEDIREN_PROBE_CACHE_DIR override; else <bundleRoot>/cache/probes if creatable+writable;
// else ${XDG_CACHE_HOME:-$HOME/.cache}/dediren/probes. Returns empty if none is usable.
static java.util.Optional<java.nio.file.Path> resolveCacheDir(
    java.nio.file.Path bundleRoot, java.util.function.Function<String,String> env);
// True only when dir is a directory, owned by the current user, and not group/world-writable.
static boolean isSafeDir(java.nio.file.Path dir);
```
Resolution details: `<bundleRoot>` is `DedirenPaths.productRoot()` at the call site. Directory is created with POSIX perms `rwx------` when the filesystem supports it (`Files.createDirectories` + `PosixFilePermissions`; if unsupported, best-effort create). `isSafeDir` uses `Files.readAttributes(..., PosixFileAttributes.class)`; on a non-POSIX filesystem, accept (Windows dev is not a shipped target). Env reads go through the injected function only — no `System.getenv` inside the pure resolver (the `PluginRunner` wiring composes candidate-then-system lookup, Task 3).

**TDD steps**
- [ ] Write failing `ProbeCacheTest`: (a) `enabled` false for `off`/`0`/`false` (case-insensitive), true otherwise incl. unset; (b) `resolveCacheDir` returns `<bundleRoot>/cache/probes` when bundle dir is writable (use `@TempDir` bundle); (c) returns the XDG path (`XDG_CACHE_HOME` honored, else `HOME/.cache`) when the bundle dir is read-only; (d) honors `DEDIREN_PROBE_CACHE_DIR`; (e) `isSafeDir` false for a `rwxrwxrwx` dir, true for a freshly created `rwx------` dir.
- [ ] Run the class to confirm it fails to compile/red (sandbox disabled): `./mvnw -pl core -am test -Dtest=ProbeCacheTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Implement `ProbeCache` dir-resolution + guard only.
- [ ] Run `ProbeCacheTest` green.
- [ ] `./mvnw -Pquality spotless:apply`; commit `core/.../ProbeCache.java` + `core/.../ProbeCacheTest.java` (explicit paths).

---

## Task 2: Fingerprint + entry read/write (memoization core)

Add the digest fingerprint and the atomic, corruption-tolerant, schema-revalidating entry I/O. Mirror `SchemaCacheModule.ensureCachedSchemaFile` for the temp-file + `ATOMIC_MOVE` write and the "mismatch ⇒ treat as absent, re-derive" posture (`schema-cache/.../SchemaCacheModule.java`, lines 68–154).

**Files**
- Modify: `core/src/main/java/dev/dediren/core/plugins/ProbeCache.java`.
- Modify (test): `core/src/test/java/dev/dediren/core/plugins/ProbeCacheTest.java`.

**Interfaces**
```java
// Digest over: manifestPath (abs-normalized) + manifest bytes + resolved executable abs path
// + executable size + executable lastModified (nanos) + sorted "name=value\n" of forwardedEnv.
// Only the hex digest is persisted — never raw manifest bytes or raw env values.
static String inputsSha256(java.nio.file.Path manifestPath, java.nio.file.Path executable,
    java.util.Map<String,String> forwardedEnv) throws java.io.IOException;

// Re-validation of the cached capabilities is injected so unit tests need no product root.
@FunctionalInterface interface CapabilitiesValidator { java.util.List<String> validate(tools.jackson.databind.JsonNode capabilities); }

static java.util.Optional<RuntimeCapabilities> lookup(
    java.nio.file.Path cacheDir, String pluginId, String inputsSha256, CapabilitiesValidator validator);
static void store(
    java.nio.file.Path cacheDir, String pluginId, String inputsSha256, RuntimeCapabilities capabilities);
```
Entry file: `<cacheDir>/<pluginId>.probe.json`, shape `{"probe_cache_version":1,"inputs_sha256":"<hex>","runtime_capabilities":{...}}`. `lookup` returns empty unless: dir passes `isSafeDir`, file parses, `probe_cache_version==1`, stored `inputs_sha256` equals the argument, and `validator.validate(runtime_capabilities)` is empty; then it deserializes `runtime_capabilities` into `RuntimeCapabilities` (`JsonSupport.objectMapper().treeToValue`). Any `IOException`/`JacksonException`/parse/permission failure ⇒ empty. `store` writes to a sibling temp file then `ATOMIC_MOVE`/`REPLACE_EXISTING` (fallback to plain replace), swallows every failure (advisory), and best-effort deletes the temp file in `finally`.

**TDD steps**
- [ ] Write failing tests: (1) `store` then `lookup` with matching `inputsSha256` + permissive validator ⇒ present, `RuntimeCapabilities` round-trips id + capabilities; (2) **invalidation on manifest change** — recompute `inputsSha256` after rewriting the manifest file ⇒ `lookup` empty; (3) **invalidation on executable change** — recompute after changing the fake executable's size/mtime ⇒ empty; (4) **corrupted entry** — overwrite the entry file with `not json{` ⇒ `lookup` empty (no throw); (5) **schema re-validation** — inject a validator returning a non-empty error list ⇒ `lookup` empty even with matching digest; (6) **env sensitivity** — same files, different `forwardedEnv` values ⇒ different `inputsSha256` ⇒ miss; (7) **no secret on disk** — read the raw entry bytes and assert they contain neither a raw env value nor the manifest's declared `executable` string.
- [ ] Run to red (sandbox disabled): `./mvnw -pl core -am test -Dtest=ProbeCacheTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Implement fingerprint + `lookup`/`store`.
- [ ] Run green.
- [ ] `spotless:apply`; commit the two files.

---

## Task 3: Wire the cache into the probe branch + prove the JVM is skipped

Split the current single flow so the explicit-`capabilities` path stays live-only and the work path consults the cache. Prove the probe JVM is genuinely elided with a probe-invocation counter in the runtime testbed (a wall-clock oracle would be flaky and is prohibited).

**Files**
- Modify: `core/src/main/java/dev/dediren/core/plugins/PluginRunner.java`.
- Modify: `testbeds/plugin-runtime/src/main/java/dev/dediren/testbeds/pluginruntime/Main.java` (append one byte to `$DEDIREN_TEST_PROBE_LOG` when invoked with the `capabilities` arg and that env is present; otherwise unchanged).
- Modify (test): `core/src/test/java/dev/dediren/core/plugins/PluginRuntimeTest.java` (new tests + a `writeManifest` overload / allowlist that includes `DEDIREN_TEST_PROBE_LOG` and `DEDIREN_PROBE_CACHE_DIR` without disturbing existing tests).
- Modify: `docs/threat-model.md` (required — this task changes plugin execution; per the threat model's Maintenance Rule, the doc update rides the same commit, not a later one):
  - Under **Plugin process boundary** (§ around line 29), add a paragraph documenting the probe cache: what it memoizes (validated `RuntimeCapabilities`), the fingerprint binding (manifest path+bytes, resolved executable path/size/mtime, forwarded allowed-env values — digest only), that a hit re-runs runtime-schema validation + id-match + capability checks in-process so the posture equals a live probe, and that it is bypassed under trust mode and for explicit `capabilities` commands.
  - State the controls explicitly: owner-only (`rwx------`) cache dir; read-time rejection of non-owned or group/world-writable cache dirs/entries; bundle-if-writable-else-XDG location so a shared/system install falls to per-user scope; digest-only persistence (no manifest bytes / no env secrets on disk); fail-open on any cache error. Note the **accepted residual risk**: same-user tampering of a user-scoped cache is not an escalation (the user already controls `DEDIREN_PLUGIN_<ID>` / `DEDIREN_PLUGIN_DIRS`), and neither probe nor cache defends an in-place executable rewrite. Note the **TOCTOU** stance (fingerprint computed on the probe branch immediately before use; no new window vs. today).
  - Honor the § **Maintenance Rule**: mention `core/.../plugins/ProbeCache.java` alongside the existing `PluginRunner`/`PluginRegistry` references.

**Interfaces / wiring**
- In `runForCapabilityWithRegistry`, after the trust-mode early return (line 65), branch:
  - If `capabilitiesCommand`: keep the existing live-probe path (lines 66–94) verbatim — no cache read/write.
  - Else (work path): resolve cache dir from `DedirenPaths.productRoot()` + a candidate-then-system env lookup; if `ProbeCache.enabled` and dir present, compute `inputsSha256(loaded.path(), executable, allowedEnv)` and `ProbeCache.lookup(...)` with `validator()::validate` adapted to the `CapabilitiesValidator` (validating against `schemas/runtime-capability.schema.json`). On hit, use the cached `RuntimeCapabilities`; on miss, run the `capabilities` `runExecutable` + `normalizeRuntimeCapabilities` exactly as today, then `ProbeCache.store(...)`.
  - Then run the existing id-match and capability-support checks against the (cached or fresh) `RuntimeCapabilities`, then the work command — unchanged.
- Extract a small private helper `probeForWork(...)` returning `RuntimeCapabilities` to keep the method readable; the id/capability checks stay in the caller so both hit and miss traverse identical validation. `allowedEnv` is the same map already forwarded to the probe, so the fingerprint binds the exact env the probe would have seen.

**TDD steps**
- [ ] Write failing runtime tests in `PluginRuntimeTest` (each sets `DEDIREN_PROBE_CACHE_DIR` to a `@TempDir` via `candidateEnv`, and points `DEDIREN_TEST_PROBE_LOG` at a temp file):
  - `probeIsSkippedOnSecondInvocationWhenInputsUnchanged` — two `runForCapabilityWithRegistry` work calls with identical inputs; assert both succeed and the probe-log file has exactly **1** entry (probe spawned once).
  - `cacheMissReprobesWhenExecutableChanges` — prime cache; mutate the testbed executable (rewrite to change size+mtime, keeping it valid); second call still succeeds and probe-log has **2** entries.
  - `cacheMissReprobesWhenManifestChanges` — prime; rewrite the manifest (e.g. add an allowed-env entry) then reload; probe-log reaches **2**.
  - `corruptCacheEntryFallsBackToLiveProbe` — prime; overwrite `<id>.probe.json` with garbage; next call succeeds (probe-log increments) and the entry is repaired (valid JSON afterward).
  - `poisonedCacheCannotFakeIdMatch` — hand-write an entry whose `runtime_capabilities.id` matches but whose `inputs_sha256` is wrong; run with the testbed set to report a **mismatched** runtime id; assert `DEDIREN_PLUGIN_ID_MISMATCH` (poison ignored, live probe runs and catches drift).
  - `disabledCacheAlwaysReprobes` — `DEDIREN_PROBE_CACHE=off`; two calls ⇒ probe-log has **2** entries and no entry file is written.
  - `explicitCapabilitiesCommandIsNotServedFromCache` — an explicit `capabilities` invocation runs live (probe-log increments) even after a work-path call primed the cache, and returns raw stdout containing `"id"`.
  - `trustModeSkipsProbeWithoutTouchingCache` — trusted dir + `DEDIREN_TRUST_MANIFEST_CAPABILITIES=1`; assert success and that **no** `*.probe.json` file was created.
- [ ] Run to red (sandbox disabled): `./mvnw -pl core -am test -Dtest=PluginRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Implement testbed probe-log + `PluginRunner` wiring.
- [ ] Run green; then the full plugin-runtime lane `./mvnw -pl core,cli -am test`.
- [ ] Add the `docs/threat-model.md` paragraph documenting the probe cache (memoized data, fingerprint binding, hit re-validation, trust-mode/`capabilities`-command bypass, the explicit controls list, the accepted residual risk, the TOCTOU stance, and the Maintenance Rule file-list mention of `ProbeCache.java`) — see the Files entry above.
- [ ] `git diff --check`.
- [ ] `spotless:apply`; commit `PluginRunner.java` + testbed `Main.java` + `PluginRuntimeTest.java` + `docs/threat-model.md`.

---

## Task 4: Exclude the runtime-written `cache/` directory from the distribution archive

The CLI now writes `<bundleRoot>/cache/probes/*.probe.json` on first use when the bundle is writable. The build archive must stay hermetic exactly as it already does for `cds/*.jsa`.

**Files**
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java` (add `--exclude=cache` next to the existing `--exclude=cds` / `--exclude=*.jsa`, lines ~308–311).
- Modify (test): `dist-tool/src/test/java/dev/dediren/tools/dist/DistHermeticityTest.java` (simulate a `cache/probes/runtime-testbed.probe.json` residue and assert it is absent from the produced archive). Update `DistModuleTest.java` only if it enumerates the exclude set.

**TDD steps**
- [ ] Write failing hermeticity assertion: seed the staged bundle with a `cache/probes/x.probe.json` file before archiving; assert the archive entries contain no `cache/` path.
- [ ] Run to red (sandbox disabled): `./mvnw -pl dist-tool -am test -Dtest=DistHermeticityTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Add the `--exclude=cache` filter.
- [ ] Run green; then the distribution lane `./mvnw -pl dist-tool -am verify -Pdist-smoke`.
- [ ] `spotless:apply`; commit the dist-tool source + test(s).

---

## Task 5: User-facing docs for the new tuning env var

`DEDIREN_PROBE_CACHE` is a user-visible runtime toggle → README + agent-usage move together (`CLAUDE.md` → Files That Move Together). Keep the source-token consistency test green.

**Files**
- Modify: `README.md` (brief note in the runtime/agent-flow section: the capability probe is cached per plugin build under `<bundle>/cache/probes` or the XDG cache; set `DEDIREN_PROBE_CACHE=off` to disable).
- Modify: `docs/agent-usage.md` (one line under the runtime/env section referencing `DEDIREN_PROBE_CACHE`).

**Constraints / steps**
- [ ] Document only `DEDIREN_PROBE_CACHE` in `docs/agent-usage.md` (keep `DEDIREN_PROBE_CACHE_DIR` as an undocumented advanced/test seam). `AgentUsageDocConsistencyTest` (dist-tool) requires every `DEDIREN_*` token in that doc to exist as a source literal and every CalVer string to match the product version — the literal ships in Task 1/3, and this task adds no version strings, so do not introduce any.
- [ ] Keep README and agent-usage wording consistent; do not duplicate agent-authoring detail into README (README defers down to agent-usage per MEMORY "Docs front-door").
- [ ] Verify: `git diff --check`; then re-run the doc-consistency guard `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled).
- [ ] Commit `README.md` + `docs/agent-usage.md`.

---

## Final verification + audit gates

- [ ] Full plugin-runtime lane green (sandbox disabled): `./mvnw -pl core,cli -am test`.
- [ ] Distribution lane green: `./mvnw -pl dist-tool -am verify -Pdist-smoke`.
- [ ] Quality gate: `./mvnw -Pquality verify` (format + SpotBugs + tests). No new `spotbugs-exclude.xml` suppression without a `docs/architecture-guidelines.md §12` note.
- [ ] `test-quality-audit` **Deep** over `ProbeCacheTest` + the new `PluginRuntimeTest` cases (oracle strength: probe-count counter, real subprocess, poison/corruption/invalidation partitions).
- [ ] `devsecops-audit` **Quick** over the plugin boundary + on-disk cache posture (permissions, fail-open, no-secret-on-disk, threat-model coverage).
- [ ] Fix block findings; fix-or-accept warn/info in the handoff; rerun affected lanes.

---

## Self-Review

**Spec coverage** (against the review-doc ruling + this plan's scoping constraints):
- Probe memoized once per plugin build, no trust-mode opt-in → Tasks 2–3 (`probeIsSkippedOnSecondInvocationWhenInputsUnchanged`, default-on).
- Fingerprint binds manifest + executable identity; stale/poisoned entry cannot let a changed plugin skip probing → Task 2 invalidation tests + Task 3 `cacheMissReprobesWhen…`, `poisonedCacheCannotFakeIdMatch`.
- Cache-poisoning + TOCTOU addressed explicitly → Global Constraints + Task 3 threat-model.
- Storage mirrors the CDS bundle-if-writable-else-XDG pattern → Task 1 (verified against `DistTool#withCdsArchive`).
- Invalidation + corruption fall back to live probe, never failure → Task 2 (corrupt/schema tests) + Task 3 (`corruptCacheEntryFallsBackToLiveProbe`).
- `docs/threat-model.md` update is folded into the same commit as the `PluginRunner` wiring it documents → Task 3.
- Trust-mode interaction (bypassed) + I9 forward-compat (external plugins keep the cache) → Global Constraints + Task 3 `trustModeSkipsProbeWithoutTouchingCache`; the cache is capability-agnostic and applies to any probe-branch plugin, trusted or third-party.
- Core tests: hit, miss, invalidation-on-manifest, invalidation-on-executable, corrupted entry → all present (Tasks 2–3).
- Out of scope honored: no `pipeline` subcommand, no version bump, no public schema change.

**Placeholder scan:** No `TODO`/`TBD`/`XXX`/`FIXME` left in the plan; every task names concrete files, interfaces, and commands. Plan file follows the repo `YYYY-MM-DD-…` naming convention.

**Type consistency checks:**
- `ProbeCache.lookup` returns `Optional<RuntimeCapabilities>`; `RuntimeCapabilities` is the existing `dev.dediren.contracts.plugin.RuntimeCapabilities` record (id, capabilities, protocol, runtime) — deserialized via `JsonSupport.objectMapper().treeToValue`, the same call `normalizeRuntimeCapabilities` already uses.
- Env reads use `java.util.function.Function<String,String>` in the pure resolver and the candidate-then-system composition at the `PluginRunner` call site, matching `manifestTrustEnabled` (lines 117–123) and `DedirenPaths.productRoot(Function,…)`.
- `CapabilitiesValidator` adapts `SchemaValidator.validate(String schemaPath, JsonNode)` (returns `List<String>` of errors) — no signature drift; injection keeps `ProbeCacheTest` free of a product root.
- Cache entry JSON uses `tools.jackson.databind.JsonNode` / `ObjectNode` via `JsonSupport.objectMapper()`, consistent with the rest of core. Digest is lowercase hex from `MessageDigest.getInstance("SHA-256")`, matching `SchemaCacheModule.sha256Hex` conventions.
- Atomic write uses `StandardCopyOption.ATOMIC_MOVE` + `REPLACE_EXISTING` with a plain-move fallback, identical to `SchemaCacheModule.ensureCachedSchemaFile`.

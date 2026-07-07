# Stored-Jar + xz Distribution Implementation Plan

> Agentic worker: execute this plan with the
> `superpowers:subagent-driven-development` skill (or
> `superpowers:executing-plans` if you run it as a separate review-checkpointed
> session). Each task is TDD: write the failing test, watch it fail, implement,
> watch it pass, then run the task's verification lane, format, and commit.
> Do not batch tasks into one commit.

## Goal

Cut the agent-bundle download roughly in half at zero product-code cost by
changing only how `dist-tool` packages jars. Today each `lib/*.jar` is a
`deflate`-compressed zip, so the release `tar.gz` is compressing already-random
deflate output and gains almost nothing across jars. If every jar is first
repacked *stored* (uncompressed, zip method 0), the tarball's `xz` pass can
finally compress across every class file at once. On the post-I4 jar set (Batik
family removed) this measured **13.16 MB deflated `tar.gz` → 7.15 MB stored
`tar.xz`, a 45.7% smaller download** (58-jar baseline evidence in the challenge
review; the post-I4 figures were re-measured in scratchpad — see Global
Constraints). The price is on-disk: the unpacked `lib/` grows ~2.2x
(14 MB → 31 MB for the post-I4 set) because the jars no longer self-compress.
That trade — pay disk to save download — is stated honestly in the install docs.

## Architecture

There is no separate design spec for this work. The authoritative design source
is the runtime size+speed challenge review,
`docs/superpowers/reviews/2026-07-08-runtime-size-speed-challenge.md` (idea I7 —
"Store-repacked jars + xz distribution" — plus its ledger and the follow-up
rulings), and the measured numbers reproduced in Global Constraints below.

The change is confined to the distribution boundary. `dist-tool`
(`DistTool.build`) already stages each launcher's declared classpath jars into
`<bundle>/lib/` and then shells out to `tar -czf` to produce
`dediren-agent-bundle-<version>.tar.gz`. This plan inserts one in-process
**stored-repack** pass over the staged `lib/` jars (rewriting every zip entry
with method `STORED`, in Java via `java.util.zip`, no new dependency and no new
CLI tool) and switches the archive from gzip to xz
(`dediren-agent-bundle-<version>.tar.xz`). No launcher script, classpath,
plugin, contract, schema, or render behavior changes: the JVM loads a
stored-entry jar identically to a deflated one (functionally verified in the
challenge). `smoke`/`bench` extraction and the release workflow are updated to
the new extension.

This plan is packaging-level and composes with I4 (drop PNG/Batik) and I9
(hybrid host) without ordering constraints: it repacks whatever jar set is
staged. It does not touch the render/PNG path, so it is orthogonal to I4 and
can land before or after it. The ~8% warm-pipeline speed side-benefit measured
in the challenge (no per-class inflate at classload) is noted but **not
counted** as a justification here — under I9's one-shot host (daemon is an
explicit I9 follow-up, not in its scope) that side-benefit is largely moot; the
download size win is the whole case.

## Tech Stack

Java 21 (Maven Wrapper build), `dist-tool` module (`DistTool`, exec-maven-plugin
`dist-build`/`dist-smoke`/`dist-bench` profiles), `java.util.zip`
(`ZipFile`/`ZipOutputStream`, method `STORED`), system `tar` + `xz` for
archiving, GitHub Actions release workflow, JUnit 5 + AssertJ.

## Global Constraints

- **Authoritative design source:** the review doc cited above. Idea I7 ledger
  (challenge lines ~153–159) and the follow-up rulings are the design of
  record. No behavior spec exists; the rulings below are binding.
- **Measured numbers (post-I4 jar set, 37 jars, this environment, verified in
  scratchpad against `dist/dediren-agent-bundle-2026.07.8/lib` with Batik/
  xmlgraphics/xml-apis family excluded):**
  - deflated `tar.gz` (current packaging): **13,159,730 B (≈12.55 MiB)**.
  - stored → `tar.xz -9`: **7,147,936 B (≈6.82 MiB)** → **45.7% smaller**.
  - stored → `tar.zst -19`: 7,783,977 B — xz wins, so **xz is chosen**.
  - installed `lib/` on disk: **14 MB deflated → 31 MB stored (~2.2x)**. This
    matches the challenge's full-58-jar figure (18 MB → 41 MB, ~2.2x).
  - The download delta is dominated by `lib/`; `schemas/`/`fixtures/`/`docs/`
    are small and unchanged, and runtime-generated `cds/`/`*.jsa` are excluded
    from the archive as today.
- **Do not count the speed side-benefit.** Justify the change on download size
  only. Note the ~8% warm side-benefit as incidental and moot under I9.
- **No version bump in this plan.** Per `release-policy`
  (`CLAUDE.md §Versioning`), the CalVer bump is a separate follow-on commit with
  its own annotated `v<version>` tag, sequenced after this change integrates on
  `main`. Do not edit `pom.xml` versions here. `DistModuleTest` is a known
  version-assertion surface, but the edits here are archive-format edits, not
  version-string edits — keep every version string it asserts unchanged.
- **Ship one archive, `.tar.xz`, replacing `.tar.gz` (do not keep both).**
  Justification: (a) keeping gzip too would require either staging the jars
  twice (deflated set for gz, stored set for xz) or gzipping the stored jars
  (~13 MB, defeating the purpose); (b) `tar` + `xz` are universally present
  wherever a JDK 21 runtime already is, and modern `tar -xf` autodetects xz;
  (c) the workflow and `DistModuleTest` already enforce an "exactly one
  archive" invariant — preserving a single artifact keeps `SHA256SUMS`,
  attestation, and that invariant intact with the least churn. Fallback for an
  ancient `tar`: `xz -d bundle.tar.xz && tar -xf bundle.tar` — document it.
- **Repack in Java, in-process.** Prefer `java.util.zip` over shelling to
  `unzip`/`zip` (the challenge experiment used `unzip`+`zip -0`, but that adds
  two build-time CLI deps and temp-dir churn). The staged jars are rewritten in
  place with all entries `STORED`; a stored-entry jar is a valid zip the JVM
  loads identically. Only `xz` (via `tar -cJf`) becomes a newly-required
  archiving tool; `xz-utils` is present on the release runner but add it to the
  workflow's `apt-get install` line defensively.
- **Files that move together (CLAUDE.md).** Distribution/artifact-location
  changes update `README.md` and `docs/agent-usage.md` together. Release
  workflow / release-artifact changes are a threat-model trigger: update
  `docs/threat-model.md` in the same change (its released-archive reference).
- **Verification lanes (CLAUDE.md §Verification; run with the sandbox disabled
  because `@TempDir`/dist-smoke need a writable `/tmp` and real `java`/`xz`):**
  - dist-tool unit tests: `./mvnw -pl dist-tool -am test`.
  - distribution build+smoke: `./mvnw -pl dist-tool -am verify -Pdist-smoke`.
  - docs-only edits: `git diff --check`.
  - full guard before calling done: `./mvnw test` then
    `./mvnw -pl dist-tool -am verify -Pdist-smoke` then `git diff --check`.
- **Do not run Maven from parallel workers.** Sibling plan-execution agents
  share `target/`; run the build lanes solo.
- **Git hygiene (CLAUDE.md §Git Hygiene).** Direct commits to `main` are
  allowed; a fix branch is optional. Stage explicit paths only — never
  `git add -A` (the build writes untracked `dist/` artifacts that must not be
  committed). Report any generated `dist/*.tar.xz` path instead of staging it.
- **Audit gates (distribution work).** Before calling the work complete, run
  `souroldgeezer-audit:devsecops-audit` (Quick: release-artifact format,
  attestation/SBOM/`SHA256SUMS` flow, supply-chain evidence, docs) and
  `souroldgeezer-audit:test-quality-audit` (Quick: the changed dist-tool tests).
  Fix block findings; fix or explicitly accept warn/info findings in the
  handoff.

## Task 1 — In-process stored-jar repack in `DistTool`

Add a package-private helper that rewrites a single jar so every entry is
`STORED`, and a pass that applies it to every jar staged under `<bundle>/lib/`.
Unit-test the helper directly (deflated-in → stored-out, bytes preserved,
zip still valid) so the behavior is pinned independently of a full build.

### Files
- **Modify:** `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
  - Add `static void storeRepackJar(Path jar)` (or a small
    `JarStoredRepacker` helper class if it reads cleaner) that reads `jar` with
    `java.util.zip.ZipFile`, writes a sibling temp file with
    `ZipOutputStream` where every copied `ZipEntry` uses `setMethod(STORED)`
    with `setSize`/`setCompressedSize`/`setCrc` computed from the entry bytes,
    preserves entry names/order and directory entries, then atomically replaces
    the original. Preserve each entry's original time for a minimal-diff
    rewrite. Skip entries already stored is unnecessary — re-setting STORED is
    idempotent.
  - Add `static void storeRepackLib(Path lib)` that iterates `lib/*.jar` and
    calls `storeRepackJar` on each.
  - Call `storeRepackLib(bundle.resolve("lib"))` inside `build(...)` **after**
    `verifyPackagedLib(...)` (so the hermeticity exact-match guard still runs
    against the declared jar *names*, which the repack preserves) and **before**
    the `afterStage.accept(bundle)` seam / the archive step.
- **Test:** `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`
  (add unit tests; this is the existing home for `DistTool` white-box tests).

### Interfaces
- `static void storeRepackJar(java.nio.file.Path jar) throws IOException`
- `static void storeRepackLib(java.nio.file.Path lib) throws IOException`

### TDD steps
- [ ] Write failing test `storeRepackJarRewritesEntriesUncompressed`: build a
      small deflated jar in a `@TempDir` with a couple of entries (including a
      directory entry) using `ZipOutputStream` default (DEFLATED); assert its
      on-disk size is smaller than the raw content sum (proves it started
      compressed). Call `DistTool.storeRepackJar`. Assert (a) every entry's
      `ZipEntry.getMethod()` is `ZipEntry.STORED`, (b) each entry's bytes are
      byte-identical to the original, (c) the entry name set/order is preserved.
- [ ] Write failing test `storeRepackLibRepacksEveryJar`: stage two deflated
      jars in a `lib/` `@TempDir`, call `storeRepackLib`, assert both are fully
      stored.
- [ ] Run `./mvnw -pl dist-tool -am test -Dtest=DistModuleTest` (sandbox
      disabled) and confirm the new tests fail (method missing / entries still
      DEFLATED).
- [ ] Implement `storeRepackJar` + `storeRepackLib`; wire `storeRepackLib` into
      `build(...)` at the point described above.
- [ ] Run the same test command; confirm the new tests pass and no existing
      `DistModuleTest` case regressed.
- [ ] `./mvnw -pl dist-tool -am test` (full module), then
      `./mvnw -Pquality spotless:apply` and commit
      (`feat(dist): repack bundle jars stored before archiving`).

## Task 2 — Archive as `.tar.xz` and assert stored+xz in the build

Switch the produced archive and every extractor in `DistTool` from gzip to xz,
update the two format-coupled tests (`DistModuleTest`, `DistHermeticityTest`),
and add build-level assertions that the shipped archive is xz and its jars are
stored — so a future refactor cannot silently revert either half of the win.

### Files
- **Modify:** `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
  - `build(...)`: archive path `bundle.getFileName() + ".tar.xz"`; change the
    `tar` invocation from `-czf` to `-cJf` (xz), keeping all existing
    `--exclude` hermeticity filters (currently `cds` / `*.jsa`; the probe-cache
    plan adds `cache`) and the `-C dist` layout.
  - `run(...)` default archive resolution for `smoke` and `bench`:
    `bundleName(version) + ".tar.xz"`.
  - `smoke(...)` and `bench(...)`: extraction `tar -xzf` → `tar -xf`
    (autodetect) or `-xJf`.
  - `pruneStaleArtifacts(...)`: match `currentBundle + ".tar.xz"` (and drop the
    `.tar.gz` special-case so a prior `.tar.gz` in `dist/` is pruned as stale).
- **Modify:** `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`
  - `buildProducesVersionOnlyJavaArchive`: expected archive
    `dediren-agent-bundle-2026.06.0.tar.xz`; the stale-artifact fixtures/asserts
    stay (still `.tar.gz` stale inputs — they must be pruned).
  - `readArchiveEntry(...)` uses `tar -xOf` (autodetects xz) — no change needed,
    but confirm it still resolves entries from the xz archive.
- **Modify:** `dist-tool/src/test/java/dev/dediren/tools/dist/DistHermeticityTest.java`
  - Every `"dediren-agent-bundle-" + VERSION + ".tar.gz"` "does not exist on
    failure" assertion → `.tar.xz`.

### Interfaces
- Archive name contract: `dediren-agent-bundle-<version>.tar.xz` (was
  `.tar.gz`). `bundleName(String)` is unchanged (extension is appended by
  callers).

### TDD steps
- [ ] Add failing test `buildProducesXzArchiveWithStoredJars` in
      `DistModuleTest`: run `DistTool.build` against the minimal distribution
      root; assert (a) `dist/dediren-agent-bundle-<v>.tar.xz` exists and
      `.tar.gz` does not, (b) the archive's magic bytes are the xz signature
      (`FD 37 7A 58 5A 00`), and (c) at least one packaged `lib/*.jar` extracted
      from the archive has all-`STORED` entries. Extract via `tar -xf`/`tar
      -xOf` in the test.
- [ ] Update `buildProducesVersionOnlyJavaArchive` and the `DistHermeticityTest`
      assertions to `.tar.xz` (these will now fail against the current gzip
      build).
- [ ] Run `./mvnw -pl dist-tool -am test` (sandbox disabled); confirm the
      format tests fail.
- [ ] Implement the `DistTool` gzip→xz changes (build/smoke/bench/default-path/
      prune).
- [ ] Run `./mvnw -pl dist-tool -am test`; confirm all pass.
- [ ] Run `./mvnw -pl dist-tool -am verify -Pdist-smoke` (sandbox disabled):
      the real build stages, stored-repacks, xz-archives, and the smoke test
      extracts and exercises the full pipeline (validate → project → layout →
      validate-layout → render, plus OEF/UML export) against the xz bundle.
      This is the functional smoke that the challenge already proved works with
      stored jars.
- [ ] `./mvnw -Pquality spotless:apply` and commit
      (`feat(dist): ship the agent bundle as tar.xz`).

## Task 3 — Release workflow: publish and verify `.tar.xz`

Update `.github/workflows/release.yml` so the build, attestation, upload,
download, verify, checksum, and publish steps all handle the single `.tar.xz`
artifact, and add `xz-utils` to the build job's package install defensively.
Keep the "exactly one archive" invariants the workflow already enforces.

### Files
- **Modify:** `.github/workflows/release.yml`
  - build job: `apt-get install` line adds `xz-utils`; "Capture archive path"
    glob `dediren-agent-bundle-*.tar.gz` → `*.tar.xz` (both the `mapfile` find
    and the error-path find); upload-artifact `compression-level: 0` stays
    (already-compressed). Attestation `subject-path` follows the captured path,
    unchanged in shape.
  - publish job: `cp release-artifacts/*.tar.gz` → `*.tar.xz`; the
    `archive="release-assets/dediren-agent-bundle-${VERSION}.tar.gz"` →
    `.tar.xz`; `tar_count` find `-name '*.tar.gz'` → `*.tar.xz`;
    `tar -tzf "$archive" ...` (two lines) → `tar -tf "$archive" ...`
    (xz autodetect); `tar -xOf "$archive" .../bundle.json` is unchanged
    (autodetects xz); `sha256sum *.tar.gz` → `*.tar.xz`; attestation loop
    `for archive in release-assets/*.tar.gz` → `*.tar.xz`; `gh release create`
    asset `dediren-agent-bundle-${VERSION}.tar.gz` → `.tar.xz`.
- **Modify:** `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`
  - `releaseWorkflowPublishesSingleJavaArchive` asserts workflow text. Its
    checks are (i) `workflowStepContaining(publishJob, "tar -xOf")` — still
    present after the change, keep; (ii) negative `doesNotContain(...)` glob
    strings (`"release-assets/*.tar.gz"`, etc.) — these are anti-multiplatform
    guards; verify they remain true for the `.tar.xz` publish step and add
    equivalent single-archive assertions if the extension change weakens any
    guard. Adjust only what the extension flip breaks; do not loosen the
    single-artifact invariant.
- **Modify:** `docs/threat-model.md` (required in this commit, not a later docs
  commit — the release-workflow edit above is a threat-model trigger):
  - Line ~10 released-archive reference
    `dediren-agent-bundle-*.tar.gz` → `*.tar.xz`. Add a one-line note if
    warranted that the artifact is a stored-jar xz tarball (no change to trust
    boundary; still attested + checksummed).

### Interfaces
- Release asset name: `dediren-agent-bundle-<version>.tar.xz` (plus unchanged
  `SHA256SUMS`, `dediren-<version>.cdx.json`, `dediren-<version>.cdx.xml`).

### TDD steps
- [ ] Update/extend `releaseWorkflowPublishesSingleJavaArchive` to assert the
      publish path references `.tar.xz` (e.g. the `gh release create` step
      contains `dediren-agent-bundle-${VERSION}.tar.xz`) and still exactly one
      archive is uploaded/downloaded. Run `./mvnw -pl dist-tool -am test
      -Dtest=DistModuleTest` (sandbox disabled); confirm it fails against the
      current gzip workflow.
- [ ] Edit `release.yml` per the list above.
- [ ] Edit `docs/threat-model.md`: update the released-archive reference
      `dediren-agent-bundle-*.tar.gz` → `*.tar.xz` (line ~10) and add the
      one-line stored-jar-xz-tarball note if warranted.
- [ ] Run `./mvnw -pl dist-tool -am test -Dtest=DistModuleTest`; confirm pass.
- [ ] `git diff --check`.
- [ ] Commit (`ci(release): publish the agent bundle as tar.xz; update the
      threat-model released-archive reference`). Note in the handoff that the
      workflow is not executed locally; its runtime correctness is covered by
      the next real release run.

## Task 4 — Install docs: new extension + honest size trade

Update the user-facing install/download surfaces to the new archive name and
state the disk trade plainly, keeping `README.md` and `docs/agent-usage.md`
consistent.

### Files
- **Modify:** `README.md`
  - The `-Pdist-build` output block (`dist/dediren-agent-bundle-<v>.tar.gz` →
    `.tar.xz`).
  - The First Run `BUNDLE=$(ls -d ... | grep -v '\.tar\.gz$' ...)` line →
    exclude `\.tar\.xz$`.
  - The attestation-verify example
    `gh attestation verify dediren-agent-bundle-<version>.tar.gz ...` →
    `.tar.xz`.
  - Add one sentence next to the dist-build block: jars are repacked *stored*
    so the `xz` archive compresses across classes (roughly half the download);
    the unpacked `lib/` is correspondingly ~2.2x larger on disk — a deliberate
    download-for-disk trade.
- **Modify:** `docs/agent-usage.md`
  - Update any archive-name / unpack guidance to `.tar.xz` and mirror the
    one-line size-trade note (bundle-local, command-oriented: e.g. unpack with
    `tar -xf dediren-agent-bundle-<version>.tar.xz`; fallback
    `xz -d … && tar -xf …`). Keep it consistent with README.
  - `AgentUsageDocConsistencyTest` (dist-tool) enforces that every `DEDIREN_*`
    token and CalVer string in this file matches source — do not introduce a new
    version string or env var; the extension edit does not touch either, but
    run the dist-tool suite to confirm it stays green.

### Interfaces
- Documented download/unpack commands reference `dediren-agent-bundle-
  <version>.tar.xz`.

### TDD steps
- [ ] (Docs lane — no unit test to fail first.) Make the edits across
      `README.md` and `docs/agent-usage.md`. (`docs/threat-model.md` was
      already updated in Task 3, alongside the release-workflow change that
      triggers it.)
- [ ] `git grep -n 'tar\.gz'` over `README.md`, `docs/agent-usage.md`,
      `docs/threat-model.md` and confirm no stale user-facing `.tar.gz`
      reference remains (historical mentions inside `docs/superpowers/specs` and
      `docs/superpowers/plans` are frozen history — leave them).
- [ ] `./mvnw -pl dist-tool -am test` (sandbox disabled) to confirm
      `AgentUsageDocConsistencyTest` stays green.
- [ ] `git diff --check` for whitespace.
- [ ] Commit (`docs: document the tar.xz stored-jar bundle and its disk trade`).

## Self-Review

### Spec coverage
- I7's two mechanisms are both implemented and pinned: **stored repack**
  (Task 1, asserted by a build-level all-`STORED` jar check in Task 2) and
  **xz archive** (Task 2, asserted by xz magic bytes). The download win is the
  justification; the speed side-benefit is explicitly not counted (Global
  Constraints), matching the follow-up ruling that I9 makes it moot.
- The disk trade (~2.2x `lib/`) is stated in every user-facing install surface
  (Task 4), per the review's honesty requirement.
- Single-artifact recommendation (drop `.tar.gz`) is decided and justified in
  Global Constraints, and carried through build, workflow, and docs.
- Every measured file-that-moves-together is covered: `DistTool`, its two
  format-coupled tests, `release.yml`, `README.md`, `docs/agent-usage.md`,
  `docs/threat-model.md` (release-workflow → threat-model trigger honored).
- No version bump (release-policy); no product/plugin/schema/contract change
  (this is not a contract-family change — unlike I4 — so no schema-id edits).

### Placeholder scan
- No `TODO`/`TBD`/stub left in the plan. `storeRepackJar`/`storeRepackLib`
  signatures and their insertion point in `build(...)` are concrete. Archive
  name, tar flags (`-cJf`/`-xf`), xz magic bytes (`FD 37 7A 58 5A 00`), and the
  exact workflow lines to flip are all named.

### Type consistency checks
- `storeRepackJar(Path)`/`storeRepackLib(Path)` throw `IOException`, consistent
  with the surrounding `DistTool` I/O helpers; `build(...)` already declares
  `throws Exception`.
- `STORED` entries set `size`, `compressedSize`, and `crc` before
  `putNextEntry` — required by `ZipOutputStream` for method `STORED`; the jar
  remains a valid zip the JVM classloader reads identically (functionally
  verified in the challenge).
- Archive-name string is produced in exactly one shape
  (`bundleName(version) + ".tar.xz"`) and consumed identically by
  `build`/`smoke`/`bench`/prune and by the tests and workflow, so no
  extension mismatch can arise.
- `tar -xf`/`tar -xOf`/`tar -tf` autodetect xz on the release runner and this
  environment (GNU tar); the build job installs `xz-utils` defensively so
  `tar -cJf` always resolves the `xz` binary.

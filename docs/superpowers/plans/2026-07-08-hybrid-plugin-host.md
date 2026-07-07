# Hybrid Single-JVM Plugin Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give bundled first-party plugins (generic-graph, elk-layout, render,
archimate-oef, uml-xmi) an in-process fast path inside the CLI JVM so that a
full diagram pipeline costs one JVM boot instead of the ~13–15 spawns of the
documented five-invocation agent flow, while third-party/external plugins keep
the JSON-over-stdio process protocol completely unchanged. Ship the
`dediren pipeline` one-shot subcommand as the user-facing facade
(project → layout → validate-layout → render/export in one CLI invocation).
A persistent daemon mode is explicitly OUT of scope; it is noted as follow-up
at the end of this plan.

**Architecture:** No spec file exists for this work. The authoritative design
source is the challenge review
`docs/superpowers/reviews/2026-07-08-runtime-size-speed-challenge.md` (I9
dark-horse section, I5/I9 follow-up rulings, and the measured baseline), plus
the rulings restated under Global Constraints. The shape is a **hybrid host**:
`core` gains an invoker seam (`PluginInvoker`) with two implementations — the
existing process-backed invoker (extracted from `PluginRunner.runExecutable`)
and a new in-process invoker that dispatches to an `InProcessPluginService`
SPI defined in `contracts` and implemented by each first-party plugin as a
thin delegate to its existing `execute(args, stdin, stdout, stderr[, env])`
method. Everything downstream of the invoker (envelope parsing, envelope/data
schema validation, diagnostics taxonomy, exit-code mapping) stays shared code,
which is what makes envelope semantics bit-identical by construction.
Discovery of in-process implementations is `ServiceLoader` over the CLI
runtime classpath — **no compile-time edge from `core` (or `cli`) to any
plugin module**; the classpath coupling is runtime-scope only (`cli` pom +
dist launcher), mirroring how `dist-tool` already runtime-depends on every
plugin for bundling. The in-process path activates only for manifests
discovered in the trusted bundled plugin directory; every other manifest
(project-local, `DEDIREN_PLUGIN_DIRS`, executable overrides) keeps the
process path, so fault isolation and the threat-model trust boundary survive
exactly where they matter.

**Tech Stack:** Java 21, Maven Wrapper multi-module reactor, `ServiceLoader`
SPI, picocli (CLI), Jackson 3 envelopes, JUnit 5 + AssertJ, appassembler
launchers, dist-tool bundle assembly.

## Global Constraints

- **Design source and closed decisions** (do NOT relitigate; source:
  `docs/superpowers/reviews/2026-07-08-runtime-size-speed-challenge.md`,
  follow-up sections):
  - I9 hybrid host is preferred over I5: in-process fast path for bundled
    first-party plugins; JSON-over-stdio retained for third-party/external
    plugins.
  - The `dediren pipeline` one-shot subcommand is built as part of this plan
    (I9's facade), not standalone.
  - The I5 probe-result cache is a separate standalone plan; this plan
    references it and must not duplicate it. In-process execution makes the
    probe effectively free for first-party plugins; the cache keeps
    independent value for external process-boundary plugins.
  - A persistent daemon mode is out of scope (follow-up only).
- **Envelope parity is the acceptance bar:** for every first-party plugin and
  every operation, the stdout envelope and exit code produced through the
  in-process path must be byte-identical to the process path given identical
  input, args, and (filtered) environment. This is achieved structurally by
  keeping normalization shared, and proven by a compatibility test (Task 7).
- **Dependency direction holds:** plugins never depend on `core`; `core` and
  `cli` never gain compile-scope dependencies on plugin modules. The only new
  edges are (a) plugins → `contracts` SPI interface (already-allowed edge) and
  (b) `cli` → plugins at **runtime scope** for classpath assembly (Task 10).
  The allowed-edge table amendment in `docs/architecture-guidelines.md` and
  the CLAUDE.md architecture-rule amendment are explicit tasks (Task 1).
- `docs/threat-model.md` changes in the same commit as the plugin execution
  boundary change (its Maintenance Rule) — folded into Task 4.
- Measured baseline for evidence claims (bundle 2026.07.8, Temurin 21, warm
  CDS; full evidence in the review doc): CLI boot 80 ms; capability probe
  ~50–80 ms/stage; 3-stage pipeline 1.325 s default / 1.152 s trust mode;
  documented agent flow = 5 CLI invocations ≈ 13–15 JVM spawns per diagram;
  I9 one-shot estimate ~0.5–0.7 s (unmeasured — treat as expectation, not a
  gate).
- Verification lanes (CLAUDE.md `## Verification`): plugin runtime changes →
  `./mvnw -pl core,cli -am test`; per-plugin changes → that plugin's lane;
  distribution changes → `./mvnw test` + `./mvnw -pl dist-tool -am verify
  -Pdist-smoke`; docs-only → `git diff --check`. Run `./mvnw` with the
  sandbox disabled (JUnit `@TempDir` fails on read-only `/tmp` under the
  sandbox).
- Format before every commit: `./mvnw -Pquality spotless:apply` on Java
  changes; the quality gate is `./mvnw -Pquality verify`.
- Files that move together: plugin protocol/runtime changes update manifests
  (none change here — capabilities are identical), runtime capability
  handling, envelope validation, CLI behavior, README notes, and
  compatibility tests together; user-facing command changes update
  `README.md` and `docs/agent-usage.md` in the same change;
  `AgentUsageDocConsistencyTest` (dist-tool) must stay green for every
  `DEDIREN_*` token added to `docs/agent-usage.md`.
- Git policy: direct-main allowed; task-scoped commits; explicit-path staging
  (no `git add -A`); do not commit generated outputs. **No version bump in
  this plan** — version bumps are separate commits governed by
  release-policy.
- No public JSON schema changes are expected in this plan: the pipeline
  command emits existing envelope/result schemas, and the SPI is Java-only
  protocol surface. If a task discovers a schema change is needed, stop and
  re-plan that task.
- Audit gates before calling the plan complete (CLAUDE.md `## Audit Gates`,
  plugin-runtime row): deep `test-quality-audit` over the new runtime
  tests/fixtures; quick `devsecops-audit` over the plugin process boundary
  and dependency posture (Task 12).

## Key design decisions (resolved here, stated once)

1. **Seam placement.** The invoker seam sits exactly at today's private
   `PluginRunner.runExecutable` boundary: an invoker receives
   `(pluginId, executable, args, input, timeout, filteredEnv, workingDir)`
   and returns `(stdout, stderr, exitCode)`. All probe logic, id-mismatch
   checks, capability checks, envelope parsing, and schema validation remain
   above the seam and are shared verbatim by both invokers. Parity is
   therefore structural, and a crashing in-process plugin flows through the
   identical normalization (`DEDIREN_PLUGIN_OUTPUT_INVALID_JSON` /
   `DEDIREN_PLUGIN_PROCESS_FAILED`) as a crashing child process.
2. **SPI mechanism, not compile edges.** The SPI interface
   `InProcessPluginService` lives in `contracts`
   (`dev.dediren.contracts.plugin.spi`): it is the in-process projection of
   the published executable protocol (args, stdin bytes, stdout bytes, exit
   code), i.e. protocol surface, not orchestration — consistent with the
   `contracts` charter. Plugins already may depend on `contracts`. `core`
   discovers implementations via `ServiceLoader` and simply finds none when
   plugin jars are absent (e.g. `core`'s own test JVM), falling back to the
   process path.
3. **Fast-path gate.** The in-process invoker is selected iff ALL hold:
   the manifest was discovered in the trusted bundled plugin directory
   (`LoadedPluginManifest.trusted()`); no `DEDIREN_PLUGIN_<ID>` executable
   override is set (an override is an explicit request for a specific
   executable); `DEDIREN_FORCE_PLUGIN_PROCESS` is not truthy (escape hatch
   for debugging/fault-isolation preference); and a `ServiceLoader`-provided
   implementation with a matching `pluginId()` exists on the classpath.
   Everything else — third-party manifests, project-local dirs,
   `DEDIREN_PLUGIN_DIRS`, overrides — takes the unchanged process path.
4. **Probe semantics in-process.** The capability probe is not skipped for
   the in-process path; it is executed in-process (SPI call with
   `["capabilities"]` args), preserving the full diagnostics taxonomy
   (`DEDIREN_PLUGIN_ID_MISMATCH`, `DEDIREN_PLUGIN_CAPABILITY_*`,
   `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`) at ~zero cost.
   `DEDIREN_TRUST_MANIFEST_CAPABILITIES` keeps its documented behavior
   (skip probe for trusted manifests) on both paths for behavioral parity.
5. **Timeout and crash mapping in-process.** The SPI call runs on a fresh
   daemon worker thread bounded by `PluginRunOptions.timeout()`. On timeout:
   the same `DEDIREN_PLUGIN_TIMEOUT` diagnostic; the worker thread is
   interrupted and abandoned (the one-shot CLI process exits shortly after).
   On any `Throwable`: synthesize exit code 1 with the throwable's
   stack trace as captured stderr, then feed the shared normalization —
   matching what a crashed child process produces. Residual risk (a hard
   JVM-level failure such as OOM in first-party plugin code can take down
   the CLI process) is accepted for same-trust bundled code and recorded in
   the threat model (Task 4).
6. **Environment discipline.** The in-process invoker passes the same
   filtered `allowedEnv` map computed today for the child process; SPI
   implementations must read environment only from that parameter, never
   `System.getenv()` (verified: today only the two export plugin Mains touch
   `System.getenv`, and both already thread an `env` map through `execute`).
   A guard test pins "no `System.getenv` outside `main(String[])`" per
   plugin (Task 5/6).
7. **Working-directory discipline.** Child processes run with cwd = product
   root; an in-process call cannot change the JVM cwd. The SPI signature
   therefore carries `Path workingDirectory` (core passes
   `DedirenPaths.productRoot()`, the exact value it gives
   `ProcessBuilder.directory`). The export plugins — the only ones that
   resolve filesystem paths from env values (`DEDIREN_OEF_SCHEMA_DIR`,
   `DEDIREN_XMI_SCHEMA_PATH`, `DEDIREN_SCHEMA_CACHE_DIR`) — must resolve
   relative values against that parameter, preserving the documented
   "relative values resolve against the product root" behavior
   (docs/agent-usage.md `## Export`). Task 6.
8. **stderr divergence, accepted.** In process mode, third-party library
   writes to the real `System.err` land in captured child stderr (discarded
   on success). In-process, such writes reach the CLI's stderr directly.
   stderr is documented human-only and carries nothing load-bearing, so this
   divergence is accepted and documented; envelope stdout parity is the
   contract.
9. **Pipeline output contract.** `dediren pipeline` prints exactly one
   envelope on stdout: the final stage's envelope, passed through verbatim
   (byte-preserving) — unless `validate-layout` produced quality warnings,
   in which case the final success envelope is re-serialized once with those
   warning diagnostics merged in and status downgraded `ok` → `warning`
   (schema-valid; `CommandEnvelope.warning` exists). Any stage failure
   short-circuits and emits that stage's error envelope verbatim with the
   existing non-zero exit-code taxonomy. Layout-quality *errors* from
   `validate-layout` fail the pipeline with the validate-layout error
   envelope. The render lane always projects render-metadata and passes it
   to render (this matches every per-notation handoff in agent-usage.md; it
   is the facade's contract even where the old smoke flow omitted metadata).
10. **CDS interplay.** The CLI launcher classpath grows to include plugin
    jars (runtime-scope deps → appassembler CLASSPATH), so the runtime-
    generated `cli.jsa` becomes the single archive covering the whole
    pipeline; per-plugin launchers and their archives remain for the stdio
    path and external callers, and only materialize if actually invoked.
    The packaged `lib/` content is unchanged (it is already the deduped
    union of all launcher classpaths), so download size is unaffected.
    Task 10 covers launcher/hermeticity effects and Task 11 documents them.

---

### Task 1: Governance pre-amendment (docs-only)

Record the architecture ruling before any code changes, so the code lands
against already-consistent guidance.

**Files:**
- Modify: `docs/architecture-guidelines.md`
  - §1/§5: amend the "In-process transport initiative — considered and
    closed (2026-07)" paragraph: the initiative is **reopened and decided**
    by the 2026-07-08 runtime challenge follow-up ruling (cite the review
    doc). Record the evidence that satisfies the reopening bar this section
    itself set: the documented agent flow costs 5 CLI invocations ≈ 13–15
    JVM spawns per diagram (~1.9 s), an order of process overhead the
    shipped tiers do not recover; state the hybrid shape (in-process for
    bundled first-party only; stdio for everything else) and what survives
    (fault isolation + trust boundary for external plugins).
  - §2: allowed-edge table — add the `cli` runtime-scope edge to first-party
    plugins ("runtime, for in-process hosting; never compile"), modeled on
    the existing `dist-tool` runtime-edge row; keep "no plugin depends on
    `core`" and "`core` never compile-depends on a plugin" intact and state
    that ServiceLoader over `contracts` SPI is the discovery mechanism.
  - §5 rules: add the fast-path gate (trusted bundled manifests only), the
    `DEDIREN_FORCE_PLUGIN_PROCESS` escape hatch, and the in-process
    timeout/crash mapping decision (design decisions 3–5 above).
  - §12: update the `JsonSupport.objectMapper()` `MS_EXPOSE_REP` row
    rationale — "first-party/same-JVM only" now includes in-process plugin
    execution; the singleton still never crosses a *trust* boundary because
    only same-trust bundled code runs in-process (no plugin mutates the
    mapper today; note that as the standing expectation).
- Modify: `CLAUDE.md`
  - `## Architecture Rules`: amend "First-party plugins are executable
    process-boundary plugins" to state the hybrid: bundled first-party
    plugins additionally expose an in-process SPI over `contracts` and may
    be hosted in the CLI JVM; third-party/external plugins are process-only.
    Keep "they must not depend on `core`".
  - `## Plugin Runtime Rules`: add the fast-path gate summary and that
    envelope semantics are identical across both paths.

**Interfaces:** none (prose only).

**Steps:**
- [ ] Step 1: Failing check — `grep -n "reopening in-process transport" docs/architecture-guidelines.md`
      still describes the initiative as closed with no reference to the
      2026-07-08 ruling.
- [ ] Step 2: Write both document amendments (content above), citing
      `docs/superpowers/reviews/2026-07-08-runtime-size-speed-challenge.md`.
- [ ] Step 3: Re-check — greps for "hybrid", the review-doc filename, and
      `DEDIREN_FORCE_PLUGIN_PROCESS` hit both documents; no stale "must stay
      test-scope" claim remains for the `cli` plugin deps sentence in the
      guidelines (§2 bullet "its plugin dependencies are `test`-scope").
- [ ] Step 4: `git diff --check`; commit (docs-only).

### Task 2: Extract the `PluginInvoker` seam in core (behavior-preserving)

**Files:**
- Create: `core/src/main/java/dev/dediren/core/plugins/PluginInvoker.java`
- Create: `core/src/main/java/dev/dediren/core/plugins/ProcessPluginInvoker.java`
- Modify: `core/src/main/java/dev/dediren/core/plugins/PluginRunner.java`
- Test: `core/src/test/java/dev/dediren/core/plugins/PluginRuntimeTest.java`
  (must stay green unchanged); new
  `core/src/test/java/dev/dediren/core/plugins/ProcessPluginInvokerTest.java`

**Interfaces:**
```java
// package-private seam inside dev.dediren.core.plugins
interface PluginInvoker {
  InvocationOutput invoke(InvocationRequest request) throws PluginExecutionException;
}

record InvocationRequest(
    String pluginId,
    java.nio.file.Path executable,      // resolved manifest executable (unused in-process)
    java.util.List<String> args,
    String input,
    java.time.Duration timeout,
    java.util.Map<String, String> env,  // the filtered allowedEnv map
    java.nio.file.Path workingDirectory) {}

record InvocationOutput(String stdout, String stderr, int exitCode) {}
// (promotion of today's private PluginRunner.ProcessOutput)
```
`ProcessPluginInvoker` is the current `runExecutable` + `readAll` + timeout
mapping, moved verbatim. `PluginRunner` calls the invoker at the three
current `runExecutable` call sites (trust-mode run, capabilities probe,
command run); nothing else moves.

**Steps:**
- [ ] Step 1: Write the failing test `ProcessPluginInvokerTest` — drive the
      invoker directly against the existing `testbeds/plugin-runtime`
      executables/scripts (same technique as `PluginRuntimeTest`): success
      output round-trips stdout/stderr/exit; timeout maps to
      `DEDIREN_PLUGIN_TIMEOUT`; I/O failure maps to `DEDIREN_PLUGIN_IO_ERROR`.
- [ ] Step 2: Run it to fail (class does not exist):
      `./mvnw -pl core -am test -Dtest=ProcessPluginInvokerTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Step 3: Implement the seam extraction; `PluginRunner` behavior
      byte-identical (pure refactor).
- [ ] Step 4: Run to pass; full `./mvnw -pl core,cli -am test` green
      (existing `PluginRuntimeTest` proves no behavior change).
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; commit.

### Task 3: `InProcessPluginService` SPI in contracts

**Files:**
- Create: `contracts/src/main/java/dev/dediren/contracts/plugin/spi/InProcessPluginService.java`
- Test: `contracts/src/test/java/dev/dediren/contracts/plugin/spi/InProcessPluginServiceTest.java`

**Interfaces:**
```java
package dev.dediren.contracts.plugin.spi;

/**
 * In-process projection of the published plugin executable protocol: same
 * args vector, stdin bytes, stdout/stderr byte streams, and exit code as the
 * process form. Implementations are discovered via ServiceLoader and MUST
 * read environment only from the supplied map and resolve relative
 * filesystem paths only against workingDirectory (never the JVM cwd),
 * mirroring the child-process contract (allowlisted env, cwd = product root).
 */
public interface InProcessPluginService {
  String pluginId();

  int run(
      String[] args,
      java.io.InputStream stdin,
      java.io.PrintStream stdout,
      java.io.PrintStream stderr,
      java.util.Map<String, String> env,
      java.nio.file.Path workingDirectory)
      throws Exception;
}
```
Design note (state in the Javadoc): this interface is protocol surface — the
typed equivalent of the executable wire contract — not orchestration; that is
why it belongs in `contracts` despite the records-only norm (`architecture-
guidelines.md` §2 "the schema is the abstraction" note is about data records;
the SPI mirrors the *executable* contract, which has no JSON schema).

**Steps:**
- [ ] Step 1: Write the failing test — a test-local implementation registered
      under `contracts/src/test/resources/META-INF/services/...` is found by
      `ServiceLoader.load(InProcessPluginService.class)` and its `run`
      round-trips args/stdin/stdout/exit code.
- [ ] Step 2: Run to fail; implement the interface; run to pass:
      `./mvnw -pl contracts -am test`.
- [ ] Step 3: `./mvnw -Pquality spotless:apply`; commit.

### Task 4: In-process invoker + fast-path selection in core (+ threat model)

**Files:**
- Create: `core/src/main/java/dev/dediren/core/plugins/InProcessPluginInvoker.java`
- Create: `core/src/main/java/dev/dediren/core/plugins/InProcessPlugins.java`
  (ServiceLoader lookup, loaded once, keyed by `pluginId()`)
- Modify: `core/src/main/java/dev/dediren/core/plugins/PluginRunner.java`
  (invoker selection per design decision 3; probe runs through the selected
  invoker per decision 4)
- Modify: `docs/threat-model.md` — same commit (Maintenance Rule): under
  "Plugin process boundary", describe the hybrid: trusted bundled manifests
  may execute in-process inside the CLI JVM (same filtered env allowlist,
  same product-root working directory passed explicitly, same envelope
  validation); untrusted/project/user-dir manifests and any
  `DEDIREN_PLUGIN_<ID>` override always execute as child processes;
  `DEDIREN_FORCE_PLUGIN_PROCESS=1` forces the process path. Add the
  attacker-goal table note: fault isolation for *first-party in-process*
  plugins is reduced by design (same-trust code; residual risk: a hard
  crash/OOM in bundled plugin code takes the CLI process down with a
  non-structured failure); the trust boundary against *foreign* plugins is
  unchanged.
- Test: `core/src/test/java/dev/dediren/core/plugins/InProcessPluginInvokerTest.java`,
  extend `PluginRuntimeTest` for selection; test-scope fake SPI impl in
  `core/src/test/java/...` + `core/src/test/resources/META-INF/services/dev.dediren.contracts.plugin.spi.InProcessPluginService`

**Interfaces:**
```java
// selection, inside PluginRunner (package-private static)
static PluginInvoker selectInvoker(
    LoadedPluginManifest loaded, PluginRunOptions options) {
  // in-process iff loaded.trusted()
  //   && executable override DEDIREN_PLUGIN_<ID> absent
  //   && !forcePluginProcess(options)   // DEDIREN_FORCE_PLUGIN_PROCESS
  //   && InProcessPlugins.lookup(loaded.manifest().id()).isPresent()
  // else ProcessPluginInvoker
}
```
`InProcessPluginInvoker.invoke` builds a `ByteArrayInputStream` over
`request.input()` UTF-8 bytes and UTF-8 `PrintStream`s over capture buffers,
runs the SPI on a fresh daemon thread with `request.timeout()`, and returns
`InvocationOutput(stdoutBytes, stderrBytes, exitCode)`; timeout → the shared
`DEDIREN_PLUGIN_TIMEOUT` exception; any `Throwable` → exit 1 with the stack
trace as stderr (decision 5). The executable-exists check stays on the
process path only (the in-process path never touches the file; document in
Javadoc).

**Steps:**
- [ ] Step 1: Write failing `InProcessPluginInvokerTest` against the fake SPI
      impl: success envelope passthrough byte-identical; plugin error
      envelope preserved with non-zero exit; timeout →
      `DEDIREN_PLUGIN_TIMEOUT`; `Throwable` → normalization yields the same
      diagnostic a crashed child yields (empty stdout →
      `DEDIREN_PLUGIN_OUTPUT_INVALID_JSON`); only the filtered env map is
      visible to the SPI; `workingDirectory` equals the product root;
      capabilities probe runs in-process and `DEDIREN_PLUGIN_ID_MISMATCH`
      still fires on a lying fake.
- [ ] Step 2: Write failing selection tests in `PluginRuntimeTest` using
      `PluginRegistry.fromDirs(dirs, trustedDirs)`: trusted manifest + fake
      SPI → in-process (assert via a fake-side marker, e.g. the fake records
      its invocation thread/JVM); untrusted manifest with same id → process
      path; `DEDIREN_PLUGIN_<ID>` override → process path;
      `DEDIREN_FORCE_PLUGIN_PROCESS=1` (via candidateEnv) → process path;
      no SPI on classpath for id → process path.
- [ ] Step 3: Run to fail; implement invoker + lookup + selection.
- [ ] Step 4: Run to pass: `./mvnw -pl core,cli -am test`.
- [ ] Step 5: Update `docs/threat-model.md` (content above) in the same
      commit; `./mvnw -Pquality spotless:apply`; `git diff --check`; commit.

### Task 5: SPI implementations — generic-graph, elk-layout, render

**Files:**
- Create: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/InProcessGenericGraphPlugin.java`
  + `plugins/generic-graph/src/main/resources/META-INF/services/dev.dediren.contracts.plugin.spi.InProcessPluginService`
- Create: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/InProcessElkLayoutPlugin.java`
  + matching service registration resource
- Create: `plugins/render/src/main/java/dev/dediren/plugins/render/InProcessRenderPlugin.java`
  + matching service registration resource
- Modify: each plugin's `Main.java` — widen the private
  `execute(String[], InputStream, PrintStream, PrintStream)` to
  package-private (pure visibility change) so the SPI class delegates to it
- Test: per plugin, `InProcess<Name>PluginTest` asserting SPI output is
  byte-identical to `Main.executeForTesting` for the same args/stdin
  (success, plugin-error envelope, and `capabilities`), and a guard test
  asserting the plugin main source tree contains no `System.getenv` and no
  `System.out`/`System.err` reference outside `main(String[])` (simple
  source-scan test, mirroring the repo's doc-consistency test style)

**Interfaces:**
```java
public final class InProcessElkLayoutPlugin implements InProcessPluginService {
  @Override public String pluginId() { return "elk-layout"; }
  @Override public int run(String[] args, InputStream stdin, PrintStream stdout,
      PrintStream stderr, Map<String, String> env, Path workingDirectory)
      throws Exception {
    return Main.execute(args, stdin, stdout, stderr); // env/cwd unused: plugin touches neither
  }
}
```
(generic-graph and render identical in shape; `pluginId()` returns
`"generic-graph"` / `"render"` matching the manifest ids in
`fixtures/plugins/`.)

**Steps:**
- [ ] Step 1: Write the three failing tests (SPI class absent).
- [ ] Step 2: Run to fail:
      `./mvnw -pl plugins/generic-graph,plugins/elk-layout,plugins/render -am test -Dtest='InProcess*' -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Step 3: Implement the delegates + service registrations + visibility
      widenings.
- [ ] Step 4: Run to pass; then the owning lanes:
      `./mvnw -pl plugins/elk-layout -am test` and
      `./mvnw -pl plugins/render,cli -am test`.
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; commit.

### Task 6: SPI implementations — export plugins + workingDirectory-relative env paths

**Files:**
- Create: `plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/InProcessArchimateOefPlugin.java`
  (+ service registration resource); `pluginId()` = `"archimate-oef"`
- Create: `plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/InProcessUmlXmiPlugin.java`
  (+ service registration resource); `pluginId()` = `"uml-xmi"`
- Modify: both `Main.java` — widen `execute(String[], InputStream,
  PrintStream, PrintStream, Map<String,String>)` to package-private; thread a
  `Path workingDirectory` parameter through `execute` (with `main` passing
  `Path.of("").toAbsolutePath()`, i.e. the process cwd — identical behavior
  for the process path, since core sets the child cwd to the product root)
- Modify: the env-path resolution sites in
  `plugins/archimate-oef-export/.../Main.java` (`DEDIREN_OEF_SCHEMA_DIR`,
  `DEDIREN_SCHEMA_CACHE_DIR`) and
  `plugins/uml-xmi-export/.../schema/SchemaValidation.java`
  (`DEDIREN_XMI_SCHEMA_PATH`, `DEDIREN_SCHEMA_CACHE_DIR`): resolve relative
  values against the threaded `workingDirectory` instead of the bare
  `Path.of(value)`
- Test: per plugin, `InProcess<Name>PluginTest` (parity with
  `executeForTesting` for `capabilities`, a successful export using the
  offline schema-env technique already used in
  `cli/src/test/.../CliLayoutRenderCommandTest.envWithOefSchemas()`, and a
  plugin-error envelope case), a unit test pinning relative-env-path
  resolution against a supplied `workingDirectory`, and the same
  no-`System.getenv`/`System.out`-outside-`main` guard as Task 5

**Interfaces:**
```java
public final class InProcessUmlXmiPlugin implements InProcessPluginService {
  @Override public String pluginId() { return "uml-xmi"; }
  @Override public int run(String[] args, InputStream stdin, PrintStream stdout,
      PrintStream stderr, Map<String, String> env, Path workingDirectory)
      throws Exception {
    return Main.execute(args, stdin, stdout, stderr, env, workingDirectory);
  }
}
```

**Steps:**
- [ ] Step 1: Write the failing tests (SPI classes and `workingDirectory`
      parameter absent), including the resolution unit test: a relative
      `DEDIREN_OEF_SCHEMA_DIR=schemas-oef` with `workingDirectory=/x/y`
      resolves to `/x/y/schemas-oef` regardless of the JVM cwd.
- [ ] Step 2: Run to fail.
- [ ] Step 3: Implement delegates, registrations, and the `workingDirectory`
      threading; keep all existing diagnostics
      (`DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` etc.) untouched.
- [ ] Step 4: Run to pass; owning lanes:
      `./mvnw -pl plugins/archimate-oef-export,cli -am test` and
      `./mvnw -pl plugins/uml-xmi-export,cli -am test`.
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; commit.

### Task 7: Cross-invoker envelope-parity compatibility test (all five plugins)

**Files:**
- Create: `cli/src/test/java/dev/dediren/cli/InProcessParityTest.java`
- Test fixtures: reuse existing `fixtures/source/*`,
  `fixtures/render-policy/default-svg.json` & `uml-svg.json`,
  `fixtures/export-policy/*`, `fixtures/layout-result/*` (no new fixtures
  expected; add only if a gap appears)

**Interfaces:** test-only. Technique:
- The cli test classpath already contains every plugin (test-scope deps), so
  `ServiceLoader` finds all five SPI impls in the test JVM.
- Build a synthetic trusted bundle root in `@TempDir`: copy `schemas/` and
  `fixtures/plugins/*.manifest.json` into `<temp>/schemas` and
  `<temp>/plugins`, then set the `dediren.bundle.root` system property to
  `<temp>` for the in-process leg (restore in `finally`); this makes
  `PluginRegistry.bundled` treat those manifests as trusted, activating the
  fast path. For the process leg, run the same operation with
  `DEDIREN_FORCE_PLUGIN_PROCESS=1` in candidateEnv plus the existing
  script-wrapper `pluginEnv` technique from `CliLayoutRenderCommandTest`
  (manifest executable resolution requires a real executable for the process
  leg — place the wrapper scripts where the synthetic manifests point, or
  use `DEDIREN_PLUGIN_<ID>` overrides for the process leg only).

**Steps:**
- [ ] Step 1: Write the failing parity matrix — for each of:
      generic-graph `validate --profile archimate|uml`, generic-graph
      `project --target layout-request|render-metadata`, elk-layout
      `layout`, render `render` (with and without metadata), archimate-oef
      `export` (offline schema dir), uml-xmi `export`, and every plugin's
      `capabilities` — execute the operation through `CoreCommands` once per
      leg and assert `stdout` byte-identical and exit codes equal. Include
      one error-parity row per plugin family (e.g. an invalid source that
      yields a plugin error envelope).
- [ ] Step 2: Run to fail (in-process leg not yet selected or byte
      mismatches surface real gaps — fix gaps in the owning task's code, not
      by weakening the assertion to JSON-equality; byte-identical is the
      bar).
- [ ] Step 3: Iterate until green: `./mvnw -pl cli -am test -Dtest=InProcessParityTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Step 4: Full plugin-runtime lane `./mvnw -pl core,cli -am test`.
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; commit.

### Task 8: Pipeline orchestration in core

**Files:**
- Create: `core/src/main/java/dev/dediren/core/commands/PipelineCommand.java`
- Create: `core/src/main/java/dev/dediren/core/commands/PipelineRequest.java`
- Test: `core/src/test/java/dev/dediren/core/commands/PipelineCommandTest.java`
  (uses the Task 4 fake SPI plugin and/or the plugin-runtime testbed to
  script per-stage outcomes without real plugins)

**Interfaces:**
```java
public record PipelineRequest(
    String sourcePlugin,          // e.g. "generic-graph"
    String view,
    String sourceText,
    java.nio.file.Path sourceBaseDir,
    String layoutPlugin,          // default "elk-layout"
    String renderPlugin,          // render lane; null in export lane
    String exportPlugin,          // export lane; null in render lane
    String policyText,            // render or export policy JSON
    java.nio.file.Path layoutOut, // optional: write layout-result envelope
    java.util.Map<String, String> env) {}

public final class PipelineCommand {
  // project(layout-request) -> layout -> validate-layout
  //   -> render lane: project(render-metadata) -> render
  //   -> export lane: export
  public static PluginRunOutcome run(PipelineRequest request)
      throws PluginExecutionException;
}
```
Behavior (design decision 9): source validation happens inside the
projection stage (same `SourceValidator` path as the standalone `validate`
command, which the facade thereby covers); stage error envelopes pass
through verbatim with their exit codes; `validate-layout` errors fail the
pipeline; quality warnings merge into the final success envelope with a
status downgrade to `warning`; `layoutOut`, when set, writes the layout
stage's stdout envelope to the given path (so agents can chain a later
`export` or inspect geometry) — write failures surface as
`DEDIREN_COMMAND_INPUT_INVALID`-family diagnostics, not stderr.

**Steps:**
- [ ] Step 1: Write failing `PipelineCommandTest`: happy render lane
      (final envelope verbatim); happy export lane; layout stage error
      short-circuits with the layout error envelope + its exit code;
      validate-layout warning merged (status `warning`, quality diagnostics
      present, render `data` untouched); validate-layout error fails;
      `layoutOut` file contains the layout envelope; no-warning path is
      byte-verbatim (compare against the final stage's raw stdout).
- [ ] Step 2: Run to fail; implement.
- [ ] Step 3: Run to pass: `./mvnw -pl core -am test`.
- [ ] Step 4: `./mvnw -Pquality spotless:apply`; commit.

### Task 9: `dediren pipeline` CLI subcommand + user docs

**Files:**
- Modify: `cli/src/main/java/dev/dediren/cli/Main.java` — add
  `PipelineCommand` subcommand (thin: parse options, read inputs with the
  existing `readInput`/`readFile` helpers, call
  `core` `PipelineCommand.run`, print with the existing
  `writePluginOutcome`/`writePluginError` helpers)
- Modify: `README.md` — pipeline one-shot example in the workflow section
- Modify: `docs/agent-usage.md` — `## Fast Path` gains the pipeline
  invocation as the primary flow (keep the stage-by-stage flow as the
  fallback/decomposed form); add a pipeline example to
  `## Bundle Smoke Workflow`-adjacent content; document `--layout-out` for
  the export handoff
- Test: `cli/src/test/java/dev/dediren/cli/CliPipelineCommandTest.java`;
  `dist-tool` `AgentUsageDocConsistencyTest` stays green

**Interfaces (CLI options):**
```
dediren pipeline
  --plugin <id>            source/projection plugin (required)
  --view <id>              view to project (required)
  --input <path>           source model JSON (default: stdin)
  --layout-plugin <id>     default: elk-layout
  --render-policy <path>   render lane (mutually exclusive with export lane)
  --render-plugin <id>     default: render
  --export-policy <path>   export lane
  --export-plugin <id>     required with --export-policy
  --layout-out <path>      optional layout-result envelope file
```
Exactly one of `--render-policy` / `--export-policy` is required; violations
produce a usage-error envelope (`DiagnosticCode` usage-error family, same
pattern as `validate --plugin/--profile` pairing errors).

**Steps:**
- [ ] Step 1: Write failing `CliPipelineCommandTest` e2e rows (in-process
      path via the Task 7 synthetic-bundle-root technique): archimate render
      lane from `fixtures/source/valid-pipeline-archimate.json` yields a
      render-result envelope with an SVG artifact; uml sequence render lane
      (metadata auto-projected) matches the artifacts of the manual
      five-stage flow run in the same test; oef + xmi export lanes yield
      export envelopes; a failing layout input short-circuits with the
      layout stage's error envelope and non-zero exit; lane-option
      validation errors.
- [ ] Step 2: Run to fail; implement the subcommand.
- [ ] Step 3: Run to pass: `./mvnw -pl core,cli -am test`.
- [ ] Step 4: Update `README.md` + `docs/agent-usage.md` in this same change
      (files-that-move-together rule for user-facing commands); run
      `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; `git diff --check`; commit.

### Task 10: Distribution wiring — CLI runtime classpath, hermeticity, dist smoke

**Files:**
- Modify: `cli/pom.xml` — flip the five first-party plugin dependencies from
  `test` scope to `runtime` scope (runtime jars appear on both the
  appassembler CLASSPATH and the test classpath, so existing tests keep
  working; no compile-scope leak — verify no `cli` main source imports a
  plugin package)
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java`
  and/or `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`
  — only where assertions pin the CLI launcher's declared classpath or the
  per-launcher `lib/` expectations; packaged `lib/` content is expected to
  be unchanged (already the deduped union)
- Modify: dist smoke coverage (the `-Pdist-smoke` path in `dist-tool`) — add
  a `dediren pipeline` render-lane smoke against the packaged bundle, plus a
  `DEDIREN_FORCE_PLUGIN_PROCESS=1` variant proving the stdio path still
  works end-to-end in the bundle
- Test: `DistModuleTest`, dist-smoke

**Interfaces:** none new. CDS notes to verify (design decision 10): the CLI
launcher keeps `EXPECTED_LAUNCHER_FLAGS` and its
`-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=$DEDIREN_CDS_DIR/...`
wiring untouched; the archive simply covers more classes. Per-plugin
launchers remain packaged and asserted exactly as today.

**Steps:**
- [ ] Step 1: Write/adjust the failing dist assertions: `DistModuleTest`
      expects the CLI launcher CLASSPATH to include the five plugin jars
      (and their transitives); dist-smoke gains the pipeline invocation
      rows.
- [ ] Step 2: Run to fail: `./mvnw -pl dist-tool -am verify -Pdist-smoke`.
- [ ] Step 3: Flip the pom scopes; fix any hermeticity-check fallout
      (packaged `lib/` must still exactly equal the union of declared
      launcher classpaths).
- [ ] Step 4: Run to pass: `./mvnw test` then
      `./mvnw -pl dist-tool -am verify -Pdist-smoke`.
- [ ] Step 5: Evidence (non-gating): using the existing `Bench` tool or
      `time` on the packaged bundle, record warm `dediren pipeline` wall
      time vs the five-invocation flow in the task handoff (expectation from
      the review doc: ~0.5–0.7 s vs ~1.9 s; do not gate the build on it).
- [ ] Step 6: `./mvnw -Pquality spotless:apply`; `git diff --check`; commit.

### Task 11: Docs finishing pass — agent flow, env tokens, CDS guidance

**Files:**
- Modify: `docs/agent-usage.md` — `## Plugin Environment`: document
  `DEDIREN_FORCE_PLUGIN_PROCESS` (forces the stdio child-process path for
  bundled first-party plugins; default off) and note that
  `DEDIREN_PLUGIN_<ID>` overrides always take the process path; note the
  relative-env-path rule is unchanged (resolved against the product root on
  both paths). Update the CDS seeding guidance: with `pipeline`, the CLI
  archive is seeded by the whole workload in one run; per-plugin archives
  only materialize if the stdio path is used.
- Modify: `README.md` — runtime behavior note: bundled first-party plugins
  run in-process inside the CLI JVM; third-party plugins always run as
  subprocesses; pointer to the threat model.
- Test: `AgentUsageDocConsistencyTest` (every `DEDIREN_*` token must exist
  in source — `DEDIREN_FORCE_PLUGIN_PROCESS` exists in `core` since Task 4);
  `git diff --check`.

**Steps:**
- [ ] Step 1: Failing check — grep `docs/agent-usage.md` for
      `DEDIREN_FORCE_PLUGIN_PROCESS` (absent) and for stale five-invocation
      "Fast Path" phrasing left after Task 9.
- [ ] Step 2: Write the doc updates; keep agent-usage bundle-self-contained
      and command-oriented.
- [ ] Step 3: `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false`;
      `git diff --check`; commit.

### Task 12: Final verification + audit gates

**Files:** none (verification only; fixes loop back into the owning task).

**Steps:**
- [ ] Step 1: Full suite: `./mvnw test` (sandbox disabled).
- [ ] Step 2: Quality gate: `./mvnw -Pquality verify`.
- [ ] Step 3: Distribution lane: `./mvnw -pl dist-tool -am verify -Pdist-smoke`;
      `git diff --check`.
- [ ] Step 4: Deep `souroldgeezer-audit:test-quality-audit` over the new
      runtime tests/fixtures (invoker tests, parity matrix, pipeline tests,
      dist smoke). Fix blocks; fix or explicitly accept warn/info in the
      handoff; rerun affected checks.
- [ ] Step 5: Quick `souroldgeezer-audit:devsecops-audit` over the plugin
      process boundary and dependency posture (scope flips in `cli/pom.xml`,
      ServiceLoader surface, threat-model consistency). Same fix/accept
      rule.
- [ ] Step 6: Stale-doc sweep: grep README/agent-usage/threat-model/
      architecture-guidelines for contradictions with the shipped behavior
      (e.g. any remaining "plugins are always separate processes" phrasing).
- [ ] Step 7: Record in the handoff: measured pipeline timing evidence
      (Task 10 Step 5), accepted audit findings, and the explicit follow-up
      note below.

## Explicit follow-ups (out of scope)

- **Persistent daemon mode** (review-doc estimate ~0.1–0.3 s per subsequent
  diagram): a long-lived host process serving pipeline requests. Requires
  its own design (lifecycle, socket/stdio protocol, idle shutdown,
  threat-model treatment) — do not bolt onto this plan.
- **Probe-result cache** for external process-boundary plugins: separate
  standalone plan from the same challenge follow-up (I5 survivor). It should
  layer on the Task 2 invoker seam (cache at the process-probe call site);
  coordinate rebase order if both plans are in flight.
- Single shared CDS archive tuning / retiring per-plugin archives once agent
  flows migrate to `pipeline` (I2 honorable-mention territory).

## Self-Review

- [ ] **Spec coverage:** every ruling from the review doc's follow-up
      sections maps to a task — hybrid host (Tasks 2–7), first-party
      in-process set covers all five plugins (Tasks 5–6), stdio protocol
      untouched for third-party (Task 4 gate + threat model), `pipeline`
      facade built inside I9 (Tasks 8–9), probe cache referenced not
      duplicated (follow-ups), daemon explicitly out of scope (follow-ups),
      no version bump anywhere in the plan.
- [ ] **Constraint coverage:** envelope bit-parity has a dedicated proving
      test (Task 7) and a structural argument (shared normalization,
      decision 1); dependency direction preserved with the guideline/CLAUDE
      amendment task landed first (Task 1); threat model rides the boundary
      change commit (Task 4); README/agent-usage ride the command change
      (Task 9); `AgentUsageDocConsistencyTest` named wherever `DEDIREN_*`
      tokens move (Tasks 9, 11); CDS/launcher effects addressed (Task 10).
- [ ] **Placeholder scan:** no TBD/TODO/???/"fill in later" markers remain in
      this plan; every env-var name (`DEDIREN_FORCE_PLUGIN_PROCESS`), SPI
      package (`dev.dediren.contracts.plugin.spi`), plugin id
      (`generic-graph`, `elk-layout`, `render`, `archimate-oef`, `uml-xmi`),
      and file path is concrete and was checked against the live tree.
- [ ] **Type consistency checks:** `InvocationRequest`/`InvocationOutput`
      match today's private `ProcessOutput(stdout, stderr, exitCode)` triple;
      `InProcessPluginService.run` signature matches the widest existing
      plugin `execute` shape (uml-xmi/archimate-oef take `env`; the plan
      threads `workingDirectory` through both export `execute`s in Task 6);
      `PipelineCommand.run` returns the existing `PluginRunOutcome(stdout,
      exitCode)`; manifest ids used by `pluginId()` match
      `fixtures/plugins/*.manifest.json` exactly; exit-code and diagnostic
      names (`DEDIREN_PLUGIN_TIMEOUT`, `DEDIREN_PLUGIN_OUTPUT_INVALID_JSON`,
      `DEDIREN_PLUGIN_ID_MISMATCH`, `DEDIREN_PLUGIN_PROCESS_FAILED`) exist in
      `DiagnosticCode` today.

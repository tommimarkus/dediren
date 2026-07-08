# Monolithic Compiler Restructure — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking. Do not batch tasks; land each behind
> its own commit(s) with explicit-path staging. Subagents must not run Maven
> in parallel (shared `target/` dirs race) — edit waves, then one build agent.

**Goal:** Delete the plugin process protocol and make dediren a single-JVM
diagram compiler. The five first-party plugins (generic-graph, elk-layout,
render, archimate-oef-export, uml-xmi-export) become ordinary Maven library
modules behind small typed engine interfaces; `core` orchestrates them in
memory using the existing `contracts` records; a new one-shot `dediren build`
command produces all views and all requested artifact kinds in one invocation
(projected ~0.3–0.5 s vs ~1.9 s / 13–15 JVM spawns for the documented
five-invocation flow). Existing per-stage subcommands (`validate`, `project`,
`layout`, `validate-layout`, `render`, `export`) keep working throughout and
after the transition — stage artifacts remain the public, schema'd
debug/interop surface. Manifests, capability probes, plugin discovery, and
trust machinery are deleted; the bundle ships one launcher and one CDS
archive. This plan is **Phase 1 (monolith restructure) only**: no typed IR, no
daemon/serve mode, no build cache — see "Follow-up plans".

**Architecture:** The approved design is the "Refined target vision" section
below (brainstormed 2026-07-08, approved by the owner); the authoritative
measurements and rulings are in
`docs/superpowers/reviews/2026-07-08-runtime-size-speed-challenge.md` (I9
dark-horse follow-up). This plan supersedes the reviewed I9 hybrid plan
(`2026-07-08-hybrid-plugin-host.md`) and kills the probe-cache plan
(`2026-07-08-plugin-probe-cache.md`); Task 1 stamps both. It revises the
product boundary set by `docs/superpowers/specs/2026-05-08-dediren-design.md`
("plugin posture: external executable plugins"), so Task 1 also lands the
spec amendment. The migration is a strangler: engines are extracted behind an
`engine-api` seam while the process protocol keeps working (Tasks 2–7), then
the protocol is deleted in two coupled cutover tasks (8–9), then the tree and
enforcement are finalized (10–12).

**Tech Stack:** Java 21, Maven Wrapper multi-module reactor, picocli (CLI),
Jackson 3 (`tools.jackson`) via `contracts` `JsonSupport`, Eclipse ELK,
JUnit 6 (Jupiter API; root pom pins `junit.version` 6.1.1) + AssertJ,
ArchUnit (dist-tool `ArchitectureRulesTest`), appassembler
launcher (cli only, post-cutover), dist-tool bundle assembly.

---

## Refined target vision (approved design anchor)

Reframe: not "plugins merged into one JVM" but **a diagram compiler with a
service mode**. The five-invocation pipeline is compiler phases that were
exposed as separate commands only because process handoffs forced it.

Module shape (compiler roles; items marked *Phase 1* are built by this plan,
the rest are the target vocabulary for follow-up plans):

- `model` — public schemas + typed source model + validation (contract surface
  unchanged: JSON in, envelopes/diagnostics out). *Phase 1: stays `contracts`
  + `core` source validation, unrenamed.*
- `semantics-archimate` / `semantics-uml` / `semantics-graph` — notation front
  ends: legality rules + projection. *Phase 1: stays one `generic-graph`
  engine consuming the `archimate`/`uml` notation cores; the per-notation
  split belongs to Plan B.*
- `ir` — typed scene graph (sealed interfaces, records, exhaustive pattern
  matching, JSON-Pointer provenance to the source model). *Deferred to Plan B;
  Phase 1 keeps the existing `contracts` records as the inter-stage types.*
- `layout` — ELK as a lowering pass + layout-quality validation (ELK-first
  rule unchanged). *Phase 1: `elk-layout` engine + `core` quality checks.*
- `emit-svg` / `emit-oef` / `emit-xmi` — backends. *Phase 1: `render`,
  `archimate-oef-export`, `uml-xmi-export` engines.*
- `driver` — orchestration + diagnostics bus. *Phase 1: `core` gains the
  `build` driver. Content-addressed build cache, incremental re-layout, and
  `watch` are deferred to Plan C.*
- `cli` (+ `serve`). *Phase 1: `cli` only; `serve`/MCP is Plan C.*

Why the IR is the prize (Plan B rationale, recorded here so Phase 1 does not
accidentally foreclose it):

1. Cross-phase diagnostics with source provenance: a render-stage defect can
   point the agent at the exact source element to repair — impossible today
   because each stage only sees its own input.
2. The recorded sequence-diagram defect class (empty bands, arrowhead
   anchoring, fragment chrome) becomes exhaustiveness errors or IR invariant
   checks ("every message anchors on a lifeline stem") instead of post-hoc
   SVG-audit findings; phase-level property tests replace defect-hiding
   golden fixtures.

Execution modes (agent-first):

- `dediren build model.json --out dist/` — one invocation, all views, all
  requested artifact kinds. *Phase 1 builds views sequentially and
  deterministically; virtual-thread parallelism is a follow-up optimization.*
- Stage artifacts demoted to opt-in debug outputs (`--emit layout-request`,
  compiler-style); they stay public and schema'd — the pipe-level extension
  surface. Existing per-stage subcommands remain the decomposed form.
- `dediren serve` — daemon done right, plausibly as an MCP server
  (validate/build/diagnose as tools); warm rebuilds ~50–150 ms. *Plan C.*

Extensibility as an explicit bet (the esbuild bet): curated in-tree
notations + declarative styling/policy + pipe-level composition. No runtime
plugin protocol.

Distribution consequences: one build target re-opens native-image (single
binary, ~10–30 ms startup, no JDK prerequisite; EMF/Jackson reflection config
is one hard problem instead of six) or a single JDK-24 AOT cache (~40 ms
boot) — both remain exploration notes, not Phase 1 work. With stored-jar
packaging (I7, separate plan): single ~7 M archive. Projected one-shot full
build ~0.3–0.5 s (ELK compute dominates once spawns and serialization are
gone) vs ~1.9 s / 13–15 spawns today — the challenge review estimated
~0.5–0.7 s for the one-shot host, and the monolith additionally removes
protocol serialization, hence this plan's tighter 0.3–0.5 s expectation (kept
as the plan's number).

Costs / spec-level reversals (all owned by this plan): the founding
plugin-boundary identity (2026-05-08 design spec, amended in Task 1), the
third-party plugin roadmap (`2026-07-03-third-party-plugin-contract.md`,
shipped and now reversed — `docs/plugin-authoring.md` retires), plugin
manifests/discovery/probe/trust machinery (deleted), and the threat model
shrinks to schema-cache + XML hardening + SVG escaping + release chain. The
probe-cache plan dies; the I9 hybrid plan is superseded. I4 (PNG/raster
removal) **already landed** (release 2026.07.13; render-policy.schema.v2 /
render-result.schema.v4) — no PNG migration content belongs in this plan. I7
(stored-jar distribution) composes unchanged and gets simpler (one launcher).

Decision question, answered: dediren is a compiler with a service mode, not a
pipeline of separate tools. The compiler answer dominates on speed, size,
diagnostics quality, and testability; it costs the plugin identity and its
contract surface, and this plan pays that cost deliberately.

---

## Global Constraints

- **Closed decisions (do not relitigate):** monolith over hybrid (owner
  approval of the vision above); Phase 1 scope = protocol deletion + library
  engines + in-memory driver + `dediren build` + single launcher/CDS +
  governance; typed IR, serve/MCP/cache/watch, and native-image are follow-up
  plans only; per-stage subcommands and their stage artifacts stay public.
  Owner-ratified on 2026-07-08: explicit `build` lane flags (no policy
  bundle/profiles); OEF one-policy-per-view stays a documented Phase-1
  limitation; no `capabilities` introspection command in Phase 1 (returns
  with serve/MCP); package rename stays recorded §12 debt (Task 10 moves
  directories/artifactIds only).
- **Public contract churn is minimized.** CLI stdout envelopes are unchanged:
  same envelope schema, same statuses, same diagnostics values, same exit
  codes. The parity gate (Task 5) is JSON-value equality + schema validity +
  exit-code equality per stage command; byte-identity is the expectation
  (same `JsonSupport` mapper, same `CommandEnvelope` records) and any byte
  divergence must be investigated as a probable mapping bug before being
  accepted. Source models keep `required_plugins[]` accepted exactly as today
  (decision 8). Removal of the plugin-surface schemas
  (`plugin-manifest.schema.json`, `runtime-capability.schema.json`) and their
  `contracts` records is **deferred** to the contract-cleanup follow-up; the
  files stay in `schemas/` and keep shipping, orphaned but harmless. New
  public surface added by this plan: `build-result.schema.v1` +
  `dediren build` (Task 6–7) and the bundle descriptor bump
  `dediren-bundle.schema.v1` → `v2` (Task 9).
- **Strangler safety:** the plugin process protocol keeps working, fully
  tested, until Task 8. Every task lands green and independently shippable —
  no half-states. Tasks 8 and 9 are each internally coupled deletions; they
  must not be interleaved with unrelated work.
- **`docs/threat-model.md` edits ride the SAME commit as the boundary change
  that triggers them** (its Maintenance Rule): the parity-gated in-process
  execution of bundled engines (Task 5), the process-runtime deletion
  (Task 8), and the launcher/bundle-surface change (Task 9) — the commits that
  change the execution boundary. Never a separate later docs task.
- **No product version bumps anywhere in this plan.** Version bumps, tags,
  and release notes are separate release-policy work. The cutover is a
  backwards-incompatible product change — record in the handoff that its
  eventual release notes must carry the retired-surface table (decision 7)
  and the schema-id changes, since CalVer signals nothing.
- **Maven runs:** sandbox must be disabled for every `./mvnw` invocation
  (JUnit `@TempDir` fails on the sandbox's read-only `/tmp`). Run
  `./mvnw -Pquality spotless:apply` before every commit touching Java. Use
  the CLAUDE.md verification lane for each touched area; module-scoped test
  selection needs `-am -Dsurefire.failIfNoSpecifiedTests=false`.
- **`ContractVersions` gotcha:** changing any `ContractVersions.*` schema-id
  constant requires a full `./mvnw clean verify` — `testbeds/plugin-runtime`
  inlines the constant values and is not in any `-pl <module> -am` closure,
  so module-scoped verification passes while the testbed is stale. Applies
  until Task 8 deletes the testbed. (No constant changes are planned; Task 6
  adds a new constant, which is safe.)
- **ELK-first rule preserved:** the layout engine remains a translation
  boundary over Eclipse ELK. Nothing in this plan adds custom placement or
  route geometry; the restructure must not smuggle any in.
- **Dependency direction (target state, enforced by ArchUnit as it lands):**
  `engine-api` → `contracts` only; engines → `engine-api`, `contracts`,
  notation cores, `schema-cache` (as today), never `core`, never each other;
  `core` → `contracts`, `engine-api`, never engine implementations; `cli` →
  `core`, `contracts`, `engine-api`, plus engine implementations **only in
  the wiring class** (decision 3). SVG emitter must not import ELK; exporters
  must not import the SVG emitter; notation semantics stay in their modules
  (Task 11 pins all of this).
- **Files that move together:** public JSON shape (Task 6) → `schemas/`,
  `contracts`, fixtures, round-trip tests together. User-facing commands
  (Tasks 7, 8, 9, 12) → `README.md` + `docs/agent-usage.md` in the same
  change; `AgentUsageDocConsistencyTest` (dist-tool) enforces that every
  `DEDIREN_*` token in the shipped docs exists in source and every CalVer
  string matches the product version — so doc token removals must ride the
  same commit as the source token deletions (Task 8), and doc-file deletions
  must update the test's file list.
- **Git policy:** direct commits to `main` allowed; task-scoped commits;
  explicit-path staging (never `git add -A`); do not commit `target/`,
  `dist/`, generated SVGs, or CDS residue. `git status --short --branch`
  before and after each task. `git diff --check` before each commit.
- **Measured baseline for evidence claims** (bundle 2026.07.8, Temurin 21,
  warm CDS; review doc holds the evidence): CLI boot 80 ms; capability probe
  ~50–80 ms/stage; 3-stage pipeline 1.325 s default / 1.152 s trust mode;
  documented agent flow = 5 CLI invocations ≈ 13–15 JVM spawns ≈ ~1.9 s.
  One-shot build projection ~0.3–0.5 s — treat as expectation, never a test
  gate. Record actual measurements in Task 13.
- **Audit gates** (CLAUDE.md, vertical slice/plugin-runtime rows): deep
  `test-quality-audit` over the new engine/dispatch/build/dist tests; quick
  `devsecops-audit` over the removed process boundary, dependency-scope
  changes, and release surface (Task 13). Fix blocks; fix or explicitly
  accept warn/info in the handoff.

## Key design decisions (resolved here, stated once)

1. **Module tree after restructure.** New tier-1 module `engine-api`
   (directory `engine-api/`, package `dev.dediren.engine`); the five plugin
   modules move to `engines/<name>` (Task 10) keeping their Maven
   artifactIds (`generic-graph`, `elk-layout`, `render`,
   `archimate-oef-export`, `uml-xmi-export`) and their existing Java packages
   (`dev.dediren.plugins.*`); `testbeds/plugin-runtime` is deleted (Task 8).
   *Justification:* the directory move makes the tree stop lying ("plugins"
   that are not plugins) while stable artifactIds and packages keep every pom
   reference, ArchUnit package rule, and git history cheap; the package
   rename is recorded as §12 debt, fixed on next substantive touch per
   module, never as a big-bang sweep entangled with this plan.
2. **Engine interfaces live in `engine-api`, not `contracts` or `core`.**
   *Justification:* `contracts` is chartered as a data contract ("the schema
   is the abstraction" — guidelines §2 forbids interface hierarchies there,
   and the wire protocol the I9 SPI mirrored is being deleted); hosting them
   in `core` would force engine→core edges and drag the driver into every
   engine build. A tiny interfaces-only module keeps `core` blind to
   implementations and engines blind to orchestration.
3. **Engine wiring is explicit construction in `cli`, not `ServiceLoader`.**
   `dev.dediren.cli.EngineWiring` constructs the five engine instances and
   hands an `Engines` registry to `core`. *Justification:* "discovery is
   explicit" is the product's DNA; a hardcoded list of five in-tree engines
   needs no lookup indirection, is debuggable, and gives ArchUnit a single
   named class to confine the cli→engine-implementation edge to.
4. **Engines are typed over existing `contracts` records** —
   `SourceDocument`/`SemanticValidationResult`/`LayoutRequest`/`LayoutResult`/
   `RenderMetadata`/`RenderResult`/`ExportRequest`/`ExportResult` — and
   return `EngineResult<T>(value, diagnostics)` or throw
   `EngineException(diagnostics, exitCode)`. Each engine preserves the exact
   diagnostic codes and non-zero exit codes its plugin form emits today
   (e.g. `DEDIREN_ELK_LAYOUT_FAILED` + exit 3), so `core` can reconstruct
   byte-shape-compatible envelopes. Success-with-info/warning diagnostics
   (e.g. `DEDIREN_OEF_VIEWS_OMITTED`, `DEDIREN_XMI_*_OMITTED`) travel in
   `EngineResult.diagnostics`.
5. **Envelope construction moves to core; runtime output re-validation is
   retired at cutover.** During transition (Task 5) the in-memory path's
   envelopes are parity-tested against the process path. After Task 8, core
   no longer schema-validates first-party engine output at runtime (the
   typed records + shared mapper make it a tautology); the result schemas
   remain the published contract, enforced by the existing schema-conformance
   and round-trip tests plus the parity fixtures.
6. **Launcher and appassembler fate.** `bin/dediren` (cli) stays as the sole
   launcher with `EXPECTED_LAUNCHER_FLAGS` and the CDS auto-create block
   unchanged; its classpath grows to the full monolith via cli's
   compile-scope engine deps. The five `bin/dediren-plugin-*` launchers and
   the appassembler executions in the five engine poms are deleted (Task 9);
   `DistTool.LAUNCHERS` shrinks to the cli entry; one runtime-generated
   `cli .jsa` covers the whole pipeline (~15–20 M less warmed footprint).
   *Justification:* every launcher existed only to give the protocol an
   executable to spawn.
7. **Diagnostics and env-var vocabulary.** Survive unchanged (wire strings):
   `DEDIREN_PLUGIN_UNKNOWN` (now "unknown engine id") and
   `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY` (engine lacks the capability) —
   published strings agents already branch on; renaming buys nothing. New:
   `DEDIREN_ENGINE_FAILED` for an unexpected in-memory engine exception
   (successor of the process-crash category). Retired at Task 8 (process
   taxonomy with nothing left to observe): `DEDIREN_PLUGIN_TIMEOUT`,
   `_PROCESS_FAILED`, `_IO_ERROR`, `_MISSING_EXECUTABLE`, `_MANIFEST_INVALID`,
   `_ID_MISMATCH`, `_CAPABILITY_PROBE_FAILED`, `_CAPABILITY_INVALID_JSON`,
   `_CAPABILITY_SCHEMA_INVALID`, `_OUTPUT_INVALID_JSON`,
   `_OUTPUT_INVALID_ENVELOPE`, `_OUTPUT_INVALID_DATA`. Env vars retired at
   Task 8: `DEDIREN_PLUGIN_DIRS`, `DEDIREN_ALLOW_PROJECT_PLUGINS`,
   `DEDIREN_PLUGIN_<PLUGIN_ID>`, `DEDIREN_TRUST_MANIFEST_CAPABILITIES`. Env
   vars kept: `DEDIREN_BUNDLE_ROOT`, `DEDIREN_CDS_DIR`,
   `DEDIREN_OEF_SCHEMA_DIR`, `DEDIREN_XMI_SCHEMA_PATH`,
   `DEDIREN_SCHEMA_CACHE_DIR`, `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY`. Every
   removal must purge the matching `docs/agent-usage.md` / `README.md` rows
   in the same commit (`AgentUsageDocConsistencyTest`).
8. **`required_plugins[]` stays accepted, unchanged.** The core today
   validates only its shape and fragment-merge version conflicts
   (`SourceValidator.mergeRequiredPlugins`) — it never checks ids against the
   discovered plugin set, so "accept and keep shape-validating, with a doc
   note that entries now name bundled engines and are informational" is a
   zero-behavior-change decision. *Justification:* validating against the
   bundled engine set would newly reject existing model corpora that name
   other ids — churn with no user gain; field retirement belongs to the
   contract-cleanup follow-up.
9. **Export engines get an explicit product-root parameter.** Today export
   plugin children run with cwd = product root, so relative
   `DEDIREN_OEF_SCHEMA_DIR`/`DEDIREN_XMI_SCHEMA_PATH`/`DEDIREN_SCHEMA_CACHE_DIR`
   values resolve against the bundle root (documented in agent-usage
   `## Export`). In-memory, the JVM cwd is the caller's directory, so the
   engine API carries `Path productRoot` (core passes
   `DedirenPaths.productRoot()`) and the env-path resolution sites
   (`plugins/archimate-oef-export/.../Main.java`,
   `plugins/uml-xmi-export/.../schema/SchemaValidation.java`) resolve
   relative values against it. `main(...)` passes the process cwd, which for
   the surviving launcher equals the caller's cwd — the doc rule "give these
   paths as absolute" stays, and the engine behavior matches the documented
   product-root resolution.
10. **`xmllint` stays; Xerces is a non-goal.** The external `--nonet`
    `xmllint` shell-out is a tool dependency owned by the export engines, not
    a plugin; `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` /
    `DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE` keep their exact semantics.
    Replacing it with in-JVM Xerces XSD validation would change validation
    behavior (libxml2 vs Xerces divergences) and grow the dependency surface
    for zero Phase 1 value — recorded as a non-goal, revisit only with
    evidence.
11. **`testbeds/plugin-runtime` retires with the protocol (Task 8).**
    *Justification:* its entire reason to exist is scripting plugin-protocol
    failure modes (timeout, invalid JSON/envelope/data, env/cwd forwarding)
    that stop being observable states; keeping it would be dead weight. Its
    two regressions that outlive the protocol are rehomed: the non-ASCII
    UTF-8 stream-encoding round-trip (issue #47) becomes a dist-smoke row
    through the packaged `dediren` launcher with a stripped environment
    (Task 9), and env forwarding for proxies is covered by the export-engine
    tests (the env map is now passed explicitly). The surviving pipe-level
    interop surface — the CLI itself speaking JSON over stdin/stdout — is
    already exercised as a real process by dist smoke.
12. **Bundle descriptor bumps to `dediren-bundle.schema.v2`** (Task 9):
    the `plugins` array and `elk_helper` field describe surfaces that no
    longer exist; an honest descriptor beats an empty-array vestige. Schema
    id is the compatibility signal; the bump rides the same task as the
    DistTool change and is called out for release notes.
13. **Plugin `Main`s become test harnesses, not deletions.** The big
    per-module suites (`GenericGraphPluginTest`, the render suite via
    `RenderTestSupport`, `ElkLayoutRenderArtifacts`, and the export
    `MainTest`s) all drive `Main.executeForTesting`; the archimate-oef export
    `MainTest` and `GenericGraphPluginTest` are named version-assertion
    surfaces in CLAUDE.md `## Versioning` (uml-xmi's `MainTest` is not). Task 9
    therefore removes only the *executable* surface: `public static void
    main`, the `capabilities` command, and the appassembler launcher config;
    `Main` + `PluginResult` move to `src/test/java` (same package, same
    names) as the envelope-shaped test harness the existing suites keep
    using. *Justification:* rewriting hundreds of envelope-asserting tests
    against the typed engines in the cutover commit would be Plan-B-scale
    churn inside the riskiest task; the harness keeps the suites green and
    their modernization becomes ordinary follow-up test work.
    Capabilities-command tests are deleted with the command.
14. **`dediren build` output contract.** One envelope on stdout whose `data`
    validates `build-result.schema.v1`: per-view outcomes (view id, status,
    artifact records with kind + written path, diagnostics), aggregate
    status (worst of the views: `ok` < `warning` < `error`), non-zero exit
    if any view failed. Artifacts are written under `--out` (files, not
    inline content — the compiler shape); `--emit
    layout-request,layout-result,render-metadata` additionally writes stage
    artifacts per view as the public debug/interop surface. Layout-quality
    validation runs per view: quality warnings downgrade the view (and
    envelope) to `warning` with the existing
    `DEDIREN_LAYOUT_QUALITY_WARNING` diagnostics; quality errors fail the
    view. A failing view does not abort the other views (report everything
    in one run — agent-repair ergonomics). Views build sequentially in
    deterministic model order in Phase 1.

---

### Task 1: Governance pre-amendment — spec, decision record, plan banners (docs-only)

Record the product-boundary reversal before any code moves, and stamp the
affected sibling plans, so parallel work cannot execute a superseded plan.

**Files:**
- Create: `docs/superpowers/specs/2026-07-08-monolithic-compiler.md` — the
  amendment spec: states the decision (compiler with a service mode; no
  runtime plugin protocol), what it revises in the 2026-05-08 design
  (Purpose, "Plugin posture", "Plugin Runtime Contract", "Rejected
  Approaches: in-process plugins", "Deferred: third-party plugin
  publishing"), what is preserved (contract-first identity; per-stage
  commands and stage artifacts as public surface; envelope/diagnostics
  contract; ELK-first; xmllint tool dependency), the extensibility bet
  (curated in-tree notations + policy + pipe-level composition), and the
  phase split (this plan = Phase 1; IR = Plan B; serve/cache/watch = Plan C;
  contract cleanup = separate follow-up). Cite the challenge review as the
  evidence record.
- Modify: `docs/superpowers/specs/2026-05-08-dediren-design.md` — prepend a
  short status note: "Amended 2026-07-08 by
  `2026-07-08-monolithic-compiler.md`: the external-executable plugin
  posture is revised to a monolithic single-JVM compiler; the contract-first
  identity and pipeline scope stand." Do not rewrite the historical body.
- Modify: `docs/architecture-guidelines.md` —
  - §1: style line becomes "contract-first modular monolith" (drop
    "process-isolated plugin runtime" from the identity, note the 2026-07-08
    reversal and its evidence: ~1.9 s / 13–15 spawns per documented agent
    flow, an order of process overhead the shipped startup tiers cannot
    recover).
  - §5: replace the "In-process transport initiative — considered and closed
    (2026-07)" paragraph: the initiative was reopened by the 2026-07-08
    runtime challenge, decided as the monolith (this plan), superseding both
    the closure ruling and the interim I9 hybrid design. Note the `[runtime]`
    reopening bar this section set is satisfied by the measured baseline.
  - §2: add the **target** allowed-edge table for the monolith (decision 1 +
    Global Constraints dependency direction) marked "target state — lands
    task-by-task via plan 2026-07-08-monolithic-runtime-radical; the current
    table remains authoritative until the matching task lands". Keep the
    current table in place.
- Modify: `docs/superpowers/plans/2026-07-08-hybrid-plugin-host.md` — top
  banner: `> **STATUS: SUPERSEDED (2026-07-08).** The owner approved the
  monolithic direction; this hybrid plan is not to be executed. Superseded by
  2026-07-08-monolithic-runtime-radical.md, which reuses its verified seam
  facts.` 
- Modify: `docs/superpowers/plans/2026-07-08-plugin-probe-cache.md` — top
  banner: `> **STATUS: DEAD (2026-07-08).** The capability probe it caches is
  deleted by 2026-07-08-monolithic-runtime-radical.md. Do not execute.`

**Interfaces:** none (prose only).

**Steps:**
- [ ] Step 1: Failing check — `grep -n "SUPERSEDED" docs/superpowers/plans/2026-07-08-hybrid-plugin-host.md`
      and `grep -n "monolithic" docs/superpowers/specs/2026-05-08-dediren-design.md`
      find nothing.
- [ ] Step 2: Write all five document changes (content above).
- [ ] Step 3: Re-check — the greps hit; `docs/architecture-guidelines.md`
      contains both the target edge table and the "current table remains
      authoritative" transition note; no doc claims the cutover already
      happened.
- [ ] Step 4: `git diff --check`; commit (docs-only, explicit paths).

### Task 2: `engine-api` module

**Files:**
- Create: `engine-api/pom.xml` (artifactId `engine-api`; dependency:
  `contracts` only + test deps).
- Create: `engine-api/src/main/java/dev/dediren/engine/EngineResult.java`,
  `EngineException.java`, `SemanticsEngine.java`, `LayoutEngine.java`,
  `RenderEngine.java`, `ExportEngine.java`, `Engines.java`.
- Modify: root `pom.xml` (add `<module>engine-api</module>`).
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java`
  — add `engineApiDependsOnlyOnContracts` (package `dev.dediren.engine..`
  may depend internally only on `dev.dediren.contracts..`); `dist-tool` test
  classpath must see the new module (add the test-scope dep in
  `dist-tool/pom.xml` alongside the existing ones).
- Test: `engine-api/src/test/java/dev/dediren/engine/EnginesTest.java`.

**Interfaces:**
```java
package dev.dediren.engine;

public record EngineResult<T>(T value, java.util.List<Diagnostic> diagnostics) {}

/** Structured engine failure: the diagnostics become the error envelope;
 *  exitCode preserves the engine's published non-zero process exit code. */
public final class EngineException extends Exception {
  public EngineException(java.util.List<Diagnostic> diagnostics, int exitCode) { ... }
  public java.util.List<Diagnostic> diagnostics();
  public int exitCode();
}

public interface SemanticsEngine {
  String id(); // "generic-graph"
  EngineResult<SemanticValidationResult> validate(SourceDocument source, String profile)
      throws EngineException;
  EngineResult<LayoutRequest> projectLayoutRequest(SourceDocument source, String view)
      throws EngineException;
  EngineResult<RenderMetadata> projectRenderMetadata(SourceDocument source, String view)
      throws EngineException;
}

public interface LayoutEngine {
  String id(); // "elk-layout"
  EngineResult<LayoutResult> layout(LayoutRequest request) throws EngineException;
}

public interface RenderEngine {
  String id(); // "render"
  EngineResult<RenderResult> render(
      LayoutResult layout, tools.jackson.databind.JsonNode policy, RenderMetadata metadataOrNull)
      throws EngineException;
}

public interface ExportEngine {
  String id(); // "archimate-oef" | "uml-xmi"
  EngineResult<ExportResult> export(
      ExportRequest request, java.util.Map<String, String> env, java.nio.file.Path productRoot)
      throws EngineException;
}

/** Immutable registry; lookups by capability + id. Missing id -> Optional.empty(). */
public record Engines(
    java.util.Map<String, SemanticsEngine> semantics,
    java.util.Map<String, LayoutEngine> layouts,
    java.util.Map<String, RenderEngine> renderers,
    java.util.Map<String, ExportEngine> exporters) { /* of(...) factory + lookups */ }
```
Notes: render policy stays `JsonNode` because the render plugin today accepts
policy JSON it validates itself (`RenderInputValidator`) — do not force
`RenderPolicy` parsing into core. `ExportResult` and
`SemanticValidationResult` are existing `contracts` records
(`contracts/.../export/ExportResult.java`,
`contracts/.../layout/SemanticValidationResult.java`).

**Steps:**
- [ ] Step 1: Write failing `EnginesTest` — a test-local fake `LayoutEngine`
      registers in `Engines.of(...)`, is found by id, and an
      `EngineException(diagnostics, 3)` round-trips both fields; unknown id
      lookup is empty.
- [ ] Step 2: Run to fail (module absent):
      `./mvnw -pl engine-api -am test` (sandbox disabled).
- [ ] Step 3: Implement; run to pass. Add the ArchUnit rule + dist-tool test
      dep; run `./mvnw -pl dist-tool -am test -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Step 4: Full reactor sanity: `./mvnw test`.
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; `git diff --check`; commit.

### Task 3: Engine extraction — generic-graph, elk-layout, render (behavior-preserving)

Each module gains a typed engine class implementing its `engine-api`
interface; the plugin `Main` becomes a thin protocol adapter (parse stdin →
call engine → print the identical envelope). All existing plugin tests stay
green unchanged — that is the behavior-preservation proof.

**Files:**
- Modify: `plugins/generic-graph/pom.xml`, `plugins/elk-layout/pom.xml`,
  `plugins/render/pom.xml` — add the `engine-api` dependency.
- Create: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphEngine.java`
  (implements `SemanticsEngine`; extracted from `Main`'s
  `validateFromStdin`/`projectFromStdin` bodies and
  `GenericGraphProjection`, operating on an already-parsed `SourceDocument`).
- Create: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkEngine.java`
  (implements `LayoutEngine`; wraps the existing `ElkLayoutEngine.layout`
  plus the error mapping `DEDIREN_ELK_LAYOUT_FAILED` exit 3). **Care:**
  `LayoutJson.readLayoutRequest` performs layout-preference validation while
  reading the stream; extract that validation so it runs on the in-memory
  `LayoutRequest` too — both paths must reject the same inputs with the same
  diagnostic.
- Create: `plugins/render/src/main/java/dev/dediren/plugins/render/SvgRenderEngine.java`
  (implements `RenderEngine`; extracted from `Main`'s render orchestration —
  `RenderInputValidator`, style resolution, `SvgDocument` emission —
  preserving all render diagnostic codes and exit codes).
- Create: a typed input-parse entry point per engine (B1) that owns the
  stdin-text → typed-record conversion, so the typed engine seam can still
  produce each engine's **published** parse-failure diagnostic once parsing
  moves off the plugin `Main`: `ElkEngine.parseRequest(byte[]) throws
  EngineException` maps a parse failure to `DEDIREN_ELK_INPUT_INVALID_JSON` +
  exit 3 (lifted from `Main.run`'s parse-catch, carrying the
  `LayoutJson.readLayoutRequest` preference validation noted above);
  `GenericGraphEngine.parseSource` and `SvgRenderEngine.parseInput` reproduce
  today's observable, because only elk-layout publishes a dedicated
  parse-failure code — unparseable stdin currently surfaces as a raw non-zero
  exit with no envelope (generic-graph `validate` and render exit 1;
  generic-graph `project` exits 2). Their per-engine parity error row (Task 5)
  therefore exercises the engine's published post-parse code instead
  (generic-graph `DEDIREN_SEMANTIC_PROFILE_REQUIRED`, render
  `DEDIREN_SVG_POLICY_INVALID`).
- Modify: the three `Main.java` files — delegate parsing to the engine's parse
  entry point, then delegate to the engine; keep `executeForTesting`, the
  `capabilities` command, and every envelope byte identical.
- Test: `GenericGraphEngineTest`, `ElkEngineTest`, `SvgRenderEngineTest` —
  typed calls on existing fixtures produce `EngineResult` values that
  JSON-equal the `data` of the corresponding `Main.executeForTesting`
  envelope, and error inputs throw `EngineException` with the same
  diagnostic code + exit code the process form returns; include an
  unparseable-input case per engine that reproduces the engine's published
  parse-failure observable (elk `DEDIREN_ELK_INPUT_INVALID_JSON` / 3; the two
  non-enveloped engines reproduce today's raw non-zero exit).

**Interfaces:** as Task 2; engine constructors are no-arg.

**Steps:**
- [ ] Step 1: Write the three failing engine tests (classes absent), each
      including the unparseable-input parse-failure case (B1) asserting the
      engine's published parse-failure code + exit through its parse entry
      point.
- [ ] Step 2: Run to fail:
      `./mvnw -pl plugins/generic-graph,plugins/elk-layout,plugins/render -am test -Dtest='*EngineTest' -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Step 3: Extract the engines; thin the Mains.
- [ ] Step 4: Run to pass, then the owning lanes:
      `./mvnw -pl plugins/elk-layout -am test` and
      `./mvnw -pl plugins/render,cli -am test` (existing plugin tests prove
      no behavior change).
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; commit (may be split into
      one commit per module if cleaner — each lands green).

### Task 4: Engine extraction — export engines + product-root env-path discipline

**Files:**
- Modify: `plugins/archimate-oef-export/pom.xml`,
  `plugins/uml-xmi-export/pom.xml` — add `engine-api`.
- Create: `plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/OefExportEngine.java`
  (`ExportEngine`, id `"archimate-oef"`).
- Create: `plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/XmiExportEngine.java`
  (`ExportEngine`, id `"uml-xmi"`).
- Create: a typed input-parse entry point on each export engine (B1) —
  `OefExportEngine.parseRequest` and `XmiExportEngine.parseRequest` — that owns
  the stdin-text → `ExportRequest` conversion so the typed seam keeps the parse
  boundary. Neither export `Main` publishes a dedicated parse-failure
  diagnostic today (unparseable stdin surfaces as a raw exit 1 with no
  envelope, before the first enveloped code), so the parse entry point
  reproduces that observable and the per-engine parity error row (Task 5)
  exercises the engine's published post-parse code instead (archimate-oef
  `DEDIREN_OEF_POLICY_INVALID`, uml-xmi `DEDIREN_UML_XMI_POLICY_INVALID`).
- Modify: both `Main.java` — delegate parsing to the engine's parse entry
  point, then delegate `execute(args, stdin, stdout, stderr, env)` to the
  engine, passing `Path.of("").toAbsolutePath()` as `productRoot` (the child
  cwd is the product root today, so the process path is byte-identical).
- Modify: env-path resolution sites — decision 9: relative
  `DEDIREN_OEF_SCHEMA_DIR` / `DEDIREN_SCHEMA_CACHE_DIR` (in
  `archimateoef/Main.java`) and `DEDIREN_XMI_SCHEMA_PATH` /
  `DEDIREN_SCHEMA_CACHE_DIR` (in `umlxmi/schema/SchemaValidation.java`)
  resolve against the threaded `productRoot` instead of bare
  `Path.of(value)`.
- Test: `OefExportEngineTest`, `XmiExportEngineTest` — typed export parity
  with `executeForTesting` (use the offline schema-env technique from
  `cli/src/test/.../CliLayoutRenderCommandTest.envWithOefSchemas()`); a unit
  test pinning relative-env-path resolution against a supplied
  `productRoot` regardless of JVM cwd; a guard test per module asserting the
  main source tree contains no `System.getenv` reference outside
  `main(String[])` (engines must read only the passed env map — verified
  today: only the two export Mains touch `System.getenv`, in `main`).

**Steps:**
- [ ] Step 1: Write the failing tests (engine classes and `productRoot`
      threading absent), including
      `relative DEDIREN_OEF_SCHEMA_DIR=schemas-oef with productRoot=/x/y
      resolves to /x/y/schemas-oef`, and an unparseable-input parse-failure
      case per export engine (B1) reproducing today's raw non-enveloped exit
      through the engine's parse entry point.
- [ ] Step 2: Run to fail.
- [ ] Step 3: Implement; keep `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` /
      `DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE` and every other diagnostic
      untouched (decision 10).
- [ ] Step 4: Run owning lanes:
      `./mvnw -pl plugins/archimate-oef-export,cli -am test` and
      `./mvnw -pl plugins/uml-xmi-export,cli -am test`.
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; commit.

### Task 5: In-memory dispatch in core + cli wiring + stage-envelope parity

Core learns to run stage commands through the `Engines` registry, falling
back to the process path only for ids the registry lacks. The cli wires the
five engines. This is the strangler seam: after this task both transports
work and are proven equivalent; Task 8 deletes the process one.

**Files:**
- Modify: `core/pom.xml` — add `engine-api` (compile).
- Modify: `contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java`
  — add `ENGINE_FAILED("DEDIREN_ENGINE_FAILED")` (the code the dispatch
  `Throwable` outcome emits, added in this task).
- Create: `core/src/main/java/dev/dediren/core/engine/EngineDispatch.java` —
  maps a typed engine call to the existing `PluginRunOutcome(stdout,
  exitCode)`. **Five outcomes:** (1) success → `CommandEnvelope.ok`/
  `.warning(value, diagnostics)` serialized with `JsonSupport` (exit
  `CommandExitCode.OK`); (2) `EngineException` →
  `CommandEnvelope.error(diagnostics)` with the exception's exit code; (3) the
  input text fails to parse into the engine's request record — dispatch routes
  the stdin text through the owning engine's parse entry point (Tasks 3–4),
  which yields that engine's **published** parse-failure diagnostic + exit
  code (elk `DEDIREN_ELK_INPUT_INVALID_JSON` + exit 3; the non-enveloped
  engines reproduce their raw non-zero exit); (4) any other `Throwable` →
  `DEDIREN_ENGINE_FAILED`-shaped `PluginExecutionException` (code added in this
  task to `DiagnosticCode`), which the cli already renders as an error
  envelope + `PLUGIN_ERROR` exit; (5) unknown engine id →
  `DiagnosticCode.PLUGIN_UNKNOWN`, wrong capability for the id →
  `PLUGIN_UNSUPPORTED_CAPABILITY` (decision 7).
- Modify: `core/src/main/java/dev/dediren/core/commands/CoreCommands.java` —
  each stage command gains an `Engines`-carrying overload
  (registry-first, `PluginRunner` fallback when the id is unbound); existing
  signatures keep the process path so `core`'s own tests need no engines on
  the classpath.
- Create: `cli/src/main/java/dev/dediren/cli/EngineWiring.java` — decision
  3; `static Engines defaults()` constructing the five engines.
- Modify: `cli/src/main/java/dev/dediren/cli/Main.java` — subcommands call
  the `Engines` overloads with `EngineWiring.defaults()`.
- Modify: `cli/pom.xml` — flip the five plugin deps from `test` to compile
  scope (they are now cli's wired engines; the appassembler CLASSPATH gains
  them, which Task 9's dist assertions will pin).
- Modify: `dist-tool/.../ArchitectureRulesTest.java` — replace
  `cliDoesNotDependOnPlugins` with: only `dev.dediren.cli.EngineWiring` may
  access `dev.dediren.plugins..`; add `coreDoesNotDependOnPlugins` stays;
  add "engines do not access `dev.dediren.engine`-implementations of other
  engines" placeholder if trivial, else defer to Task 11.
- Modify: `docs/threat-model.md` — same commit (its Maintenance Rule; Task 5
  is the first commit to change the execution boundary): a one-paragraph
  transition note that bundled first-party ids now execute in-process behind
  the parity gate, while the process boundary remains for all other manifests
  until its deletion in the cutover (Task 8).
- Test: `cli/src/test/java/dev/dediren/cli/InMemoryParityTest.java` — the
  parity matrix. For each stage operation — generic-graph `validate
  --profile archimate|uml`, `project --target
  layout-request|render-metadata`, elk-layout `layout`, render `render`
  (with and without metadata), archimate-oef `export` (offline schema dir),
  uml-xmi `export` — run once through the in-memory path
  (`CoreCommands` + `EngineWiring.defaults()`) and once through the process
  path (`PluginRunner` with the script-wrapper/manifest technique already
  used in `CliLayoutRenderCommandTest`), and assert: stdout envelopes
  JSON-value-equal, envelopes schema-valid, exit codes equal. Include one
  invalid-input error row per engine, routed through the engine's parse entry
  point / dispatch's parse outcome (B1), asserting the same diagnostic code +
  exit code on both legs (elk uses the `DEDIREN_ELK_INPUT_INVALID_JSON` / 3
  parse row; the non-enveloped engines, which have no dedicated parse code, use
  their published post-parse code — generic-graph
  `DEDIREN_SEMANTIC_PROFILE_REQUIRED`, render `DEDIREN_SVG_POLICY_INVALID`,
  archimate-oef `DEDIREN_OEF_POLICY_INVALID`, uml-xmi
  `DEDIREN_UML_XMI_POLICY_INVALID`). Investigate any byte divergence before
  accepting JSON-only equality for that row (Global Constraints).

**Steps:**
- [ ] Step 1: Write failing `InMemoryParityTest` (in-memory overloads
      absent) and an `EngineDispatchTest` in core against a fake engine
      (success, warning diagnostics, `EngineException` exit-code
      passthrough, `Throwable` → `DEDIREN_ENGINE_FAILED`, unknown id →
      `DEDIREN_PLUGIN_UNKNOWN`, parse failure → the engine's published
      parse-failure code/exit via its parse entry point).
- [ ] Step 2: Run to fail:
      `./mvnw -pl core,cli -am test -Dtest='InMemoryParityTest,EngineDispatchTest' -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Step 3: Implement dispatch, overloads, wiring, pom scope flips,
      ArchUnit amendment; add the `DiagnosticCode.ENGINE_FAILED` constant and
      the `docs/threat-model.md` transition paragraph (W2, same commit).
- [ ] Step 4: Run to pass; full plugin-runtime lane
      `./mvnw -pl core,cli -am test`; ArchUnit lane
      `./mvnw -pl dist-tool -am test -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; `git diff --check`; commit.

### Task 6: `build` driver in core + `build-result` contract

**Files:**
- Create: `schemas/build-result.schema.json` (`build-result.schema.v1`):
  aggregate `status`, `views[]` (view id, status, `artifacts[]` of
  `{artifact_kind, path}`, `diagnostics[]`), reusing the envelope
  diagnostics shape.
- Modify: `contracts/src/main/java/dev/dediren/contracts/ContractVersions.java`
  — add `BUILD_RESULT_SCHEMA_VERSION = "build-result.schema.v1"` (additive —
  no existing constant changes, so the clean-verify gotcha does not fire;
  still note it).
- Create: `contracts/src/main/java/dev/dediren/contracts/build/BuildResult.java`,
  `BuildViewOutcome.java`, `BuildArtifact.java` (records mirroring the
  schema).
- Create: `fixtures/build-result/basic.json` (+ an error-case fixture).
- Modify: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`
  — round-trip + schema-conformance rows for the new family.
- Create: `core/src/main/java/dev/dediren/core/commands/BuildCommand.java`,
  `BuildRequest.java` — the driver (decision 14): validate source (existing
  `SourceValidator`) → for each selected view: project layout-request →
  layout → layout-quality validation → render lane (auto-project
  render-metadata, render) and/or export lane(s) → write artifacts under
  `--out`; `--emit` writes stage artifacts
  (`<out>/<view>/layout-request.json` etc.); aggregate the envelope.
- Test: `core/src/test/java/dev/dediren/core/commands/BuildCommandTest.java`
  — fake engines via `engine-api` (no processes, no testbed): happy
  render-only build (two views, artifacts written, envelope validates the
  new schema); export lanes; quality warning downgrades one view + envelope
  to `warning`; one failing view does not abort the other and yields
  aggregate `error` + non-zero exit; `--emit` writes the stage files;
  deterministic view order.

**Interfaces:**
```java
public record BuildRequest(
    String sourceText,
    java.nio.file.Path sourceBaseDir,
    java.util.List<String> views,          // empty = all views in model order
    String renderPolicyText,               // null = no render lane
    String oefPolicyText,                  // null = no OEF lane
    String xmiPolicyText,                  // null = no XMI lane
    java.util.Set<String> emit,            // subset of layout-request|layout-result|render-metadata
    java.nio.file.Path outDir,
    java.util.Map<String, String> env) {}

public final class BuildCommand {
  public static PluginRunOutcome run(BuildRequest request, Engines engines)
      throws PluginExecutionException;
}
```
Engine ids are fixed to the bundled set in Phase 1 (`generic-graph`,
`elk-layout`, `render`, `archimate-oef`, `uml-xmi`); per-stage `--plugin`
selection stays on the per-stage subcommands. Export policies apply as-is per
exported view (the OEF policy's fixed view-identity fields are a documented
limitation carried over from today's per-view export).

**Steps:**
- [ ] Step 1: Write the failing round-trip/schema rows and
      `BuildCommandTest`.
- [ ] Step 2: Run to fail:
      `./mvnw -pl contracts -am test` then
      `./mvnw -pl core -am test -Dtest=BuildCommandTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Step 3: Implement schema + records + fixtures + driver.
- [ ] Step 4: Run to pass; contract lane `./mvnw -pl contracts -am test`;
      core lane `./mvnw -pl core,cli -am test`.
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; `git diff --check`; commit
      (schema, contracts, fixtures, driver, tests move together).

### Task 7: `dediren build` CLI subcommand + user docs

**Files:**
- Modify: `cli/src/main/java/dev/dediren/cli/Main.java` — add the `build`
  subcommand (thin: parse options, read inputs with the existing
  `readInput`/`readFile` helpers, call `BuildCommand.run` with
  `EngineWiring.defaults()`, print with `writePluginOutcome`/
  `writePluginError`).
- Modify: `README.md` — `dediren build` as the primary workflow example;
  per-stage flow stays as the decomposed form.
- Modify: `docs/agent-usage.md` — `## Fast Path` gains the one-shot build as
  step 4 (keep the stage-by-stage flow as the fallback/decomposed form);
  document `--emit` as the debug/interop surface and the build-result
  envelope reading pattern (`.data.views[].artifacts[]`).
- Test: `cli/src/test/java/dev/dediren/cli/CliBuildCommandTest.java` — e2e
  through the wired engines: archimate render build from
  `fixtures/source/valid-pipeline-archimate.json` writes an SVG and the
  envelope validates `build-result.schema.v1`; a uml-sequence view build
  matches the artifacts of the manual five-stage flow run in the same test;
  OEF + XMI lanes (offline schema env); failing-view aggregation; option
  validation (at least one lane required; unknown `--emit` value → usage
  error envelope).

**CLI options:**
```
dediren build
  --input <path>            source model JSON (default: stdin)
  --out <dir>               output directory (required)
  --views <id,id,...>       default: all views in model order
  --render-policy <path>    render lane
  --oef-policy <path>       ArchiMate OEF export lane
  --xmi-policy <path>       UML/XMI export lane
  --emit <kinds>            layout-request,layout-result,render-metadata
```
At least one of `--render-policy`/`--oef-policy`/`--xmi-policy` is required.

**Steps:**
- [ ] Step 1: Write failing `CliBuildCommandTest`.
- [ ] Step 2: Run to fail; implement the subcommand.
- [ ] Step 3: Run to pass: `./mvnw -pl core,cli -am test`.
- [ ] Step 4: Update `README.md` + `docs/agent-usage.md` in the same change;
      run `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; `git diff --check`; commit.

### Task 8: Cutover A — delete the plugin process runtime (core + docs + threat model, one commit)

The registry becomes the only transport. Everything that exists to launch,
probe, trust, or discover an external executable is deleted. This is one
coupled commit: source deletions, doc token purges, and the threat-model
rewrite land together (Maintenance Rule + `AgentUsageDocConsistencyTest`).

**Files:**
- Delete: `core/src/main/java/dev/dediren/core/plugins/PluginRunner.java`,
  `PluginRegistry.java`, `LoadedPluginManifest.java`, `PluginRunOptions.java`
  (these two runtime files own the retired-env-var reads — `DEDIREN_PLUGIN_DIRS`,
  `DEDIREN_ALLOW_PROJECT_PLUGINS`, `DEDIREN_PLUGIN_<ID>`,
  `DEDIREN_TRUST_MANIFEST_CAPABILITIES`; deleting them removes those reads).
  Keep `PluginRunOutcome` and `PluginExecutionException` — they are the
  core→cli outcome surface, now fed by `EngineDispatch`.
- Modify: `core/.../commands/CoreCommands.java` — drop the process-path
  overloads and the fallback; all stage commands require `Engines`.
  `CoreCommands`' own `System.getenv` calls are ambient-env default overloads
  the export lane still needs — leave them.
- Modify: `contracts/.../DiagnosticCode.java` — retire the twelve
  process-taxonomy codes (decision 7); `DEDIREN_ENGINE_FAILED`,
  `PLUGIN_UNKNOWN`, `PLUGIN_UNSUPPORTED_CAPABILITY` remain. Grep-sweep every
  retired code's usages.
- Delete: `core/src/test/java/dev/dediren/core/plugins/PluginRuntimeTest.java`
  (its surviving semantics — unknown id, unsupported capability, envelope
  preservation, non-zero exit mapping — are already covered by
  `EngineDispatchTest`/`InMemoryParityTest`; port any residual gap into
  those, do not keep the process tests alive).
- Modify: `core/src/test/java/dev/dediren/core/commands/CoreCommandsTest.java`
  and cli tests that script plugins via manifests/`DEDIREN_PLUGIN_<ID>`
  wrappers (`CliValidateTest`, `CliLayoutRenderCommandTest`,
  `InMemoryParityTest` process leg) — rework onto the engine path; the
  parity test collapses to an envelope-contract regression test (in-memory
  envelopes still validate the envelope schema and keep their exit codes).
- Delete: `testbeds/plugin-runtime/` module + its root `pom.xml` entry
  (decision 11).
- Delete: `docs/plugin-authoring.md` (the executable-plugin contract it
  documents no longer exists); Modify:
  `dist-tool/.../AgentUsageDocConsistencyTest.java` `SHIPPED_DOCS` list to
  drop it.
- Modify: `docs/agent-usage.md` + `README.md` — remove the retired env-var
  rows (`DEDIREN_PLUGIN_DIRS`, `DEDIREN_ALLOW_PROJECT_PLUGINS`,
  `DEDIREN_PLUGIN_<PLUGIN_ID>`, `DEDIREN_TRUST_MANIFEST_CAPABILITIES`), the
  trust-mode paragraphs, and the repair rows for retired diagnostics
  (`DEDIREN_PLUGIN_MISSING_EXECUTABLE`, `DEDIREN_PLUGIN_OUTPUT_INVALID_*`);
  reword `DEDIREN_PLUGIN_UNKNOWN` repair guidance to "unknown engine id —
  the bundled set is generic-graph, elk-layout, render, archimate-oef,
  uml-xmi"; add a `DEDIREN_ENGINE_FAILED` repair row to `## Repair Rules` for
  the new published diagnostic (an unexpected in-memory engine failure). Keep
  the launcher/probe sections intact (they die in Task 9).
- Modify: `docs/threat-model.md` — **same commit**: replace the "Plugin
  process boundary" trust-boundary section with a short statement that the
  runtime is a single JVM with no plugin discovery or execution surface
  (first-party engines are compile-time modules); drop the "Malicious plugin
  on a user machine" attacker-goal row; keep envelope-parsing, schema-cache,
  XML-hardening, SVG-escaping, and release-chain sections; note that the
  manifest env allowlist is gone because no child processes exist — export
  engines receive the CLI's env map explicitly and read nothing else (guard
  tests, Task 4).
- Modify: `CLAUDE.md` (part 1) — `## Architecture Rules`: first-party
  plugins → "engines are library modules behind `engine-api`; engines never
  depend on `core`; `core` never depends on engine implementations; only
  `cli` `EngineWiring` constructs them". `## Plugin Runtime Rules` →
  `## Engine Runtime Rules`: envelopes on stdout remain the agent contract;
  stderr human-only; unknown-id/unsupported-capability diagnostics; no
  discovery of any kind. Update the "Files That Move Together"
  plugin-protocol row to the engine wording. Leave launcher/ELK-lane lines
  for Task 9/10.
- Modify: `docs/architecture-guidelines.md` — §5 rewritten (the process
  boundary section becomes a historical note + the engine-boundary rules);
  the §2 table's plugin rows flip to the target rows landed so far.

**Steps:**
- [ ] Step 1: Failing signal — write/adjust the reworked core/cli tests
      first (engine-only expectations, e.g. unknown engine id yields
      `DEDIREN_PLUGIN_UNKNOWN` without touching any filesystem manifest);
      run them red where they encode post-cutover behavior.
- [ ] Step 2: Delete the runtime + testbed; rework `CoreCommands`;
      retire the diagnostic codes; sweep usages.
- [ ] Step 3: Purge doc tokens + delete `docs/plugin-authoring.md` + update
      the consistency test; rewrite the threat model; CLAUDE.md +
      guidelines amendments.
- [ ] Step 4: Full verification (the deletion touches everything):
      `./mvnw clean verify` (sandbox disabled — full clean build because the
      testbed and `ContractVersions` surfaces are in play), then
      `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest,ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false`.
      Note: dist smoke still passes at this point — the plugin launchers and
      manifests still exist and the plugin Mains still run standalone.
- [ ] Step 5: Verify the dist-smoke claim above rather than asserting it —
      `./mvnw -pl dist-tool -am verify -Pdist-smoke` (sandbox disabled) still
      passes (the plugin launchers, manifests, and standalone Mains remain
      until Task 9).
- [ ] Step 6: `./mvnw -Pquality spotless:apply`; `git diff --check`; commit
      everything above as one commit (explicit paths; the threat-model edit
      must be in it).

### Task 9: Cutover B — single-launcher distribution (dist + bundle descriptor + docs, one commit)

**Files:**
- Modify: the five engine module poms — remove the appassembler executions
  (no more `dediren-plugin-*` scripts).
- Modify: the five plugin `Main.java` + `PluginResult.java` files — decision
  13: delete `public static void main` and the `capabilities` command
  branch, then move both files unchanged-in-name from `src/main/java` to
  `src/test/java` (same package) as the envelope-shaped test harness; the
  existing suites (`GenericGraphPluginTest`, render suite via
  `RenderTestSupport`, `ElkLayoutRenderArtifacts`, elk/export/render
  `MainTest`s) keep passing. Delete only the tests that assert the removed
  `capabilities` command output.
- Delete: `fixtures/plugins/*.manifest.json` (all five).
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java` —
  `LAUNCHERS` shrinks to the cli entry; stop staging the bundle `plugins/`
  manifest dir; drop the launcher capability probes from `smoke` and replace
  with: `dediren --version`, a full `dediren build` render+export run
  against bundle fixtures, the per-stage smoke flow (public decomposed
  surface), and a non-ASCII round-trip row (a model whose labels carry the
  `Sähkö öäå 测试` sentinel rendered through the packaged launcher — issue
  #47 regression rehomed per decision 11); bundle metadata drops `plugins`
  and `elk_helper`.
- Modify: `dist-tool/.../DistTool.java` `bench(...)` scenarios (W1) — rework to
  the surviving surface (`dediren --version`, a per-stage `layout` row, a
  `dediren build` row) and delete the plugin-launcher rows; `bench` currently
  hard-codes `bin/dediren-plugin-elk-layout` and
  `bin/dediren-plugin-generic-graph` `capabilities` scenarios that crash
  post-cutover and are not covered by `-Pdist-smoke`.
- Modify: `schemas/bundle.schema.json` — `dediren-bundle.schema.v1` → `v2`
  (decision 12): remove `plugins`, `bundledPlugin`, `elk_helper`.
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`,
  `DistHermeticityTest.java` — launcher list, install dirs, bundle-layout
  and metadata assertions; CLI launcher CLASSPATH now includes the five
  engine jars (+ transitives); packaged `lib/` must equal the cli launcher's
  declared classpath (the hermeticity union collapses to one launcher).
- Modify: `dist-tool/pom.xml` — runtime deps simplify to `cli` (the engines
  arrive transitively through cli's compile deps); keep test deps as needed.
- Modify: `docs/agent-usage.md` — `## Runtime Probes` (launcher probes →
  `dediren --version` + a fixture `build`), bundle layout (`bin/` has one
  launcher; no `plugins/` manifest dir), CDS guidance (one archive; seed it
  with one representative `dediren build`); `README.md` — bundle layout,
  runtime notes, pointer to the threat model.
- Modify: `CLAUDE.md` (part 2) — `## ELK Runtime` ("first-party Java ELK
  engine module; the cli launcher hosts it" wording), `## Versioning` known
  version-assertion surfaces list stays accurate (the export `MainTest`s and
  `GenericGraphPluginTest` survive on the test harness, decision 13 — verify
  the listed paths after Task 10's directory move).
- Modify: `docs/threat-model.md` — same commit: the "Build & release chain"
  / assets wording that referenced per-plugin launchers and manifest
  shipping.

**Steps:**
- [ ] Step 1: Write the failing dist assertions first (`DistModuleTest`
      launcher list = `dediren` only; metadata without `plugins`; smoke rows
      incl. the non-ASCII sentinel and the `build` run).
- [ ] Step 2: Run to fail:
      `./mvnw -pl dist-tool -am verify -Pdist-smoke` (sandbox disabled).
- [ ] Step 3: Implement the deletions and DistTool/schema changes.
- [ ] Step 4: Run to pass: `./mvnw test` then
      `./mvnw -pl dist-tool -am verify -Pdist-smoke`; consistency lane
      `-Dtest=AgentUsageDocConsistencyTest`.
- [ ] Step 5: `./mvnw -Pquality spotless:apply`; `git diff --check`; commit
      as one commit (threat-model edit inside).

### Task 10: Module tree rename `plugins/` → `engines/`

Mechanical honesty move (decision 1). No Java package changes.

**Files:**
- Move: `plugins/generic-graph` → `engines/generic-graph`, and likewise
  `elk-layout`, `render`, `archimate-oef-export`, `uml-xmi-export`
  (`git mv`).
- Modify: root `pom.xml` module paths; `coverage-report/pom.xml` (profile
  deps are coordinates, not paths — verify unaffected). The moved poms'
  `<relativePath>../../pom.xml</relativePath>` stays correct (verified: all
  five already use depth-2 paths, and `engines/<name>` keeps the depth).
- Modify: `CLAUDE.md` `## Verification` lanes (`-pl plugins/elk-layout` →
  `-pl engines/elk-layout`, etc.) and any remaining `plugins/` module paths;
  `docs/architecture-guidelines.md` §2/§3 module names + a §12 debt row for
  the retained `dev.dediren.plugins.*` package names.
- Modify: any workflow/docs references to `plugins/...` module paths
  (`.github/workflows/*.yml` if they name module paths; grep).

**Steps:**
- [ ] Step 1: Failing check — `grep -rn "plugins/elk-layout" pom.xml CLAUDE.md .github/ docs/architecture-guidelines.md`
      lists the references that must move.
- [ ] Step 2: `git mv` the five modules; update poms and docs; re-run the
      grep to zero (excluding historical plans/reviews, which are records
      and stay untouched).
- [ ] Step 3: `./mvnw test` (full reactor — path changes touch everything);
      `./mvnw -pl dist-tool -am verify -Pdist-smoke`.
- [ ] Step 4: `./mvnw -Pquality spotless:apply`; `git diff --check`; commit
      (renames + path fixes only; no logic changes ride along).

### Task 11: ArchUnit hardening — compile-time boundaries replace the process wall

**Files:**
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java`:
  - `svgEmitterDoesNotImportElk`: no class in
    `dev.dediren.plugins.render..` depends on `org.eclipse.elk..`.
  - `exportersDoNotImportSvgEmitter`: no class in
    `dev.dediren.plugins.archimateoef..` or `dev.dediren.plugins.umlxmi..`
    depends on `dev.dediren.plugins.render..`.
  - `enginesDoNotDependOnEachOther`: engine packages are pairwise
    independent (shared code only via `contracts`, `engine-api`,
    `archimate`, `uml`, `schema-cache`).
  - `enginesDoNotDependOnCore` (rename of `pluginsDoNotDependOnCore` —
    same predicate, updated `because(...)`).
  - `coreDoesNotDependOnEngineImplementations` (rename; core may see
    `dev.dediren.engine..` only).
  - `onlyEngineWiringTouchesEngineImplementations` (from Task 5; re-verify
    wording).
  - Keep: acyclicity, contracts-depends-on-nothing, engine-api rule.
- Modify: `docs/architecture-guidelines.md` §11 — name the new rules as the
  enforcement of the monolith boundaries ("ArchUnit replaces the OS wall").

**Steps:**
- [ ] Step 1: Write the new rules; run
      `./mvnw -pl dist-tool -am test -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false`
      — expect green (the boundaries already hold); to prove each rule
      bites, temporarily break one edge locally (e.g. an unused
      `org.eclipse.elk` import in a render class), watch it fail, revert.
- [ ] Step 2: Guidelines §11 update; `git diff --check`.
- [ ] Step 3: `./mvnw -Pquality spotless:apply`; commit.

### Task 12: Docs finishing pass — feature docs, front door, stale sweep

**Files:**
- Modify: `docs/features/plugin-runtime.md` → rewrite as the engine-runtime
  feature page (or fold into `docs/features/pipeline-and-commands.md` and
  delete — keep the features index consistent);
  `docs/features/distribution-and-runtime.md` (one launcher, one CDS
  archive); `docs/features/pipeline-and-commands.md` (`build` +
  per-stage), `docs/features/contracts-and-schemas.md` (build-result family;
  orphaned plugin-surface schemas noted as pending contract-cleanup).
- Modify: `README.md` — front-door pass: compiler framing, `build` first,
  per-stage as the decomposed/debug surface, plugin-authoring pointer
  removed (already deleted); keep it the compact human front door deferring
  to `docs/agent-usage.md`.
- Modify: `docs/agent-usage.md` — final ordering pass: `## Fast Path` leads
  with `build`; per-notation handoff sections stay (they document the
  decomposed form and the authored JSON, which are unchanged).
- Verify: `docs/assets/pipeline.svg` (dogfooded diagram) still tells the
  truth — the pipeline stages are unchanged; regenerate through
  `dediren build`/per-stage flow only if its content drifted (report, do
  not commit generated SVGs unless the tracked example is being
  intentionally refreshed).
- Sweep: grep the live docs (`README.md`, `docs/agent-usage.md`,
  `docs/features/`, `docs/architecture-guidelines.md`,
  `docs/threat-model.md`, `CLAUDE.md`, `SECURITY.md`) for stale claims:
  "process", "subprocess", "manifest", "probe", "launcher(s)" (plural),
  "third-party plugin", retired `DEDIREN_*` tokens. Historical
  plans/reviews/specs are records — do not rewrite them beyond the Task 1
  banners.

**Steps:**
- [ ] Step 1: Failing check — the sweep greps produce hits in live docs.
- [ ] Step 2: Apply the doc changes; re-run the sweep to zero (live docs
      only).
- [ ] Step 3: `./mvnw -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false`;
      `git diff --check`; commit.

### Task 13: Final verification, measurements, audit gates

**Files:** none (verification only; fixes loop back into the owning task).

**Steps:**
- [ ] Step 1: Full suite: `./mvnw clean verify` (sandbox disabled; clean
      because module moves and deletions happened).
- [ ] Step 2: Quality gate: `./mvnw -Pquality verify`.
- [ ] Step 3: Distribution lane:
      `./mvnw -pl dist-tool -am verify -Pdist-smoke`; `git diff --check`;
      `git status --short --branch` clean.
- [ ] Step 4: Evidence (non-gating): on the built bundle, measure warm
      `dediren build` wall time for the 3-stage archimate fixture flow and
      compare against the recorded ~1.9 s five-invocation baseline
      (expectation ~0.3–0.5 s); use `time` or the existing `Bench` tool;
      record numbers in the handoff.
- [ ] Step 5: Deep `souroldgeezer-audit:test-quality-audit` over the new
      engine tests, dispatch/parity tests, build driver/CLI tests, and dist
      smoke. Fix blocks; fix or explicitly accept warn/info in the handoff;
      rerun affected checks.
- [ ] Step 6: Quick `souroldgeezer-audit:devsecops-audit` over the removed
      process boundary (threat-model consistency), the cli/dist dependency
      scope changes, the bundle-descriptor bump, and the release workflow
      surface (`.github/workflows/release.yml` untouched — verify).
- [ ] Step 7: Handoff notes: measured timings; accepted audit findings; the
      release-notes obligations (retired env vars + diagnostics table,
      launcher removal, `dediren-bundle.schema.v2`, new
      `build-result.schema.v1`, `docs/plugin-authoring.md` removal); the
      cross-plan status (hybrid superseded, probe-cache dead, I7 stored-jar
      still composes — its packaging tasks now cover one launcher and the
      reworked single-launcher `bench` scenarios (W1), so coordinate a rebase
      if it is in flight); and the follow-up stubs below.

---

## Follow-up plans (stubs only — do not plan their tasks here)

- **Plan B — typed IR + source provenance.** Replace the stringly
  layout-request JSON as the *internal* representation with a typed scene
  graph (sealed interfaces, records, exhaustive switches), every node
  carrying a JSON-Pointer back to the source model; split the notation front
  ends (`semantics-archimate`/`semantics-uml`/`semantics-graph`); turn the
  recorded sequence-diagram defect class into IR invariant checks and
  phase-level property tests. The public layout-request/layout-result JSON
  stays as the schema'd debug/interop surface. Builds directly on the
  `engine-api` seams this plan creates.
- **Plan C — serve/MCP + content-addressed cache + watch.** A resident
  `dediren serve` (plausibly an MCP server exposing validate/build/diagnose
  tools), a content-addressed build cache (hash of model slice + policy +
  version → artifact) enabling incremental re-layout of changed views, and a
  `watch` mode. Requires its own lifecycle/threat-model design; estimated
  warm rebuilds ~50–150 ms.
- **Contract cleanup.** Remove the orphaned plugin surface once consumers
  have moved: `schemas/plugin-manifest.schema.json`,
  `schemas/runtime-capability.schema.json`, `contracts`
  `plugin/PluginManifest` + `plugin/RuntimeCapabilities` records and
  `PLUGIN_PROTOCOL_VERSION`; decide `required_plugins[]` retirement (schema
  major bump `model.schema.v1` → `v2` territory — big-bang per guidelines
  §10); consider renaming the surviving `DEDIREN_PLUGIN_UNKNOWN` /
  `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY` wire strings; fold in the
  `dev.dediren.plugins.*` → `dev.dediren.engines.*` package-rename debt.
- **Native-image / AOT exploration (note, not a plan).** With one build
  target, GraalVM native-image (single ~binary, ~10–30 ms startup, no JDK
  prerequisite) or a JDK-24+ AOT cache becomes a single hard problem
  (EMF/Jackson reflection config) instead of six. Explore only with
  measurements; the guidelines' Tier 4 (Leyden) note still applies.

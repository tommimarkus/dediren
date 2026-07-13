# Dediren Architecture Guidelines

These are the standing architecture guidelines for the `dediren` codebase: the
module shape, the dependency rules, the boundaries that must hold, and the
forces behind them. They are grounded in two things at once — the architecture
the code already has, and established software-architecture sources — so that
each rule names a force, not just a preference.

This document is the *rationale and enforcement* layer. The terse, agent-facing
rules in `CLAUDE.md` (`## Architecture Rules`, `## Engine Runtime Rules`,
`## ELK Runtime`, `## Files That Move Together`) are the quick reference; this
file explains why they exist, what verifies them, and how to evolve the system
without breaking them. Where this document and the live code disagree, the code
and its tests are the current truth — fix one or the other, do not let them
drift silently.

Sources are cited inline by short tag (for example, *Parnas 1972*) and resolved
in [Sources](#sources).

---

## 1. Architectural style

Dediren is a **contract-first modular monolith**. Two properties define it, and
every other rule serves them:

1. **Contract-first.** The stable product is a set of machine-readable contracts
   — JSON schemas, command envelopes, and structured diagnostics. Java types are
   an implementation of those contracts, not the product. An agent must be able
   to decide success or failure from stdout JSON alone, without reading Java.
2. **Modular monolith.** The system ships as one coordinated Maven reactor build
   of small modules with an explicit, acyclic dependency graph (*Maven reactor*;
   *Martin 2017*, ADP). It is not a distributed system: the modules build and
   version together.

**Process boundary — reversed 2026-07-08, cutover landed.** Dediren shipped
with a third defining property: *process-isolated plugins* — layout, render,
and export as separate executables core launched as OS subprocesses over JSON
on stdin/stdout, a deliberate microkernel/plug-in split (*microkernel pattern*,
*Richards 2022*; *LSP*). The 2026-07-08 runtime challenge measured that
boundary's cost — roughly 1.9 s across 13–15 JVM spawns for the documented
five-invocation agent flow, an order of process overhead the shipped startup
tiers cannot recover — and the owner approved reversing it: the plugins become
in-tree single-JVM library engines behind typed interfaces
(spec `docs/superpowers/specs/2026-07-08-monolithic-compiler.md`; plan
`2026-07-08-monolithic-runtime-radical.md`). The cutover has landed: core no
longer discovers or executes plugin processes; §5 holds the engine-boundary
rules and a historical note on the retired process boundary. The launcher and
manifest packaging lane was retired by the follow-on packaging task (Cutover
B, single-launcher distribution).

The job of these guidelines is to keep the contract-first and modular-monolith
properties true as the system grows.

---

## 2. The dependency rule (the spine)

The internal compile-time dependency graph is, and must remain, a **directed
acyclic graph rooted at `contracts`** (*Martin 2017*, Acyclic Dependencies
Principle). Dependencies point *inward toward stable contracts*, never outward
toward an entrypoint or a plugin (*Cockburn ports-and-adapters*; *Martin 2017*,
Stable Dependencies Principle).

### Allowed compile-scope edges

| Module | May compile-depend on | Stability tier |
|---|---|---|
| `contracts` | *(nothing internal)* | 0 — foundation |
| `ir` | `contracts` | 0.5 — IR spine, between `contracts` and `engine-api` |
| `archimate` | *(nothing internal)* | 1 — notation core |
| `uml` | `contracts` | 1 — notation core |
| `schema-cache` | `contracts` | 1 — utility core |
| `engine-api` | `contracts`, `ir` | 1 — engine seam |
| `core` | `contracts`, `engine-api`, `ir` | 2 — orchestration + `build` driver |
| `render` (engine) | `engine-api`, `contracts`, `archimate`, `uml`, `ir` | 2 — leaf engine |
| `semantics-graph` (engine) | `engine-api`, `contracts`, `ir` | 2 — leaf engine |
| `semantics-archimate` (engine) | `engine-api`, `contracts`, `archimate` | 2 — leaf engine |
| `semantics-uml` (engine) | `engine-api`, `contracts`, `uml`, `ir` | 2 — leaf engine |
| `elk-layout` (engine) | `engine-api`, `contracts`, `ir` | 2 — leaf engine |
| `archimate-oef-export` (engine) | `engine-api`, `contracts`, `archimate`, `schema-cache` | 2 — leaf engine |
| `uml-xmi-export` (engine) | `engine-api`, `contracts`, `uml`, `schema-cache` | 2 — leaf engine |
| `cli` | `contracts`, `core`, `engine-api`, `ir`; engine implementations **only in `EngineWiring`** | 3 — entrypoint + wiring |
| `dist-tool` | `contracts` (compile); `cli` (runtime, for bundling — the bundled engine and semantics-front-end modules arrive transitively through `cli`'s compile deps, so the single-launcher distribution needs no separate engine-launcher dependency) | 3 — assembly |
| `coverage-report` | *(nothing in the default build)*; every product module (runtime, **`coverage`-profile-scoped only**, for JaCoCo `report-aggregate`) | 3 — build tooling |

Rules that fall out of this table and must be enforced, not just hoped for:

- **No cycles, ever.** A back-edge anywhere is a defect (*Martin 2017*, ADP).
- **`engine-api` is a tier-1 module** (directory `engine-api/`, package
  `dev.dediren.engine`): interfaces only, depends on `contracts` and `ir` (Plan
  B P4 added the `ir` edge — see below).
- **`engine-api` speaks `ir` types (Plan B P4 seam flip); export stays on
  `contracts` records.** `SemanticsEngine.projectScene`, `LayoutEngine.layout`,
  and `RenderEngine.render` now take/return the typed `ir` `SceneGraph` /
  `LaidOutScene` instead of the `contracts` `LayoutRequest` / `LayoutResult`
  records, adding the new `engine-api → ir` (and downstream `core → ir`,
  `elk-layout → ir`, `render → ir`) compile edges recorded in the table above.
  `ExportRequest` is the one boundary that did **not** flip: it is a wire
  contract (`export-request.schema.v1`) built from `contracts.LayoutResult`,
  and a `contracts → ir` edge is a cycle forbidden by ADP and the ArchUnit
  `internalPackagesAreAcyclic` rule, so export keeps consuming
  `contracts` records. `build` still avoids re-serializing between stages for
  this lane: it maps the in-memory `LaidOutScene` to a `LayoutResult` object
  (`LaidOutSceneMapper.toResult`) to assemble the `ExportRequest`, rather than
  writing and re-reading `layout-result.json`.
- **No engine depends on `core`.** The old plugin→`core` prohibition survives
  the reversal as an engine→`core` prohibition: engines depend on `engine-api`,
  `contracts`, and the notation/utility cores they need, never on `core` and
  never on each other. The SVG emitter must not import ELK; exporters must not
  import the SVG emitter.
- **`core` never compile-depends on an engine implementation.** `core` drives
  engines only through the `engine-api` interfaces and the `contracts` records.
- **`cli` confines the engine-implementation edge to one class.** `cli` depends
  on `core`, `contracts`, and `engine-api` for orchestration, and on the
  engine implementations — the four remaining `dev.dediren.plugins.*` engines
  plus the three `semantics-*` carve modules (Plan B P3) — only inside
  `EngineWiring`, which constructs them explicitly (no `ServiceLoader`, no
  `PATH`, no runtime discovery). ArchUnit pins the edge to that single named
  class.
- **The four remaining `dev.dediren.plugins.*` engines live under
  `engines/<name>`** (directory move landed 2026-07-08); their packages are
  retained debt — see §12. The former fifth engine, `generic-graph`, was
  carved (Plan B P3) into three top-level modules — `semantics-graph` (the
  profile-routing engine, wire id still `generic-graph`), `semantics-archimate`,
  and `semantics-uml` (its notation front ends) — each with a clean
  `dev.dediren.semantics.*` package, discharging that debt for its slice.
- **`contracts` depends on nothing internal.** It is the most-depended-on module
  and must stay the most stable (*Martin 2017*, SDP).
- **`coverage-report` is build-only.** It exists solely to host JaCoCo
  `report-aggregate` under `-Pcoverage`. Its dependencies on every product
  module are runtime-scope and confined to the `coverage` profile, so they
  never enter the default build or the shipped product graph. Nothing depends
  on it and `dist-tool` does not bundle it — it is not a compile-scope edge on
  the spine.

### A noted exception to Stable Abstractions

`contracts` is *stable and concrete* — it is almost entirely records and enums,
not abstract interfaces. The Stable Abstractions Principle (*Martin 2017*, SAP)
would flag a stable concrete module, but here it is correct: `contracts` is a
**data contract**, and its abstraction lives in the JSON schemas under
`schemas/`, not in Java interface hierarchies. Do not "fix" this by inventing
interfaces over the records. The schema is the abstraction; the records are its
typed projection.

### How to enforce it

Treat the dependency rule as testable, not as documentation:

- **Module cycles:** Maven Enforcer `reactorModuleConvergence` (built-in) plus an
  ArchUnit slice check `slices()…should().beFreeOfCycles()` (*ArchUnit*;
  *Maven Enforcer*). Prefer these over `banCircularDependencies`, which is a
  separately-governed third-party rule (`extra-enforcer-rules`) and weaker
  defense-in-depth.
- **Inward-only direction:** an ArchUnit `layeredArchitecture()` (or custom
  package rule) asserting "no plugin package may access `dev.dediren.core`" and
  "`contracts` may not access any other internal package."
- **Version convergence:** Maven Enforcer `dependencyConvergence` so transitive
  versions resolve to one set.

If a guideline in this section is worth stating, it is worth an ArchUnit test;
prose alone will drift.

---

## 3. Module responsibility charter

Each module hides one kind of decision (*Parnas 1972*, information hiding). The
charter below is the contract for "what changes for this reason lives here."

- **`contracts`** — the shared protocol vocabulary: command envelopes,
  diagnostics, schema-version constants, and the record/enum families for
  `layout`, `render`, `export`, `source`, and `plugin` surfaces. Owns the
  *shape* of the wire. Must **not** own orchestration, validation policy, plugin
  execution, or notation semantics. This is the stable kernel of the system; it
  changes only when a contract intentionally changes.

- **`core`** — orchestration and backend-neutral policy: command handlers and
  the `build` driver (`commands`), in-memory engine dispatch (`engine`), the
  engine outcome types (`plugins`: `PluginRunOutcome`,
  `PluginExecutionException`), schema validation (`schema`), source validation
  (`source`), and layout-quality checks (`quality`). Owns *how a command is
  run* and *how engine failures become structured diagnostics*. Must **not**
  own SVG styling, OEF/XMI mapping, ELK geometry, or notation type rules —
  those belong to engines.

- **`cli`** — the thin entrypoint: parse arguments, assemble a request, call
  `core`, print the envelope. Must stay thin (see §8). No domain policy, no
  plugin knowledge beyond `contracts`.

- **`archimate` / `uml`** — notation cores: the type vocabulary and semantic
  validation for ArchiMate and UML source models, shared by the plugins that
  consume those notations. Governed by §6. Must **not** own export mapping
  (OEF/XMI) or render styling.

- **`schema-cache`** — a small, boring utility that fetches and caches external
  standards schemas for export validation. Stable mechanics, low semantic load
  — the model shared library *should* look like (*reuse-or-migrate*: "stable,
  boring, owned mechanics").

- **Engines** (`render`, `elk-layout`, `archimate-oef-export`,
  `uml-xmi-export`) — leaf library modules behind `engine-api`, each owning one
  backend concern: SVG rendering, ELK layout, ArchiMate OEF export, UML/XMI
  export. Each owns its backend-specific policy and *only* that. Styling lives
  in render; OEF semantics in the OEF engine; XMI semantics in the XMI engine;
  layout geometry in ELK. (Their former standalone `Main` executables and
  per-engine launchers were retired by the single-launcher distribution
  cutover (Cutover B); each `Main` survives only as an `src/test/java`
  envelope-shaped test harness — no `main()`, no launcher script.)

- **`semantics-graph` / `semantics-archimate` / `semantics-uml`** — the former
  `generic-graph` engine, carved (Plan B P3) into a profile-routing engine
  (`semantics-graph`, still published under wire id `generic-graph`) plus two
  notation front ends. `semantics-graph` owns generic-graph source handling
  and base scene projection and depends only on `engine-api`, `contracts`, and
  `ir`; `semantics-archimate` and `semantics-uml` own the per-notation legality
  checks the router dispatches to, each depending on its own notation core
  (`archimate` or `uml`) and nothing else notation-specific. No single module
  in this trio depends on both `archimate` and `uml`.

- **`dist-tool`** — assembly and distribution: bundles the single `cli`
  launcher, which hosts the bundled engines in-process, into a runnable
  distribution, third-party-notice and SBOM generation, dist smoke. Top of the
  graph; nothing depends on it.

- **`coverage-report`** — build-only JaCoCo aggregation: hosts `report-aggregate`
  over the product reactor under `-Pcoverage`. Ships nothing and nothing depends
  on it; its product-module dependencies stay confined to the `coverage`
  profile. See §2.

- **`test-support`** — test-only scaffolding; `test`-scope only, never on a
  production compile path. (`testbeds/plugin-runtime` was deleted with the
  plugin process protocol.)

---

## 4. The contract surface is the product

Because dediren is contract-first, the **public surface is the set of contracts,
not the Java API** (*Cockburn*: the port is the stable thing; *LSP*: the wire
protocol is the compatibility boundary). Treat these as the stable product:

- public JSON schemas under `schemas/`;
- command envelopes on stdout (success and error);
- structured diagnostics agents inspect without scraping stderr;
- the documented fixtures under `fixtures/`.

(`plugin-manifest.schema.json` and `runtime-capability.schema.json` are
schemas from the retired process-plugin runtime that still ship but are
orphaned — no live code path constructs a manifest or answers a capability
probe; see §12.)

Discipline:

- **Compatibility is signalled by schema id, not by version.** Schema ids such
  as `model.schema.v1` change only when a contract family intentionally changes;
  they are the durable compatibility signal. The product CalVer
  (`YYYY.0M.MICRO`) encodes the release *date*, not compatibility — never
  communicate a breaking contract change through the version number. (See
  `CLAUDE.md` `## Versioning`.)
- **Contract changes move together.** A change to a public JSON shape updates
  `schemas/`, `contracts`, fixtures, the mapping code in the owning plugin, and
  the schema/round-trip tests *in the same change* (*Martin 2017*, Common
  Closure Principle: things that change together live, and are released,
  together). The "Files That Move Together" list in `CLAUDE.md` is the concrete
  index of these change-sets; keep it current.
- **stderr is human-only.** Anything a tool or agent must act on is in the JSON
  envelope. stderr carries debugging text and nothing load-bearing.

---

## 5. The engine boundary

The five backends are in-tree single-JVM library engines behind the typed
`engine-api` seam. The boundary is a compile-time module wall enforced by
ArchUnit and the Maven dependency graph, not an OS process wall.

### Historical note: the retired process boundary

Dediren originally ran every backend as a separate executable core launched as
an OS subprocess over JSON stdin/stdout (*microkernel*, *Richards 2022*;
*LSP*), buying fault isolation, a process trust boundary, language
independence, and decoupled versioning at the cost of per-call JVM/process
overhead. A trust-tiered in-process transport was designed on 2026-07-01 and
closed after the 2026-07-03 multi-viewpoint review (MT-7). The `[runtime]`
reopening bar that closure set was then *met*: the 2026-07-08 runtime challenge
measured the boundary end-to-end — roughly 1.9 s across 13–15 JVM spawns for
the documented five-invocation agent flow, an order of process overhead the
shipped startup tiers could not recover
(`docs/superpowers/reviews/2026-07-08-runtime-size-speed-challenge.md`, I9
dark-horse follow-up). On that evidence the owner approved the full
**monolith** (spec `docs/superpowers/specs/2026-07-08-monolithic-compiler.md`;
plan `2026-07-08-monolithic-runtime-radical.md`), superseding the interim I9
hybrid design (`2026-07-08-hybrid-plugin-host.md`) and the probe cache
(`2026-07-08-plugin-probe-cache.md`). The cutover deleted the plugin process
runtime: discovery (manifest directories, executable overrides, trust mode),
subprocess execution, capability probing, and the twelve process-taxonomy
diagnostic codes. `DEDIREN_PLUGIN_UNKNOWN` and
`DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY` keep their wire strings, now answered
from the in-memory registry; `DEDIREN_ENGINE_FAILED` succeeds the
process-crash category.

### Rules for the boundary

- **The JSON envelope stays the agent contract** (§4). Engines return typed
  results; `core`'s `EngineDispatch` renders them into the exact published
  stdout envelopes and exit codes, pinned by the cli engine-envelope
  regression tests.
- **No discovery of any kind.** No `PATH` lookup, no manifest directories, no
  per-engine executable overrides, no trust flags. The bundled engine set is
  constructed explicitly in the single `cli` `EngineWiring` class (ArchUnit
  pins the edge). An unknown engine id / wrong capability is answered from the
  in-memory registry (`DEDIREN_PLUGIN_UNKNOWN` /
  `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`).
- **Failure taxonomy is small and owned.** A structured engine failure
  (`EngineException`) becomes the engine's published error envelope with its
  exit code, preserved verbatim; a structural semantics failure keeps its
  non-enveloped stderr/exit-2 observable; an unexpected engine exception is
  normalized to `DEDIREN_ENGINE_FAILED`; Errors crash loudly.
- **A dependency belongs to the engine that needs it.** An engine reports its
  own missing runtime dependency as a structured error envelope (for example
  the export engines emit `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` /
  `DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE` when `xmllint` is absent). Keep
  this ownership line crisp when adding diagnostics.
- **Ambient environment stays out of engines.** The export engines receive the
  CLI's env map explicitly for their schema-path variables and read nothing
  else (no-`getenv` guard tests); no other engine reads the environment.

---

## 6. Shared notation cores: `archimate` and `uml`

This is the system's most load-bearing design tension, so it gets its own rules.
`archimate` and `uml` are **shared kernels** (*Evans 2003*): notation vocabulary
and validation co-owned by every plugin that reads that notation (`render`,
the matching `semantics-*` notation front end, and the matching export
plugin). A shared kernel trades
duplication for tight, governed coupling, so it is justified only for *stable,
genuinely common* concepts and must be kept small.

### What may live in a notation core, and what may not

- **In the core:** the notation *type vocabulary* (element/relationship/view
  kinds) and *source-model validation semantics* — the rules that decide whether
  an input model is legal in that notation. These are common to every consumer
  and benefit from one owner.
- **Not in the core:** *export mapping* semantics. ArchiMate→OEF XML belongs to
  `archimate-oef-export`; UML→XMI belongs to `uml-xmi-export`; SVG styling
  belongs to `render`. The `CLAUDE.md` rule "ArchiMate/OEF semantics belong in
  the export plugin" is about *mapping/serialization* semantics, which the
  export plugins do own. Input *validation* is the shared part. State the
  distinction this way to keep the rule and the code consistent.

### Single source of truth for vocabulary

A notation core's vocabulary is its **owned, exported surface**, and every
consumer reads it from there (*Hunt & Thomas* DRY-as-ownership; *Martin 2017*,
Common Reuse Principle). Re-declaring a notation's vocabulary in a consumer is a
defect: the two copies drift, and a spec change must be made twice.

> **Known violation (debt):** `uml`'s `MESSAGE_SORTS` and
> `COMBINED_FRAGMENT_OPERATORS` (`UmlSequenceValidation`) are `private`, so
> `render` cannot reuse them and re-declares its own `UML_SEQUENCE_MESSAGE_SORTS`
> / `UML_SEQUENCE_COMBINED_FRAGMENT_OPERATORS` in `RenderInputValidator`. The fix
> is to make the vocabulary the *exported* surface of `uml` (or, if it is truly a
> schema-level enumeration, to lift it into `contracts`) and have `render`
> consume it — not to keep two private copies in sync.

### Keep cores symmetric in role, not necessarily in size

`uml` is legitimately larger and more complex than `archimate` (more diagram
types, ordering and nesting rules), and that asymmetry of *scope* is fine. What
should be symmetric is their *architectural role and placement*: both are
tier-1 notation cores consumed through the same engine roles. Today they differ even in dependency (`uml` → `contracts`, `archimate`
standalone) and in how they model endpoint legality (`archimate` uses explicit
curated/rejected triples; `uml` uses inline conditional logic). Prefer the
explicit-data style for new endpoint rules — it is auditable — and let a core
depend on `contracts` only when it genuinely consumes contract types. When a
notation core keeps growing and pulling unrelated consumers along, that is the
signal (*Evans 2003*) to split the shared part or accept deliberate duplication,
not to keep enlarging the kernel.

### The contested edge, stated honestly

Whether to share a kernel at all versus duplicate across contexts is genuinely
debated (*Evans 2003* vs. team-topology practice: "a little copying is better
than a little dependency"). Dediren's position: a single team owns all of this,
the notation vocabulary is stable and standardized, and three consumers each
would otherwise re-implement it — so a *small, exported* shared core is the right
call here. Revisit if ownership ever splits across teams.

---

## 7. ELK-first: depend on the engine, do not reinvent layout

`elk-layout` is an adapter over Eclipse ELK, and layout/routing intent is
expressed *through ELK*, not reimplemented beside it (*value/waste discipline*;
*adapter boundary*). Before adding any custom placement or route geometry, the
order of attempts is: official ELK Layered options, graph structure, ports,
hierarchy, then real-render evidence. Custom geometry is a last resort backed by
evidence that ELK cannot express the intent.

This keeps `elk-layout` a translation boundary (semantic graph → ELK graph →
laid-out `contracts` types) rather than a second layout engine. Duplicating a
capability ELK already provides is design debt, not a feature.

---

## 8. Thinness and cohesion

Entrypoints and adapters stay thin; the work lives in owned policy modules
(*Java extension default*: entrypoints/adapters are thin). `cli` honors this
today (446 LOC). The active divergence is **plugin `Main.java` god-files**:

> **Known debt:** `render/Main.java` is ~3,851 LOC and `uml-xmi-export/Main.java`
> ~1,734 LOC in single files; `uml/Uml.java` (904) and
> `uml/UmlSequenceValidation.java` (798) are also large. A single file carrying
> an entire backend mixes many reasons to change and raises cognitive load
> (*Parnas 1972*; ISO/IEC 25010 maintainability). When one of these files is next
> touched substantively, split it along the responsibilities already implicit in
> it (input validation, mapping, serialization, per-diagram logic) rather than
> growing it further.

Guideline: a plugin's `Main.java` should orchestrate and delegate, not *be* the
backend. Treat a multi-thousand-line entrypoint as a boundary smell to pay down
on the next real change in that file — not a reason for a speculative rewrite.

---

## 9. Quality forces as owned requirements

Name the non-functional forces this architecture serves, each as a measurable
attribute with an owning boundary (*ISO/IEC 25010*; *SEI ATAM* discipline).
These are the forces; sharpen the measure before treating one as a hard
requirement.

| Force (25010 attribute) | Scenario shape | Owner |
|---|---|---|
| **Determinism / agent-consumability** (Interaction Capability, Functional Suitability) | Given the same source + policy, a command yields byte-stable envelopes and artifacts an agent can branch on. | `core` (envelopes), each plugin (artifacts) |
| **Failure legibility** (Reliability) | An engine failure yields a structured diagnostic (or the engine's published error envelope) and a non-zero exit an agent can branch on. | `core` engine dispatch + engine boundary (§5) |
| **Offline capability** (Flexibility/Portability) | Export validation runs without network when schema files are supplied (`DEDIREN_OEF_SCHEMA_DIR`, `DEDIREN_XMI_SCHEMA_PATH`). | `schema-cache` + export plugins |
| **Reproducible build** (Maintainability) | The reactor builds from the checked-in Maven Wrapper with repo-local cache, no ambient state. | root reactor / `dist-tool` |

If a future change adds a *latency* or *throughput* requirement (for example,
"render under N ms"), express it as a scenario with a number and an owner before
it is allowed to override a contract guideline above. Performance work that
erodes the engine boundary needs `[runtime]` evidence, not assertion.

---

## 10. Evolving the architecture

Small, boundary-preserving moves (*evolutionary design*). Playbooks for the
common changes:

- **Add an engine.** New Maven module under `engines/`, depends on
  `engine-api` and `contracts` (+ the notation/utility cores it needs), never
  on `core`. Implement the matching `engine-api`
  interface, surface failures as `EngineException` with published diagnostics,
  and bind the instance in `cli` `EngineWiring` (the only permitted
  implementation edge). Add the cli engine-envelope regression coverage named
  in the matching `CLAUDE.md` "Files That Move Together" row.

- **Add or extend a notation.** Extend the owning notation core
  (`archimate`/`uml`) with vocabulary as the *exported* surface (§6); update
  consumers (`render`, the matching `semantics-*` notation front end, the
  export plugin) to read it from there. Do not re-declare vocabulary in a
  consumer. If the change is a public schema change, move the
  schema/contracts/fixtures/mapping/tests together (§4).

- **Add a contract surface.** Add records/enums to `contracts` and the schema to
  `schemas/`; bump the schema id only if compatibility actually breaks; update
  fixtures and round-trip tests in the same change. Keep orchestration and
  notation logic out of `contracts`.

- **Add a runtime-boundary diagnostic.** Decide ownership first (§5): a
  dependency the plugin owns is reported by the plugin; a failure core observes
  generically stays a core diagnostic. Add the structured code; do not leak it to
  stderr.

- **Break a public schema (vN → vN+1).** Policy: **big-bang by design** — one
  schema file per family, replaced in place; there is no dual-read window, no
  `migrate` subcommand, and no deprecation period. Consumers pin bundle
  versions, and the schema id (not CalVer) is the compatibility signal, so a
  break ships with release notes containing an explicit old→new field mapping
  agents can apply mechanically. Measured change surface for
  `model.schema.v1` (2026-07-03): ~30 files — the schema `const`,
  `contracts/ContractVersions`, 16 `fixtures/source/` documents, test
  hotspots (`GenericGraphPluginTest`, `CliValidateTest`), and 5 docs. The
  recipe is the three precedent bumps: `1087f95` (render-result v2, 11
  files), `db09a7b` (v3, 6 files), `238da5a` (family rename, 12 files);
  round-trip tests fail loudly on any surface you forget. If a future
  consumer base makes big-bang untenable, design a dual-read window as its
  own spec first — do not improvise one mid-bump.

- **Bump Jackson.** The 2→3 migration this section used to plan for has
  **already happened**: Jackson 3 (`tools.jackson`, `jackson.version` 3.2.0 in
  the root pom) is the product's sole JSON stack. 42 main-source files import
  `tools.jackson`; **no** main-source file imports a `com.fasterxml.jackson`
  runtime package. There is no dual-stack bump procedure to follow and no
  migration left to schedule.

  The one `com.fasterxml` surface that remains is deliberate and is not a
  leftover: **annotations**. Jackson 3 keeps its annotation package at
  `com.fasterxml.jackson.annotation` by design, so the 40 main-source files
  carrying `@JsonProperty` / `@JsonInclude` are correct as they are — do not
  "finish the migration" by rewriting them.

  Routine bumps therefore move one stack. Keep Enforcer
  `dependencyConvergence` green: networknt `json-schema-validator` is the other
  consumer of Jackson on the classpath, so a bump that changes the databind
  version must re-verify convergence against it.

---

## 11. Enforcing these guidelines

Guidelines that are not checked become folklore. The enforceable core:

- **Dependency DAG + no cycles + inward-only:** ArchUnit architecture tests
  (slice cycle check, layered/package-access rules) plus Maven Enforcer
  (`reactorModuleConvergence`, `dependencyConvergence`). These directly back §2.
  (*ArchUnit*; *Maven Enforcer* — current as of writing: ArchUnit 1.4.x, Enforcer
  3.6.x.)
- **Contract stability:** schema id discipline and the schema/round-trip and
  version-assertion tests already in the suite back §4.
- **Engine boundary — ArchUnit replaces the OS wall:** the retired process
  boundary (§5 "Historical note") was a runtime, subprocess-enforced wall; the
  monolith cutover moved that enforcement to compile time.
  `ArchitectureRulesTest` (`dist-tool`) is the enforcement: `enginesDoNotDependOnCore`
  and `coreDoesNotDependOnEngineImplementations` pin the core↔engine edge,
  `enginesDoNotDependOnEachOther` asserts the four remaining
  `dev.dediren.plugins.*` engines are pairwise independent and
  `semanticsModulesAreIndependentAndLeaf` asserts the same for the three
  `semantics-*` carve modules, `svgEmitterDoesNotImportElk` and
  `exportersDoNotImportSvgEmitter` pin the two named examples from §2 ("the
  SVG emitter must not import ELK; exporters must not import the SVG
  emitter"), and `onlyEngineWiringTouchesEngineImplementations` confines the
  cli-to-engine-implementation edge to the single `EngineWiring` class. A
  `reactorProductionClassesWereImported` guard keeps every rule non-vacuous by
  asserting each constrained package (core, engine-api, each of the four
  `plugins.*` engine packages, and each of the three `semantics-*` packages
  individually) actually has classes on the test classpath.
  `EngineDispatchTest` (core) and the cli engine-envelope regression tests back
  the runtime dispatch and diagnostics side of §5.
- **Audit gates:** the `CLAUDE.md` `## Audit Gates` table assigns
  `test-quality-audit` and `devsecops-audit` passes per work area; run the one
  the touched area names before calling work complete.

JPMS (`module-info.java`) is intentionally **not** used to enforce boundaries.
For a Maven multi-module CLI with external dependencies, ArchUnit + Enforcer give
deterministic, dependency-friendly enforcement, while JPMS adoption across every
module carries real cost (automatic-module friction with non-modular
dependencies) for encapsulation this design already gets from module structure
and the ArchUnit-pinned engine boundary. (*JLS 21* §7.7; *JEP 261* — JPMS-for-libraries is a
genuinely contested area; this is a reasoned position, not settled consensus.)
Adopting JPMS selectively on the runnable distribution for `jlink` is a separate,
defensible option if minimal-runtime images become a goal.

---

## 12. Known architectural debt

The guidelines above are mostly already honored by the code. The active
divergences, gathered for visibility (fix on the next real change to each, not
speculatively):

| Debt | Location | Guideline | Smell |
|---|---|---|---|
| Notation vocabulary duplicated because the source copy is `private` | `uml/UmlSequenceValidation.java` (private) vs `render/node/uml/RenderInputValidator.java` (re-declared) | §6 single source of truth | `java.SD-S` / `SD-C` |
| Plugin `Main.java` god-files — **resolved**: split into per-notation packages (`render` → `.style`/`.svg`/`.node.{archimate,uml}`; `uml-xmi-export` → `.build`/`.policy`/`.schema`/`.write.*`) | `render/Main.java` 3,851→317 LOC, `uml-xmi-export/Main.java` 1,734→292 LOC | §8 thinness/cohesion | (resolved) |
| Notation-core asymmetry (dependency + endpoint-rule modeling) | `archimate` (standalone, explicit triples) vs `uml` (→`contracts`, inline conditionals) | §6 symmetric role | `SD-S` (low) |
| `CLAUDE.md` "semantics belong in export plugin" reads as contradicting shared cores | wording vs `archimate`/`uml` consumers | §6 validation-vs-mapping distinction | doc/code drift |
| SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` suppressed across the contract DTO records (17 `contracts` value records + `schemacache.SchemaFetchResult`) | `spotbugs-exclude.xml` | §6 contract surface | deferred: two distinct cases — the `contracts` records wrap List/Map components via `ContractCollections.listOrEmpty`/`mapOrEmpty` (`List.copyOf`/`Map.copyOf`), so sharing the reference is genuinely immutable; `schemacache.SchemaFetchResult` is different — mutable `byte[] stdout`/`stderr` exposed by reference, never mutated after construction, and not part of the public `contracts` surface (short-lived internal schema-cache result). Defensive-copying either case is out of scope for the quality-gate wiring |
| SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` suppressed on the `ir` pre-layout and post-layout scene records (`ir.SceneGraph`, `ir.SceneGroup`, `ir.LaidOutScene`, `ir.PlacedGroup`, `ir.RoutedEdge`, `ir.LayoutIntent$OrderedBand`) | `spotbugs-exclude.xml` | §6 contract surface | deferred: same case as the other `contracts`-style records — wrap their List components via `ContractCollections.listOrEmpty` (`List.copyOf`), so sharing the reference is genuinely immutable; suppressed rather than defensive-copied for consistency with the existing `contracts` records (`ir.SceneGraph`/`ir.SceneGroup` from Plan B P1; `ir.LaidOutScene`/`ir.PlacedGroup`/`ir.RoutedEdge` from Plan B P2; `ir.LayoutIntent$OrderedBand` from Plan B P5 Task 1; `ir.BandMember` holds no List/Map component and is not flagged) |
| SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` suppressed on the `contracts.build` record classes (`BuildArtifact`, `BuildResult`, `BuildViewOutcome`) | `spotbugs-exclude.xml` | §6 contract surface | deferred: same case as the other `contracts` records — wrap List/Map components via `ContractCollections.listOrEmpty`/`mapOrEmpty` (`List.copyOf`/`Map.copyOf`), so sharing the reference is genuinely immutable; suppressed rather than defensive-copied for consistency with the existing `contracts` records |
| SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` suppressed on the `engine-api` value types (`engine.EngineResult`, `engine.EngineException`, `engine.Engines`) | `spotbugs-exclude.xml` | §2 dependency spine | deferred: same case as the `contracts` records — `EngineResult`/`EngineException` wrap their `List<Diagnostic>` component via `ContractCollections.listOrEmpty` (`List.copyOf`) and `Engines` wraps its capability maps via `mapOrEmpty`/`Map.copyOf`, so sharing the reference is genuinely immutable; suppressed rather than defensive-copied for consistency with the `contracts` records |
| SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` suppressed on `core.engine.EngineDispatch$InMemoryOutcome$Failure` | `spotbugs-exclude.xml` | §2 dependency spine | deferred: same case as the `engine-api`/`contracts` records — the in-memory dispatch failure outcome (Plan B P4) wraps its `List<Diagnostic>` component via `ContractCollections.listOrEmpty` (`List.copyOf`), so sharing the reference is genuinely immutable; suppressed rather than defensive-copied for consistency with the `contracts`/`engine-api` records |
| SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` suppressed on `render/node/NodeLabelLines` and `umlxmi/build/ExportScope` | `spotbugs-exclude.xml` | §8 thinness/cohesion | deferred: plugin-internal value records extracted from the former Main god-files; hold List/Set consumed read-only within the engine, not part of the public contract surface; suppressed rather than defensive-copied for consistency with the `contracts` records |
| SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` suppressed on the styling records that carry an optional `dash_pattern` (`contracts.render.Svg{Node,Group,Edge}Style` and `render/style/Resolved{Node,Group,Edge}Style`) plus `contracts.render.SvgGradient` (its `List<SvgGradientStop> stops`) | `spotbugs-exclude.xml` | §6 contract surface / §8 thinness | deferred: each wraps its `List` via `ContractCollections.copyOrNull` (`List.copyOf`, null-preserving so an unset override differs from an empty list), so sharing the reference is genuinely immutable; suppressed rather than defensive-copied for consistency with the existing records (broaden-styling: lines & strokes, gradient fills) |
| SpotBugs `MS_EXPOSE_REP` suppressed in `JsonSupport.objectMapper()` | `spotbugs-exclude.xml` | §4 contract surface | by design: returns the shared `ObjectMapper` singleton, mutable in place (`.configure()`/`.registerModule()`), exposed as one canonical mapper; the reconfiguration risk is first-party-only: every consumer of the singleton is first-party code in the product JVM, and the mapper is configured once at class initialization |
| SpotBugs `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` suppressed in `DistTool` (5 sites) | `spotbugs-exclude.xml` | §3 module charter | deferred: `Path.getFileName()`/`getParent()` on `Files.list(dir)` entries and real bundle/output paths; null branch infeasible; build/dist tool, not shipped runtime |
| SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` suppressed on `semantics.uml.SequenceConstraint`'s four records (`LifelineOrder`, `MessageOrder`, `FragmentOpen`, `OperandOpen`) | `spotbugs-exclude.xml` | §6 contract surface | deferred: same case as the other `contracts`/`ir` records — each wraps its `List<String>` component via `ContractCollections.listOrEmpty` (`List.copyOf`) in a compact constructor, so sharing the reference is genuinely immutable; suppressed rather than defensive-copied for consistency with the existing `contracts`/`ir` records (Plan B P5 Task 2) |
| Cross-plugin envelope/dispatch boilerplate duplicated across first-party plugin Mains | `engines/archimate-oef-export/src/test/.../Main.java`, `engines/uml-xmi-export/src/test/.../Main.java` (and the render/elk-layout equivalents) | §5 engine boundary | `LA-CODE-DUP-1` (lean-audit 2026-07-06); the packaging task (Cutover B) landed and retired the launcher lane, but demoted these `Main`s to `src/test/java` envelope-shaped test harnesses rather than deleting them (decision 13), so the duplication is retained debt with no scheduled retirement — the former `generic-graph` engine's copy was discharged by the Plan B P3 semantics carve, which introduced no replacement `Main` |
| Layout-quality metric math re-implemented in the ELK plugin's e2e test | `engines/elk-layout/.../ElkLayoutEngineTest.java` (geometry-metric helpers) vs `core/quality/LayoutQuality.java` | §2 no plugin→`core` edge | `LA-CODE-DUP-2` (lean-audit 2026-07-06), accepted: plugins may depend only on `contracts` (the sole test-scope exception belongs to `cli`), and `test-support` cannot host `contracts`-typed helpers without a reactor cycle (`contracts` test-depends on `test-support`); the independent copy deliberately corroborates core's quality metrics against real ELK output; marked `lean-audit:dup-intentional`; revisit if a contracts-aware test-support home is ever chartered |
| `dev.dediren.plugins.*` package names retained on the four remaining engines after the `plugins/` → `engines/` directory move | `engines/{elk-layout,render,archimate-oef-export,uml-xmi-export}/src/**/dev/dediren/plugins/**` | §2 module naming | mechanical directory/reactor move only (owner-ratified); the matching package rename is out-of-scope follow-on debt for these four, not yet scheduled; the former fifth engine, `generic-graph`, discharged this debt for its slice when the Plan B P3 semantics carve replaced it with the clean `dev.dediren.semantics.*` packages in `semantics-graph`/`semantics-archimate`/`semantics-uml` |
| `plugin-manifest.schema.json` / `runtime-capability.schema.json` still ship though no live code path constructs a manifest or answers a capability probe | `schemas/plugin-manifest.schema.json`, `schemas/runtime-capability.schema.json`, `contracts.PluginManifest`/`RuntimeCapabilities` | §4 contract surface | orphaned by the process-plugin runtime deletion (Cutover A/B); kept compile-checked and round-tripped from inline JSON (`ContractRoundTripTest`, `SchemaValidatorTest`) rather than deleted, since a future non-bundled engine could still want the same manifest/capability shape; retire or repurpose is out-of-scope follow-on debt, not yet scheduled |

None of these block the architecture; they are propagation-cost and clarity
debts with a clear, local fix when their files are next worked.

---

## 13. Decision history and operational rationale

Decisions a maintainer needs that used to live only in retired plans or git
archaeology (promoted 2026-07-03 after the multi-viewpoint review, MT-5).

- **(a) Build lineage.** The product began as a Rust/Cargo workspace,
  replaced in place by a Java 21 + Gradle build (`4933d79`), then switched to
  the Maven Wrapper (`657c4fa`). The Maven pivot's driver, previously
  recorded nowhere: **Gradle does not work in the sandboxed agent
  environments this repository is developed in** (daemon, cache, and
  network-resolution behavior fail under the sandbox), while the Maven
  Wrapper runs cleanly. Pre-Java contract rationale requires archaeology
  below `4933d79`; per `CLAUDE.md`, do not revive retired pre-Maven guidance.

- **(b) Enforced launcher flags.** The bundle's single `bin/dediren` launcher
  ships `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC`, asserted by `DistTool`
  (`EXPECTED_LAUNCHER_FLAGS`). Measured justification: elk-layout −102 ms
  (−12.9%) wall per call (JVM tier-1 plan/results, 2026-06-10, measured when
  layout still had its own per-plugin launcher pre-cutover — the flags carried
  forward to the single launcher). `-Xmx` is deliberately excluded — do not
  add heap caps to the launcher.

- **(c) JVM startup tiers.** Startup cost is attacked in ordered tiers:
  Tier 1 launcher flags (shipped), Tier 2 AppCDS auto-created `.jsa`
  archives (shipped; seeding caveat — archives are seeded by the first
  invocation and a probe-seeded archive is ~30% slower per call than a
  workload-seeded one, see `docs/agent-usage.md`), Tier 3 manifest-trust
  probe skip (~50 ms/op; retired with the plugin process runtime — the
  monolith removed the per-call probe entirely), Tier 4 Leyden AOT cache
  (planned successor; gated on the pending Java 25 baseline decision). Tier 4
  supersedes Tier 2 when adopted. The 2026-07-03 review appendix holds the
  measured baseline for all of this.

---

## Sources

- **Parnas 1972** — D. L. Parnas, "On the Criteria To Be Used in Decomposing
  Systems into Modules," *CACM* 15(12). https://dl.acm.org/doi/10.1145/361598.361623
- **Martin 2017** — R. C. Martin, *Clean Architecture*, Part IV (Component
  Principles: REP, CCP, CRP, ADP, SDP, SAP).
  https://www.oreilly.com/library/view/clean-architecture-a/9780134494272/
- **Cockburn** — A. Cockburn, "Hexagonal Architecture (Ports and Adapters)."
  https://alistair.cockburn.us/hexagonal-architecture/ (book-length treatment:
  *Hexagonal Architecture Explained*, 2024).
- **Evans 2003** — E. Evans, *Domain-Driven Design* (Shared Kernel; Maintaining
  Model Integrity). DDD Reference: https://www.domainlanguage.com/ddd/reference/
- **Richards 2022** — M. Richards, *Software Architecture Patterns*, 2nd ed.,
  "Microkernel Architecture."
  https://www.oreilly.com/library/view/software-architecture-patterns/9781098134280/
- **LSP** — Language Server Protocol (out-of-process JSON-RPC rationale).
  https://microsoft.github.io/language-server-protocol/
- **Maven reactor / Enforcer** — Maven multi-module guide
  https://maven.apache.org/guides/mini/guide-multiple-modules.html ; Enforcer
  rules https://maven.apache.org/enforcer/enforcer-rules/index.html
- **ArchUnit** — architecture tests for Java. https://www.archunit.org/ (User
  Guide: https://www.archunit.org/userguide/html/000_Index.html)
- **ISO/IEC 25010** — SQuaRE product-quality model.
  https://www.iso.org/standard/78176.html
- **SEI ATAM** — Architecture Tradeoff Analysis Method.
  https://www.sei.cmu.edu/library/the-architecture-tradeoff-analysis-method-2/
- **Hunt & Thomas** — *The Pragmatic Programmer* (DRY as knowledge ownership).
- **JLS 21 §7.7 / JEP 261** — Java module declarations / module system.
  https://docs.oracle.com/javase/specs/jls/se21/html/jls-7.html#jls-7.7 ;
  https://openjdk.org/jeps/261

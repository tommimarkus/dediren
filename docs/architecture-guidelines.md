# Dediren Architecture Guidelines

These are the standing architecture guidelines for the `dediren` codebase: the
module shape, the dependency rules, the boundaries that must hold, and the
forces behind them. They are grounded in two things at once — the architecture
the code already has, and established software-architecture sources — so that
each rule names a force, not just a preference.

This document is the *rationale and enforcement* layer. The terse, agent-facing
rules in `CLAUDE.md` (`## Architecture Rules`, `## Plugin Runtime Rules`,
`## ELK Runtime`, `## Files That Move Together`) are the quick reference; this
file explains why they exist, what verifies them, and how to evolve the system
without breaking them. Where this document and the live code disagree, the code
and its tests are the current truth — fix one or the other, do not let them
drift silently.

Sources are cited inline by short tag (for example, *Parnas 1972*) and resolved
in [Sources](#sources).

---

## 1. Architectural style

Dediren is a **contract-first modular monolith with a process-isolated plugin
runtime**. Three properties define it, and every other rule serves them:

1. **Contract-first.** The stable product is a set of machine-readable contracts
   — JSON schemas, command envelopes, plugin manifests, runtime capability
   probes, and structured diagnostics. Java types are an implementation of those
   contracts, not the product. An agent must be able to decide success or
   failure from stdout JSON alone, without reading Java.
2. **Modular monolith.** The system ships as one coordinated Maven reactor build
   of small modules with an explicit, acyclic dependency graph (*Maven reactor*;
   *Martin 2017*, ADP). It is not a distributed system: the modules build and
   version together.
3. **Process-isolated plugins.** Layout, render, and export are *separate
   executables* that core launches as OS subprocesses and talks to over JSON on
   stdin/stdout (*microkernel pattern*, *Richards 2022*; *LSP*). This is a
   deliberate microkernel/plug-in split: `core` is the kernel, plugins are
   independently built feature modules behind a wire contract.

The job of these guidelines is to keep those three properties true as the system
grows.

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
| `archimate` | *(nothing internal)* | 1 — notation core |
| `uml` | `contracts` | 1 — notation core |
| `schema-cache` | `contracts` | 1 — utility core |
| `core` | `contracts` | 2 — orchestration |
| `cli` | `contracts`, `core` | 3 — entrypoint |
| `render` (plugin) | `contracts`, `archimate`, `uml` | 2 — leaf plugin |
| `generic-graph` (plugin) | `contracts`, `archimate`, `uml` | 2 — leaf plugin |
| `elk-layout` (plugin) | `contracts` | 2 — leaf plugin |
| `archimate-oef-export` (plugin) | `contracts`, `archimate`, `schema-cache` | 2 — leaf plugin |
| `uml-xmi-export` (plugin) | `contracts`, `uml`, `schema-cache` | 2 — leaf plugin |
| `dist-tool` | `contracts` (compile); `cli` + every plugin (runtime, for bundling) | 3 — assembly |
| `coverage-report` | *(nothing in the default build)*; every product module (runtime, **`coverage`-profile-scoped only**, for JaCoCo `report-aggregate`) | 3 — build tooling |

Rules that fall out of this table and must be enforced, not just hoped for:

- **No cycles, ever.** A back-edge anywhere is a defect (*Martin 2017*, ADP).
- **No plugin depends on `core`.** Plugins are reachable only across the process
  boundary; a compile dependency from a plugin to `core` would collapse the
  microkernel split. Plugins may depend on `contracts` (the wire types) and on
  the notation/utility cores they need.
- **`core` never compile-depends on a plugin.** `core` discovers and runs
  plugins as subprocesses (`PluginRunner` via `ProcessBuilder`); it knows them
  only through `contracts` (manifests, envelopes, capabilities).
- **`cli` depends on `core` and `contracts` only.** The CLI assembles requests
  and prints envelopes; it does not reach into plugins (its plugin dependencies
  are `test`-scope, for end-to-end coverage, and must stay that way).
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

- **`core`** — orchestration and backend-neutral policy: command handlers
  (`commands`), plugin discovery and subprocess execution (`plugins`), schema
  validation (`schema`), source validation (`source`), and layout-quality checks
  (`quality`). Owns *how a command is run* and *how runtime-boundary failures
  become structured diagnostics*. Must **not** own SVG styling, OEF/XMI mapping,
  ELK geometry, or notation type rules — those belong to plugins.

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

- **Plugins** (`render`, `generic-graph`, `elk-layout`, `archimate-oef-export`,
  `uml-xmi-export`) — leaf executables, each owning one backend concern: SVG/PNG
  rendering, generic-graph source handling, ELK layout, ArchiMate OEF export,
  UML/XMI export. Each owns its backend-specific policy and *only* that. Styling
  lives in render; OEF semantics in the OEF plugin; XMI semantics in the XMI
  plugin; layout geometry in ELK.

- **`dist-tool`** — assembly and distribution: bundles `cli` and the plugins
  into a runnable distribution, third-party-notice and SBOM generation, dist
  smoke. Top of the graph; nothing depends on it.

- **`coverage-report`** — build-only JaCoCo aggregation: hosts `report-aggregate`
  over the product reactor under `-Pcoverage`. Ships nothing and nothing depends
  on it; its product-module dependencies stay confined to the `coverage`
  profile. See §2.

- **`test-support`, `testbeds/plugin-runtime`** — test-only scaffolding;
  `test`-scope only, never on a production compile path.

---

## 4. The contract surface is the product

Because dediren is contract-first, the **public surface is the set of contracts,
not the Java API** (*Cockburn*: the port is the stable thing; *LSP*: the wire
protocol is the compatibility boundary). Treat these as the stable product:

- public JSON schemas under `schemas/`;
- command envelopes on stdout (success and error);
- first-party plugin manifests and runtime capability probes;
- structured diagnostics agents inspect without scraping stderr;
- the documented fixtures under `fixtures/`.

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

## 5. The plugin process boundary

Plugins are a hard boundary — a separate process, reached only through a JSON
contract. This is the system's most important and most expensive seam, chosen
deliberately.

### Why out-of-process (the tradeoff, made explicit)

Out-of-process plugins over JSON buy **fault isolation** (a plugin crash, hang,
or OOM cannot take down core), a real **trust/security boundary**, **language
independence**, and **decoupled versioning** — the wire envelope, not a shared
Java classpath, is the compatibility surface, which sidesteps classpath/JAR
conflicts (*microkernel*, *Richards 2022*; *LSP*). The cost is
serialization/process overhead per call and a protocol that is hard to change
once published. This trade is correct for a CLI that invokes a plugin once per
command rather than in a hot loop. The standing obligation it creates: **treat
the JSON envelope as a versioned public contract** (§4).

The in-process alternative (`ServiceLoader`/classloader plugins) is faster and
typed but reintroduces exactly the fault-coupling and version-coupling this
design removes. Do not migrate a plugin in-process for performance without a
measured latency requirement that justifies losing isolation (a `[runtime]`
quality scenario, §9).

### Rules for the boundary

- **Discovery is explicit, never from `PATH`.** Order: bundled first-party
  plugins, then project plugin directories (`.dediren/plugins`), then
  user-configured directories. Adding implicit `PATH` discovery is prohibited —
  it would erase the trust boundary.
- **Core normalizes the failures it can observe** into structured diagnostics:
  missing executable, timeout, invalid JSON, schema mismatch, unsupported
  capability, id mismatch, process failure. A valid plugin error envelope is
  preserved and surfaced with a non-zero CLI exit; core does not rewrite it.
- **A dependency belongs to the plugin that needs it.** A plugin reports its own
  missing runtime dependency as a structured error envelope (for example the
  export plugins emit `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` /
  `DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE` when `xmllint` is absent). Core does
  not synthesize this category: a launcher that cannot even start its runtime
  yields only a raw non-zero exit, which core surfaces generically (typically
  `DEDIREN_PLUGIN_CAPABILITY_PROBE_FAILED`). Keep this ownership line crisp when
  adding diagnostics.

---

## 6. Shared notation cores: `archimate` and `uml`

This is the system's most load-bearing design tension, so it gets its own rules.
`archimate` and `uml` are **shared kernels** (*Evans 2003*): notation vocabulary
and validation co-owned by every plugin that reads that notation (`render`,
`generic-graph`, and the matching export plugin). A shared kernel trades
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
tier-1 notation cores consumed across the process boundary by the same plugin
roles. Today they differ even in dependency (`uml` → `contracts`, `archimate`
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
| **Fault isolation** (Reliability) | A plugin crash/hang/OOM yields a structured diagnostic and non-zero exit; core stays up. | `core` plugin runner + process boundary (§5) |
| **Offline capability** (Flexibility/Portability) | Export validation runs without network when schema files are supplied (`DEDIREN_OEF_SCHEMA_DIR`, `DEDIREN_XMI_SCHEMA_PATH`). | `schema-cache` + export plugins |
| **Reproducible build** (Maintainability) | The reactor builds from the checked-in Maven Wrapper with repo-local cache, no ambient state. | root reactor / `dist-tool` |

If a future change adds a *latency* or *throughput* requirement (for example,
"render under N ms"), express it as a scenario with a number and an owner before
it is allowed to override an isolation or contract guideline above. Performance
work that erodes the process boundary needs `[runtime]` evidence, not assertion.

---

## 10. Evolving the architecture

Small, boundary-preserving moves (*evolutionary design*). Playbooks for the
common changes:

- **Add a plugin.** New Maven module under `plugins/`, depends on `contracts`
  (+ the notation/utility cores it needs), never on `core`. Ship a manifest and
  capability probe; communicate over the JSON envelope; report its own dependency
  failures as structured diagnostics. Register it for discovery and bundling
  (`dist-tool` runtime dep). Add the cli/dist smoke coverage named in the
  matching `CLAUDE.md` "Files That Move Together" row.

- **Add or extend a notation.** Extend the owning notation core
  (`archimate`/`uml`) with vocabulary as the *exported* surface (§6); update
  consumers (`render`, `generic-graph`, the export plugin) to read it from
  there. Do not re-declare vocabulary in a consumer. If the change is a public
  schema change, move the schema/contracts/fixtures/mapping/tests together (§4).

- **Add a contract surface.** Add records/enums to `contracts` and the schema to
  `schemas/`; bump the schema id only if compatibility actually breaks; update
  fixtures and round-trip tests in the same change. Keep orchestration and
  notation logic out of `contracts`.

- **Add a runtime-boundary diagnostic.** Decide ownership first (§5): a
  dependency the plugin owns is reported by the plugin; a failure core observes
  generically stays a core diagnostic. Add the structured code; do not leak it to
  stderr.

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
- **Process boundary + diagnostics:** the plugin-runtime tests and the
  `plugin-runtime` testbed back §5.
- **Audit gates:** the `CLAUDE.md` `## Audit Gates` table assigns
  `test-quality-audit` and `devsecops-audit` passes per work area; run the one
  the touched area names before calling work complete.

JPMS (`module-info.java`) is intentionally **not** used to enforce boundaries.
For a Maven multi-module CLI with external dependencies, ArchUnit + Enforcer give
deterministic, dependency-friendly enforcement, while JPMS adoption across every
module carries real cost (automatic-module friction with non-modular
dependencies) for encapsulation this design already gets from module structure
and the process boundary. (*JLS 21* §7.7; *JEP 261* — JPMS-for-libraries is a
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
| Notation vocabulary duplicated because the source copy is `private` | `uml/UmlSequenceValidation.java` (private) vs `render/RenderInputValidator.java` (re-declared) | §6 single source of truth | `java.SD-S` / `SD-C` |
| Plugin `Main.java` god-files | `render/Main.java` (~3,851 LOC), `uml-xmi-export/Main.java` (~1,734 LOC) | §8 thinness/cohesion | `java.SD-B` / `SD-Q` |
| Notation-core asymmetry (dependency + endpoint-rule modeling) | `archimate` (standalone, explicit triples) vs `uml` (→`contracts`, inline conditionals) | §6 symmetric role | `SD-S` (low) |
| `CLAUDE.md` "semantics belong in export plugin" reads as contradicting shared cores | wording vs `archimate`/`uml` consumers | §6 validation-vs-mapping distinction | doc/code drift |
| SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` suppressed across the contract DTO records (17 `contracts` value records + `schemacache.SchemaFetchResult`) | `spotbugs-exclude.xml` | §6 contract surface | deferred: two distinct cases — the `contracts` records wrap List/Map components via `ContractCollections.listOrEmpty`/`mapOrEmpty` (`List.copyOf`/`Map.copyOf`), so sharing the reference is genuinely immutable; `schemacache.SchemaFetchResult` is different — mutable `byte[] stdout`/`stderr` exposed by reference, never mutated after construction, and not part of the public `contracts` surface (short-lived internal schema-cache result). Defensive-copying either case is out of scope for the quality-gate wiring |
| SpotBugs `MS_EXPOSE_REP` suppressed in `JsonSupport.objectMapper()` | `spotbugs-exclude.xml` | §4 contract surface | by design: returns the shared `ObjectMapper` singleton, mutable in place (`.configure()`/`.registerModule()`), exposed as one canonical mapper; the reconfiguration risk is first-party/same-JVM only because plugins communicate cross-process over stdin/stdout JSON, so the singleton never crosses a process/trust boundary |
| SpotBugs `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` suppressed in `DistTool` (5 sites) | `spotbugs-exclude.xml` | §3 module charter | deferred: `Path.getFileName()`/`getParent()` on `Files.list(dir)` entries and real bundle/output paths; null branch infeasible; build/dist tool, not shipped runtime |
| SpotBugs `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` suppressed in `PluginRunner.executablePath` | `spotbugs-exclude.xml` | §5 process boundary | deferred: discovered manifest path always resolves under a plugins dir, so `getParent()` is non-null in practice; defining behavior for the infeasible null case is out of scope |

None of these block the architecture; they are propagation-cost and clarity
debts with a clear, local fix when their files are next worked.

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

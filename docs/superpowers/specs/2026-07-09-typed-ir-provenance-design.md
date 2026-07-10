# Typed IR + Source Provenance Design (Plan B)

## Status

Approved design anchor for "Plan B — typed IR + source provenance", the first
follow-up to the Monolithic Compiler Restructure
(`2026-07-08-monolithic-compiler.md`, Phase 1, released `2026.07.14`). This spec
turns the Plan B stub in `2026-07-08-monolithic-runtime-radical.md §Follow-up
plans` into a concrete, phased design. The implementation plan is authored
separately after this spec is approved.

## Purpose

Replace the "stringly" `layout-request` JSON as the *internal* inter-stage
representation with a typed, spanning scene-graph IR that:

1. carries a JSON-Pointer back to the source model on every element, so a
   defect discovered in any phase can point the agent at the exact source
   element to repair; and
2. turns the recorded sequence-diagram defect class (empty bands, arrowhead
   anchoring, fragment chrome — memory `seq-diagram-defects-poc-uljas`) into
   *unrepresentable states* and *IR invariant checks* backed by phase-level
   property tests, replacing hand-authored golden fixtures that have repeatedly
   hidden real ELK-path defects.

The public `layout-request` / `layout-result` JSON stays the schema'd, agent-
facing debug/interop surface; the per-stage commands keep working. This is an
internal-representation change plus one deliberate, additive contract change
(source provenance surfaced on the stage artifacts).

## Decisions (resolved in brainstorming)

- **Scope: full Plan B, phased in one spec** — the typed IR, JSON-Pointer
  provenance, the `semantics-*` notation-front-end split, and typed-IR piping,
  as five sequenced phases (P1–P5 below). (Originally four phases; the P3/P4 line
  was re-sliced into P3/P4/P5 to keep the typed `LayoutIntent` vocabulary with
  its `elk-layout` consumer instead of one phase ahead of it.)
- **Spanning IR.** The typed IR is the single in-memory truth across
  project → layout → render/export. The public `layout-request` /
  `layout-result` records become *serialization DTOs* of the IR.
- **Provenance is embedded in the public stage artifacts.** Every
  `layout-request` / `layout-result` node and edge carries a `sourcePointer`
  (JSON-Pointer); `layout-request.schema` and `layout-result.schema` go
  **v1 → v2**. Diagnostics also gain an optional `sourcePointer`. The schema-id
  change is the compatibility signal (CalVer does not encode compatibility).
- **Full replace of the idealized golden `layout-result` fixtures.** No
  checked-in `layout-result` retains idealized geometry. Geometry confidence
  moves to the real ELK path + IR invariants + property tests; render/export
  input fixtures are regenerated from the real engine as characterization
  snapshots.
- **IR type hierarchy: spine + notation-owned constraints (Approach 1).** A
  unified, typed *structural spine* that the shared layout/quality passes match
  exhaustively, plus a sealed, notation-*owned* constraint/annotation channel
  that lowers into a neutral layout-intent vocabulary at projection time.
- **jqwik** (test scope) for property generators, with a `devsecops-audit`
  Quick pass on the new dependency.

## Module Shape

New modules, dir-aligned, clean packages (the existing `dev.dediren.plugins.*`
packages are **not** renamed here — that stays the contract-cleanup follow-up):

| Module | Package | Depends on | Owns |
| --- | --- | --- | --- |
| `ir` | `dev.dediren.ir` | `contracts` | `SceneGraph`, `LaidOutScene`, the structural spine, `SourcePointer`, `LayoutIntent`, the record↔IR mappers, the neutral geometric invariants, and the notation-invariant / normalization SPIs |
| `semantics-graph` | `dev.dediren.semantics.graph` | `ir`, `contracts` | base projection + the `SemanticsEngine` profile router |
| `semantics-archimate` | `dev.dediren.semantics.archimate` | `ir`, `contracts`, `archimate` | ArchiMate legality + projection |
| `semantics-uml` | `dev.dediren.semantics.uml` | `ir`, `contracts`, `uml` | UML legality + projection + the `SequenceConstraint` family + UML invariants + UML→`LayoutIntent` lowering |

**The `engines/generic-graph` carve.** `GenericGraphProjection` +
`GenericGraphEngine` (one `String semanticProfile` switch, diving into
schemaless `properties["uml"]`) split three ways: the plain/base projection and
the profile router move to `semantics-graph`; the ArchiMate branches to
`semantics-archimate`; the UML branches (including the four `uml.sequence.*`
constraints) to `semantics-uml`. `engines/generic-graph` is deleted, which also
discharges its slice of the §12 `dev.dediren.plugins.*` package-rename debt.

**Engine seam stays stable.** One `SemanticsEngine` implementation in
`semantics-graph` routes by a typed `SemanticProfile` enum to the notation
projection libraries; the split is about where code lives, not adding engines.
`cli` `EngineWiring` and the in-memory registry are unchanged in shape.
`elk-layout` / `render` / `export` engines stay put; their `engine-api` methods
come to speak IR types (P4).

**Dependency direction** stays an acyclic graph rooted at `contracts`:
`contracts → ir → engine-api → {semantics-*, elk-layout, render, export}`;
`core` depends on `engine-api` + `ir`; `cli` wires. The `archimate` / `uml`
notation cores are imported only by their matching `semantics-*` module.
`ir` never depends on `engine-api`, `core`, or any engine.

**ArchUnit** extends the Phase-1 boundary matrix: `ir` imports nothing above
`contracts`; `semantics-uml` and `semantics-archimate` do not depend on each
other; only `semantics-*` import the notation cores; `core` stays blind to
engine implementations; `elk-layout` never imports a `semantics-*` module.

## The IR Types

Twin IR (both in `ir`, both provenance-bearing):

```java
// pre-layout, produced by semantics-*, serializes to layout-request.json
record SceneGraph(SceneMeta meta, List<SceneNode> nodes, List<SceneEdge> edges,
                  List<SceneGroup> groups, List<LayoutIntent> intents,
                  LayoutPreferences preferences) {}

// post-layout, produced by elk-layout, serializes to layout-result.json
record LaidOutScene(SceneMeta meta, List<PlacedNode> nodes,
                    List<RoutedEdge> edges, List<PlacedGroup> groups) {}
```

Structural spine — the shared vocabulary the layout and quality passes match
exhaustively:

```java
record SceneNode(String id, String label, Optional<SizeHint> sizeHint,
                 NodeRole role, Integer partition, LayerConstraint layerConstraint,
                 SourcePointer origin) {}
sealed interface NodeRole permits Plain, Lifeline, InteractionFrame, Junction {}
record SceneEdge(String id, EndpointRef source, EndpointRef target, String label,
                 RelationshipKind kind, EdgePriority priority, SourcePointer origin) {}
record SourcePointer(String jsonPointer) {}   // "/nodes/3", "/relationships/2/properties/uml/order"
```

`InteractionFrame` is the typed role that ends the current `"interaction"` name
collision — the layout frame and the owning-interaction *id* become distinct
typed things, never the same string token. Every spine element carries a
non-null `origin`; a synthesized visual-only element (e.g. an
`InteractionFrame` the projector adds) points at its owning source element,
never null.

### How notation-owned constraints reach layout (the load-bearing mechanism)

`semantics-uml` owns
`sealed interface SequenceConstraint permits LifelineOrder, MessageOrder,
FragmentOpen, OperandOpen` and its invariants. During projection it **lowers**
those into a small notation-neutral `LayoutIntent` vocabulary on the spine —
`AlignmentAxis`, `OrderedBand(minGapBefore)`, `PortSideHint`, `Encloses` — which
is all `elk-layout` ever sees. So `elk-layout` depends only on `ir`, never on
`semantics-uml`. This replaces today's broken arrangement, where
`SequenceLayoutConstraints` re-derives UML geometry *inside* the layout engine
from stringly constraint keys.

**Escape hatch (evidence-gated).** If some sequence normalization genuinely
cannot be expressed as neutral `LayoutIntent` + ELK options, `ir` exposes a
per-notation post-layout `NormalizationPass` SPI that `core` invokes, keeping
the notation-specific step owned by `semantics-uml` and out of `elk-layout`.
Per the ELK-first rule, neutral intent + ELK options are tried first; the SPI is
the documented fallback, not the default.

### Public-record mapping

`contracts.layout.LayoutRequest` / `LayoutResult` become serialization DTOs.
`ir` owns the bidirectional mappers (`SceneGraph ↔ LayoutRequest`,
`LaidOutScene ↔ LayoutResult`). The stringly `LayoutConstraint(kind, subjects)`
DTO stays on the wire (schema-stable apart from the new `sourcePointer` field)
but is produced and consumed only through the typed `LayoutIntent` mapping.

## Data Flow

`build` runs the typed IR in memory, with no stage re-serialization (this is the
change P4 lands — today `BuildCommand` re-serializes and re-parses a JSON
envelope between every stage):

```
source bytes ─parse+validate(SourceValidator)→ SourceDocument
  ─semantics.project(profile)→ SceneGraph        [structural invariants by construction]
  ─elk-layout.layout(SceneGraph)→ LaidOutScene    [geometric invariants checked]
  ─render(LaidOutScene, policy)→ SVG   and/or  ─export(LaidOutScene, …)→ OEF/XMI
```

The per-stage commands keep the public surface by mapping at their edges:
`project` emits `layout-request.json` from a `SceneGraph`; `layout` parses
`layout-request.json` into a `SceneGraph`, lays it out, and emits
`layout-result.json`; `validate-layout` parses `layout-result.json` into a
`LaidOutScene` and runs the invariants. So `build` never touches JSON between
stages, while `… | dediren layout | …` composition stays byte-for-byte stable
(plus the new `sourcePointer` field).

**Render-metadata is out of scope for retyping.** The parallel
`projectRenderMetadata → RenderMetadata` channel (per-element `JsonNode`
styling selectors) is left as-is; `render(LaidOutScene, policy, RenderMetadata)`
keeps its own channel. Its content is SVG-styling selection, not part of the
IR/provenance/invariants prize, and folding it in would pull a render-engine
refactor and a third public-schema family into Plan B for no thrust benefit.

## Invariants And Testing

Invariant catalog, mapped to the recorded defect class (all of A/C/D/D′ and the
2026-07-08 addendum reproduced only through the real engine):

| Invariant | Kind | Retires |
| --- | --- | --- |
| message has exactly two endpoints; fragment has ≥1 operand; lifeline belongs to one interaction | structural → unrepresentable in sealed types | stringly `properties["uml"]` shape bugs |
| every message endpoint sits on its lifeline axis (center) | geometric, on `LaidOutScene` | defect D/D′ + the `onLifelineAxis` edge-vs-center bug |
| message Y strictly increasing; fragment/operand min-gaps honored | geometric | defect C (chrome-vs-label collision) |
| `InteractionFrame` encloses its lifelines and messages | geometric | defect A (empty band / stray frame node) |
| no node overlap; no connector-through-node (frame excluded) | geometric, neutral spine | `LayoutQuality.countOverlaps` / `countConnectorThroughNodes` |

Neutral geometric invariants live in `ir` / `core` (they replace the matching
`LayoutQuality` checks); UML-semantic invariants are contributed by
`semantics-uml` through the invariant SPI. Every failure emits a diagnostic
carrying the offending element's `sourcePointer`.

**Golden-fixture full replace.** The idealized `layout-result` fixtures play two
roles, treated differently:

- *Oracle uses* (a layout test asserting hand-authored geometry ELK never
  produced) are deleted; confidence moves to the real ELK path + invariants +
  property tests.
- *Input uses* (render / oef / xmi tests consuming a `layout-result` as input —
  the three idealized sequence goldens alone are referenced 44 times across
  ~14 test files in 6 modules) are regenerated from the real ELK engine as
  characterization snapshots, checked in and regenerated deliberately via a
  script. Every checked-in `layout-result` then reflects real engine output.

**Determinism precondition.** Reproducible real-engine geometry requires the
bundled Liberation Sans font plus a pinned ELK version — the existing
hermeticity fix (memory `visual-defect-test-suite`). The characterization
approach is gated on it and verified in CI.

**Property tests.** A seeded `SequenceModelGenerator` (jqwik, with shrinking to
minimal counterexamples) emits randomized valid UML-sequence source models; each
runs through the real project → layout path and is asserted against the
geometric invariants (`∀ model: endpoints-on-stems ∧ monotonic-Y ∧
frame-encloses ∧ no-overlap`).

**Audit gate.** Per the repo audit table, this export/engine-touching,
test-heavy work takes a Deep `test-quality-audit`; the new jqwik dependency and
the engine-boundary changes take a Quick `devsecops-audit`.

## Phasing

An expand/contract sequence; each phase is independently landable with `main`
green throughout.

- **P1 — `ir` module + mappers + provenance (the contract change).** Introduce
  the twin IR, spine, `SourcePointer`, and the bidirectional record↔IR mappers.
  Projection sets `origin` on every element; the mapper emits the new
  `sourcePointer` field → `layout-request.schema` / `layout-result.schema`
  **v1 → v2**, carried across the full "files that move together" set (schemas,
  `contracts`, fixtures, round-trip tests, `agent-usage`, diagnostics
  `sourcePointer`). Engines still speak records here (the mappers bridge); the
  only engine change is that `elk-layout` propagates the new `sourcePointer`
  through its record→record transform exactly as it already propagates
  `sourceId`, so `layout-result` v2 is populated. P1 lands as a clean,
  shippable, breaking-schema release on its own.
- **P2 — invariants + property tests + fixture full-replace.** Structural
  invariants become unrepresentable states; geometric invariants run on
  `LaidOutScene` and retire the matching `LayoutQuality` checks; jqwik
  generators; regenerate characterization `layout-result` fixtures; delete the
  idealized-oracle assertions.
- **P3 — carve `generic-graph` → `semantics-graph/archimate/uml` (structural,
  byte-stable).** A pure code-relocation slice: the base/plain projection + the
  profile router move to `semantics-graph`, the ArchiMate legality/projection/
  sizing to `semantics-archimate`, and the UML legality/projection/sizing + the
  four stringly `uml.sequence.*` constraint producers to `semantics-uml`. One
  `SemanticsEngine` (engine id **unchanged**: `generic-graph`) routes by a typed
  profile to a `NotationSemantics` SPI (owned by `engine-api`) that the three
  notation modules satisfy, so the router depends on an abstraction and the two
  notation modules stay independent siblings. Delete `engines/generic-graph`;
  extend ArchUnit (three package constants, sibling-independence, only
  `semantics-*` import the notation cores). **The wire is unchanged** — same
  `contracts` records, same stringly `uml.sequence.*` constraints (relocated, not
  retyped), no schema bump; only the Java package moves
  (`dev.dediren.plugins.genericgraph` → `dev.dediren.semantics.*`), discharging
  that slice of the §12 package-rename debt. The typed `LayoutIntent` /
  `SequenceConstraint` vocabulary is **not** introduced here — it lands in P5
  with its consumer. `elk-layout` / `render` / `export` are untouched.
- **P4 — IR seam flip (plumbing, behavior-preserving).** Flip `engine-api` to
  speak IR end-to-end (`SemanticsEngine` → `SceneGraph`; `LayoutEngine`
  `SceneGraph` → `LaidOutScene`; render/export consume `LaidOutScene`) via
  Parallel Change so `main` stays green per step; `BuildCommand` passes IR in
  memory (no stage re-serialization). `SceneGraph` grows to carry the layout
  constraints so the IR is the full pre-layout truth. Geometry is unchanged: the
  stringly `uml.sequence.*` constraints still ride inside the IR and `elk-layout`
  still consumes them via `SequenceLayoutConstraints`.
- **P5 — typed sequence intent + remove the `elk-layout` re-derivation
  (semantic).** Introduce the typed `LayoutIntent` + `SequenceConstraint` vocab
  (the piece moved out of P3), the notation invariants, and the neutral-invariant
  / `NormalizationPass` SPIs; `semantics-uml` lowers UML sequence rules into
  neutral `LayoutIntent` on the `SceneGraph`; `elk-layout` consumes `LayoutIntent`
  (+ the `NormalizationPass` escape hatch) and the hand-rolled
  `SequenceLayoutConstraints` UML re-derivation is deleted. This is the
  geometry-affecting change, gated by the P2 real-engine invariants + jqwik
  property tests. The typed vocab now lands with its consumer, so nothing is
  dead-weight.

### P5 design resolution (2026-07-10)

Scouting the four seams (elk `SequenceLayoutConstraints`, the `semantics-uml`
producer, the `ir`/`engine-api` type surface, the invariant/property-test gates)
surfaced the Risk-R1 fork the P5 bullet left open: the four `uml.sequence.*`
constraints carry **only ordering + markers, no coordinates** — every number
(column pitch 96, y-lattice steps 24/46/68, stem = head-box center, head band =
min-y, interaction frame = bbox of lifelines + message points) is invented inside
elk. So "delete the re-derivation and consume a neutral `LayoutIntent`" is not a
mechanical retype. Resolved decisions:

- **Geometry ownership.** The post-ELK geometry mechanics are *neutral* ("order
  along an axis", "share a band") and stay in `elk-layout`, now driven by typed
  `LayoutIntent` instead of the four magic strings. What is deleted is the
  **notation coupling** — elk stops knowing `uml.sequence.*`; the
  `SequenceLayoutConstraints` class is replaced by a neutral `LayoutIntent`
  normalizer. ELK-first is preserved (no new custom geometry, only retyped
  inputs).
- **Neutral `LayoutIntent` vocab (in `ir`, sealed), YAGNI-pruned to what is
  emitted.** `OrderedBand(Axis, List<BandMember> members)` where
  `BandMember(String id, double leadingGap)` — lifelines along X (columns +
  non-overlap rebuild, `leadingGap` 0) and messages along Y (strictly-increasing
  rows, `leadingGap` carrying the fragment/operand spacing); `AlignmentAxis` —
  lifeline heads share one top band. **`PortSideHint` dropped** (elk re-derives
  source/target port side from the lifeline `OrderedBand(X)`); **`Encloses`
  dropped** (interaction-frame enclosure is driven by the neutral scene
  `role=="interaction"`, not by a constraint). A future notation that needs either
  re-adds it to the sealed family then.
- **Sealed `SequenceConstraint` (in `semantics-uml`).** `LifelineOrder` /
  `MessageOrder` / `FragmentOpen` / `OperandOpen` — the typed form of today's four
  producers. Lowering: `LifelineOrder → OrderedBand(X) + AlignmentAxis`;
  `MessageOrder` + `FragmentOpen` + `OperandOpen` fold into one
  `OrderedBand(Y)` whose per-member `leadingGap` is 0 / 46 / 68. `semantics-uml`
  owns those numbers (they are render-fragment-chrome-coupled — a shared constant
  with the render engine, guarded by the existing sequence-fragment alignment
  test); elk stays value-agnostic.
- **Fragment/operand gaps → declarative data on `LayoutIntent`, NOT a
  `NormalizationPass`.** Every sequence geometry input (columns, head band,
  message rows, gaps, port sides, enclosure) is expressible as declarative data,
  so R1's escape-hatch risk does not materialize on the actual port. The decisive
  force is **wire self-sufficiency**: the `layout-request` is a serialization DTO
  consumed by a standalone `layout` command that runs *no notation*
  (README documents `project --target layout-request | layout` as a first-class
  equivalent of `build`, with a worked example). A `NormalizationPass` is
  *behavior* the producer holds — it cannot serialize, and the standalone path
  could never reconstruct it, so `project | layout` would silently lose the
  fragment gaps `build` keeps (a regression on a documented invariant). Keeping
  the gaps as data on the `OrderedBand` keeps the wire complete, keeps
  `build ≡ project|layout`, and makes **both elk and the wire notation-free**
  (elk consumes typed `LayoutIntent`; the notation-free codec that serializes
  `LayoutIntent ↔ stringly LayoutConstraint` lives in `ir`'s `LayoutRequestMapper`
  and knows only its own neutral encoding). **No `NormalizationPass` SPI in P5** —
  with everything declarative it would have no consumer (speculative generality,
  the exact smell the P3→P5 re-slice avoided); it is deferred until a genuine
  non-serializable normalization need appears. **No schema-id bump** — the wire
  `constraint` shape (`{id, kind, subjects:[string]}`, free-form `kind`) is
  preserved; the neutral kinds + gap encoding sit inside it. `LayoutEngine.layout`
  keeps its P4 `SceneGraph → LaidOutScene` signature (no pass parameter); all
  post-ELK geometry mechanics stay co-located in `elk-layout`, now driven by the
  typed intent.
- **Invariants → `validate-layout`** (absorbs the P2-deferred item). The three
  `SequenceInvariants` wire into `CoreCommands.validateLayoutResult` (the single
  funnel for CLI validate + in-memory build), bridging `LayoutResult →
  LaidOutScene` via `LaidOutSceneMapper`. **`core` gains an `ir` dependency** — a
  deliberate new edge (P2 avoided it) recorded in the dep-table + guidelines.
- **Test scope.** Extend the jqwik generator to `CombinedFragment` /
  `InteractionOperand` (closes W3, since P5 changes fragment geometry); **W1**
  (ExecutionSpecification / Destruction / create-delete — zero constraints today,
  untouched by P5) stays a tracked follow-up guarded by the characterization
  fixtures. Regenerate the 3 real `uml-sequence-*` fixtures; port the 2
  direct-construction elk tests; the jqwik property test stays the primary
  geometry gate.
- **Move-together surfaces.** `semantics-uml/pom.xml` + `core/pom.xml` gain `ir`;
  the §2 dep-table rows, the §12 debt register, `spotbugs-exclude.xml` (the new
  `ir` `LayoutIntent`/`OrderedBand` records join the existing `ir` EI_EXPOSE_REP
  suppressions), the render/`semantics-uml` shared fragment-gap constant + its
  alignment test, and the `elkLayoutDoesNotImportSemantics` `because`-string
  update together. No ArchUnit *rule* has to loosen — `semantics-uml → ir` and
  `core → ir` are already permitted by the forbidden lists; no wire schema-id
  bump (the `constraint` shape is unchanged).

## Costs And Reversals

- **Blast radius.** The `engine-api` signature change (P4) touches all five
  engines + `core` dispatch + `cli` wiring. The schema v1 → v2 bump (P1) touches
  the move-together set. The fixture replace (P2) touches ~14 test files.
- **Reversibility.** P1–P2 are largely additive (mappers bridge old and new);
  P3 is a byte-stable structural carve (a reversible module move with no wire
  change); P4–P5 are structural seam/geometry changes and harder to revert.
  Parallel change keeps `main` releasable at every phase boundary.
- **Release/versioning.** The P1 schema-id bump is the compatibility signal
  (CalVer does not encode it): breaking-change release notes + a separate
  version-bump commit + an annotated `v<version>` tag per `release-policy`,
  whenever P1 is released.

## Risks

- **R1 — neutral `LayoutIntent` may not express every sequence normalization.**
  Validate in P5 by porting one sequence rule first; the `NormalizationPass` SPI
  is the named, evidence-gated escape hatch (ELK-first preserved).
- **R2 — characterization-fixture determinism** across CI and local runs. Gated
  on bundled Liberation Sans + pinned ELK; verified in CI.
- **R3 — IR↔record round-trip fidelity.** The mappers are the risk surface;
  round-trip tests are their gate.
- **R4 — plan size.** Four phases is a lot; mitigated by making each phase
  independently shippable and by holding the non-goals below.

## Non-Goals

- No Plan C (serve/MCP, content-addressed cache, watch).
- No `dev.dediren.plugins.*` package rename beyond deleting `generic-graph`
  (the rest stays the contract-cleanup follow-up).
- No new notations.
- No render-metadata retyping.
- No native-image / AOT exploration.
- No custom ELK placement or route geometry (ELK-first intact).

## What This Revises

This spec implements the `ir` and `semantics-*` module rows that
`2026-07-08-monolithic-compiler.md §Module Shape` recorded as "deferred to
Plan B", and delivers the two benefits named in its "Why the IR Is the Prize"
section. It does not revise the Phase-1 engine seam, the command envelope
contract, the diagnostics vocabulary agents branch on, or the ELK-first rule —
all preserved.

## Evidence Record

- Architecture map (this session): one `GenericGraphProjection` (365 lines)
  handles all notations by `String semanticProfile`; the stringly seams are
  exactly `LayoutConstraint.kind`, `LayoutNode.role`, `LayoutEdge.relationshipType`;
  provenance is half-built as `sourceId` / `projectionId` id-strings (no
  JSON-Pointer); the sequence invariant is expressed three times across
  `generic-graph`, `elk-layout` (`SequenceLayoutConstraints`), and `core`
  (`LayoutQuality`); `engine-api` is already typed and clean; `BuildCommand`
  pipes stages as re-serialized JSON envelopes; the notation rule tables already
  live in the separate `archimate` / `uml` code modules.
- Fixture set: 15 `layout-result` fixtures (4 UML-sequence); the three idealized
  sequence goldens are referenced 44 times across ~14 test files in 6 modules;
  no property-testing library present today.
- Defect record: memory `seq-diagram-defects-poc-uljas` (defects A/C/D/D′ + the
  2026-07-08 addendum), all reproduced only through the real engine; golden
  sequence fixtures documented as defect-hiding.

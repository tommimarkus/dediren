# Dediren ELK Per-Edge Priority Hints

Date: 2026-07-06
Status: Draft
Scope: Public JSON contracts (`schemas/model.schema.json`,
`schemas/layout-request.schema.json`), `contracts` records, the `generic-graph`
projection, and the `elk-layout` Java helper (`ElkLayeredOptions`,
`ElkLayoutEngine`, `LayoutJson`) plus their fixtures, tests, and docs.
Perspective: expose ELK Layered's per-edge priority controls as stable,
Dediren-owned symbolic layout intent, mirroring the shipped per-node
placement-hints slice, without leaking raw ELK option names into `.dediren`
files.

## Purpose

The `elk-layout` helper today sets **no** per-edge ELK option — every
edge-affecting control is graph-scoped root configuration (`EDGE_ROUTING`,
merge, spacing). Individual connections cannot carry layout intent. This is the
edge-side gap left by the ELK Layered capability-vocabulary project: nodes
gained per-element hints (`layer_constraint`, `partition`) in the placement-hints
slice, but the symmetric per-edge surface was deferred.

ELK Layered honors three distinct, orthogonal per-edge priority options, each
evaluated in a different phase of the algorithm. This slice exposes all three as
Dediren-owned symbolic vocabulary and applies them to individual ELK edges.

This is a continuation of, and correction to, the umbrella spec
`docs/superpowers/specs/2026-07-05-dediren-elk-layered-capability-vocabulary-design.md`.
That spec's element-scoped table (§B) listed edge `priority` mapping to
`PRIORITY / PRIORITY_STRAIGHTNESS / PRIORITY_DIRECTION` as a single deferred
knob. Verification against ELK 0.11.0 shows the real surface is **three**
phase-scoped options — the omitted one is `PRIORITY_SHORTNESS`. This spec
supersedes that row.

## Decisions locked during brainstorming

1. **Shape: three phase-scoped integers, not one knob.** A `priority` object on
   each edge with three optional int sub-fields, one per ELK priority option.
   Chosen over a single generic `priority` (which would reach only straightness,
   losing coverage) and over a symbolic tier (`low|normal|high`, which cannot
   express the relative ordering that a priority weight requires). This honors
   the umbrella spec's "model *every* missing capability" decision.
2. **Raw integers, following the `partition` precedent.** The node-hints slice
   already puts a raw `partition: <int>` in public JSON. Priority is a relative
   weight whose ordering is the whole point, so a raw int is the faithful value
   type; a symbolic tier would erase relative ordering. This does not leak an ELK
   option *name* — the "no raw ELK names" boundary concerns option ids, not
   integer values.
3. **Full phase-coupling rejection.** Each priority only takes effect under a
   specific phase strategy. When a priority is set while the governing phase
   strategy cannot honor it, the request is rejected with a structured error
   envelope, per the umbrella spec's decision 5 ("incompatible combinations are
   rejected, not silently ignored"). ELK itself silently ignores these; Dediren
   surfaces them so agents can decide failure from stdout JSON alone.
4. **`routing.profile` cleanup is out of scope.** The unused
   `LayoutRoutingProfile` field is a separate finding, not folded into this
   slice.

## Vocabulary

A single optional `priority` object on each relationship. Dediren-owned intent
names (snake_case keys, matching existing `layer_constraint` /
`high_degree_nodes`); all three sub-fields optional integers.

```jsonc
// a relationship in a .dediren model
"priority": {
  "resist_reversal": 5,   // ELK PRIORITY_DIRECTION    — cycle-breaking phase
  "keep_short":      2,   // ELK PRIORITY_SHORTNESS    — layering phase
  "keep_straight":   8    // ELK PRIORITY_STRAIGHTNESS — node-placement phase
}
```

Semantics (from the ELK 0.11.0 layered phase code):

- `resist_reversal` — how strongly to keep the edge pointing in the overall
  layout direction, i.e. resist reversing it during cycle breaking. Read by
  `GreedyCycleBreaker`.
- `keep_short` — how strongly to keep the edge short (few layers spanned). Read
  by `NetworkSimplexLayerer`.
- `keep_straight` — how strongly to keep the edge straight (axis-aligned). Read
  by the Brandes-Köpf, linear-segments, and network-simplex node placers.

Absent sub-field = ELK option unset = current default behavior. Absent
`priority` object = unchanged. `additionalProperties: false` on the object.

## Contract mechanics and files that move together

Following CLAUDE.md "Files That Move Together" for public JSON shape and layout
changes, and mirroring the node-hints pipeline
(`SourceNode` → `GenericGraphProjection` → `LayoutNode` → `applyNodeHints`):

- `schemas/model.schema.json` — add the `priority` object `$def` to
  `sourceRelationship`.
- `schemas/layout-request.schema.json` — mirror the same shape on the edge.
- `contracts` — new record
  `LayoutEdgePriority(Integer resistReversal, Integer keepShort, Integer keepStraight)`;
  add a typed `priority` field to `SourceRelationship`; add a
  `LayoutEdgePriority priority` field to `LayoutEdge` with backward-compatible
  delegating constructors (existing 5-arg and 6-arg constructors delegate with
  `null`, keeping all call sites green).
- `plugins/generic-graph` — `GenericGraphProjection` projects
  `relationship.priority()` into the `new LayoutEdge(...)` call.
- `plugins/elk-layout` —
  - `ElkLayeredOptions.applyEdgeHints(ElkEdge, LayoutEdge)` maps present
    sub-fields to `LayeredOptions.PRIORITY_DIRECTION`,
    `LayeredOptions.PRIORITY_SHORTNESS`, `LayeredOptions.PRIORITY_STRAIGHTNESS`.
    This is the first per-edge `setProperty` in the plugin, the symmetric
    counterpart to `applyNodeHints`.
  - `ElkLayoutEngine` calls `applyEdgeHints` immediately after each
    `createRoutedEdge` in every edge-building loop (flat and grouped).
  - Cross-field validation rejects incompatible priority/strategy combinations
    (see below).
- Fixtures under `fixtures/source`, `fixtures/layout-request` /
  `fixtures/layout-result`, and `fixtures/plugins`.
- Tests: `ContractRoundTripTest`, schema valid/invalid tests, `LayoutJson` /
  helper mapping tests, engine rejection tests, and one ignored real-ELK render
  fixture.
- Docs: `README.md`, `docs/agent-usage.md`, `docs/features/layout.md`, and
  `docs/threat-model.md`.

## Conditional validity and validation policy

### Inherited gate (no new code)

The three priorities are layered-only. They inherit the existing gate for free:
the `algorithm` field is boundary-locked to `layered`, and `packed` mode already
requires an edge-less request (`ElkLayoutEngine` rejects any edges under packed).
An edge — and therefore an edge priority — can only appear under `layered`. No
new algorithm-gate code is needed.

### New phase-coupling rejection

New error code `DEDIREN_LAYOUT_EDGE_PRIORITY_UNSUPPORTED_FOR_STRATEGY`, with a
JSON path to the offending edge, rejects a set priority whose governing phase
strategy cannot honor it. **An absent strategy means the ELK default, which
supports its priority** — so a priority set against defaults is always valid.

Matrix (verified against ELK 0.11.0 phase code):

| Sub-field | Honored when strategy is | Rejected when strategy is |
| --- | --- | --- |
| `resist_reversal` | `cycle_breaking` ∈ {`greedy`, absent} | `cycle_breaking` ∈ {`depth-first`, `model-order`} |
| `keep_short` | `layering.strategy` ∈ {`network-simplex`, absent} | any other explicit `layering.strategy` |
| `keep_straight` | `placement.strategy` ∈ {`brandes-koepf`, `network-simplex`, `linear-segments`, absent} | `placement.strategy: simple` |

Rationale for the matrix: ELK's BFS/DFS/depth-first cycle breakers explicitly
document that they ignore `PRIORITY_DIRECTION`; only `GreedyCycleBreaker` reads
it. `PRIORITY_SHORTNESS` is read only by `NetworkSimplexLayerer`.
`PRIORITY_STRAIGHTNESS` is read by every node placer except `SimpleNodePlacer`.

Enum/type validation (non-integer values, unknown keys) is rejected with the
existing unsupported-value / null-rejection diagnostics extended to the new
fields, matching the `LayoutJson.rejectUnsupportedPreferenceValues` pattern.

## Testing strategy

- **Schema**: valid case with all three sub-fields; valid case with a subset;
  invalid cases for each of the three rejected priority/strategy combinations;
  invalid case for a non-integer sub-field and an unknown key.
- **Contract round-trip**: serialize/deserialize `LayoutEdgePriority` and a
  `LayoutEdge` / `SourceRelationship` carrying it.
- **Helper mapping**: assert each sub-field sets the expected `LayeredOptions`
  property on the `ElkEdge`; assert absent sub-fields leave the property unset.
- **Engine rejection**: assert each of the three incompatible combinations
  produces `DEDIREN_LAYOUT_EDGE_PRIORITY_UNSUPPORTED_FOR_STRATEGY` with the
  correct edge JSON path, and that priority-against-defaults is accepted.
- **Real render**: one ignored real-ELK render fixture exercising edge priority
  as geometry evidence (renders can be stale — regenerate before review).
- **Distribution**: `AgentUsageDocConsistencyTest` stays green for the new
  `DEDIREN_*` code.

## Version and schema id

- Adding optional fields is backward-compatible; the public schema id stays
  `model.schema.v1`. Compatibility is communicated through release notes.
- No version bump in this slice: it stays on local `main` with the rest of the
  unreleased ELK Layered capability-vocabulary work, per that project's
  established "no bump until release" pattern. A separate version-bump commit and
  `v<version>` tag follow when the vocabulary project is released.

## Risks and open questions

- **Field naming.** `resist_reversal` / `keep_short` / `keep_straight` are
  Dediren-owned intent names, not ELK's `direction` / `shortness` /
  `straightness`. They are bikesheddable; the mapping layer absorbs any rename.
- **Strictness cost.** Full phase-coupling rejection adds a coupling matrix to
  maintain against ELK phase-strategy changes. Accepted per decision 3; the
  matrix is small and centralized in the helper.
- **ELK version drift.** If a future ELK version changes which phase reads which
  priority, the matrix and its tests must be revisited — the same drift risk the
  umbrella spec already accepts for the phase-strategy vocabulary.

## Sources

- ELK Layered reference:
  <https://eclipse.dev/elk/reference/algorithms/org-eclipse-elk-layered.html>
- ELK 0.11.0 layered phase code (verified locally): `GreedyCycleBreaker`,
  `NetworkSimplexLayerer`, Brandes-Köpf / `LinearSegmentsNodePlacer` /
  `NetworkSimplexPlacer`, and the `does not support PRIORITY_DIRECTION` notes on
  the BFS/DFS/depth-first cycle breakers.
- Umbrella spec (superseded §B priority row):
  `docs/superpowers/specs/2026-07-05-dediren-elk-layered-capability-vocabulary-design.md`
- Node-hints precedent:
  `docs/superpowers/plans/2026-07-05-elk-node-placement-hints.md`

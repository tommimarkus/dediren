# Dediren ELK Layered Capability Vocabulary

Date: 2026-07-05
Status: Draft
Scope: Public JSON contracts (`schemas/model.schema.json`,
`schemas/layout-request.schema.json`), `contracts` records/enums, the
`generic-graph` projection, and the `elk-layout` Java helper
(`ElkLayeredOptions`, `ElkLayoutEngine`) plus their fixtures, tests, and docs.
Perspective: expose the ELK Layered and ELK core capabilities the helper
currently pins or leaves unavailable as stable, Dediren-owned layout intent,
without leaking raw ELK option names into `.dediren` files.

## Purpose

Today the `elk-layout` helper wires a fixed slice of ELK: the `layered`
algorithm only, orthogonal edge routing only, Brandes-Köpf node placement only,
and ELK defaults for cycle breaking, layering, and crossing minimization. Many
readable-layout controls ELK already implements are unreachable from a
`.dediren` file.

This spec defines a complete Dediren-owned symbolic vocabulary that makes those
controls configurable as layout intent, and a mapping layer that translates the
vocabulary into ELK options internally. It is an **umbrella spec**: it defines
the full surface and then sequences the build into independently shippable
slices, each of which gets its own implementation plan.

The Dediren-owned work is:

- add symbolic, Dediren-named layout vocabulary covering the missing ELK
  Layered and ELK core capabilities;
- map that vocabulary to ELK options inside the helper, with defaults when
  absent;
- validate incompatible combinations and reject them as structured error
  envelopes;
- keep `model.schema` the coordinate-free source of truth and project intent
  through `generic-graph` into `layout-request`;
- maintain schema, round-trip, helper, and real-render regression coverage.

The Dediren-owned work is **not**:

- a raw ELK option passthrough that makes `.dediren` files depend on ELK class
  names or option ids (explicitly ruled out by the layout-preferences design and
  the elk-first cleanup plan);
- authored or seed coordinates in source graph JSON (interactive/incremental
  layout is excluded — see Decisions);
- a parallel layout engine or post-ELK geometry rewriting;
- renderer changes that mask unreadable layout output.

## Decisions locked during brainstorming

1. **Coverage.** Model *every* missing capability as first-class Dediren
   vocabulary — not a curated subset, and not a raw ELK escape hatch.
2. **Interactive/incremental layout is excluded.** It requires ELK to read seed
   coordinates, which would mean authored coordinates in source JSON — a
   boundary the elk-first cleanup plan deliberately holds. This removes ELK's
   `interactive` mode, `interactiveReferencePoint`, and the `INTERACTIVE`
   variants of cycle breaking, crossing minimization, and node placement. That
   leaves the remaining capability families — enumerated in the Vocabulary
   section — in scope.
3. **Naming philosophy: ELK-mirroring Dediren enums.** Symbolic values are
   Dediren-owned (kebab-case, no `org.eclipse.elk` prefix), defined and
   defaulted in `contracts`, validated by Dediren, and mapped to ELK constants
   only inside the helper. This is the only shape that delivers 1:1 coverage;
   abstract intent words (`layering: "tight"`) cannot express distinct ELK
   strategies without losing coverage.
4. **Accepted coupling.** With ELK-mirroring enums, the *shape* of the contract
   tracks ELK's phase model (cycle-breaking → layering → crossing → placement →
   routing) even though the names are Dediren-owned. The mapping layer contains
   the cost of any future ELK reorganization. This coupling is accepted as the
   price of full coverage.
5. **Incompatible combinations are rejected**, not silently ignored, so agents
   can decide failure from stdout JSON alone.

## Vocabulary

The vocabulary has two homes because ELK options split into graph-scoped and
element-scoped controls.

### A. Graph-scoped — extends the existing `layout_preferences` block

| Dediren vocabulary | ELK target | Notes |
| --- | --- | --- |
| `algorithm: layered \| tree \| radial \| force \| stress \| packed` | `CoreOptions.ALGORITHM` | default `layered`; gates the layered-only knobs below |
| `cycle_breaking: greedy \| depth-first \| model-order` | `CYCLE_BREAKING_STRATEGY` | interactive variant dropped |
| `layering.strategy: network-simplex \| longest-path \| coffman-graham \| min-width \| stretch-width \| breadth-first \| depth-first` | `LAYERING_STRATEGY` | default `network-simplex` |
| `layering.node_promotion: off \| on` | `NODE_PROMOTION_STRATEGY` | optional refinement; symbolic `on` maps to a chosen ELK promotion strategy |
| `crossing.strategy: layer-sweep \| none` | `CROSSING_MINIMIZATION_STRATEGY` | interactive dropped |
| `crossing.greedy_switch: off \| one-sided \| two-sided` | `GREEDY_SWITCH_TYPE` | |
| `placement.strategy: brandes-koepf \| network-simplex \| linear-segments \| simple` | `NODE_PLACEMENT_STRATEGY` | currently pinned to `brandes-koepf` |
| `routing.style: orthogonal \| polyline \| spline` | `CoreOptions.EDGE_ROUTING` | extends today's orthogonal-only enum |
| `compaction: off \| left \| right \| balanced` | post-compaction strategy | |
| `components.separate: true \| false` | `SEPARATE_CONNECTED_COMPONENTS` | |
| `components.spacing: compact \| readable \| spacious` | `SPACING_COMPONENT_COMPONENT` | reuses the density tiers |
| `high_degree_nodes: off \| on` | `HIGH_DEGREE_NODES_TREATMENT` | |
| `thoroughness: low \| normal \| high` | `THOROUGHNESS` | symbolic tier, not a raw integer |

`routing.style` extends the existing `LayoutRoutingStyle` enum
(`orthogonal` only today). `routing.profile` and `routing.endpoint_merging`
are unchanged.

### B. Element-scoped — new per-node / per-edge layout hints on `model.schema`

These attach to individual graph elements, so they are new optional attributes
on nodes and edges, projected through `generic-graph` into the layout request.

| Dediren vocabulary | Element | ELK target |
| --- | --- | --- |
| graph `partitioning: on \| off` + node `partition: <int>` | graph + node | `PARTITIONING_ACTIVATE`, `PARTITIONING_PARTITION` |
| node `layer_constraint: first \| last \| first-separate \| last-separate \| none` | node | `LAYERING_LAYER_CONSTRAINT` |
| node `layer_choice: <int>` | node | `LAYER_CHOICE_CONSTRAINT` |
| node `position_choice: <int>` | node | `POSITION_CHOICE_CONSTRAINT` |
| node `priority: <int>` and edge `priority: <int>` | node, edge | `PRIORITY` / `PRIORITY_STRAIGHTNESS` / `PRIORITY_DIRECTION` |

The element-hint surface is the larger schema change: it introduces optional
layout-hint attributes on every node and edge rather than fields on a single
top-level object.

## Conditional validity and validation policy

Most graph-scoped knobs apply only under `algorithm: layered`. The helper
validates requested combinations and rejects incompatible ones with a structured
error envelope, matching the existing `validateRoutingPreferences` /
`rejectNull` pattern.

Rules:

- Layered-only knobs (`cycle_breaking`, `layering.*`, `crossing.*`,
  `placement.*`, `compaction`, `high_degree_nodes`, `thoroughness`) are rejected
  when `algorithm` is not `layered`. New code:
  `DEDIREN_LAYOUT_OPTION_UNSUPPORTED_FOR_ALGORITHM`.
- Element-scoped hints that are layered-only (`layer_constraint`, `layer_choice`,
  `position_choice`, `partition`) are rejected under non-layered algorithms with
  the same code and a JSON path to the offending element.
- Enum values outside the documented set are rejected with the existing
  unknown-value diagnostic, extended to name the new fields.
- Absent fields fall back to the current hardcoded defaults, so existing
  fixtures and behavior are unchanged.

## Contract mechanics and files that move together

Following CLAUDE.md "Files That Move Together" for public JSON shape and layout
changes, each slice updates as a set:

- `schemas/model.schema.json` — extend `layoutRoutingPreferences` /
  `layoutPreferences` `$defs`; add node/edge layout-hint `$defs`.
- `schemas/layout-request.schema.json` — mirror the same shapes.
- `contracts` — new enums (`LayoutAlgorithm`, `LayeringStrategy`,
  `CycleBreaking`, `CrossingStrategy`, `PlacementStrategy`, extended
  `LayoutRoutingStyle`, etc.) and records for element hints; extend
  `LayoutPreferences` and the node/edge records.
- `plugins/generic-graph` — project new preferences and element hints into the
  layout request.
- `plugins/elk-layout` — `ElkLayeredOptions` gains the symbolic→ELK mapping;
  `ElkLayoutEngine` gains cross-field validation and per-element application.
- Fixtures under `fixtures/source`, `fixtures/layout-result`, and
  `fixtures/plugins`.
- Tests: `ContractRoundTripTest`, schema valid/invalid tests, `LayoutJson` /
  helper mapping tests, and ignored real-ELK render evidence.
- Docs: `README.md`, `docs/agent-usage.md`, `docs/features/layout.md`, and
  `docs/threat-model.md` (the envelope/validation surface changes).

### Version and schema id

- Adding optional fields is backward-compatible, so the public schema id stays
  `model.schema.v1`. Compatibility is communicated through release notes, per
  the CalVer-does-not-encode-compatibility rule.
- Each shipped slice that changes plugin behavior or public contracts takes a
  separate version-bump commit with the matching `v<version>` tag, per
  `release-policy`. Run the stale-version search after each bump.

## Slice sequencing

Too large for one commit. Each slice is independently valuable and gets its own
implementation plan via the writing-plans skill.

1. **Routing styles** — add `polyline` and `spline` to `routing.style`.
   Smallest surface, highest immediate value, extends one enum.
2. **Layered phase strategies** — `cycle_breaking`, `layering.*`, `crossing.*`,
   `placement.strategy`. Graph-scoped, self-contained, no new element surface.
3. **Graph tuning** — `compaction`, `components.*`, `high_degree_nodes`,
   `thoroughness`.
4. **Alternate algorithms** — `algorithm` field plus the conditional-validity
   framework that gates slices 2–3 under `layered`.
5. **Element-scoped hints** — `partition`/`partitioning`, `layer_constraint`,
   `layer_choice`, `position_choice`, node/edge `priority`. Largest schema
   change; last.

Slice 4 introduces the algorithm gate that slices 2–3 are validated against; if
slice 4 ships before 2–3 in practice, the gate can be added with 4 and the
earlier slices validated as layered-only from the start. The plans will pin the
exact ordering; the default above front-loads value and defers the biggest
schema change.

## Testing strategy

- **Schema**: valid + invalid cases per new field and per rejected
  cross-field combination.
- **Contract round-trip**: serialize/deserialize each new enum and element hint.
- **Helper mapping**: assert each symbolic value sets the expected ELK option;
  assert incompatible combinations produce the documented error envelope.
- **Real render**: ignored real-ELK render tests for at least one fixture per
  algorithm and per routing style, as geometry evidence (renders can be stale —
  regenerate before review).
- **Distribution**: `AgentUsageDocConsistencyTest` stays green for any new
  `DEDIREN_*` codes and version strings.

## Risks and open questions

- **Surface size.** This is a large, permanent public surface, much of it
  speculative relative to today's diagram types. Slicing bounds the risk per
  commit but not the long-term support cost. Accepted per the coverage decision.
- **ELK version drift.** The mapping layer must be revisited on ELK upgrades;
  enum values that ELK removes become Dediren-rejected values, a contract change
  to sequence carefully.
- **Alternate-algorithm fidelity.** `force`/`stress`/`radial` interact
  differently with ports and hierarchy than `layered`; per-algorithm real-render
  evidence is required before each is documented as supported. If an algorithm
  cannot honor Dediren's port/hierarchy intent acceptably, it is deferred rather
  than shipped half-working.
- **Symbolic tiers vs raw numbers.** `thoroughness` and `components.spacing` use
  symbolic tiers to avoid leaking raw ELK numeric ranges; if agents need finer
  control later, that is a separate, additive decision.

## Sources

- ELK Layered reference:
  <https://eclipse.dev/elk/reference/algorithms/org-eclipse-elk-layered.html>
- ELK algorithm catalog:
  <https://eclipse.dev/elk/reference/algorithms.html>
- ELK options reference:
  <https://eclipse.dev/elk/reference/options.html>
- Prior Dediren layout design (no-raw-names boundary):
  `docs/superpowers/specs/2026-05-15-dediren-elk-libavoid-layout-routing-strategy.md`
- Layout preferences implementation history:
  `docs/superpowers/plans/2026-05-16-dediren-layout-preferences.md`
- ELK-first routing cleanup (out-of-scope boundaries):
  `docs/superpowers/plans/2026-05-20-dediren-elk-first-routing-cleanup.md`

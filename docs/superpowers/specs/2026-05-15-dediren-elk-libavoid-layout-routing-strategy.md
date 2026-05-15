# Dediren ELK + Libavoid Layout Routing Strategy

Date: 2026-05-15
Status: Draft specification
Scope: `elk-layout` Java helper graph construction, ELK Layered options, and
ELK Libavoid options. This spec does not change public contracts by itself.
Perspective: identify the options currently hardcoded in the helper and decide
which should reasonably become JSON-configurable layout intent.

## Purpose

Dediren should improve generated architecture renders by tuning the layout and
routing data it gives to ELK, not by becoming its own layout engine. ELK Layered
remains the authority for generated node, group, port, and hierarchy placement.
ELK Libavoid remains the authority for obstacle-avoiding connector routes over
fixed geometry.

The Dediren-owned work is:

- map backend-neutral layout requests into an ELK graph that expresses intent;
- choose a small set of documented ELK and Libavoid options;
- expose diagnostics and quality metrics that reveal when a layout is hard to
  read;
- maintain regression fixtures using real ELK output.

The Dediren-owned work is not:

- authored absolute geometry in source graph JSON;
- a parallel router that reconstructs connector doglegs after Libavoid;
- semantic-type-specific layout behavior;
- renderer fixes that hide invalid or unreadable layout output;
- a raw ELK option passthrough that makes `.dediren` files depend on ELK class
  names and option ids.

## Sources

Primary sources:

- ELK Layered reference:
  <https://eclipse.dev/elk/reference/algorithms/org-eclipse-elk-layered.html>
- ELK Libavoid reference:
  <https://eclipse.dev/elk/reference/algorithms/org-eclipse-elk-alg-libavoid.html>
- ELK Libavoid integration notes:
  <https://eclipse.dev/elk/blog/posts/2022/22-11-17-libavoid.html>
- Libavoid integration repository:
  <https://github.com/TypeFox/elk-libavoid>
- Libavoid routing parameters and options:
  <https://www.adaptagrams.org/documentation/namespaceAvoid.html>
- ELK options:
  - `mergeEdges`:
    <https://eclipse.dev/elk/reference/options/org-eclipse-elk-layered-mergeEdges.html>
  - `mergeHierarchyEdges`:
    <https://eclipse.dev/elk/reference/options/org-eclipse-elk-layered-mergeHierarchyEdges.html>
  - `portConstraints`:
    <https://eclipse.dev/elk/reference/options/org-eclipse-elk-portConstraints.html>
  - `segmentPenalty`:
    <https://eclipse.dev/elk/reference/options/org-eclipse-elk-alg-libavoid-segmentPenalty.html>
  - `shapeBufferDistance`:
    <https://eclipse.dev/elk/reference/options/org-eclipse-elk-alg-libavoid-shapeBufferDistance.html>
  - `idealNudgingDistance`:
    <https://eclipse.dev/elk/reference/options/org-eclipse-elk-alg-libavoid-idealNudgingDistance.html>
  - `nudgeSharedPathsWithCommonEndPoint`:
    <https://eclipse.dev/elk/reference/options/org-eclipse-elk-alg-libavoid-nudgeSharedPathsWithCommonEndPoint.html>

Experience reports from upstream issue threads:

- Libavoid port-side and fixed-order behavior:
  <https://github.com/eclipse-elk/elk/issues/1006>
- Edge overlap around shared orthogonal ports:
  <https://github.com/eclipse-elk/elk/issues/441>
- Fixed-node routing and cross-hierarchy routing constraints:
  <https://github.com/eclipse-elk/elk/issues/1174>
- Layered optimal-port request directed toward Libavoid:
  <https://github.com/eclipse-elk/elk/issues/916>

Local Dediren context:

- `AGENTS.md` states layout intent must be expressed through ELK graph
  structure, ports, hierarchy, and options.
- `README.md` documents the current two-phase helper: ELK Layered placement,
  then ELK Libavoid rerouting over fixed geometry.
- `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/ElkLayoutEngine.java`
  is the implementation surface this spec targets.

## Research Findings

1. ELK Layered is the placement authority for this domain.

   ELK Layered arranges nodes into layers, supports ports, compound graphs,
   cross-hierarchy edges, and orthogonal routing. With orthogonal routing,
   arbitrary port constraints are part of the intended model. This makes it the
   correct first-stage backend for architecture views with groups and directed
   dependencies.

2. Libavoid is a routing backend, not a placer.

   The ELK Libavoid algorithm routes edges without moving node positions. The
   ELK blog and the TypeFox integration notes both call out the same practical
   contract: nodes must already have positions and sizes; when ports are used,
   ports must be positioned and assigned a `PortSide`.

3. Libavoid does not solve coordinate-level port placement for us.

   In upstream issue #1006, a maintainer explains that the Libavoid integration
   can at most help with side selection, not coordinate-level port-position
   calculation. Direction should be explicit to avoid unstable side choices, and
   `FIXED_ORDER` or `FIXED_POS` may be needed when small endpoint bends are
   caused by unevenly distributed ports.

4. Same-port orthogonal overlap is expected near endpoints.

   In upstream issue #441, maintainers state that edges incident to the same
   port overlap near that port, and that avoiding that overlap requires separate
   ports. This means Dediren should use endpoint sharing only when a shared
   junction is intentionally better than separate lanes.

5. Cross-hierarchy routing is sensitive to graph shape and options.

   Upstream issue #1174 highlights that cross-hierarchy edges require
   `hierarchyHandling: INCLUDE_CHILDREN`; that option does not compose freely
   with mixed algorithms at different hierarchy levels. The same discussion
   recommends moving ports to outer nodes for some cases and using Java ELK
   Libavoid when fixed-node edge routing is needed.

6. Libavoid penalties are useful but should stay conservative.

   `segmentPenalty` discourages step-like orthogonal routes and, in the
   Adaptagrams docs, should be set above zero for orthogonal nudging to work
   well. `shapeBufferDistance` controls how closely connectors pass obstacles.
   `idealNudgingDistance` controls spacing used when nudging overlapping
   connector segments apart. Some penalties, including crossing and fixed shared
   path penalties, are documented as experimental or potentially slow, so they
   should not be enabled globally without measured regressions.

## Current Dediren Baseline

The helper already uses the right high-level split:

1. Build an ELK Layered graph from `layout-request.schema.v1`.
2. Run `RecursiveGraphLayoutEngine` to place nodes and groups.
3. Build a fixed-geometry Libavoid graph from the laid-out nodes.
4. Run Libavoid to reroute edges.
5. Return generated geometry and routes in `layout-result.schema.v1`.

Current Layered defaults in `ElkLayoutEngine`:

- `org.eclipse.elk.layered`
- explicit `Direction.RIGHT` for the root, with local group directions derived
  from group internals;
- orthogonal edge routing;
- generous node, edge, and port spacing;
- grouped root uses `HierarchyHandling.INCLUDE_CHILDREN`, aspect ratio `2.2`,
  `WrappingStrategy.MULTI_EDGE`, and `FEEDBACK_EDGES=true`;
- `MERGE_EDGES=true`;
- `MERGE_HIERARCHY_EDGES=true`.

Current Libavoid defaults in `ElkLayoutEngine`:

- `org.eclipse.elk.alg.libavoid`
- orthogonal edge routing;
- `SEGMENT_PENALTY=50`;
- `IDEAL_NUDGING_DISTANCE=16`;
- `SHAPE_BUFFER_DISTANCE=16`;
- `NUDGE_ORTHOGONAL_SEGMENTS_CONNECTED_TO_SHAPES=true`;
- `PENALISE_ORTHOGONAL_SHARED_PATHS_AT_CONN_ENDS=false`.

This baseline is directionally correct. The main risk is that current broad
merge options and endpoint-sharing heuristics can create long shared lanes that
are technically valid but unreadable in dense architecture views.

## Current JSON Configuration Surface

Today, most layout/routing behavior is hardcoded in the Java helper. The JSON
contracts already carry a few effective layout signals:

- `layout-request.schema.v1` node `width_hint` and `height_hint` influence
  generated node size.
- `layout-request.schema.v1` groups express hierarchy and membership.
- group provenance says whether a group is visual-only or semantic-backed.
- edge `relationship_type` influences endpoint-sharing decisions.
- source `plugins.generic-graph.views[].groups` can create layout groups in the
  projected request.

The current contracts do not yet provide a general layout option surface:

- `layout-request.schema.v1.constraints[]` has only `id`, `kind`, and
  `subjects`. It has no value object and the ELK helper currently validates it
  but does not interpret it.
- `model.schema.v1` has no per-view layout preference object.
- `layout-result.schema.v1` is derived output and should not become an input
  configuration channel.
- SVG render policy JSON owns visual styling only; it should not configure
  ELK or Libavoid.

Any new JSON-configurable layout setting therefore requires a public contract
change: update `schemas/`, `dediren-contracts`, projection code, Java helper
mapping, fixtures, README examples, and schema/round-trip tests together. A
compatible additive field may stay in the same schema family, but it still
requires a product version bump under the repo versioning rules.

## Configurability Decision Matrix

Use this matrix to decide what belongs in JSON and what stays helper-owned.

| Current hardcoded behavior | JSON-configurable? | Preferred JSON surface | Rationale |
| --- | --- | --- | --- |
| `org.eclipse.elk.layered` as placement backend | No | Plugin selection only | Selecting `elk-layout` already chooses the backend. Raw algorithm ids would leak backend internals into stable user data. |
| Two-phase Layered placement then Libavoid routing | No | None | This is the plugin implementation contract, not per-view intent. |
| `org.eclipse.elk.alg.libavoid` as router | No | Plugin selection only | Same as placement backend. Dediren may add other layout plugins later; individual requests should not hardcode Java ELK ids. |
| Orthogonal edge routing | Maybe | `layout_preferences.routing.style`, default `orthogonal` | This is genuine visual/layout intent, but the current renderer and quality metrics assume orthogonal routes. Keep orthogonal as the only supported value until polyline has tests. |
| Root direction `RIGHT` | Yes | `layout_preferences.direction` or source view layout preferences projected into layout request | Direction is layout intent, not geometry. It is one of the safest options to expose. |
| Derived internal group direction | Maybe | Group-level `layout_preferences.direction`, later | Useful for mixed vertical/horizontal groups, but it can conflict with root flow. Start with view-level direction only. |
| `HierarchyHandling.INCLUDE_CHILDREN` for grouped views | No, derive it | Groups and cross-group edges | This is required implementation behavior once hierarchy exists. Users should express groups, not ELK hierarchy mode. |
| Group padding | Yes as profile, not raw initially | `layout_preferences.density` or `spacing_profile` | Padding affects readability but raw pixels invite fixture-specific tuning. Prefer symbolic profiles first. |
| Node, edge, and port spacing constants | Yes as profile, cautiously as numeric later | `layout_preferences.spacing_profile` | These are layout-quality tradeoffs. Start with `compact`, `readable`, `spacious`; raw numeric overrides belong only in advanced/debug JSON if ever needed. |
| Aspect ratio `2.2` | Yes | `layout_preferences.aspect_ratio` or `shape` | Aspect ratio is view-level layout intent and useful for wide vs tall output. Clamp accepted values. |
| `WrappingStrategy.MULTI_EDGE` | Yes | `layout_preferences.wrapping: auto/off/multi-edge` | Wrapping is a readability tradeoff. It should be configurable without exposing the exact ELK enum as the user-facing vocabulary. |
| `FEEDBACK_EDGES=true` | Probably no | Derived from graph cycles | This is a cycle-handling tactic. Expose only if concrete views need alternate cycle behavior. |
| `MERGE_EDGES=true` | Yes, but not as raw ELK option | `layout_preferences.routing.endpoint_merging: off/local/auto` | Edge merging has visible readability impact. The stable concept is endpoint sharing policy, not ELK's global merge flag. |
| `MERGE_HIERARCHY_EDGES=true` | Maybe | Same endpoint-merging policy plus group routing profile | This interacts with cross-hierarchy edges. Prefer deriving it from endpoint policy and grouped route tests. |
| `MERGEABLE_ENDPOINT_EDGE_COUNT=3` | Yes | Endpoint-merging policy, possibly `min_edges` later | The threshold is a user-visible readability policy. Start symbolic; add numeric only after metrics prove the need. |
| Relationship-type keyed endpoint sharing | Already data-driven | Existing edge `relationship_type` | This is a good current example: semantic relationship data influences generic routing without making layout depend on ArchiMate types. |
| Libavoid `segmentPenalty` | Not directly | `routing_profile` | Direct penalty values are backend-specific. Profiles can map to tuned Libavoid values. |
| Libavoid `shapeBufferDistance` | Yes as obstacle clearance, not raw option name | `routing_profile` or `obstacle_clearance` | The user-facing concept is clearance from nodes/groups. Numeric support is plausible because layout units are already numeric, but start with profiles. |
| Libavoid `idealNudgingDistance` | Yes as lane separation, not raw option name | `routing_profile` or `lane_separation` | The stable concept is lane separation between routed connectors. |
| `NUDGE_ORTHOGONAL_SEGMENTS_CONNECTED_TO_SHAPES=true` | No initially | Derived from orthogonal routing | This is an implementation detail of the chosen orthogonal routing profile. |
| `PENALISE_ORTHOGONAL_SHARED_PATHS_AT_CONN_ENDS=false` | Maybe through endpoint policy | Endpoint-merging policy | This option conflicts with intentional shared endpoints. Keep it helper-owned until endpoint-sharing fixtures prove a reason. |
| Route detour and close-parallel thresholds | Yes, but likely validation policy, not layout request | Future `layout-quality-policy` or validate-layout options | These judge output quality. They should not silently change how the router behaves. |
| Post-processing cleanup constants | No | None | Expanding post-processing config would move Dediren toward a second router. Keep cleanup narrow and reduce it over time. |

## Recommended JSON Shape

Use stable Dediren vocabulary, then translate to ELK/Libavoid options inside
the helper. Do not expose raw option names such as
`org.eclipse.elk.layered.mergeEdges` in source or layout request JSON.

The preferred contract direction is a top-level `layout_preferences` object in
`layout-request.schema.v1`:

```json
{
  "layout_preferences": {
    "direction": "right",
    "density": "readable",
    "wrapping": "auto",
    "routing": {
      "style": "orthogonal",
      "profile": "readable",
      "endpoint_merging": "local"
    }
  }
}
```

For hand-authored `.dediren` source documents, the same intent may appear under
`plugins.generic-graph.views[]` and be projected into `layout_preferences`.
That keeps source JSON plugin-typed and intent-oriented while making the layout
request the authoritative input to the layout plugin.

Avoid putting layout preferences in:

- node or relationship `properties`, because those are semantic source data;
- render policy JSON, because styling is renderer-owned;
- plugin manifests, because they describe plugin capabilities, not per-view
  layout intent;
- layout result JSON, because it is derived output.

If numeric overrides become necessary, prefer Dediren-owned names and bounded
values:

```json
{
  "layout_preferences": {
    "spacing": {
      "node_node": 72,
      "edge_node": 48,
      "edge_edge": 48,
      "port_port": 40,
      "group_padding": 32
    },
    "routing": {
      "obstacle_clearance": 24,
      "lane_separation": 24,
      "max_shared_trunk": 240
    }
  }
}
```

Those numeric fields should be a later step. The first public surface should be
symbolic profiles because they localize backend tuning inside the helper.

## Strategy

### 1. Keep Two Explicit Phases

Keep the helper architecture as:

```text
layout request
  -> ELK graph for Layered placement
  -> generated fixed node/group geometry
  -> ELK graph for Libavoid routing
  -> generated connector routes
```

Do not expand `normalizeExcessiveRoutes` or
`straightenConnectorEndpointDoglegs` into a competing router. Treat existing
post-processing as compatibility cleanup only. New readability improvements
should come from graph structure, ports, hierarchy, options, and measurable
quality feedback.

### 2. Prefer JSON Intent And Graph Structure Over Raw Option Patching

Use JSON layout intent and graph structure before tuning ELK/Libavoid options.
The JSON contract should name Dediren concepts; the helper translates those
concepts to backend-specific options.

The graph shape should make these facts explicit:

- group membership and hierarchy;
- cross-group edges;
- edge direction by endpoint geometry;
- ports on the intended node side;
- stable port order for dense fan-in and fan-out;
- separate ports for unrelated edges;
- intentionally shared ports only for local, same-kind fan-in or fan-out.

Avoid adding new source-model layout semantics. Any additional helper-side
classification must be derived from existing layout request fields, generated
ownership, relationship type, endpoint locality, and route-quality metrics.

### 3. Make Global Merging Conservative

`mergeEdges` and `mergeHierarchyEdges` are space-saving options, but they can
turn unrelated edges into long bus-like routes. ELK's own `mergeEdges`
description says it causes incoming or outgoing edges without ports to share
ports. That is useful only when sharing is intentional.

Recommended policy:

- Disable global `LayeredOptions.MERGE_EDGES` for grouped, architecture-style
  views unless a regression fixture proves the global merge improves
  readability.
- Treat `LayeredOptions.MERGE_HIERARCHY_EDGES` as a profile choice, not a fixed
  global truth. Leave it enabled only where cross-hierarchy edge ports become
  unreadable without it.
- Keep Dediren's explicit endpoint-merge logic as the preferred mechanism for
  intentional shared trunks, because it can be relationship-aware and
  owner-aware.
- Never rely on global merge options to compensate for missing ports.
- If endpoint merging becomes configurable, expose `off`, `local`, and `auto`
  rather than raw `MERGE_EDGES` and `MERGE_HIERARCHY_EDGES`.

The first implementation experiment should compare:

1. current global merge behavior;
2. `MERGE_EDGES=false`, `MERGE_HIERARCHY_EDGES=true`;
3. both false, with only explicit Dediren endpoint merges.

Use real LFM grouped renders and synthetic fan-in/fan-out fixtures for the
comparison.

### 4. Make Endpoint Sharing Locality-Aware

Endpoint sharing should be allowed only when it produces a short, intentional
trunk. The current relationship-type threshold is a good starting point, but it
is not enough for cross-group architecture views.

Endpoint sharing should require all of the following:

- at least three edges with the same non-empty `relationship_type`;
- same endpoint node;
- same endpoint side;
- same owner group, or adjacent owner groups with a short estimated trunk;
- no predicted trunk that crosses unrelated group interiors;
- no prior route-quality warning for that endpoint class in the fixture set.

Endpoint sharing should be rejected when:

- the shared segment would become a cross-view bus;
- edges with different semantic relationship types would share the same port;
- the shared endpoint is a connector/junction node whose visual value comes from
  keeping the fanout legible;
- the direct Manhattan span between member edges exceeds a configured threshold.

Initial threshold candidates:

- maximum shared trunk length: `240px`;
- maximum route/direct Manhattan ratio after routing: `1.5`;
- maximum route detour excess after routing: `240px`;
- minimum useful edge count for shared endpoint: `3`.

These values align with existing Dediren route-quality constants and should be
validated against real LFM renders before becoming product diagnostics.

### 5. Port Policy

Use ports as Dediren's main control surface.

Default policy:

- Use explicit `PortSide` for every routed port before Libavoid.
- Use `FIXED_ORDER` when Dediren chooses side and relative order but should let
  ELK place exact coordinates.
- Use `FIXED_POS` only for generated helper-owned ports whose coordinates are
  derived from final generated geometry, never from source-authored geometry.
- Keep separate ports for unrelated edges.
- Use shared ports only for intentional endpoint merging.
- Set direction explicitly; do not let `Direction.UNDEFINED` decide the side
  layout in production helpers.
- Let JSON configure intended direction and endpoint-merging policy; keep exact
  generated port coordinates helper-owned.

Grouped routing policy:

- First let Layered place groups and child nodes.
- Derive Libavoid port sides from actual generated geometry.
- Preserve grouped edge port order after geometry-aware side selection.
- Prefer outside-facing ports for cross-group edges when a child-to-child port
  would force a route through group interiors.
- For cross-hierarchy edges, keep `HierarchyHandling.INCLUDE_CHILDREN` in the
  Layered phase and ensure edge coordinates are interpreted relative to the
  parent coordinate system used by the renderer.

### 6. Libavoid Readability Profile

The current Libavoid values are reasonable but slightly compact for dense
architecture views. Define named profiles inside the helper before changing
public contracts.

Recommended internal profiles:

| Profile | `segmentPenalty` | `shapeBufferDistance` | `idealNudgingDistance` | Use |
| --- | ---: | ---: | ---: | --- |
| `compact` | 50 | 16 | 16 | Current behavior and small diagrams |
| `readable` | 60 | 24 | 24 | Default candidate for grouped architecture views |
| `spacious` | 80 | 32 | 32 | Stress fixtures with many cross-group edges |

If profiles become JSON-configurable, the JSON value should be `routing.profile`
with the Dediren names above. The helper should remain free to retune the
underlying Libavoid numbers in a patch release if the public profile semantics
stay stable.

Keep these conservative defaults:

- `NUDGE_ORTHOGONAL_SEGMENTS_CONNECTED_TO_SHAPES=true`;
- `NUDGE_SHARED_PATHS_WITH_COMMON_ENDPOINT=true` unless an explicitly shared
  endpoint should visually overlap by design;
- `PENALISE_ORTHOGONAL_SHARED_PATHS_AT_CONN_ENDS=false` until a fixture proves
  the experimental behavior improves Dediren's graphs without route churn;
- leave crossing and cluster-crossing penalties off unless targeted tests show
  they improve readability within acceptable runtime.

### 7. Layered Spacing Profile

Spacing must match the Libavoid routing envelope. If Libavoid is asked to keep
`24px` from shapes and nudge lanes `24px` apart, Layered should not place nodes
or layers so tightly that no corridor exists.

Recommended grouped architecture profile:

- `SPACING_NODE_NODE`: at least `72px`;
- `SPACING_EDGE_NODE`: at least the Libavoid `shapeBufferDistance + 24px`;
- `SPACING_EDGE_EDGE`: at least the Libavoid `idealNudgingDistance + 24px`;
- `SPACING_PORT_PORT`: at least `32px`, raised to `40px` for dense fanout;
- group padding: at least `32px` for grouped views with cross-group routes.

Treat these as candidate values. The acceptance test is improved real render
quality, not numeric elegance.

If spacing becomes JSON-configurable, expose a symbolic `density` or
`spacing_profile` first. Raw spacing numbers should be added only when a real
use case needs more precision than profiles can provide.

### 8. Wrapping And Aspect Ratio

`WrappingStrategy.MULTI_EDGE` can reduce width, but it also introduces cuts and
wrapped long edges. Use it as a grouped-view profile option, not as an
unquestioned default.

Recommended policy:

- keep `MULTI_EDGE` only for views whose unwrapped width is excessive;
- compare against `OFF` in real LFM renders before keeping it as the default;
- derive aspect ratio from view shape where possible, rather than pinning all
  grouped views to `2.2`;
- use route-quality metrics to decide whether wrapping made long shared lanes
  worse.

### 9. Diagnostics And Quality Metrics

The helper and core should judge layout quality with metrics, not coordinate
snapshots.

Existing and proposed metrics:

- node overlap count;
- edge through unrelated node count;
- invalid route count;
- route length/direct Manhattan ratio;
- route detour excess;
- route segment count;
- close parallel route channel count;
- group boundary crossings;
- shared lane length;
- unrelated-edge shared lane length;
- endpoint fan-in/fan-out lane count;
- maximum route span inside a group interior.

Suggested warning candidates:

- `DEDIREN_LAYOUT_LONG_SHARED_LANE`: a shared segment exceeds `240px` and
  includes edges that do not all share relationship type and endpoint.
- `DEDIREN_LAYOUT_ROUTE_DETOUR`: route/direct ratio exceeds `1.5` and excess
  exceeds `240px`.
- `DEDIREN_LAYOUT_UNRELATED_CLOSE_PARALLEL`: unrelated routes run within `20px`
  for at least `40px`.
- `DEDIREN_LAYOUT_GROUP_INTERIOR_ROUTE`: a cross-group edge crosses the interior
  of an unrelated group.
- `DEDIREN_LAYOUT_ENDPOINT_OVERMERGED`: endpoint sharing was applied but route
  quality worsened against the separate-port baseline.

Do not add these diagnostics to public envelopes until the schema and versioning
impact is intentionally planned. They can first live in test helpers and local
debug reports.

## Implementation Experiments

Run experiments in this order:

1. Define the intended JSON contract before changing behavior.

   Decide whether the first implementation uses `layout_preferences` directly
   in layout requests, source view preferences projected into layout requests,
   or both. Do not reuse the current `constraints[]` array for settings unless
   it first gains a typed value object.

2. Build a real-render comparison harness.

   Use representative LFM layout requests and current Dediren grouped fixtures.
   Generate layout JSON, SVG, and PNG evidence for each option profile. Keep
   generated outputs ignored unless explicitly promoted to fixtures.

3. Add route-quality assertions before tuning.

   Cover long shared lanes, unrelated close parallel channels, connector through
   node, group interior crossing, and route detour. Assert facts rather than
   exact coordinates.

4. Compare Layered merge profiles.

   Test current global merge settings, `MERGE_EDGES=false`, and both merge
   options disabled. Keep the smallest merge surface that improves readability
   without breaking fan-in/fan-out fixtures.

5. Make endpoint merging locality-aware.

   Keep the relationship-type threshold, then add owner-group and estimated
   trunk-span checks. Reject sharing when it creates cross-view buses.

6. Compare Libavoid profiles.

   Test `compact`, `readable`, and `spacious`. Prefer `readable` for grouped
   architecture views only if it improves route-quality metrics and real PNG
   inspection without unacceptable render size growth.

7. Revisit grouped wrapping and aspect ratio.

   Compare `WrappingStrategy.OFF` and `MULTI_EDGE` on dense LFM views. Keep
   wrapping only where it reduces total unreadability, not merely width.

8. Freeze the selected profile behind tests.

   The accepted behavior should be pinned by real-helper ignored tests and
   deterministic fixture tests where possible.

## Acceptance Criteria

A layout/routing change is acceptable only when all are true:

- source graph JSON remains semantic and contains no authored absolute geometry;
- layout request remains intent-oriented;
- JSON-configurable settings use Dediren-owned vocabulary, not raw ELK option
  names;
- ELK Layered computes node, group, port, and hierarchy placement;
- Libavoid computes connector routes over fixed geometry;
- no new runtime dependency is added to `lfm`;
- no first-party plugin depends on `dediren-core`;
- route-quality metrics improve or stay neutral on representative LFM renders;
- real PNG inspection confirms the improvement is human-readable;
- existing layout, render, and export behavior remains contract-compatible;
- tests cover the specific route class being improved.

## Non-Goals

- Replacing ELK Layered.
- Replacing ELK Libavoid.
- Adding source-authored node coordinates.
- Adding ArchiMate-specific layout semantics.
- Rendering tricks that make invalid geometry look acceptable.
- Tuning for one LFM view by making generic grouped graphs worse.

## Open Decisions

1. Should grouped architecture views default to the `readable` Libavoid profile,
   or should the helper select it only above a graph-density threshold?
2. Should `MERGE_HIERARCHY_EDGES` remain enabled while `MERGE_EDGES` is
   disabled, or should both be controlled by explicit endpoint-merge decisions?
3. What ignored real-helper fixture should become the canonical regression for
   LFM-style long shared lanes?
4. Should route-quality diagnostics become public `layout-result.schema.v1`
   warnings in a later versioned change, or remain core validation output?

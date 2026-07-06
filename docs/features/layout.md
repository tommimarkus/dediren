# Layout (ELK)

Layout turns a backend-neutral layout request into generated geometry: node
positions and sizes, and edge routes. Dediren does this with the official
Eclipse ELK Java libraries — it does not duplicate layout or routing features
that ELK already provides.

[← Back to feature index](README.md)

Plugin: `elk-layout` (`dediren-plugin-elk-layout`) ·
Request schema: [`schemas/layout-request.schema.json`](../../schemas/layout-request.schema.json) ·
Result schema: [`schemas/layout-result.schema.json`](../../schemas/layout-result.schema.json)

## ELK-First Principle

Layout intent is expressed through ELK graph structure, ports, hierarchy, and
options, then ELK computes geometry and routes. Custom placement or route
geometry is a last resort, justified only after official ELK Layered options,
graph structure, ports, hierarchy, and real-render evidence have been tried.

- Uses Eclipse ELK Java libraries directly (no external layout adapters).
- Requires Java 21 or newer.

## Layout Modes

`layout_preferences.mode` in the layout request selects the engine:

| Mode | Engine | Edge routes? | Use for |
| --- | --- | --- | --- |
| `flow` | ELK Layered | Yes | Directed, relationship-heavy diagrams that need placement **and** routing. |
| `packed` | ELK Rectangle Packing | No | Edge-less node/group maps and inventories (e.g. grouped ArchiMate maps). |
| `auto` (or omitted) | ELK Layered (default) | Yes | Default flow behavior. |

Use `packed` **only** for edge-less views; it returns no edge routes.
Relationship-heavy diagrams should stay on `auto`/`flow` so ELK Layered owns
both placement and routing.

## `validate-layout` Quality Metrics

`validate-layout` reports backend-neutral metrics over a layout result.
`status` is `ok` **only when every non-informational count and `warning_count`
is zero**.

| Field | Meaning |
| --- | --- |
| `overlap_count` | Overlapping node boxes. |
| `connector_through_node_count` | Edges routed through a node box. |
| `invalid_route_count` | Malformed/invalid routes. |
| `route_detour_count` | Routes taking an avoidable detour. |
| `route_close_parallel_count` | Routes running too close in parallel. |
| `group_boundary_issue_count` | Members escaping their group boundary. |
| `group_label_band_issue_count` | Members overlapping a labeled group's title band. |
| `label_space_issue_count` | Node labels that clearly cannot fit their box (icon-sized nodes are exempt). |
| `edge_label_dissociation_count` | Labeled edges trapped in a dense band of parallel labeled edges, where a centered edge label cannot stay on its own route and drifts toward a neighbour (edges sharing an endpoint node are exempt). |
| `edge_crossing_count` | Edge crossings — **informational only**; crossings can be unavoidable, so this never degrades `status`. |
| `warning_count` | Aggregate warning count. |

### Node placement hints

Per-node layout hints authored on source-model nodes (they also survive on
layout-request nodes). Both are optional and layered-only.

| Node hint | Values | Controls |
| --- | --- | --- |
| `layer_constraint` | `none`, `first`, `first-separate`, `last`, `last-separate` | Pin a node to the first or last layer of the drawing. |
| `partition` | integer | Assign the node to an ordered partition band; lower numbers are placed earlier. |

Partitioning activates automatically when any node carries a `partition`. For
predictable results, give every node a partition when you use the feature —
ELK places unpartitioned nodes without band ordering.

These hints affect the layered algorithm only; they have no effect when `layout_preferences.mode` is `packed` (which uses rectangle packing).

### Edge priority hints

Per-edge layout hints authored on source-model relationships (they also survive
on layout-request edges), grouped under an optional `priority` object. All three
are optional integers and layered-only; higher numbers mean "try harder". They
are relative weights, so what matters is the ordering between edges.

| Edge hint | ELK phase | Honored only when |
| --- | --- | --- |
| `resist_reversal` | cycle-breaking (resist pointing against the flow) | `cycle_breaking` is `greedy` (the default) |
| `keep_short` | layering (fewer layers spanned) | `layering.strategy` is `network-simplex` (the default) |
| `keep_straight` | node placement (axis-aligned) | `placement.strategy` is anything except `simple` |

A priority set against the default strategies is always honored. If you set a
priority whose governing phase strategy cannot honor it (for example `keep_short`
with a non-`network-simplex` layering strategy), the layout request is rejected
with a `$.edges[i].priority.<field>` diagnostic rather than being silently
ignored.

### Algorithm

`layout_preferences.algorithm` selects the layout algorithm.

| Value | Algorithm |
| --- | --- |
| `layered` (default) | ELK Layered — the hierarchical, directed algorithm Dediren is built around. |

`layered` is currently the only supported value. The layering, crossing,
placement, compaction, high-degree-node, and thoroughness options apply only to
the `layered` algorithm; requesting them under a different algorithm is rejected.
Additional algorithms may be added in future releases.

### Graph tuning

Optional graph-level tuning under `layout_preferences`. Omitted options keep
ELK's defaults. Numeric ELK options are exposed as symbolic tiers, never raw
numbers.

| Option | Values | Controls |
| --- | --- | --- |
| `compaction` | `off`, `left`, `right`, `balanced` | Post-layout horizontal compaction of the drawing. |
| `components.separate` | `true`, `false` | Whether disconnected components are laid out separately. |
| `components.spacing` | `compact`, `readable`, `spacious` | Gap between separated components. |
| `high_degree_nodes` | `off`, `on` | Special treatment for nodes with many edges. |
| `thoroughness` | `low`, `normal`, `high` | How hard ELK works to improve the layout (more thorough = slower). |

### Layered phase strategies

`layout_preferences` exposes the ELK Layered pipeline stages as Dediren-owned
options. All are optional; when omitted, Dediren keeps its defaults.

| Option | Values | Controls |
| --- | --- | --- |
| `cycle_breaking` | `greedy` (default), `depth-first`, `model-order` | How edges in cycles are reversed for layering. |
| `layering.strategy` | `network-simplex` (default), `longest-path`, `coffman-graham`, `min-width`, `stretch-width`, `breadth-first`, `depth-first` | How nodes are assigned to layers. |
| `crossing.strategy` | `layer-sweep` (default), `none` | The crossing-minimization pass. |
| `crossing.greedy_switch` | `off`, `one-sided`, `two-sided` | Greedy post-pass that swaps adjacent nodes to cut crossings. |
| `placement.strategy` | `brandes-koepf` (default), `network-simplex`, `linear-segments`, `simple` | How nodes are positioned within their layers. |

These map to ELK Layered options internally; Dediren validates the values and
rejects unknown ones with a structured error envelope.

#### Straightening fan-out edges

When one node fans out to several targets that live in a different group, the
default `brandes-koepf` placement balances the source to its own center rather
than aligning it with any target, so orthogonal edges can stair-step (several
small bends) on their way across the group boundary. Setting
`placement.strategy: network-simplex` on that view aligns the source with the
central target — straightening the middle edge and cutting bends — while keeping
orthogonal routing. It repositions nodes, so apply it per view where the fan-out
matters rather than expecting it everywhere. The stair-stepping that remains
comes from the group-boundary crossing itself, which placement cannot remove.

### Routing styles

`layout_preferences.routing.style` selects how ELK draws edges:

| Value | Route shape |
| --- | --- |
| `orthogonal` (default) | Axis-aligned segments with right-angle bends. |
| `polyline` | Straight point-to-point segments; diagonal bends allowed. |
| `spline` | Smooth curved routes. |

The style is Dediren-owned vocabulary; the `elk-layout` plugin maps it to the
matching ELK edge-routing mode. `orthogonal` remains the default when the field
is omitted.

### Junction routing

In ArchiMate views, junction-role nodes (`AndJunction`/`OrJunction`) must sit on
the routes of their incident edges. A detached junction is the error diagnostic
`DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE`.

## Related Pages

- [Pipeline & Commands](pipeline-and-commands.md) — `layout` and `validate-layout`.
- [SVG Rendering](svg-render.md) — consumes the layout result.
- [Exports (OEF & XMI)](exports.md) — also consumes the layout result.

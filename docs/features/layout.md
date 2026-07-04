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

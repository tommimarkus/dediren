# Interactive SVG: Select Node → Highlight Edges — Design

Date: 2026-06-10
Status: implemented 2026-06; retired 2026-07 (render-policy.schema.v3 removed interactive output — see plans/2026-07-09-retire-interactive-svg.md).

## Problem

The SVG render plugin emits static diagrams. For graphs of any size, a reader
cannot quickly see which edges connect to a given node. The output already
carries the structure needed to make this interactive — every node, edge, and
group `<g>` is tagged with stable `data-dediren-*` attributes, and each edge in
the layout result already names its `source` and `target` node ids — but there
is no interaction layer that uses it.

The goal: let a reader **select a node and have its connected edges highlight**,
delivered as an interactive SVG (and/or an HTML wrapper that guarantees the
script runs everywhere). Highlighting is edges-only: the selected node's
incident edges change appearance; nodes and unrelated edges are left unchanged.

## Non-goals (YAGNI)

- Neighbor-node highlighting or dimming non-selected elements.
- Hover-driven highlighting (selection is click-only).
- Multi-select, zoom, or pan.
- Interactivity for UML sequence diagrams (no node→edge model). The packaging
  modes still apply to sequence output, but no highlight script is emitted for
  it.
- Any external JS/CSS dependency. The interaction layer is hand-written vanilla
  JS embedded in the artifact.

## Approach

Gate everything behind a new render-policy mode and emit a small, dependency-free
interaction layer inside the SVG. The HTML mode wraps that same interactive SVG
inline, so there is one interaction generator and an optional wrapper — never two
copies of the behavior.

### Policy surface

`svg-render-policy.schema.json` (and the `contracts` `RenderPolicy`) gain:

- `interactive`: optional string, enum `"none" | "svg" | "html" | "both"`,
  **default `"svg"`**. Omitting it now produces an interactive SVG; `"none"`
  restores today's static output.
- `style.interaction`: optional object, both fields optional with built-in
  defaults:
  - `highlight_stroke`: color `#rrggbb`, default `#1f6feb`.
  - `highlight_stroke_width`: number, default `3`.

Default `svg` is a deliberate behavior change: existing renders become
interactive unless the caller opts out with `"none"`. The static-render fixtures
and any golden comparisons update accordingly.

### Render-result contract (breaking, intentional)

`render-result` moves from a single artifact to a list. The schema id bumps
`render-result.schema.v1` → `render-result.schema.v2`:

```json
{
  "render_result_schema_version": "render-result.schema.v2",
  "artifacts": [
    { "artifact_kind": "svg",  "content": "<svg ...>...</svg>" },
    { "artifact_kind": "html", "content": "<!DOCTYPE html>..." }
  ]
}
```

- `artifact_kind` enum: `["svg", "html"]`.
- Mode → artifacts produced:
  - `none` → `[{ svg (static) }]`
  - `svg` → `[{ svg (interactive) }]`
  - `html` → `[{ html }]` (the HTML inlines a complete interactive SVG)
  - `both` → `[{ svg (interactive) }, { html }]`
- The list is ordered; `both` always emits svg first, then html.

The contract change is the durable compatibility signal here (schema id), not the
CalVer version. Both renderers (graph and UML sequence) return the new list
shape; only the graph renderer emits the highlight script.

### Markup changes (graph renderer, only when `interactive != "none"`)

- Each edge `<g>` gains `data-dediren-edge-source` and `data-dediren-edge-target`,
  populated from the edge's existing `source` / `target` node ids.
- Nodes already carry `data-dediren-node-id`; unchanged.
- One `<style>` block defines the highlighted-edge appearance from the resolved
  interaction style (default or policy override).
- One `<script>` (wrapped in `<![CDATA[ ... ]]>`) implements the behavior.

Because these are only emitted when `interactive != "none"`, the static path
(`"none"`) stays byte-for-byte identical to today apart from the result envelope
shape.

### Interaction behavior (vanilla JS)

- A single click listener on the root `<svg>` (event delegation). A click walks
  up to the nearest ancestor carrying `data-dediren-node-id`.
- Selecting a node adds the highlight class to every edge `<g>` whose
  `data-dediren-edge-source` **or** `data-dediren-edge-target` equals that node
  id, and removes it from all other edges.
- Selection is single. Re-clicking the selected node, clicking empty canvas, or
  pressing **Escape** clears the highlight.
- No state persists outside the DOM; reload resets to nothing selected.

### Packaging

- The graph renderer builds the interactive SVG once.
- `svg` returns it directly; `html` wraps it inline in a minimal
  `<!DOCTYPE html><meta charset="utf-8"><body> … </body></html>` shell (inline
  SVG scripts execute, so no behavior is duplicated); `both` returns both
  artifacts.
- For UML sequence output the same packaging applies (svg / html wrapper) but the
  SVG carries no highlight script, since there is nothing to highlight.

## Components and data flow

```
RenderPolicy.interactive ─┐
RenderPolicy.style.interaction ─┐
                                ▼
LayoutResult (edges: source/target) ──► graph renderer
                                            │
                       static svg ◄─────────┤ (interactive == none)
                       interactive svg ◄─────┤ (else: + edge src/tgt attrs,
                                            │          <style>, <script>)
                                            ▼
                              artifacts[] (svg and/or html)
                                            ▼
                              CommandEnvelope.ok(render-result.v2)
```

## Affected surfaces (move together)

- `schemas/svg-render-policy.schema.json` — `interactive`, `style.interaction`.
- `schemas/render-result.schema.json` — v2 artifacts array, `artifact_kind` enum.
- `contracts` — `RenderPolicy`, `RenderResult` (list of artifacts), the
  render-result schema-version constant, and round-trip tests.
- `plugins/svg-render` — graph renderer markup + interaction emission, result
  assembly, UML sequence result wrapping.
- CLI render tests — assert envelope/artifact shape for each mode.
- `README.md` and `docs/agent-usage.md` — the artifact-extraction examples move
  from a single `content` to selecting an entry in `artifacts[]` (e.g. by
  `artifact_kind`), plus a short note on the modes and default.

## Error handling

- Unknown `interactive` value: rejected by the policy schema and by
  `RenderInputValidator`, returning a structured diagnostic and non-zero exit
  (consistent with existing policy validation).
- Invalid `highlight_stroke` (not `#rrggbb`) or out-of-range
  `highlight_stroke_width`: schema validation error with a path-scoped
  diagnostic.
- Missing optional fields: built-in defaults applied; no error.

## Testing (TDD)

- **svg-render plugin**
  - `none` → result has one svg artifact, content has no `<script`/`<style`
    interaction block and no `data-dediren-edge-source`; matches the prior static
    bytes.
  - `svg` (and the default, with `interactive` omitted) → one svg artifact whose
    content contains the interaction `<script>`, the highlight `<style>`, and
    `data-dediren-edge-source`/`-target` on edge groups.
  - `html` → one html artifact whose content starts with `<!DOCTYPE html` and
    contains an inline `<svg>` with the interaction script.
  - `both` → two artifacts in order `[svg, html]`.
  - `style.interaction` overrides are reflected in the emitted CSS; defaults
    applied when omitted.
  - UML sequence under each mode → correct packaging, no highlight script.
- **contracts** — round-trip for `render-result.schema.v2` (artifacts list) and
  the new policy fields; the schema-version constant assertion updates to v2.
- **CLI** — render command with an interactive policy fixture asserts the
  envelope's artifact list.
- **Behavior note**: the JS highlight logic is verified structurally (the
  emitted script/attributes/markup), not by executing a browser. The edge
  source/target attributes plus the highlight class wiring are the testable
  contract; this is recorded as the boundary of automated coverage.

## Open decisions resolved

- Modes `none | svg | html | both`, default `svg` (interactive by default).
- Highlight is edges-only, click-driven, single-select, with Escape/empty-canvas
  clear.
- Highlight appearance is policy-configurable (`style.interaction`) with
  defaults.
- `both` is delivered via a multi-artifact `render-result.v2` list.
- UML sequence diagrams are out of scope for highlighting but follow the same
  packaging.

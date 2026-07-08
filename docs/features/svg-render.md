# SVG Rendering

The `render` plugin turns a layout result into a deterministic SVG artifact
(and, optionally, an interactive HTML page). All SVG styling lives in the render
policy and the plugin — never in source JSON. dediren emits no PNG; convert the
SVG with an external tool (`rsvg-convert`, `resvg`, ImageMagick, or Inkscape).

[← Back to feature index](README.md)

Plugin: `render` ·
Policy schema: [`schemas/render-policy.schema.json`](../../schemas/render-policy.schema.json) ·
Result schema: [`schemas/render-result.schema.json`](../../schemas/render-result.schema.json)

## Result Shape: `artifacts[]`

`render` returns an ordered `.data.artifacts[]` list. Each entry has an
`artifact_kind` (`svg` or `html`) and `content`. Select the one you want:

```bash
jq -r '.data.artifacts[] | select(.artifact_kind=="svg") | .content' \
  render-result.json > diagram.svg
```

## Render Policies

The policy owns presentation. Reuse a shipped policy unless custom style is
needed:

| Policy | Use |
| --- | --- |
| [`fixtures/render-policy/default-svg.json`](../../fixtures/render-policy/default-svg.json) | General-purpose default. |
| [`fixtures/render-policy/archimate-svg.json`](../../fixtures/render-policy/archimate-svg.json) | ArchiMate notation styling. |
| [`fixtures/render-policy/uml-svg.json`](../../fixtures/render-policy/uml-svg.json) | UML notation styling. |
| [`fixtures/render-policy/rich-svg.json`](../../fixtures/render-policy/rich-svg.json) | Richer styling example. |
| [`fixtures/render-policy/interactive-svg.json`](../../fixtures/render-policy/interactive-svg.json) | Interactive-mode example. |

### Edge label presentation

Edge labels default to **outlined text**. Set
`style.edge.label_presentation` to `background` when a filled label backing is
preferred.

Edge label placement tries candidate positions that avoid node boxes, group
title/border bands, other edge route segments, and labels already placed earlier
in the SVG, then falls back to the preferred route position when no clear
candidate is available. Line-jump masks use the local group fill when the jump
occurs inside a group, falling back to the page background outside groups.

## Interactive Modes

The policy accepts an optional `interactive` mode. Interactivity is opt-in:
omitting `interactive` (or setting `none`) produces a static SVG, and the
click-to-highlight `<script>` is embedded only when a non-`none` mode is
explicitly requested. This rule applies uniformly to every view.

| `interactive` | Output |
| --- | --- |
| `none` (default) | Static SVG, no embedded script. |
| `svg` | Self-contained interactive SVG that highlights a node's edges on click. |
| `html` | An HTML page wrapping the interactive SVG. |
| `both` | An `svg` artifact **and** an `html` artifact. |

Highlight appearance is controlled by `style.interaction.highlight_stroke` and
`style.interaction.highlight_stroke_width`. UML sequence views carry no highlight
script (there is no node→edge model to highlight) but follow the same packaging.

## Notation Rendering & Render Metadata

For UML notation views (sequence, state machine, use case, component,
deployment), pass generated **render metadata** with `--metadata` so the
renderer receives the notation semantics (e.g. for sequence: lifelines,
interaction, message order, message sort, and combined-fragment structure):

```bash
dediren project --target render-metadata --plugin generic-graph \
  --view sequence-view --input source.json > render-metadata.json

dediren render --plugin render \
  --policy fixtures/render-policy/uml-svg.json \
  --metadata render-metadata.json \
  --input layout-result.json > render-result.json
```

Render metadata schema:
[`schemas/render-metadata.schema.json`](../../schemas/render-metadata.schema.json).

## Related Pages

- [Pipeline & Commands](pipeline-and-commands.md) — the `render` command.
- [Layout (ELK)](layout.md) — produces the layout result that render consumes.
- [Exports (OEF & XMI)](exports.md) — the non-SVG output paths.

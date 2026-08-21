# Text (ASCII) Rendering

The `ascii` render plugin turns a layout result into a text diagram — box-drawn
nodes and wired edges on a character grid — instead of SVG. It exists for
agents that want to embed a "before"/"after" diagram directly in a plan, a PR
description, or a terminal, where SVG cannot render but a monospaced block of
text can.

[← Back to feature index](README.md)

Plugin: `ascii` ·
Policy schema: [`schemas/render-policy.schema.json`](../../schemas/render-policy.schema.json) ·
Result schema: [`schemas/render-result.schema.json`](../../schemas/render-result.schema.json)

## Pipeline

The lane reuses the exact same `project`/`layout` stages as SVG — only the
`render` plugin and policy change:

```text
mermaid/dot import (or a hand-authored source) -> project --target layout-request
  -> layout (ELK) -> render --plugin ascii
```

```bash
dediren import --plugin mermaid --input diagram.mmd > import-result.json
jq -r '.data.source' import-result.json > source.json

dediren project --target layout-request --plugin generic-graph \
  --view main --input source.json > layout-request.json

dediren layout --plugin elk-layout --input layout-request.json > layout-result.json

dediren render --plugin ascii \
  --policy fixtures/render-policy/ascii-text.json \
  --input layout-result.json > render-result.json

jq -r '.data.artifacts[] | select(.artifact_kind=="text") | .content' render-result.json > diagram.txt
```

Over MCP, `dediren_import` with `output: "text"` runs the same import ->
project -> layout -> ascii-render sequence in one call and returns the
diagram as a second `TextContent`, after the unchanged command envelope; see
[`docs/agent-usage.md`](../agent-usage.md#mcp-server).

## Result Shape: `artifacts[]`

`render --plugin ascii` returns a single `artifacts[]` entry with
`artifact_kind` `text` and `content` holding the rendered diagram:

```bash
jq -r '.data.artifacts[] | select(.artifact_kind=="text") | .content' \
  render-result.json > diagram.txt
```

## Policy: `text.charset`

The render policy's optional `text` object selects the character set:

```json
{
  "render_policy_schema_version": "render-policy.schema.v4",
  "text": { "charset": "ascii" }
}
```

- `"unicode"` (default): box-drawing lines and arrowheads —
  `─│┌┐├┼▶◀▲▼`, truncation marker `…`.
- `"ascii"`: plain-ASCII equivalents — `-|+`, arrowheads `>v^<`, truncation
  marker `~`. Shipped as
  [`fixtures/render-policy/ascii-text.json`](../../fixtures/render-policy/ascii-text.json).

Every other policy field — `page`, `margin`, `style`, `accessibility`, and
`semantic_profile` — carries no meaning for a character grid and is ignored.

A two-node example, both charsets:

```text
unicode                          ascii
┌────────┐     ┌────────┐        +--------+     +--------+
│   a    │────▶│   b    │        |   a    |---->|   b    |
└────────┘     └────────┘        +--------+     +--------+
```

## Degrades

The lane draws what it can rather than failing outright when the layout does
not fit a grid cleanly:

| Diagnostic | Severity | Meaning |
| --- | --- | --- |
| `DEDIREN_ASCII_POLICY_INVALID` | error (exit 3) | `text.charset` is not `unicode` or `ascii`. |
| `DEDIREN_ASCII_EDGE_APPROXIMATED` | warning | A non-orthogonal edge route was straightened to an L-shaped run to fit the character grid. |
| `DEDIREN_ASCII_EDGE_LABEL_DROPPED` | warning | An edge label could not be placed without colliding with other content, so it was left off. |
| `DEDIREN_ASCII_LABEL_TRUNCATED` | warning | A node or group label is wider or taller than its box, so it was truncated with `…`/`~`. |
| `DEDIREN_ASCII_SEQUENCE_VIEW_GENERIC` | warning | A UML sequence view was drawn as generic boxes and wires, not lifelines and messages. |

## Known limits

**Not part of `dediren build` or the package model.** `core` selects no
`ascii` lane in either the per-view build or the package driver — the plugin
is reached only through the standalone `render` command and MCP
`dediren_import`'s `output: "text"` lane. That is a deliberate resting state,
the same one the `drawio` export lane sits in today (see
[Exports — Known limits](exports.md#known-limits)).

**No provenance stamp.** The stamp is a build-lane step, and no build driver
selects `ascii`, so a text artifact is never stamped and is not among the
suffixes `dediren verify`/`dediren status` enumerate. There is no drift
detection for this lane beyond re-rendering and comparing.

**No sequence-diagram notation.** A UML sequence view renders as generic boxes
and wires (`DEDIREN_ASCII_SEQUENCE_VIEW_GENERIC`); use the SVG render lane
([SVG Rendering](svg-render.md)) when lifeline/message notation matters.

## Related Pages

- [Pipeline & Commands](pipeline-and-commands.md) — the `render` command.
- [Layout (ELK)](layout.md) — produces the layout result that render consumes.
- [SVG Rendering](svg-render.md) — the notation-aware render lane.

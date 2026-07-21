# Pipeline & Commands

Dediren is a sequence of small, explicit CLI commands. Each command reads JSON,
calls a plugin or a core check, and writes a JSON command envelope to stdout.
There is no hidden state between commands — every step is a file you can inspect.

[← Back to feature index](README.md)

## The Pipeline

```text
validate -> project --target layout-request -> layout -> validate-layout -> render
validate -> project --target layout-request -> layout -> validate-layout -> export
```

UML notation views also need generated **render metadata** so the renderer
receives the notation semantics:

```text
project --target render-metadata --> (passed to render with --metadata)
```

`build` runs this whole flow as one command per view, writing each view's
artifacts under `--out`; use the per-stage commands below when you need to
inspect, cache, or persist an intermediate stage envelope.

Which views, the commands, and the schema: [SVG Rendering → Notation Rendering
& Render Metadata](svg-render.md).

## Command Handoff

Commands that consume a generated artifact accept **either** a full Dediren
command envelope **or** the raw `.data` artifact JSON from the previous step. So
you can pipe envelopes straight through without unwrapping them.

## Commands

### `build`

Runs `project` (layout-request, then render-metadata when `--render-policy` is
set) → `layout` → `validate-layout` → one or more of
`render`/`archimate-oef`/`uml-xmi` for one or more views, in a single process
call, writing each view's artifacts under `--out/<view-id>/`. At least one of
`--render-policy`/`--oef-policy`/`--xmi-policy` is required.

```bash
dediren build --input fixtures/source/valid-basic.json --out out \
  --render-policy fixtures/render-policy/default-svg.json
```

Unlike every other command, `build`'s stdout **is** the build result document
directly (`schemas/build-result.schema.json`) — it is not wrapped in the
generic envelope's `.data`. Full flag table, `--emit` (persisting intermediate
stage envelopes), and status-rollup rules: [Agent Usage →
`## Build`](../agent-usage.md#build).

Fall back to the per-stage commands below to run a single stage, inspect an
intermediate result, or reuse a cached stage output.

### `validate`

Checks the source graph: document shape and contract version, id uniqueness,
endpoint/reference integrity, namespaced property shape, and authored-geometry
rules (source JSON must not carry `x`/`y`/`width`/`height`).

With `--plugin generic-graph --profile archimate` or `--profile uml`, it also
runs the selected plugin's **semantic validation** (relationship legality,
notation-specific rules).

```bash
dediren validate --input fixtures/source/valid-basic.json

dediren validate --plugin generic-graph --profile uml \
  --input fixtures/source/valid-uml-sequence-basic.json
```

### `project`

Asks the `generic-graph` plugin to project a named view into a generated
artifact. Two targets:

- `--target layout-request` → backend-neutral layout request JSON (the input to
  `layout`).
- `--target render-metadata` → render metadata JSON carrying notation semantics
  (passed to `render` with `--metadata`; required for UML notation SVG).

```bash
dediren project --target layout-request --plugin generic-graph \
  --view main --input fixtures/source/valid-basic.json > layout-request.json
```

### `layout`

Asks the official Java ELK plugin (`elk-layout`) to generate node geometry and
edge routes. `layout_preferences.mode` in the layout request selects the engine:

- `flow` — ELK Layered flow diagrams (directed, routed edges).
- `packed` — edge-less node/group maps via ELK Rectangle Packing (no routes).
- `auto` (or omitted) — default flow behavior.

```bash
dediren layout --plugin elk-layout --input layout-request.json > layout-result.json
```

See [Layout (ELK)](layout.md) for mode guidance and metrics.

### `validate-layout`

Reports backend-neutral route and layout quality metrics over a layout result.
`status` is `ok` only when every non-informational count and the warning count
is zero. See [Layout (ELK) → Quality metrics](layout.md#validate-layout-quality-metrics)
for the full field list.

```bash
dediren validate-layout --input layout-result.json
```

### `render`

Asks the `render` plugin to produce SVG. Output is a `.data.artifacts[]` list
holding a single entry with `artifact_kind` `svg` and `content`. Select the
artifact:

```bash
dediren render --plugin render \
  --policy fixtures/render-policy/default-svg.json \
  --input layout-result.json > render-result.json

jq -r '.data.artifacts[] | select(.artifact_kind=="svg") | .content' \
  render-result.json > diagram.svg
```

For UML notation views, add `--metadata <render-metadata.json>`. See
[SVG Rendering](svg-render.md).

### `export`

Asks `archimate-oef` or `uml-xmi` to generate XML in `.data.content`. Export
consumes the **source** model and the **layout result** together:

```bash
dediren export --plugin uml-xmi \
  --policy fixtures/export-policy/default-uml-xmi.json \
  --source fixtures/source/valid-uml-basic.json \
  --layout layout-result.json > xmi-result.json

jq -r '.data.content' xmi-result.json > diagram.xmi
```

See [Exports (OEF & XMI)](exports.md).

### `--version`

```bash
dediren --version
```

### `diff`

Compares two revisions of a source model, keyed on stable ids: added, removed,
and field-level changed nodes/relationships plus per-view membership changes,
every list sorted by id (`schemas/diff-result.schema.json`). Both sides must
be valid current-schema models. A report, never a merge.

```bash
dediren diff --old model-before.json --new model-after.json
```

### `query`

Answers one fixed-vocabulary question about a model
(`schemas/query-result.schema.json`): `--kind dependents --id <node>`
(fan-in/fan-out), `--kind orphans` (nodes without relationships; nodes in no
view), or `--kind view-coverage` (per-view counts + uncovered nodes).
Deterministic and read-only; deliberately not a query language.

```bash
dediren query --kind dependents --id api --input model.json
```

Details for both: [Agent Usage → `## Diff & Query`](../agent-usage.md#diff--query).

## Output & Exit Codes

- Every command but `build` produces a JSON command envelope on stdout
  (`schemas/envelope.schema.json`), with `.status` and `.diagnostics[]`;
  `build`'s stdout is the build result document directly
  (`schemas/build-result.schema.json`).
- A valid engine **error envelope** is preserved and the CLI exits non-zero.
- **stderr is for human debugging only.** Agents must decide success or failure
  from stdout JSON. See [Contracts & Schemas](contracts-and-schemas.md) for the
  envelope and diagnostic shape.

## Related Pages

- [Source Model & Views](source-model.md) — what `validate`/`project` consume.
- [Engine Runtime](engine-runtime.md) — how commands reach engines.
- [Contracts & Schemas](contracts-and-schemas.md) — envelope and diagnostic codes.

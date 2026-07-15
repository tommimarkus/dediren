# Source Model & Views

The source document is the only artifact an agent normally authors by hand
(plus, optionally, a render or export policy). Everything downstream —
layout requests, geometry, SVG, XML — is generated.

[← Back to feature index](README.md)

Schema: [`schemas/model.schema.json`](../../schemas/model.schema.json) ·
Smallest example: [`fixtures/source/valid-basic.json`](../../fixtures/source/valid-basic.json)

## Semantic, Plugin-Typed Graph

A source model is a graph of `nodes` and `relationships` with stable
human-readable ids and type strings, plus a `plugins` section that holds
plugin-owned document data (such as views).

```json
{
  "model_schema_version": "model.schema.v1",
  "required_plugins": [{ "id": "generic-graph", "version": "2026.07.20" }],
  "nodes": [
    { "id": "client", "type": "generic.actor", "label": "Client", "properties": {} },
    { "id": "api", "type": "generic.component", "label": "API", "properties": {} }
  ],
  "relationships": [
    { "id": "client-calls-api", "type": "generic.calls",
      "source": "client", "target": "api", "label": "calls", "properties": {} }
  ],
  "plugins": {
    "generic-graph": {
      "views": [
        { "id": "main", "label": "Main",
          "nodes": ["client", "api"], "relationships": ["client-calls-api"],
          "groups": [] }
      ]
    }
  }
}
```

### What the core validates

The core validates only structure, not meaning:

- document shape and contract version (`model_schema_version`);
- id uniqueness across nodes, relationships, views, and groups;
- endpoint/reference integrity (relationship `source`/`target` resolve);
- namespaced `properties` object shape;
- plugin declaration shape and `required_plugins` metadata.

The core does **not** decide whether a `type` string is meaningful. That is
**semantic validation**, owned by the selected plugin (see Semantic Profiles).

### No authored geometry

Source JSON must not contain `x`, `y`, `width`, `height`, colors, fonts, or SVG
shape choices. Geometry is generated into the layout result; presentation lives
in render policy. Authored geometry is rejected with `DEDIREN_SCHEMA_INVALID`
(see [`fixtures/source/invalid-absolute-geometry.json`](../../fixtures/source/invalid-absolute-geometry.json)).

### Namespaced properties

Plugin-specific data lives in namespaced areas so the core stays
notation-agnostic:

- node/relationship metadata → a namespaced `properties` object (e.g.
  `properties.uml.*`);
- plugin-owned document sections → under `plugins.<plugin-id>`.

## Views & Projection

Views are **not** core structure — they are plugin-defined projections over the
graph, stored under `plugins.generic-graph.views[]`. Each view has an `id`,
`label`, explicit `nodes` and `relationships` selections, and `groups`.

`project` turns one named view into a layout request or render metadata. A
single model can carry many views.

### Semantic-backed groups

A view group may be backed by a semantic element via `semantic_source_id`. This
is how container-like notation constructs are represented in a view without
making the core understand containment — for example UML `StateMachine`/`Region`
boundaries, the use-case subject boundary, component/package boundaries, and
deployment-target boundaries are all semantic-backed groups.

## Semantic Profiles

The `generic-graph` plugin's `semantic_profile` selects which vocabulary and
semantic rules apply:

| Profile | `semantic_profile` | Type vocabulary | View kinds |
| --- | --- | --- | --- |
| Generic | (unset) | `generic.*` | `generic` |
| ArchiMate | `"archimate"` | ArchiMate 3.2 element/relationship names | `archimate` |
| UML | `"uml"` | UML 2.5.1 metaclass names | `uml-class`, `uml-data`, `uml-activity`, `uml-sequence`, `uml-state-machine`, `uml-use-case`, `uml-component`, `uml-deployment` |

```json
{
  "plugins": { "generic-graph": { "semantic_profile": "archimate", "views": [] } }
}
```

Run profile-aware semantic validation with
`validate --plugin generic-graph --profile <archimate|uml>`. `required_plugins`
entries (for example `archimate-oef` or `uml-xmi`) are informational: they name
the bundled engines a source model expects, but the registry does not enforce
them against export, so export runs whether or not the matching entry is
present.

UML notation specifics (vocabulary, message sorts, pseudostate/transition kinds,
deferred constructs) are documented in [Exports (OEF & XMI)](exports.md), since
those rules govern both validation and export.

## Fragments

Larger models can be split: a source model may declare relative file fragments,
which use the same schema and are resolved relative to the entry file. This lets
big models stay modular without changing the contract.

## Source Fixtures

The repo ships ready-made source fixtures to copy from:

- `valid-basic.json` — minimal generic graph.
- `valid-pipeline-rich.json`, `valid-pipeline-archimate.json` — richer pipelines.
- `valid-archimate-oef.json`, `valid-archimate-junction.json` — ArchiMate/OEF.
- `valid-uml-basic.json`, `valid-uml-complex.json` — UML structural.
- `valid-uml-sequence-basic.json`, `valid-uml-sequence-fragments.json`,
  `valid-uml-state-machine-basic.json`, `valid-uml-use-case-basic.json`,
  `valid-uml-component-basic.json`, `valid-uml-deployment-basic.json` — UML notations.
- `invalid-*.json` — fixtures that intentionally trip a specific diagnostic.

## Related Pages

- [Pipeline & Commands](pipeline-and-commands.md) — how the model flows downstream.
- [Exports (OEF & XMI)](exports.md) — full ArchiMate/UML vocabulary and rules.
- [Contracts & Schemas](contracts-and-schemas.md) — schema ids and diagnostics.

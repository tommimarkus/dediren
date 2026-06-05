# Dediren Agent Usage

This guide is for agents that author Dediren JSON and run a packaged Dediren
bundle. Use schemas for exact validation and fixtures for examples, but use
this file to decide which JSON to write, which JSON is generated, and how to
repair failures.

Source builds use the checked-in Maven Wrapper. Packaged bundle usage below is
unchanged.

Preserve the bundle root `LICENSE`, `THIRD-PARTY-NOTICES.md`, and this guide
when redistributing a Dediren archive.

This file is the shipped agent-facing contract for bundle usage. If Dediren is
embedded in another agent skill, plugin, or tool package, preserve this path or
carry the same JSON authoring, command handoff, runtime probe, and repair
guidance in that package.

## Fast Path

1. Author `model.json` with the `Minimal Source JSON` shape below.
2. Add `plugins.generic-graph.views[]` with the nodes and relationships for
   each view.
3. Reuse `fixtures/render-policy/default-svg.json` unless custom SVG style is
   required.
4. Run `validate`, `project --target layout-request`, `layout`,
   `validate-layout`, then `render` or `export`.
5. Inspect stdout JSON `.status` and `.diagnostics[]`; do not parse stderr.

## Artifact Map

| Artifact | Agent authors it? | Schema | Example |
| --- | --- | --- | --- |
| Source model | Yes | `schemas/model.schema.json` | `fixtures/source/valid-basic.json` |
| SVG render policy | Usually reuse | `schemas/svg-render-policy.schema.json` | `fixtures/render-policy/default-svg.json` |
| OEF export policy | Usually reuse | `schemas/oef-export-policy.schema.json` | `fixtures/export-policy/default-oef.json` |
| UML/XMI export policy | Usually reuse | `schemas/uml-xmi-export-policy.schema.json` | `fixtures/export-policy/default-uml-xmi.json` |
| Layout request | Usually generated | `schemas/layout-request.schema.json` | `fixtures/layout-request/basic.json` |
| Render metadata | Usually generated | `schemas/render-metadata.schema.json` | `fixtures/render-metadata/archimate-basic.json` |
| Layout result | No | `schemas/layout-result.schema.json` | `fixtures/layout-result/basic.json` |
| Render/export result | No | `schemas/render-result.schema.json`, `schemas/export-result.schema.json` | command stdout |

## Minimal Source JSON

```json
{
  "model_schema_version": "model.schema.v1",
  "required_plugins": [
    { "id": "generic-graph", "version": "0.24.0" }
  ],
  "nodes": [
    { "id": "client", "type": "generic.actor", "label": "Client", "properties": {} },
    { "id": "api", "type": "generic.component", "label": "API", "properties": {} }
  ],
  "relationships": [
    {
      "id": "client-calls-api",
      "type": "generic.calls",
      "source": "client",
      "target": "api",
      "label": "calls",
      "properties": {}
    }
  ],
  "plugins": {
    "generic-graph": {
      "views": [
        {
          "id": "main",
          "label": "Main",
          "nodes": ["client", "api"],
          "relationships": ["client-calls-api"],
          "groups": []
        }
      ]
    }
  }
}
```

Do not put `x`, `y`, `width`, `height`, colors, fonts, or SVG shape choices in
source JSON. Source JSON is semantic. Layout results contain generated
geometry. Render policy contains presentation.

## Semantic Profiles

For ArchiMate SVG notation or OEF export, set the generic graph semantic
profile and use ArchiMate type names:

```json
{
  "required_plugins": [
    { "id": "generic-graph", "version": "0.24.0" },
    { "id": "archimate-oef", "version": "0.24.0" }
  ],
  "plugins": {
    "generic-graph": {
      "semantic_profile": "archimate",
      "views": []
    }
  }
}
```

For UML SVG notation or XMI export, use `semantic_profile: "uml"` and the
`uml-xmi` plugin. Supported UML view kinds are `uml-class`, `uml-data`,
`uml-activity`, `uml-sequence`, `uml-state-machine`, and `uml-use-case`.

## Command Handoff

Commands that consume generated artifacts accept either the raw artifact JSON
or the previous command envelope:

```bash
"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph \
  --view main --input "$BUNDLE/fixtures/source/valid-basic.json" \
  > layout-request.json

"$BUNDLE/bin/dediren" layout --plugin elk-layout \
  --input layout-request.json \
  > layout-result.json

"$BUNDLE/bin/dediren" render --plugin svg-render \
  --policy "$BUNDLE/fixtures/render-policy/default-svg.json" \
  --input layout-result.json \
  > render-result.json

jq -r '.data.content' render-result.json > diagram.svg
```

## UML Sequence Handoff

Use `fixtures/source/valid-uml-sequence-basic.json` for the sequence MVP
shape: one `Interaction`, `Lifeline` nodes, and ordered `Message`
relationships with `properties.uml.sequence` plus `message_sort`. The SVG
sequence path needs generated render metadata. For combined fragments, use
`fixtures/source/valid-uml-sequence-fragments.json` and
`--view sequence-fragments-view`; author `CombinedFragment` and
`InteractionOperand` nodes under `properties.uml` for `alt`, `opt`, `loop`,
and `par`. Keep message `sequence` values unique within an interaction, keep
each operand's `fragments` list in sequence order, and do not leave standalone
messages inside a combined fragment's owned sequence span.

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view sequence-view \
  --input "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json" \
  > sequence-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view sequence-view \
  --input "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json" \
  > sequence-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input sequence-layout-request.json \
  > sequence-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin svg-render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata sequence-render-metadata.json \
  --input sequence-layout-result.json \
  > sequence-render-result.json

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json" \
  --layout sequence-layout-result.json \
  > sequence-xmi-result.json
```

Read `.status`, `.data`, and `.diagnostics[]` from stdout JSON envelopes for
each command before continuing. The sequence MVP supports `Interaction`,
`Lifeline`, `Message`, `ExecutionSpecification`, `Gate`, and
`DestructionOccurrenceSpecification` plus `CombinedFragment` and
`InteractionOperand`; message sorts are `synchCall`, `asynchCall`,
`asynchSignal`, `reply`, `createMessage`, and `deleteMessage`. `InteractionUse`,
`GeneralOrdering`, `ignore`, `consider`, UMLDI, and deployment diagrams are not
yet supported.

## UML State Machine Handoff

Use `fixtures/source/valid-uml-state-machine-basic.json` for the state-machine
MVP. `StateMachine` and `Region` are semantic-backed groups in
`plugins.generic-graph.views[].groups` with `semantic_source_id`; state
vertices are nodes and transitions are relationships.

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view state-machine-view \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  > state-machine-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view state-machine-view \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  > state-machine-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input state-machine-layout-request.json \
  > state-machine-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin svg-render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata state-machine-render-metadata.json \
  --input state-machine-layout-result.json \
  > state-machine-render-result.json

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  --layout state-machine-layout-result.json \
  > state-machine-xmi-result.json
```

Supported vocabulary: `StateMachine`, `Region`, `State`, `FinalState`,
`Pseudostate`, `Transition`. Pseudostate kinds: `initial`, `deepHistory`,
`shallowHistory`, `join`, `fork`, `junction`, `choice`, `entryPoint`,
`exitPoint`, `terminate`. Transition kinds: `internal`, `local`, `external`.
Deferred/non-goals: `ConnectionPointReference`, `ProtocolStateMachine`,
`ProtocolTransition`, submachine states, orthogonal multi-region internals,
trigger event metaclasses, effects as behavior nodes, UMLDI, and deployment
diagrams.

## UML Use Case Handoff

Use `fixtures/source/valid-uml-use-case-basic.json` for the use-case MVP.
Author `Actor`, `UseCase`, and `ExtensionPoint` nodes; actor `Association`
relationships; and `Include` or `Extend` relationships between use cases. Model
the subject boundary as a semantic-backed view group whose `semantic_source_id`
points at a UML structural classifier node. Put `UseCase.properties.uml.subject`
on use cases and `ExtensionPoint.properties.uml.use_case` on extension points.

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-use-case-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view use-case-view \
  --input "$BUNDLE/fixtures/source/valid-uml-use-case-basic.json" \
  > use-case-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view use-case-view \
  --input "$BUNDLE/fixtures/source/valid-uml-use-case-basic.json" \
  > use-case-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input use-case-layout-request.json \
  > use-case-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin svg-render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata use-case-render-metadata.json \
  --input use-case-layout-result.json \
  > use-case-render-result.json

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-use-case-basic.json" \
  --layout use-case-layout-result.json \
  > use-case-xmi-result.json
```

Rules: `Include` and `Extend` are `UseCase -> UseCase`.
`Extend.properties.uml.extension_point`, when present, must reference an
extension point owned by the extended target use case. Deferred/non-goals:
use-case generalization, collaboration use-case realizations, UMLDI, and
deployment diagrams.

## Runtime Probes

```bash
VERSION=0.24.0
TARGET=x86_64-unknown-linux-gnu
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}-${TARGET}

"$BUNDLE/bin/dediren" --version
"$BUNDLE/bin/dediren-plugin-generic-graph" capabilities
"$BUNDLE/bin/dediren-plugin-elk-layout" capabilities
"$BUNDLE/bin/dediren-plugin-svg-render" capabilities
"$BUNDLE/bin/dediren-plugin-archimate-oef-export" capabilities
"$BUNDLE/bin/dediren-plugin-uml-xmi-export" capabilities
```

Capability output is raw JSON using `schemas/runtime-capability.schema.json`.
Workflow commands return command envelopes using `schemas/envelope.schema.json`.
Packaged launchers set `DEDIREN_BUNDLE_ROOT` automatically so commands can run
from any current working directory.

## Bundle Smoke Workflow

```bash
"$BUNDLE/bin/dediren" validate \
  --input "$BUNDLE/fixtures/source/valid-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view main \
  --input "$BUNDLE/fixtures/source/valid-basic.json" \
  > layout-request.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input layout-request.json \
  > layout-result.json

"$BUNDLE/bin/dediren" validate-layout \
  --input layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin svg-render \
  --policy "$BUNDLE/fixtures/render-policy/default-svg.json" \
  --input layout-result.json \
  > render-result.json
```

The `elk-layout` plugin uses official Eclipse ELK Java libraries and requires
Java 21 or newer. It does not use external layout adapters. Use
`layout_preferences.mode: "flow"` for directed diagrams that need ELK Layered
placement and routing. Use `layout_preferences.mode: "packed"` only for
edge-less node/group maps; this selects official ELK Rectangle Packing and
returns no edge routes.

## Export

ArchiMate OEF:

```bash
"$BUNDLE/bin/dediren" export \
  --plugin archimate-oef \
  --policy "$BUNDLE/fixtures/export-policy/default-oef.json" \
  --source "$BUNDLE/fixtures/source/valid-archimate-oef.json" \
  --layout "$BUNDLE/fixtures/layout-result/archimate-oef-basic.json" \
  > oef-result.json
```

UML/XMI:

```bash
"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-basic.json" \
  --layout "$BUNDLE/fixtures/layout-result/uml-basic.json" \
  > xmi-result.json
```

Use `DEDIREN_OEF_SCHEMA_DIR` or `DEDIREN_XMI_SCHEMA_PATH` for offline schema
validation. Use `DEDIREN_SCHEMA_CACHE_DIR` when downloads are allowed and a
stable cache location is desired.

## Repair Rules

- `DEDIREN_SOURCE_SCHEMA_INVALID`: validate against `schemas/model.schema.json`.
- `DEDIREN_SOURCE_DUPLICATE_ID`: make node, relationship, view, and group ids
  unique.
- `DEDIREN_SOURCE_DANGLING_RELATIONSHIP`: repair relationship source/target
  ids or include the missing node.
- `DEDIREN_SOURCE_AUTHORED_GEOMETRY`: remove authored geometry from source
  JSON.
- `DEDIREN_PLUGIN_UNKNOWN`: inspect `plugins/*.manifest.json` in the bundle or
  explicit `DEDIREN_PLUGIN_DIRS`.
- `DEDIREN_PLUGIN_MISSING_EXECUTABLE`: inspect the manifest executable and the
  bundle `bin/` directory.
- `DEDIREN_PLUGIN_OUTPUT_INVALID_*`: treat plugin stdout as invalid and do not
  continue the pipeline.
- `DEDIREN_COMMAND_INPUT_INVALID`: the CLI could not read or parse a command
  input file.

## Plugin Environment

Bundle launchers use `DEDIREN_BUNDLE_ROOT` for product-root discovery. Plugin
child processes launched by the core receive only manifest-listed environment
variables. Important explicit variables:

- `DEDIREN_BUNDLE_ROOT`: explicit bundle or repository root for bundled
  schemas, plugin manifests, and launchers. Packaged launchers set this
  automatically.
- `DEDIREN_PLUGIN_DIRS`: additional manifest directories.
- `DEDIREN_PLUGIN_<PLUGIN_ID>`: per-plugin executable override.
- `DEDIREN_OEF_SCHEMA_DIR`: local OEF schema directory.
- `DEDIREN_XMI_SCHEMA_PATH`: local XMI schema file.
- `DEDIREN_SCHEMA_CACHE_DIR`: cache directory for schema downloads.

Keep stderr for human debugging only. Agents should decide success or failure
from stdout JSON.

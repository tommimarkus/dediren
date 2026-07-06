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
| Render policy | Usually reuse | `schemas/render-policy.schema.json` | `fixtures/render-policy/default-svg.json` |
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
    { "id": "generic-graph", "version": "2026.07.5" }
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

Every emitted SVG names itself for assistive technology: the root `<svg>` has
`role="img"` with a `<title>` (and a `<desc>` when supplied). Set the text with
an optional `accessibility` block in the render policy, for example
`"accessibility": { "title": "Order Processing", "description": "Application cooperation view" }`;
without it the `<title>` falls back to the layout `view_id`, so shipped
diagrams should use a policy copy with a real title.

## Semantic Profiles

For ArchiMate® SVG notation or OEF export, set the generic graph semantic
profile and use ArchiMate type names:

```json
{
  "required_plugins": [
    { "id": "generic-graph", "version": "2026.07.5" },
    { "id": "archimate-oef", "version": "2026.07.5" }
  ],
  "plugins": {
    "generic-graph": {
      "semantic_profile": "archimate",
      "views": []
    }
  }
}
```

An `archimate-oef` export renders the single laid-out view it is handed and
preserves node/relationship `properties` via OEF `<propertyDefinitions>` and
per-element `<property>` values, so evidence-classification markers survive the
export. When the source declares more views than the exported one, the omission
is declared (rather than dropped silently) with the `info` diagnostic
`DEDIREN_OEF_VIEWS_OMITTED`, which names the omitted view ids and counts; the
envelope `status` stays `ok`. Because the document always carries a
`<views>`/`<diagrams>` element it declares and validates against
`archimate3_Diagram.xsd`, not the model-only `archimate3_Model.xsd`; point
`DEDIREN_OEF_SCHEMA_DIR` at a directory holding all three ArchiMate 3.1 OEF XSDs.

For UML® SVG notation or XMI export, use `semantic_profile: "uml"` and the
`uml-xmi` plugin. Supported UML view kinds are `uml-class`, `uml-data`,
`uml-activity`, `uml-sequence`, `uml-state-machine`, `uml-use-case`,
`uml-component`, and `uml-deployment`.

## ArchiMate Handoff

The `archimate` profile accepts exactly these type names.

Elements: `Plateau`, `WorkPackage`, `Deliverable`, `ImplementationEvent`,
`Gap`, `AndJunction`, `OrJunction`, `Grouping`, `Location`, `Stakeholder`,
`Driver`, `Assessment`, `Goal`, `Outcome`, `Value`, `Meaning`, `Constraint`,
`Requirement`, `Principle`, `CourseOfAction`, `Resource`, `ValueStream`,
`Capability`, `BusinessInterface`, `BusinessCollaboration`, `BusinessActor`,
`BusinessRole`, `BusinessProcess`, `BusinessService`, `BusinessInteraction`,
`BusinessFunction`, `BusinessEvent`, `Product`, `BusinessObject`, `Contract`,
`Representation`, `ApplicationInterface`, `ApplicationCollaboration`,
`ApplicationComponent`, `ApplicationService`, `ApplicationInteraction`,
`ApplicationFunction`, `ApplicationProcess`, `ApplicationEvent`, `DataObject`,
`TechnologyInterface`, `TechnologyCollaboration`, `Node`, `SystemSoftware`,
`Device`, `Facility`, `Equipment`, `Path`, `TechnologyService`,
`TechnologyInteraction`, `TechnologyFunction`, `TechnologyProcess`,
`TechnologyEvent`, `Artifact`, `Material`, `CommunicationNetwork`,
`DistributionNetwork`.

Relationships: `Composition`, `Aggregation`, `Assignment`, `Realization`,
`Specialization`, `Serving`, `Access`, `Influence`, `Flow`, `Triggering`,
`Association`. `AndJunction`/`OrJunction` are relationship connector nodes.

Relationship endpoint pairs are semantically validated with the relationship
direction mattering (for example `ApplicationComponent --Realization-->
ApplicationService` and `ApplicationService --Serving--> BusinessActor` are
valid; the reversed directions are diagnosed).

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile archimate \
  --input "$BUNDLE/fixtures/source/valid-pipeline-archimate.json"
```

Continue with the Bundle Smoke Workflow commands, using
`--policy "$BUNDLE/fixtures/render-policy/archimate-svg.json"` for ArchiMate
SVG notation, and the OEF export under `## Export`.

A `uml-xmi` export represents the single laid-out view it is handed, not the
whole source model. In a `uml-class`/`uml-data` view it emits class
relationships (`Association`, `Aggregation`, `Composition`, `Dependency`, and
`Realization` between classifiers) as owned `packagedElement`s and nests
classifiers under the `Package` they declare via `properties.uml.package`. When
the source model contains elements or relationships outside the exported view,
the export declares them (rather than dropping them silently) with `info`
diagnostics `DEDIREN_XMI_ELEMENTS_OMITTED` and
`DEDIREN_XMI_RELATIONSHIPS_OMITTED`, each listing the omitted count and a
per-type breakdown; the envelope `status` stays `ok`. Read those diagnostics
from `.diagnostics[]` to know exactly what a given XMI does and does not cover
(for example, to disclose "classes only") and export the other views to
represent their content.

Class content is canonical UML 2.5.1: every attribute `type` resolves to an
`xmi:id` in the document (an emitted classifier, or a self-contained
`uml:PrimitiveType`/`uml:DataType` synthesized for standard primitives and
domain types) rather than a dangling type-name string, and multiplicities are
owned `lowerValue` (`uml:LiteralInteger`) / `upperValue`
(`uml:LiteralUnlimitedNatural`, `*` for unbounded) value-specification children
rather than XML attributes. To schema-check the emitted UML content, point
`DEDIREN_XMI_SCHEMA_PATH` at a driver schema that imports the OMG `XMI.xsd` and
a UML 2.5.1 XSD and run `xmllint --nonet --noout --schema <driver.xsd>
<document>`; OMG does not publish an importable UML 2.5.1 XSD, so supply or
generate one, or import the document into a UML tool. Without a UML schema only
the XMI envelope is checked. The `capabilities` command reports this recipe
under `runtime.schema_validation.uml_content_validation`.

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

"$BUNDLE/bin/dediren" render --plugin render \
  --policy "$BUNDLE/fixtures/render-policy/default-svg.json" \
  --input layout-result.json \
  > render-result.json

jq -r '.data.artifacts[] | select(.artifact_kind=="svg") | .content' render-result.json > diagram.svg
```

The `render` plugin also emits a base64-encoded `png` artifact (`encoding: base64`) when the policy includes a `raster` block (e.g. `"raster": { "scale": 2 }`); decode it with `base64 -d > diagram.png`.

## Render Policy Options

The render policy owns SVG presentation. Beyond `accessibility` (above) and
`raster` (PNG), these options shape output:

- **Interactive SVG.** `interactive` is `none` (default — a static SVG with no
  embedded script), `svg` (a self-contained SVG that highlights a node's edges
  on click), `html` (an HTML page wrapping that SVG), or `both` (an `svg` and an
  `html` artifact). The render result is an ordered `artifacts[]` list — select
  the one you want by `artifact_kind`. `style.interaction.highlight_stroke` and
  `style.interaction.highlight_stroke_width` tune the highlight.
- **Edge label backing.** Edge labels default to outlined text; set
  `style.edge.label_presentation` to `background` for a filled label backing.
- **UML association-end adornments.** In UML class diagrams, multiplicity and
  role carried in render metadata (`properties.uml.{source,target}_multiplicity`
  and `properties.uml.{source,target}_role`) are drawn beside their own end of
  the edge, each wrapped in a
  `data-dediren-edge-adornment="<source|target>_<multiplicity|role>"` group so
  consumers can find them.

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
  --plugin render \
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
`GeneralOrdering`, `ignore`, `consider`, and UMLDI are not yet supported.

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
  --plugin render \
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
trigger event metaclasses, effects as behavior nodes, and UMLDI.

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
  --plugin render \
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
use-case generalization, collaboration use-case realizations, and UMLDI.

## UML Component Handoff

Use `fixtures/source/valid-uml-component-basic.json` for the component MVP.
Author `Component` and `Port` nodes alongside `Package`, `Interface`, and
`Class` classifiers. Put `Port.properties.uml.component` on each port; optional
`provided` and `required` arrays reference interface ids. Use `Usage`,
`Realization`, and `Dependency` relationships, and model package/component
boundaries as semantic-backed view groups.

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-component-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view component-view \
  --input "$BUNDLE/fixtures/source/valid-uml-component-basic.json" \
  > component-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view component-view \
  --input "$BUNDLE/fixtures/source/valid-uml-component-basic.json" \
  > component-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input component-layout-request.json \
  > component-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata component-render-metadata.json \
  --input component-layout-result.json \
  > component-render-result.json

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-component-basic.json" \
  --layout component-layout-result.json \
  > component-xmi-result.json
```

Rules: `Port.properties.uml.component` must reference a `Component`; `provided`
and `required` entries must reference `Interface` nodes. Deferred/non-goals:
composite structure, connectors, collaborations, and UMLDI.

## UML Deployment Handoff

Use `fixtures/source/valid-uml-deployment-basic.json` for the deployment MVP.
Author `Node`, `Device`, `ExecutionEnvironment`, `Artifact`, and
`DeploymentSpecification` nodes alongside manifested structural classifiers.
Put optional `ExecutionEnvironment.properties.uml.node` on nested runtimes. Use
`Deployment`, `Manifestation`, and `CommunicationPath` relationships, and model
deployment target boundaries as semantic-backed view groups.

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-deployment-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view deployment-view \
  --input "$BUNDLE/fixtures/source/valid-uml-deployment-basic.json" \
  > deployment-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view deployment-view \
  --input "$BUNDLE/fixtures/source/valid-uml-deployment-basic.json" \
  > deployment-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input deployment-layout-request.json \
  > deployment-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata deployment-render-metadata.json \
  --input deployment-layout-result.json \
  > deployment-render-result.json

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-deployment-basic.json" \
  --layout deployment-layout-result.json \
  > deployment-xmi-result.json
```

Rules: `Deployment` connects an `Artifact` or `DeploymentSpecification` to a
deployment target; `Manifestation` connects an artifact or deployment
specification to a structural classifier; `CommunicationPath` connects
deployment targets. Deferred/non-goals: full nested part/property modeling,
deployment slots, and UMLDI.

## Runtime Probes

```bash
VERSION=2026.07.5
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}

"$BUNDLE/bin/dediren" --version
"$BUNDLE/bin/dediren-plugin-generic-graph" capabilities
"$BUNDLE/bin/dediren-plugin-elk-layout" capabilities
"$BUNDLE/bin/dediren-plugin-render" capabilities
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
  --plugin render \
  --policy "$BUNDLE/fixtures/render-policy/default-svg.json" \
  --input layout-result.json \
  > render-result.json
```

`validate-layout` quality fields: `overlap_count`, `connector_through_node_count`,
`invalid_route_count`, `route_detour_count`, `route_close_parallel_count`,
`group_boundary_issue_count`, `group_label_band_issue_count`,
`label_space_issue_count`, `edge_label_dissociation_count`,
`edge_crossing_count` (informational only), and
`warning_count`. The payload `data.status` is `ok` only when all
non-informational counts and warnings are zero; otherwise it is `warning`, and
the command envelope now restates that verdict so consumers reading only
`.status`/`.diagnostics[]` see it: envelope `status` becomes `warning` and one
`DEDIREN_LAYOUT_QUALITY_WARNING` diagnostic (severity `warning`, `path` pointing
at the offending `data.*` count) is emitted per nonzero non-informational count.
A warning verdict is not a failure — the exit code stays `0`. ArchiMate junction
nodes detached from an incident edge route fail with
`DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE`.

The `elk-layout` plugin uses official Eclipse ELK Java libraries and requires
Java 21 or newer. It does not use external layout adapters. Use
`layout_preferences.mode: "flow"` for directed diagrams that need ELK Layered
placement and routing. Use `layout_preferences.mode: "packed"` only for
edge-less node/group maps; this selects official ELK Rectangle Packing and
returns no edge routes. The `algorithm` option selects the layout algorithm
(`layered`, the default, is currently the only value). `routing.style` accepts
`orthogonal` (default), `polyline`, or `spline`. Layered phase strategies
(`cycle_breaking`, `layering.strategy`, `crossing.strategy`,
`crossing.greedy_switch`, `placement.strategy`), graph tuning (`compaction`,
`components`, `high_degree_nodes`, `thoroughness`), and per-node placement hints
(`layer_constraint`, `partition`) are also configurable under
`layout_preferences`; see `schemas/layout-request.schema.json` for the allowed
values.

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

Export envelopes carry one artifact directly at `.data.artifact_kind` and
`.data.content` — unlike render's `.data.artifacts[]` array:

```bash
jq -r '.data.content' oef-result.json > model-oef.xml
```

The default export policies hard-code fixture identity: `default-oef.json`
sets `model_identifier: "id-dediren-oef-basic-model"` and
`model_name: "Dediren OEF Basic"`. Export succeeds with them unchanged, so
copy the policy and replace the identity fields for a real model:

```json
{
  "oef_export_policy_schema_version": "oef-export-policy.schema.v1",
  "model_identifier": "id-my-model",
  "model_name": "My Model",
  "view_identifier": "id-view-main",
  "view_name": "Main",
  "viewpoint": "Application Cooperation"
}
```

Use `DEDIREN_OEF_SCHEMA_DIR` or `DEDIREN_XMI_SCHEMA_PATH` for offline schema
validation. `DEDIREN_OEF_SCHEMA_DIR` must point at a flat directory containing
the ArchiMate 3.1 XSDs (`archimate3_Model.xsd`, `archimate3_View.xsd`,
`archimate3_Diagram.xsd`); `DEDIREN_XMI_SCHEMA_PATH` points at the XMI 2.5.1
`XMI.xsd` file itself. Use `DEDIREN_SCHEMA_CACHE_DIR` when downloads are
allowed and a stable cache location is desired; one online run populates a
reusable offline cache (subtrees `opengroup/archimate/3.1/` and
`omg/xmi/2.5.1/`, which also satisfy the two offline variables). Give these
paths as absolute: plugins run from the bundle's product root, so a relative
value resolves against that root rather than your current directory.

When schema downloads must go through a proxy, set `HTTP_PROXY`, `HTTPS_PROXY`,
and `NO_PROXY` (or their lowercase forms) before invoking `dediren`; the export
plugins forward them to `curl`. If a download still fails, the
`DEDIREN_OEF_SCHEMA_UNAVAILABLE` / `DEDIREN_XMI_SCHEMA_UNAVAILABLE` diagnostic
message names both the proxy variables and the offline schema-path fallback, so
you can recover from stdout JSON alone.

## Repair Rules

- `DEDIREN_SCHEMA_INVALID`: validate against `schemas/model.schema.json`. A
  common cause is authored geometry (`x`, `y`, `width`, `height`) or other
  fields the schema rejects on a node — source JSON is semantic only, so remove
  them.
- `DEDIREN_DUPLICATE_ID`: make node, relationship, view, and group ids unique.
- `DEDIREN_DANGLING_ENDPOINT`: repair relationship source/target ids or include
  the missing node.
- `DEDIREN_PLUGIN_UNKNOWN`: inspect `plugins/*.manifest.json` in the bundle,
  `.dediren/plugins/*.manifest.json` under the directory you run the CLI from
  (discovered only when `DEDIREN_ALLOW_PROJECT_PLUGINS=1`), or explicit
  `DEDIREN_PLUGIN_DIRS`. Security: a project-supplied plugin runs as arbitrary
  code with your privileges, so do not enable that flag or invoke such a plugin
  for a repository you did not author without explicit human confirmation.
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
- `DEDIREN_PLUGIN_DIRS`: additional manifest directories. Discovery order is
  bundled plugins, then (opt-in) `.dediren/plugins` under the CLI's current
  working directory, then these directories; never `PATH`.
- `DEDIREN_ALLOW_PROJECT_PLUGINS`: opt-in; when `1` or `true`, enables discovery
  of `.dediren/plugins` under the CLI's current working directory. Off by
  default. Security: such a plugin executable runs unsandboxed with your
  privileges and is unsigned, so treat a cloned repository's `.dediren/plugins`
  as untrusted — do not enable this for a repository you did not author without
  explicit human confirmation.
- `DEDIREN_PLUGIN_<PLUGIN_ID>`: per-plugin executable override.
- `DEDIREN_OEF_SCHEMA_DIR`: local OEF schema directory.
- `DEDIREN_XMI_SCHEMA_PATH`: local XMI schema file, or a driver schema that
  imports `XMI.xsd` plus a UML 2.5.1 XSD to also validate UML content.
- `DEDIREN_SCHEMA_CACHE_DIR`: cache directory for schema downloads.
- `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` (and their lowercase forms): forwarded
  to the export plugins so `curl` can download standards schemas through a proxy.
- `DEDIREN_CDS_DIR`: directory for Class-Data-Sharing archives (see below).
- `DEDIREN_TRUST_MANIFEST_CAPABILITIES`: opt-in; trusts each plugin's static
  manifest capabilities and skips the per-call runtime probe, removing one JVM
  start per plugin operation; bypasses the runtime id-mismatch pre-check. Honored
  only for bundled first-party plugins; manifests in `.dediren/plugins` or
  `DEDIREN_PLUGIN_DIRS` always keep the probe. Default (unset) keeps the probe
  and all integrity checks.

Each `bin/dediren*` launcher auto-creates a Class-Data-Sharing archive on its
first invocation to speed JVM startup on subsequent calls. Archives are written
to `<bundle>/cds/` by default (one `.jsa` file per launcher). If that directory
is read-only, the launcher falls back to `${XDG_CACHE_HOME:-$HOME/.cache}/dediren/cds`.
Set `DEDIREN_CDS_DIR` to an explicit writable path to relocate all archives.
The feature is based on `-XX:+AutoCreateSharedArchive` and degrades silently if
the archive directory is unwritable — startup continues at normal speed without
any error. Launchers also pass `-Xlog:cds=off` so the JVM's per-invocation CDS
archive-dump warnings never reach stdout/stderr; a healthy run stays quiet and
stderr remains reserved for genuine diagnostics.

Each archive is seeded by that launcher's first invocation and is not
regenerated while it stays valid, and its contents depend on what that first
command loaded: an archive seeded by `--version` or a `capabilities` probe
stays measurably slower (about 30% per call) than one seeded by real work.
Seed each launcher with a representative workload command — running the
Bundle Smoke Workflow once covers the whole pipeline — before or instead of
trivial probes. To reseed, delete the `.jsa` files or point `DEDIREN_CDS_DIR`
at a fresh directory.

Setting `DEDIREN_TRUST_MANIFEST_CAPABILITIES=1` (or `true`) makes dediren trust
each plugin's static manifest capabilities and skip the per-call runtime
capability probe, removing one JVM start per plugin operation. The tradeoff is
that the runtime `id`-mismatch pre-flight check is bypassed, so the flag is
honored only for bundled first-party plugins (manifests in the bundle's
`plugins/` directory). Manifests discovered in `.dediren/plugins` or a
`DEDIREN_PLUGIN_DIRS` entry always keep the probe regardless of this flag.
Default (unset) keeps the probe and all integrity checks.

Keep stderr for human debugging only. Agents should decide success or failure
from stdout JSON.

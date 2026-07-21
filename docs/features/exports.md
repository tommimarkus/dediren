# Exports (OEF & XMI)

Beyond SVG, Dediren exports standards XML: ArchiMate 3.2 Open Exchange Format
(OEF) and UML 2.5.1 XMI. Export consumes the **source** model and the **layout
result** together and writes XML to `.data.content`.

[← Back to feature index](README.md)

ArchiMate/OEF semantics live in the `archimate-oef-export` plugin; UML/XMI
semantics live in the `uml-xmi-export` plugin. The matching plugin must be
declared in the source model's `required_plugins`.

```bash
# ArchiMate OEF
dediren export --plugin archimate-oef \
  --policy fixtures/export-policy/default-oef.json \
  --source fixtures/source/valid-archimate-oef.json \
  --layout fixtures/layout-result/archimate-oef-basic.json > oef-result.json

# UML XMI
dediren export --plugin uml-xmi \
  --policy fixtures/export-policy/default-uml-xmi.json \
  --source fixtures/source/valid-uml-basic.json \
  --layout layout-result.json > xmi-result.json

jq -r '.data.content' xmi-result.json > diagram.xmi
```

Policy schemas:
[`oef-export-policy.schema.json`](../../schemas/oef-export-policy.schema.json),
[`uml-xmi-export-policy.schema.json`](../../schemas/uml-xmi-export-policy.schema.json).
Result schema:
[`export-result.schema.json`](../../schemas/export-result.schema.json).
The base schema keeps an open `artifact_kind` pattern
(`^[a-z0-9][a-z0-9.-]*\+(xml|json|text)$`) rather than a closed list, by
design, for a future non-bundled export engine; the two bundled first-party
export engines (`archimate-oef`, `uml-xmi`) are additionally held to the
closed first-party enum in
[`export-result.first-party.schema.json`](../../schemas/export-result.first-party.schema.json).

## Standards Validation

OEF and UML/XMI export paths validate against the official standards schemas
using `xmllint` (required on `PATH`). Schema sources:

- **Online:** `curl` fetches schemas into a cache; set
  `DEDIREN_SCHEMA_CACHE_DIR` for a stable cache location. Behind a proxy, set
  `HTTP_PROXY`, `HTTPS_PROXY`, and `NO_PROXY` (or their lowercase forms) before
  invoking `dediren`: `curl` runs as a subprocess of the CLI process and
  inherits them directly, no plugin-specific forwarding involved.
- **Offline:** provide local schema files with `DEDIREN_OEF_SCHEMA_DIR` (OEF
  directory) and `DEDIREN_XMI_SCHEMA_PATH` (XMI schema file).

When a download fails, `DEDIREN_OEF_SCHEMA_UNAVAILABLE` /
`DEDIREN_XMI_SCHEMA_UNAVAILABLE` names both remediations in its message — the
proxy variables to expose and the offline schema path to set — so an agent can
recover from stdout JSON alone.

## ArchiMate OEF

Use `semantic_profile: "archimate"` and ArchiMate 3.2 type names in the source
model. The export emits ArchiMate Open Exchange Format XML. ArchiMate
junction-role nodes (`AndJunction`/`OrJunction`) participate in layout/render and
must stay on incident edge routes (see [Layout](layout.md#junction-routing)).

OEF diagram connections preserve generated layout routes using OEF attachment
semantics: the first route point is emitted as `sourceAttachment`, the last route
point as `targetAttachment`, and only intermediate route points are emitted as
`bendpoint`. This keeps schema-valid XML closer to how importing tools expect
relationship anchors and avoids treating node attachment points as free-standing
bendpoints.

Because the emitted document always carries a `<views>`/`<diagrams>` element, it
declares (and validates against) the diagram-bearing `archimate3_Diagram.xsd`,
not the model-only `archimate3_Model.xsd` — a diagram-bearing OEF fails the
latter. `DEDIREN_OEF_SCHEMA_DIR` must therefore contain all three ArchiMate 3.1
OEF XSDs (`archimate3_Model.xsd`, `archimate3_View.xsd`, `archimate3_Diagram.xsd`)
so `xmllint` can resolve the include/redefine chain.

Node and relationship `properties` are preserved through the OEF property
mechanism: each distinct key becomes a model-level `<propertyDefinition>` and each
value is emitted as a `<property propertyDefinitionRef="…">` on its element or
relationship. This keeps evidence-classification markers (for example
`candidate-from-source`, a confidence score, or a source path) attached to the
exported concept instead of being dropped.

A standalone OEF export renders exactly the one laid-out view it is handed. When
the source declares more views than the exported one, the omission is declared
(not silently dropped) with an `info` diagnostic `DEDIREN_OEF_VIEWS_OMITTED` that
names the omitted view ids and counts; the envelope `status` stays `ok`. Read
`.diagnostics[]` to see which diagrams a given OEF does not carry, and export the
other views to represent them.

For whole-model interchange, `dediren build` with `--oef-policy` also composes
`model.oef.xml` at the output root — one document carrying every built view's
diagram, each with its own identity (policy `views` override, else a
source-derived default), listed under the build result's `model_artifacts`.
Import that one file into Archi/EA instead of reassembling per-view files.

## UML/XMI

Use `semantic_profile: "uml"` and the `uml-xmi` plugin. View kinds: `uml-class`,
`uml-data`, `uml-activity`, `uml-sequence`, `uml-state-machine`, `uml-use-case`,
`uml-component`, `uml-deployment`. For SVG of the notation diagrams below, also
generate render
metadata (see [SVG Rendering](svg-render.md#notation-rendering--render-metadata)).

The following sections summarize each UML notation's supported vocabulary and
rules, and the constructs intentionally deferred (non-goals for the current
slices). The starter fixture is named for each.

### Sequence — `valid-uml-sequence-basic.json` / `valid-uml-sequence-fragments.json`

Vocabulary: `Interaction`, `Lifeline`, `Message`, `ExecutionSpecification`,
`Gate`, `DestructionOccurrenceSpecification`, `CombinedFragment`,
`InteractionOperand`.

Message sorts: `synchCall`, `asynchCall`, `asynchSignal`, `reply`,
`createMessage`, `deleteMessage`.

Ordered `Message` relationships carry `properties.uml.sequence` plus
`message_sort`. Combined fragments support the `alt`, `opt`, `loop`, and `par`
interaction operators (use the `-fragments` fixture and `--view
sequence-fragments-view`). Rules: message `sequence` values are unique within an
interaction; each operand's `fragments` list follows the referenced messages'
sequence order; a combined fragment must not leave standalone messages inside its
owned sequence span. These rules keep rendered SVG and exported XMI in the same
interaction order.

Deferred: `InteractionUse`, `GeneralOrdering`, `ignore`, `consider`, and UMLDI.

### State machine — `valid-uml-state-machine-basic.json`

Vocabulary, semantic-backed groups, pseudostate/transition kinds, and deferred
constructs: [Agent Usage → UML State Machine
Handoff](../agent-usage.md#uml-state-machine-handoff).

### Use case — `valid-uml-use-case-basic.json`

Vocabulary, authoring rules, and deferred constructs: [Agent Usage → UML Use
Case Handoff](../agent-usage.md#uml-use-case-handoff). Additionally:
`Association` may connect actors and use cases in either direction.

### Component — `valid-uml-component-basic.json`

Vocabulary: `Component`, `Port` plus structural classifiers `Package`,
`Interface`, `Class`. Relationships: `Usage`, `Realization`, `Dependency`.
`Port.properties.uml.component` must reference its owning `Component`; optional
`provided`/`required` arrays reference `Interface` nodes. Component and package
boundaries are semantic-backed groups. `Usage` connects a component or port to a
structural classifier; `Realization`/`Dependency` reuse the structural
relationship rules.

Deferred: composite structure, connectors, collaborations, UMLDI.

### Deployment — `valid-uml-deployment-basic.json`

Vocabulary, authoring rules, and deferred constructs: [Agent Usage → UML
Deployment Handoff](../agent-usage.md#uml-deployment-handoff). Additionally:
`Deployment` connects an `Artifact` or `DeploymentSpecification` to a
`Node`/`Device`/`ExecutionEnvironment`, and `CommunicationPath` connects
deployment targets in either direction.

## Related Pages

- [Source Model & Views](source-model.md) — semantic profiles and groups.
- [SVG Rendering](svg-render.md) — render metadata for notation diagrams.
- [Distribution & Runtime](distribution-and-runtime.md) — schema cache env vars.

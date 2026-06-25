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

## Standards Validation

OEF and UML/XMI export paths validate against the official standards schemas
using `xmllint` (required on `PATH`). Schema sources:

- **Online:** `curl` fetches schemas into a cache; set
  `DEDIREN_SCHEMA_CACHE_DIR` for a stable cache location.
- **Offline:** provide local schema files with `DEDIREN_OEF_SCHEMA_DIR` (OEF
  directory) and `DEDIREN_XMI_SCHEMA_PATH` (XMI schema file).

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

## UML/XMI

Use `semantic_profile: "uml"` and the `uml-xmi` plugin. View kinds: `uml-class`,
`uml-data`, `uml-activity`, `uml-sequence`, `uml-state-machine`, `uml-use-case`,
`uml-component`. For SVG of the notation diagrams below, also generate render
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

Vocabulary: `StateMachine`, `Region`, `State`, `FinalState`, `Pseudostate`,
`Transition`. `StateMachine` and `Region` are semantic-backed view groups
(`semantic_source_id`); states are nodes and transitions are relationships.

Pseudostate kinds: `initial`, `deepHistory`, `shallowHistory`, `join`, `fork`,
`junction`, `choice`, `entryPoint`, `exitPoint`, `terminate`. Transition kinds:
`internal`, `local`, `external`.

Deferred: `ConnectionPointReference`, `ProtocolStateMachine`,
`ProtocolTransition`, submachine states, orthogonal multi-region internals,
trigger event metaclasses, effects as behavior nodes, and UMLDI.

### Use case — `valid-uml-use-case-basic.json`

Vocabulary: `Actor`, `UseCase`, `ExtensionPoint` plus `Association`, `Include`,
`Extend`. `Include`/`Extend` connect `UseCase -> UseCase`; `Association` may
connect actors and use cases in either direction. The subject boundary is a
semantic-backed group whose `semantic_source_id` points at a UML structural
classifier. `UseCase.properties.uml.subject` references that classifier;
`ExtensionPoint.properties.uml.use_case` references its owning use case;
`Extend.properties.uml.extension_point`, when present, must reference an
extension point owned by the extended target use case.

Deferred: use-case generalization, collaboration use-case realizations, UMLDI.

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

Vocabulary: `Node`, `Device`, `ExecutionEnvironment`, `Artifact`,
`DeploymentSpecification`, and manifested structural classifiers. Relationships:
`Deployment`, `Manifestation`, `CommunicationPath`. Optional
`ExecutionEnvironment.properties.uml.node` marks nested runtimes; deployment
target boundaries are semantic-backed groups. `Deployment` connects an `Artifact`
or `DeploymentSpecification` to a `Node`/`Device`/`ExecutionEnvironment`;
`Manifestation` connects an artifact or deployment specification to a structural
classifier; `CommunicationPath` connects deployment targets in either direction.

Deferred: full nested part/property modeling, deployment slots, UMLDI.

## Related Pages

- [Source Model & Views](source-model.md) — semantic profiles and groups.
- [SVG Rendering](svg-render.md) — render metadata for notation diagrams.
- [Distribution & Runtime](distribution-and-runtime.md) — schema cache env vars.

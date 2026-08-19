# Exports (OEF, XMI & draw.io)

Beyond SVG, Dediren exports XML. Two of the three lanes are standards
interchange — the ArchiMate Open Exchange Format (OEF) and UML 2.5.1 XMI — and
the third, draw.io, is an editable-picture format with no standards schema
behind it. Every lane consumes the **source** model and the **layout result**
together and writes XML to `.data.content`.

The two version numbers here are different things and are easy to conflate. The
modelling **vocabulary** is ArchiMate 3.2 — that is the element and relationship
set the `archimate` profile accepts. The **exchange format** is OEF 3.1, which
is the latest The Open Group publishes; there is no 3.2 OEF XSD to validate
against. An emitted document therefore carries 3.2 vocabulary inside a 3.1
envelope, and staging "3.2 OEF XSDs" will find nothing.

[← Back to feature index](README.md)

ArchiMate/OEF semantics live in the `archimate-oef-export` plugin; UML/XMI
semantics live in the `uml-xmi-export` plugin. The matching plugin must be
declared in the source model's `required_plugins`. draw.io mapping lives in the
`drawio` plugin and needs no such declaration — it reads whatever notation the
view already declares (see [draw.io](#drawio) below).

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

# draw.io
dediren export --plugin drawio \
  --policy fixtures/export-policy/default-drawio.json \
  --source fixtures/source/valid-uml-basic.json \
  --layout fixtures/layout-result/uml-basic.json > drawio-result.json

jq -r '.data.content' xmi-result.json > diagram.xmi
jq -r '.data.content' drawio-result.json > diagram.drawio
```

Policy schemas:
[`oef-export-policy.schema.json`](../../schemas/oef-export-policy.schema.json),
[`uml-xmi-export-policy.schema.json`](../../schemas/uml-xmi-export-policy.schema.json),
[`drawio-export-policy.schema.json`](../../schemas/drawio-export-policy.schema.json).
Result schema:
[`export-result.schema.json`](../../schemas/export-result.schema.json).
`export-result.schema.v2` adds a nullable, plugin-owned `.data.assurance`
object; exporters that do not publish assurance may omit it. The base schema
keeps an open `artifact_kind` pattern
(`^[a-z0-9][a-z0-9.-]*\+(xml|json|text)$`) rather than a closed list, by
design, for a future non-bundled export engine; the three bundled first-party
export engines (`archimate-oef`, `uml-xmi`, `drawio`) are additionally held to
the closed first-party enum in
[`export-result.first-party.schema.json`](../../schemas/export-result.first-party.schema.json).
First-party UML/XMI results must carry an assurance object governed by
[`uml-xmi-assurance.schema.json`](../../schemas/uml-xmi-assurance.schema.json).

## Standards Validation

This applies to the two standards lanes only. draw.io has no published schema
this export validates against, so the `drawio` engine performs no schema
validation, emits no `DEDIREN_EXPORT_SCHEMA_CONFORMANCE` diagnostic, and has no
schema-cache dependency, no download, and no `*_SCHEMA_UNAVAILABLE` failure
mode.

The two standards export paths validate against the official standards schemas in-JVM via
`javax.xml.validation` — no external validator binary on either lane. Schema
imports (the W3C `xml.xsd` the ArchiMate XSDs reference, or a UML XSD beside
an XMI driver schema) resolve local-only from the schema file's own directory.
Every successful export carries a `DEDIREN_EXPORT_SCHEMA_CONFORMANCE` `info`
diagnostic naming exactly which schema was validated against and its
provenance (pinned SHA-256-verified download vs user-supplied path). UML/XMI
also publishes the stable machine contract in `.data.assurance`; diagnostics
remain the human-readable provenance companion. Schema sources:

- **Online:** Java's `HttpClient` fetches schemas into a cache; set
  `DEDIREN_SCHEMA_CACHE_DIR` for a stable cache location. Requests require
  HTTPS before and after redirects, use a 20-second connect timeout and
  60-second request timeout, and reject a response body above 8 MiB before it
  replaces a cache entry. Behind a proxy, precedence is `HTTPS_PROXY`, then
  `HTTP_PROXY`, then `ALL_PROXY`; lowercase wins within each name, and
  `NO_PROXY` supports exact hosts, leading-dot suffixes, and `*`. A selected
  proxy URI must be a credential-free `http` URI, include a host, and contain
  no path/query/fragment; secure proxy transport and proxy-URI credentials are
  unsupported. Invalid configuration fails closed rather than silently
  downloading directly.
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
plus, for the real Open Group set, the W3C `xml.xsd` they import — the in-JVM
validator resolves the include/import chain local-only from that directory (the
cache-download lane fetches all four automatically).

Node and relationship `properties` are preserved through the OEF property
mechanism: each distinct key becomes a model-level `<propertyDefinition>` and each
value is emitted as a `<property propertyDefinitionRef="…">` on its element or
relationship. This keeps evidence-classification markers (for example
`candidate-from-source`, a confidence score, or a source path) attached to the
exported concept instead of being dropped.

Each definition is typed by the values that key actually carries: JSON booleans
become `boolean`, JSON numbers become `number`, and everything else becomes
`string`, so a consuming tool can type-filter and sort rather than treating every
property as text. Definitions are model-level and keyed by name, so one key used
with different value types across concepts is declared `string` — the only one of
the format's six data types that represents them all. The remaining three
(`currency`, `date`, `time`) have no JSON counterpart to detect and are never
inferred. A `<value>` is text, so an object or array value can only be carried as
its JSON rendering; that is unrecoverable as structure on import, and each
occurrence is declared with a `warn` diagnostic
`DEDIREN_OEF_PROPERTY_FLATTENED` naming the key and its source path.

The `viewpoint` a view declares is copied through verbatim, because the exchange
format types it as a union that accepts any string — a tool-specific or
organization-specific viewpoint is legitimate. A name outside the format's own
viewpoint vocabulary is therefore exported, not rejected, with a `warn`
diagnostic `DEDIREN_OEF_VIEWPOINT_UNKNOWN` that offers the nearest known name
when the value looks like a typo.

Render-policy styling deliberately does not cross into OEF. The format's
`<style>` carries fill, line and font choices, but those are dediren's rendering
decisions for its own SVG, not properties of the model; emitting them would push
one tool's presentation into every consumer that imports the file. An OEF export
therefore carries structure, geometry and identity, and leaves appearance to the
importing tool.

Grouping containment crosses into OEF as structure, not just geometry: the view
nodes laid out inside a semantic `Grouping` are emitted as nested `<node>`
children of that grouping's own node, so an importing tool reconstructs the
containment instead of seeing overlapping sibling boxes. Nesting is recursive
(a grouping inside a grouping nests), coordinates stay absolute to the diagram,
and purely visual layout bands — which are not ArchiMate concepts — are not
emitted, with their members owned by the nearest enclosing semantic grouping.

A standalone OEF export renders exactly the one laid-out view it is handed. When
the source declares more views than the exported one, the omission is declared
(not silently dropped) with an `info` diagnostic `DEDIREN_OEF_VIEWS_OMITTED` that
names the omitted view ids and counts; the envelope `status` stays `ok`. Read
`.diagnostics[]` to see which diagrams a given OEF does not carry, and export the
other views to represent them.

The exchange format types diagram coordinates as `xs:nonNegativeInteger` and
sizes as `xs:positiveInteger`, which is narrower than the plain numbers
`layout-result.schema.json` permits — and a layout engine can legitimately place
a node at a negative coordinate. Rather than fail the export on its last stage
for geometry the caller cannot repair, the exporter rounds to integers and
clamps whatever falls outside those ranges, declaring each clamp with a `warn`
diagnostic `DEDIREN_OEF_GEOMETRY_CLAMPED` that names the original value and its
layout-result path. A clamped export is usable; the affected node or bendpoint
sits where the exchange schema allows rather than where the layout put it.

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

Each successful UML/XMI result declares all eight supported kinds under
`.data.assurance.kind_taxonomy`. Seven are standard UML diagram kinds;
`uml-data` is explicitly a Dediren-local classifier view that maps to the UML
class-diagram family. `artifact_scope` says whether the artifact is a single
view or a model aggregate and lists the selected view kinds. `coverage`
partitions source elements and relationships into represented, out-of-view
omitted, and in-view-but-unrepresented counts by source type.

The current `validation_evidence.level` is deliberately
`xmi-envelope-only`: Dediren validates the XMI envelope/schema and structural
IDs, but does not claim UML metamodel or importer validation. The schema
requires explicit non-empty metamodel/importer evidence before a future engine
may publish either stronger level. UMLDI is `provisional-aggregate` only for
the classifier family (`uml-class`, `uml-data`); the remaining families state
`uml_di: none`.

For whole-model interchange, `dediren build` with `--xmi-policy` also composes
`model.uml.xml` at the output root — one `<uml:Model>` plus one OMG UMLDI diagram
per classifier-diagram view (`uml-class`, `uml-data`), each with its own diagram
identity (policy `views` override, else a source-derived default), listed under
`model_artifacts`. The UMLDI dialect is **provisional** (not yet verified against
a real UML tool importer); other UML families contribute model content only.

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

## draw.io

```bash
dediren export --plugin drawio \
  --policy fixtures/export-policy/default-drawio.json \
  --source fixtures/source/valid-uml-basic.json \
  --layout fixtures/layout-result/uml-basic.json > drawio-result.json

jq -r '.data.content' drawio-result.json > diagram.drawio
```

`artifact_kind` is `drawio+xml`. The policy needs only
`drawio_export_policy_schema_version` (`drawio-export-policy.schema.v1`) and a
non-blank `diagram_name`; an optional `views` map keyed by view id carries a
per-view `diagram_name` override. A policy this engine cannot read
(`DEDIREN_DRAWIO_POLICY_INVALID`) is the one thing that stops a draw.io export —
see [Degrade, don't fail](#degrade-dont-fail) below. There is no schema fetch,
so this engine ignores the `env` map and the product root entirely.

The output is uncompressed mxGraph XML: an `<mxfile>` holding one `<diagram>`
page whose `<mxGraphModel>` carries the cells directly. draw.io opens it, and
`dediren import --plugin drawio` reads it back.

### Geometry is taken, never computed

Every coordinate comes straight from the layout result. There is no second
layout pass and no invented geometry, and exactly one arithmetic operation is
performed on a coordinate: a container rebase. The layout result's coordinates
are absolute, while mxGraph reads a child cell's geometry against its parent's
origin, so re-parenting an element into a container subtracts that container's
absolute origin — using the element's *immediate* parent, because groups nest.
Edges always ride the layer and never a container, because mxGraph interprets an
edge's waypoints relative to the edge's own parent and the route points are
absolute; parenting an edge into a group would displace its whole route while
leaving its endpoints correct.

### The `<object>` identity wrapper

Each element's `mxCell` is wrapped in an `<object>` carrying `dedirenId` and
`dedirenType` (the element's exact source type), plus `dedirenSource` /
`dedirenTarget` on an edge, `dedirenGroupRole` and the `dedirenSemanticSource*`
trio on a container. Endpoints are keyed by `dedirenId` rather than by `mxCell`
id because draw.io reassigns cell ids freely as a user edits — cell ids are the
one part of the file that does not survive a real editing session. The names are
camelCase because draw.io's Edit Data dialog shows them to a human verbatim.

One hidden, unlabelled metadata cell per page (`dedirenType="dediren.view"`)
carries what belongs to the view rather than to any element: semantic profile,
view id, view kind, model schema version, and the layout preferences. The
importer consumes it as metadata and never emits it as a node.

Shapes are chosen by the view's declared kind: the eight `uml-*` kinds read the
UML table, everything else reads ArchiMate, and a type the primary table does not
cover is looked up in the other before falling back. That ordering is what makes
`Node`, `Device` and `Artifact` — declared by *both* vocabularies and meaning
different things in each — resolve by the view's declared kind rather than by
table order. There is deliberately no reverse style-to-type index: sixteen UML
types share the plain-rectangle style, so import resolves the type from
`dedirenType` and never from the style string.

### Degrade, don't fail

Apart from an unreadable policy, nothing in this export is fatal. That is a
deliberate difference from the OEF exporter, whose equivalent layout-reference
check is a hard failure: a `.drawio` is an editable picture, and a picture
missing one box is more useful than no picture.

- An element or relationship type no draw.io shape covers is drawn as a neutral
  rectangle or a neutral line with a `DEDIREN_DRAWIO_SHAPE_UNMAPPED` **warning**.
  `dedirenType` still records the exact type, so re-importing the file is
  lossless regardless of the shape it was drawn with.
- A layout reference that resolves to nothing is reported rather than thrown.
- UML behaviour this export places but does not ornament is declared as
  `DEDIREN_DRAWIO_ORNAMENT_OMITTED` (info), which says in as many words that the
  result is a correctly positioned set of boxes and lines, not a rendered
  sequence diagram.

### Known limits

**No provenance stamp, and `dediren verify` does not see the file.** The stamp
is a build-lane step (`Provenance.stampXml`), and the build lanes construct the
ArchiMate OEF and UML XMI aggregates only — `core` selects no draw.io lane at
all. A `.drawio` file is therefore produced solely by the standalone `export`
command, carries no stamp, and is not among the artifact suffixes `verify` and
`status` enumerate (`.svg`, `.xml`, `.xmi`). Drift detection for this lane means
re-exporting and comparing, not `dediren verify`. What identity there is rests on
the `<object>` attributes, which draw.io preserves across an edit and a save.

**No multi-page whole-model file from any command.** The engine implements the
whole-model interface — the aggregate is page concatenation, which works cleanly
here because a draw.io page owns its own `mxCell` id space and each page keeps
its own metadata cell — but no build driver selects the draw.io lane, so no
command produces one today. The *importer* does read multi-page documents, one
view per page, which is how a hand-authored multi-page file arrives.

**UML classifier compartments are not drawn.** A Class exports as its box
labelled with the element's own name; the attribute and operation compartments
its source properties describe, and stereotype keywords, are not written. A plain
rectangle here is a decision rather than a gap — UML draws a Class, an Interface,
an Artifact and a lifeline head as plain rectangles — and both survive the round
trip regardless, on `dedirenType` and in the source model.

**Lifelines are drawn as the head only.** draw.io does ship a real
`shape=umlLifeline` stencil that draws a head plus a dashed tail down the cell,
but the tail spans the cell and Dediren lays a Lifeline out as its head alone
(64 tall in every sequence fixture) while the messages route below it. Drawn with
that stencil, every tail would stop short of every message it is supposed to
meet. Reaching the messages means deriving the stem extent from the enclosing
interaction frame — which is what the SVG renderer does, and what this builder's
geometry-is-taken-never-computed rule forbids. `DEDIREN_DRAWIO_ORNAMENT_OMITTED`
declares the missing tail, alongside execution occurrences (activation bars) and
destruction occurrences.

### Round trip

`export → import → project → layout → export` is meant to be the identity inside
Dediren-authored `.drawio` space: export is a retraction, so everything it writes
the importer reads and a second pass has nothing left to change. Byte equality is
a strictly stronger bar than structural equivalence, which excludes geometry,
style and document order by design, so it catches faults an equivalence relation
is blind to — a dropped `layout_preferences` block produces an identical
structure laid out at different coordinates.

`cli/src/test/java/dev/dediren/cli/DrawioRoundTripTest.java` is the authority on
how much of the fixture corpus currently clears that bar, and it is a live
measurement rather than a claim in this page: it sweeps the repository's own
`(source, view)` list through the whole live pipeline, and its
`NOT_YET_A_FIXED_POINT` map records every fixture that does not reach the fixed
point together with *how* it fails. A companion test re-measures every entry in
that map and fails when one starts passing, so the list can only shrink
deliberately — an exclusion that has quietly started working is a lie the suite
refuses to keep. Read the map, not a number copied out of it.

Two distinct residual classes turn up in that map, and they need different
remedies. The first is that mxGraph has nowhere of its own to keep an element's
`properties`, which bites twice: a model missing a *required* UML ownership
property (`Port.component`, `ExtensionPoint.use_case`, `Transition.region`,
`ExecutionSpecification.covered`) is rejected by `project` before a second export
can happen, and a model that stays valid but loses `uml.attributes` /
`uml.operations` comes back as the same graph drawn at different coordinates,
because those are what size a Class box. That whole class is carried by a
per-element property map on the page's hidden metadata cell — hidden rather than
on each element's own wrapper, because draw.io's Edit Data dialog would otherwise
show raw model JSON to whoever right-clicks a shape.

The second class no property channel can reach. A `.drawio` is a picture of a
*layout result*, so a view member the layout gives no geometry has no cell, and
an element with no cell is not in the file at all — its properties included.
`CombinedFragment` and `InteractionOperand` are the live example: the notation
layer consumes them to size the interaction frame and emits no box of their own,
so the re-imported view declares fewer nodes than the original and the frame
comes back shorter. Closing that means either inventing geometry the exporter
deliberately never invents, or adding a second identity channel for view members
no page draws — a separate decision, not taken.

A **foreign** file — one Dediren never produced — carries none of that
vocabulary, so only structure survives: it imports as the `generic-graph` profile
with `generic.node` / `generic.link` types, exactly like the DOT and Mermaid
importers. A recognized draw.io stencil is recorded under
`properties.drawio.stencil` with a suggested type and summarized in one
`DEDIREN_DRAWIO_KIND_INFERRED` info diagnostic, but is never promoted to a real
ArchiMate or UML type: draw.io encodes relationship semantics only as arrowhead
decoration, which cannot be reversed, and a wrongly promoted model would import
green and then fail `validate --profile`.

## Related Pages

- [Source Model & Views](source-model.md) — semantic profiles and groups.
- [SVG Rendering](svg-render.md) — render metadata for notation diagrams.
- [Distribution & Runtime](distribution-and-runtime.md) — schema cache env vars.

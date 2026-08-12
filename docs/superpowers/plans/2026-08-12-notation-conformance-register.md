# 2026-08-12 notation conformance register — UML 2.5.1 and ArchiMate 3.2

Diagnostic register measuring dediren's implementation against the two specifications it claims on
its front door, at `ac0175f`. **No code was changed.** This document exists so the decision about
what to fix is made against cited evidence rather than against the assumption that a green suite
means a conformant product.

Every finding carries a specification citation and a repo `file:line`. Interchange and notation
findings additionally cite **emitted bytes** — a committed golden or an artifact built for this
review — because reading an emitter is not evidence about what it emits.

## Provenance of the evidence

| | UML 2.5.1 | ArchiMate 3.2 |
| --- | --- | --- |
| Source | OMG spec, 796 pp, local extraction | The Open Group spec, 175 pp, local extraction |
| Citation anchor | per-clause `.3 Semantics` / `.4 Notation` section files | 269 indexed sections; exact PDF text preferred over OCR |
| Notation legibility | **prose** — glyphs are described in searchable text | **image-only** — notation tables are pictures; the text layer omits them and OCR garbles them, so notation was read from page rasters |
| Schema oracle | real OMG `XMI.xsd`, SHA-256-pinned, read from the warm cache | real Open Group 3.1 XSDs, SHA-256-pinned, read from the warm cache |

Two traps were identified and avoided:

- The ArchiMate extraction ships `agent/relationships/` and `appendix-b5/allowed-relationship-triples.*`.
  **These are not an oracle.** `agent-manifest.json` names dediren's own retired Rust rule table
  (`crates/dediren-archimate/src/relationship_rules.rs`) as their source, so validating dediren
  against them would be circular, and they are stale by an entire language rewrite.
- Appendix B.5's relationship table is copyrighted and was deliberately removed from this repo in
  2026-05-27. Nothing in this register transcribes it, in any form, including as a triple list.

## Scope

| Axis | Question | Prefix |
| --- | --- | --- |
| V | Vocabulary & metamodel coverage | `AM-VOCAB-n` / `UML-VOCAB-n` |
| S | Semantics & legality | `AM-SEM-n` / `UML-SEM-n` |
| N | Notation fidelity | `AM-NOT-n` / `UML-NOT-n` |
| I | Interchange fidelity | `AM-OEF-n` / `UML-XMI-n` |
| D | Claim accuracy in user-facing docs | `DOC-n` |

Severity is about **conformance**, not code quality:

- `block` — emitted output or accepted input contradicts the specification, or a spec-legal model is
  rejected.
- `warn` — a divergence a standards-aware consumer or an importing tool would notice.
- `info` — a documented or defensible deviation worth recording.

**Out of scope:** code changes; test-suite quality as a subject (cited only where a gate's weakness
is what lets a defect survive). Findings already carried by `docs/architecture-guidelines.md` §12 or
by the 2026-07-23 audits are **cited, not re-raised** — notably the UMLDI provisional-dialect row,
the stub-XSD default-lane row, `SD-S-1` lane divergence, and `SD-B-1`. Recorded user won't-fixes
(`ARCH-V-002`, `ARCH-L-004`) are not flagged.

## Baseline

Measured against a healthy tree, so no finding is an artifact of a broken checkout.

| Check | Result |
| --- | --- |
| `./mvnw test` (fuzz excluded — they fail only under the sandbox) | **green** |
| `RealSchemaConformanceTest`, both engines, `-Ddediren.real-schemas=true` | **green** — but **1 test** for OEF against **12** for XMI |
| ArchiMate oracle tier (`-Ddediren.archimate-oracle=…`) | **unrun** — needs a user-supplied, never-committed Appendix B.5 oracle |

That 1-vs-12 asymmetry is itself the shape of this register: the XMI lane has been audited and
gated; the OEF lane has not.

## Headline

The two notations fail in **opposite directions**, and that is the most useful thing this register
has to say.

- **ArchiMate is too permissive.** Its rule model is a declared "sound under-approximation", and the
  places it leaks are not the declared ones. Two `block` findings let plainly illegal models through
  with a clean `status: ok`.
- **UML is too restrictive.** Its rule model over-fits to what the exporter happens to support, so
  spec-legal models — including one of the repo's own committed fixtures — are rejected outright.

The vocabulary, by contrast, is in excellent shape in both notations. Several starting hypotheses
were refuted; those verified negatives are recorded because they are the cheapest thing to lose.

## AM-VOCAB — ArchiMate vocabulary and metamodel coverage

Coverage matrix: 98 rows — 60 elements, 11 relationships, 2 relationship connectors, 25 example
viewpoints. **70 supported, 3 partial, 25 absent, 0 divergent.**

| id | finding | evidence | sev | symptom | trigger |
| --- | --- | --- | --- | --- | --- |
| `AM-VOCAB-1` | **Verified negative — the element and relationship vocabulary is exact in both directions.** The spec's 60 element-defining leaf sections map 1:1 onto the 60 non-connector names, PascalCase-exact. §E.3 confirms 3.2 adds no type. Independently, the names are a *byte-exact* match to the real OEF 3.1 XSD enumerations (62/62, 11/11, zero divergence either way). | `sections-pdf-page-range/{4-3,6-5,7-5,8-6,9-5,10-9,12-3}-summary-of-*.txt`, `e-3-changes-*.txt`; `Archimate.java:14-77`; cached `archimate3_Model.xsd` | info | README's "ArchiMate® 3.2" vocabulary claim is **accurate**. | n/a |
| `AM-VOCAB-8` | **Verified negative — the 3.1→3.2 metamodel delta is reflected, not stale.** All four semantic §E.3 changes hold (device/system-software/facility/equipment re-parenting, plateau→outcome composition, material→equipment realization, plateau/gap recolouring). | `e-3-changes-*.txt`; `RelationshipLegality.java:152,109-118`; `fixtures/render-policy/archimate-svg.json:47,51` | info | None. Note (a)–(c) pass via blanket category arms rather than targeted rules. | n/a |
| `AM-VOCAB-11` | **Access, Influence and Association carry none of their spec-defined attributes — and the target format supports all of them.** No `accessType`, no influence sign/strength, no `isDirected` anywhere in `contracts/`, `schemas/`, `core/`, or the OEF emitter (grep: zero hits). The OEF 3.1 XSD *does* define `AccessTypeEnum` (Access/Read/Write/ReadWrite) and `InfluenceStrengthEnum` (`+`/`++`/`-`/`--`). | §5.2.2, §5.2.3, §5.2.4; `schemas/model.schema.json:48-58`; `OefExportEngine.java:624-635`; XSD `archimate3_Model.xsd:1127,1158,1186` | **warn** | Read-vs-write access and positive-vs-negative influence — the whole point of a motivation model — cannot be expressed or round-tripped. Everything exports as unspecified. | any motivation model with contribution signs, or an information model distinguishing read from write |
| `AM-VOCAB-12` | **Relationships between relationships are structurally impossible.** `SourceValidator` requires both endpoints to be *node* ids, but §5.2.4 permits an association between a relationship and an element, and §4.5.1/B.6 permit grouping relationships. | §5.2.4, §4.5.1, App B.6; `core/.../source/SourceValidator.java:291,302` | **warn** | The spec's own Example 10 cannot be authored; the endpoint fails as an unknown node id. | associating a contract with a flow, or grouping a set of relationships |
| `AM-VOCAB-4` | **Zero of the spec's 25 example viewpoints are modelled.** No enumeration, no per-viewpoint content restriction (§13.4), no `<viewpoints>` emission. The only surface is a required free-form string copied verbatim into `view/@viewpoint`; the XSD's `ViewpointTypeType` is a union with `xs:string`, so a typo stays schema-valid. | §13.4, App C; `schemas/oef-export-policy.schema.json:12,32`; `OefExportEngine.java:674-675`; `archimate3_View.xsd:145,263-319` | **warn** | `"viewpoint": "Layred"` exports clean and imports as an unknown viewpoint. | any OEF export |
| `AM-VOCAB-2` | **Two feature docs name an exchange-format version that does not exist.** The Open Group publishes the Model Exchange File Format at **3.1**; `README.md:295` and `docs/agent-usage.md` say so correctly, but the feature docs label the format itself 3.2. | `docs/features/exports.md:3`, `engine-runtime.md:41`, `features/README.md:5,48` vs `README.md:295` | **warn** | A standards-aware reader is sent looking for a 3.2 OEF that was never published; the two doc families contradict each other. | reading the feature docs instead of the README |
| `AM-VOCAB-5` | **Seven independent copies of the element list, zero drift today, zero test protecting it.** `Archimate.java` (62), `RelationshipLegality` (60), the conformance test (58), `SvgNodeDecorator` (62), `render-policy.schema.json` (62), the render fixture (62), `docs/agent-usage.md` (62). `elementTypes()` has only two callers, both tests, and neither pins copies 4–7. | file:line per copy as listed | **warn** | A 63rd element added to `Archimate.java` alone compiles, passes the suite, and silently renders undecorated and exports unstyled. | adding or renaming an element type |
| `AM-VOCAB-9` | **A §12.1-deprecated edge is accepted silently.** `WorkPackage -[Realization]-> Deliverable` is marked deprecated in favour of access; dediren allows it with no diagnostic. | §12.1 Figure 106; `RelationshipLegality.java:98-106` | info | Models are authored on an edge a future revision may make illegal. | any project model with work packages and deliverables |
| `AM-VOCAB-6` | **One token-naming asymmetry.** 61 decorator tokens are the exact snake_case of the element name; `Node` alone becomes `archimate_technology_node`. | §10.2.1; `SvgNodeDecorator.java:100-101`, `render-policy.schema.json:193` | info | An agent deriving the token mechanically emits `archimate_node` and is rejected by the enum. | hand-writing a render policy for a `Node` |
| `AM-VOCAB-3` | **Refuted — junction-as-two-element-types is inherited, not invented.** §5.5 models one Junction concept, but the OEF 3.1 XSD lists `AndJunction`/`OrJunction` in *both* `ElementTypeEnum` and `RelationshipConnectorEnum`. dediren mirrors the schema it emits. §5.5's behavioural rules are fully implemented. | §5.5, App A.3 (`page-124.png`); `archimate3_Model.xsd:1075,1081,1286-1299`; `Archimate.java:11-12` | info | None. Record as a deliberate interop-driven deviation. | n/a |
| `AM-VOCAB-13` | **Refuted — the 62-vs-60 count leaks nowhere.** No diagnostic, schema, README or feature doc states an element count; `agent-usage.md` lists all 62 and correctly labels the two connectors. | `Archimate.java:95-97`; `agent-usage.md:296` | info | None. | n/a |

## AM-SEM — ArchiMate semantics and legality

The rule model's charter (`RelationshipLegality.java:22-35`) declares a *sound under-approximation*:
it may accept invalid combinations, but must never reject a valid one outside a documented
five-triple carve-out. Both `block` findings below are leaks the charter does **not** cover — they
are hand-written rules and blanket short-circuits, not coarse-category slack.

| id | finding | evidence | sev | symptom | trigger |
| --- | --- | --- | --- | --- | --- |
| `AM-SEM-1` | **Cross-type specialization is accepted in both directions; ArchiMate specialization is directional.** The javadoc says "Accepted in either direction" with no spec basis. §5.4.1 role names are `specializes`/`specialized by`; §8.4.2 states a contract *is a* specialization of a business object; Figure 34 draws the arrowhead from Constraint to Requirement. | §5.4.1/§5.4.2, §8.4.2, `page-images/page-051.png`; `RelationshipLegality.java:169-174`, pinned direction-blind by `ArchimateRelationshipLegalityConformanceTest.java:245-248` | **block** | **Reproduced:** `BusinessObject -[Specialization]-> Contract` validates with `status: ok` and zero diagnostics — asserting a business object is a kind of contract. | the reversed pair, either way round |
| `AM-SEM-2` | **`UNIVERSAL = {Grouping, Location}` short-circuits every rule, and runs *before* the engine's own declared carve-out.** §B.6 grants a grouping universality only *conditionally* — the other endpoint must itself be a legal source/target for that relationship — and names location only for aggregation/composition. | §B.6 + Table 21 (`page-images/page-147.png`), §4.5.2; `RelationshipLegality.java:63,136-139`, pinned as required by test `:257-271` | **block** | **Reproduced:** `Grouping -[Access]-> BusinessProcess` validates clean. Worse, `BusinessObject -[Assignment]-> Grouping` and `Goal -[Flow]-> Location` also pass — the exact combinations `isSpecContradictedByFive` claims are always rejected. | any non-structural relationship touching a grouping or location |
| `AM-SEM-3` | **Composition/aggregation legality is decided on *category* equality, not element-type equality.** §5.1.1 permits them between two instances of the same element **type**; §B.4's notes warn that subtypes inherit relationships only within their own layer. | §5.1.1/§5.1.2, §B.4 notes; `RelationshipLegality.java:151-152` | **warn** | `BusinessActor -[Composition]-> ApplicationComponent` and `Artifact -[Composition]-> BusinessObject` pass. | any cross-layer same-category containment edge |
| `AM-SEM-4` | **Product and Plateau are folded into `COMP`, inheriting Grouping/Location's blanket powers.** §8.5.1 gives Product a *closed* list of composable concepts; §B.4 says product and plateau may only aggregate the concepts in their own metamodel fragments. Because `COMP ∈ DYNAMIC`, the leak extends to Serving/Flow/Access/Realization. | §8.5.1, §12.5, §B.4 notes; `RelationshipLegality.java:47,53-60,152` | **warn** | `Product -[Composition]-> BusinessActor`, `Product -[Access]-> DataObject`, `Product -[Realization]-> <anything>` all pass. | composing a product out of non-product concepts |
| `AM-SEM-7` | **Skipping §5.7/B.2–B.4 costs a *domain* blind spot, not false rejections.** §B.4 defines four domains and restricts crossings to named relationship types. Strategy and Implementation-&-Migration elements are classified structurally, so no rule can see a crossing. | §B.4 (p.135); `RelationshipLegality.java:176-247` | **warn** | `BusinessProcess -[Serving]-> Capability`, `Node -[Assignment]-> Capability`, `ApplicationService -[Serving]-> WorkPackage` all pass — each a crossing B.4 permits only for a different relationship type. Motivation crossings *are* covered. | Strategy or Implementation-&-Migration endpoints |
| `AM-SEM-5` | **Junction containment carve-out is bidirectional; the spec's is one-way.** §5.5.1 allows a junction to be aggregated or composed **in** a plateau/grouping/location. | §5.5.1, §5 intro; `Archimate.java:275-276` | **warn** | `AndJunction -[Composition]-> Grouping` is stripped from every junction check and never endpoint-validated. | containment drawn backwards |
| `AM-SEM-6` | **Element↔connector edges bypass endpoint legality entirely.** §5 intro restricts element↔relationship edges to aggregation/composition/association. | §5 intro, Table 21; `Archimate.java:136-138` | **warn** | `BusinessProcess -[Specialization]-> OrJunction -[Specialization]-> BusinessProcess` validates. | junction models using structural relationship types |
| `AM-SEM-9` | **`CATCH_RATE_FLOOR = 0.75` has no recorded provenance.** Introduced by `bc1b997` with no rationale; the only figure ever measured (~78%) exists solely in a sibling commit message. Zero hits across README, agent-usage, threat-model, architecture-guidelines, CLAUDE.md, plans, or the vault; **no §12 debt entry**. | `ArchimateRelationshipLegalityConformanceTest.java:285,326-331`; `bc1b997`, `e07744e` | **warn** | The only quantitative threshold on the rule model is unexplained, and the gate cannot detect erosion — a change dropping the real rate from ~78% to 75.1% still passes green. | any future edit to the rule model |
| `AM-SEM-8` | **The declared carve-out genuinely over-restricts, and its cited justification is wrong.** The class cites §5.1.3 for the Assignment rows, but §5.1.3's framework sentence explicitly sanctions behavior→passive, which `ASSIGNMENT_TARGETS[BEH] == {BEH}` rejects. | §5.1.3, §B.1; `RelationshipLegality.java:67-81,159` | info | Documented and Tier-2-pinned; the rule is defensible but the citation is not. | n/a |
| `AM-SEM-10` | **Partially refuted — the null-category branch is unreachable today, but nothing pins it.** `buildCategories()` covers all 60 non-connector types (verified programmatically). The test checks its *own* independently-declared sets, never `RelationshipLegality.CATEGORY`. | `RelationshipLegality.java:142-146`; test `:132-146` | info | Adding an element type while forgetting `buildCategories()` makes every relationship on it legal, silently and greenly. | adding an element type |
| `AM-SEM-11` | **`default -> true` is dead but fail-open.** A new relationship type added without a switch arm becomes universally legal rather than failing the build. | `RelationshipLegality.java:160` | info | — | adding a relationship type |
| `AM-SEM-12` | **Cyclic junction chains validate nothing.** `J1 → J2 → J1` satisfies both direction requirements and terminates the reachable-target walk with zero endpoint pairs checked. | §5.5.1; `Archimate.java:229-231,181-194` | info | Degenerate, but silent. | a junction cycle |

**Refuted in this lane, and worth keeping:** `Influence` having no source constraint is **correct** —
§B.4's only Influence restriction is on the target, and §5.2.5 explicitly permits a passive source.
`Association -> true` is **correct** (§5.2.4). The junction *behavioural* model matches §5.5.1 on
every point checked. Missing derivation closure causes **no** false rejections.

## AM-OEF — ArchiMate OEF interchange

Validated against the **real** SHA-256-pinned Open Group 3.1 XSDs from the warm cache, with
`xmllint` on emitter-shaped documents. The committed golden `fixtures/export/oef-basic.xml`
validates clean.

Five findings share one shape: **an input that is valid per dediren's own published JSON contract
produces a document the exchange schema rejects.** The failure is loud, not silent — no corrupt
artifact ships — but the export dies blaming its own generated XML rather than naming the cause.

| id | finding | evidence | sev | symptom | trigger |
| --- | --- | --- | --- | --- | --- |
| `AM-OEF-1` | **An empty `<relationships>` is emitted unconditionally and hard-fails the XSD.** `RelationshipsType` requires `minOccurs="1"`; `model.schema.json` sets no `minItems`, so a zero-relationship model is contract-valid. | `OefExportEngine.java:622,637`; `archimate3_Model.xsd` `RelationshipsType`; verified by hand | **block** | **Reproduced end-to-end through the CLI:** a relationship-free model — a capability map, a component inventory — cannot be exported at all. Dies with `DEDIREN_OEF_SCHEMA_INVALID: content of element 'relationships' is not complete`. | `relationships: []` |
| `AM-OEF-2` | **Same for `<elements>`.** `ElementsType` also requires `minOccurs="1"`. | `OefExportEngine.java:609,620`; `archimate3_Model.xsd` `ElementsType` | **block** | Same false rejection for a node-less model. | `nodes: []` |
| `AM-OEF-3` | **Negative coordinates hard-fail; the emitter passes them straight through.** `layout-result.schema.json` types `x`/`y` as plain `number` with no minimum; the XSD's `LocationGroup` requires `xs:nonNegativeInteger`. | `OefExportEngine.java:947`; `schemas/layout-result.schema.json`; `archimate3_Diagram.xsd` `LocationGroup`; verified by hand | **block** | A contract-valid layout-result is rejected at export. `--layout` is caller-supplied, so this is reachable through the documented public surface. | any layout with a negative `x` or `y` |
| `AM-OEF-4` | **Sub-half-pixel sizes round to 0 and hard-fail.** `width`/`height` allow `exclusiveMinimum: 0`, so `0.4` is contract-valid and rounds to `0`; the XSD requires `xs:positiveInteger`. | `schemas/layout-result.schema.json`; `archimate3_Diagram.xsd` `SizeGroup`; verified by hand | **block** | One sub-pixel node kills the whole export. | a node or group sized in `(0, 0.5)` |
| `AM-OEF-5` | **Negative bendpoint/attachment coordinates hard-fail.** Same `nonNegativeInteger` constraint on route geometry. | `OefExportEngine.java:725-744`; `archimate3_Diagram.xsd:373-375` | **block** | Edge routes leaving the positive quadrant break export even when every node is in range. | any route point with negative `x`/`y` |
| `AM-OEF-6` | **Grouping containment is discarded, and the data to express it is right there.** The XSD's `Container` declares a nested `<node>` child, documented as supporting nested diagram nodes. `LaidOutGroup.members()` exists and `writeViewBody` never reads it. | `OefExportEngine.java:678-708`; `contracts/.../LaidOutGroup.java:16`; `archimate3_Diagram.xsd:301-342` | **warn** | **Verified in emitted bytes:** the group node (785×420 at 12,30) and `web-app` (36,54) / `orders-api` (317,200), which sit geometrically inside it, are flat siblings. Both shapes validate, so a consuming tool imports them as unrelated overlapping boxes. | any view with a semantic Grouping |
| `AM-OEF-11` | **OEF has no machine-readable assurance contract while UML/XMI just gained one.** PR #72 added `UmlXmiAssurance` (163 LOC) + a 240-line schema; OEF's entire disclosure is one free-text INFO string built inline. | `UmlXmiAssurance.java`, `schemas/uml-xmi-assurance.schema.json` vs `OefExportEngine.java:808-810,836-837` | **warn** | An agent can machine-check what the XMI lane verified but must regex an English sentence for OEF — and **OEF's claim is the weaker one**. | any OEF export |
| `AM-OEF-9` | **Every property is declared `type="string"` although the XSD offers six data types** (string, boolean, currency, date, time, number). | `OefExportEngine.java:892`; `archimate3_Model.xsd:470-484` | **warn** | A boolean or numeric property round-trips as text; a consumer cannot type-filter or sort it. | any non-string property |
| `AM-OEF-8` | **Non-scalar property values are flattened to a raw JSON blob** via `value.toString()`. | `OefExportEngine.java:926-931` | **warn** | An object property lands in `<value>` as escaped JSON text — well-formed and valid, but unrecoverable on round-trip. | any object/array property value |
| `AM-OEF-10` | **No `<style>` is ever emitted**, so render-policy styling does not cross into OEF, though `StyleType` carries lineColor/fillColor/font/lineWidth. | `OefExportEngine.java:641-723`; `archimate3_Diagram.xsd:184,448-467` | **warn** | The same model renders one way as dediren SVG and another in the importing tool. Defensible, but currently an unstated stance. | any non-default render policy |
| `AM-OEF-12` | **The real-XSD lane pins exactly one happy-path fixture** — 2 elements, 1 relationship, no groups, junctions, properties or edge-case geometry. Every `block` finding above sits outside that shape. | `RealSchemaConformanceTest.java:32-53` | info | Compounds the already-registered stub-XSD row (§12). | adding any structural case |
| `AM-OEF-13` | **The 3.2-vs-3.1 gap lands in legality, not vocabulary.** The XSD types relationship endpoints as bare `xs:IDREF` with no endpoint constraint, so it can never corroborate ArchiMate legality at all. | §E.3; `archimate3_Model.xsd:600-601` | info | A 3.2-only-legal edge passes the 3.1 XSD because the XSD is endpoint-blind. | n/a |

**Refuted in this lane:** junction naming and placement are exactly right; there are no 3.2 type
names missing from the 3.1 XSD; omitting `<organizations>`, `<documentation>` and `<metadata>` is
lossless (dediren has no counterpart concept); node `<label>` omission is schema-sanctioned;
coordinate rounding is schema-*mandated* (the format has no sub-integer representation — the defect
is the missing clamp, not the rounding); element order and nesting are correct throughout; and the
3.0-namespace / 3.1-schemaLocation split is correct, because the XSDs themselves declare
`targetNamespace=".../3.0/"` with `version="3.1"`.

## AM-NOT — ArchiMate notation

Read from the page rasters (Appendix A pp. 123–125, §5.6 Table 3 pp. 47–49, §3.7–3.9 p. 26–27),
because the notation tables are images. Every finding is confirmed against **emitted SVG**.

**Net: 5 of 11 relationship types collapse into 2 visual forms; 4 are exactly right; 2 diverge
without colliding.**

| id | finding | evidence | sev | symptom | trigger |
| --- | --- | --- | --- | --- | --- |
| `AM-NOT-1` | **Serving, Triggering and Flow are byte-identical in emitted output.** The spec separates them by arrowhead *fill* and line style: Serving solid + open head; Triggering solid + **filled** head; Flow **dashed** + filled head. | A.3 `page-124.png`, Table 3 `page-048/049.png`, §5.2 prose `pages/page-040.txt`; `fixtures/render-policy/archimate-svg.json:116,120,121`; emitted `valid-pipeline-archimate/main/diagram.svg` — three edges, same `M 1 1 L 9 5 L 1 9` `fill="none"` marker, no dasharray | **block** | A reader cannot tell "provides functionality to" from "temporally triggers" from "transfers something to". | any view with two of Serving / Triggering / Flow |
| `AM-NOT-2` | **Access and Influence are identical.** §5.2 states the line styles in words: serving solid, **access dotted**, influence dashed. Both ship as `dashed` + `open_arrow`. `dotted` is already a legal value in the render-policy enum and is unused. | `pages/page-040.txt`, A.3 `page-124.png`; `archimate-svg.json:117,118`; `schemas/render-policy.schema.json` `$defs.lineStyle`; emitted rows 7–8 of the coverage sheet | **block** | A data-access edge and an impact edge are indistinguishable. | any view mixing Access and Influence |
| `AM-NOT-3` | **No ArchiMate relationship uses a filled arrowhead**, though the spec draws three that way (Assignment target, Triggering, Flow). `FILLED_ARROW` exists and works — it is used only by UML and as the product default. | A.3 `page-124.png`; `archimate-svg.json:111-121` (zero `filled_arrow`); `SvgEdgeMarkerEnd.java:7`, `EdgeMarkers.java:139-144` | **warn** | The correct glyph is one enum value away. | — |
| `AM-NOT-4` | **Assignment's target arrowhead is open, not filled.** | Table 3 `page-048.png`; `archimate-svg.json:113` | **warn** | Not a collision — the source ball keeps it unique. | any Assignment |
| `AM-NOT-5` | **Realization renders dashed; the spec draws it dotted.** | A.3 `page-124.png`; `archimate-svg.json:114`; emitted `valid-archimate-oef/main/diagram.svg` | **warn** | Still unambiguous (the hollow triangle is unique), so cosmetic rather than semantic. | any Realization |
| `AM-NOT-6` | **The ArchiMate profile contributes no notation defaults — the entire table is the policy fixture.** An incomplete policy falls back to `StyleResolver`'s generic `FILLED_ARROW` + solid. The repo's own self-model policy declares exactly one edge override. | `StyleResolver.java:37`; `docs/architecture/dediren.dediren/render-policy.json:24`; Appendix A opening, `page-123.png` | **warn** | Latent today (the self-model uses only Serving), but adding any other relationship to it silently emits non-ArchiMate arrowheads. | any partial ArchiMate render policy |
| `AM-NOT-7` | **Nesting notation (§3.8) is half-supported.** The model *can* express a semantic container of any type, but the renderer returns early unless the decorator is `ARCHIMATE_GROUPING`. | §3.8 `pages/page-026.txt`, §5.2.2 `pages/page-041.txt`; `SceneProjection.java:66-81` vs `SvgDocument.java:410-413`; `archimate-svg.json:123-130` | **warn** | Nesting a data object inside an application component draws the component as an untyped grey box — no icon, no layer colour, no square corners. Not documented as declined anywhere. | any non-Grouping semantic container |
| `AM-NOT-8` | **Influence's ± sign modifier cannot be rendered** — the only end-adornment channel is gated to the `uml` profile. | §5.2.3 `pages/page-042.txt`; `EdgeEndAdornments.java:60` | info | Optional in spec. Compounds `AM-VOCAB-11` (the data cannot be modelled either). | — |
| `AM-NOT-9` | **Access read / read-write arrow variants have no type-level expression.** | §5.2.2 `pages/page-041.txt`; `archimate-svg.json:117` | info | Reachable only per-edge; no shipped policy uses it. | — |
| `AM-NOT-10` | **Directed Association (half arrowhead) is unsupported**, and the marker vocabulary has no half-arrow — substituting `open_arrow` would make it read as Serving. | A.3 `page-124.png`; `archimate-svg.json:119` | info | — | — |
| `AM-NOT-11` | **The Service corner icon is a rounded rectangle, not the spec's pill**, and ApplicationService's differs in height and y from the other two. | A.1 `page-123.png`; `ArchimateIcons.java:804-816`; emitted `valid-archimate-junction/main/diagram.svg` | info | — | — |
| `AM-NOT-12` | **The optional per-layer letter cue is absent**, so layer identity rides on colour alone — which §3.9 says carries no formal semantics. | §3.9 `pages/page-026.txt` | info | In greyscale or under a mono policy, layers become indistinguishable. | — |

**Refuted, and important:** the 44-icon-kinds-for-62-types ratio is **not** a conflation — every
many-to-one mapping matches a glyph the spec itself reuses across layers, and no element pair the
spec distinguishes renders identically. And/Or junction differentiation is correct (filled vs hollow
circle). The §3.9 corner-shape convention is *fully* conformant — square for structure, round for
behaviour, diagonal for motivation, all three sets exactly right. Colour choices are permitted
(§3.9 assigns colour no formal semantics). `ARCH-V-001` and `ARCH-V-003` remain fixed.

## UML-VOCAB — UML vocabulary and metaclass coverage

Coverage matrix: **238 rows** across clauses 7–20 — 40 supported, 23 partial, 5 divergent, 170
absent. The 5 divergent are `VisibilityKind`, `Trigger`, `ExecutionSpecification`, `Gate`,
`DestructionOccurrenceSpecification`.

**Row-set caveat, stated because it changes how the counts read:** the row set is derived from the
extraction's TOC index, whose heading capture is incomplete. At least nine metaclasses dediren *does*
emit have no TOC heading and therefore no row — `Node`, `ExecutionEnvironment`, `Deployment`,
`Manifestation`, `CommunicationPath`, `Operation`, `Generalization`, `Constraint`, `OpaqueBehavior`.
The `supported` count is understated by roughly that much. §16 Actions contributes 63 absent rows on
its own; no diagramming tool surfaces them.

### Enumeration-literal coverage

| enumeration | spec § | literals | dediren |
| --- | --- | ---: | --- |
| `VisibilityKind` | 7.8.24.3 | 4 | **rendered 4, validated 0** |
| `ParameterDirectionKind` | 9.9.14.3 | 4 | **2** (`in`, `return`) |
| `InteractionOperatorKind` | 17.12.15.3 | 12 | **4** (`alt`/`opt`/`loop`/`par`) |
| `AggregationKind` | 9.9.1.3 | 3 | 3 |
| `PseudostateKind` | 14.5.7.3 | 10 | 10 |
| `TransitionKind` | 14.5.12.3 | 3 | 3 |
| `MessageSort` | 17.12.22.3 | 6 | 6 |
| `MessageKind` / `CallConcurrencyKind` / `ObjectNodeOrderingKind` / `ExpansionKind` / `ConnectorKind` / `ParameterEffectKind` | — | 4/3/4/3/2/4 | 0 each |

| id | finding | evidence | sev | symptom | trigger |
| --- | --- | --- | --- | --- | --- |
| `UML-VOCAB-1` | **`VisibilityKind` is unvalidated free text written verbatim into XMI.** No allow-list in `uml/`, `schemas/`, or the exporter. All 4 literals are recognised for *notation* only, by fallthrough switches whose `default -> "+"` swallows anything unknown. | §7.8.24.3; `ClassifierWriter.java:59,68,91,101`; `UmlDecorators.java:251-257` | **block** | `visibility="pubic"` exports as an invalid enumeration value while the SVG shows `+` — the two artifacts disagree on the same input and neither reports it. | any `properties.uml.*.visibility` |
| `UML-VOCAB-2` | **`ParameterDirectionKind` is 2 of 4; `out`/`inout` silently export as `in`.** `direction` is hard-coded. | §9.9.14.3; `ClassifierWriter.java:117,126` | **warn** | An out-parameter round-trips as an in-parameter — well-formed, semantically wrong. The SVG signature shows no direction at all. | any operation parameter |
| `UML-VOCAB-7` | **Component realization exports as plain `uml:Realization`, never `uml:ComponentRealization`.** | §11.6; `ComponentWriter.java:216` | **warn** | On import a Component's `realization` list is empty; the link survives only as a loose Dependency. | any `Realization` in a `uml-component` view |
| `UML-VOCAB-8` | **Two element kinds have no `SvgNodeDecorator` at all** (`CombinedFragment`, `InteractionOperand`), and `DestructionOccurrenceSpecification` is the only one of 34 whose token is not the snake_case of its kind. | `Uml.java:46-48`; `SvgNodeDecorator.java:130-197` | **warn** | A policy author cannot style a CombinedFragment or InteractionOperand at all. | authoring a render policy for either |
| `UML-VOCAB-10` | **No test asserts the three vocabulary copies agree, and the check is not even mechanically available** — `Uml.java` exposes no accessor for the seven element-kind sets. The repo already has the pattern to copy (`UmlCompartmentMetricsConsistencyTest`, `ArchimateLabelReserveConsistencyTest`). | `Uml.java:103,107`; `dist-tool/.../*ConsistencyTest.java` | **warn** | This is *how* `UML-VOCAB-8`'s two gaps and one broken name arose. | any vocabulary edit |
| `UML-VOCAB-3` | **`InteractionOperatorKind` is 4 of 12** — a bounded, honestly-diagnosed gap. Consequence: `ConsiderIgnoreFragment` is unreachable. | §17.12.15.3; `UmlSequenceValidation.java:33-34` | info | Rejected at authoring time with a clear diagnostic. | 8 of the operators |
| `UML-VOCAB-4` | **No Profile / Stereotype / Extension support anywhere.** The guillemet titles are hard-coded UML *keyword* notation for six built-ins, reaching the SVG only. | §12.3; `UmlDecorators.java:66-69,116-120` | info | Domain profiles have no expression path. | — |

## UML-SEM — UML semantics and legality

The failure direction here is the **mirror** of ArchiMate's: the rule model over-fits to what the
exporter supports, so spec-legal models are rejected. The over-permissive failures all trace to one
cause — a single `isStructuralType` predicate standing in for three different spec type systems
(`Type` for association ends, `Classifier` for generalization ends, `NamedElement` for dependency
ends). It is simultaneously **too wide** (admits `Package`, which is none of the three) and **too
narrow** (excludes Actor, UseCase, Artifact, Node — all Classifiers).

| id | finding | evidence | sev | symptom | trigger |
| --- | --- | --- | --- | --- | --- |
| `UML-SEM-13` | **Transitions must share a region with *both* endpoints, so external transitions crossing a composite-state boundary are rejected outright.** §14.5.11.8 requires only a shared `containingStateMachine()`; §14.2.3.8.5 says Transition ownership is *not* explicitly constrained. | §14.5.11.8 `pages/page-362.txt`, §14.2.3.8.5; `Uml.java:661-668` | **block** | The ordinary case in any non-trivial state machine cannot be expressed. Entry/exit-point transitions are structurally impossible — an entryPoint's `container` is empty. | any transition whose endpoints sit in different regions |
| `UML-SEM-15` | **No Pseudostate degree constraints are enforced at all.** §14.5.6.7 defines nine (`initial_vertex`, `join_vertex`, `fork_vertex`, `junction_vertex`, `choice_vertex`, `history_vertices`, …); dediren validates only that `kind` is one of the 10 literals. | §14.5.6.7 `pages/page-350/351.txt`; `Uml.java:528-540` | **block** | A fork with one outgoing edge, a join with one incoming, an initial with three outgoing, a dangling choice — all validate and export. | any wrong-degree pseudostate |
| `UML-SEM-16` | **Transition-kind semantics unenforced.** `state_is_internal`, `state_is_local`, `state_is_external`, the fork/join segment guard rules — none checked. | §14.5.11.8 `pages/page-361/362.txt`; `Uml.java:621-627` | **block** | `kind: "internal"` with different source and target validates and is emitted. | declaring a kind the endpoints don't support |
| `UML-SEM-17` | **Region cardinality unenforced** — §14.5.8.6 allows at most one initial and one each of deep/shallow history per Region. | §14.5.8.6 `pages/page-354.txt`; `Uml.java:519-526` | **block** | Two initial pseudostates in one region validate. | any duplicate |
| `UML-SEM-24` | **`no_occurrence_specifications_below` unenforced — messages arrive on a destroyed lifeline.** §17.12.6.4 requires a DestructionOccurrenceSpecification to be `last()` in its lifeline's events. | §17.12.6.4; `UmlSequenceValidation.java:140-180`; `UmlSequenceConstraints.java:288-310` | **block** | A later message targeting a destroyed lifeline validates, projects and renders — the arrow lands below the destruction cross. | any traffic after a delete-message |
| `UML-SEM-19` | **`alt` and `par` are required to have ≥ 2 operands; no such rule exists.** §17.12.3.6 gives every operator a floor of one, and the *only* arity constraint in clause 17 covers opt/loop/break/assert/neg. | §17.12.3.6 `pages/page-650.txt`, §17.12.3.7; `UmlSequenceValidation.java:744-758` | **block** | A one-operand guarded `alt` — legal, common, and semantically distinct from `opt` — is rejected. | `alt` with a single operand |
| `UML-SEM-29` | **Core and export disagree on message endpoints, and the spec sides with the core.** Core allows `Lifeline -> DestructionOccurrenceSpecification`; the exporter demands `Lifeline -> Lifeline`. | §17.12.6.3, §17.12.22.3; `Uml.java:838-842` vs `InteractionWriter.java:477-507` | **block** | **Reproduced:** `valid-uml-sequence-lifecycle.json`, a *committed fixture*, validates `ok`, renders fine, then hard-fails at export. It is the only UML fixture with no XMI golden — so the golden-iterating invariant gates structurally cannot see this. | any delete-message |
| `UML-SEM-1` | **`Association` accepts `Package` as an end.** §12.4.5.3 — Package is not a Type. | §11.8.1.6, §12.4.5.3 `pages/page-318.txt`; `Uml.java:25,426-430` | **block** | Validates, lays out, renders and exports; no UML tool can consume it. | `Association` between two Packages |
| `UML-SEM-2` | **`Generalization` accepts `Package -> Package`.** §9.9.7.5 requires both ends to be Classifiers. | §9.9.7.5 `pages/page-181.txt`; `Uml.java:426-430` | **block** | Package-to-package inheritance is accepted and emitted. | — |
| `UML-SEM-4` | **The Association rule contradicts the spec's own Actor rule in both directions.** §18.2.1.4 says an Actor may associate with UseCases, **Components and Classes**. | §18.2.1.4, §18.1.4; `Uml.java:426-430,826-829` | **block** | `Association: Actor -> Class` is named as legal by the spec and rejected here. | an actor associated with the class it drives |
| `UML-SEM-5` | **`Usage` source restricted to `Component`/`Port` with no spec basis** — §7.8.23.3 gives Usage no constraints beyond Dependency's `NamedElement`. | §7.8.23.3, §7.8.4.5; `Uml.java:441-442,810-812` | **block** | The canonical `«use»` dependency from a Class to an Interface is rejected. | `Usage: Class -> Interface` |
| `UML-SEM-8` | **`UseCase.subject` is narrower than `Classifier` and single-valued;** §18.2.5.4 gives `subject : Classifier [0..*]`, and the spec's own Figure 18.2 uses a **Component** as the subject. | §18.2.5.4, §18.1.4; `Uml.java:542-556,831-836` | **block** | The exact subject-boundary construct the spec illustrates is rejected. | a Component subject |
| `UML-SEM-3` | **`Generalization` rejects spec-legal Classifier pairs** — Actor, UseCase, Artifact, Node are all Classifiers. | §18.2.1.3, §9.9.4.4, §19.5.10.3; `Uml.java:426-428,752-754` | **warn** | Actor-to-Actor and UseCase-to-UseCase generalization — canonical use-case content — is rejected. Only the use-case case is documented as deferred. | — |
| `UML-SEM-22`, `UML-SEM-23` | **Two Dediren-local house rules are enforced as UML legality.** Nested-fragment coverage containment has no spec counterpart (`covered` is a free `[0..*]`), and contiguity contradicts UML's *partial*-order model — `critical` exists precisely because ordinary fragments are not protected from interleaving. | §17.12.13.5, §17.1.3, §17.6.3.14; `UmlSequenceValidation.java:563-596,362-397` | **warn** | Spec-legal interleavings are rejected. Both are documented as house rules in `agent-usage.md`. | — |
| `UML-SEM-12` | **Multiplicity bounds are restricted to integer literals.** §7.5.4.1's grammar makes both bounds ValueSpecifications — the `value_specification_constant` constraint exists *because* non-literals are permitted. | §7.5.4.1 `pages/page-077.txt`, §7.8.8.8; `Uml.java:724-737` | **warn** | `1..n`, `0..maxSize`, `2..size` are rejected. Undocumented — the grammar appears in no schema and nowhere in `agent-usage.md`. | a symbolic bound |
| `UML-SEM-9`, `-10`, `-25`, `-26`, `-31` | Gate is an inert vocabulary entry that can never connect to anything; Extend's `extensionLocation [1..*]{ordered}` is optional and single-valued; ExecutionSpecification's `same_lifeline` is unenforced; fragment-ownership uniqueness is scoped per-interaction rather than globally; Actor/ExtensionPoint `must_have_name` is unenforced. | §17.12.19, §18.2.2.4, §17.12.8.6, §17.12.13.5, §18.2.1.4 | **warn** | — | — |
| `UML-SEM-11` | **Refuted — accepting `0..0` and bare `0` is spec-correct.** §7.5.3.2 explicitly permits a multiplicity with both bounds zero, and §7.8.8.8's six constraints include no positive-upper-bound rule. The `maxint_positive` constraint that exists belongs to `InteractionConstraint`, a different metaclass. | §7.5.3.2, §7.8.8.8, §17.12.12.5 | info | **Do not "fix" this.** | n/a |

**Also refuted:** `CommunicationPath`, `Deployment`, `Include`/`Extend` endpoint rules and the
Extend extension-point ownership rule all match the spec exactly. `FinalState` outgoing-transition
prohibition is enforced structurally. Message sorts are exactly the six `MessageSort` literals.
There is **no** XOR constraint on `enclosingInteraction`/`enclosingOperand`, and **no** constraint
forcing `consider`/`ignore` to a single operand — nothing missing there.

## UML-NOT — UML notation

UML notation *is* text-searchable, so these are precisely citable. Confirmed against emitted SVG.

| id | finding | evidence | sev | symptom | trigger |
| --- | --- | --- | --- | --- | --- |
| `UML-NOT-1` | **Six distinct edge kinds render as one identical glyph, because the renderer has no edge-keyword surface at all.** The spec requires «use» (§7.7.4), «include»/«extend» (§18.1.4), «manifest» (§19.3.4), «deploy» (§19.2.4). Only six guillemet literals exist repo-wide and all are node-level. | §7.7.4, §18.1.4, §19.2.4, §19.3.4; `UmlDecorators.java:67-69,116-120`; `fixtures/render-policy/uml-svg.json`; emitted `valid-uml-component-basic/component-view/diagram.svg` — a Usage and a Dependency are byte-identical | **block** | A reader cannot tell Usage from Dependency from Include from Extend from Deployment from Manifestation. The `include`/`uses` strings visible in the SVGs are the *author's free-text label*, not rendered keywords. Aggravating: §11.6.4 permits the expandable-interface-rectangle form dediren uses **only if** the arrows carry their keywords. | any view mixing two of the six — two shipped fixtures already do |
| `UML-NOT-5` | **Any unrecognised `visibility` string silently renders as `+` (public).** §9.5.4/§9.6.4 fix the four symbols. | §9.5.4, §9.6.4; `UmlDecorators.java:251-258`; `Uml.java` (no validation) | **block** | **Probed:** attributes set to `"Private"`, `"PRIVATE"`, `"protected "` built with `status: ok`, zero diagnostics, and rendered `+`. A model asserting private renders as public. | any casing or whitespace slip |
| `UML-NOT-2` | **`createMessage` renders solid, indistinguishable from an asynchronous message.** §17.4.4 requires a dashed line with an open arrowhead. | §17.4.4 `pages/page-619.txt`; `UmlSequenceRenderer.java:641-650`; emitted `valid-uml-sequence-lifecycle/sequence-view/diagram.svg` — no `stroke-dasharray` | **block** | Object creation reads as an asynchronous signal. | `message_sort: "createMessage"` |
| `UML-NOT-3` | **`junction` pseudostate is drawn with `choice`'s diamond.** §14.2.4.6: junction is a small filled circle; choice is a diamond. | §14.2.4.6; `UmlShapes.java:275` | **block** | A static junction is drawn as a dynamic choice point. | `kind: "junction"` |
| `UML-NOT-4` | **`terminate` is drawn with `exitPoint`'s circled X.** §14.2.4.6: exit point is a circle with a cross on the border; terminate is a bare cross. | §14.2.4.6; `UmlShapes.java:280` | **block** | "The state machine halts" is drawn as "control leaves through this exit point" — opposite semantics. | `kind: "terminate"` |
| `UML-NOT-6` | **`Activity` and `Action` render as the same rounded rectangle at the same size.** §15.2.4 makes an Activity a *frame* around its nodes; §16.2.4.1 makes an Action a round-cornered box. | §15.2.4, §16.2.4.1; `UmlShapes.java:44-45`; `uml-svg.json` (`rx: 12` for both); emitted `valid-uml-complex/complex-activity-view/diagram.svg` — both 160×80 | **block** | The containing behaviour reads as one more step in its own flow. | any activity view with an Activity node — the shipped complex fixture |
| `UML-NOT-7` | **Package name is drawn twice**, once by the decorator and once by the generic label path; the tab label also lands below the tab. | §12.2.4; `UmlDecorators.java:34-42`, `NodeShapeSupport.java:44-50`; emitted `valid-uml-basic/class-view/diagram.svg` — two "Orders" texts | **warn** | Reads as name-plus-stereotype. | any Package node |
| `UML-NOT-8` | **`DeploymentSpecification` uses the Artifact dog-ear** rather than §19.2.4's classifier rectangle. | §19.2.4; `UmlShapes.java:39-40` | **warn** | Only the keyword text distinguishes it from an Artifact. | — |
| `UML-NOT-9`, `-10`, `-11` | `entryPoint` drawn as a circled letter "E" (spec: named circle, and the recorded label won't-fix means the name never appears); «device»/«executionEnvironment» keyword straddles the 3-D fold line; the interaction frame pentagon omits the `sd` tag. | §14.2.4.6, §19.4.4, §17.2.4.1; `UmlShapes.java:279`, `UmlDecorators.java:72-80`, `UmlSequenceRenderer.java:312-318` | **warn** | — | — |
| `UML-NOT-12` | **Hollow markers and the final-state ring hardcode `#ffffff`**, so a dark UML policy inverts hollow-vs-filled. | §11.5.4, §14.2.4.5; `EdgeMarkers.java:86`, `UmlShapes.java:256,354` | **warn** | Shared aggregation would read as composition. Latent — no dark UML policy ships. | a dark UML render policy |
| `UML-NOT-14`, `-15`, `-16`, `-17` | Classifier names are not boldface while edge labels are (§9.2.4.1 requires the opposite emphasis); `deleteMessage` has no arrowhead (permitted by omission, not statement); abstract-classifier italics, GeneralizationSets and FlowFinalNode are unauthorable; navigability/end-ownership adornments are unmodellable (optional in §11.5.4). | §9.2.4.1, §17.4.4, §9.7.4, §15.3.4.1, §11.5.4 | info | Coverage gaps, not mis-renders. | — |

**Refuted:** aggregation and composition diamond **end placement is correct** — §11.5.4 puts the
diamond opposite the end carrying `aggregation`, and both the XMI and the SVG do exactly that. All
six guillemet keywords are *verbatim* spec keywords for the metaclasses they decorate. Ball-and-socket
absence is not a defect (§11.6.4 blesses the alternative dediren uses). Multiplicity and role
placement, InterfaceRealization's glyph, enumeration literal compartments, the use-case subject
boundary, the component icon, and CombinedFragment chrome are all conformant.

## UML-XMI — XMI and UMLDI interchange

Validated against the **real** SHA-256-pinned OMG `XMI.xsd`.

### The headline measurement

`xmi:XMI`'s content model is `<xsd:any processContents="strict"/>`. `uml:Model` has no declaration,
so the validator errors once and **skips the entire subtree**. Measured across all 11 goldens:
**11 of 449 elements (2.4%) are schema-checked — all of them the root — and 0 content-bearing
attributes.** `xmi:id` is `xsd:ID` and `xmi:idref` is `xsd:IDREF` in the schema, but since no element
carrying them is ever validated, the schema enforces neither uniqueness nor reference resolution.
(`SchemaValidation` compensates for uniqueness in-engine; nothing compensates for reference
resolution at runtime.)

| id | finding | evidence | sev | symptom | trigger |
| --- | --- | --- | --- | --- | --- |
| `UML-XMI-12` | **`umldi:UMLDiagram` is an abstract class.** UML 2.5.1 **Annex B is normative** and *is* the UMLDI metamodel — it was assumed out-of-corpus and is not. Concrete kinds exist (`UMLClassDiagram [Class]`). | `full.txt:53929` (`UMLDiagram [Abstract Class]`), `:53711` (`UMLClassDiagram [Class]`); `DiagramWriter.java:63` | **block** | A conforming importer cannot instantiate it; the class-family lane should emit `umldi:UMLClassDiagram`. **Spec self-conflict flagged honestly:** B.2.2 prose says the specialization exists "to make UMLDiagram concrete", contradicting B.7.13's formal catalogue. | every whole-model aggregate export |
| `UML-XMI-13` | **Every emitted diagram violates the `no-frame-no-heading` invariant.** `isFrame` defaults true, so a `heading : UMLLabel` is mandatory; none is ever emitted. | `full.txt:53981-53983` (`inv: (isFrame = false) = (heading->isEmpty())`); `DiagramWriter.java:63-68` | **block** | Fails a normative Annex B invariant. Fix is either emit a heading or set `isFrame="false"`. | every aggregate export |
| `UML-XMI-6` | **`uml:Trigger` is emitted without its mandatory `[1..1]` event.** | §13.4.11.4; `StateMachineWriter.java:119-126`; golden `uml-state-machine-basic.xmi` | **block** | Violates a mandatory association end; a validating importer must reject or repair. Deliberate and reasoned in-code — documentation mitigates but does not make the bytes conformant. | any triggered transition |
| `UML-XMI-21` | **Every emitted `uml:Port` omits `aggregation`, violating `port_aggregation`.** The spec constraint is explicit: "Port.aggregation must be composite." The existing `M-PORT` invariant correctly checks the *derived* properties (`provided`/`required`/`type`) and is simply silent on this one. | `full.txt:19832-19834`; `ComponentWriter.java:80-89`; golden `uml-component-basic.xmi` — both Ports carry no `aggregation`; `XmiMetamodelInvariantsTest.java:128-146` | **block** | Every component export ships Ports that fail a §11.8 invariant. Two-token fix. | any component view with a Port |
| `UML-XMI-14` | **The DD namespaces are a major version behind the normative reference.** UML 2.5.1 normatively cites DD **1.1**; the pinned `20100524` datestamp is DD 1.0 vintage and has **zero** occurrences anywhere in the corpus. | `full.txt:~3954`, Annex B.1 `:50540`; `DiagramWriter.java:35-36` | **warn** | An importer keyed to the DD 1.1 namespace will not recognise the geometry elements. | every aggregate export |
| `UML-XMI-16` | **The assurance contract never observes the actual validation outcome.** `level` and `status` are unconditional literals; the outcome is computed but never passed to the builder. | `UmlXmiAssurance.java:89,91`; `XmiExportEngine.java:97,115,165,183`; `SchemaValidation.java:139-146` | **warn** | `status="validated"` is emitted for the tolerated-gap path where the schema *rejected* the content; a user supplying a real UML schema gets the same "xmi-envelope-only" and so is **under**-credited; the schema's "not-validated" enum value is unreachable. | every export |
| `UML-XMI-17` | **The coverage partition is id-presence only**, so it cannot see metaclass errors or attribute loss. | `XmiBuilder.java:90-92`; `Coverage.java:62-111` | **warn** | The wrong-metaclass Realization of `UML-XMI-5` counts as "represented"; all attribute-level loss is invisible; hard failures never reach Coverage at all, so the partition describes only *successful* exports. | every export |
| `UML-XMI-18` | **The artifact carries no in-band provenance, and the two facts most likely to break an importer are unclaimed.** `XMI.xsd` declares `xmi:Documentation` with `exporter`/`exporterVersion`/`timestamp` — a schema-**valid** slot the engine never fills. | `XMI.xsd:34,40-54`; `XmiBuilder.openRoot():205-216`; `schemas/uml-xmi-assurance.schema.json` | **warn** | Hand the `.xmi` to a colleague and every assurance qualifier is lost — the document asserts nothing about what produced it. This is the cheapest high-value fix in the lane and one of the few things the pinned XSD would actually verify. | every export |
| `UML-XMI-5` | **`Class -> Interface` Realization emits `uml:Realization`, not `uml:InterfaceRealization`** (§10.5.6). `ComponentWriter` emits the correct nested form for the identical source pattern. | §10.5.6.1/.4; `ClassRelationshipWriter.java:52-53,169-186`; golden `uml-complex-class.xmi` | **warn** | An importer shows a generic dependency, not interface implementation; codegen produces no `implements`. | any class→interface realization |
| `UML-XMI-10` | **Every emitted Association is non-navigable in *both* directions.** `navigableOwnedEnd` appears zero times in the emitter and zero times across all 11 goldens; both ends are Association-owned, so `isNavigable()` is false for both. | §11.5.3 `pages/page-261.txt`, §9.5.3 `pages/page-193.txt`; `ClassRelationshipWriter.java:144-167` | **warn** | This is not "navigability unmodelled" — it is an active assertion of non-navigability. Codegen tools emit no accessors in either direction. | every association |
| `UML-XMI-1` | **The "schema-validated" lane verifies one element per document.** | `XMI.xsd:26-27`; measured 11/449 | **warn** | The assurance reports `status="validated"` for a document of which 97.6% was never examined. | any export |
| `UML-XMI-2` | **The tolerated-gap filter keys on Xerces message wording, and fails *closed*.** An unrecognised wording makes the whole check false and throws. | `SchemaValidation.java:239-263,147-149` | **warn** | Safe direction, but a JDK upgrade that rewords `cvc-complex-type.2.4.c` bricks 100% of UML/XMI exports. No test pins the wording, so it would surface as mass export failure, not a test failure. | a JDK/Xerces upgrade |
| `UML-XMI-9` | **Three source kinds legal in the model and drawn in SVG are hard export errors.** Sharper: `InteractionWriter` itself *emits* `uml:DestructionOccurrenceSpecification` as a delete-message's derived receive event — the same metaclass it refuses as a source node. | `Uml.java:40-48`; `InteractionWriter.java:399-401,503-506` | **warn** | An activation bar is ordinary sequence content; including one makes the **entire** export fail rather than degrade. | any activation bar, gate, or explicit destruction |
| `UML-XMI-20` | **No committed golden exercises UMLDI or DestructionOccurrenceSpecification** — the two least-verified surfaces have no byte-level evidence, which is precisely where `UML-XMI-12/13/14` were hiding. | 0 of 11 goldens declare `xmlns:umldi`; UMLDI exists only transiently in `RealSchemaConformanceTest` | **warn** | — | — |
| `UML-XMI-4` | **The `xmi:version` ban is XSD-correct but unconditional**, and the emitted document carries no version marker at all. | `XMI.xsd:25-33` (no `version` attribute, no `anyAttribute`); probe error `cvc-complex-type.3.2.2`; `SchemaValidation.java:89-93` | **warn** | Consumers branching on `xmi:version` get nothing, with no policy escape hatch. Note the design asymmetry: the XSD is treated as authoritative where it *forbids* and irrelevant where it *cannot verify*. | any importer keying on `xmi:version` |
| `UML-XMI-3` | **Two of the four tolerated prefixes and one of the two matched wordings are dead code** — `di:`/`dc:` sit inside an already-skipped subtree and never reach the gap; `cvc-elt.1.a` fires only for an undeclared root, which an earlier gate already rejects. | `SchemaValidation.java:231-232,255-262,84-88` | info | **Precision refinement** to §12 row 676 and `agent-usage.md:271-273`, which describe `di:`/`dc:` as "riding the tolerated gap". Net outcome identical (unvalidated); the stated mechanism is inaccurate. | n/a |
| `UML-XMI-15` | **Not locally decidable: `di:waypoint` / `dc:Bounds` element naming.** "waypoint" has zero corpus hits; the capitalised type "Bounds" appears nowhere, though the lowercase *property* `bounds` is attested. Geometry is inherited from DD, which UML 2.5.1 does not reproduce, and no DD/DI/DC schema exists locally. | `full.txt:50631,50635-50636`; Annex E.1 `:56110` | info | The corpus is **silent, not contradicting**. Two observations needing no external spec: the dialect mixes a type-named element (`dc:Bounds`) with a property-named one (`di:waypoint`) — one convention must be wrong; and the corpus attests lowercase `bounds`. Closing this needs DD 1.1 + the MOF 2 XMI Mapping locally. | n/a |

**Refuted:** aggregation end placement is correct (§11.5.4 — diamond opposite the marked end,
verified in both golden and SVG). Referential integrity holds — 449 elements, 428 ids, **0**
unresolved references across all goldens. Unqualified element names are correct XMI practice.
`xmi:type` is present exactly where the property type is abstract and omitted where determinate.
`memberEnd` arity 2 satisfies §11.5. `umldi:UMLShape`/`umldi:UMLEdge` are both concrete and correctly
named — only `UMLDiagram` is abstract. AssociationClass, n-ary associations, the Property/Operation
modifiers, Comments, PackageImport, Signal/Reception, InstanceSpecification and Collaboration are
**source-model** limits, not exporter drops — a correctly-scoped capability boundary.

## DOC — claim accuracy

Measured against what the eight lanes established. This axis matters more than usual here, because
`docs/agent-usage.md` **ships inside the bundle** and is the only document an agent consumer sees.

| id | finding | evidence | sev | symptom | trigger |
| --- | --- | --- | --- | --- | --- |
| `DOC-12` | **"It emits conformant UML 2.5.1 abstract syntax" is false on at least four independent counts** — wrong metaclass for interface and component realization, `uml:Trigger` without its mandatory event, Ports without `port_aggregation`, and an abstract `umldi:UMLDiagram`. Reinforced at `:345` ("Class content is canonical UML 2.5.1"). | `docs/agent-usage.md:324-326,345`; `UML-XMI-5/6/12/21`, `UML-VOCAB-7` | **block** | Any agent deciding whether dediren XMI is fit for handoff to a conformance-checking importer is misled. | — |
| `DOC-16` | **UML `visibility` is undocumented, unschema'd, and makes SVG and XMI disagree.** **Verified: zero occurrences** across `README.md`, `docs/agent-usage.md`, `docs/features/` *and* `schemas/` — yet it is a live authored field used by shipped fixtures and written verbatim into XMI. | grep = 0 hits; `fixtures/source/valid-uml-component-basic.json:76,93,111`; `ClassifierWriter.java:59-68`; `UmlDecorators.java:251-257` | **block** | `"visibility": "protected "` renders `+` in the SVG while the XMI carries the typo — two artifacts, one model, no diagnostic. The whole `properties.uml.attributes[]`/`operations[]` surface is absent from the shipped guide. | authoring any class attribute or operation |
| `DOC-22` | **The bundle ships a `valid-`-prefixed fixture that cannot be exported.** **Verified present** at `dist/dediren-agent-bundle-2026.08.2/fixtures/source/valid-uml-sequence-lifecycle.json`, and reachable as an MCP resource. `docs/features/source-model.md:130-132` lists the sequence fixtures and omits it. | `DistTool.java:257,1092-1108`; `docs/agent-usage.md:70-71,97-108`; `UML-SEM-29` | **block** | The guide directs agents to fixtures as the examples; an agent picking the richest sequence template gets a model that validates, renders, and dies at export. | asking `dediren://fixture/source/` for a sequence template |
| `DOC-9` | **Grouping containment is silently flattened, in a doc family that explicitly promises omissions are never silent.** | `docs/features/exports.md:99-105` ("declared — **not silently dropped**"), `agent-usage.md:224-227`; `AM-OEF-6` | **block** | The imported model has lost its grouping structure with nothing in `.diagnostics[]`. The omission diagnostics operate at element/relationship granularity only. | any ArchiMate view using Grouping |
| `DOC-7` | **Five input classes hard-fail export while valid under dediren's own schemas — and the guide calls those codes self-repairing.** Three of the five (negative coordinates, sub-pixel sizes, negative bendpoints) are produced by **dediren's own layout engine**, so the failure is internal to the pipeline and unrepairable by editing source JSON. | `schemas/model.schema.json` (no `minItems`), `layout-result.schema.json`; `docs/agent-usage.md:1163-1171`; `AM-OEF-1..5` | **block** | `validate` ok → `layout` ok → `validate-layout` ok → `export` dies on the last stage with an undocumented code. | any ArchiMate view whose ELK layout places a node at a negative coordinate |
| `DOC-20` | **Six UML edge kinds render identically, against an explicit claim of "specification-mandated shapes and icons".** | `docs/features/svg-render.md:44-46`, `agent-usage.md:611-612`; `UML-NOT-1` | **block** | Silent: the diagram is produced, looks correct, and cannot be read back to the model. An Include and an Extend between the same two use cases are indistinguishable. | any use-case, component or deployment view |
| `DOC-19` | **"Provisional" materially understates UMLDI.** "Not yet verified against a real importer" describes an open question; the reality is a definite violation of a normative annex. The "no normative OMG DI XSD" phrasing — literally true about schema *files* — frames a spec-contradicting choice as an unavoidable gap. | `docs/agent-usage.md:269-272`, `docs/features/exports.md:141-142`; `UML-XMI-12/13/14` | **block** | `model.uml.xml` is invalid UML regardless of which importer reads it. | any `uml-class`/`uml-data` XMI build |
| `DOC-14` | **`external` is advertised as a supported transition kind, but the case that defines it is rejected.** The deferred list names "orthogonal multi-region internals" and not cross-boundary transitions. | `docs/agent-usage.md:748-751`; `UML-SEM-13` | **block** | A legal hierarchical state machine is rejected and the guide gives no reason why. | any composite state with a transition leaving it |
| `DOC-1` | **The exchange-format version is stated as 3.2 in five places and 3.1 in three — and `README.md` contradicts itself.** `README.md:7` makes the bare "ArchiMate® 3.2 OEF XML" claim; `README.md:295-296` correctly says 3.1. | `README.md:7` vs `:295-296`; `docs/features/exports.md:3`, `engine-runtime.md:41`, `features/README.md:5,48`; `agent-usage.md:243,1054` | **block** | A reader stages "3.2 OEF XSDs", finds none, and cannot tell whether dediren or The Open Group is at fault. | reading any entry point but `README.md:295` |
| `DOC-25` | **README's "schema-governed per-kind scope, validation, and coverage assurance" overstates the middle noun.** "Schema-governed" and "coverage" are accurate and genuinely computed; **"validation" is a hard-coded constant**, backing 2.4% element coverage and zero content-bearing attributes. | `README.md:297-300`; `UML-XMI-16` | **block** | The reader is told the result reports what was validated; it reports a constant. | — |
| `DOC-26` | **The assurance schema advertises a two-valued discriminator the producer never varies.** The `allOf` conditional guards that prevent exactly this failure exist one rung up, for the stronger levels, and are absent at the rung actually in use. | `schemas/uml-xmi-assurance.schema.json:67,100-114`; `UML-XMI-16` | **block** | A consumer branching on `status == "validated"` is reading a constant, and would read `"validated"` even where validation did not establish it. | — |
| `DOC-13` | **"components with typed ports and interface realizations" names a metaclass that is not emitted.** `docs/features/exports.md:186-188` is more careful — the two docs disagree. | `docs/agent-usage.md:330-331`; `UML-VOCAB-7` | **block** | — | any component realization |
| `DOC-3`, `DOC-4` | **The ArchiMate under-approximation disclosure attributes the permissiveness to the wrong cause.** It says the residue follows from not computing the derivation closure; the two `block` leaks are rule gaps, not closure effects. And "it never rejects a valid combination except a deliberately-rejected §5-contradicted set" sits directly beside "Grouping/Location connect to anything" — jointly implying the carve-out is universally enforced when the short-circuit bypasses it. | `docs/agent-usage.md:301-311`; `AM-SEM-1/2` | **warn** | An author reads "some invalid pairs still pass" as a narrow remainder and trusts a green validate. | any reversed Specialization, or Grouping/Location as an endpoint |
| `DOC-5` | **The 25%-miss design point appears in no user-facing document.** The nearest statement is "some invalid pairs still pass". | zero hits outside the test; `AM-SEM-9` | **warn** | A model `validate` calls `ok` is shipped as spec-legal and rejected by Archi/EA. | relying on `dediren validate` as the legality gate — which `pipeline-and-commands.md:66-68` invites |
| `DOC-6` | **`docs/features/` presents ArchiMate semantic validation as unqualified**, with none of `agent-usage.md`'s caveat — the human-facing family overstates a gate the agent-facing family qualifies. | `docs/features/pipeline-and-commands.md:66-68`, `source-model.md:105-106` | **warn** | — | — |
| `DOC-8` | **"The machine contract is the JSON in `schemas/`" is falsified by `DOC-7`** — schema-validity is necessary but demonstrably not sufficient for the export lanes. | `README.md:302-304` | **warn** | — | — |
| `DOC-23` | **The UML/XMI export contract is filed under `## ArchiMate Handoff`, so `dediren_guide` misdelivers it** — and `uml-class`/`uml-data`, the only family with UMLDI output, has no topic at all. | `docs/agent-usage.md:274,324-357`; `GuideCatalog.java:43,49-53` | **warn** | An agent requesting `topic: "archimate"` receives UML prose; an agent building the class family cannot find the export contract by topic name. | any MCP-driven agent using `dediren_guide` |
| `DOC-10`, `DOC-11`, `DOC-15`, `DOC-17`, `DOC-21` | Access/Influence/Association attribute gaps undisclosed; `viewpoint` is a *required* free string with no vocabulary and no statement that any string is accepted; pseudostate/transition-kind/region under-validation undisclosed; `ParameterDirectionKind` hard-coding undisclosed; "a message's line style stays notation-driven" names only the case that works (`reply`) and fails for `createMessage`. | as cited in each lane | **warn** | — | — |
| `DOC-24` | `schemas/uml-xmi-assurance.schema.json` is missing from the page that claims to map the public schema surface (along with `package`, `diff-result`, `query-result`, `verify-result`, `status-result`). | `docs/features/contracts-and-schemas.md:15-30` | info | — | — |

### Verified accurate — recorded because honest disclosure is a result

- **The ArchiMate 3.2 *vocabulary* claims are correct** and must not be swept up in the `DOC-1` fix.
  Only the *format* noun phrase is wrong.
- **`README.md:295-296` states the 3.1-XSD / 3.2-vocabulary split exactly right, including why**, and
  `docs/features/exports.md:84-91` correctly explains the diagram-bearing-XSD choice.
- **The `xmi-envelope-only` disclosure is careful, unhedged and correctly scoped** — it directly
  anticipates the misreading ("treat it literally: empty UML metamodel/importer evidence means no
  such stronger validation was performed"). It is undermined only by `DOC-25`/`DOC-26` and by the
  "conformant abstract syntax" claim 70 lines earlier.
- **UMLDI's class-family restriction is consistent across all three surfaces** — guide, feature doc,
  and assurance schema, plus all five per-family "Deferred: … UMLDI" lines.
- **`InteractionOperatorKind` 4-of-12 is disclosed honestly** — the supported set is enumerated and
  enforced as a closed allow-set, so an unmentioned operator is rejected, not silently dropped.
- **The absence of an OEF assurance object is stated as a contract**, not hidden.
- **Element-level omission diagnostics do what they promise.** The element/relationship-level promise
  is kept; only attribute-level and containment-level losses escape it.

### The bundle-local reader

`docs/agent-usage.md` does not warn its only reader about any of the hard-fail input shapes, and its
repeated "declared rather than dropping them silently" framing actively creates a false completeness
impression — those diagnostics operate at element granularity only. An agent that reads
`.diagnostics[]`, finds no omission entry, and concludes the export was lossless is wrong.

**The single highest-value doc remediation** is an explicit "what is lost without a diagnostic"
subsection covering Grouping containment, Access/Influence/Association attributes, `visibility`, and
parameter direction — because the guide's current structure implies that list is empty.

## Suggested groupings

Re-cut by remediation cost rather than severity, following the Group 1 / Group 2 shape of
`2026-07-28-audit-remediation.md`. Nothing here is a commitment; it is the cheapest ordering.

**Group 1 — data-only, no code change.** Edit the shipped ArchiMate render policy: `Triggering`
→ filled arrowhead; `Flow` → dashed + filled; `Access` → dotted; `Realization` → dotted;
`Assignment` target → filled. Closes `AM-NOT-1`, `-2`, `-4`, `-5` and most of `-3` — five of eleven
relationship types stop colliding, with zero risk to any lane. The marker and line-style values all
already exist in the enums.

**Group 2 — one-line or two-token code fixes with an obvious correct answer.** `createMessage` →
dashed (`UML-NOT-2`); `junction` → filled circle, `terminate` → bare cross (`UML-NOT-3/4`); emit
`aggregation="composite"` on Ports (`UML-XMI-21`); emit `umldi:UMLClassDiagram` and either a heading
`UMLLabel` or `isFrame="false"` (`UML-XMI-12/13`); emit `xmi:Documentation`/`exporter`
(`UML-XMI-18`); pass the real validation outcome into the assurance builder (`UML-XMI-16`,
`DOC-25/26`); drop the duplicate Package label (`UML-NOT-7`).

**Group 3 — documentation only.** `DOC-1` (one noun phrase in five places, being careful not to
touch the correct vocabulary claims), `DOC-12`, `DOC-13`, `DOC-19`, `DOC-25`, plus the "what is lost
without a diagnostic" subsection and the `GuideCatalog` topic fix (`DOC-23`). Cheap, and it converts
several `block` findings into accurate statements without touching behaviour.

**Group 4 — clamp-and-guard.** The five OEF hard-fail classes (`AM-OEF-1..5`). Each needs a decision
about *where* to enforce: clamp in the emitter, tighten `layout-result.schema.json`, or emit a
targeted diagnostic naming the cause. Three of the five originate in dediren's own layout output, so
the emitter is the natural place.

**Group 5 — legality-model work, each needing a design decision and a test change.** The two
ArchiMate `block` leaks (`AM-SEM-1/2`) are both *pinned as required behaviour* by the conformance
test, so fixing either means editing that test — tdd-policy's RED step is naturally available. On the
UML side, splitting the single `isStructuralType` predicate into the spec's three type systems
(`Type` / `Classifier` / `NamedElement`) closes `UML-SEM-1` through `-7` at once. The state-machine
cluster (`UML-SEM-13`, `-15`, `-16`, `-17`) is the largest single body of work and the one that most
changes what users can model.

**Group 6 — needs external material.** `UML-XMI-15` cannot be decided without DD 1.1 and the MOF 2
XMI Mapping specification locally. `AM-SEM-9`'s catch-rate floor needs a decision about provenance,
not a code change.

## What this register does not establish

- **The ArchiMate oracle tier never ran** — it needs a user-supplied Appendix B.5 oracle file that is
  deliberately never committed. Every ArchiMate legality finding here is derived from §4/§5/B.1–B.4
  prose and the generic metamodel, not from the relationship tables.
- **No importer was driven.** No Archi, Papyrus, or Sparx EA import was attempted. The UMLDI findings
  are spec-derived, which is precisely why they are actionable *before* the §12 row 676 probe: the
  dialect is wrong against a normative annex regardless of what any tool does with it.
- **Nothing here re-audits test quality.** Where a gate's weakness explains why a defect survived, it
  is cited as mechanism, not raised as a finding.


---

# Appendix A — ArchiMate 3.2 coverage matrix (98 rows)

## Status counts

| Row family | Rows | supported | partial | absent | divergent |
| --- | ---: | ---: | ---: | ---: | ---: |
| Elements (S4.5, S6-S10, S12) | 60 | 60 | 0 | 0 | 0 |
| Relationships (S5.1-S5.4) | 11 | 8 | 3 | 0 | 0 |
| Relationship connectors (S5.5) | 2 | 2 | 0 | 0 | 0 |
| Example viewpoints (App C leaves) | 25 | 0 | 0 | 25 | 0 |
| **Total** | **98** | **70** | **3** | **25** | **0** |

No row is `divergent`. Every accepted type name matches the spec term exactly, and the
one structural divergence - junction modelled as two types rather than one Junction
concept - is scored `supported` because it reproduces the Open Group exchange schema the
product emits (AM-VOCAB-3).

## 1. Elements - 60 rows, all `supported`

Layer per S3.4 (Core Framework) / S3.5 (Full Framework); aspect per S4 (Generic Metamodel)
and each layer chapter. All 60 are accepted by `Archimate.validateElementType`, categorized
in `RelationshipLegality`, carry an SVG decorator token, and are documented. All 60 names
match their spec section title exactly under PascalCase concatenation.

| # | Element | Spec S | Spec text | Layer (S3.4/S3.5) | Aspect (S4) | Status | Notes |
| ---: | --- | --- | --- | --- | --- | --- | --- |
| 1 | `Grouping` | S4.5.1 Grouping | `sections-pdf-page-range/4-5-1-grouping.txt` | composite / any layer | Composite (all aspects) | supported |  |
| 2 | `Location` | S4.5.2 Location | `sections-pdf-page-range/4-5-2-location.txt` | composite / any layer | Composite (all aspects) | supported | fixture gives Location its own colour band while Grouping stays white |
| 3 | `Stakeholder` | S6.2.1 Stakeholder | `sections-pdf-page-range/6-2-1-stakeholder.txt` | Motivation | Motivation | supported |  |
| 4 | `Driver` | S6.2.2 Driver | `sections-pdf-page-range/6-2-2-driver.txt` | Motivation | Motivation | supported |  |
| 5 | `Assessment` | S6.2.3 Assessment | `sections-pdf-page-range/6-2-3-assessment.txt` | Motivation | Motivation | supported |  |
| 6 | `Goal` | S6.3.1 Goal | `sections-pdf-page-range/6-3-1-goal.txt` | Motivation | Motivation | supported |  |
| 7 | `Outcome` | S6.3.2 Outcome | `sections-pdf-page-range/6-3-2-outcome.txt` | Motivation | Motivation | supported | 3.2 added Plateau composition/aggregation to Outcome - allowed |
| 8 | `Principle` | S6.3.3 Principle | `sections-pdf-page-range/6-3-3-principle.txt` | Motivation | Motivation | supported |  |
| 9 | `Requirement` | S6.3.4 Requirement | `sections-pdf-page-range/6-3-4-requirement.txt` | Motivation | Motivation | supported |  |
| 10 | `Constraint` | S6.3.5 Constraint | `sections-pdf-page-range/6-3-5-constraint.txt` | Motivation | Motivation | supported | Constraint <-> Requirement specialization modelled (RelationshipLegality.java:169-174) |
| 11 | `Meaning` | S6.4.1 Meaning | `sections-pdf-page-range/6-4-1-meaning.txt` | Motivation | Motivation | supported |  |
| 12 | `Value` | S6.4.2 Value | `sections-pdf-page-range/6-4-2-value.txt` | Motivation | Motivation | supported |  |
| 13 | `Resource` | S7.2.1 Resource | `sections-pdf-page-range/7-2-1-resource.txt` | Strategy | Structure (Fig 46 parent: Structure Element) | supported | pinned to *internal active* structure - AM-VOCAB-7 |
| 14 | `Capability` | S7.3.1 Capability | `sections-pdf-page-range/7-3-1-capability.txt` | Strategy | Behavior (strategy behavior) | supported |  |
| 15 | `ValueStream` | S7.3.2 Value Stream | `sections-pdf-page-range/7-3-2-value-stream.txt` | Strategy | Behavior (strategy behavior) | supported |  |
| 16 | `CourseOfAction` | S7.3.3 Course of Action | `sections-pdf-page-range/7-3-3-course-of-action.txt` | Strategy | Behavior (non-strategy, per S7.1) | supported |  |
| 17 | `BusinessActor` | S8.2.1 Business Actor | `sections-pdf-page-range/8-2-1-business-actor.txt` | Business | Active structure (internal) | supported |  |
| 18 | `BusinessRole` | S8.2.2 Business Role | `sections-pdf-page-range/8-2-2-business-role.txt` | Business | Active structure (internal) | supported |  |
| 19 | `BusinessCollaboration` | S8.2.3 Business Collaboration | `sections-pdf-page-range/8-2-3-business-collaboration.txt` | Business | Active structure (internal) | supported |  |
| 20 | `BusinessInterface` | S8.2.4 Business Interface | `sections-pdf-page-range/8-2-4-business-interface.txt` | Business | Active structure (external) | supported |  |
| 21 | `BusinessProcess` | S8.3.1 Business Process | `sections-pdf-page-range/8-3-1-business-process.txt` | Business | Behavior (internal) | supported |  |
| 22 | `BusinessFunction` | S8.3.2 Business Function | `sections-pdf-page-range/8-3-2-business-function.txt` | Business | Behavior (internal) | supported |  |
| 23 | `BusinessInteraction` | S8.3.3 Business Interaction | `sections-pdf-page-range/8-3-3-business-interaction.txt` | Business | Behavior (internal) | supported |  |
| 24 | `BusinessEvent` | S8.3.4 Business Event | `sections-pdf-page-range/8-3-4-business-event.txt` | Business | Behavior (event) | supported |  |
| 25 | `BusinessService` | S8.3.5 Business Service | `sections-pdf-page-range/8-3-5-business-service.txt` | Business | Behavior (external) | supported |  |
| 26 | `BusinessObject` | S8.4.1 Business Object | `sections-pdf-page-range/8-4-1-business-object.txt` | Business | Passive structure | supported |  |
| 27 | `Contract` | S8.4.2 Contract | `sections-pdf-page-range/8-4-2-contract.txt` | Business | Passive structure | supported | Contract <-> BusinessObject specialization modelled |
| 28 | `Representation` | S8.4.3 Representation | `sections-pdf-page-range/8-4-3-representation.txt` | Business | Passive structure | supported |  |
| 29 | `Product` | S8.5.1 Product | `sections-pdf-page-range/8-5-1-product.txt` | Business | Composite (S4.5, S8.5) | supported |  |
| 30 | `ApplicationComponent` | S9.2.1 Application Component | `sections-pdf-page-range/9-2-1-application-component.txt` | Application | Active structure (internal) | supported |  |
| 31 | `ApplicationCollaboration` | S9.2.2 Application Collaboration | `sections-pdf-page-range/9-2-2-application-collaboration.txt` | Application | Active structure (internal) | supported |  |
| 32 | `ApplicationInterface` | S9.2.3 Application Interface | `sections-pdf-page-range/9-2-3-application-interface.txt` | Application | Active structure (external) | supported |  |
| 33 | `ApplicationFunction` | S9.3.1 Application Function | `sections-pdf-page-range/9-3-1-application-function.txt` | Application | Behavior (internal) | supported |  |
| 34 | `ApplicationInteraction` | S9.3.2 Application Interaction | `sections-pdf-page-range/9-3-2-application-interaction.txt` | Application | Behavior (internal) | supported |  |
| 35 | `ApplicationProcess` | S9.3.3 Application Process | `sections-pdf-page-range/9-3-3-application-process.txt` | Application | Behavior (internal) | supported |  |
| 36 | `ApplicationEvent` | S9.3.4 Application Event | `sections-pdf-page-range/9-3-4-application-event.txt` | Application | Behavior (event) | supported |  |
| 37 | `ApplicationService` | S9.3.5 Application Service | `sections-pdf-page-range/9-3-5-application-service.txt` | Application | Behavior (external) | supported |  |
| 38 | `DataObject` | S9.4.1 Data Object | `sections-pdf-page-range/9-4-1-data-object.txt` | Application | Passive structure | supported |  |
| 39 | `Node` | S10.2.1 Node | `sections-pdf-page-range/10-2-1-node.txt` | Technology | Active structure (internal) | supported | decorator token is `archimate_technology_node`, breaking the token rule - AM-VOCAB-6 |
| 40 | `Device` | S10.2.2 Device | `sections-pdf-page-range/10-2-2-device.txt` | Technology | Active structure (internal) | supported | 3.2 re-parent (E.3) reflected: composition/aggregation with Node allowed - AM-VOCAB-8 |
| 41 | `SystemSoftware` | S10.2.3 System Software | `sections-pdf-page-range/10-2-3-system-software.txt` | Technology | Active structure (internal) | supported | 3.2 re-parent (E.3) reflected |
| 42 | `TechnologyCollaboration` | S10.2.4 Technology Collaboration | `sections-pdf-page-range/10-2-4-technology-collaboration.txt` | Technology | Active structure (internal) | supported |  |
| 43 | `TechnologyInterface` | S10.2.5 Technology Interface | `sections-pdf-page-range/10-2-5-technology-interface.txt` | Technology | Active structure (external) | supported |  |
| 44 | `Path` | S10.2.6 Path | `sections-pdf-page-range/10-2-6-path.txt` | Technology | Active structure (internal) | supported |  |
| 45 | `CommunicationNetwork` | S10.2.7 Communication Network | `sections-pdf-page-range/10-2-7-communication-network.txt` | Technology | Active structure (internal) | supported |  |
| 46 | `TechnologyFunction` | S10.3.1 Technology Function | `sections-pdf-page-range/10-3-1-technology-function.txt` | Technology | Behavior (internal) | supported |  |
| 47 | `TechnologyProcess` | S10.3.2 Technology Process | `sections-pdf-page-range/10-3-2-technology-process.txt` | Technology | Behavior (internal) | supported |  |
| 48 | `TechnologyInteraction` | S10.3.3 Technology Interaction | `sections-pdf-page-range/10-3-3-technology-interaction.txt` | Technology | Behavior (internal) | supported |  |
| 49 | `TechnologyEvent` | S10.3.4 Technology Event | `sections-pdf-page-range/10-3-4-technology-event.txt` | Technology | Behavior (event) | supported |  |
| 50 | `TechnologyService` | S10.3.5 Technology Service | `sections-pdf-page-range/10-3-5-technology-service.txt` | Technology | Behavior (external) | supported |  |
| 51 | `Artifact` | S10.4.1 Artifact | `sections-pdf-page-range/10-4-1-artifact.txt` | Technology | Passive structure | supported |  |
| 52 | `Equipment` | S10.6.1 Equipment | `sections-pdf-page-range/10-6-1-equipment.txt` | Technology (physical, S3.5) | Active structure (physical, internal) | supported | 3.2 re-parent (E.3) reflected; no P-vs-T visual cue - AM-VOCAB-10 |
| 53 | `Facility` | S10.6.2 Facility | `sections-pdf-page-range/10-6-2-facility.txt` | Technology (physical, S3.5) | Active structure (physical, internal) | supported | 3.2 re-parent (E.3) reflected; no P-vs-T visual cue |
| 54 | `DistributionNetwork` | S10.6.3 Distribution Network | `sections-pdf-page-range/10-6-3-distribution-network.txt` | Technology (physical, S3.5) | Active structure (physical, internal) | supported | no P-vs-T visual cue |
| 55 | `Material` | S10.7.1 Material | `sections-pdf-page-range/10-7-1-material.txt` | Technology (physical, S3.5) | Passive structure (physical) | supported | 3.2 Material -[Realization]-> Equipment allowed - AM-VOCAB-8 |
| 56 | `WorkPackage` | S12.2.1 Work Package | `sections-pdf-page-range/12-2-1-work-package.txt` | Implementation & Migration | Behavior (internal, Fig 106) | supported | deprecated Realization -> Deliverable still silently accepted - AM-VOCAB-9 |
| 57 | `Deliverable` | S12.2.2 Deliverable | `sections-pdf-page-range/12-2-2-deliverable.txt` | Implementation & Migration | Passive structure (Fig 106) | supported |  |
| 58 | `ImplementationEvent` | S12.2.3 Implementation Event | `sections-pdf-page-range/12-2-3-implementation-event.txt` | Implementation & Migration | Behavior (event, Fig 106) | supported |  |
| 59 | `Plateau` | S12.2.4 Plateau | `sections-pdf-page-range/12-2-4-plateau.txt` | Implementation & Migration | Composite (Fig 106, S4.5) | supported | 3.2 Plateau composition/aggregation -> Outcome allowed - AM-VOCAB-8 |
| 60 | `Gap` | S12.2.5 Gap | `sections-pdf-page-range/12-2-5-gap.txt` | Implementation & Migration | Passive structure (Fig 106) | supported |  |

Implementing files, identical for every element row: `archimate/src/main/java/dev/dediren/archimate/Archimate.java` `ELEMENT_TYPES`; `archimate/src/main/java/dev/dediren/archimate/RelationshipLegality.java` `buildCategories()`; `contracts/src/main/java/dev/dediren/contracts/render/SvgNodeDecorator.java`; `schemas/render-policy.schema.json`; `fixtures/render-policy/archimate-svg.json`; `docs/agent-usage.md`

## 2. Relationships - 11 rows

All 11 pass `Archimate.validateRelationshipType`
(`archimate/src/main/java/dev/dediren/archimate/Archimate.java:79-91`), carry endpoint rules
in `RelationshipLegality.isAllowedEndpoint`
(`archimate/src/main/java/dev/dediren/archimate/RelationshipLegality.java:136-162`), have
notation in `fixtures/render-policy/archimate-svg.json:110-122`, and are emitted as OEF
`xsi:type` by
`engines/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/OefExportEngine.java:630`.
Endpoint-legality depth is lane A2; the `partial` rows below are *attribute* coverage gaps.

| # | Relationship | Spec S | Spec text | S5 group | Status | Gap |
| ---: | --- | --- | --- | --- | --- | --- |
| 1 | `Composition` | S5.1.1 Composition Relationship | `sections-pdf-page-range/5-1-1-composition-relationship.txt` | Structural | supported |  |
| 2 | `Aggregation` | S5.1.2 Aggregation Relationship | `sections-pdf-page-range/5-1-2-aggregation-relationship.txt` | Structural | supported |  |
| 3 | `Assignment` | S5.1.3 Assignment Relationship | `sections-pdf-page-range/5-1-3-assignment-relationship.txt` | Structural | supported |  |
| 4 | `Realization` | S5.1.4 Realization Relationship | `sections-pdf-page-range/5-1-4-realization-relationship.txt` | Structural | supported |  |
| 5 | `Serving` | S5.2.1 Serving Relationship | `sections-pdf-page-range/5-2-1-serving-relationship.txt` | Dependency | supported |  |
| 6 | `Access` | S5.2.2 Access Relationship | `sections-pdf-page-range/5-2-2-access-relationship.txt` | Dependency | partial | no access-type (read / write / read-write / unspecified) attribute or arrowhead notation |
| 7 | `Influence` | S5.2.3 Influence Relationship | `sections-pdf-page-range/5-2-3-influence-relationship.txt` | Dependency | partial | no sign/strength modifier attribute or `+/-` notation |
| 8 | `Association` | S5.2.4 Association Relationship | `sections-pdf-page-range/5-2-4-association-relationship.txt` | Dependency | partial | no directed/undirected variant; cannot attach to a relationship (S5.2.4, App B.6) |
| 9 | `Triggering` | S5.3.1 Triggering Relationship | `sections-pdf-page-range/5-3-1-triggering-relationship.txt` | Dynamic | supported |  |
| 10 | `Flow` | S5.3.2 Flow Relationship | `sections-pdf-page-range/5-3-2-flow-relationship.txt` | Dynamic | supported |  |
| 11 | `Specialization` | S5.4.1 Specialization Relationship | `sections-pdf-page-range/5-4-1-specialization-relationship.txt` | Other | supported |  |

## 3. Relationship connectors - 2 rows

| # | Type name | Spec S | Spec text | Spec concept | Status | Notes |
| ---: | --- | --- | --- | --- | --- | --- |
| 1 | `AndJunction` | S5.5.1 Junction | `sections-pdf-page-range/5-5-1-junction.txt` | Junction, "and" use | supported | S5.6 lists ONE connector concept, "Junction"; App A.3 labels the two notations "(And) Junction" and "Or Junction". Two entries in `Archimate.java:11-12,21-22` because OEF 3.1 `ElementTypeEnum` and `RelationshipConnectorEnum` do (AM-VOCAB-3) |
| 2 | `OrJunction` | S5.5.1 Junction | `sections-pdf-page-range/5-5-1-junction.txt` | Junction, "or" use | supported | as above |

Junction semantics live in `Archimate.validateJunctionRelationshipSemantics`
(`archimate/src/main/java/dev/dediren/archimate/Archimate.java:147-218`): one relationship
type per junction (S5.5), at least one incoming and one outgoing (S5.5), path legality
propagated to reachable non-junction targets (S5.5 states that junctions cannot be used to
create relationships that would otherwise be disallowed), and the S5.5 plateau/grouping/location
containment carve-out (`Archimate.java:264-281`). That is full S5.5 coverage.

## 4. Example viewpoints - 25 rows, all `absent`

Dediren models no viewpoint concept anywhere: no enumeration, no per-viewpoint allowed-
element or allowed-relationship restriction (S13.4), no `<viewpoints>` emission. The only
viewpoint surface is a **required free-form string** `viewpoint` in
`schemas/oef-export-policy.schema.json:12,32` (`"type": "string", "minLength": 1`), copied
verbatim into the OEF `view/@viewpoint` attribute by
`engines/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/OefExportEngine.java:674-675`.
See AM-VOCAB-4.

| # | Viewpoint | Spec S | Spec text | Category | Status |
| ---: | --- | --- | --- | --- | --- |
| 1 | Organization Viewpoint | SC.1.1 | `sections-pdf-page-range/c-1-1-organization-viewpoint.txt` | Basic | absent |
| 2 | Application Structure Viewpoint | SC.1.2 | `sections-pdf-page-range/c-1-2-application-structure-viewpoint.txt` | Basic | absent |
| 3 | Information Structure Viewpoint | SC.1.3 | `sections-pdf-page-range/c-1-3-information-structure-viewpoint.txt` | Basic | absent |
| 4 | Technology Viewpoint | SC.1.4 | `sections-pdf-page-range/c-1-4-technology-viewpoint.txt` | Basic | absent |
| 5 | Layered Viewpoint | SC.1.5 | `sections-pdf-page-range/c-1-5-layered-viewpoint.txt` | Basic | absent |
| 6 | Physical Viewpoint | SC.1.6 | `sections-pdf-page-range/c-1-6-physical-viewpoint.txt` | Basic | absent |
| 7 | Product Viewpoint | SC.1.7 | `sections-pdf-page-range/c-1-7-product-viewpoint.txt` | Basic | absent |
| 8 | Application Usage Viewpoint | SC.1.8 | `sections-pdf-page-range/c-1-8-application-usage-viewpoint.txt` | Basic | absent |
| 9 | Technology Usage Viewpoint | SC.1.9 | `sections-pdf-page-range/c-1-9-technology-usage-viewpoint.txt` | Basic | absent |
| 10 | Business Process Cooperation Viewpoint | SC.1.10 | `sections-pdf-page-range/c-1-10-business-process-cooperation-viewpoint.txt` | Basic | absent |
| 11 | Application Cooperation Viewpoint | SC.1.11 | `sections-pdf-page-range/c-1-11-application-cooperation-viewpoint.txt` | Basic | absent |
| 12 | Service Realization Viewpoint | SC.1.12 | `sections-pdf-page-range/c-1-12-service-realization-viewpoint.txt` | Basic | absent |
| 13 | Implementation and Deployment Viewpoint | SC.1.13 | `sections-pdf-page-range/c-1-13-implementation-and-deployment-viewpoint.txt` | Basic | absent |
| 14 | Stakeholder Viewpoint | SC.2.1 | `sections-pdf-page-range/c-2-1-stakeholder-viewpoint.txt` | Motivation | absent |
| 15 | Goal Realization Viewpoint | SC.2.2 | `sections-pdf-page-range/c-2-2-goal-realization-viewpoint.txt` | Motivation | absent |
| 16 | Requirements Realization Viewpoint | SC.2.3 | `sections-pdf-page-range/c-2-3-requirements-realization-viewpoint.txt` | Motivation | absent |
| 17 | Motivation Viewpoint | SC.2.4 | `sections-pdf-page-range/c-2-4-motivation-viewpoint.txt` | Motivation | absent |
| 18 | Strategy Viewpoint | SC.3.1 | `sections-pdf-page-range/c-3-1-strategy-viewpoint.txt` | Strategy | absent |
| 19 | Capability Map Viewpoint | SC.3.2 | `sections-pdf-page-range/c-3-2-capability-map-viewpoint.txt` | Strategy | absent |
| 20 | Value Stream Viewpoint | SC.3.3 | `sections-pdf-page-range/c-3-3-value-stream-viewpoint.txt` | Strategy | absent |
| 21 | Outcome Realization Viewpoint | SC.3.4 | `sections-pdf-page-range/c-3-4-outcome-realization-viewpoint.txt` | Strategy | absent |
| 22 | Resource Map Viewpoint | SC.3.5 | `sections-pdf-page-range/c-3-5-resource-map-viewpoint.txt` | Strategy | absent |
| 23 | Project Viewpoint | SC.4.1 | `sections-pdf-page-range/c-4-1-project-viewpoint.txt` | Implementation & Migration | absent |
| 24 | Migration Viewpoint | SC.4.2 | `sections-pdf-page-range/c-4-2-migration-viewpoint.txt` | Implementation & Migration | absent |
| 25 | Implementation and Migration Viewpoint | SC.4.3 | `sections-pdf-page-range/c-4-3-implementation-and-migration-viewpoint.txt` | Implementation & Migration | absent |

## 5. Cross-copy agreement (task 4)

Mechanical set/order diff of the six declared copies plus a seventh found in
`docs/agent-usage.md`:

| # | Copy | Count | Agreement with copy 1 |
| ---: | --- | ---: | --- |
| 1 | `archimate/src/main/java/dev/dediren/archimate/Archimate.java:14-77` | 62 | reference |
| 2 | `archimate/src/main/java/dev/dediren/archimate/RelationshipLegality.java:176-247` | 60 | identical minus the 2 connectors (by design; connectors never reach the rules) |
| 3 | `archimate/src/test/java/dev/dediren/archimate/ArchimateRelationshipLegalityConformanceTest.java:29-89` | 58 | identical minus connectors + `Plateau` + `Product`, which line 142 special-cases |
| 4 | `contracts/src/main/java/dev/dediren/contracts/render/SvgNodeDecorator.java:6-129` | 62 | identical set in token form |
| 5 | `schemas/render-policy.schema.json:146-207` | 62 | identical to copy 4, same order |
| 6 | `fixtures/render-policy/archimate-svg.json:47-108` | 62 | identical set, different order |
| 7 | `docs/agent-usage.md:275-292` | 62 | identical set AND identical order to copy 1 |

Zero elements present in one copy and missing or misnamed in another. The only token-level
asymmetry is `Node` -> `archimate_technology_node` (AM-VOCAB-6). No automated test pins
copies 4-7 against copy 1 (AM-VOCAB-5).

## 6. External anchor and the 3.1 -> 3.2 delta

`Archimate.ELEMENT_TYPES` (62) and `RELATIONSHIP_TYPES` (11) are byte-exact matches to the
Open Group ArchiMate 3.1 Model Exchange File Format `ElementTypeEnum` and
`RelationshipTypeEnum` (cached XSD
`~/.cache/dediren-real-schemas/opengroup/archimate/3.1/archimate3_Model.xsd:1219,1291,1319`).
That set is also exactly the ArchiMate 3.2 element vocabulary: 60 concrete elements
(S4.5 x2, S6.5 x10, S7.5 x4, S8.6 x13, S9.5 x9, S10.9 x17, S12.3 x5) plus the two junction
connectors. Appendix E.3 confirms 3.1 -> 3.2 added, removed, and renamed nothing.

The E.3 delta that *is* semantic is reflected in the rules - see AM-VOCAB-8:

| App E.3 3.2 change | Dediren behaviour | Verdict |
| --- | --- | --- |
| device/system software/facility/equipment re-parented off node onto technology internal active structure, with composition/aggregation to node | all five are `AS_INT`; `Composition`/`Aggregation` allowed when `s == t` (`RelationshipLegality.java:152`) | reflected |
| composition and aggregation added from plateau to outcome | `Plateau` is `COMP`, `Outcome` is `MOT`; the `s == Category.COMP` arm allows it (`RelationshipLegality.java:152`) | reflected |
| realization added from material to equipment | `Material` is `PAS`, `Equipment` is `AS_INT`; `REALIZATION_TARGETS[PAS]` contains `AS_INT` (`RelationshipLegality.java:109-118`) | reflected |
| default colour of plateau and gap changed to the Implementation & Migration pink | `fixtures/render-policy/archimate-svg.json:47,51` both use `#f8cecc`, the same fill as `WorkPackage`/`Deliverable`/`ImplementationEvent` | reflected |
| restrictions on derivation rules (B.3.5) improved; derivation rule added for grouping | no derivation closure is computed at all - a declared non-goal (`RelationshipLegality.java:22-24`); grouping is handled by the `UNIVERSAL` short-circuit (`RelationshipLegality.java:63,137`) | out of scope by design |
| icon/box notation changes for meaning, communication network, work package, value, business object, contract, representation, deliverable | icon kinds exist for all of them (`engines/render/.../archimate/ArchimateIcons.java:1588,1607,1597,...`); whether the drawn geometry is the 3.2 revision is a render-notation question | handed to the notation lane |


---

# Appendix B — UML 2.5.1 coverage matrix (238 rows)

## Status counts

| status | count |
| --- | --- |
| `supported` | 40 |
| `partial` | 23 |
| `divergent` | 5 |
| `absent` | 170 |
| **total** | **238** |

### Per clause

| clause | title | rows | supported | partial | divergent | absent |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| §7 | Common Structure | 23 | 3 | 2 | 1 | 17 |
| §8 | Values | 21 | 3 | 0 | 0 | 18 |
| §9 | Classification | 20 | 1 | 3 | 0 | 16 |
| §10 | Simple Classifiers | 9 | 6 | 0 | 0 | 3 |
| §11 | Structured Classifiers | 14 | 2 | 2 | 0 | 10 |
| §12 | Packages | 9 | 2 | 0 | 0 | 7 |
| §13 | Common Behavior | 5 | 0 | 0 | 1 | 4 |
| §14 | StateMachines | 13 | 5 | 3 | 0 | 5 |
| §15 | Activities | 25 | 10 | 2 | 0 | 13 |
| §16 | Actions | 63 | 1 | 1 | 0 | 61 |
| §17 | Interactions | 25 | 3 | 6 | 3 | 13 |
| §18 | UseCases | 5 | 3 | 2 | 0 | 0 |
| §19 | Deployments | 4 | 1 | 2 | 0 | 1 |
| §20 | Information Flows | 2 | 0 | 0 | 0 | 2 |
| | **total** | **238** | **40** | **23** | **5** | **170** |

## Matrix

### §7 Common Structure (23 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `Abstraction` | §7.8.1 | `sections/079-7-8-1-abstraction-class.txt` | `absent` | no relationship type maps to a bare Abstraction; only its concrete subclasses `Realization` and `Usage` are surfaced (`uml/src/main/java/dev/dediren/uml/Uml.java:74-90`) |
| `Comment` | §7.8.2 | `sections/084-7-8-2-comment-class.txt` | `absent` | no comment/note element kind; `Uml.java:24-54` has 36 element kinds, none of them a Comment |
| `Dependency` | §7.8.4.1 | `sections/095-7-8-4-1-dependency-class.txt` | `supported` | `Dependency` relationship kind `Uml.java:80`; emitted `engines/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/write/classifier/ClassRelationshipWriter.java:51` and `.../write/component/ComponentWriter.java:218` |
| `DirectedRelationship` *(Abstract Class)* | §7.8.5.1 | `sections/100-7-8-5-1-directedrelationship-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `Element` *(Abstract Class)* | §7.8.6.1 | `sections/105-7-8-6-1-element-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `ElementImport` | §7.8.7.1 | `sections/109-7-8-7-1-elementimport-class.txt` | `absent` | no import relationship type in `RELATIONSHIP_TYPES` (`Uml.java:74-90`) |
| `MultiplicityElement` *(Abstract Class)* | §7.8.8.1 | `sections/112-7-8-8-1-multiplicityelement-abstract-class.txt` | `partial` | bounds only: `multiplicity` string -> `lowerValue`/`upperValue` (`.../build/XmiHelpers.java:140,145`, validated `Uml.java:469`). `isOrdered`/`isUnique` are never read or emitted |
| `NamedElement` *(Abstract Class)* | §7.8.9.1 | `sections/118-7-8-9-1-namedelement-abstract-class.txt` | `partial` | `name` is carried everywhere; `visibility` is carried as free text (`.../write/classifier/ClassifierWriter.java:59,91`) with no allow-list; `nameExpression` and `clientDependency` absent |
| `Namespace` *(Abstract Class)* | §7.8.10 | `sections/124-7-8-10-namespace-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `PackageImport` | §7.8.11 | `sections/129-7-8-11-packageimport-class.txt` | `absent` | no import relationship type in `RELATIONSHIP_TYPES` (`Uml.java:74-90`) |
| `PackageableElement` *(Abstract Class)* | §7.8.12.1 | `sections/134-7-8-12-1-packageableelement-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `ParameterableElement` *(Abstract Class)* | §7.8.13 | `sections/138-7-8-13-parameterableelement-abstract-class.txt` | `absent` | UML templates/generics are not surfaced by any of the 8 view kinds |
| `Realization` | §7.8.14 | `sections/144-7-8-14-realization-class.txt` | `supported` | `Realization` relationship kind `Uml.java:79`; emitted `ClassRelationshipWriter.java:53` and `ComponentWriter.java:216` |
| `Relationship` *(Abstract Class)* | §7.8.15 | `sections/149-7-8-15-relationship-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `TemplateBinding` | §7.8.16 | `sections/154-7-8-16-templatebinding-class.txt` | `absent` | UML templates/generics are not surfaced by any of the 8 view kinds |
| `TemplateParameter` | §7.8.17.1 | `sections/159-7-8-17-1-templateparameter-class.txt` | `absent` | UML templates/generics are not surfaced by any of the 8 view kinds |
| `TemplateParameterSubstitution` | §7.8.18 | `sections/164-7-8-18-templateparametersubstitution-class.txt` | `absent` | UML templates/generics are not surfaced by any of the 8 view kinds |
| `TemplateSignature` | §7.8.19 | `sections/169-7-8-19-templatesignature-class.txt` | `absent` | UML templates/generics are not surfaced by any of the 8 view kinds |
| `TemplateableElement` *(Abstract Class)* | §7.8.20 | `sections/175-7-8-20-templateableelement-abstract-class.txt` | `absent` | UML templates/generics are not surfaced by any of the 8 view kinds |
| `Type` *(Abstract Class)* | §7.8.21 | `sections/181-7-8-21-type-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept; type *names* are resolved to `uml:PrimitiveType`/`uml:DataType` in `.../build/TypeResolver.java:16-17,52-54` |
| `TypedElement` *(Abstract Class)* | §7.8.22 | `sections/188-7-8-22-typedelement-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `Usage` | §7.8.23 | `sections/193-7-8-23-usage-class.txt` | `supported` | `Usage` relationship kind `Uml.java:87`; emitted `ComponentWriter.java:143,217` |
| `VisibilityKind` *(Enumeration)* | §7.8.24 | `sections/197-7-8-24-visibilitykind-enumeration.txt` | `divergent` | **4 of 4 literals rendered, 0 validated.** `visibility` is read as free text defaulting to `public` (`ClassifierWriter.java:59,91`) and written straight into the XMI attribute (`:68,:101`). No allow-list exists in `uml/`, `schemas/`, or the export engine; the only enumeration-shaped code is a *fallthrough* switch (`engines/render/src/main/java/dev/dediren/plugins/render/node/uml/UmlDecorators.java:251-257`, `semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlLayoutSizing.java:311-318`) where any unknown string silently becomes `+`. See UML-VOCAB-1 |

### §8 Values (21 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `Duration` | §8.6.1 | `sections/308-8-6-1-duration-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `DurationConstraint` | §8.6.2.1 | `sections/313-8-6-2-1-durationconstraint-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `DurationInterval` | §8.6.3 | `sections/316-8-6-3-durationinterval-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `DurationObservation` | §8.6.4 | `sections/321-8-6-4-durationobservation-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `Expression` | §8.6.5.1 | `sections/325-8-6-5-1-expression-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `IntervalConstraint` | §8.6.7.1 | `sections/334-8-6-7-1-intervalconstraint-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `LiteralBoolean` | §8.6.8.1 | `sections/338-8-6-8-1-literalboolean-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `LiteralInteger` | §8.6.9.1 | `sections/343-8-6-9-1-literalinteger-class.txt` | `supported` | emitted as `lowerValue` of a multiplicity (`XmiHelpers.java:140`) |
| `LiteralNull` | §8.6.10 | `sections/348-8-6-10-literalnull-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `LiteralReal` | §8.6.11.1 | `sections/352-8-6-11-1-literalreal-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `LiteralSpecification` *(Abstract Class)* | §8.6.12 | `sections/357-8-6-12-literalspecification-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `LiteralString` | §8.6.13 | `sections/362-8-6-13-literalstring-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings (default values are never emitted) |
| `LiteralUnlimitedNatural` | §8.6.14 | `sections/366-8-6-14-literalunlimitednatural-class.txt` | `supported` | emitted as `upperValue` of a multiplicity, with `*` for unlimited (`XmiHelpers.java:145`) |
| `Observation` *(Abstract Class)* | §8.6.15 | `sections/370-8-6-15-observation-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `OpaqueExpression` | §8.6.16.1 | `sections/375-8-6-16-1-opaqueexpression-class.txt` | `supported` | emitted for activity-edge guards (`.../write/activity/ActivityWriter.java:125`), transition guard specifications (`.../write/statemachine/StateMachineWriter.java:136`), and interaction-operand guards (`.../write/interaction/InteractionWriter.java:367`) |
| `StringExpression` | §8.6.17 | `sections/380-8-6-17-stringexpression-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `TimeConstraint` | §8.6.18 | `sections/384-8-6-18-timeconstraint-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `TimeExpression` | §8.6.19 | `sections/388-8-6-19-timeexpression-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `TimeInterval` | §8.6.20 | `sections/393-8-6-20-timeinterval-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `TimeObservation` | §8.6.21 | `sections/397-8-6-21-timeobservation-class.txt` | `absent` | value specifications are not surfaced; the source model carries plain strings |
| `ValueSpecification` *(Abstract Class)* | §8.6.22 | `sections/401-8-6-22-valuespecification-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |

### §9 Classification (20 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `AggregationKind` *(Enumeration)* | §9.9.1 | `sections/500-9-9-1-aggregationkind-enumeration.txt` | `supported` | 3 of 3 literals. Derived from the relationship kind, not authored directly: `Composition`->`composite`, `Aggregation`->`shared`, everything else `none` (`ClassRelationshipWriter.java:188-193`) |
| `BehavioralFeature` *(Abstract Class)* | §9.9.2.1 | `sections/503-9-9-2-1-behavioralfeature-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept; the concrete `Operation` is emitted as `<ownedOperation>` (`ClassifierWriter.java:96-101`) |
| `CallConcurrencyKind` *(Enumeration)* | §9.9.3.1 | `sections/507-9-9-3-1-callconcurrencykind-enumeration.txt` | `absent` | 0 of 3 literals. `concurrency` is never read or emitted anywhere in `engines/uml-xmi-export/src/main` |
| `Classifier` *(Abstract Class)* | §9.9.4.1 | `sections/508-9-9-4-1-classifier-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `ClassifierTemplateParameter` | §9.9.5.1 | `sections/512-9-9-5-1-classifiertemplateparameter-class.txt` | `absent` | UML templates/generics are not surfaced by any of the 8 view kinds |
| `Feature` *(Abstract Class)* | §9.9.6.1 | `sections/515-9-9-6-1-feature-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `GeneralizationSet` | §9.9.8.1 | `sections/523-9-9-8-1-generalizationset-class.txt` | `absent` | generalization sets, `isCovering`/`isDisjoint`, and powertypes are not surfaced; `Generalization` is emitted bare (`ClassifierWriter.java:41`) |
| `InstanceSpecification` | §9.9.9.1 | `sections/528-9-9-9-1-instancespecification-class.txt` | `absent` | no instance/object view kind, so InstanceSpecification-family concepts are unreachable |
| `InstanceValue` | §9.9.10 | `sections/533-9-9-10-instancevalue-class.txt` | `absent` | no instance/object view kind, so InstanceSpecification-family concepts are unreachable |
| `OperationTemplateParameter` | §9.9.12 | `sections/540-9-9-12-operationtemplateparameter-class.txt` | `absent` | UML templates/generics are not surfaced by any of the 8 view kinds |
| `Parameter` | §9.9.13 | `sections/544-9-9-13-parameter-class.txt` | `partial` | emitted as `<ownedParameter>` with `name`, `type`, `direction` only (`ClassifierWriter.java:109-129`). `direction` is hard-coded to `in` (`:117`) / `return` (`:126`) — 2 of 4 literals; `effect`, `multiplicity`, `isException`, `isStream`, `defaultValue` never emitted |
| `ParameterDirectionKind` *(Enumeration)* | §9.9.14 | `sections/549-9-9-14-parameterdirectionkind-enumeration.txt` | `partial` | **2 of 4 literals** (`in` at `ClassifierWriter.java:117`, `return` at `:126`). `inout` and `out` are unreachable: nothing reads a per-parameter direction from the source. See UML-VOCAB-2 |
| `ParameterEffectKind` *(Enumeration)* | §9.9.15 | `sections/552-9-9-15-parametereffectkind-enumeration.txt` | `absent` | 0 of 4 literals (`create`, `read`, `update`, `delete`, §9.9.15); `effect` is never emitted on a Parameter |
| `ParameterSet` | §9.9.16 | `sections/555-9-9-16-parameterset-class.txt` | `absent` | not surfaced by the `uml-class`/`uml-data` view kinds (STRUCTURAL_TYPES has 6 element kinds) |
| `Property` | §9.9.17 | `sections/560-9-9-17-property-class.txt` | `partial` | emitted as `<ownedAttribute>` (name/type/visibility/multiplicity, `ClassifierWriter.java:50-71`) and as association `<ownedEnd>` with `aggregation` (`ClassRelationshipWriter.java:150-161`). `isStatic`, `isDerived`, `isDerivedUnion`, `isReadOnly`, `isID`, `defaultValue`, `qualifier`, `subsettedProperty`, `redefinedProperty` are never emitted |
| `RedefinableElement` *(Abstract Class)* | §9.9.18 | `sections/567-9-9-18-redefinableelement-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `RedefinableTemplateSignature` | §9.9.19 | `sections/573-9-9-19-redefinabletemplatesignature-class.txt` | `absent` | UML templates/generics are not surfaced by any of the 8 view kinds |
| `Slot` | §9.9.20 | `sections/577-9-9-20-slot-class.txt` | `absent` | no instance/object view kind, so InstanceSpecification-family concepts are unreachable |
| `StructuralFeature` *(Abstract Class)* | §9.9.21 | `sections/582-9-9-21-structuralfeature-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `Substitution` | §9.9.22 | `sections/587-9-9-22-substitution-class.txt` | `absent` | not surfaced by the `uml-class`/`uml-data` view kinds (STRUCTURAL_TYPES has 6 element kinds) |

### §10 Simple Classifiers (9 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `BehavioredClassifier` *(Abstract Class)* | §10.5.1 | `sections/678-10-5-1-behavioredclassifier-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `DataType` | §10.5.2 | `sections/684-10-5-2-datatype-class.txt` | `supported` | element kind `Uml.java:25`; decorator `uml_data_type` (`SvgNodeDecorator.java:137`); emitted `.../build/XmiBuilder.java:444` and `TypeResolver.java:54` |
| `Enumeration` | §10.5.3 | `sections/690-10-5-3-enumeration-class.txt` | `supported` | element kind `Uml.java:25`; decorator `uml_enumeration` (`SvgNodeDecorator.java:139`); emitted with `<ownedLiteral>` children (`ClassifierWriter.java:130-152`) |
| `EnumerationLiteral` | §10.5.4 | `sections/694-10-5-4-enumerationliteral-class.txt` | `supported` | emitted as `<ownedLiteral>` from `properties.uml.literals[]` (`ClassifierWriter.java:136-151`). Name-only, which is a complete EnumerationLiteral; `specification` is optional in the spec |
| `Interface` | §10.5.5 | `sections/699-10-5-5-interface-class.txt` | `supported` | element kind `Uml.java:25`; decorator `uml_interface` (`SvgNodeDecorator.java:135`); emitted `XmiBuilder.java:442`; `«interface»` keyword drawn at `UmlDecorators.java:117-118` |
| `InterfaceRealization` | §10.5.6 | `sections/703-10-5-6-interfacerealization-class.txt` | `supported` | emitted for `Realization` edges landing on an Interface inside a component view (`ComponentWriter.java:70`) |
| `PrimitiveType` | §10.5.7 | `sections/708-10-5-7-primitivetype-class.txt` | `supported` | emitted for unresolved/primitive type names (`TypeResolver.java:16,52`) |
| `Reception` | §10.5.8 | `sections/712-10-5-8-reception-class.txt` | `absent` | not surfaced by the `uml-class`/`uml-data` view kinds (STRUCTURAL_TYPES has 6 element kinds); signals are not modelled |
| `Signal` | §10.5.9 | `sections/716-10-5-9-signal-class.txt` | `absent` | not surfaced by the `uml-class`/`uml-data` view kinds (STRUCTURAL_TYPES has 6 element kinds); no signal element kind, which is also why `uml:Trigger` has no event (see UML-VOCAB-6) |

### §11 Structured Classifiers (14 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `Association` | §11.8.1 | `sections/788-11-8-1-association-class.txt` | `supported` | relationship kind `Uml.java:75`; emitted as `<packagedElement xmi:type="uml:Association">` with two `<ownedEnd>` member ends (`ClassRelationshipWriter.java:116-166`); also used for Actor<->UseCase (`:60`) |
| `AssociationClass` | §11.8.2.1 | `sections/794-11-8-2-1-associationclass-class.txt` | `absent` | not surfaced by the `uml-class`/`uml-data` view kinds (STRUCTURAL_TYPES has 6 element kinds) |
| `Class` | §11.8.3 | `sections/797-11-8-3-class-class.txt` | `partial` | element kind `Uml.java:25`; decorator `uml_class` (`SvgNodeDecorator.java:133`); emitted `XmiBuilder.java:440` with attributes/operations/generalizations. `isAbstract`, `isActive`, `isFinalSpecialization`, `nestedClassifier`, `ownedReception` are never emitted, so an abstract class exports as concrete |
| `Collaboration` | §11.8.4.1 | `sections/804-11-8-4-1-collaboration-class.txt` | `absent` | not surfaced by the `uml-class`/`uml-data` view kinds (STRUCTURAL_TYPES has 6 element kinds) |
| `Component` | §11.8.6 | `sections/811-11-8-6-component-class.txt` | `supported` | element kind `Uml.java:25`; decorator `uml_component` (`SvgNodeDecorator.java:185`); emitted `ComponentWriter.java:34` |
| `ComponentRealization` | §11.8.7.1 | `sections/815-11-8-7-1-componentrealization-class.txt` | `absent` | a `Realization` inside a component view is emitted as a plain `uml:Realization`, never `uml:ComponentRealization` (`ComponentWriter.java:216`), so `Component::realization` is empty on import. See UML-VOCAB-7 |
| `ConnectableElement` *(Abstract Class)* | §11.8.8.1 | `sections/819-11-8-8-1-connectableelement-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `ConnectableElementTemplateParameter` | §11.8.9.1 | `sections/824-11-8-9-1-connectableelementtemplateparameter-class.txt` | `absent` | UML templates/generics are not surfaced by any of the 8 view kinds |
| `Connector` | §11.8.10.1 | `sections/827-11-8-10-1-connector-class.txt` | `absent` | component wiring is expressed only as `Usage`/`InterfaceRealization` dependencies (`ComponentWriter.java:143,70`); no assembly/delegation connector element or relationship kind exists |
| `ConnectorEnd` | §11.8.11.1 | `sections/831-11-8-11-1-connectorend-class.txt` | `absent` | no Connector, so no ConnectorEnd |
| `ConnectorKind` *(Enumeration)* | §11.8.12.1 | `sections/834-11-8-12-1-connectorkind-enumeration.txt` | `absent` | 0 of 2 literals (`assembly`, `delegation`); no Connector is ever emitted |
| `EncapsulatedClassifier` *(Abstract Class)* | §11.8.13 | `sections/836-11-8-13-encapsulatedclassifier-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `Port` | §11.8.14 | `sections/841-11-8-14-port-class.txt` | `partial` | element kind `Uml.java:52`; decorator `uml_port` (`SvgNodeDecorator.java:187`); emitted `ComponentWriter.java:85` with `provided`/`required` interfaces validated at `Uml.java:572-583`. `isConjugated`, `isBehavior`, `isService`, and `Port::type` are never emitted |
| `StructuredClassifier` *(Abstract Class)* | §11.8.15.1 | `sections/847-11-8-15-1-structuredclassifier-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |

### §12 Packages (9 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `Extension` | §12.4.1 | `sections/943-12-4-1-extension-class.txt` | `absent` | no UML Profile machinery anywhere (no uml:Profile/Stereotype/Extension emitted, no schema field) |
| `ExtensionEnd` | §12.4.2 | `sections/948-12-4-2-extensionend-class.txt` | `absent` | no UML Profile machinery anywhere (no uml:Profile/Stereotype/Extension emitted, no schema field) |
| `Image` | §12.4.3 | `sections/952-12-4-3-image-class.txt` | `absent` | no UML Profile machinery anywhere (no uml:Profile/Stereotype/Extension emitted, no schema field); no icon/image surface |
| `Model` | §12.4.4 | `sections/957-12-4-4-model-class.txt` | `supported` | every export is wrapped in `<uml:Model>` (`XmiBuilder.java:305,357`) |
| `Package` | §12.4.5 | `sections/961-12-4-5-package-class.txt` | `supported` | element kind `Uml.java:25`; decorator `uml_package` (`SvgNodeDecorator.java:131`); emitted `XmiBuilder.java:386,389,427` |
| `PackageMerge` | §12.4.6 | `sections/966-12-4-6-packagemerge-class.txt` | `absent` | no merge relationship kind in `RELATIONSHIP_TYPES` (`Uml.java:74-90`) |
| `Profile` | §12.4.7 | `sections/971-12-4-7-profile-class.txt` | `absent` | no UML Profile machinery anywhere (no uml:Profile/Stereotype/Extension emitted, no schema field) |
| `ProfileApplication` | §12.4.8 | `sections/975-12-4-8-profileapplication-class.txt` | `absent` | no UML Profile machinery anywhere (no uml:Profile/Stereotype/Extension emitted, no schema field) |
| `Stereotype` | §12.4.9 | `sections/980-12-4-9-stereotype-class.txt` | `absent` | no UML Profile machinery anywhere (no uml:Profile/Stereotype/Extension emitted, no schema field). The guillemet titles the renderer draws (`UmlDecorators.java:66-69,116-120`) are hard-coded UML *keyword* notation for Device/ExecutionEnvironment/DeploymentSpecification/Enumeration/Interface/DataType, not Stereotype applications, and nothing corresponding reaches the XMI. See UML-VOCAB-4 |

### §13 Common Behavior (5 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `AnyReceiveEvent` | §13.4.1 | `sections/1039-13-4-1-anyreceiveevent-class.txt` | `absent` | no event metaclass is surfaced; a transition trigger is a bare name (`StateMachineWriter.java:119-125`) |
| `Behavior` *(Abstract Class)* | §13.4.2 | `sections/1043-13-4-2-behavior-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept; the only concrete behavior emitted is `uml:OpaqueBehavior` as a transition effect (`StateMachineWriter.java:146`) |
| `FunctionBehavior` | §13.4.6 | `sections/1049-13-4-6-functionbehavior-class.txt` | `absent` | not surfaced by the `uml-state-machine` view kind (STATE_MACHINE_TYPES has 5 element kinds) |
| `MessageEvent` *(Abstract Class)* | §13.4.7 | `sections/1053-13-4-7-messageevent-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `Trigger` | §13.4.11.1 | `sections/1059-13-4-11-1-trigger-class.txt` | `divergent` | emitted as `<trigger xmi:type="uml:Trigger" name="…"/>` with **no `event`** (`StateMachineWriter.java:115-125`). `Trigger::event` is `[1..1]`, so the emitted element is structurally invalid UML. The omission is deliberate and documented in the code comment at `:115-118` (no Signal/Operation surface exists to bind an event to). See UML-VOCAB-6 |

### §14 StateMachines (13 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `ConnectionPointReference` | §14.5.1 | `sections/1159-14-5-1-connectionpointreference-class.txt` | `absent` | not surfaced by the `uml-state-machine` view kind (STATE_MACHINE_TYPES has 5 element kinds); entry/exit points exist as `Pseudostate` kinds but submachine states do not |
| `FinalState` | §14.5.2 | `sections/1164-14-5-2-finalstate-class.txt` | `supported` | element kind `Uml.java:50`; decorator `uml_final_state` (`SvgNodeDecorator.java:175`); emitted `StateMachineWriter.java:81` |
| `ProtocolConformance` | §14.5.3 | `sections/1168-14-5-3-protocolconformance-class.txt` | `absent` | not surfaced by the `uml-state-machine` view kind (STATE_MACHINE_TYPES has 5 element kinds) |
| `ProtocolStateMachine` | §14.5.4 | `sections/1173-14-5-4-protocolstatemachine-class.txt` | `absent` | not surfaced by the `uml-state-machine` view kind (STATE_MACHINE_TYPES has 5 element kinds); only behavior state machines |
| `ProtocolTransition` | §14.5.5 | `sections/1177-14-5-5-protocoltransition-class.txt` | `absent` | not surfaced by the `uml-state-machine` view kind (STATE_MACHINE_TYPES has 5 element kinds) |
| `Pseudostate` | §14.5.6 | `sections/1181-14-5-6-pseudostate-class.txt` | `supported` | element kind `Uml.java:50`; decorator `uml_pseudostate` (`SvgNodeDecorator.java:177`); emitted with `kind` (`StateMachineWriter.java:87-92`); all 10 kinds validated at `Uml.java:60-71` |
| `PseudostateKind` *(Enumeration)* | §14.5.7 | `sections/1186-14-5-7-pseudostatekind-enumeration.txt` | `supported` | **10 of 10 literals** — `initial`, `deepHistory`, `shallowHistory`, `join`, `fork`, `junction`, `choice`, `entryPoint`, `exitPoint`, `terminate` (`Uml.java:60-71`), matching the spec list exactly. Validated as a hard allow-list at `Uml.java:535-538`. See UML-VOCAB-5 (refutation of the 'however many' framing) |
| `Region` | §14.5.8 | `sections/1188-14-5-8-region-class.txt` | `supported` | element kind `Uml.java:50`; decorator `uml_region` (`SvgNodeDecorator.java:171`); emitted as `<region>` and required on every vertex (`Uml.java:532`) |
| `State` | §14.5.9 | `sections/1192-14-5-9-state-class.txt` | `partial` | element kind `Uml.java:50`; decorator `uml_state` (`SvgNodeDecorator.java:173`); emitted `StateMachineWriter.java:75`. `entry`, `exit`, `doActivity`, `stateInvariant`, `submachine`, `connectionPoint`, `deferrableTrigger` are never read or emitted, so composite/submachine states degrade to plain states |
| `StateMachine` | §14.5.10.1 | `sections/1198-14-5-10-1-statemachine-class.txt` | `partial` | element kind `Uml.java:50`; decorator `uml_state_machine` (`SvgNodeDecorator.java:169`); emitted `StateMachineWriter.java:23`. `connectionPoint`, `extendedStateMachine`, `submachineState` never emitted |
| `Transition` | §14.5.11.1 | `sections/1203-14-5-11-1-transition-class.txt` | `partial` | relationship kind `Uml.java:84`; emitted with `kind`, `trigger`, `guard` (as `uml:Constraint`+`uml:OpaqueExpression`), and `effect` (as `uml:OpaqueBehavior`) at `StateMachineWriter.java:104-152`. `port` and `redefinedTransition` absent; the emitted `uml:Trigger` is eventless (see Trigger row) |
| `TransitionKind` *(Enumeration)* | §14.5.12.1 | `sections/1208-14-5-12-1-transitionkind-enumeration.txt` | `supported` | **3 of 3 literals** — `internal`, `local`, `external` (`Uml.java:72`), matching the spec exactly; emitted with `external` as the default (`StateMachineWriter.java:112-113`) |
| `Vertex` *(Abstract Class)* | §14.5.13.1 | `sections/1210-14-5-13-1-vertex-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept; the concrete `State`/`FinalState`/`Pseudostate` are supported (`Uml.java:56`) |

### §15 Activities (25 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `Activity` | §15.7.1 | `sections/1338-15-7-1-activity-class.txt` | `partial` | element kind `Uml.java:28`; decorator `uml_activity` (`SvgNodeDecorator.java:141`); emitted `ActivityWriter.java:25` with nodes, edges, and partitions. `ownedParameter`, `variable`, `isReadOnly`, `isSingleExecution`, `precondition`/`postcondition` never emitted |
| `ActivityEdge` *(Abstract Class)* | §15.7.2 | `sections/1344-15-7-2-activityedge-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept; the concrete `ControlFlow`/`ObjectFlow` are supported (`Uml.java:99`) |
| `ActivityFinalNode` | §15.7.3 | `sections/1351-15-7-3-activityfinalnode-class.txt` | `supported` | element kind `Uml.java:31`; decorator `uml_activity_final_node` (`SvgNodeDecorator.java:147`); emitted `ActivityWriter.java:136` |
| `ActivityGroup` *(Abstract Class)* | §15.7.4 | `sections/1355-15-7-4-activitygroup-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `ActivityNode` *(Abstract Class)* | §15.7.5.1 | `sections/1361-15-7-5-1-activitynode-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `ActivityParameterNode` | §15.7.6 | `sections/1367-15-7-6-activityparameternode-class.txt` | `absent` | not surfaced by the `uml-activity` view kind (ACTIVITY_TYPES has 9 element kinds) |
| `ActivityPartition` | §15.7.7 | `sections/1371-15-7-7-activitypartition-class.txt` | `supported` | emitted from view groups via the composite `Activity::group` feature (`ActivityWriter.java:68-71`); not a dediren element kind, so it is reachable only through view grouping |
| `CentralBufferNode` | §15.7.8 | `sections/1377-15-7-8-centralbuffernode-class.txt` | `supported` | emitted, but only indirectly: dediren's `ObjectNode` element kind is concretized to `uml:CentralBufferNode` (`ActivityWriter.java:141`). There is no way to ask for a DataStoreNode instead |
| `ControlFlow` | §15.7.9 | `sections/1382-15-7-9-controlflow-class.txt` | `supported` | relationship kind `Uml.java:81`; emitted `ActivityWriter.java:147`, with an optional `uml:OpaqueExpression` guard (`:125`) |
| `ControlNode` *(Abstract Class)* | §15.7.10.1 | `sections/1386-15-7-10-1-controlnode-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `DataStoreNode` | §15.7.11.1 | `sections/1390-15-7-11-1-datastorenode-class.txt` | `absent` | not surfaced by the `uml-activity` view kind (ACTIVITY_TYPES has 9 element kinds); `ObjectNode` always concretizes to `CentralBufferNode` (`ActivityWriter.java:141`) |
| `DecisionNode` | §15.7.12.1 | `sections/1393-15-7-12-1-decisionnode-class.txt` | `supported` | element kind `Uml.java:32`; decorator `uml_decision_node` (`SvgNodeDecorator.java:149`); emitted `ActivityWriter.java:137`. `decisionInput`/`decisionInputFlow` absent, but guards on outgoing edges are the standard notation |
| `ExceptionHandler` | §15.7.13.1 | `sections/1397-15-7-13-1-exceptionhandler-class.txt` | `absent` | not surfaced by the `uml-activity` view kind (ACTIVITY_TYPES has 9 element kinds) |
| `ExecutableNode` *(Abstract Class)* | §15.7.14 | `sections/1400-15-7-14-executablenode-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `FinalNode` *(Abstract Class)* | §15.7.15.1 | `sections/1405-15-7-15-1-finalnode-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `FlowFinalNode` | §15.7.16.1 | `sections/1409-15-7-16-1-flowfinalnode-class.txt` | `absent` | not surfaced by the `uml-activity` view kind (ACTIVITY_TYPES has 9 element kinds); only `ActivityFinalNode` is in `ACTIVITY_TYPES` (`Uml.java:26-36`), so a flow-final cannot be distinguished from an activity-final |
| `ForkNode` | §15.7.17.1 | `sections/1412-15-7-17-1-forknode-class.txt` | `supported` | element kind `Uml.java:34`; decorator `uml_fork_node` (`SvgNodeDecorator.java:153`); emitted `ActivityWriter.java:139` |
| `InitialNode` | §15.7.18.1 | `sections/1415-15-7-18-1-initialnode-class.txt` | `supported` | element kind `Uml.java:30`; decorator `uml_initial_node` (`SvgNodeDecorator.java:145`); emitted `ActivityWriter.java:135` |
| `InterruptibleActivityRegion` | §15.7.19.1 | `sections/1418-15-7-19-1-interruptibleactivityregion-class.txt` | `absent` | not surfaced by the `uml-activity` view kind (ACTIVITY_TYPES has 9 element kinds) |
| `JoinNode` | §15.7.20.1 | `sections/1422-15-7-20-1-joinnode-class.txt` | `supported` | element kind `Uml.java:35`; decorator `uml_join_node` (`SvgNodeDecorator.java:155`); emitted `ActivityWriter.java:140`. `joinSpec` absent (its spec default `and` applies) |
| `MergeNode` | §15.7.21.1 | `sections/1425-15-7-21-1-mergenode-class.txt` | `supported` | element kind `Uml.java:33`; decorator `uml_merge_node` (`SvgNodeDecorator.java:151`); emitted `ActivityWriter.java:138` |
| `ObjectFlow` | §15.7.22.1 | `sections/1428-15-7-22-1-objectflow-class.txt` | `supported` | relationship kind `Uml.java:82`; emitted `ActivityWriter.java:147` with an optional guard. `isMulticast`, `isMultireceive`, `transformation`, `selection` absent |
| `ObjectNode` *(Abstract Class)* | §15.7.23.1 | `sections/1433-15-7-23-1-objectnode-abstract-class.txt` | `partial` | dediren surfaces the *abstract* `ObjectNode` as a concrete element kind (`Uml.java:36`, decorator `uml_object_node` at `SvgNodeDecorator.java:157`) and concretizes it to `uml:CentralBufferNode` on export (`ActivityWriter.java:141`). `ordering`, `upperBound`, `inState`, `isControlType`, and `type` are never emitted |
| `ObjectNodeOrderingKind` *(Enumeration)* | §15.7.24.1 | `sections/1439-15-7-24-1-objectnodeorderingkind-enumeration.txt` | `absent` | 0 of 4 literals (`unordered`, `ordered`, `LIFO`, `FIFO`); `ordering` is never read or emitted |
| `Variable` | §15.7.25.1 | `sections/1441-15-7-25-1-variable-class.txt` | `absent` | not surfaced by the `uml-activity` view kind (ACTIVITY_TYPES has 9 element kinds) |

### §16 Actions (63 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `AcceptCallAction` | §16.14.1 | `sections/1622-16-14-1-acceptcallaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `AcceptEventAction` | §16.14.2 | `sections/1626-16-14-2-accepteventaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `Action` *(Abstract Class)* | §16.14.3.1 | `sections/1632-16-14-3-1-action-abstract-class.txt` | `partial` | dediren surfaces the *abstract* `Action` as a concrete element kind (`Uml.java:29`, decorator `uml_action` at `SvgNodeDecorator.java:143`) and always concretizes it to `uml:OpaqueAction` (`ActivityWriter.java:134,142`). `localPrecondition`, `localPostcondition`, `isLocallyReentrant`, and all pins are absent |
| `ActionInputPin` | §16.14.4.1 | `sections/1637-16-14-4-1-actioninputpin-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `AddStructuralFeatureValueAction` | §16.14.5.1 | `sections/1640-16-14-5-1-addstructuralfeaturevalueaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `AddVariableValueAction` | §16.14.6.1 | `sections/1643-16-14-6-1-addvariablevalueaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `BroadcastSignalAction` | §16.14.7.1 | `sections/1646-16-14-7-1-broadcastsignalaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `CallAction` *(Abstract Class)* | §16.14.8.1 | `sections/1649-16-14-8-1-callaction-abstract-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `CallBehaviorAction` | §16.14.9.1 | `sections/1654-16-14-9-1-callbehavioraction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `CallOperationAction` | §16.14.10 | `sections/1657-16-14-10-calloperationaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `Clause` | §16.14.11 | `sections/1662-16-14-11-clause-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ClearAssociationAction` | §16.14.12 | `sections/1667-16-14-12-clearassociationaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ClearStructuralFeatureAction` | §16.14.13 | `sections/1672-16-14-13-clearstructuralfeatureaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ClearVariableAction` | §16.14.14 | `sections/1676-16-14-14-clearvariableaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ConditionalNode` | §16.14.15 | `sections/1680-16-14-15-conditionalnode-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `CreateLinkAction` | §16.14.16 | `sections/1685-16-14-16-createlinkaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `CreateLinkObjectAction` | §16.14.17 | `sections/1690-16-14-17-createlinkobjectaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `CreateObjectAction` | §16.14.18 | `sections/1694-16-14-18-createobjectaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `DestroyLinkAction` | §16.14.19 | `sections/1699-16-14-19-destroylinkaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `DestroyObjectAction` | §16.14.20 | `sections/1703-16-14-20-destroyobjectaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ExpansionKind` *(Enumeration)* | §16.14.21 | `sections/1707-16-14-21-expansionkind-enumeration.txt` | `absent` | 0 of 3 literals (`parallel`, `iterative`, `stream`); no ExpansionRegion exists |
| `ExpansionNode` | §16.14.22 | `sections/1710-16-14-22-expansionnode-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ExpansionRegion` | §16.14.23 | `sections/1715-16-14-23-expansionregion-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `InputPin` | §16.14.24 | `sections/1720-16-14-24-inputpin-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `InvocationAction` *(Abstract Class)* | §16.14.25 | `sections/1725-16-14-25-invocationaction-abstract-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `LinkAction` *(Abstract Class)* | §16.14.26 | `sections/1731-16-14-26-linkaction-abstract-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `LinkEndCreationData` | §16.14.27 | `sections/1737-16-14-27-linkendcreationdata-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `LinkEndData` | §16.14.28 | `sections/1741-16-14-28-linkenddata-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `LinkEndDestructionData` | §16.14.29 | `sections/1747-16-14-29-linkenddestructiondata-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `LoopNode` | §16.14.30 | `sections/1751-16-14-30-loopnode-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `OpaqueAction` | §16.14.31 | `sections/1756-16-14-31-opaqueaction-class.txt` | `supported` | the single concrete action metaclass dediren emits (`ActivityWriter.java:134,142`); also the silent `default ->` fallback for any unrecognized activity node type (`ActivityWriter.java:142`). `body`/`language`/`inputValue`/`outputValue` never emitted |
| `OutputPin` | §16.14.32 | `sections/1762-16-14-32-outputpin-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `Pin` *(Abstract Class)* | §16.14.33 | `sections/1766-16-14-33-pin-abstract-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `QualifierValue` | §16.14.34 | `sections/1771-16-14-34-qualifiervalue-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `RaiseExceptionAction` | §16.14.35 | `sections/1776-16-14-35-raiseexceptionaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ReadExtentAction` | §16.14.36 | `sections/1780-16-14-36-readextentaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ReadIsClassifiedObjectAction` | §16.14.37 | `sections/1785-16-14-37-readisclassifiedobjectaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ReadLinkAction` | §16.14.38 | `sections/1790-16-14-38-readlinkaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ReadLinkObjectEndAction` | §16.14.39 | `sections/1794-16-14-39-readlinkobjectendaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ReadLinkObjectEndQualifierAction` | §16.14.40 | `sections/1799-16-14-40-readlinkobjectendqualifieraction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ReadSelfAction` | §16.14.41 | `sections/1804-16-14-41-readselfaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ReadStructuralFeatureAction` | §16.14.42 | `sections/1808-16-14-42-readstructuralfeatureaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ReadVariableAction` | §16.14.43 | `sections/1812-16-14-43-readvariableaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ReclassifyObjectAction` | §16.14.44 | `sections/1816-16-14-44-reclassifyobjectaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ReduceAction` | §16.14.45 | `sections/1820-16-14-45-reduceaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `RemoveStructuralFeatureValueAction` | §16.14.46 | `sections/1825-16-14-46-removestructuralfeaturevalueaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `RemoveVariableValueAction` | §16.14.47 | `sections/1829-16-14-47-removevariablevalueaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ReplyAction` | §16.14.48 | `sections/1833-16-14-48-replyaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `SendObjectAction` | §16.14.49 | `sections/1838-16-14-49-sendobjectaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `SendSignalAction` | §16.14.50 | `sections/1843-16-14-50-sendsignalaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `SequenceNode` | §16.14.51 | `sections/1848-16-14-51-sequencenode-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `StartClassifierBehaviorAction` | §16.14.52 | `sections/1852-16-14-52-startclassifierbehavioraction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `StartObjectBehaviorAction` | §16.14.53 | `sections/1856-16-14-53-startobjectbehavioraction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `StructuralFeatureAction` *(Abstract Class)* | §16.14.54 | `sections/1860-16-14-54-structuralfeatureaction-abstract-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `StructuredActivityNode` | §16.14.55 | `sections/1866-16-14-55-structuredactivitynode-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `TestIdentityAction` | §16.14.56 | `sections/1872-16-14-56-testidentityaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `UnmarshallAction` | §16.14.57 | `sections/1877-16-14-57-unmarshallaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ValuePin` | §16.14.58 | `sections/1882-16-14-58-valuepin-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `ValueSpecificationAction` | §16.14.59 | `sections/1886-16-14-59-valuespecificationaction-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `VariableAction` *(Abstract Class)* | §16.14.60 | `sections/1891-16-14-60-variableaction-abstract-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `WriteLinkAction` *(Abstract Class)* | §16.14.61 | `sections/1896-16-14-61-writelinkaction-abstract-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `WriteStructuralFeatureAction` *(Abstract Class)* | §16.14.62 | `sections/1901-16-14-62-writestructuralfeatureaction-abstract-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |
| `WriteVariableAction` *(Abstract Class)* | §16.14.63 | `sections/1907-16-14-63-writevariableaction-abstract-class.txt` | `absent` | dediren's activity vocabulary has exactly one action kind (`Action` -> `uml:OpaqueAction`); no Pin, structured-node, or action-taxonomy surface exists in any of the 8 view kinds |

### §17 Interactions (25 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `ActionExecutionSpecification` | §17.12.1 | `sections/2155-17-12-1-actionexecutionspecification-class.txt` | `absent` | not surfaced by the `uml-sequence` view kind (SEQUENCE_TYPES has 7 element kinds); dediren's `ExecutionSpecification` never reaches the exporter (see that row) |
| `BehaviorExecutionSpecification` | §17.12.2.1 | `sections/2159-17-12-2-1-behaviorexecutionspecification-class.txt` | `absent` | not surfaced by the `uml-sequence` view kind (SEQUENCE_TYPES has 7 element kinds); dediren's `ExecutionSpecification` never reaches the exporter (see that row) |
| `CombinedFragment` | §17.12.3.1 | `sections/2162-17-12-3-1-combinedfragment-class.txt` | `partial` | element kind `Uml.java:47`; emitted `InteractionWriter.java:329`. **4 of 12 `interactionOperator` literals** (`alt`, `opt`, `loop`, `par` at `uml/src/main/java/dev/dediren/uml/UmlSequenceValidation.java:33-34`), enforced twice (`UmlSequenceValidation.java:225`, `InteractionWriter.java:440-448`). Also has **no `SvgNodeDecorator` value** — see UML-VOCAB-8 |
| `ConsiderIgnoreFragment` | §17.12.4.1 | `sections/2167-17-12-4-1-considerignorefragment-class.txt` | `absent` | requires the `consider`/`ignore` operators, which are 2 of the 8 missing `InteractionOperatorKind` literals (`UmlSequenceValidation.java:33-34`) |
| `Continuation` | §17.12.5.1 | `sections/2170-17-12-5-1-continuation-class.txt` | `absent` | not surfaced by the `uml-sequence` view kind (SEQUENCE_TYPES has 7 element kinds) |
| `DestructionOccurrenceSpecification` | §17.12.6.1 | `sections/2173-17-12-6-1-destructionoccurrencespecification-class.txt` | `divergent` | accepted as an element kind (`Uml.java:46`), given a decorator `uml_destruction_occurrence` (`SvgNodeDecorator.java:167`) and an SVG shape — then **hard-rejected by the XMI exporter** with `DEDIREN_XMI_UNSUPPORTED_SEQUENCE_NODE` (`InteractionWriter.java:494-506`). The exporter separately *auto-emits* one for a `deleteMessage` receive event (`InteractionWriter.java:396-401`), so the metaclass is reachable only by a route the author cannot select. See UML-VOCAB-9 |
| `ExecutionOccurrenceSpecification` | §17.12.7.1 | `sections/2176-17-12-7-1-executionoccurrencespecification-class.txt` | `absent` | not surfaced by the `uml-sequence` view kind (SEQUENCE_TYPES has 7 element kinds) |
| `ExecutionSpecification` *(Abstract Class)* | §17.12.8.1 | `sections/2179-17-12-8-1-executionspecification-abstract-class.txt` | `divergent` | accepted as an element kind (`Uml.java:44`), given a decorator `uml_execution_specification` (`SvgNodeDecorator.java:163`), sized by the layout engine (`semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlLayoutSizing.java:95,105`) and drawn — then **hard-rejected by the XMI exporter** (`InteractionWriter.java:504`). Separately, `ExecutionSpecification` is abstract in UML: even if exported it would need a concrete subtype. See UML-VOCAB-9 |
| `Gate` | §17.12.9 | `sections/2184-17-12-9-gate-class.txt` | `divergent` | accepted as an element kind (`Uml.java:45`), given a decorator `uml_gate` (`SvgNodeDecorator.java:165`), sized (`UmlLayoutSizing.java:96,106`) and drawn — then **hard-rejected by the XMI exporter** (`InteractionWriter.java:505`). See UML-VOCAB-9 |
| `GeneralOrdering` | §17.12.10 | `sections/2188-17-12-10-generalordering-class.txt` | `absent` | not surfaced by the `uml-sequence` view kind (SEQUENCE_TYPES has 7 element kinds) |
| `Interaction` | §17.12.11 | `sections/2193-17-12-11-interaction-class.txt` | `partial` | element kind `Uml.java:42`; decorator `uml_interaction` (`SvgNodeDecorator.java:159`); emitted `InteractionWriter.java:44` with lifelines, fragments, and messages. `ownedParameter`, `formalGate`, `action`, `ownedBehavior` never emitted |
| `InteractionConstraint` | §17.12.12 | `sections/2198-17-12-12-interactionconstraint-class.txt` | `supported` | emitted as an operand guard with a nested `uml:OpaqueExpression` (`InteractionWriter.java:362-367`). `minint`/`maxint` never emitted, so a bounded `loop` cannot express its bounds |
| `InteractionFragment` *(Abstract Class)* | §17.12.13 | `sections/2203-17-12-13-interactionfragment-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `InteractionOperand` | §17.12.14 | `sections/2209-17-12-14-interactionoperand-class.txt` | `partial` | element kind `Uml.java:48`; emitted as `<operand>` with an `InteractionConstraint` guard (`InteractionWriter.java:355-390`). Has **no `SvgNodeDecorator` value** — see UML-VOCAB-8 |
| `InteractionOperatorKind` *(Enumeration)* | §17.12.15 | `sections/2214-17-12-15-interactionoperatorkind-enumeration.txt` | `partial` | **4 of 12 literals** — `alt`, `opt`, `loop`, `par` supported (`UmlSequenceValidation.java:33-34`); `seq`, `strict`, `break`, `critical`, `neg`, `assert`, `ignore`, `consider` absent. Rejected at export with `DEDIREN_XMI_UNSUPPORTED_SEQUENCE_FRAGMENT_OPERATOR` (`InteractionWriter.java:440-448`). See UML-VOCAB-3 |
| `InteractionUse` | §17.12.16 | `sections/2217-17-12-16-interactionuse-class.txt` | `absent` | not surfaced by the `uml-sequence` view kind (SEQUENCE_TYPES has 7 element kinds); no `ref` fragment |
| `Lifeline` | §17.12.17 | `sections/2222-17-12-17-lifeline-class.txt` | `partial` | element kind `Uml.java:43`; decorator `uml_lifeline` (`SvgNodeDecorator.java:161`); emitted as `<lifeline>`. `represents`, `selector`, and `decomposedAs` never emitted, so a lifeline has no ConnectableElement binding |
| `Message` | §17.12.18 | `sections/2227-17-12-18-message-class.txt` | `partial` | relationship kind `Uml.java:83`; emitted `InteractionWriter.java:416-427` with `messageSort`, `sendEvent`, `receiveEvent`. `signature`, `argument`, `connector`, and the derived `messageKind` never emitted |
| `MessageEnd` *(Abstract Class)* | §17.12.19 | `sections/2233-17-12-19-messageend-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept |
| `MessageKind` *(Enumeration)* | §17.12.20 | `sections/2238-17-12-20-messagekind-enumeration.txt` | `absent` | 0 of 4 literals (`complete`, `lost`, `found`, `unknown`); `messageKind` is a *derived* attribute and is never emitted (`InteractionWriter.java:416-427`) — defensible, since a consumer recomputes it |
| `MessageOccurrenceSpecification` | §17.12.21 | `sections/2241-17-12-21-messageoccurrencespecification-class.txt` | `supported` | emitted for both ends of every message (`InteractionWriter.java:393-414`) |
| `MessageSort` *(Enumeration)* | §17.12.22 | `sections/2246-17-12-22-messagesort-enumeration.txt` | `supported` | **6 of 6 literals** — `synchCall`, `asynchCall`, `asynchSignal`, `reply`, `createMessage`, `deleteMessage` (`UmlSequenceValidation.java:31-32`), matching the spec exactly; validated at `:555` and emitted at `InteractionWriter.java:422` |
| `OccurrenceSpecification` | §17.12.23 | `sections/2248-17-12-23-occurrencespecification-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept; the concrete `MessageOccurrenceSpecification` is supported |
| `PartDecomposition` | §17.12.24 | `sections/2254-17-12-24-partdecomposition-class.txt` | `absent` | not surfaced by the `uml-sequence` view kind (SEQUENCE_TYPES has 7 element kinds) |
| `StateInvariant` | §17.12.25 | `sections/2258-17-12-25-stateinvariant-class.txt` | `absent` | not surfaced by the `uml-sequence` view kind (SEQUENCE_TYPES has 7 element kinds) |

### §18 UseCases (5 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `Actor` | §18.2.1 | `sections/2319-18-2-1-actor-class.txt` | `supported` | element kind `Uml.java:51`; decorator `uml_actor` (`SvgNodeDecorator.java:179`) and stick-figure shape (`UmlDecorators.java:43`); emitted `XmiBuilder.java:446` |
| `Extend` | §18.2.2 | `sections/2323-18-2-2-extend-class.txt` | `partial` | relationship kind `Uml.java:86`; emitted as `<extend>` with `extensionLocation` (`.../write/usecase/UseCaseWriter.java:90-101`), cross-validated against the target use case at `Uml.java:681-694`. `Extend::condition` (the guard Constraint) is never emitted |
| `ExtensionPoint` | §18.2.3 | `sections/2327-18-2-3-extensionpoint-class.txt` | `supported` | element kind `Uml.java:51`; decorator `uml_extension_point` (`SvgNodeDecorator.java:183`); emitted as `<extensionPoint>` (`UseCaseWriter.java:67-74`) with `use_case` ownership validated at `Uml.java:564` |
| `Include` | §18.2.4.1 | `sections/2331-18-2-4-1-include-class.txt` | `supported` | relationship kind `Uml.java:85`; emitted as `<include>` (`UseCaseWriter.java:38-44`) |
| `UseCase` | §18.2.5 | `sections/2335-18-2-5-usecase-class.txt` | `partial` | element kind `Uml.java:51`; decorator `uml_use_case` (`SvgNodeDecorator.java:181`); emitted `UseCaseWriter.java:21` with `subject` (`:26-28`), extension points, includes and extends. `ownedBehavior` and multi-`subject` never emitted (`UseCase::subject` is `[0..*]`; only one is read at `Uml.java:546`) |

### §19 Deployments (4 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `Artifact` | §19.5.1 | `sections/2380-19-5-1-artifact-class.txt` | `partial` | element kind `Uml.java:54`; decorator `uml_artifact` (`SvgNodeDecorator.java:195`); emitted `.../write/deployment/DeploymentWriter.java:177`. `fileName`, `nestedArtifact`, `ownedAttribute`, `ownedOperation`, and `manifestation` ownership detail absent |
| `DeploymentSpecification` | §19.5.5 | `sections/2386-19-5-5-deploymentspecification-class.txt` | `partial` | element kind `Uml.java:54`; decorator `uml_deployment_specification` (`SvgNodeDecorator.java:197`); emitted `DeploymentWriter.java:176`. `deploymentLocation` and `executionLocation` never emitted, which are the two attributes that distinguish it from a plain Artifact |
| `DeploymentTarget` *(Abstract Class)* | §19.5.6 | `sections/2391-19-5-6-deploymenttarget-abstract-class.txt` | `absent` | abstract metamodel infrastructure, not a diagram concept; the concrete `Node`, `Device`, `ExecutionEnvironment` are supported (`Uml.java:54`) |
| `Device` | §19.5.7 | `sections/2397-19-5-7-device-class.txt` | `supported` | element kind `Uml.java:54`; decorator `uml_device` (`SvgNodeDecorator.java:191`); emitted `DeploymentWriter.java:252`; `«device»` keyword drawn at `UmlDecorators.java:67` |

### §20 Information Flows (2 metaclasses)

| metaclass | spec section | text_path | status | implementing evidence |
| --- | --- | --- | --- | --- |
| `InformationFlow` | §20.2.1 | `sections/2430-20-2-1-informationflow-class.txt` | `absent` | information flows are not a dediren view kind or element kind |
| `InformationItem` | §20.2.2.1 | `sections/2434-20-2-2-1-informationitem-class.txt` | `absent` | information flows are not a dediren view kind or element kind |

## Enumeration-literal coverage

Spec literal lists come from each enumeration's own clause. Where the local extraction split a
`Literals` subsection across a page break, the continuation file/page is cited too.

| enumeration | spec section | spec `text_path` | spec literals (M) | dediren supports (N) | verdict |
| --- | --- | --- | ---: | --- | --- |
| `VisibilityKind` | §7.8.24.3 | `sections/197-7-8-24-visibilitykind-enumeration.txt` (+ `pages/page-102.txt` tail) | 4 | `public`, `private`, `protected`, `package` are all *rendered* (`UmlDecorators.java:251-257`) but **none is validated** | **rendered 4 of 4; validated 0 of 4** — see UML-VOCAB-1 |
| `ParameterDirectionKind` | §9.9.14.3 | `sections/549-9-9-14-parameterdirectionkind-enumeration.txt` | 4 | `in` (`ClassifierWriter.java:117`), `return` (`:126`) | **supported 2 of 4** (`inout`, `out` unreachable) — UML-VOCAB-2 |
| `AggregationKind` | §9.9.1.3 | `sections/502-9-9-1-3-literals.txt` | 3 | `none`, `shared`, `composite` (`ClassRelationshipWriter.java:188-193`) | **supported 3 of 3** (derived from relationship kind, not authored) |
| `PseudostateKind` | §14.5.7.3 | `sections/1186-14-5-7-pseudostatekind-enumeration.txt` (+ `pages/page-348.txt`) | 10 | all 10 (`Uml.java:60-71`), hard allow-list at `:535-538` | **supported 10 of 10** — UML-VOCAB-5 |
| `TransitionKind` | §14.5.12.3 | `sections/1208-14-5-12-1-transitionkind-enumeration.txt` | 3 | `internal`, `local`, `external` (`Uml.java:72`) | **supported 3 of 3** |
| `InteractionOperatorKind` | §17.12.15.3 | `sections/2216-17-12-15-3-literals.txt` (+ `sections/2217-…` for `neg`/`assert`/`ignore`/`consider`) | 12 | `alt`, `opt`, `loop`, `par` (`UmlSequenceValidation.java:33-34`) | **supported 4 of 12** — UML-VOCAB-3 |
| `MessageSort` | §17.12.22.3 | `sections/2246-17-12-22-messagesort-enumeration.txt` (+ `pages/page-616.txt`) | 6 | all 6 (`UmlSequenceValidation.java:31-32`) | **supported 6 of 6** |
| `MessageKind` | §17.12.20.3 | `sections/2240-17-12-20-3-literals.txt` | 4 | none | **supported 0 of 4** — derived attribute, defensibly omitted |
| `CallConcurrencyKind` | §9.9.3.3 | `sections/507-9-9-3-1-callconcurrencykind-enumeration.txt` (+ `pages/page-174.txt`) | 3 | none | **supported 0 of 3** — `concurrency` never emitted |
| `ObjectNodeOrderingKind` | §15.7.24.3 | `sections/1440-15-7-24-3-literals.txt` | 4 | none | **supported 0 of 4** — `ordering` never emitted |
| `ExpansionKind` | §16.14.21.3 | `sections/1707-16-14-21-expansionkind-enumeration.txt` | 3 | none | **supported 0 of 3** — no ExpansionRegion |
| `ConnectorKind` | §11.8.12.3 | `sections/835-11-8-12-3-literals.txt` | 2 | none | **supported 0 of 2** — no Connector |
| `ParameterEffectKind` *(bonus)* | §9.9.15 | `sections/552-9-9-15-parametereffectkind-enumeration.txt` | 4 | none | **supported 0 of 4** |

**Single-sourcing note (positive):** the two sequence enumerations are *not* triplicated. Both the
render engine (`engines/render/src/main/java/dev/dediren/plugins/render/RenderInputValidator.java:31-34`)
and the XMI exporter (`.../write/interaction/InteractionWriter.java:32-33`) call
`UmlSequenceValidation.messageSorts()` / `.combinedFragmentOperators()` rather than re-declaring
them. The triplication is specific to the *element-kind* vocabulary.

## Row-set caveat

The supplied row set is derived from `toc-index.json`, whose heading extraction is
incomplete. At least nine metaclasses that dediren **does** emit have no clause
heading in the local TOC and therefore no row in this matrix:

`Node`, `ExecutionEnvironment`, `Deployment`, `Manifestation`, `CommunicationPath`,
`Operation`, `Generalization`, `Constraint`, `OpaqueBehavior`.

All nine are emitted by the writers (`DeploymentWriter.java:154,176,194,217,253-254`,
`ClassifierWriter.java:41,96`, `StateMachineWriter.java:131,146`). Their absence from
the matrix understates coverage; it is an artifact of the extraction, not of dediren.
Verified by `grep -c '^<name>,' uml-metaclasses.csv` returning 0 for each, and by the
TOC containing no `<name> [Class]` title for any of them.




## Status — remediated 2026-08-12

This register is diagnose-only; the execution record is
`2026-08-12-conformance-remediation.md` beside it. Summary of the outcome, so a reader arriving here
does not have to reconstruct it from the plan.

**Fixed.** Every `block` finding in Groups 1–6 except where noted below, plus most `warn`s:
`AM-NOT-1..7,-11`, `AM-OEF-1..6,-8,-9,-10,-12`, `AM-SEM-2,-5,-10,-11,-12`, `AM-VOCAB-4,-5,-9`,
`UML-NOT-2..5,-7,-8`, part of `-9`, `UML-SEM-1..5,-8,-13,-15,-16,-17,-19,-22,-23,-24,-29`,
`UML-VOCAB-1`, `UML-XMI-12,-13,-16,-21`, and every `DOC-` finding.

**Six findings were real observations with wrong conclusions attached**, which is the register's own
most reusable result and the reason each fix was re-derived rather than applied:

- `UML-NOT-7` — the proposed fix would have kept the overflowing tab copy and deleted the correct
  body label.
- `UML-NOT-1` — composing the keyword at render time is on the wrong side of layout; the paint
  oracle caught it and the change was reverted.
- `UML-XMI-18` — the "schema-valid slot" is not valid there; the pinned XSD's content model excludes
  its own namespace.
- `UML-XMI-2` — "no test pins the wording" is false; corrupting both literals fails two tests.
- `UML-XMI-5`/`UML-VOCAB-7` — sized as a metaclass rename; §10.5.6 needs a nested
  `InterfaceRealization`, so it is a writer restructure.
- `UML-XMI-14`/`-15` — both refuted against the OMG's published DD schemas. `dc:Bounds` is a global
  element named after its type and `di:waypoint` a local element in a qualified schema, so the
  "mixed convention" is the schemas' own; and `20100524` is those schemas' `targetNamespace`, not a
  stale DD 1.0 reference. Acting on either would emit documents no DD-aware tool can read.

**And one conclusion this remediation got wrong.** `AM-SEM-1` was implemented as written — cross-type
specialization narrowed to one direction — and reverted the same day when the Appendix-B oracle
produced exactly two false negatives, both of them there. §8.4.2's "a contract is a specialization of
a business object" is a metamodel statement, and B.5 derives the pair through that inheritance in
both directions. The unordered `Set.of(s, t)` assertion this register flagged as "permitting but not
requiring the bug" was correct, and deliberately so.

**Measured, once the specification was available locally:** the ArchiMate legality model rejects
**79.9%** of the combinations Appendix B forbids, with **zero false negatives**, over 10,620 allowed
triples. `AM-SEM-9`'s undocumented `CATCH_RATE_FLOOR` now has that provenance and sits at 0.79.

**Accepted or carried forward.** Seven remainders are recorded in `docs/architecture-guidelines.md`
§12, each with the condition that reopens it. One followup stays open: the §B.4 domain dimension
(`AM-SEM-3/-4/-7`), whose findings the oracle confirms are real and whose fix is now verifiable
against it — that verification was the thing missing when the work was deferred.

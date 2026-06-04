# Dediren UML Sequence Combined Fragments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a vertical UML sequence combined-fragment slice: source validation, projection, sequence layout compatibility, SVG rendering, UML/XMI export, CLI workflow coverage, docs, and version metadata for `alt`, `opt`, `loop`, and `par` frames.

**Architecture:** Keep `uml-sequence` as the public view kind and represent `CombinedFragment` and `InteractionOperand` as UML source nodes with Dediren-specific `properties.uml` membership metadata. Keep sequence layout ELK-first by excluding source-only fragment/operand semantic nodes from layout requests while preserving them in render metadata and export source scope. Render and export derive fragment bounds and containment from message ids, nested fragment ids, and ordered operand metadata rather than introducing a new public schema family.

**Tech Stack:** Java 21, Maven Wrapper, Jackson, JSON Schema, first-party process-boundary plugins, Eclipse ELK, SVG, UML 2.5.1 XMI.

---

## Scope Decision

This plan refines roadmap Task 2 into the next executable vertical slice.

Included now:

- `CombinedFragment` source nodes.
- `InteractionOperand` source nodes.
- Operand guards represented as `properties.uml.guard` strings.
- `InteractionOperatorKind` values required by the first rendered output: `alt`, `opt`, `loop`, and `par`.
- Deterministic SVG frames with operator tabs, operand separators, guard labels, and stable `data-dediren-*` attributes.
- UML/XMI `uml:CombinedFragment`, nested `operand`, `guard`, and message occurrence fragments inside operands.

Deferred to a later slice:

- `InteractionUse` reference frames.
- `GeneralOrdering` relationships.
- `ConsiderIgnoreFragment`, `ignore`, and `consider` operator semantics.
- UMLDI.
- Full UML conformance claims beyond the explicitly tested Dediren slice.

This cut is intentional: `InteractionUse` and `GeneralOrdering` are useful UML interaction concepts, but they are not needed to make combined fragments usable and would require separate authoring, rendering, and export acceptance rules.

## Source Conventions

Use existing source JSON shapes. No `model.schema.v1` structural change is required because UML-specific fields live under `properties.uml`.

Combined fragment node:

```json
{
  "id": "cf-availability",
  "type": "CombinedFragment",
  "label": "Availability",
  "properties": {
    "uml": {
      "interaction": "interaction-place-order",
      "operator": "alt",
      "operands": ["op-in-stock", "op-backorder"],
      "covered": ["customer", "service"]
    }
  }
}
```

Interaction operand node:

```json
{
  "id": "op-in-stock",
  "type": "InteractionOperand",
  "label": "In Stock",
  "properties": {
    "uml": {
      "interaction": "interaction-place-order",
      "combined_fragment": "cf-availability",
      "order": 1,
      "guard": "inStock",
      "fragments": ["m2", "m3"]
    }
  }
}
```

Rules:

- `CombinedFragment.properties.uml.interaction` is required and must name an `Interaction` node.
- `CombinedFragment.properties.uml.operator` is required and must be one of `alt`, `opt`, `loop`, or `par` for this slice.
- `CombinedFragment.properties.uml.operands` is required, ordered, non-empty, and must reference `InteractionOperand` nodes.
- `InteractionOperand.properties.uml.combined_fragment` is required and must name a selected `CombinedFragment`.
- `InteractionOperand.properties.uml.order` is required and must be an integer greater than or equal to `1`.
- `InteractionOperand.properties.uml.fragments` is required and must reference selected `Message` ids or selected nested `CombinedFragment` ids.
- `guard` is optional for `par`, but expected in fixtures for `alt`, `opt`, and `loop`.
- `opt` and `loop` require exactly one operand. `alt` and `par` require at least two operands.
- Operand message ids must all belong to the same interaction as the operand.

## File Responsibility Map

- `fixtures/source/valid-uml-sequence-fragments.json`: source fixture with one `uml-sequence` view containing `alt`, `opt`, `loop`, and `par`.
- `fixtures/render-metadata/uml-sequence-fragments.json`: expected fragment/operand render selectors.
- `fixtures/layout-result/uml-sequence-fragments.json`: stable renderer/export fixture derived from layout output.
- `fixtures/export/uml-sequence-fragments.xmi`: deterministic UML/XMI fixture.
- `modules/uml/src/main/java/dev/dediren/uml/Uml.java`: vocabulary and semantic validation for fragment/operand nodes and their properties.
- `modules/plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java`: keep fragment/operand nodes in render metadata; exclude source-only fragment/operand nodes from sequence layout requests.
- `modules/plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java`: only add sizing if fragment/operand nodes remain in any layout request for non-sequence contexts.
- `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/UmlSequenceModel.java`: parse fragment/operand metadata and resolve referenced messages/nested fragments.
- `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/UmlSequenceRenderer.java`: render frames, operator tabs, operand separators, and guard labels.
- `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/RenderInputValidator.java`: validate fragment/operand render metadata shape.
- `modules/plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`: export nested UML interaction fragments.
- `apps/cli/src/test/java/dev/dediren/cli/MainTest.java`: add documented CLI workflow coverage for the fragments fixture.
- `README.md` and `docs/agent-usage.md`: document supported combined-fragment authoring conventions.
- Version surfaces listed in AGENTS.md: bump to `0.22.0` because this is additive public UML surface.

## Task 0: Preflight And Branch Setup

**Files:**

- Read: `AGENTS.md`
- Read: `docs/superpowers/plans/2026-06-04-dediren-uml-expansion-roadmap.md`
- Read: `docs/superpowers/plans/2026-06-04-dediren-uml-sequence-mvp.md`

- [ ] **Step 1: Check repository state**

```bash
git status --short --branch
```

Expected: record current branch and any pre-existing user-owned changes.

- [ ] **Step 2: Create an isolated worktree**

Use `superpowers:using-git-worktrees`.

Suggested branch:

```bash
git worktree add .worktrees/uml-sequence-fragments -b feature/uml-sequence-fragments main
```

Expected: a clean worktree at `.worktrees/uml-sequence-fragments`.

- [ ] **Step 3: Confirm no release tag collision**

```bash
git tag --list v0.22.0
```

Expected before implementation: no output. If a tag exists, stop and choose the next version according to AGENTS.md.

## Task 1: Add Fragment Fixture And Contract Coverage

**Files:**

- Create: `fixtures/source/valid-uml-sequence-fragments.json`
- Modify: `modules/contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`

- [ ] **Step 1: Create the source fixture**

Add `fixtures/source/valid-uml-sequence-fragments.json` with:

- `Interaction` node `interaction-place-order`.
- Lifelines `customer`, `service`, `inventory`, and `payment`.
- Combined fragments:
  - `cf-availability`, operator `alt`, operands `op-in-stock` and `op-backorder`.
  - `cf-coupon`, operator `opt`, operand `op-coupon`.
  - `cf-retry`, operator `loop`, operand `op-retry`.
  - `cf-parallel-closeout`, operator `par`, operands `op-charge` and `op-confirm`.
- Messages ordered with `properties.uml.sequence` and assigned to operands through each operand's `properties.uml.fragments`.
- A `uml-sequence` view named `sequence-fragments-view` selecting all lifelines, fragment nodes, operand nodes, and messages.

Use this shape for the first fragment:

```json
{
  "id": "cf-availability",
  "type": "CombinedFragment",
  "label": "Availability",
  "properties": {
    "uml": {
      "interaction": "interaction-place-order",
      "operator": "alt",
      "operands": ["op-in-stock", "op-backorder"],
      "covered": ["customer", "service", "inventory"]
    }
  }
}
```

- [ ] **Step 2: Write failing contract test**

Add a focused test in `ContractRoundTripTest`:

```java
@Test
void umlSequenceFragmentsFixtureRoundTrips() throws Exception {
    JsonNode source = JsonSupport.objectMapper().readTree(fixture("fixtures/source/valid-uml-sequence-fragments.json"));

    assertThat(source.at("/plugins/generic-graph/views/0/kind").asText())
            .isEqualTo("uml-sequence");
    assertThat(source.at("/nodes/3/type").asText())
            .isEqualTo("CombinedFragment");
    assertThat(source.at("/nodes/4/type").asText())
            .isEqualTo("InteractionOperand");
    assertThat(source.at("/nodes/3/properties/uml/operator").asText())
            .isEqualTo("alt");
    assertThat(source.at("/nodes/4/properties/uml/fragments/0").asText())
            .startsWith("m");
}
```

- [ ] **Step 3: Run the failing contract lane**

```bash
./mvnw -pl modules/contracts -am test -Dtest=ContractRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected now: schema or fixture assumptions fail until downstream validation/projection accepts the new UML concepts. If it passes immediately, keep the test and continue; schema shape is already open enough for these properties.

- [ ] **Step 4: Commit fixture and contract test**

```bash
git add fixtures/source/valid-uml-sequence-fragments.json modules/contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java
git commit -m "Add UML sequence fragment fixture contract"
```

## Task 2: Add UML Fragment Vocabulary And Validation

**Files:**

- Modify: `modules/uml/src/main/java/dev/dediren/uml/Uml.java`
- Modify: `modules/uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`

- [ ] **Step 1: Write failing validation tests**

Add tests:

```java
@Test
void acceptsUmlSequenceCombinedFragments() throws Exception {
    assertValidUmlSource(Files.readString(workspaceRoot().resolve(
            "fixtures/source/valid-uml-sequence-fragments.json")));
}

@Test
void rejectsUnknownCombinedFragmentKeyword() throws Exception {
    String source = mutateFragmentOperator("unknownOperator");

    UmlValidationException error = assertThrows(
            UmlValidationException.class,
            () -> validateUmlSource(source));

    assertThat(error.code()).isEqualTo("DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED");
    assertThat(error.path()).contains("properties.uml.operator");
}

@Test
void rejectsOptFragmentWithMultipleOperands() throws Exception {
    String source = mutateFragmentOperands("cf-coupon", List.of("op-coupon", "op-in-stock"));

    UmlValidationException error = assertThrows(
            UmlValidationException.class,
            () -> validateUmlSource(source));

    assertThat(error.path()).contains("properties.uml.operands");
}
```

Use existing helper style in `UmlValidationTest`; add small mutation helpers near the existing sequence helpers.

- [ ] **Step 2: Run tests to verify failure**

```bash
./mvnw -pl modules/uml -am test -Dtest=UmlValidationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: failures for unsupported `CombinedFragment` / `InteractionOperand` or missing property validation.

- [ ] **Step 3: Implement vocabulary**

In `Uml.java`, extend the sequence type set:

```java
private static final Set<String> SEQUENCE_TYPES = Set.of(
        "Interaction",
        "Lifeline",
        "ExecutionSpecification",
        "Gate",
        "DestructionOccurrenceSpecification",
        "CombinedFragment",
        "InteractionOperand");
```

Add operator support:

```java
private static final Set<String> COMBINED_FRAGMENT_OPERATORS = Set.of(
        "alt",
        "opt",
        "loop",
        "par");
```

- [ ] **Step 4: Implement node property validation**

Add a node-property validation branch after multiplicity validation:

```java
private static void validateSequenceNodeProperties(
        String nodeType,
        JsonNode umlProperties,
        String path,
        Set<String> nodeIds,
        Set<String> relationshipIds) throws UmlValidationException {
    if ("CombinedFragment".equals(nodeType)) {
        validateCombinedFragmentProperties(umlProperties, path, nodeIds);
    } else if ("InteractionOperand".equals(nodeType)) {
        validateInteractionOperandProperties(umlProperties, path, nodeIds, relationshipIds);
    }
}
```

Keep the implementation local to `modules:uml`. Do not move these rules into contracts or core.

- [ ] **Step 5: Enforce operand count rules**

Implement:

```java
private static void validateOperandCount(String operator, JsonNode operands, String path)
        throws UmlValidationException {
    int count = operands == null || !operands.isArray() ? 0 : operands.size();
    boolean supported = switch (operator) {
        case "opt", "loop" -> count == 1;
        case "alt", "par" -> count >= 2;
        default -> false;
    };
    if (!supported) {
        throw new UmlValidationException(
                UmlTypeKind.ELEMENT_PROPERTY,
                "CombinedFragment." + operator + ".operands",
                path);
    }
}
```

If `UmlTypeKind` does not yet have `ELEMENT_PROPERTY`, add the narrow enum value and diagnostic mapping in the same task.

- [ ] **Step 6: Run validation tests**

```bash
./mvnw -pl modules/uml -am test
```

Expected: all UML tests pass.

- [ ] **Step 7: Commit validation**

```bash
git add modules/uml/src/main/java/dev/dediren/uml/Uml.java modules/uml/src/test/java/dev/dediren/uml/UmlValidationTest.java
git commit -m "Validate UML sequence combined fragments"
```

## Task 3: Project Fragment Metadata Without Polluting Layout

**Files:**

- Modify: `modules/plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java`
- Modify: `modules/plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`

- [ ] **Step 1: Write failing projection tests**

Add tests:

```java
@Test
void projectsUmlSequenceFragmentRenderMetadata() throws Exception {
    PluginResult result = Main.executeForTesting(
            new String[]{"project", "--target", "render-metadata", "--view", "sequence-fragments-view"},
            fixture("fixtures/source/valid-uml-sequence-fragments.json"));

    JsonNode data = okData(result);

    assertThat(data.at("/nodes/cf-availability/type").asText()).isEqualTo("CombinedFragment");
    assertThat(data.at("/nodes/cf-availability/properties/operator").asText()).isEqualTo("alt");
    assertThat(data.at("/nodes/op-in-stock/type").asText()).isEqualTo("InteractionOperand");
    assertThat(data.at("/nodes/op-in-stock/properties/guard").asText()).isEqualTo("inStock");
    assertSchemaValid("schemas/render-metadata.schema.json", data);
}

@Test
void excludesSequenceFragmentSemanticNodesFromLayoutRequest() throws Exception {
    PluginResult result = Main.executeForTesting(
            new String[]{"project", "--target", "layout-request", "--view", "sequence-fragments-view"},
            fixture("fixtures/source/valid-uml-sequence-fragments.json"));

    JsonNode data = okData(result);

    assertThat(jsonTexts(data.get("nodes"), "id"))
            .doesNotContain("cf-availability", "op-in-stock");
    assertThat(jsonTexts(data.get("edges"), "id"))
            .contains("m1", "m2");
    assertSchemaValid("schemas/layout-request.schema.json", data);
}
```

- [ ] **Step 2: Run tests to verify failure**

```bash
./mvnw -pl modules/plugins/generic-graph -am test -Dtest=GenericGraphPluginTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: layout request still includes fragment/operand nodes, or render metadata is missing expected properties.

- [ ] **Step 3: Keep metadata projection unchanged for selected nodes**

Confirm `projectRenderMetadata()` already includes every selected view node and its UML properties. If the failing test shows metadata is present, do not refactor this method.

- [ ] **Step 4: Exclude source-only sequence fragment nodes from layout**

In `GenericGraphProjection.projectLayoutRequest()`, when `semanticProfile` is `uml` and selected view kind is `UML_SEQUENCE`, skip layout nodes whose source type is `CombinedFragment` or `InteractionOperand`:

```java
private static boolean isSourceOnlySequenceFragment(String semanticProfile, GenericGraphView selectedView, SourceNode node) {
    return "uml".equals(semanticProfile)
            && selectedView.kind() == GenericGraphViewKind.UML_SEQUENCE
            && (node.type().equals("CombinedFragment") || node.type().equals("InteractionOperand"));
}
```

Use this helper inside the node loop:

```java
if (isSourceOnlySequenceFragment(semanticProfile, selectedView, sourceNode)) {
    continue;
}
```

- [ ] **Step 5: Keep message-order constraints scoped to messages**

Confirm `projectLayoutConstraints()` still emits only lifeline order and message order. Do not add fragment-specific layout constraints in this slice unless a later real-render failure proves the need.

- [ ] **Step 6: Run generic graph tests**

```bash
./mvnw -pl modules/plugins/generic-graph -am test
```

Expected: generic graph tests pass.

- [ ] **Step 7: Commit projection**

```bash
git add modules/plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java modules/plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java
git commit -m "Project UML sequence fragments as render metadata"
```

## Task 4: Render Combined Fragment Frames

**Files:**

- Create: `fixtures/render-metadata/uml-sequence-fragments.json`
- Create: `fixtures/layout-result/uml-sequence-fragments.json`
- Modify: `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/UmlSequenceModel.java`
- Modify: `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/UmlSequenceRenderer.java`
- Modify: `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/RenderInputValidator.java`
- Modify: `modules/plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [ ] **Step 1: Create render fixtures**

Create `fixtures/render-metadata/uml-sequence-fragments.json` from the generic-graph render metadata output. It must include `nodes.cf-availability` and `nodes.op-in-stock`.

Create `fixtures/layout-result/uml-sequence-fragments.json` from the sequence layout output. It must include only layout-relevant nodes and message edges.

- [ ] **Step 2: Write failing renderer test**

Add a test in `MainTest`:

```java
@Test
void rendersUmlSequenceCombinedFragments() throws Exception {
    ObjectNode input = renderInput();
    input.set("layout_result", fixtureJson("fixtures/layout-result/uml-sequence-fragments.json"));
    input.set("render_metadata", fixtureJson("fixtures/render-metadata/uml-sequence-fragments.json"));

    PluginResult result = Main.executeForTesting(new String[]{"render"}, input.toString());
    String svg = okData(result).at("/content").asText();

    assertThat(svg).contains(
            "data-dediren-sequence-combined-fragment=\"cf-availability\"",
            "data-dediren-sequence-interaction-operator=\"alt\"",
            "data-dediren-sequence-operand=\"op-in-stock\"",
            "data-dediren-sequence-operand-guard=\"inStock\"",
            "data-dediren-sequence-operand-separator");
}
```

- [ ] **Step 3: Run renderer test to verify failure**

```bash
./mvnw -pl modules/plugins/svg-render -am test -Dtest=MainTest#rendersUmlSequenceCombinedFragments -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: SVG does not yet contain combined-fragment attributes.

- [ ] **Step 4: Extend `UmlSequenceModel`**

Add records:

```java
record SequenceCombinedFragment(
        String id,
        RenderMetadataSelector selector,
        String interactionId,
        String operator,
        List<String> operandIds,
        List<String> coveredLifelineIds,
        int sourceOrder) {
}

record SequenceOperand(
        String id,
        RenderMetadataSelector selector,
        String interactionId,
        String combinedFragmentId,
        int order,
        String guard,
        List<String> fragmentIds,
        int sourceOrder) {
}
```

Populate them from `metadata.nodes()` rather than `result.nodes()` so source-only semantic nodes can drive rendering.

- [ ] **Step 5: Add renderer frame calculation**

In `UmlSequenceRenderer`, calculate each combined fragment frame from:

- referenced message route points;
- referenced nested fragment frames;
- covered lifeline head x extents;
- the interaction frame as a clipping outer bound.

Use fixed padding constants:

```java
private static final double FRAGMENT_HORIZONTAL_PADDING = 20.0;
private static final double FRAGMENT_VERTICAL_PADDING = 18.0;
private static final double FRAGMENT_HEADER_HEIGHT = 24.0;
```

- [ ] **Step 6: Render combined fragments before lifeline/message foreground**

In `render()` call order, render fragments after interaction frames and before lifeline heads:

```java
renderInteractions(svg);
renderCombinedFragments(svg);
renderLifelineHeads(svg);
renderLifelineStems(svg);
```

Each frame should emit:

```xml
<g data-dediren-sequence-combined-fragment="cf-availability" data-dediren-sequence-interaction-operator="alt">
  <rect data-dediren-node-shape="uml_combined_fragment" .../>
  <path data-dediren-sequence-fragment-operator-tab="true" .../>
  <text data-dediren-sequence-fragment-operator="cf-availability" ...>alt</text>
  <line data-dediren-sequence-operand-separator="op-backorder" .../>
  <text data-dediren-sequence-operand="op-in-stock" data-dediren-sequence-operand-guard="inStock" ...>[inStock]</text>
</g>
```

- [ ] **Step 7: Validate render metadata**

In `RenderInputValidator`, accept `CombinedFragment` and `InteractionOperand` node metadata for UML sequence mode and validate required properties:

- `operator` text for `CombinedFragment`;
- `operands` array for `CombinedFragment`;
- `combined_fragment`, `order`, and `fragments` for `InteractionOperand`.

Return existing structured render metadata diagnostics; do not add raw exceptions.

- [ ] **Step 8: Run renderer tests**

```bash
./mvnw -pl modules/plugins/svg-render -am test
```

Expected: all SVG renderer tests pass.

- [ ] **Step 9: Commit renderer**

```bash
git add fixtures/render-metadata/uml-sequence-fragments.json fixtures/layout-result/uml-sequence-fragments.json modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/UmlSequenceModel.java modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/UmlSequenceRenderer.java modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/RenderInputValidator.java modules/plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "Render UML sequence combined fragments"
```

## Task 5: Export Combined Fragments To UML/XMI

**Files:**

- Create: `fixtures/export/uml-sequence-fragments.xmi`
- Modify: `modules/plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`
- Modify: `modules/plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`

- [ ] **Step 1: Write failing export test**

Add:

```java
@Test
void exportsUmlSequenceCombinedFragments() throws Exception {
    ObjectNode input = exportInput(
            fixtureJson("fixtures/source/valid-uml-sequence-fragments.json"),
            fixtureJson("fixtures/layout-result/uml-sequence-fragments.json"));

    PluginResult result = Main.executeForTesting(new String[]{"export"}, input.toString(), envWithXmiSchema());

    String xml = okData(result).at("/content").asText();
    assertThat(xml).isEqualTo(fixture("fixtures/export/uml-sequence-fragments.xmi"));
}
```

- [ ] **Step 2: Run export test to verify failure**

```bash
./mvnw -pl modules/plugins/uml-xmi-export -am test -Dtest=MainTest#exportsUmlSequenceCombinedFragments -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: output has messages but no combined fragment / operand XMI.

- [ ] **Step 3: Add export records**

Add local records in `Main.java`:

```java
private record CombinedFragmentExport(
        SourceNode node,
        String fragmentId,
        String operator,
        List<OperandExport> operands,
        List<String> coveredNodeIds,
        int sourceOrder) {
}

private record OperandExport(
        SourceNode node,
        String operandId,
        int order,
        String guard,
        List<String> fragmentIds,
        int sourceOrder) {
}
```

- [ ] **Step 4: Write nested XMI fragments**

In `writeInteraction()`, write lifelines first, then nested interaction fragments, then message elements.

Combined fragment shape:

```xml
<fragment xmi:type="uml:CombinedFragment" xmi:id="id-cf-availability" name="Availability" interactionOperator="alt" covered="id-customer id-service">
  <operand xmi:id="id-op-in-stock" name="In Stock">
    <guard xmi:type="uml:InteractionConstraint" xmi:id="id-op-in-stock-guard" name="inStock">
      <specification xmi:type="uml:OpaqueExpression" xmi:id="id-op-in-stock-guard-specification">
        <body>inStock</body>
      </specification>
    </guard>
    <fragment xmi:type="uml:MessageOccurrenceSpecification" .../>
  </operand>
</fragment>
```

Keep `<message>` elements direct children of `uml:Interaction`, pointing to send/receive occurrence ids wherever those occurrences are nested.

- [ ] **Step 5: Reject unsupported fragment operators for export**

If a source somehow contains `ignore`, `consider`, or an operator outside this slice, return:

```text
DEDIREN_UML_XMI_SEQUENCE_FRAGMENT_OPERATOR_UNSUPPORTED
```

with a JSON path to the fragment node's `properties.uml.operator`.

- [ ] **Step 6: Run export tests**

```bash
./mvnw -pl modules/plugins/uml-xmi-export -am test
```

Expected: all UML/XMI export tests pass.

- [ ] **Step 7: Commit export**

```bash
git add fixtures/export/uml-sequence-fragments.xmi modules/plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java modules/plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java
git commit -m "Export UML sequence combined fragments"
```

## Task 6: Add CLI Workflow And Documentation

**Files:**

- Modify: `apps/cli/src/test/java/dev/dediren/cli/MainTest.java`
- Modify: `README.md`
- Modify: `docs/agent-usage.md`

- [ ] **Step 1: Add CLI workflow test**

Add a second CLI integration test using `fixtures/source/valid-uml-sequence-fragments.json`. Assert:

- validate succeeds;
- project layout request excludes `cf-availability` from layout nodes;
- project render metadata includes `cf-availability`;
- layout succeeds;
- render SVG contains `data-dediren-sequence-combined-fragment`;
- export XMI contains `uml:CombinedFragment` and `interactionOperator="alt"`.

- [ ] **Step 2: Run CLI test to verify failure or pass**

```bash
./mvnw -pl apps/cli -am test -Dtest=MainTest#sequenceFragmentsFixtureRunsThroughDocumentedCliWorkflow -Dsurefire.failIfNoSpecifiedTests=false
```

Expected before implementation completion: failure at render or export assertions. Expected after Tasks 4-5: pass.

- [ ] **Step 3: Update README**

In `README.md`, update the UML section to state:

- Dediren supports `uml-sequence` MVP plus combined fragments `alt`, `opt`, `loop`, and `par`.
- Fragment authoring uses `CombinedFragment` and `InteractionOperand` nodes under `properties.uml`.
- `InteractionUse`, `GeneralOrdering`, `ignore`, `consider`, and UMLDI are not yet supported.
- Use `fixtures/source/valid-uml-sequence-fragments.json` for a command example.

- [ ] **Step 4: Update agent guide**

Mirror the README command-level guidance in `docs/agent-usage.md`. Keep it bundle-local and concise.

- [ ] **Step 5: Run docs/CLI checks**

```bash
./mvnw -pl apps/cli -am test
git diff --check
```

Expected: CLI tests pass and no whitespace errors.

- [ ] **Step 6: Commit docs and CLI**

```bash
git add apps/cli/src/test/java/dev/dediren/cli/MainTest.java README.md docs/agent-usage.md
git commit -m "Document UML sequence combined fragments"
```

## Task 7: Version Bump And Distribution Metadata

**Files:**

- Modify: root `pom.xml`
- Modify: all module child `pom.xml` parent versions
- Modify: `fixtures/plugins/*.manifest.json`
- Modify: `fixtures/source/*.json`
- Modify: `README.md`
- Modify: `docs/agent-usage.md`
- Modify: version assertion tests named in AGENTS.md

- [ ] **Step 1: Bump minor version to 0.22.0**

Run from the repo root:

```bash
./mvnw build-helper:parse-version versions:set -DnewVersion='${parsedVersion.majorVersion}.${parsedVersion.nextMinorVersion}.0' -DprocessAllModules=true -DgenerateBackupPoms=false
```

Expected: all POM versions update from `0.21.0` to `0.22.0`.

- [ ] **Step 2: Update non-POM version surfaces**

Replace `0.21.0` with `0.22.0` in:

- `fixtures/plugins/*.manifest.json`
- `fixtures/source/*.json` `required_plugins[].version`
- README bundle examples
- `docs/agent-usage.md` bundle examples
- `apps/cli/src/test/java/dev/dediren/cli/MainTest.java`
- `modules/contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`
- `modules/plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`
- `modules/plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`
- `modules/plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`
- `tools/dist/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

- [ ] **Step 3: Run stale-version search**

```bash
rg "0\\.21\\.0" pom.xml README.md docs/agent-usage.md fixtures/plugins fixtures/source apps/cli/src/test/java modules/contracts/src/test/java modules/plugins tools/dist/src/test/java
```

Expected: no stale `0.21.0` output except historical plan files under `docs/superpowers/plans` if they are intentionally outside the search lane.

- [ ] **Step 4: Run version tests**

```bash
./mvnw -pl modules/contracts,apps/cli,tools/dist -am test
```

Expected: version assertions pass.

- [ ] **Step 5: Commit version bump**

```bash
git add pom.xml apps/cli/pom.xml modules/archimate/pom.xml modules/contracts/pom.xml modules/core/pom.xml modules/plugins/archimate-oef-export/pom.xml modules/plugins/elk-layout/pom.xml modules/plugins/generic-graph/pom.xml modules/plugins/svg-render/pom.xml modules/plugins/uml-xmi-export/pom.xml modules/schema-cache/pom.xml modules/uml/pom.xml test-support/pom.xml testbeds/plugin-runtime/pom.xml tools/dist/pom.xml fixtures/plugins fixtures/source README.md docs/agent-usage.md apps/cli/src/test/java modules/contracts/src/test/java modules/plugins tools/dist/src/test/java
git commit -m "Bump version for UML sequence fragments"
```

If unrelated files exist, do not use the quoted glob command. Stage explicit paths reviewed with `git diff -- <path>`.

## Task 8: Audits And Final Verification

**Files:**

- Review all files touched in Tasks 1-7.

- [ ] **Step 1: Run focused verification lane**

```bash
./mvnw -pl modules/uml,modules/contracts,modules/plugins/generic-graph,modules/plugins/elk-layout,modules/plugins/svg-render,modules/plugins/uml-xmi-export,apps/cli -am test
```

Expected: all focused UML sequence fragment tests pass.

- [ ] **Step 2: Run full test suite**

```bash
./mvnw test
```

Expected: build success.

- [ ] **Step 3: Run distribution smoke**

```bash
./mvnw -pl tools/dist -am verify -Pdist-smoke
```

Expected: `distribution smoke test passed` for `dediren-agent-bundle-0.22.0-...tar.gz`.

- [ ] **Step 4: Run whitespace check**

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 5: Run required audits**

Use repo audit gates:

- `souroldgeezer-architecture:architecture-design` Review for UML render/export evidence.
- `souroldgeezer-audit:test-quality-audit` Deep for Java tests and fixtures.
- `souroldgeezer-audit:devsecops-audit` Quick for plugin process boundaries, dependency posture, docs, and artifacts.

Fix block findings. Fix or explicitly accept warn/info findings in the handoff, then rerun affected checks.

- [ ] **Step 6: Create release tag after integration**

Only after merge to `main` and green verification:

```bash
git tag -a v0.22.0 -m "Dediren 0.22.0"
```

Expected: annotated tag points to the integrated commit containing the version bump.

## Self-Review

- Spec coverage: This plan covers the refined next vertical slice: `alt`, `opt`, `loop`, and `par` combined fragments with source validation, projection, layout compatibility, SVG rendering, XMI export, CLI docs, versioning, distribution, and audits.
- Placeholder scan: No placeholders or unspecified implementation slots are intentionally left.
- Type consistency: Source property names are consistent across validation, render metadata, renderer, export, docs, and fixtures: `operator`, `operands`, `combined_fragment`, `order`, `guard`, and `fragments`.
- Scope control: `InteractionUse`, `GeneralOrdering`, `ignore`, `consider`, and UMLDI are explicitly deferred to later slices.

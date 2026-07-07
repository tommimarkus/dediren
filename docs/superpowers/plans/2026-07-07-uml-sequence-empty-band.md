# UML Sequence Empty-Band Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the large empty vertical band above the lifeline heads in every UML sequence diagram by making the interaction node's geometry wrap the lifeline+message content band.

**Architecture:** The post-ELK `SequenceLayoutConstraints.normalize` pass already synthesizes sequence geometry (lifeline columns, message rows). Give it ownership of the interaction node too: tag the interaction node with a `role` in the projection, then have `normalize` reposition any `role="interaction"` node to the content bounding box. The renderer is unchanged — its existing content-clamping frame math produces a tight frame once the interaction node is sane.

**Tech Stack:** Java 21, Maven Wrapper (`./mvnw`), Eclipse ELK, JUnit 5, AssertJ (generic-graph tests) / JUnit assertions (elk-layout tests).

## Global Constraints

- Java formatted by google-java-format (GOOGLE style) via Spotless; run `./mvnw -Pquality spotless:apply` before committing Java changes.
- Plugins may depend only on `contracts` (not `core`); this change stays within `plugins/generic-graph` and `plugins/elk-layout`.
- No public schema or contract change: `role` is an existing optional field on layout nodes.
- Scope is the empty-band defect only. Combined-fragment chrome collisions are out of scope (separate design).
- Spec: `docs/superpowers/specs/2026-07-07-uml-sequence-empty-band-design.md`.

---

### Task 1: Projection tags the interaction node with `role="interaction"`

**Files:**
- Modify: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java` (method `layoutRole`, ~lines 231-240)
- Test: `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java` (method `projectsLifelineRoleOntoSequenceLayoutNodes`, ~line 497-510)

**Interfaces:**
- Produces: layout-request `Interaction` nodes now carry `role="interaction"` (previously no `role`). Task 2 relies on this role to identify the interaction node.

- [ ] **Step 1: Update the test to expect the new role**

In `GenericGraphPluginTest.projectsLifelineRoleOntoSequenceLayoutNodes`, replace the current line 508:

```java
    assertThat(layoutRequestNode(data, "interaction-place-order").has("role")).isFalse();
```

with:

```java
    assertThat(layoutRequestNode(data, "interaction-place-order").at("/role").asText())
        .isEqualTo("interaction");
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest#projectsLifelineRoleOntoSequenceLayoutNodes -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — actual role is empty/absent, expected `"interaction"`.

- [ ] **Step 3: Add the interaction role in `layoutRole`**

In `GenericGraphProjection.layoutRole`, add an `Interaction` branch. The method becomes:

```java
  private static String layoutRole(String semanticProfile, String sourceType) {
    if ("Lifeline".equals(sourceType)) {
      return "lifeline";
    }
    if ("Interaction".equals(sourceType)) {
      return "interaction";
    }
    if (semanticProfile.equals("archimate") && Archimate.isRelationshipConnectorType(sourceType)) {
      return "junction";
    }
    return null;
  }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest#projectsLifelineRoleOntoSequenceLayoutNodes -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Run the full generic-graph suite to catch collateral role assertions**

Run: `./mvnw -q -pl plugins/generic-graph -am test`
Expected: PASS. If another test asserts the interaction node is role-less, update it to expect `"interaction"` (the interaction node is now consistently role-tagged).

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java \
        plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java
git commit -m "feat(generic-graph): tag UML sequence interaction node with interaction role"
```

---

### Task 2: Normalize wraps the interaction node around the content band

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/SequenceLayoutConstraints.java` (method `normalize`, ~lines 95-107; add new private method)
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java` (helper `sequenceLayoutRequest`, test `sequenceLayoutPreservesLifelineRoleOnNodes`, plus a new test)

**Interfaces:**
- Consumes: `Interaction` layout node carries `role="interaction"` (Task 1). In the elk-layout unit test the request is hand-built, so the helper must set that role explicitly.
- Produces: `LayoutResult` where the `role="interaction"` node's box equals the bounding box of the lifelines plus all message points: `x = min(lifeline.x)`, `y = min(lifeline.y)`, `width = max(lifeline.x+width) - x`, `height = max(bottom-most message point y, lifeline head bottom) - y`.

- [ ] **Step 1: Give the test helper's interaction node the interaction role**

In `ElkLayoutEngineTest.sequenceLayoutRequest()`, change the interaction node from the 5-arg constructor to the 6-arg constructor with the role. Replace:

```java
            new LayoutNode(
                "interaction-place-order", "Place Order", "interaction-place-order", 360.0, 260.0),
```

with:

```java
            new LayoutNode(
                "interaction-place-order",
                "Place Order",
                "interaction-place-order",
                360.0,
                260.0,
                "interaction"),
```

- [ ] **Step 2: Update the role-preservation test to expect the interaction role**

In `ElkLayoutEngineTest.sequenceLayoutPreservesLifelineRoleOnNodes`, replace the interaction assertion (currently `assertNull(nodeById(result, "interaction-place-order").role(), "interaction frame should stay role-less");`) with:

```java
    assertEquals(
        "interaction",
        nodeById(result, "interaction-place-order").role(),
        "interaction frame keeps its interaction role");
```

- [ ] **Step 3: Write the failing wrapping test**

Add this test to `ElkLayoutEngineTest` (next to the other sequence tests):

```java
  @Test
  void wrapsInteractionNodeAroundLifelineBand() {
    LayoutResult result = new ElkLayoutEngine().layout(sequenceLayoutRequest());

    LaidOutNode interaction = nodeById(result, "interaction-place-order");
    LaidOutNode customer = nodeById(result, "customer");
    LaidOutNode service = nodeById(result, "service");
    double lifelineTop = Math.min(customer.y(), service.y());

    // Interaction top hugs the lifeline heads instead of floating a band above them.
    assertEquals(
        lifelineTop, interaction.y(), 0.5, "interaction top should equal the lifeline band top");
    // Interaction spans every lifeline horizontally.
    assertTrue(
        interaction.x() <= Math.min(customer.x(), service.x()) + 0.5,
        "interaction left edge spans the lifelines");
    assertTrue(
        interaction.x() + interaction.width()
            >= Math.max(customer.x() + customer.width(), service.x() + service.width()) - 0.5,
        "interaction right edge spans the lifelines");
    // Interaction reaches below the head band to enclose the message rows.
    assertTrue(
        interaction.y() + interaction.height() > lifelineTop + customer.height(),
        "interaction encloses the message rows below the heads");
  }
```

Confirm `LaidOutNode` is importable in the test (the file already uses `import dev.dediren.contracts.layout.*;`).

- [ ] **Step 4: Run the new test to verify it fails**

Run: `./mvnw -q -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#wrapsInteractionNodeAroundLifelineBand -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `interaction.y()` is ELK's stray disconnected-component position, well above the lifeline band; the `assertEquals(lifelineTop, interaction.y(), 0.5)` assertion fails.

- [ ] **Step 5: Implement interaction-node wrapping in `normalize`**

In `SequenceLayoutConstraints`, replace the `normalize` method:

```java
  LayoutResult normalize(LayoutResult result) {
    if (!active()) {
      return result;
    }
    List<LaidOutNode> normalizedNodes = normalizedLifelineNodes(result.nodes());
    List<LaidOutEdge> normalizedEdges =
        normalizedMessageEdges(result.edges(), nodesById(normalizedNodes));
    List<LaidOutNode> wrappedNodes = normalizedInteractionNodes(normalizedNodes, normalizedEdges);
    return new LayoutResult(
        result.layoutResultSchemaVersion(),
        result.viewId(),
        wrappedNodes,
        normalizedEdges,
        result.groups(),
        result.warnings());
  }

  private List<LaidOutNode> normalizedInteractionNodes(
      List<LaidOutNode> nodes, List<LaidOutEdge> edges) {
    Map<String, LaidOutNode> byId = nodesById(nodes);
    List<LaidOutNode> lifelines = new ArrayList<>();
    for (String id : lifelineOrder) {
      LaidOutNode node = byId.get(id);
      if (node != null) {
        lifelines.add(node);
      }
    }
    if (lifelines.isEmpty()) {
      return nodes;
    }

    double top = lifelines.stream().mapToDouble(LaidOutNode::y).min().orElse(0.0);
    double left = lifelines.stream().mapToDouble(LaidOutNode::x).min().orElse(0.0);
    double right =
        lifelines.stream().mapToDouble(node -> node.x() + node.width()).max().orElse(left);
    double bottom =
        lifelines.stream().mapToDouble(node -> node.y() + node.height()).max().orElse(top);
    for (LaidOutEdge edge : edges) {
      for (Point point : edge.points()) {
        bottom = Math.max(bottom, point.y());
      }
    }

    List<LaidOutNode> normalized = new ArrayList<>();
    for (LaidOutNode node : nodes) {
      if ("interaction".equals(node.role())) {
        normalized.add(
            new LaidOutNode(
                node.id(),
                node.sourceId(),
                node.projectionId(),
                left,
                top,
                right - left,
                bottom - top,
                node.label(),
                node.role()));
      } else {
        normalized.add(node);
      }
    }
    return normalized;
  }
```

(`ArrayList`, `List`, `Map`, `LaidOutNode`, `LaidOutEdge`, and `Point` are already imported in this file.)

- [ ] **Step 6: Run the new test to verify it passes**

Run: `./mvnw -q -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#wrapsInteractionNodeAroundLifelineBand -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Run the full elk-layout suite**

Run: `./mvnw -q -pl plugins/elk-layout -am test`
Expected: PASS (including the updated `sequenceLayoutPreservesLifelineRoleOnNodes` and the unchanged lifeline/message-order and messages-below-heads tests).

- [ ] **Step 8: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/SequenceLayoutConstraints.java \
        plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "fix(elk-layout): wrap UML sequence interaction node around the content band"
```

---

### Task 3: End-to-end verification and fixture reconciliation

**Files:**
- Verify only (no source change expected): `plugins/render/src/test/java/dev/dediren/plugins/render/SequenceFragmentAlignmentTest.java`, `plugins/render/src/test/java/dev/dediren/plugins/render/SvgAppearanceAuditTest.java`, `fixtures/layout-result/uml-sequence-*.json`

**Interfaces:**
- Consumes: the Task 1 + Task 2 behavior end to end (projection → elk-layout → render).

- [ ] **Step 1: Run the render/CLI lanes to confirm nothing regressed**

Run: `./mvnw -q -pl plugins/render,cli -am test`
Expected: PASS. The static `fixtures/layout-result/uml-sequence-*.json` already encode a wrapped interaction (e.g. `uml-sequence-basic.json` has interaction `y=32`, lifelines `y=72`), so render tests consuming them are unaffected. If any appearance/alignment test asserted the pre-fix stray-box geometry, update the assertion to the wrapped geometry and re-run.

- [ ] **Step 2: Build a fresh bundle**

Run: `./mvnw -q -pl dist-tool -am verify -Pdist-build -DskipTests`
Expected: exit 0; bundle at `dist/dediren-agent-bundle-<version>/bin/dediren`.

- [ ] **Step 3: Re-render the uljas sequence views and confirm the band is gone**

Use the local uljas model at `/home/souroldgeezer/repos/uljas/docs/architecture/uljas.dediren/`. For each of `seq-submission seq-balance seq-change seq-maksukielto`, run project (layout-request + render-metadata) → layout → render, then check the interaction frame top hugs the lifelines. Concretely, verify the layout-result interaction node now sits at the lifeline band (not `y=12`):

```bash
BUNDLE=$(ls -d dist/dediren-agent-bundle-* | grep -v '\.tar\.gz$' | sort | tail -1)
export DEDIREN_BUNDLE_ROOT="$BUNDLE"
IN=/home/souroldgeezer/repos/uljas/docs/architecture/uljas.dediren/model-uml.json
for V in seq-submission seq-balance seq-change seq-maksukielto; do
  "$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view "$V" --input "$IN" 2>/dev/null \
    | "$BUNDLE/bin/dediren" layout --plugin elk-layout --input /dev/stdin 2>/dev/null \
    | python3 -c 'import json,sys; d=json.load(sys.stdin);
def lr(o):
 if isinstance(o,dict):
  return o if "nodes" in o and "edges" in o else next((r for v in o.values() if (r:=lr(v))),None)
 return None
r=lr(d); ix=[n for n in r["nodes"] if n.get("role")=="interaction"][0]; ll=min(n["y"] for n in r["nodes"] if n.get("role")=="lifeline")
print(f"'"$V"' interaction.y={ix[chr(39)+chr(121)+chr(39)]} lifeline_top={ll} gap={ll-ix[chr(34)+chr(121)+chr(34)]:.0f}")'
done
```

Expected: for every view, `gap` is ~0 (interaction top equals the lifeline band top), not ~300.

- [ ] **Step 4: Broad regression run**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 5: Report artifacts, do not stage them**

The bundle under `dist/` and any re-rendered SVGs are generated outputs — report their paths, do not commit them (per repo Git Hygiene). No commit in this task unless Step 1 required a test-assertion update, in which case:

```bash
./mvnw -Pquality spotless:apply
git add <the-updated-test-file>
git commit -m "test(render): update sequence appearance assertions for wrapped interaction frame"
```

---

## Self-Review

**Spec coverage:**
- Root cause (disconnected interaction node) → addressed by Task 2 wrapping. ✓
- Decision: fix in normalize, layout_result becomes correct → Task 2. ✓
- Decision: identify interaction by role → Task 1. ✓
- Decision: renderer unchanged → confirmed (no render source change; Task 3 verifies). ✓
- Testing: failing real-engine test → Task 2 Step 3-4; golden-fixture oracle + reconciliation → Task 3. ✓
- Out of scope (fragment collisions, ELK hierarchy, multi-interaction) → not touched. ✓

**Placeholder scan:** No TBD/TODO; all code shown in full. ✓

**Type consistency:** `layoutRole(String, String)` returns `String`; `LaidOutNode` 9-arg constructor `(id, sourceId, projectionId, x, y, width, height, label, role)` matches its use in `normalizedLifelineNodes`; `LayoutNode` 6-arg constructor `(id, label, sourceId, widthHint, heightHint, role)` matches the existing helper usage; `nodeById`, `nodesById`, `lifelineOrder`, `Point`, `LaidOutEdge` all exist in their files. ✓

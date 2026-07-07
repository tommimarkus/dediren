# UML Sequence Combined-Fragment Chrome Spacing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Task 4 is an empirical render-tuning loop.

**Goal:** Stop combined-fragment chrome (operator header, operand separators, guards) from colliding with message labels inside `alt`/`opt`/`loop`/`par` fragments, by reserving vertical room at fragment/operand boundaries.

**Architecture:** Projection marks the messages that open a fragment or a non-first operand; the layout-normalize pass reserves extra vertical room before those messages; the renderer's vertical chrome-spacing constants grow to match. Horizontal geometry and the render computation approach are unchanged.

**Tech Stack:** Java 21, Maven Wrapper, Eclipse ELK, JUnit 5, AssertJ.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-07-uml-sequence-fragment-chrome-design.md`.
- Plugins depend only on `contracts` (not `core`); changes stay within `plugins/generic-graph`, `plugins/elk-layout`, `plugins/render`.
- No public schema/contract change: `LayoutConstraint(id, kind, subjects)` is existing; two new `kind` values (`uml.sequence.fragment-open`, `uml.sequence.operand-open`) are added.
- Java = google-java-format GOOGLE style; run `./mvnw -Pquality spotless:apply` before each commit.
- Maven tests fail under the command sandbox (@TempDir on read-only /tmp) — run every `./mvnw ...` with the sandbox disabled.
- The gap/padding constants (`FRAGMENT_OPEN_GAP`, `OPERAND_OPEN_GAP`, renderer `FRAGMENT_VERTICAL_PADDING`) are coupled and get their final values in Task 4's render loop; Tasks 2–3 seed them at 30 / 40 / 34.

---

### Task 1: Projection emits fragment-open / operand-open constraints

**Files:**
- Modify: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java` (`projectLayoutConstraints` + two new private helpers)
- Test: `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`

**Interfaces:**
- Produces two new `LayoutConstraint`s on UML_SEQUENCE layout-requests: `uml.sequence.fragment-open` (first message of each fragment) and `uml.sequence.operand-open` (first message of each non-first operand). Task 2 consumes them by `kind`.

- [ ] **Step 1: Write the failing test**

Add to `GenericGraphPluginTest` (mirrors the existing `projectsBasicViewToLayoutRequest`/constraint tests; the fragments source fixture is `fixtures/source/valid-uml-sequence-fragments.json`, view id `sequence-view`):

```java
  @Test
  void projectsFragmentAndOperandOpenConstraintsForSequenceFragments() throws Exception {
    PluginResult result =
        Main.executeForTesting(
            new String[] {"project", "--target", "layout-request", "--view", "sequence-view"},
            fixture("fixtures/source/valid-uml-sequence-fragments.json"));

    JsonNode data = okData(result);

    assertThat(constraintSubjects(data, "uml.sequence.fragment-open"))
        .containsExactlyInAnyOrder("m1", "m5", "m7", "m9");
    assertThat(constraintSubjects(data, "uml.sequence.operand-open"))
        .containsExactlyInAnyOrder("m3", "m11");
  }
```

Add this helper next to the other private helpers in the test class (follow how the existing constraint test reads `data.get("constraints")`):

```java
  private static List<String> constraintSubjects(JsonNode data, String kind) {
    for (JsonNode constraint : data.get("constraints")) {
      if (kind.equals(constraint.at("/kind").asText())) {
        List<String> subjects = new ArrayList<>();
        for (JsonNode subject : constraint.at("/subjects")) {
          subjects.add(subject.asText());
        }
        return subjects;
      }
    }
    return List.of();
  }
```

Ensure `java.util.List` and `java.util.ArrayList` are imported in the test.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest#projectsFragmentAndOperandOpenConstraintsForSequenceFragments -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — the two constraints are absent (subjects empty).

- [ ] **Step 3: Implement the constraint emission**

In `GenericGraphProjection.projectLayoutConstraints`, replace the final `return List.of(<lifeline-order>, <message-order>);` with the block below (keep the two existing constraints; append the two new ones). `messageIds` and `selectedNodeIds` are already in scope in that method.

```java
    var messageIdSet = new java.util.HashSet<>(messageIds);
    var nodesById =
        source.nodes().stream()
            .collect(java.util.stream.Collectors.toMap(SourceNode::id, node -> node, (a, b) -> a));
    var fragmentOpenIds = new ArrayList<String>();
    var operandOpenIds = new ArrayList<String>();
    for (SourceNode node : source.nodes()) {
      if (!selectedNodeIds.contains(node.id()) || !"CombinedFragment".equals(node.type())) {
        continue;
      }
      JsonNode uml = node.properties().get("uml");
      if (uml == null) {
        continue;
      }
      var operandIds = new ArrayList<String>();
      for (JsonNode operand : uml.path("operands")) {
        operandIds.add(operand.asText());
      }
      operandIds.sort(Comparator.comparingInt(id -> operandOrder(nodesById.get(id))));
      for (int index = 0; index < operandIds.size(); index++) {
        String firstMessage =
            firstMessageOfOperand(
                nodesById.get(operandIds.get(index)), nodesById, messageIdSet, new HashSet<>());
        if (firstMessage == null) {
          continue;
        }
        if (index == 0) {
          fragmentOpenIds.add(firstMessage);
        } else {
          operandOpenIds.add(firstMessage);
        }
      }
    }

    return List.of(
        new LayoutConstraint(
            selectedView.id() + ".uml.sequence.lifeline-order",
            "uml.sequence.lifeline-order",
            lifelineIds),
        new LayoutConstraint(
            selectedView.id() + ".uml.sequence.message-order",
            "uml.sequence.message-order",
            messageIds),
        new LayoutConstraint(
            selectedView.id() + ".uml.sequence.fragment-open",
            "uml.sequence.fragment-open",
            fragmentOpenIds),
        new LayoutConstraint(
            selectedView.id() + ".uml.sequence.operand-open",
            "uml.sequence.operand-open",
            operandOpenIds));
```

Add these private helpers to `GenericGraphProjection` (near `umlMessageSequence`):

```java
  private static int operandOrder(SourceNode operand) {
    if (operand == null) {
      return Integer.MAX_VALUE;
    }
    JsonNode uml = operand.properties().get("uml");
    JsonNode order = uml == null ? null : uml.get("order");
    return order != null && order.isNumber() ? order.asInt() : Integer.MAX_VALUE;
  }

  private static String firstMessageOfOperand(
      SourceNode operand,
      java.util.Map<String, SourceNode> nodesById,
      java.util.Set<String> messageIds,
      java.util.Set<String> visiting) {
    if (operand == null) {
      return null;
    }
    JsonNode uml = operand.properties().get("uml");
    if (uml == null) {
      return null;
    }
    for (JsonNode member : uml.path("fragments")) {
      String memberId = member.asText();
      if (messageIds.contains(memberId)) {
        return memberId;
      }
      SourceNode nested = nodesById.get(memberId);
      if (nested != null && "CombinedFragment".equals(nested.type()) && visiting.add(memberId)) {
        JsonNode nestedUml = nested.properties().get("uml");
        if (nestedUml == null) {
          continue;
        }
        var nestedOperands = new ArrayList<String>();
        for (JsonNode operandRef : nestedUml.path("operands")) {
          nestedOperands.add(operandRef.asText());
        }
        nestedOperands.sort(Comparator.comparingInt(id -> operandOrder(nodesById.get(id))));
        for (String nestedOperandId : nestedOperands) {
          String found =
              firstMessageOfOperand(
                  nodesById.get(nestedOperandId), nodesById, messageIds, visiting);
          if (found != null) {
            return found;
          }
        }
      }
    }
    return null;
  }
```

Ensure imports exist for `java.util.HashSet` and `Comparator` (the file already imports `Comparator`); use fully-qualified `java.util.*` where shown or add imports.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest#projectsFragmentAndOperandOpenConstraintsForSequenceFragments -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Full generic-graph suite + format + commit**

Run: `./mvnw -q -pl plugins/generic-graph -am test` (expect PASS — the existing `projectsBasicViewToLayoutRequest` asserts the constraint LIST by kind order; if it uses `containsExactly` on kinds it will now see 4 kinds — update it to include `uml.sequence.fragment-open`, `uml.sequence.operand-open` in order, or relax to `contains`). Then:

```bash
./mvnw -Pquality spotless:apply
git add plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java \
        plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java
git commit -m "feat(generic-graph): emit UML sequence fragment-open/operand-open constraints"
```

---

### Task 2: Normalize reserves leading gaps for marked messages

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/SequenceLayoutConstraints.java`
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java`

**Interfaces:**
- Consumes `uml.sequence.fragment-open` / `uml.sequence.operand-open` constraints (Task 1). Produces message Y-slots with an extra leading gap before marked messages.

- [ ] **Step 1: Write the failing test**

Add to `ElkLayoutEngineTest` a request builder + test. The builder reuses the sequence shape but adds a fragment-open marker on `m3`:

```java
  @Test
  void reservesLeadingGapBeforeFragmentOpenMessages() {
    LayoutResult result = new ElkLayoutEngine().layout(fragmentGapSequenceRequest());

    double y1 = messageY(result, "m1");
    double y2 = messageY(result, "m2");
    double y3 = messageY(result, "m3");

    double plainStep = y2 - y1;
    double gappedStep = y3 - y2;
    assertTrue(
        gappedStep > plainStep + 20.0,
        "fragment-open message m3 must reserve extra leading room (plain="
            + plainStep
            + ", gapped="
            + gappedStep
            + ")");
  }

  private static double messageY(LayoutResult result, String id) {
    for (LaidOutEdge edge : result.edges()) {
      if (edge.id().equals(id)) {
        return edge.points().get(0).y();
      }
    }
    throw new AssertionError("no message " + id);
  }

  private static LayoutRequest fragmentGapSequenceRequest() {
    return new LayoutRequest(
        "layout-request.schema.v1",
        "sequence-view",
        List.of(
            new LayoutNode("customer", "Customer", "customer", 140.0, 48.0, "lifeline"),
            new LayoutNode(
                "interaction-place-order",
                "Place Order",
                "interaction-place-order",
                360.0,
                260.0,
                "interaction"),
            new LayoutNode("service", "Order Service", "service", 140.0, 48.0, "lifeline")),
        List.of(
            new LayoutEdge("m1", "customer", "service", "a", "m1", "Message"),
            new LayoutEdge("m2", "service", "customer", "b", "m2", "Message"),
            new LayoutEdge("m3", "customer", "service", "c", "m3", "Message")),
        List.of(),
        List.of(
            new LayoutConstraint(
                "sequence-view.uml.sequence.lifeline-order",
                "uml.sequence.lifeline-order",
                List.of("customer", "service")),
            new LayoutConstraint(
                "sequence-view.uml.sequence.message-order",
                "uml.sequence.message-order",
                List.of("m1", "m2", "m3")),
            new LayoutConstraint(
                "sequence-view.uml.sequence.fragment-open",
                "uml.sequence.fragment-open",
                List.of("m3"))),
        readableSequencePreferences());
  }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#reservesLeadingGapBeforeFragmentOpenMessages -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — without the gap, `gappedStep == plainStep == MESSAGE_Y_STEP (24)`.

- [ ] **Step 3: Implement gap reservation**

In `SequenceLayoutConstraints`, add constants and fields:

```java
  private static final String FRAGMENT_OPEN_KIND = "uml.sequence.fragment-open";
  private static final String OPERAND_OPEN_KIND = "uml.sequence.operand-open";
  private static final double FRAGMENT_OPEN_GAP = 30.0; // tuned in the render loop (Task 4)
  private static final double OPERAND_OPEN_GAP = 40.0; // tuned in the render loop (Task 4)
```

Add `private final java.util.Set<String> fragmentOpenIds;` and `operandOpenIds;` fields, populate them in the private constructor from new constructor params, and read them in `from(LayoutRequest)` alongside the existing kinds (collect `constraint.subjects()` for the two new kinds; default to empty). Then replace the `headBottom`-branch loop in `normalizedMessageYSlots`:

```java
    if (Double.isFinite(headBottom)) {
      List<Double> ySlots = new ArrayList<>();
      double y = headBottom + MESSAGE_HEAD_GAP;
      for (int index = 0; index < orderedMessages.size(); index++) {
        if (index > 0) {
          y += MESSAGE_Y_STEP;
        }
        String id = orderedMessages.get(index).id();
        if (fragmentOpenIds.contains(id)) {
          y += FRAGMENT_OPEN_GAP;
        } else if (operandOpenIds.contains(id)) {
          y += OPERAND_OPEN_GAP;
        }
        ySlots.add(y);
      }
      return ySlots;
    }
```

- [ ] **Step 4: Run to verify it passes**

Run the focused test (Step 2 command). Expected: PASS (gappedStep = 24 + 30 = 54 > 44).

- [ ] **Step 5: Full elk-layout suite + format + commit**

Run: `./mvnw -q -pl plugins/elk-layout -am test` (expect PASS — the existing sequence tests use no fragment-open/operand-open constraints, so their spacing is unchanged). Then:

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/SequenceLayoutConstraints.java \
        plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "fix(elk-layout): reserve vertical room before UML sequence fragment/operand starts"
```

---

### Task 3: Renderer vertical chrome padding

**Files:**
- Modify: `plugins/render/src/main/java/dev/dediren/plugins/render/node/uml/UmlSequenceRenderer.java`

**Interfaces:**
- Consumes the reserved gaps (Tasks 1–2). Produces a fragment frame whose header band + operand-0 guard clear the first message.

- [ ] **Step 1: Raise `FRAGMENT_VERTICAL_PADDING`**

Change the constant from `18.0` to `34.0` (seed value; Task 4 finalizes it):

```java
  private static final double FRAGMENT_VERTICAL_PADDING = 34.0;
```

- [ ] **Step 2: Build + smoke the render suite**

Run: `./mvnw -q -pl plugins/render,cli -am test`
Expected: PASS (the golden fragments fixture's messages are widely spaced, so the taller padding does not introduce a new failure; if a coordinate-exact appearance assertion breaks, note it for Task 4 reconciliation).

- [ ] **Step 3: Commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/render/src/main/java/dev/dediren/plugins/render/node/uml/UmlSequenceRenderer.java
git commit -m "fix(render): widen UML sequence fragment vertical padding for guard/header room"
```

---

### Task 4: Regenerate fixture, add collision test, tune constants (render loop)

**Files:**
- Modify: `plugins/render/src/test/java/dev/dediren/plugins/render/SequenceFragmentAlignmentTest.java`
- Regenerate: `fixtures/layout-result/uml-sequence-fragments.json` (only if its message spacing must reflect the reserved gaps for the collision test to bite)
- Possibly re-tune: the three constants in Tasks 2–3.

**Interfaces:** consumes Tasks 1–3 end to end.

- [ ] **Step 1: Add the collision assertion**

Add to `SequenceFragmentAlignmentTest` a test that, for the rendered fragments fixture, asserts no operand separator line and no operand guard label sits within a tolerance band of any message label Y. Reuse the file's `fragmentBox`/`groupWithAttribute` helpers; collect `<line data-dediren-sequence-operand-separator>` `y1`, `<text data-dediren-sequence-operand-guard>` `y`, and `<text data-dediren-sequence-message-label>` `y`, and assert every separator/guard is at least `MESSAGE_LABEL_CLEARANCE` (e.g. 10.0) from every message label Y.

- [ ] **Step 2: Determine whether the golden fixture needs regeneration**

Run the new collision test against the current golden `uml-sequence-fragments.json`. If it passes (the golden spacing already clears chrome), no regeneration is needed — record that and skip to Step 4. If it fails because the golden message Ys are on the old tight lattice, regenerate the fixture from the real engine:

```bash
BUNDLE=$(ls -d dist/dediren-agent-bundle-* | grep -v '\.tar\.gz$' | sort | tail -1)
"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view sequence-view \
  --input fixtures/source/valid-uml-sequence-fragments.json \
  | "$BUNDLE/bin/dediren" layout --plugin elk-layout --input /dev/stdin \
  | jq '.data.layout_result // .' > fixtures/layout-result/uml-sequence-fragments.json
```

Then check every OTHER test that reads this fixture (`grep -rl uml-sequence-fragments fixtures plugins */src/test`) still passes; reconcile any coordinate-exact assertion.

- [ ] **Step 3: Tune the coupled constants against real renders**

Render both the repo fragments fixture and the uljas `seq-balance` (`alt`) view. Rasterize (resvg) and probe geometry for separator/guard/header-vs-message-label overlaps. Adjust `FRAGMENT_VERTICAL_PADDING` (render), `FRAGMENT_OPEN_GAP`, and `OPERAND_OPEN_GAP` (normalize) until: (a) the collision test passes, and (b) the uljas `seq-balance` shows no chrome overlapping any message label. Expected landing zone from the spec math: `FRAGMENT_VERTICAL_PADDING ≈ 34`, `FRAGMENT_OPEN_GAP ≈ FRAGMENT_VERTICAL_PADDING + ~12`, `OPERAND_OPEN_GAP ≈ 44–52`.

- [ ] **Step 4: Full verification + commit**

Run: `./mvnw test` (full reactor, sandbox disabled) — expect PASS. Re-render the four uljas sequence views; confirm `seq-balance` is collision-free and the other three are unchanged. Commit:

```bash
./mvnw -Pquality spotless:apply
git add plugins/render/src/test/java/dev/dediren/plugins/render/SequenceFragmentAlignmentTest.java \
        plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/SequenceLayoutConstraints.java \
        plugins/render/src/main/java/dev/dediren/plugins/render/node/uml/UmlSequenceRenderer.java
# add fixtures/layout-result/uml-sequence-fragments.json only if regenerated in Step 2
git commit -m "test(render): assert combined-fragment chrome clears message labels; finalize gaps"
```

---

## Self-Review

**Spec coverage:** projection constraints (Task 1), normalize gaps (Task 2), renderer vertical padding (Task 3), collision test + fixture regen + constant tuning (Task 4) — all spec sections mapped. ✓
**Placeholder scan:** mechanism code is complete; Task 4 is intentionally an empirical loop with explicit pass criteria (collision test + real-render check), not a placeholder. ✓
**Type consistency:** `LayoutConstraint(String,String,List<String>)`, `SourceNode.properties().get("uml")`, `LayoutEdge`/`LayoutNode` constructors, and `ElkLayoutEngine().layout(...)` all match existing usage. The new normalize fields thread through the private constructor and `from(...)`. ✓

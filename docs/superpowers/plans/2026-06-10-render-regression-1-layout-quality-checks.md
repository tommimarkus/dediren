# Render Regression Test Set — Plan 1: Layout-Level Quality Checks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the backend-neutral `LayoutQuality` checker (and thus `validate-layout`) with junction-aware, label-aware, and crossing-aware checks, with a false-positive sweep over every known-good layout fixture, so ELK-derived layout defects fail the product quality gate instead of reaching rendered output.

**Architecture:** All new geometry checks live in `core` `LayoutQuality` (per the repo rule: backend-neutral quality checks belong in `core`). Junction identification reaches the layout result by propagating a `"junction"` role in the `generic-graph` projection, mirroring the existing `"lifeline"` role. New aggregate counts go on `LayoutQualityReport` (serialized snake_case into the `validate-layout` ok-envelope `data`); the one hard geometric impossibility (junction detached from its incident route) is an ERROR diagnostic like the existing endpoint checks.

**Tech Stack:** Java 21, Maven Wrapper, JUnit 5 + AssertJ, Jackson (snake_case envelope mapper from `dev.dediren.contracts.json.JsonSupport`).

**Spec:** `docs/superpowers/specs/2026-06-10-archimate-render-regression-test-set-design.md` (sections 2 and 7).

**Plan sequence:** This is plan 1 of 4. Plan 2 (curated ArchiMate fixtures + golden backstop), plan 3 (SVG geometry harness + notation conformance), and plan 4 (stress profile + regression URL intake) are authored after this plan lands.

**Execution notes:**
- Maven test runs need the sandbox disabled (read-only `/tmp` breaks JUnit `@TempDir`).
- Module-scoped runs need `-am -Dsurefire.failIfNoSpecifiedTests=false` (sibling modules are not installed).
- Stage with explicit paths only; never `git add -A`.
- Severity philosophy (spec section 7): hard geometric impossibilities are ERROR diagnostics; estimate-based or aesthetic findings are report counts. `edge_crossing_count` is informational only and must NOT flip `status` to `warning`.
- ELK-first contingency: if a check fires on a real ELK layout during the sweep (Task 7), do not weaken the check silently and do not post-process routes. Inspect the geometry; either it is a real defect (fix via ELK options/graph structure in a separate change, pin the fixture) or the check needs a diagram-kind scoping rule or documented tolerance, decided explicitly.

---

### Task 1: Junction role propagation in generic-graph projection

`LayoutQuality` sees only the layout result, which has no node types. Propagate role `"junction"` for ArchiMate relationship-connector node types (`AndJunction`, `OrJunction`), exactly as `"lifeline"` is propagated today. ELK already passes `node.role()` through to `LaidOutNode`. Junction size hints (28×28) already exist in `GenericGraphLayoutSizing`.

**Files:**
- Create: `fixtures/source/valid-archimate-junction.json`
- Modify: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java:97-104` (call site) and `:211-215` (`layoutRole`)
- Test: `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`

- [ ] **Step 1: Add the junction source fixture**

Create `fixtures/source/valid-archimate-junction.json`. The junction satisfies ArchiMate junction semantics (one incoming, two outgoing, single relationship type `Flow`). The version in `required_plugins` must match the current root `pom.xml` product version (2026.06.3 at time of writing — check before committing).

```json
{
  "model_schema_version": "model.schema.v1",
  "required_plugins": [
    {
      "id": "generic-graph",
      "version": "2026.06.3"
    }
  ],
  "nodes": [
    {
      "id": "order-intake",
      "type": "ApplicationService",
      "label": "Order Intake",
      "properties": {}
    },
    {
      "id": "fulfillment-junction",
      "type": "OrJunction",
      "label": "or",
      "properties": {}
    },
    {
      "id": "fulfillment",
      "type": "ApplicationService",
      "label": "Fulfillment",
      "properties": {}
    },
    {
      "id": "notification",
      "type": "ApplicationService",
      "label": "Notification",
      "properties": {}
    }
  ],
  "relationships": [
    {
      "id": "intake-flows-junction",
      "type": "Flow",
      "source": "order-intake",
      "target": "fulfillment-junction",
      "label": "order accepted",
      "properties": {}
    },
    {
      "id": "junction-flows-fulfillment",
      "type": "Flow",
      "source": "fulfillment-junction",
      "target": "fulfillment",
      "label": "fulfil",
      "properties": {}
    },
    {
      "id": "junction-flows-notification",
      "type": "Flow",
      "source": "fulfillment-junction",
      "target": "notification",
      "label": "notify",
      "properties": {}
    }
  ],
  "plugins": {
    "generic-graph": {
      "semantic_profile": "archimate",
      "views": [
        {
          "id": "main",
          "label": "Fulfillment Flow",
          "nodes": [
            "order-intake",
            "fulfillment-junction",
            "fulfillment",
            "notification"
          ],
          "relationships": [
            "intake-flows-junction",
            "junction-flows-fulfillment",
            "junction-flows-notification"
          ]
        }
      ]
    }
  }
}
```

- [ ] **Step 2: Write the failing projection test**

Add to `GenericGraphPluginTest.java`, next to `projectsLifelineRoleOntoSequenceLayoutNodes` (around line 413), using the same `fixture(...)`, `okData(...)`, `layoutRequestNode(...)`, `assertSchemaValid(...)` helpers already in the class:

```java
@Test
void projectsJunctionRoleOntoArchimateLayoutNodes() throws Exception {
    PluginResult result = Main.executeForTesting(
            new String[]{"project", "--target", "layout-request", "--view", "main"},
            fixture("fixtures/source/valid-archimate-junction.json"));

    JsonNode data = okData(result);

    assertThat(layoutRequestNode(data, "fulfillment-junction").at("/role").asText()).isEqualTo("junction");
    assertThat(layoutRequestNode(data, "order-intake").has("role")).isFalse();
    assertSchemaValid("schemas/layout-request.schema.json", data);
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest#projectsJunctionRoleOntoArchimateLayoutNodes -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `/role` is missing on `fulfillment-junction` (asText() returns "").

- [ ] **Step 4: Implement role propagation**

In `GenericGraphProjection.java`, change the `layoutRole` method (line 211-215) to take the profile and recognize junction types. `dev.dediren.archimate.Archimate` is already on the generic-graph classpath (used by `GenericGraphLayoutSizing`); add the import if the file lacks it.

```java
// Carry roles into the layout-request so backend-neutral layout-quality checks can apply
// role-aware geometry rules (lifeline message anchors, junction route proximity).
// Other source types stay role-less.
private static String layoutRole(String semanticProfile, String sourceType) {
    if ("Lifeline".equals(sourceType)) {
        return "lifeline";
    }
    if (semanticProfile.equals("archimate") && Archimate.isRelationshipConnectorType(sourceType)) {
        return "junction";
    }
    return null;
}
```

Update the single call site at line 103 from `layoutRole(sourceNode.type())` to:

```java
                    layoutRole(semanticProfile, sourceNode.type())));
```

- [ ] **Step 5: Run the test to verify it passes, plus the module suite**

Run: `./mvnw -q -pl plugins/generic-graph -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, no regressions in the module.

- [ ] **Step 6: Commit**

```bash
git add fixtures/source/valid-archimate-junction.json \
        plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java \
        plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java
git commit -m "feat: propagate junction role onto archimate layout-request nodes"
```

---

### Task 2: Edge-crossing count (informational metric)

Count edge pairs whose routes properly cross (interiors intersect), excluding pairs that share an endpoint node. Surfaced as `edge_crossing_count` in the report; deliberately does NOT affect `status`.

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/quality/LayoutQualityReport.java`
- Modify: `core/src/main/java/dev/dediren/core/quality/LayoutQuality.java`
- Test: `core/src/test/java/dev/dediren/core/quality/LayoutQualityTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `LayoutQualityTest.java` (the `node`, `edge`, `layoutResult` helpers already exist; `node(id, x, y)` makes a 100×80 node):

```java
@Test
void crossingEdgePairsAreCountedAsInformationOnly() {
    var nodes = List.of(
            node("a", 0.0, 0.0),
            node("b", 400.0, 400.0),
            node("c", 0.0, 400.0),
            node("d", 400.0, 0.0));
    var edges = List.of(
            edge("a-b", "a", "b", List.of(new Point(100.0, 80.0), new Point(400.0, 440.0))),
            edge("c-d", "c", "d", List.of(new Point(100.0, 440.0), new Point(400.0, 40.0))));

    LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, edges, List.of()));

    assertThat(report.edgeCrossingCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("ok");
}

@Test
void edgesSharingAnEndpointNodeAreNotCountedAsCrossings() {
    var nodes = List.of(
            node("hub", 0.0, 0.0),
            node("left", 300.0, 0.0),
            node("right", 300.0, 200.0));
    var edges = List.of(
            edge("hub-left", "hub", "left", List.of(new Point(100.0, 40.0), new Point(300.0, 40.0))),
            edge("hub-right", "hub", "right", List.of(new Point(100.0, 40.0), new Point(300.0, 240.0))));

    LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, edges, List.of()));

    assertThat(report.edgeCrossingCount()).isZero();
}
```

The first test's geometry keeps every other count zero (endpoints on perimeters, no overlaps, no orthogonal parallels), so `status` stays `"ok"` — that assertion is the "informational only" oracle.

- [ ] **Step 2: Run tests to verify they fail to compile**

Run: `./mvnw -q -pl core -am test -Dtest=LayoutQualityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILE ERROR — `edgeCrossingCount()` does not exist on the report.

- [ ] **Step 3: Extend the report record**

Replace `LayoutQualityReport.java` content (three new components before `warningCount`; tasks 3 and 4 fill the other two — adding all three now keeps the record change to one edit):

```java
package dev.dediren.core.quality;

public record LayoutQualityReport(
        String status,
        String policyName,
        int overlapCount,
        int connectorThroughNodeCount,
        int invalidRouteCount,
        int routeDetourCount,
        int routeCloseParallelCount,
        int groupBoundaryIssueCount,
        int groupLabelBandIssueCount,
        int labelSpaceIssueCount,
        int edgeCrossingCount,
        int warningCount) {
}
```

- [ ] **Step 4: Implement crossing detection and wire the report**

In `LayoutQuality.java`, update `validateLayout` (tasks 3 and 4 will replace the two `0` placeholders for band/label counts with real calls — at this task they are wired as literal zeros via local variables so the record constructs):

```java
    public static LayoutQualityReport validateLayout(LayoutResult result) {
        int overlapCount = countOverlaps(result);
        int connectorThroughNodeCount = countConnectorThroughNodes(result);
        int invalidRouteCount = (int) result.edges().stream()
                .filter(edge -> routeHasIntegrityIssue(edge, result))
                .count();
        int routeDetourCount = (int) result.edges().stream()
                .filter(edge -> hasExcessiveDetour(edge.points()))
                .count();
        int routeCloseParallelCount = countCloseParallelRoutes(result);
        int groupBoundaryIssueCount = countGroupBoundaryIssues(result);
        int groupLabelBandIssueCount = 0;
        int labelSpaceIssueCount = 0;
        int edgeCrossingCount = countEdgeCrossings(result);
        int warningCount = result.warnings().size();
        // edgeCrossingCount is informational: crossings can be unavoidable in non-planar graphs,
        // so it never degrades status. Per-fixture thresholds are asserted in tests instead.
        String status = overlapCount == 0
                && connectorThroughNodeCount == 0
                && invalidRouteCount == 0
                && routeDetourCount == 0
                && routeCloseParallelCount == 0
                && groupBoundaryIssueCount == 0
                && groupLabelBandIssueCount == 0
                && labelSpaceIssueCount == 0
                && warningCount == 0
                ? "ok"
                : "warning";
        return new LayoutQualityReport(
                status,
                "draft",
                overlapCount,
                connectorThroughNodeCount,
                invalidRouteCount,
                routeDetourCount,
                routeCloseParallelCount,
                groupBoundaryIssueCount,
                groupLabelBandIssueCount,
                labelSpaceIssueCount,
                edgeCrossingCount,
                warningCount);
    }
```

Add the private helpers:

```java
    private static int countEdgeCrossings(LayoutResult result) {
        int count = 0;
        for (int i = 0; i < result.edges().size(); i++) {
            for (int j = i + 1; j < result.edges().size(); j++) {
                LaidOutEdge left = result.edges().get(i);
                LaidOutEdge right = result.edges().get(j);
                if (edgesShareEndpointNode(left, right)) {
                    continue;
                }
                if (routesProperlyCross(left.points(), right.points())) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean edgesShareEndpointNode(LaidOutEdge left, LaidOutEdge right) {
        return left.source().equals(right.source())
                || left.source().equals(right.target())
                || left.target().equals(right.source())
                || left.target().equals(right.target());
    }

    private static boolean routesProperlyCross(List<Point> leftPoints, List<Point> rightPoints) {
        for (int i = 0; i + 1 < leftPoints.size(); i++) {
            for (int j = 0; j + 1 < rightPoints.size(); j++) {
                if (segmentsProperlyCross(
                        leftPoints.get(i), leftPoints.get(i + 1),
                        rightPoints.get(j), rightPoints.get(j + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    // Proper crossing only (interiors intersect). Touches and collinear overlaps are excluded so
    // orthogonal routes that share a corner coordinate do not count as crossings.
    private static boolean segmentsProperlyCross(Point a, Point b, Point c, Point d) {
        double o1 = orientation(a, b, c);
        double o2 = orientation(a, b, d);
        double o3 = orientation(c, d, a);
        double o4 = orientation(c, d, b);
        return o1 * o2 < 0 && o3 * o4 < 0;
    }

    private static double orientation(Point a, Point b, Point c) {
        return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    }
```

- [ ] **Step 5: Run the core suite to verify pass**

Run: `./mvnw -q -pl core -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (all existing `LayoutQualityTest` tests still construct reports through `validateLayout`, so the record change is invisible to them).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/dev/dediren/core/quality/LayoutQuality.java \
        core/src/main/java/dev/dediren/core/quality/LayoutQualityReport.java \
        core/src/test/java/dev/dediren/core/quality/LayoutQualityTest.java
git commit -m "feat: report informational edge-crossing count in layout quality"
```

---

### Task 3: Group label band reservation check

Members of a labeled group must not occupy the group's title band (top 24 layout units). Counted as `group_label_band_issue_count`; contributes to `warning` status. Also adds 3-level-deep containment characterization tests for the existing `groupBoundaryIssueCount` (currently only covered to 2 levels).

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/quality/LayoutQuality.java`
- Test: `core/src/test/java/dev/dediren/core/quality/LayoutQualityTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void groupMembersInsideLabelBandAreCounted() {
    var nodes = List.of(node("member", 10.0, 10.0));
    var groups = List.of(new LaidOutGroup(
            "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), "Zone"));

    LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

    assertThat(report.groupLabelBandIssueCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
}

@Test
void groupMembersBelowLabelBandAreAccepted() {
    var nodes = List.of(node("member", 10.0, 32.0));
    var groups = List.of(new LaidOutGroup(
            "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), "Zone"));

    LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

    assertThat(report.groupLabelBandIssueCount()).isZero();
    assertThat(report.status()).isEqualTo("ok");
}

@Test
void unlabeledGroupHasNoLabelBandReservation() {
    var nodes = List.of(node("member", 10.0, 10.0));
    var groups = List.of(new LaidOutGroup(
            "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), null));

    assertThat(LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups))
            .groupLabelBandIssueCount()).isZero();
}

@Test
void threeLevelNestedContainmentValidatesCleanly() {
    var nodes = List.of(node("leaf", 60.0, 110.0));
    var groups = List.of(
            new LaidOutGroup("outer", "outer", "outer", null, 0.0, 0.0, 400.0, 320.0,
                    List.of("middle"), "Outer"),
            new LaidOutGroup("middle", "middle", "middle", null, 30.0, 40.0, 320.0, 240.0,
                    List.of("inner"), "Middle"),
            new LaidOutGroup("inner", "inner", "inner", null, 50.0, 80.0, 260.0, 160.0,
                    List.of("leaf"), "Inner"));

    LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

    assertThat(report.groupBoundaryIssueCount()).isZero();
    assertThat(report.groupLabelBandIssueCount()).isZero();
    assertThat(report.status()).isEqualTo("ok");
}

@Test
void memberEscapingDeepNestedGroupIsCounted() {
    var nodes = List.of(node("leaf", 290.0, 110.0));
    var groups = List.of(
            new LaidOutGroup("outer", "outer", "outer", null, 0.0, 0.0, 400.0, 320.0,
                    List.of("middle"), "Outer"),
            new LaidOutGroup("middle", "middle", "middle", null, 30.0, 40.0, 320.0, 240.0,
                    List.of("inner"), "Middle"),
            new LaidOutGroup("inner", "inner", "inner", null, 50.0, 80.0, 260.0, 160.0,
                    List.of("leaf"), "Inner"));

    LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

    assertThat(report.groupBoundaryIssueCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
}
```

Geometry notes baked into the values: `node(id, x, y)` is 100×80, so the leaf at (60,110) sits below the inner group's band (y 80–104) and inside all three group rects; at (290,110) its right edge (390) escapes the inner group's right bound (310) but stays inside middle and outer, so exactly one boundary issue counts.

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./mvnw -q -pl core -am test -Dtest=LayoutQualityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `groupMembersInsideLabelBandAreCounted` gets count 0 (placeholder), others may pass; the deep-nesting tests are expected to pass already (characterization).

- [ ] **Step 3: Implement the band check**

In `LayoutQuality.java` add the constant next to the other thresholds:

```java
    // Layout units reserved for the group title row; svg-render draws the group label inside the
    // top of the group rect, so members inside this band collide with the label visually.
    private static final double GROUP_LABEL_BAND_HEIGHT = 24.0;
```

Add the helper:

```java
    private static int countGroupLabelBandIssues(LayoutResult result) {
        int count = 0;
        for (LaidOutGroup group : result.groups()) {
            if (group.label() == null || group.label().isBlank()) {
                continue;
            }
            for (String memberId : group.members()) {
                LaidOutNode node = findNode(result, memberId);
                if (node != null) {
                    if (rectanglesOverlap(
                            group.x(), group.y(), group.width(), GROUP_LABEL_BAND_HEIGHT,
                            node.x(), node.y(), node.width(), node.height())) {
                        count++;
                    }
                    continue;
                }
                LaidOutGroup childGroup = findGroup(result, memberId);
                if (childGroup != null && rectanglesOverlap(
                        group.x(), group.y(), group.width(), GROUP_LABEL_BAND_HEIGHT,
                        childGroup.x(), childGroup.y(), childGroup.width(), childGroup.height())) {
                    count++;
                }
            }
        }
        return count;
    }
```

In `validateLayout`, replace `int groupLabelBandIssueCount = 0;` with:

```java
        int groupLabelBandIssueCount = countGroupLabelBandIssues(result);
```

- [ ] **Step 4: Fix the pre-existing nested-group test geometry**

The existing `nestedGroupMembersAreCountedAsGroupBoundaryMembers` test (LayoutQualityTest.java:129-164) now legitimately fires the band check twice — its inner group starts at y −10, inside the labeled outer group's band (−30..−6), and its member nodes start at y 0, inside the labeled inner group's band (−10..14) — which breaks that test's `status == "ok"` assertion. The test is about nested containment, not band collisions, so move its geometry below the bands while keeping the same containment story. Replace the test's nodes/edges/groups construction with:

```java
        var nodes = List.of(
                node("source", 0.0, 30.0),
                node("target", 200.0, 30.0));
        var edges = List.of(edge("internal", "source", "target", List.of(
                new Point(100.0, 40.0),
                new Point(200.0, 40.0))));
        var groups = List.of(
                new LaidOutGroup(
                        "outer",
                        "outer",
                        "outer",
                        null,
                        -30.0,
                        -30.0,
                        360.0,
                        200.0,
                        List.of("inner"),
                        "Outer"),
                new LaidOutGroup(
                        "inner",
                        "inner",
                        "inner",
                        null,
                        -10.0,
                        0.0,
                        320.0,
                        140.0,
                        List.of("source", "target"),
                        "Inner"));
```

(Checked: outer band −30..−6 clears inner at y 0; inner band 0..24 clears members at y 30; inner ⊂ outer and members ⊂ inner still hold; edge endpoints stay on node perimeters.) Keep the test's existing assertions unchanged.

- [ ] **Step 5: Run the core suite to verify pass**

Run: `./mvnw -q -pl core -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including the adjusted nested-group test.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/dev/dediren/core/quality/LayoutQuality.java \
        core/src/test/java/dev/dediren/core/quality/LayoutQualityTest.java
git commit -m "feat: count group members intruding into the group label band"
```

---

### Task 4: Node label-space check

Conservative estimate of whether a node's declared label can possibly fit its computed box. Flags only clear violations (label needs more than 2× the estimated capacity) to stay safe across renderer fonts. Counted as `label_space_issue_count`; contributes to `warning` status. Junction-role nodes are exempt (their labels render adjacent to the dot, not inside).

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/quality/LayoutQuality.java`
- Test: `core/src/test/java/dev/dediren/core/quality/LayoutQualityTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void labelClearlyOverflowingNodeCapacityIsCounted() {
    var nodes = List.of(new LaidOutNode("tiny", "tiny", "tiny", 0.0, 0.0, 60.0, 24.0,
            "An extremely long label that cannot possibly fit", null));

    LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()));

    assertThat(report.labelSpaceIssueCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
}

@Test
void typicalLabelsWithinNodeCapacityAreAccepted() {
    var nodes = List.of(node("api", 0.0, 0.0));

    LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()));

    assertThat(report.labelSpaceIssueCount()).isZero();
    assertThat(report.status()).isEqualTo("ok");
}

@Test
void junctionLabelsAreExemptFromLabelSpaceCheck() {
    var nodes = List.of(new LaidOutNode("junction", "junction", "junction", 0.0, 0.0, 28.0, 28.0,
            "a junction label rendered adjacent to the dot", "junction"));

    assertThat(LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()))
            .labelSpaceIssueCount()).isZero();
}
```

Capacity arithmetic for the first test: width 60 → `floor((60−16)/7)` = 6 chars/line; height 24 → `max(1, floor((24−16)/16))` = 1 line; capacity 6, overflow threshold 12; the 48-char label clearly exceeds it.

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./mvnw -q -pl core -am test -Dtest=LayoutQualityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — first test gets count 0 (placeholder still wired).

- [ ] **Step 3: Implement the label-space check**

Constants (next to the other thresholds):

```java
    // Conservative label-fit estimate: generous per-char width and line height, and only flag
    // labels needing more than LABEL_OVERFLOW_FACTOR times the estimated capacity, so renderer
    // font differences cannot produce false positives.
    private static final double LABEL_CHAR_WIDTH = 7.0;
    private static final double LABEL_LINE_HEIGHT = 16.0;
    private static final double LABEL_PADDING = 8.0;
    private static final int LABEL_OVERFLOW_FACTOR = 2;
```

Helper:

```java
    private static int countLabelSpaceIssues(LayoutResult result) {
        int count = 0;
        for (LaidOutNode node : result.nodes()) {
            if (node.label() == null || node.label().isBlank() || "junction".equals(node.role())) {
                continue;
            }
            int charsPerLine = (int) Math.max(1.0,
                    Math.floor((node.width() - 2 * LABEL_PADDING) / LABEL_CHAR_WIDTH));
            int lines = (int) Math.max(1.0,
                    Math.floor((node.height() - 2 * LABEL_PADDING) / LABEL_LINE_HEIGHT));
            if (node.label().length() > charsPerLine * lines * LABEL_OVERFLOW_FACTOR) {
                count++;
            }
        }
        return count;
    }
```

In `validateLayout`, replace `int labelSpaceIssueCount = 0;` with:

```java
        int labelSpaceIssueCount = countLabelSpaceIssues(result);
```

- [ ] **Step 4: Run the core suite to verify pass**

Run: `./mvnw -q -pl core -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/dediren/core/quality/LayoutQuality.java \
        core/src/test/java/dev/dediren/core/quality/LayoutQualityTest.java
git commit -m "feat: count node labels that clearly overflow their layout box"
```

---

### Task 5: Junction route-proximity diagnostic

A junction renders as a circle of radius ≈ `min(width, height)/2` at the node center. An incident edge that attaches at the bounding-box corner passes the perimeter check but leaves a visible gap to the dot. New ERROR diagnostic `DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE`: every incident edge's route must come within `min(w,h)/2 + 2.0` of the junction center.

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/quality/LayoutQuality.java`
- Test: `core/src/test/java/dev/dediren/core/quality/LayoutQualityTest.java`

- [ ] **Step 1: Write the failing tests**

Add a helper next to `lifelineNode` plus two tests:

```java
private static LaidOutNode junctionNode(String id, double x, double y) {
    return new LaidOutNode(id, id, id, x, y, 28.0, 28.0, "", "junction");
}

@Test
void junctionCornerAttachedEdgeIsReported() {
    var nodes = List.of(
            node("upstream", 0.0, 0.0),
            junctionNode("junction", 200.0, 26.0));
    var edges = List.of(edge("into-junction", "upstream", "junction", List.of(
            new Point(100.0, 40.0),
            new Point(200.0, 28.0))));

    var diagnostics = LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()));

    assertThat(diagnostics).extracting(diagnostic -> diagnostic.code())
            .containsExactly("DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE");
    assertThat(diagnostics.get(0).severity()).isEqualTo(DiagnosticSeverity.ERROR);
    assertThat(diagnostics.get(0).path()).isEqualTo("$.nodes[1]");
}

@Test
void junctionCenterAttachedEdgeIsAccepted() {
    var nodes = List.of(
            node("upstream", 0.0, 0.0),
            junctionNode("junction", 200.0, 26.0));
    var edges = List.of(edge("into-junction", "upstream", "junction", List.of(
            new Point(100.0, 40.0),
            new Point(200.0, 40.0))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()))).isEmpty();
}
```

Geometry baked in: the junction box is (200, 26, 28, 28), center (214, 40), dot-radius proxy 14, reach 16. Endpoint (200, 28) is on the box perimeter (passes the existing endpoint check — that is the point of this test) but 18.4 from the center → fires. Endpoint (200, 40) is 14 from the center → accepted.

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./mvnw -q -pl core -am test -Dtest=LayoutQualityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `junctionCornerAttachedEdgeIsReported` finds no diagnostics.

- [ ] **Step 3: Implement the junction proximity diagnostic**

Constant:

```java
    private static final double JUNCTION_ROUTE_TOLERANCE = 2.0;
```

In `validateLayoutDiagnostics`, after the existing edge loop (before `return diagnostics;`), add:

```java
        for (int nodeIndex = 0; nodeIndex < result.nodes().size(); nodeIndex++) {
            LaidOutNode node = result.nodes().get(nodeIndex);
            if (!"junction".equals(node.role())) {
                continue;
            }
            double centerX = node.x() + node.width() / 2.0;
            double centerY = node.y() + node.height() / 2.0;
            // The rendered junction dot radius tracks min(w,h)/2; routes must reach the dot,
            // not merely the bounding box, or the line shows a visible gap.
            double reach = Math.min(node.width(), node.height()) / 2.0 + JUNCTION_ROUTE_TOLERANCE;
            for (LaidOutEdge edge : result.edges()) {
                boolean incident = node.id().equals(edge.source()) || node.id().equals(edge.target());
                if (!incident || edge.points().size() < 2) {
                    continue;
                }
                if (distanceToRoute(centerX, centerY, edge.points()) > reach) {
                    diagnostics.add(routeError(
                            "DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE",
                            "junction '" + node.id() + "' is not on the route of incident edge '"
                                    + edge.id() + "'",
                            "$.nodes[" + nodeIndex + "]"));
                }
            }
        }
```

Helpers:

```java
    private static double distanceToRoute(double x, double y, List<Point> points) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i + 1 < points.size(); i++) {
            min = Math.min(min, distanceToSegment(x, y, points.get(i), points.get(i + 1)));
        }
        return min;
    }

    private static double distanceToSegment(double x, double y, Point start, Point end) {
        double dx = end.x() - start.x();
        double dy = end.y() - start.y();
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0.0
                ? 0.0
                : Math.clamp(((x - start.x()) * dx + (y - start.y()) * dy) / lengthSquared, 0.0, 1.0);
        return Math.hypot(x - (start.x() + t * dx), y - (start.y() + t * dy));
    }
```

- [ ] **Step 4: Run the core suite to verify pass**

Run: `./mvnw -q -pl core -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/dediren/core/quality/LayoutQuality.java \
        core/src/test/java/dev/dediren/core/quality/LayoutQualityTest.java
git commit -m "feat: report junctions detached from incident edge routes"
```

---

### Task 6: ELK end-to-end junction coverage

Prove the role survives the real pipeline: project the junction fixture, lay it out with the real ELK engine, and assert the layout result carries the junction role and passes the new checks. This is the first time a junction goes through layout in any test.

**Files:**
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java`

- [ ] **Step 1: Inspect existing request-builder patterns**

`ElkLayoutEngineTest` builds `LayoutRequest` objects directly (it does not shell out to generic-graph). Find an existing test that constructs a small `LayoutRequest` with `new LayoutNode(...)` (grep for `new LayoutNode(` in the test) and reuse its construction/run helper pattern for Step 2. Use the 6-arg `LayoutNode` constructor with the role: `new LayoutNode(id, label, id, 28.0, 28.0, "junction")`.

- [ ] **Step 2: Write the test**

Add (adapting the run/engine invocation to the pattern found in Step 1 — the class's existing tests show how a `LayoutRequest` becomes a `LayoutResult`):

```java
@Test
void junctionRoleSurvivesLayoutAndPassesQualityGeometry() {
    var request = new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                    new LayoutNode("order-intake", "Order Intake", "order-intake", 160.0, 80.0, null),
                    new LayoutNode("fulfillment-junction", "or", "fulfillment-junction", 28.0, 28.0, "junction"),
                    new LayoutNode("fulfillment", "Fulfillment", "fulfillment", 160.0, 80.0, null),
                    new LayoutNode("notification", "Notification", "notification", 160.0, 80.0, null)),
            List.of(
                    new LayoutEdge("intake-flows-junction", "order-intake", "fulfillment-junction",
                            "order accepted", "intake-flows-junction", "Flow"),
                    new LayoutEdge("junction-flows-fulfillment", "fulfillment-junction", "fulfillment",
                            "fulfil", "junction-flows-fulfillment", "Flow"),
                    new LayoutEdge("junction-flows-notification", "fulfillment-junction", "notification",
                            "notify", "junction-flows-notification", "Flow")),
            List.of(),
            List.of(),
            List.of(),
            null);

    LayoutResult result = layout(request);

    LaidOutNode junction = result.nodes().stream()
            .filter(node -> node.id().equals("fulfillment-junction"))
            .findFirst()
            .orElseThrow();
    assertThat(junction.role()).isEqualTo("junction");

    for (LaidOutEdge edge : result.edges()) {
        boolean incident = junction.id().equals(edge.source()) || junction.id().equals(edge.target());
        if (!incident) {
            continue;
        }
        double centerX = junction.x() + junction.width() / 2.0;
        double centerY = junction.y() + junction.height() / 2.0;
        double reach = Math.min(junction.width(), junction.height()) / 2.0 + 2.0;
        assertThat(minDistanceToRoute(centerX, centerY, edge.points()))
                .as("junction must sit on the route of %s", edge.id())
                .isLessThanOrEqualTo(reach);
    }
}

private static double minDistanceToRoute(double x, double y, List<Point> points) {
    double min = Double.MAX_VALUE;
    for (int i = 0; i + 1 < points.size(); i++) {
        double dx = points.get(i + 1).x() - points.get(i).x();
        double dy = points.get(i + 1).y() - points.get(i).y();
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0.0
                ? 0.0
                : Math.clamp(((x - points.get(i).x()) * dx + (y - points.get(i).y()) * dy) / lengthSquared, 0.0, 1.0);
        min = Math.min(min, Math.hypot(
                x - (points.get(i).x() + t * dx),
                y - (points.get(i).y() + t * dy)));
    }
    return min;
}
```

Adjust the `LayoutRequest` constructor arity and the `layout(request)` call to the class's actual helper (the test class has ~60 examples; copy the nearest one). Do not change assertion content.

- [ ] **Step 3: Run it**

Run: `./mvnw -q -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#junctionRoleSurvivesLayoutAndPassesQualityGeometry -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS if ELK routes edges to the small junction box cleanly. If it FAILS on the proximity assertion, that is a real finding of the exact bug class this plan targets: apply the ELK-first contingency from the execution notes (ELK Layered options/ports for the junction node — never post-ELK geometry). Record the outcome either way; if a product-side ELK option change is needed, it is its own commit before this test's commit.

- [ ] **Step 4: Commit**

```bash
git add plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "test: junction role and route proximity survive real ELK layout"
```

---

### Task 7: Known-good fixture sweep (false-positive gate)

Issue #13 lesson: every new check must stay silent on every known-good layout fixture of every diagram kind. The sweep asserts only the NEW surfaces, isolating their false-positive risk from pre-existing metrics.

**Files:**
- Create: `core/src/test/java/dev/dediren/core/quality/LayoutQualityFixtureSweepTest.java`

- [ ] **Step 1: Write the sweep test**

```java
package dev.dediren.core.quality;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// False-positive gate for the new quality checks: every checked-in layout-result fixture is a
// known-good layout of some diagram kind, so the label-space, label-band, and junction checks
// must stay silent on all of them (issue #13 regression class).
class LayoutQualityFixtureSweepTest {

    @ParameterizedTest
    @MethodSource("layoutResultFixtures")
    void newQualityChecksStaySilentOnKnownGoodLayouts(Path fixture) throws IOException {
        LayoutResult result = JsonSupport.objectMapper()
                .readValue(Files.readString(fixture), LayoutResult.class);

        LayoutQualityReport report = LayoutQuality.validateLayout(result);

        assertThat(report.labelSpaceIssueCount())
                .as("label-space false positive in %s", fixture.getFileName())
                .isZero();
        assertThat(report.groupLabelBandIssueCount())
                .as("group-label-band false positive in %s", fixture.getFileName())
                .isZero();
        assertThat(LayoutQuality.validateLayoutDiagnostics(result))
                .filteredOn(diagnostic -> diagnostic.code()
                        .equals("DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE"))
                .as("junction false positive in %s", fixture.getFileName())
                .isEmpty();
    }

    static Stream<Path> layoutResultFixtures() throws IOException {
        Path dir = Path.of("..", "fixtures", "layout-result").normalize().toAbsolutePath();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.toString().endsWith(".json")).sorted().toList().stream();
        }
    }
}
```

- [ ] **Step 2: Run the sweep**

Run: `./mvnw -q -pl core -am test -Dtest=LayoutQualityFixtureSweepTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS over all ~15 fixtures. If a fixture fires a new check, inspect the geometry by hand before doing anything: if the fixture genuinely has the defect (e.g., a member really does sit in the group's label band), the fixture is documenting a layout bug — record it for plan 2's regression intake and adjust the check's constant only if the geometry is actually fine visually. Never exclude a fixture silently.

- [ ] **Step 3: Commit**

```bash
git add core/src/test/java/dev/dediren/core/quality/LayoutQualityFixtureSweepTest.java
git commit -m "test: sweep known-good layout fixtures against new quality checks"
```

---

### Task 8: CLI report surface

The new report fields reach the CLI automatically through `valueToTree` (snake_case mapper). Pin them in the CLI contract test so removal or rename is caught.

**Files:**
- Modify: `cli/src/test/java/dev/dediren/cli/CliLayoutRenderCommandTest.java:19-32`

- [ ] **Step 1: Extend the existing test**

In `validateLayoutReportsQualityFromFile`, after the existing `overlap_count` assertion, add:

```java
        assertThat(envelope.at("/data/group_label_band_issue_count").asInt()).isZero();
        assertThat(envelope.at("/data/label_space_issue_count").asInt()).isZero();
        assertThat(envelope.at("/data/edge_crossing_count").isInt()).isTrue();
```

(`isInt()` rather than `isZero()` for the crossing count: it pins presence without pinning the fixture's crossing topology.)

- [ ] **Step 2: Run the CLI suite**

Run: `./mvnw -q -pl cli -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, including the dist-affecting `MainTest`.

- [ ] **Step 3: Commit**

```bash
git add cli/src/test/java/dev/dediren/cli/CliLayoutRenderCommandTest.java
git commit -m "test: pin new layout-quality report fields in CLI envelope"
```

---

### Task 9: Documentation surfaces

User-facing command behavior changed (new report fields, new diagnostic code, new role value), so `README.md` and `docs/agent-usage.md` move together. `AgentUsageDocConsistencyTest` (dist-tool) enforces every `DEDIREN_*` token in `docs/agent-usage.md` exists in source — `DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE` does (Task 5).

**Files:**
- Modify: `README.md` (validate-layout section — locate with `grep -n 'validate-layout' README.md`)
- Modify: `docs/agent-usage.md` (validate-layout section near the existing invocation example, around line 447)

- [ ] **Step 1: Update README**

In the section describing `validate-layout` output, document the three new fields with this content (adapt formatting to the surrounding section):

```markdown
`validate-layout` additionally reports `group_label_band_issue_count` (members
overlapping a labeled group's title band), `label_space_issue_count` (node
labels that clearly cannot fit their computed box), and `edge_crossing_count`
(informational; crossings can be unavoidable, so this count never degrades
`status`). Junction-role nodes (`AndJunction`/`OrJunction` in ArchiMate views)
must sit on the routes of their incident edges; a detached junction is the
error diagnostic `DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE`.
```

- [ ] **Step 2: Update docs/agent-usage.md**

Near the `validate-layout` example, add the same facts in the guide's terse command-oriented style:

```markdown
`validate-layout` quality fields: `overlap_count`, `connector_through_node_count`,
`invalid_route_count`, `route_detour_count`, `route_close_parallel_count`,
`group_boundary_issue_count`, `group_label_band_issue_count`,
`label_space_issue_count`, `edge_crossing_count` (informational only), and
`warning_count`. `status` is `ok` only when all non-informational counts and
warnings are zero. ArchiMate junction nodes detached from an incident edge
route fail with `DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE`.
```

- [ ] **Step 3: Verify doc consistency and diff hygiene**

Run: `./mvnw -q -pl dist-tool -am test -Dtest=AgentUsageDocConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false && git diff --check`
Expected: PASS, no whitespace errors.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/agent-usage.md
git commit -m "docs: document junction-aware and label-aware layout quality checks"
```

---

### Task 10: Full verification and audit gates

- [ ] **Step 1: Full build**

Run: `./mvnw test`
Expected: PASS across all modules (contracts, core, cli, all plugins, dist-tool).

- [ ] **Step 2: Distribution smoke**

The dist smoke pins `validate-layout` `status: ok` on a structural layout; the new checks must not break it.

Run: `./mvnw -pl dist-tool -am verify -Pdist-smoke`
Expected: PASS.

- [ ] **Step 3: Audit gates (per CLAUDE.md, plugin-runtime/vertical row)**

Run `souroldgeezer-audit:test-quality-audit` deep over the new/changed tests (`LayoutQualityTest`, `LayoutQualityFixtureSweepTest`, `GenericGraphPluginTest`, `ElkLayoutEngineTest`, `CliLayoutRenderCommandTest`) and `souroldgeezer-audit:devsecops-audit` quick over the contract/docs diff. Fix block findings; fix or explicitly accept warn/info findings in the handoff, then rerun affected checks.

- [ ] **Step 4: Final status check**

Run: `git status --short --branch` and `git diff --check`
Expected: clean tree, all commits scoped as above. Report any skipped verification in the handoff.

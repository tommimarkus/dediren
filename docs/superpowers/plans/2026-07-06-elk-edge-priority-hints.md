# ELK Edge Priority Hints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three first-class, per-edge layout hints — `resist_reversal`, `keep_short`, `keep_straight` — grouped under an optional `priority` object on connections, authored on source-model relationships, projected through `generic-graph`, applied to individual ELK edges, and rejected when the governing phase strategy cannot honor them.

**Architecture:** Follow-up to the shipped node-placement-hints slice (`docs/superpowers/plans/2026-07-05-elk-node-placement-hints.md`) of the umbrella spec `docs/superpowers/specs/2026-07-05-dediren-elk-layered-capability-vocabulary-design.md`. These are **element-scoped** edge hints, the symmetric counterpart to the node hints: typed attributes on `SourceRelationship` (public model) and `LayoutEdge` (layout contract), projected by `generic-graph`, applied per-edge via a centralized `ElkLayeredOptions.applyEdgeHints` at each of the engine's two edge-building sites. Unlike the node hints, this slice adds **cross-field validation** (phase-coupling rejection) because each priority is only honored by a specific ELK phase strategy. Full design: `docs/superpowers/specs/2026-07-06-dediren-elk-edge-priority-hints-design.md`.

**Tech Stack:** Java 21+ via the Maven Wrapper; Jackson 3 (`tools.jackson`) with global `SNAKE_CASE`; Eclipse ELK Layered 0.11.0; JUnit 5 + AssertJ; `SchemaAssertions` (networknt).

## Global Constraints

- **No raw ELK names in public JSON.** The three sub-fields are Dediren-owned intent names (`resist_reversal`, `keep_short`, `keep_straight`). Values are plain integers — priority is a relative weight whose ordering must be expressible, so an integer IS the natural vocabulary (same rationale as the node `partition` integer; contrast the symbolic tiers of graph-tuning).
- **Exact ELK targets (verified against ELK 0.11.0 layered phase code):**
  - edge `resist_reversal` (Integer) → `LayeredOptions.PRIORITY_DIRECTION` (read by `GreedyCycleBreaker`; cycle-breaking phase).
  - edge `keep_short` (Integer) → `LayeredOptions.PRIORITY_SHORTNESS` (read by `NetworkSimplexLayerer`; layering phase).
  - edge `keep_straight` (Integer) → `LayeredOptions.PRIORITY_STRAIGHTNESS` (read by the Brandes-Köpf, linear-segments, and network-simplex node placers; placement phase).
- **Phase-coupling matrix (the rejection rule). Absent strategy = ELK default = supported:**
  - `resist_reversal` rejected when `cycle_breaking` ∈ {`depth-first`, `model-order`} (only `greedy`/absent honor it).
  - `keep_short` rejected when `layering.strategy` is any explicit value other than `network-simplex` (only `network-simplex`/absent honor it).
  - `keep_straight` rejected when `placement.strategy` is `simple` (every other placement strategy and absent honor it).
- **Error model — mirror the existing gate, add NO new code.** Every hard layout rejection in this plugin (the `rejectLayeredOnly` algorithm gate, provenance, structural `requireNonNull`) throws `IllegalArgumentException` with a JSON-path message; `Main` maps all of them to the single envelope `DEDIREN_ELK_LAYOUT_FAILED`. The phase-coupling rejection follows that exact pattern. Do NOT introduce a per-rejection `DEDIREN_*` code — it would be a one-off inconsistent with every other layout gate, and would need envelope-architecture changes. Consequence: no `docs/threat-model.md` change, no new `DEDIREN_*` token, `AgentUsageDocConsistencyTest` unaffected.
- **Behavior preservation:** every hint is conditional-set per edge; an edge without a `priority` sets no ELK property and is never rejected. Existing fixtures/renders are byte-identical. Verified by the full elk-layout suite in Task 4.
- **Contract growth:** `SourceRelationship` grows 6→7 fields; `LayoutEdge` grows 6→7 fields. Keep backward-compatible convenience constructors delegating to the new canonical with a trailing `null`, preserving `SourceRelationship`'s compact-constructor `properties` normalization. Do NOT edit existing call sites.
- **Jackson:** record components use global `SNAKE_CASE` (no `@JsonProperty` on components → `resistReversal` serializes as `resist_reversal`).
- **Schema id stays `model.schema.v1`.**
- **Version:** no bump — this slice stays on local `main` with the rest of the unreleased ELK Layered capability-vocabulary work, per that project's established pattern.
- **Env:** Maven tests need the sandbox DISABLED (JUnit `@TempDir` on read-only `/tmp`). Module runs need `-am -Dsurefire.failIfNoSpecifiedTests=false`. Run `./mvnw -Pquality spotless:apply` before every Java commit. Explicit-path staging only; never `git add -A` (untracked user dotfiles are present in this worktree).

---

### Task 1: Contract types for edge priority hints

**Files:**
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutEdgePriority.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutEdge.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/source/SourceRelationship.java`
- Test: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`

**Interfaces produced (consumed by Tasks 3–5):** `LayoutEdgePriority.resistReversal()/keepShort()/keepStraight()` → `Integer`; `LayoutEdge.priority()` → `LayoutEdgePriority`; `SourceRelationship.priority()` → `LayoutEdgePriority`.

- [ ] **Step 1: Write the failing test**

Add to `ContractRoundTripTest`:
```java
  @Test
  void layoutEdgeRoundTripsPriorityHints() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "id": "e1",
          "source": "a",
          "target": "b",
          "label": "",
          "source_id": "e1",
          "relationship_type": "flow",
          "priority": { "resist_reversal": 5, "keep_short": 2, "keep_straight": 8 }
        }
        """;
    dev.dediren.contracts.layout.LayoutEdge edge =
        mapper.readValue(json, dev.dediren.contracts.layout.LayoutEdge.class);
    assertThat(edge.priority()).isNotNull();
    assertThat(edge.priority().resistReversal()).isEqualTo(5);
    assertThat(edge.priority().keepShort()).isEqualTo(2);
    assertThat(edge.priority().keepStraight()).isEqualTo(8);
    assertThat(
            mapper.writeValueAsString(
                new dev.dediren.contracts.layout.LayoutEdgePriority(null, 2, null)))
        .isEqualTo("{\"keep_short\":2}");
  }

  @Test
  void sourceRelationshipRoundTripsPriorityHints() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "id": "e1",
          "type": "flow",
          "source": "a",
          "target": "b",
          "label": "",
          "properties": {},
          "priority": { "keep_straight": 3 }
        }
        """;
    dev.dediren.contracts.source.SourceRelationship rel =
        mapper.readValue(json, dev.dediren.contracts.source.SourceRelationship.class);
    assertThat(rel.priority()).isNotNull();
    assertThat(rel.priority().keepStraight()).isEqualTo(3);
    assertThat(rel.priority().resistReversal()).isNull();
  }
```
(The `writeValueAsString` assertion assumes Jackson omits null record components — the codebase's `objectMapper()` is configured `NON_NULL`, matching how existing optional fields serialize. If the round-trip mapper is not `NON_NULL`, drop that one assertion line; the read-side assertions are the load-bearing ones.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#layoutEdgeRoundTripsPriorityHints+sourceRelationshipRoundTripsPriorityHints -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `LayoutEdgePriority` / the `priority()` accessors do not exist (compile error).

- [ ] **Step 3: Create the record**

`LayoutEdgePriority.java`:
```java
package dev.dediren.contracts.layout;

public record LayoutEdgePriority(Integer resistReversal, Integer keepShort, Integer keepStraight) {}
```

- [ ] **Step 4: Extend `LayoutEdge`**

Replace the whole body of `LayoutEdge.java`:
```java
package dev.dediren.contracts.layout;

public record LayoutEdge(
    String id,
    String source,
    String target,
    String label,
    String sourceId,
    String relationshipType,
    LayoutEdgePriority priority) {
  public LayoutEdge(String id, String source, String target, String label, String sourceId) {
    this(id, source, target, label, sourceId, null, null);
  }

  public LayoutEdge(
      String id,
      String source,
      String target,
      String label,
      String sourceId,
      String relationshipType) {
    this(id, source, target, label, sourceId, relationshipType, null);
  }
}
```

- [ ] **Step 5: Extend `SourceRelationship`**

Replace the whole body of `SourceRelationship.java`:
```java
package dev.dediren.contracts.source;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import dev.dediren.contracts.layout.LayoutEdgePriority;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record SourceRelationship(
    String id,
    String type,
    String source,
    String target,
    String label,
    Map<String, JsonNode> properties,
    LayoutEdgePriority priority) {
  public SourceRelationship {
    properties = mapOrEmpty(properties);
  }

  public SourceRelationship(
      String id,
      String type,
      String source,
      String target,
      String label,
      Map<String, JsonNode> properties) {
    this(id, type, source, target, label, properties, null);
  }
}
```

- [ ] **Step 6: Run the new tests + full contracts suite**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#layoutEdgeRoundTripsPriorityHints+sourceRelationshipRoundTripsPriorityHints -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl contracts -am test` (sandbox disabled) — Expected: PASS.

- [ ] **Step 7: Confirm downstream modules still test-compile**

Run: `./mvnw -pl plugins/elk-layout,plugins/generic-graph -am test-compile` (sandbox disabled)
Expected: BUILD SUCCESS (the backward-compat convenience constructors keep existing `new LayoutEdge(...)` / `new SourceRelationship(...)` call sites compiling).

- [ ] **Step 8: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add contracts/src/main/java/dev/dediren/contracts/layout/LayoutEdgePriority.java contracts/src/main/java/dev/dediren/contracts/layout/LayoutEdge.java contracts/src/main/java/dev/dediren/contracts/source/SourceRelationship.java contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java
git commit -m "Add edge priority-hint contract fields"
```

---

### Task 2: Widen public schemas (source relationship + layout-request edge)

**Files:**
- Modify: `schemas/model.schema.json` (`sourceRelationship` `$def`, currently lines 48–60)
- Modify: `schemas/layout-request.schema.json` (`edge` `$def`, currently lines 32–44)
- Test: `contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java`

- [ ] **Step 1: Write the failing test**

Add to `SchemaValidatorTest`:
```java
  @Test
  void edgePriorityHintsValidateAndRejectUnknownKey() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String sourceTemplate =
        """
        {
          "model_schema_version": "model.schema.v1",
          "nodes": [ { "id": "a", "type": "Component", "label": "A", "properties": {} },
                     { "id": "b", "type": "Component", "label": "B", "properties": {} } ],
          "relationships": [ { "id": "e1", "type": "flow", "source": "a", "target": "b",
                               "label": "", "properties": {}, "priority": { %s } } ],
          "plugins": {}
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/model.schema.json",
                mapper.readTree(String.format(sourceTemplate, "\"keep_short\": 2"))))
        .describedAs("valid edge priority must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/model.schema.json",
                mapper.readTree(String.format(sourceTemplate, "\"keep_medium\": 2"))))
        .describedAs("unknown priority key must be rejected")
        .isNotEmpty();
  }
```
(If `model.schema.json` requires additional top-level keys beyond `model_schema_version/nodes/relationships/plugins`, read an existing valid source fixture such as `fixtures/source/valid-basic.json` first and match its required top-level fields.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#edgePriorityHintsValidateAndRejectUnknownKey -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `sourceRelationship` has `additionalProperties:false`, so `priority` is rejected and the first assertion fails.

- [ ] **Step 3: Extend the `sourceRelationship` def**

In `schemas/model.schema.json`, add a comma after the `"properties": { "type": "object", "additionalProperties": true }` line (line 58) and insert:
```json
        "priority": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "resist_reversal": { "type": "integer" },
            "keep_short": { "type": "integer" },
            "keep_straight": { "type": "integer" }
          }
        }
```

- [ ] **Step 4: Extend the `edge` def (layout-request mirror)**

In `schemas/layout-request.schema.json`, add a comma after the `"relationship_type": { "type": "string", "minLength": 1 }` line (line 42) and insert the identical block:
```json
        "priority": {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "resist_reversal": { "type": "integer" },
            "keep_short": { "type": "integer" },
            "keep_straight": { "type": "integer" }
          }
        }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#edgePriorityHintsValidateAndRejectUnknownKey -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS (including any "all public schemas compile" method — both edited schemas must still parse).

- [ ] **Step 6: Commit**

```bash
git add schemas/model.schema.json schemas/layout-request.schema.json contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java
git commit -m "Accept edge priority hints in public schemas"
```

---

### Task 3: Project edge priority in `generic-graph`

**Files:**
- Modify: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java` (the `new LayoutEdge(...)` construction in the relationship-projection loop, currently near line 124)
- Test: `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`

**Interfaces:** consumes Task 1 accessors. Produces: projected `LayoutEdge`s carry the source relationship's `priority`.

- [ ] **Step 1: Write the failing test**

Read `GenericGraphPluginTest.java` first to match its existing pattern for building a `SourceDocument`/view and invoking the projection. Add a test that builds a source with two nodes and one relationship carrying a `priority`, runs the projection, and asserts the emitted `LayoutEdge` carries it. Model it on the nearest existing projection test in that file; the load-bearing assertion is:
```java
    // after projecting a view whose relationship e1 was authored with priority keep_short=7:
    LayoutEdge projected =
        result.edges().stream().filter(e -> e.id().equals("e1")).findFirst().orElseThrow();
    assertNotNull(projected.priority());
    assertEquals(Integer.valueOf(7), projected.priority().keepShort());
```
Author the test relationship with the new 7-arg `SourceRelationship` constructor:
```java
    new SourceRelationship(
        "e1", "flow", "a", "b", "", java.util.Map.of(),
        new LayoutEdgePriority(null, 7, null))
```
(Follow the file's existing helpers for the view/document scaffolding and its import style; add imports for `LayoutEdge` and `LayoutEdgePriority` if not already present.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest#<new-method> -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — projection drops the hint (the `LayoutEdge` is built with the 6-arg convenience constructor, leaving `priority` null).

- [ ] **Step 3: Project the hint**

In `GenericGraphProjection.java`, change the `new LayoutEdge(...)` construction (the relationship-projection loop) from the 6-arg form to pass the source priority as a 7th argument:
```java
          new LayoutEdge(
              relationship.id(),
              relationship.source(),
              relationship.target(),
              relationship.label(),
              relationship.id(),
              relationship.type(),
              relationship.priority()));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS (new + existing projection tests).

- [ ] **Step 5: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java
git commit -m "Project edge priority hints in generic-graph"
```

---

### Task 4: Apply edge priority to the ELK graph

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayeredOptions.java` (add `applyEdgeHints`)
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java` (call it at both edge-building sites — after the flat-path `elkEdges.put(...)` near line 138, and after the grouped-path `elkEdges.put(...)` near line 388)
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java` (ADDITIVE — new imports + one `@Test`)

**Interfaces:** consumes Task 1 types. Produces: `ElkLayeredOptions.applyEdgeHints(ElkEdge, LayoutEdge)` sets the per-edge ELK priority properties.

- [ ] **Step 1: Write the failing test**

In `ElkLayoutEngineTest.java`, add imports if absent:
```java
import dev.dediren.contracts.layout.LayoutEdgePriority;
import org.eclipse.elk.graph.ElkEdge;
```
(`ElkNode`, `LayeredOptions`, `LayoutEdge`, `ElkGraphUtil`, and the JUnit assertions are already available.)

Append this test:
```java
  @Test
  void applyEdgeHintsSetsThreePriorities() {
    ElkNode root = ElkGraphUtil.createGraph();
    ElkNode a = ElkGraphUtil.createNode(root);
    ElkNode b = ElkGraphUtil.createNode(root);
    ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(a, b);
    LayoutEdge edge =
        new LayoutEdge("e1", "a", "b", "", "e1", null, new LayoutEdgePriority(5, 2, 8));

    ElkLayeredOptions.applyEdgeHints(elkEdge, edge);

    assertEquals(Integer.valueOf(5), elkEdge.getProperty(LayeredOptions.PRIORITY_DIRECTION));
    assertEquals(Integer.valueOf(2), elkEdge.getProperty(LayeredOptions.PRIORITY_SHORTNESS));
    assertEquals(Integer.valueOf(8), elkEdge.getProperty(LayeredOptions.PRIORITY_STRAIGHTNESS));
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#applyEdgeHintsSetsThreePriorities -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `applyEdgeHints` does not exist (compile error).

- [ ] **Step 3: Add the helper**

In `ElkLayeredOptions.java`, add imports:
```java
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutEdgePriority;
import org.eclipse.elk.graph.ElkEdge;
```
Add this package-private static method to the class:
```java
  static void applyEdgeHints(ElkEdge elkEdge, LayoutEdge edge) {
    LayoutEdgePriority priority = edge.priority();
    if (priority == null) {
      return;
    }
    if (priority.resistReversal() != null) {
      elkEdge.setProperty(LayeredOptions.PRIORITY_DIRECTION, priority.resistReversal());
    }
    if (priority.keepShort() != null) {
      elkEdge.setProperty(LayeredOptions.PRIORITY_SHORTNESS, priority.keepShort());
    }
    if (priority.keepStraight() != null) {
      elkEdge.setProperty(LayeredOptions.PRIORITY_STRAIGHTNESS, priority.keepStraight());
    }
  }
```

- [ ] **Step 4: Wire the helper into `ElkLayoutEngine` at BOTH edge sites**

In `ElkLayoutEngine.java`, add the same line immediately after each `elkEdges.put(edge.id(), elkEdge);` statement (there are two — the flat/sequence path near line 138 and the grouped path near line 388):
```java
      ElkLayeredOptions.applyEdgeHints(elkEdge, edge);
```
(Search the file for `elkEdges.put(edge.id(), elkEdge);` to find both sites; do not miss one. The packed path is edge-less, so it needs no call. In the grouped path a cross-group back-edge is created with source/target swapped — priority is an orientation-independent scalar weight, so applying it to the single `elkEdge` after the `put` is correct for both branches.)

- [ ] **Step 5: Run the new test, then the full elk-layout suite**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#applyEdgeHintsSetsThreePriorities -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl plugins/elk-layout -am test` (sandbox disabled) — Expected: PASS. Existing fixtures have no edge priority, so `applyEdgeHints` sets nothing — geometry is unchanged. If any existing test fails, STOP and report BLOCKED (do not edit fixtures).

- [ ] **Step 6: Confirm the test-file change is additive**

Run: `git diff -- plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java | grep '^-' | grep -v '^---'`
Expected: no output.

- [ ] **Step 7: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayeredOptions.java plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "Apply edge priority hints to the ELK graph"
```

---

### Task 5: Reject edge priorities their phase strategy cannot honor

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java` (add a `validateEdgePriorities` call inside `validate(...)` and the helper + three strategy-honor predicates)
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java` (ADDITIVE — new imports + four `@Test`)

**Interfaces:** consumes Task 1 types + the `LayoutPreferences` strategy accessors. Produces: `ElkLayoutEngine.layout(request)` throws `IllegalArgumentException` (→ `DEDIREN_ELK_LAYOUT_FAILED`) when a set priority's governing phase strategy cannot honor it.

- [ ] **Step 1: Write the failing tests**

In `ElkLayoutEngineTest.java`, add imports if absent:
```java
import dev.dediren.contracts.layout.LayoutCycleBreaking;
import dev.dediren.contracts.layout.LayoutEdgePriority;
import dev.dediren.contracts.layout.LayoutLayeringPreferences;
import dev.dediren.contracts.layout.LayoutLayeringStrategy;
import dev.dediren.contracts.layout.LayoutPlacementPreferences;
import dev.dediren.contracts.layout.LayoutPlacementStrategy;
import java.util.List;
```
(`LayoutNode`, `LayoutEdge`, `LayoutPreferences`, `LayoutRequest` are already imported by earlier tasks/tests.)

Append these tests. They reuse one private helper to build a two-node, one-edge request:
```java
  private static LayoutRequest edgePriorityRequest(
      LayoutEdgePriority priority, LayoutPreferences prefs) {
    LayoutNode a = new LayoutNode("a", "A", "a", null, null);
    LayoutNode b = new LayoutNode("b", "B", "b", null, null);
    LayoutEdge e = new LayoutEdge("e1", "a", "b", "", "e1", null, priority);
    return new LayoutRequest(
        "layout-request.schema.v1", "main", List.of(a, b), List.of(e), List.of(), List.of(), prefs);
  }

  @Test
  void resistReversalRejectedUnderNonGreedyCycleBreaking() {
    LayoutPreferences prefs =
        new LayoutPreferences(
            null, null, null, null, null, LayoutCycleBreaking.MODEL_ORDER, null, null, null);
    IllegalArgumentException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                new ElkLayoutEngine()
                    .layout(edgePriorityRequest(new LayoutEdgePriority(5, null, null), prefs)));
    org.junit.jupiter.api.Assertions.assertTrue(
        ex.getMessage().contains("$.edges[0].priority.resist_reversal"),
        "message should name the offending path, was: " + ex.getMessage());
  }

  @Test
  void keepShortRejectedUnderNonNetworkSimplexLayering() {
    LayoutPreferences prefs =
        new LayoutPreferences(
            null,
            null,
            null,
            null,
            null,
            null,
            new LayoutLayeringPreferences(LayoutLayeringStrategy.LONGEST_PATH),
            null,
            null);
    IllegalArgumentException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                new ElkLayoutEngine()
                    .layout(edgePriorityRequest(new LayoutEdgePriority(null, 2, null), prefs)));
    org.junit.jupiter.api.Assertions.assertTrue(
        ex.getMessage().contains("$.edges[0].priority.keep_short"),
        "message should name the offending path, was: " + ex.getMessage());
  }

  @Test
  void keepStraightRejectedUnderSimplePlacement() {
    LayoutPreferences prefs =
        new LayoutPreferences(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new LayoutPlacementPreferences(LayoutPlacementStrategy.SIMPLE));
    IllegalArgumentException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                new ElkLayoutEngine()
                    .layout(edgePriorityRequest(new LayoutEdgePriority(null, null, 8), prefs)));
    org.junit.jupiter.api.Assertions.assertTrue(
        ex.getMessage().contains("$.edges[0].priority.keep_straight"),
        "message should name the offending path, was: " + ex.getMessage());
  }

  @Test
  void edgePriorityAcceptedAgainstDefaultStrategies() {
    // All strategies absent → ELK defaults (greedy / network-simplex / brandes-koepf) honor all
    // three, so a fully-populated priority must NOT be rejected.
    LayoutResult result =
        new ElkLayoutEngine()
            .layout(edgePriorityRequest(new LayoutEdgePriority(5, 2, 8), null));
    org.junit.jupiter.api.Assertions.assertNotNull(result);
  }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#resistReversalRejectedUnderNonGreedyCycleBreaking+keepShortRejectedUnderNonNetworkSimplexLayering+keepStraightRejectedUnderSimplePlacement -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — no rejection is thrown yet, so `assertThrows` fails.

- [ ] **Step 3: Add the validation**

In `ElkLayoutEngine.java`, add imports:
```java
import dev.dediren.contracts.layout.LayoutCycleBreaking;
import dev.dediren.contracts.layout.LayoutEdgePriority;
import dev.dediren.contracts.layout.LayoutLayeringStrategy;
import dev.dediren.contracts.layout.LayoutPlacementStrategy;
```
Inside `validate(LayoutRequest request)`, immediately after the `for (int index = 0; index < request.edges().size(); index++)` structural edge loop closes, add:
```java
    validateEdgePriorities(request.edges(), request.layoutPreferences());
```
Then add these package-private/private static methods to the class:
```java
  private static void validateEdgePriorities(
      List<LayoutEdge> edges, LayoutPreferences preferences) {
    for (int index = 0; index < edges.size(); index++) {
      LayoutEdge edge = edges.get(index);
      LayoutEdgePriority priority = edge == null ? null : edge.priority();
      if (priority == null) {
        continue;
      }
      String path = "$.edges[" + index + "].priority";
      if (priority.resistReversal() != null && !cycleBreakingHonorsDirection(preferences)) {
        throw new IllegalArgumentException(
            path + ".resist_reversal is only honored by the 'greedy' cycle_breaking strategy");
      }
      if (priority.keepShort() != null && !layeringHonorsShortness(preferences)) {
        throw new IllegalArgumentException(
            path + ".keep_short is only honored by the 'network-simplex' layering strategy");
      }
      if (priority.keepStraight() != null && !placementHonorsStraightness(preferences)) {
        throw new IllegalArgumentException(
            path + ".keep_straight is not honored by the 'simple' placement strategy");
      }
    }
  }

  private static boolean cycleBreakingHonorsDirection(LayoutPreferences preferences) {
    var strategy = preferences == null ? null : preferences.cycleBreaking();
    return strategy == null || strategy == LayoutCycleBreaking.GREEDY;
  }

  private static boolean layeringHonorsShortness(LayoutPreferences preferences) {
    var layering = preferences == null ? null : preferences.layering();
    var strategy = layering == null ? null : layering.strategy();
    return strategy == null || strategy == LayoutLayeringStrategy.NETWORK_SIMPLEX;
  }

  private static boolean placementHonorsStraightness(LayoutPreferences preferences) {
    var placement = preferences == null ? null : preferences.placement();
    var strategy = placement == null ? null : placement.strategy();
    return strategy != LayoutPlacementStrategy.SIMPLE;
  }
```

- [ ] **Step 4: Run the new tests, then the full elk-layout suite**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#resistReversalRejectedUnderNonGreedyCycleBreaking+keepShortRejectedUnderNonNetworkSimplexLayering+keepStraightRejectedUnderSimplePlacement+edgePriorityAcceptedAgainstDefaultStrategies -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl plugins/elk-layout -am test` (sandbox disabled) — Expected: PASS (no existing fixture carries edge priority, so `validateEdgePriorities` is a no-op for them).

- [ ] **Step 5: Confirm the test-file change is additive**

Run: `git diff -- plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java | grep '^-' | grep -v '^---'`
Expected: no output.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "Reject edge priorities their phase strategy cannot honor"
```

---

### Task 6: Document edge priority hints

**Files:**
- Modify: `docs/features/layout.md` (add "### Edge priority hints" near the node-hints subsection)
- Modify: `README.md` and `docs/agent-usage.md` (one sentence each)

- [ ] **Step 1: Add the layout.md subsection**

Insert immediately after the existing "### Node placement hints" subsection in `docs/features/layout.md`:
```markdown
### Edge priority hints

Per-edge layout hints authored on source-model relationships (they also survive
on layout-request edges), grouped under an optional `priority` object. All three
are optional integers and layered-only; higher numbers mean "try harder". They
are relative weights, so what matters is the ordering between edges.

| Edge hint | ELK phase | Honored only when |
| --- | --- | --- |
| `resist_reversal` | cycle-breaking (resist pointing against the flow) | `cycle_breaking` is `greedy` (the default) |
| `keep_short` | layering (fewer layers spanned) | `layering.strategy` is `network-simplex` (the default) |
| `keep_straight` | node placement (axis-aligned) | `placement.strategy` is anything except `simple` |

A priority set against the default strategies is always honored. If you set a
priority whose governing phase strategy cannot honor it (for example `keep_short`
with a non-`network-simplex` layering strategy), the layout request is rejected
with a `$.edges[i].priority.<field>` diagnostic rather than being silently
ignored.
```

- [ ] **Step 2: Add the one-line mentions**

In `README.md` and `docs/agent-usage.md`, next to the existing node-placement-hints sentence, add: ``Individual connections accept priority hints (`resist_reversal`, `keep_short`, `keep_straight`); see the Layout feature page.``

- [ ] **Step 3: Verify whitespace**

Run: `git diff --check`
Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add docs/features/layout.md README.md docs/agent-usage.md
git commit -m "Document edge priority hints"
```

---

## Final verification (after Tasks 1–6)

```bash
./mvnw test                                   # sandbox disabled — full integration
./mvnw -pl dist-tool -am verify -Pdist-smoke  # sandbox disabled
git diff --check
```
`AgentUsageDocConsistencyTest` (dist-tool) must stay green — this slice adds no new `DEDIREN_*` token and no version string, so it should be unaffected.

## Self-Review Notes

- **Spec coverage:** implements the edge `priority` row of the umbrella spec's element-scoped table, corrected to the three phase-scoped ELK options (`PRIORITY_DIRECTION` / `PRIORITY_SHORTNESS` / `PRIORITY_STRAIGHTNESS`) — the umbrella spec's §B row omitted `PRIORITY_SHORTNESS`. Full design in `docs/superpowers/specs/2026-07-06-dediren-elk-edge-priority-hints-design.md`.
- **Two edge sites:** the risk in this slice is missing one of the engine's two edge-building loops; Task 4 Step 4 calls out searching for every `elkEdges.put(edge.id(), elkEdge);` and the full-suite run guards against a missed site.
- **Error model:** the phase-coupling rejection deliberately reuses `IllegalArgumentException` → `DEDIREN_ELK_LAYOUT_FAILED`, matching every existing layout gate; it does NOT invent a per-rejection code (which would be inconsistent and require envelope-architecture changes). No threat-model change.
- **Default = supported:** `edgePriorityAcceptedAgainstDefaultStrategies` (Task 5) locks in that a priority against absent/default strategies is never rejected — the most common authoring case.
- **Behavior preservation:** per-edge conditional-set; no `priority` → no ELK property and no rejection. Verified by the full elk-layout suite in Tasks 4 and 5.
- **Real-render evidence:** intentionally deferred (no task). Edge priorities are tie-breakers with weak visual evidence on small fixtures; the node-hints slice set the precedent of relying on the full suite for behavior preservation. Add an ignored render fixture later only if a concrete readability case motivates it.
- **Type consistency:** `LayoutEdgePriority(resistReversal, keepShort, keepStraight)` accessor names are used identically in Tasks 1, 3, 4, and 5; ELK constants `PRIORITY_DIRECTION`/`PRIORITY_SHORTNESS`/`PRIORITY_STRAIGHTNESS` are used identically in Task 4 (apply) and the Task 5 predicates (which gate on the Dediren strategy enums `LayoutCycleBreaking.GREEDY`, `LayoutLayeringStrategy.NETWORK_SIMPLEX`, `LayoutPlacementStrategy.SIMPLE`).

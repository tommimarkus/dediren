# ELK Node Placement Hints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two first-class, per-node layout hints — `layer_constraint` (pin a node to the first/last layer) and `partition` (assign a node to an ordered partition band) — authored on source-model nodes, projected through `generic-graph`, and applied to the ELK graph.

**Architecture:** Slice 5 of the umbrella spec `docs/superpowers/specs/2026-07-05-dediren-elk-layered-capability-vocabulary-design.md`. Unlike slices 1–4 (graph-scoped `layout_preferences`), these are **element-scoped**: typed attributes on `SourceNode` (public model) and `LayoutNode` (layout contract). `generic-graph` projects them (it cannot pass them through like preferences). The ELK engine applies them per-node via a centralized `ElkLayeredOptions.applyNodeHints` helper at each of its three `createNode` sites, and activates ELK partitioning on the root when any node carries a `partition`.

**Tech Stack:** Java 21+ via the Maven Wrapper; Jackson 3 (`tools.jackson`) with global `SNAKE_CASE`; Eclipse ELK Layered 0.11.0; JUnit 5 + AssertJ; `SchemaAssertions` (networknt).

## Global Constraints

- **No raw ELK names in public JSON.** `layer_constraint` is a Dediren kebab enum. `partition` is a plain integer (a Dediren-owned semantic band index — an integer IS the natural vocabulary here, not an ELK magic number, so it is exposed directly; contrast the symbolic tiers of slice 3).
- **Exact ELK targets (verified against ELK 0.11.0):**
  - node `layer_constraint` → `LayeredOptions.LAYERING_LAYER_CONSTRAINT` (type `LayerConstraint`): `none`→`NONE`, `first`→`FIRST`, `first-separate`→`FIRST_SEPARATE`, `last`→`LAST`, `last-separate`→`LAST_SEPARATE`.
  - node `partition` (Integer) → `LayeredOptions.PARTITIONING_PARTITION`.
  - graph activation → `LayeredOptions.PARTITIONING_ACTIVATE` (Boolean): set `true` on the root when ANY node carries a `partition` (implicit activation — there is no separate public `partitioning` flag). Absent partitions → never set → ELK default (off).
- **Behavior preservation:** both hints are conditional-set per node; a node without hints sets no ELK property, and if no node has a partition the root partitioning flag is never set. Existing fixtures/renders are byte-identical. Verified by the full elk-layout suite in Task 4.
- **Validation home:** node hints are validated by the JSON **schema** (enum for `layer_constraint`, integer for `partition`) and by Jackson enum deserialization — NOT by `LayoutJson` (whose string-validation only covers `layout_preferences`). This slice adds no `LayoutJson` change.
- **Contract growth:** `SourceNode` grows 4→6 fields; `LayoutNode` grows 6→8 fields. Keep backward-compatible convenience constructors (SourceNode: keep the 4-arg; LayoutNode: keep the 5-arg and 6-arg) delegating to the new canonical with trailing nulls, preserving the record's compact-constructor normalization where present. Do NOT edit existing call sites.
- **Jackson:** record components use global `SNAKE_CASE` (no `@JsonProperty` on components → `layerConstraint` serializes as `layer_constraint`); enum constants carry `@JsonProperty`.
- **Schema id stays `model.schema.v1`.**
- **Deferred (documented, not lost):** `layer_choice`, `position_choice`, and node/edge `priority` from the umbrella spec's element-scoped table are NOT in this slice. `layer_choice`/`position_choice` are niche layer/position-index pinning; `priority` adds the edge surface. They are a natural follow-up increment. `partition` interacts best when all nodes are partitioned (ELK places unpartitioned nodes ad hoc); this is documented in Task 5, not enforced.
- **Env:** Maven tests need the sandbox DISABLED. Module runs need `-am -Dsurefire.failIfNoSpecifiedTests=false`. `./mvnw -Pquality spotless:apply` before every Java commit. Explicit-path staging only; never `git add -A`.

---

### Task 1: Contract types for node placement hints

**Files:**
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutLayerConstraint.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutNode.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/source/SourceNode.java`
- Test: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`

**Interfaces produced (consumed by Tasks 3–4):** `LayoutNode.partition()` → `Integer`, `LayoutNode.layerConstraint()` → `LayoutLayerConstraint`; same accessors on `SourceNode`.

- [ ] **Step 1: Write the failing test**

Add to `ContractRoundTripTest`:
```java
  @Test
  void layoutNodeRoundTripsPlacementHints() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "id": "n1",
          "label": "N1",
          "source_id": "n1",
          "partition": 2,
          "layer_constraint": "first-separate"
        }
        """;
    dev.dediren.contracts.layout.LayoutNode node =
        mapper.readValue(json, dev.dediren.contracts.layout.LayoutNode.class);
    assertThat(node.partition()).isEqualTo(2);
    assertThat(node.layerConstraint()).isEqualTo(LayoutLayerConstraint.FIRST_SEPARATE);
    assertThat(mapper.writeValueAsString(LayoutLayerConstraint.LAST_SEPARATE))
        .isEqualTo("\"last-separate\"");
  }

  @Test
  void sourceNodeRoundTripsPlacementHints() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "id": "n1",
          "type": "Component",
          "label": "N1",
          "properties": {},
          "partition": 3,
          "layer_constraint": "last"
        }
        """;
    dev.dediren.contracts.source.SourceNode node =
        mapper.readValue(json, dev.dediren.contracts.source.SourceNode.class);
    assertThat(node.partition()).isEqualTo(3);
    assertThat(node.layerConstraint()).isEqualTo(LayoutLayerConstraint.LAST);
  }
```
Add the `LayoutLayerConstraint` import if the test does not wildcard-import `dev.dediren.contracts.layout`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#layoutNodeRoundTripsPlacementHints+sourceNodeRoundTripsPlacementHints -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — new type/accessors do not exist (compile error).

- [ ] **Step 3: Create the enum**

`LayoutLayerConstraint.java`:
```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutLayerConstraint {
  @JsonProperty("none")
  NONE,

  @JsonProperty("first")
  FIRST,

  @JsonProperty("first-separate")
  FIRST_SEPARATE,

  @JsonProperty("last")
  LAST,

  @JsonProperty("last-separate")
  LAST_SEPARATE
}
```

- [ ] **Step 4: Extend `LayoutNode`**

Replace the whole body of `LayoutNode.java`:
```java
package dev.dediren.contracts.layout;

public record LayoutNode(
    String id,
    String label,
    String sourceId,
    Double widthHint,
    Double heightHint,
    String role,
    Integer partition,
    LayoutLayerConstraint layerConstraint) {

  public LayoutNode(String id, String label, String sourceId, Double widthHint, Double heightHint) {
    this(id, label, sourceId, widthHint, heightHint, null, null, null);
  }

  public LayoutNode(
      String id, String label, String sourceId, Double widthHint, Double heightHint, String role) {
    this(id, label, sourceId, widthHint, heightHint, role, null, null);
  }
}
```

- [ ] **Step 5: Extend `SourceNode`**

Replace the whole body of `SourceNode.java`:
```java
package dev.dediren.contracts.source;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import dev.dediren.contracts.layout.LayoutLayerConstraint;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record SourceNode(
    String id,
    String type,
    String label,
    Map<String, JsonNode> properties,
    Integer partition,
    LayoutLayerConstraint layerConstraint) {
  public SourceNode {
    properties = mapOrEmpty(properties);
  }

  public SourceNode(String id, String type, String label, Map<String, JsonNode> properties) {
    this(id, type, label, properties, null, null);
  }
}
```

- [ ] **Step 6: Run the new tests + full contracts suite**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#layoutNodeRoundTripsPlacementHints+sourceNodeRoundTripsPlacementHints -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl contracts -am test` (sandbox disabled) — Expected: PASS.

- [ ] **Step 7: Confirm downstream modules still test-compile**

Run: `./mvnw -pl plugins/elk-layout,plugins/generic-graph -am test-compile` (sandbox disabled)
Expected: BUILD SUCCESS (the backward-compat convenience constructors keep existing `new LayoutNode(...)` / `new SourceNode(...)` call sites compiling).

- [ ] **Step 8: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add contracts/src/main/java/dev/dediren/contracts/layout/LayoutLayerConstraint.java contracts/src/main/java/dev/dediren/contracts/layout/LayoutNode.java contracts/src/main/java/dev/dediren/contracts/source/SourceNode.java contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java
git commit -m "Add node placement-hint contract fields (layer_constraint, partition)"
```

---

### Task 2: Widen public schemas (source + layout-request node)

**Files:**
- Modify: `schemas/model.schema.json` (`sourceNode` `$def`)
- Modify: `schemas/layout-request.schema.json` (`node` `$def`)
- Test: `contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java`

- [ ] **Step 1: Write the failing test**

Add to `SchemaValidatorTest`:
```java
  @Test
  void nodePlacementHintsValidateAndRejectUnknown() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String sourceTemplate =
        """
        {
          "model_schema_version": "model.schema.v1",
          "nodes": [ { "id": "n1", "type": "Component", "label": "N1", "properties": {},
                      "partition": 1, "layer_constraint": "%s" } ],
          "relationships": [],
          "plugins": {}
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/model.schema.json",
                mapper.readTree(String.format(sourceTemplate, "first"))))
        .describedAs("valid node hints must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/model.schema.json",
                mapper.readTree(String.format(sourceTemplate, "middle"))))
        .describedAs("unknown layer_constraint must be rejected")
        .isNotEmpty();
  }
```
(If `model.schema.json` requires additional top-level keys beyond `model_schema_version/nodes/relationships/plugins`, mirror the shape of an existing valid source fixture such as `fixtures/source/valid-basic.json` — read it first and match its required top-level fields.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#nodePlacementHintsValidateAndRejectUnknown -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `sourceNode` has `additionalProperties:false`, so `partition`/`layer_constraint` are rejected and the first assertion fails.

- [ ] **Step 3: Extend both node defs**

In `schemas/model.schema.json`, add to the `sourceNode` `$def` `properties` (after `"properties"`):
```json
        "partition": { "type": "integer" },
        "layer_constraint": { "enum": ["none", "first", "first-separate", "last", "last-separate"] }
```
(add a comma after the existing `"properties": { ... }` line).

In `schemas/layout-request.schema.json`, add to the `node` `$def` `properties` (after `"role"`):
```json
        "partition": { "type": "integer" },
        "layer_constraint": { "enum": ["none", "first", "first-separate", "last", "last-separate"] }
```
(add a comma after the existing `"role"` line).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#nodePlacementHintsValidateAndRejectUnknown -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#allPublicSchemasCompile -Dsurefire.failIfNoSpecifiedTests=false` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add schemas/model.schema.json schemas/layout-request.schema.json contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java
git commit -m "Accept node placement hints in public schemas"
```

---

### Task 3: Project node hints in `generic-graph`

**Files:**
- Modify: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java`
- Test: `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`

**Interfaces:** consumes Task 1 accessors. Produces: projected `LayoutNode`s carry the source node's `partition` and `layerConstraint`.

- [ ] **Step 1: Write the failing test**

Read `GenericGraphPluginTest.java` first to match its existing pattern for building a `SourceDocument`/view and invoking the projection. Add a test that builds a source with one node carrying `partition`/`layerConstraint`, runs the projection, and asserts the emitted `LayoutNode` carries them. Model it on the nearest existing projection test in that file; the assertion is:
```java
    // after projecting a view whose node was authored with partition=4, layer_constraint=LAST:
    LayoutNode projected =
        result.nodes().stream().filter(n -> n.id().equals("n1")).findFirst().orElseThrow();
    assertEquals(Integer.valueOf(4), projected.partition());
    assertEquals(LayoutLayerConstraint.LAST, projected.layerConstraint());
```
(Use the exact `SourceNode` 6-arg constructor `new SourceNode("n1", "Component", "N1", java.util.Map.of(), 4, LayoutLayerConstraint.LAST)` when authoring the test source, and follow the file's existing helpers for the view/document scaffolding.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest#<new-method> -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — projection drops the hints (the `LayoutNode` is built with the 6-arg convenience constructor, leaving `partition`/`layerConstraint` null).

- [ ] **Step 3: Project the hints**

In `GenericGraphProjection.java`, change the `new LayoutNode(...)` construction (the node-projection loop) from the 6-arg form to pass the source hints:
```java
          new LayoutNode(
              sourceNode.id(),
              sourceNode.label(),
              sourceNode.id(),
              GenericGraphLayoutSizing.widthHint(semanticProfile, sourceNode),
              GenericGraphLayoutSizing.heightHint(semanticProfile, sourceNode),
              layoutRole(semanticProfile, sourceNode.type()),
              sourceNode.partition(),
              sourceNode.layerConstraint()));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS (new + existing projection tests).

- [ ] **Step 5: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java
git commit -m "Project node placement hints in generic-graph"
```

---

### Task 4: Apply node hints to the ELK graph

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayeredOptions.java` (add `applyNodeHints` + `activatePartitioning` helpers)
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java` (call them at the three `createNode` sites / per layout path)
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java` (ADDITIVE — new imports + two `@Test` methods)

**Interfaces:** consumes Task 1 types. Produces: `ElkLayeredOptions.applyNodeHints(ElkNode, LayoutNode)` sets the per-node ELK properties; `ElkLayeredOptions.activatePartitioning(ElkNode, List<LayoutNode>)` sets the root partitioning flag when any node has a partition.

- [ ] **Step 1: Write the failing test**

In `ElkLayoutEngineTest.java`, add imports if absent:
```java
import dev.dediren.contracts.layout.LayoutLayerConstraint;
import org.eclipse.elk.alg.layered.options.LayerConstraint;
import org.eclipse.elk.graph.util.ElkGraphUtil;
```
(`ElkNode`, `LayeredOptions`, `LayoutNode`, `List`, and the JUnit assertions are already available.)

Append these tests:
```java
  @Test
  void applyNodeHintsSetsPartitionAndLayerConstraint() {
    ElkNode root = ElkGraphUtil.createGraph();
    ElkNode elkNode = ElkGraphUtil.createNode(root);
    LayoutNode node =
        new LayoutNode("n1", "N1", "n1", null, null, null, 2, LayoutLayerConstraint.FIRST);

    ElkLayeredOptions.applyNodeHints(elkNode, node);

    assertEquals(
        Integer.valueOf(2), elkNode.getProperty(LayeredOptions.PARTITIONING_PARTITION));
    assertEquals(
        LayerConstraint.FIRST, elkNode.getProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT));
  }

  @Test
  void activatePartitioningWhenAnyNodeHasPartition() {
    ElkNode root = ElkGraphUtil.createGraph();
    ElkLayeredOptions.activatePartitioning(
        root,
        java.util.List.of(
            new LayoutNode("a", "A", "a", null, null, null, null, null),
            new LayoutNode("b", "B", "b", null, null, null, 5, null)));

    assertEquals(Boolean.TRUE, root.getProperty(LayeredOptions.PARTITIONING_ACTIVATE));
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#applyNodeHintsSetsPartitionAndLayerConstraint+activatePartitioningWhenAnyNodeHasPartition -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — the helper methods do not exist (compile error).

- [ ] **Step 3: Add the helpers**

In `ElkLayeredOptions.java`, add imports:
```java
import dev.dediren.contracts.layout.LayoutLayerConstraint;
import dev.dediren.contracts.layout.LayoutNode;
import java.util.List;
import org.eclipse.elk.alg.layered.options.LayerConstraint;
```
Add these package-private static methods to the class:
```java
  static void applyNodeHints(ElkNode elkNode, LayoutNode node) {
    if (node.partition() != null) {
      elkNode.setProperty(LayeredOptions.PARTITIONING_PARTITION, node.partition());
    }
    LayerConstraint layerConstraint = layerConstraint(node.layerConstraint());
    if (layerConstraint != null) {
      elkNode.setProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT, layerConstraint);
    }
  }

  static void activatePartitioning(ElkNode root, List<LayoutNode> nodes) {
    boolean anyPartition = nodes.stream().anyMatch(node -> node.partition() != null);
    if (anyPartition) {
      root.setProperty(LayeredOptions.PARTITIONING_ACTIVATE, true);
    }
  }

  private static LayerConstraint layerConstraint(LayoutLayerConstraint constraint) {
    if (constraint == null) {
      return null;
    }
    return switch (constraint) {
      case NONE -> LayerConstraint.NONE;
      case FIRST -> LayerConstraint.FIRST;
      case FIRST_SEPARATE -> LayerConstraint.FIRST_SEPARATE;
      case LAST -> LayerConstraint.LAST;
      case LAST_SEPARATE -> LayerConstraint.LAST_SEPARATE;
    };
  }
```

- [ ] **Step 4: Wire the helpers into `ElkLayoutEngine`**

In `ElkLayoutEngine.java`, at EACH of the three node-building loops (the loops that call `ElkGraphUtil.createNode(...)` then `setGeneratedDimensions(...)` then `ElkGraphUtil.createLabel(...)` — there are three, in the flat/sequence path, the grouped-root path, and the grouped path), add one line immediately after the `ElkGraphUtil.createLabel(elkNode).setText(node.label());` line in that loop:
```java
      ElkLayeredOptions.applyNodeHints(elkNode, node);
```
And, once per layout path, after that node loop completes (where the path's `root` and node list are in scope), add:
```java
    ElkLayeredOptions.activatePartitioning(root, list(request.nodes()));
```
(Use the existing `list(...)` helper and the path's own root variable. If a path builds nodes into groups under `root`, still activate on `root`. Search the file for `ElkGraphUtil.createNode(` to find all three sites; do not miss one.)

- [ ] **Step 5: Run the new tests, then the full elk-layout suite**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#applyNodeHintsSetsPartitionAndLayerConstraint+activatePartitioningWhenAnyNodeHasPartition -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl plugins/elk-layout -am test` (sandbox disabled) — Expected: PASS. Existing fixtures have no hints, so `applyNodeHints` sets nothing and `activatePartitioning` never fires — geometry is unchanged. If any existing test fails, STOP and report BLOCKED (do not edit fixtures).

- [ ] **Step 6: Confirm the test-file change is additive**

Run: `git diff -- plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java | grep '^-' | grep -v '^---'`
Expected: no output.

- [ ] **Step 7: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayeredOptions.java plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "Apply node placement hints to the ELK graph"
```

---

### Task 5: Document node placement hints

**Files:**
- Modify: `docs/features/layout.md` (add "### Node placement hints" before "### Algorithm")
- Modify: `README.md` and `docs/agent-usage.md` (one sentence each)

- [ ] **Step 1: Add the layout.md subsection**

Insert immediately before the `### Algorithm` heading in `docs/features/layout.md`:
```markdown
### Node placement hints

Per-node layout hints authored on source-model nodes (they also survive on
layout-request nodes). Both are optional and layered-only.

| Node hint | Values | Controls |
| --- | --- | --- |
| `layer_constraint` | `none`, `first`, `first-separate`, `last`, `last-separate` | Pin a node to the first or last layer of the drawing. |
| `partition` | integer | Assign the node to an ordered partition band; lower numbers are placed earlier. |

Partitioning activates automatically when any node carries a `partition`. For
predictable results, give every node a partition when you use the feature —
ELK places unpartitioned nodes without band ordering.

```

- [ ] **Step 2: Add the one-line mentions**

In `README.md` and `docs/agent-usage.md`, near the existing `layout_preferences` guidance, add: ``Individual nodes accept placement hints (`layer_constraint`, `partition`); see the Layout feature page.``

- [ ] **Step 3: Verify whitespace**

Run: `git diff --check`
Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add docs/features/layout.md README.md docs/agent-usage.md
git commit -m "Document node placement hints"
```

---

## Final verification (after Tasks 1–5)

```bash
./mvnw test                                   # sandbox disabled — full integration
./mvnw -pl dist-tool -am verify -Pdist-smoke  # sandbox disabled
git diff --check
```

## Self-Review Notes

- **Spec coverage:** implements the `layer_constraint` and `partition`/partitioning rows of the umbrella spec's element-scoped table (slice 5), the first per-node hints in the product.
- **Deferred (documented):** `layer_choice`, `position_choice`, node/edge `priority` — a follow-up increment (niche pinning + the edge surface).
- **Validation:** node hints are gated by the JSON schema + Jackson enum deserialization, not `LayoutJson` (which validates only `layout_preferences`); this is a deliberate, documented difference from slices 1–4.
- **Behavior preservation:** per-node conditional-set; no hints → no ELK property; no partition anywhere → partitioning never activated. Verified by the full elk-layout suite in Task 4.
- **Three createNode sites:** the risk in this slice is missing one of the engine's three node-building loops; Task 4 Step 4 calls out searching for every `ElkGraphUtil.createNode(` and the full-suite run guards against a missed site changing behavior.
- **Threat model:** additive optional node fields + schema-validated enums; no new parser or trust boundary, so `docs/threat-model.md` needs no change.

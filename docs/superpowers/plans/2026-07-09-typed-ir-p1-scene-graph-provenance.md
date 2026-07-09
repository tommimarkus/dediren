# Typed IR — Phase 1: Scene Graph Skeleton + Source Provenance — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the `ir` module skeleton and embed a JSON-Pointer `source_pointer` on the public `layout-request` / `layout-result` artifacts (schema v1→v2), as the first, independently-shippable phase of Plan B.

**Architecture:** Introduce a new `ir` Maven module (depends only on `contracts`) holding a minimal, provenance-bearing pre-layout scene graph (`SceneGraph`) plus a `SceneGraph → LayoutRequest` mapper. The `generic-graph` projection is switched to build a `SceneGraph` (stamping each element's JSON-Pointer origin) and map it to the record. The public records (`LayoutNode`/`LayoutEdge`/`LaidOutNode`/`LaidOutEdge`) gain an optional-but-always-emitted `source_pointer`; `elk-layout` propagates it through layout. Engines still speak the existing `contracts` records; the twin `LaidOutScene`, typed `NodeRole`/`LayoutIntent`, the `semantics-*` carve, and typed-IR piping are later phases.

**Tech Stack:** Java 21, Maven (checked-in `mvnw`), Jackson 3 (`tools.jackson`, SNAKE_CASE naming), JUnit Jupiter, AssertJ. Design anchor: `docs/superpowers/specs/2026-07-09-typed-ir-provenance-design.md`.

## Global Constraints

- Product version source is root `pom.xml`; current version `2026.07.14`. Do **not** bump the version in this plan — the version bump is a separate follow-on commit sequenced at release time per `release-policy`.
- Java formatted by google-java-format (GOOGLE) via Spotless; run `./mvnw -Pquality spotless:apply` before each commit touching Java. The gate is `./mvnw -Pquality verify`.
- New public JSON shape ⇒ the "files that move together" set moves together: `schemas/`, `contracts`, fixtures, plugin/engine mapping code, and schema/round-trip tests (CLAUDE.md).
- `source_pointer` is a JSON-Pointer (RFC 6901) into the **source model** document, e.g. `/nodes/3`, `/relationships/2`.
- `ir` depends only on `contracts`. It must not depend on `engine-api`, `core`, or any engine (ArchUnit will enforce this in a later phase; keep it true now).
- Maven `@TempDir` tests fail on the read-only `/tmp` under the Claude Code sandbox — run `./mvnw` commands **sandbox-disabled** (memory `maven-tests-need-sandbox-disabled`).
- A `ContractVersions.*` schema-id constant change requires a full `./mvnw clean …` — stale inlined constants otherwise fail downstream modules (memory `inlined-constant-testbed-staleness`). The final task runs `clean verify`.
- Module-scoped single-test runs need `-am -Dsurefire.failIfNoSpecifiedTests=false` (memory `maven-module-scoped-tests-need-am`).
- Git: explicit-path staging only; never `git add -A` (untracked user env files are present). Direct commits to `main` are allowed.

---

### Task 1: Scaffold the `ir` Maven module

**Files:**
- Create: `ir/pom.xml`
- Create: `ir/src/main/java/dev/dediren/ir/package-info.java`
- Modify: `pom.xml` (root `<modules>`)

**Interfaces:**
- Consumes: nothing.
- Produces: a buildable `dev.dediren:ir` module (packages `dev.dediren.ir`) depending on `contracts`.

- [ ] **Step 1: Create the module POM** (mirrors `engine-api/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>dev.dediren</groupId>
    <artifactId>dediren</artifactId>
    <version>2026.07.14</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>ir</artifactId>

  <dependencies>
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>contracts</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 2: Create the package marker**

`ir/src/main/java/dev/dediren/ir/package-info.java`:
```java
/**
 * Typed intermediate representation (scene graph) for the dediren compiler. Depends only on {@code
 * contracts}; holds the pre-layout {@code SceneGraph} and its mapping to the public layout-request
 * record. See {@code docs/superpowers/specs/2026-07-09-typed-ir-provenance-design.md}.
 */
package dev.dediren.ir;
```

- [ ] **Step 3: Register the module** — add `<module>ir</module>` to root `pom.xml` after line 16 (`<module>engine-api</module>`), so it sits next to the other core modules:

```xml
    <module>engine-api</module>
    <module>ir</module>
    <module>core</module>
```

- [ ] **Step 4: Verify the module builds**

Run (sandbox-disabled): `./mvnw -q -pl ir -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add ir/pom.xml ir/src/main/java/dev/dediren/ir/package-info.java pom.xml
git commit -m "build: add ir module skeleton (Plan B P1)"
```

---

### Task 2: `SourcePointer` value + pointer helper

**Files:**
- Create: `ir/src/main/java/dev/dediren/ir/SourcePointer.java`
- Create: `ir/src/main/java/dev/dediren/ir/SourcePointers.java`
- Test: `ir/src/test/java/dev/dediren/ir/SourcePointersTest.java`

**Interfaces:**
- Produces: `record SourcePointer(String jsonPointer)`; `SourcePointers.node(int index) -> SourcePointer`; `SourcePointers.relationship(int index) -> SourcePointer`; `SourcePointer.value() -> String` (the raw pointer string used on the wire).

- [ ] **Step 1: Write the failing test**

`ir/src/test/java/dev/dediren/ir/SourcePointersTest.java`:
```java
package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourcePointersTest {
  @Test
  void nodePointerTargetsSourceNodesByIndex() {
    assertThat(SourcePointers.node(3).value()).isEqualTo("/nodes/3");
  }

  @Test
  void relationshipPointerTargetsSourceRelationshipsByIndex() {
    assertThat(SourcePointers.relationship(0).value()).isEqualTo("/relationships/0");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl ir -am test -Dtest=SourcePointersTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (cannot find symbol `SourcePointers`).

- [ ] **Step 3: Write minimal implementation**

`ir/src/main/java/dev/dediren/ir/SourcePointer.java`:
```java
package dev.dediren.ir;

/** RFC 6901 JSON-Pointer into the source model document. */
public record SourcePointer(String jsonPointer) {
  public String value() {
    return jsonPointer;
  }
}
```

`ir/src/main/java/dev/dediren/ir/SourcePointers.java`:
```java
package dev.dediren.ir;

/** Builds {@link SourcePointer}s addressing the source model's top-level arrays. */
public final class SourcePointers {
  private SourcePointers() {}

  public static SourcePointer node(int sourceNodeIndex) {
    return new SourcePointer("/nodes/" + sourceNodeIndex);
  }

  public static SourcePointer relationship(int sourceRelationshipIndex) {
    return new SourcePointer("/relationships/" + sourceRelationshipIndex);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl ir -am test -Dtest=SourcePointersTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Format + commit**

```bash
./mvnw -Pquality spotless:apply -pl ir
git add ir/src/main/java/dev/dediren/ir/SourcePointer.java ir/src/main/java/dev/dediren/ir/SourcePointers.java ir/src/test/java/dev/dediren/ir/SourcePointersTest.java
git commit -m "feat(ir): add SourcePointer value and pointer helpers (Plan B P1)"
```

---

### Task 3: Add `source_pointer` to the layout-request contract (schema v2)

**Files:**
- Modify: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutNode.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutEdge.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/ContractVersions.java:7`
- Modify: `schemas/layout-request.schema.json`
- Test: `contracts/src/test/java/dev/dediren/contracts/layout/LayoutRequestProvenanceTest.java`

**Interfaces:**
- Produces: `LayoutNode` and `LayoutEdge` each gain a trailing nullable `String sourcePointer` component (serializes as `source_pointer`); `ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION == "layout-request.schema.v2"`.

- [ ] **Step 1: Write the failing test**

`contracts/src/test/java/dev/dediren/contracts/layout/LayoutRequestProvenanceTest.java`:
```java
package dev.dediren.contracts.layout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;

class LayoutRequestProvenanceTest {
  @Test
  void schemaVersionIsV2() {
    assertThat(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION).isEqualTo("layout-request.schema.v2");
  }

  @Test
  void nodeSourcePointerRoundTripsAsSnakeCase() throws Exception {
    LayoutNode node = new LayoutNode("n1", "N1", "n1", 10.0, 10.0, null, null, null, "/nodes/0");
    String json = JsonSupport.writeValueAsString(node);
    assertThat(json).contains("\"source_pointer\":\"/nodes/0\"");
    assertThat(JsonSupport.readValue(json, LayoutNode.class).sourcePointer()).isEqualTo("/nodes/0");
  }

  @Test
  void edgeSourcePointerRoundTrips() throws Exception {
    LayoutEdge edge = new LayoutEdge("e1", "n1", "n2", "", "e1", null, null, "/relationships/0");
    assertThat(JsonSupport.readValue(JsonSupport.writeValueAsString(edge), LayoutEdge.class).sourcePointer())
        .isEqualTo("/relationships/0");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl contracts -am test -Dtest=LayoutRequestProvenanceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (constructor arity / `sourcePointer()` missing / version is v1).

- [ ] **Step 3: Add the field to `LayoutNode`** (append `sourcePointer` as the last component; keep the existing convenience constructors delegating with `null`)

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
    LayoutLayerConstraint layerConstraint,
    String sourcePointer) {

  public LayoutNode(String id, String label, String sourceId, Double widthHint, Double heightHint) {
    this(id, label, sourceId, widthHint, heightHint, null, null, null, null);
  }

  public LayoutNode(
      String id, String label, String sourceId, Double widthHint, Double heightHint, String role) {
    this(id, label, sourceId, widthHint, heightHint, role, null, null, null);
  }

  public LayoutNode(
      String id,
      String label,
      String sourceId,
      Double widthHint,
      Double heightHint,
      String role,
      Integer partition,
      LayoutLayerConstraint layerConstraint) {
    this(id, label, sourceId, widthHint, heightHint, role, partition, layerConstraint, null);
  }
}
```

- [ ] **Step 4: Add the field to `LayoutEdge`** (append `sourcePointer`; keep existing convenience constructors delegating with `null`)

```java
package dev.dediren.contracts.layout;

public record LayoutEdge(
    String id,
    String source,
    String target,
    String label,
    String sourceId,
    String relationshipType,
    LayoutEdgePriority priority,
    String sourcePointer) {
  public LayoutEdge(String id, String source, String target, String label, String sourceId) {
    this(id, source, target, label, sourceId, null, null, null);
  }

  public LayoutEdge(
      String id, String source, String target, String label, String sourceId, String relationshipType) {
    this(id, source, target, label, sourceId, relationshipType, null, null);
  }

  public LayoutEdge(
      String id,
      String source,
      String target,
      String label,
      String sourceId,
      String relationshipType,
      LayoutEdgePriority priority) {
    this(id, source, target, label, sourceId, relationshipType, priority, null);
  }
}
```

- [ ] **Step 5: Bump the schema-id constant** — `contracts/src/main/java/dev/dediren/contracts/ContractVersions.java` line 7:

```java
  public static final String LAYOUT_REQUEST_SCHEMA_VERSION = "layout-request.schema.v2";
```

- [ ] **Step 6: Update `schemas/layout-request.schema.json`** — bump the version `const` (line 8) and add an optional `source_pointer` to the `node` and `edge` `$defs` (do **not** add it to `required`):

Line 8:
```json
    "layout_request_schema_version": { "const": "layout-request.schema.v2" },
```
In `$defs.node.properties` (after `layer_constraint`):
```json
        "layer_constraint": { "enum": ["none", "first", "first-separate", "last", "last-separate"] },
        "source_pointer": { "type": "string", "pattern": "^/" }
```
In `$defs.edge.properties` (after `priority`):
```json
        "source_pointer": { "type": "string", "pattern": "^/" }
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./mvnw -q -pl contracts -am test -Dtest=LayoutRequestProvenanceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 8: Format + commit**

```bash
./mvnw -Pquality spotless:apply -pl contracts
git add contracts/src/main/java/dev/dediren/contracts/layout/LayoutNode.java contracts/src/main/java/dev/dediren/contracts/layout/LayoutEdge.java contracts/src/main/java/dev/dediren/contracts/ContractVersions.java schemas/layout-request.schema.json contracts/src/test/java/dev/dediren/contracts/layout/LayoutRequestProvenanceTest.java
git commit -m "feat(contracts): layout-request v2 carries optional source_pointer (Plan B P1)"
```

---

### Task 4: Add `source_pointer` to the layout-result contract (schema v2)

**Files:**
- Modify: `contracts/src/main/java/dev/dediren/contracts/layout/LaidOutNode.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/layout/LaidOutEdge.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/ContractVersions.java:8`
- Modify: `schemas/layout-result.schema.json`
- Test: `contracts/src/test/java/dev/dediren/contracts/layout/LayoutResultProvenanceTest.java`

**Interfaces:**
- Produces: `LaidOutNode` and `LaidOutEdge` each gain a trailing nullable `String sourcePointer`; `ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION == "layout-result.schema.v2"`.

- [ ] **Step 1: Write the failing test**

`contracts/src/test/java/dev/dediren/contracts/layout/LayoutResultProvenanceTest.java`:
```java
package dev.dediren.contracts.layout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;

class LayoutResultProvenanceTest {
  @Test
  void schemaVersionIsV2() {
    assertThat(ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION).isEqualTo("layout-result.schema.v2");
  }

  @Test
  void laidOutNodeSourcePointerRoundTrips() throws Exception {
    LaidOutNode node = new LaidOutNode("n1", "n1", "p1", 0, 0, 10, 10, "N1", null, "/nodes/0");
    String json = JsonSupport.writeValueAsString(node);
    assertThat(json).contains("\"source_pointer\":\"/nodes/0\"");
    assertThat(JsonSupport.readValue(json, LaidOutNode.class).sourcePointer()).isEqualTo("/nodes/0");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl contracts -am test -Dtest=LayoutResultProvenanceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL.

- [ ] **Step 3: Add the field to `LaidOutNode`** (append `sourcePointer` as the last component; keep the 8-arg convenience constructor delegating with `null` role and `null` pointer)

```java
package dev.dediren.contracts.layout;

public record LaidOutNode(
    String id,
    String sourceId,
    String projectionId,
    double x,
    double y,
    double width,
    double height,
    String label,
    String role,
    String sourcePointer) {

  public LaidOutNode(
      String id,
      String sourceId,
      String projectionId,
      double x,
      double y,
      double width,
      double height,
      String label) {
    this(id, sourceId, projectionId, x, y, width, height, label, null, null);
  }

  public LaidOutNode(
      String id,
      String sourceId,
      String projectionId,
      double x,
      double y,
      double width,
      double height,
      String label,
      String role) {
    this(id, sourceId, projectionId, x, y, width, height, label, role, null);
  }
}
```

- [ ] **Step 4: Add the field to `LaidOutEdge`** (append `sourcePointer`; keep the compact-canonical-constructor list defaulting)

```java
package dev.dediren.contracts.layout;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record LaidOutEdge(
    String id,
    String source,
    String target,
    String sourceId,
    String projectionId,
    List<String> routingHints,
    List<Point> points,
    String label,
    String sourcePointer) {
  public LaidOutEdge {
    routingHints = listOrEmpty(routingHints);
    points = listOrEmpty(points);
  }

  public LaidOutEdge(
      String id,
      String source,
      String target,
      String sourceId,
      String projectionId,
      List<String> routingHints,
      List<Point> points,
      String label) {
    this(id, source, target, sourceId, projectionId, routingHints, points, label, null);
  }
}
```

- [ ] **Step 5: Bump the schema-id constant** — `ContractVersions.java` line 8:

```java
  public static final String LAYOUT_RESULT_SCHEMA_VERSION = "layout-result.schema.v2";
```

- [ ] **Step 6: Update `schemas/layout-result.schema.json`** — bump the version `const` (line 8) and add optional `source_pointer` to the `node` and `edge` `$defs` (not `required`):

Line 8:
```json
    "layout_result_schema_version": { "const": "layout-result.schema.v2" },
```
In `$defs.node.properties` (after `role`):
```json
        "role": { "type": "string" },
        "source_pointer": { "type": "string", "pattern": "^/" }
```
In `$defs.edge.properties` (after `label`):
```json
        "label": { "type": "string" },
        "source_pointer": { "type": "string", "pattern": "^/" }
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./mvnw -q -pl contracts -am test -Dtest=LayoutResultProvenanceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 8: Format + commit**

```bash
./mvnw -Pquality spotless:apply -pl contracts
git add contracts/src/main/java/dev/dediren/contracts/layout/LaidOutNode.java contracts/src/main/java/dev/dediren/contracts/layout/LaidOutEdge.java contracts/src/main/java/dev/dediren/contracts/ContractVersions.java schemas/layout-result.schema.json contracts/src/test/java/dev/dediren/contracts/layout/LayoutResultProvenanceTest.java
git commit -m "feat(contracts): layout-result v2 carries optional source_pointer (Plan B P1)"
```

---

### Task 5: The minimal pre-layout scene graph (`SceneGraph`)

**Files:**
- Create: `ir/src/main/java/dev/dediren/ir/SceneNode.java`
- Create: `ir/src/main/java/dev/dediren/ir/SceneEdge.java`
- Create: `ir/src/main/java/dev/dediren/ir/SceneGroup.java`
- Create: `ir/src/main/java/dev/dediren/ir/SceneGraph.java`
- Test: `ir/src/test/java/dev/dediren/ir/SceneGraphTest.java`

**Interfaces:**
- Produces: `record SceneNode(String id, String label, SourcePointer origin, Double widthHint, Double heightHint, String role, Integer partition, LayoutLayerConstraint layerConstraint)`; `record SceneEdge(String id, String source, String target, String label, SourcePointer origin, String relationshipType, LayoutEdgePriority priority)`; `record SceneGroup(String id, String label, java.util.List<String> members, GroupProvenance provenance)`; `record SceneGraph(String viewId, java.util.List<SceneNode> nodes, java.util.List<SceneEdge> edges, java.util.List<SceneGroup> groups, LayoutPreferences preferences)`.
- Note: `role` stays a `String` in P1 (typed `NodeRole` arrives in P3). Reuses `contracts` value types (`LayoutLayerConstraint`, `LayoutEdgePriority`, `GroupProvenance`, `LayoutPreferences`).

- [ ] **Step 1: Write the failing test**

`ir/src/test/java/dev/dediren/ir/SceneGraphTest.java`:
```java
package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SceneGraphTest {
  @Test
  void sceneNodeCarriesOrigin() {
    SceneNode node =
        new SceneNode("n1", "N1", SourcePointers.node(0), 10.0, 10.0, "lifeline", null, null);
    assertThat(node.origin().value()).isEqualTo("/nodes/0");
  }

  @Test
  void sceneGraphDefaultsEmptyCollections() {
    SceneGraph graph = new SceneGraph("view-1", null, null, null, null);
    assertThat(graph.nodes()).isEmpty();
    assertThat(graph.edges()).isEmpty();
    assertThat(graph.groups()).isEmpty();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl ir -am test -Dtest=SceneGraphTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (types missing).

- [ ] **Step 3: Create the records**

`ir/src/main/java/dev/dediren/ir/SceneNode.java`:
```java
package dev.dediren.ir;

import dev.dediren.contracts.layout.LayoutLayerConstraint;

/** A pre-layout scene node. Carries a non-null {@link SourcePointer} origin. */
public record SceneNode(
    String id,
    String label,
    SourcePointer origin,
    Double widthHint,
    Double heightHint,
    String role,
    Integer partition,
    LayoutLayerConstraint layerConstraint) {}
```

`ir/src/main/java/dev/dediren/ir/SceneEdge.java`:
```java
package dev.dediren.ir;

import dev.dediren.contracts.layout.LayoutEdgePriority;

/** A pre-layout scene edge. Carries a non-null {@link SourcePointer} origin. */
public record SceneEdge(
    String id,
    String source,
    String target,
    String label,
    SourcePointer origin,
    String relationshipType,
    LayoutEdgePriority priority) {}
```

`ir/src/main/java/dev/dediren/ir/SceneGroup.java`:
```java
package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.layout.GroupProvenance;
import java.util.List;

/** A pre-layout grouping. Provenance uses the existing {@link GroupProvenance} tagged value. */
public record SceneGroup(String id, String label, List<String> members, GroupProvenance provenance) {
  public SceneGroup {
    members = listOrEmpty(members);
  }
}
```

`ir/src/main/java/dev/dediren/ir/SceneGraph.java`:
```java
package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.layout.LayoutPreferences;
import java.util.List;

/** The pre-layout typed scene graph produced by projection and mapped to the layout-request. */
public record SceneGraph(
    String viewId,
    List<SceneNode> nodes,
    List<SceneEdge> edges,
    List<SceneGroup> groups,
    LayoutPreferences preferences) {
  public SceneGraph {
    nodes = listOrEmpty(nodes);
    edges = listOrEmpty(edges);
    groups = listOrEmpty(groups);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl ir -am test -Dtest=SceneGraphTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Format + commit**

```bash
./mvnw -Pquality spotless:apply -pl ir
git add ir/src/main/java/dev/dediren/ir/SceneNode.java ir/src/main/java/dev/dediren/ir/SceneEdge.java ir/src/main/java/dev/dediren/ir/SceneGroup.java ir/src/main/java/dev/dediren/ir/SceneGraph.java ir/src/test/java/dev/dediren/ir/SceneGraphTest.java
git commit -m "feat(ir): minimal provenance-bearing SceneGraph (Plan B P1)"
```

---

### Task 6: `SceneGraph → LayoutRequest` mapper

**Files:**
- Create: `ir/src/main/java/dev/dediren/ir/LayoutRequestMapper.java`
- Test: `ir/src/test/java/dev/dediren/ir/LayoutRequestMapperTest.java`

**Interfaces:**
- Consumes: `SceneGraph` (Task 5), `SourcePointer` (Task 2), the v2 `LayoutNode`/`LayoutEdge` records (Tasks 3–4).
- Produces: `LayoutRequestMapper.toRequest(SceneGraph graph) -> LayoutRequest`, stamping `ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION` and each node/edge `sourcePointer` from the scene element's `origin`.

- [ ] **Step 1: Write the failing test**

`ir/src/test/java/dev/dediren/ir/LayoutRequestMapperTest.java`:
```java
package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.LayoutRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutRequestMapperTest {
  @Test
  void mapsSceneNodesAndStampsProvenanceAndVersion() {
    SceneGraph graph =
        new SceneGraph(
            "view-1",
            List.of(new SceneNode("n1", "N1", SourcePointers.node(2), 10.0, 10.0, "lifeline", null, null)),
            List.of(new SceneEdge("e1", "n1", "n2", "", SourcePointers.relationship(0), "flow", null)),
            List.of(),
            null);

    LayoutRequest request = LayoutRequestMapper.toRequest(graph);

    assertThat(request.layoutRequestSchemaVersion())
        .isEqualTo(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);
    assertThat(request.viewId()).isEqualTo("view-1");
    assertThat(request.nodes().get(0).sourcePointer()).isEqualTo("/nodes/2");
    assertThat(request.nodes().get(0).sourceId()).isEqualTo("n1");
    assertThat(request.nodes().get(0).role()).isEqualTo("lifeline");
    assertThat(request.edges().get(0).sourcePointer()).isEqualTo("/relationships/0");
    assertThat(request.edges().get(0).sourceId()).isEqualTo("e1");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl ir -am test -Dtest=LayoutRequestMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (`LayoutRequestMapper` missing).

- [ ] **Step 3: Write the mapper**

`ir/src/main/java/dev/dediren/ir/LayoutRequestMapper.java`:
```java
package dev.dediren.ir;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutRequest;
import java.util.List;

/** Serializes a {@link SceneGraph} to the public {@code layout-request} record. */
public final class LayoutRequestMapper {
  private LayoutRequestMapper() {}

  public static LayoutRequest toRequest(SceneGraph graph) {
    List<LayoutNode> nodes =
        graph.nodes().stream()
            .map(
                n ->
                    new LayoutNode(
                        n.id(),
                        n.label(),
                        n.id(),
                        n.widthHint(),
                        n.heightHint(),
                        n.role(),
                        n.partition(),
                        n.layerConstraint(),
                        pointerValue(n.origin())))
            .toList();
    List<LayoutEdge> edges =
        graph.edges().stream()
            .map(
                e ->
                    new LayoutEdge(
                        e.id(),
                        e.source(),
                        e.target(),
                        e.label(),
                        e.id(),
                        e.relationshipType(),
                        e.priority(),
                        pointerValue(e.origin())))
            .toList();
    List<LayoutGroup> groups =
        graph.groups().stream()
            .map(g -> new LayoutGroup(g.id(), g.label(), g.members(), g.provenance()))
            .toList();
    return new LayoutRequest(
        ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
        graph.viewId(),
        nodes,
        edges,
        groups,
        List.of(),
        graph.preferences());
  }

  private static String pointerValue(SourcePointer pointer) {
    return pointer == null ? null : pointer.value();
  }
}
```

Note: the third `LayoutNode` argument stays the existing `sourceId` (kept as the scene node's `id` echo for backward compatibility, as projection does today); the new ninth argument is the provenance pointer. Task 7 preserves that `sourceId` behavior when it builds the `SceneNode`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl ir -am test -Dtest=LayoutRequestMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Verify `LayoutGroup` constructor arity** — confirm `new LayoutGroup(String id, String label, List<String> members, GroupProvenance provenance)` matches the record. If it differs, adjust the `groups` mapping to the actual constructor (read `contracts/src/main/java/dev/dediren/contracts/layout/LayoutGroup.java`).

- [ ] **Step 6: Format + commit**

```bash
./mvnw -Pquality spotless:apply -pl ir
git add ir/src/main/java/dev/dediren/ir/LayoutRequestMapper.java ir/src/test/java/dev/dediren/ir/LayoutRequestMapperTest.java
git commit -m "feat(ir): SceneGraph->LayoutRequest mapper stamping provenance (Plan B P1)"
```

---

### Task 7: Projection builds a `SceneGraph` and stamps origins

**Files:**
- Modify: `engines/generic-graph/pom.xml` (add dependency on `ir`)
- Modify: `engines/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java:94-135`
- Test: `engines/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphProvenanceTest.java`

**Interfaces:**
- Consumes: `SceneGraph`, `SceneNode`, `SceneEdge`, `SourcePointers` (Tasks 2, 5), `LayoutRequestMapper` (Task 6).
- Produces: `projectLayoutRequest(...)` returns a `LayoutRequest` whose nodes/edges carry a `source_pointer` addressing the element's index in `source.nodes()` / `source.relationships()`.

- [ ] **Step 1: Add the `ir` dependency** to `engines/generic-graph/pom.xml` (in `<dependencies>`, next to the `contracts` dependency):

```xml
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>ir</artifactId>
      <version>${project.version}</version>
    </dependency>
```

- [ ] **Step 2: Write the failing test**

`engines/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphProvenanceTest.java`:
```java
package dev.dediren.plugins.genericgraph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.source.SourceDocument;
import org.junit.jupiter.api.Test;

class GenericGraphProvenanceTest {
  @Test
  void projectionStampsSourcePointerBySourceIndex() throws Exception {
    // Reuse the module's existing source fixture loader/helper used by GenericGraphProjectionTest.
    SourceDocument source = ProjectionFixtures.basicSource();
    GenericGraphView view = ProjectionFixtures.basicView(source);

    LayoutRequest request = GenericGraphProjection.projectLayoutRequest(source, view, "generic-graph");

    // Every projected node/edge carries a JSON-Pointer into the source arrays.
    assertThat(request.nodes()).allSatisfy(n -> assertThat(n.sourcePointer()).startsWith("/nodes/"));
    assertThat(request.edges()).allSatisfy(e -> assertThat(e.sourcePointer()).startsWith("/relationships/"));
  }
}
```

Note: if the module has no `ProjectionFixtures` helper, inline the same source-document construction that `GenericGraphProjectionTest` already uses (read that test first) rather than adding a new helper.

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -q -pl engines/generic-graph -am test -Dtest=GenericGraphProvenanceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (no `source_pointer` set — currently `null`).

- [ ] **Step 4: Rewrite the node/edge build to go through the `SceneGraph`** — replace `GenericGraphProjection.projectLayoutRequest` body (lines 94–135, up to the `groups` build) so it accumulates `SceneNode`/`SceneEdge` with an origin computed from the source index, then let existing group logic stay, and finally return via the mapper. Concretely:

Replace the node loop (lines 97–117) with:
```java
    var sceneNodes = new java.util.ArrayList<dev.dediren.ir.SceneNode>();
    var sourceNodeOrder = source.nodes();
    for (String id : selectedView.nodes()) {
      int sourceIndex = indexOfNode(sourceNodeOrder, id);
      SourceNode sourceNode = sourceNodeOrder.get(sourceIndex);
      if (isSourceOnlySequenceFragment(semanticProfile, selectedView, sourceNode)) {
        continue;
      }
      sceneNodes.add(
          new dev.dediren.ir.SceneNode(
              sourceNode.id(),
              sourceNode.label(),
              dev.dediren.ir.SourcePointers.node(sourceIndex),
              GenericGraphLayoutSizing.widthHint(semanticProfile, sourceNode),
              GenericGraphLayoutSizing.heightHint(semanticProfile, sourceNode),
              layoutRole(semanticProfile, sourceNode.type()),
              sourceNode.partition(),
              sourceNode.layerConstraint()));
    }
```

Replace the edge loop (lines 119–135) with:
```java
    var sceneEdges = new java.util.ArrayList<dev.dediren.ir.SceneEdge>();
    var sourceRelationshipOrder = source.relationships();
    for (String id : selectedView.relationships()) {
      int sourceIndex = indexOfRelationship(sourceRelationshipOrder, id);
      SourceRelationship relationship = sourceRelationshipOrder.get(sourceIndex);
      sceneEdges.add(
          new dev.dediren.ir.SceneEdge(
              relationship.id(),
              relationship.source(),
              relationship.target(),
              relationship.label(),
              dev.dediren.ir.SourcePointers.relationship(sourceIndex),
              relationship.type(),
              relationship.priority()));
    }
```

Add these private helpers to the class:
```java
  private static int indexOfNode(java.util.List<SourceNode> nodes, String id) throws IOException {
    for (int i = 0; i < nodes.size(); i++) {
      if (nodes.get(i).id().equals(id)) {
        return i;
      }
    }
    throw new IOException("view references missing node " + id);
  }

  private static int indexOfRelationship(java.util.List<SourceRelationship> relationships, String id)
      throws IOException {
    for (int i = 0; i < relationships.size(); i++) {
      if (relationships.get(i).id().equals(id)) {
        return i;
      }
    }
    throw new IOException("view references missing relationship " + id);
  }
```

Then, where the method currently assembles and returns the `LayoutRequest` from `nodes`/`edges`/`groups`/`constraints`/`preferences`, build a `SceneGraph` and delegate node/edge mapping to `LayoutRequestMapper`, but keep the existing `groups`, `constraints`, and `preferences` wiring intact. The simplest behavior-preserving shape: assemble the `LayoutRequest` from the mapper's node/edge output while retaining the existing `groups`/`constraints`/`preferences` computation:

```java
    LayoutRequest mapped =
        dev.dediren.ir.LayoutRequestMapper.toRequest(
            new dev.dediren.ir.SceneGraph(selectedView.id(), sceneNodes, sceneEdges, java.util.List.of(), preferences));
    return new LayoutRequest(
        mapped.layoutRequestSchemaVersion(),
        mapped.viewId(),
        mapped.nodes(),
        mapped.edges(),
        groups,       // existing computed groups
        constraints,  // existing computed constraints
        preferences); // existing computed preferences
```

Note: this keeps the existing `groups`/`constraints`/`preferences` derivation untouched (the sequence `uml.sequence.*` constraints still flow) — P1 only redirects node/edge construction through the scene graph so origins are stamped. The `LayoutRequestMapper` groups path is exercised by its own unit test; here the projection's richer group logic wins.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -q -pl engines/generic-graph -am test -Dtest=GenericGraphProvenanceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Run the module's full test suite** (catch projection regressions)

Run: `./mvnw -q -pl engines/generic-graph -am test`
Expected: PASS.

- [ ] **Step 7: Format + commit**

```bash
./mvnw -Pquality spotless:apply -pl engines/generic-graph
git add engines/generic-graph/pom.xml engines/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java engines/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphProvenanceTest.java
git commit -m "feat(generic-graph): stamp source_pointer via SceneGraph projection (Plan B P1)"
```

---

### Task 8: `elk-layout` propagates `source_pointer` through layout

**Files:**
- Modify: `engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java` (LaidOut construction sites near lines 153, 171, 246, 404, 429)
- Modify: `engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/SequenceLayoutConstraints.java` (LaidOut construction sites near lines 165, 214, 281)
- Test: `engines/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutProvenanceTest.java`

**Interfaces:**
- Consumes: the v2 `LayoutRequest` carrying `LayoutNode.sourcePointer()` / `LayoutEdge.sourcePointer()`.
- Produces: `layout(...)` output whose `LaidOutNode.sourcePointer()` / `LaidOutEdge.sourcePointer()` equal the corresponding request element's pointer.

- [ ] **Step 1: Write the failing test**

`engines/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutProvenanceTest.java`:
```java
package dev.dediren.plugins.elklayout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElkLayoutProvenanceTest {
  @Test
  void layoutCarriesSourcePointerFromRequestToResult() throws Exception {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v2",
            "view-1",
            List.of(
                new LayoutNode("a", "A", "a", 40.0, 30.0, null, null, null, "/nodes/0"),
                new LayoutNode("b", "B", "b", 40.0, 30.0, null, null, null, "/nodes/1")),
            List.of(new LayoutEdge("e1", "a", "b", "", "e1", "flow", null, "/relationships/0")),
            List.of(),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request).value();

    assertThat(result.nodes()).allSatisfy(n -> assertThat(n.sourcePointer()).startsWith("/nodes/"));
    assertThat(result.edges().get(0).sourcePointer()).isEqualTo("/relationships/0");
  }
}
```

Note: confirm the engine's public entry point and result unwrapping against `ElkLayoutEngine` (it returns `EngineResult<LayoutResult>`; adjust `.value()` if the accessor differs).

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl engines/elk-layout -am test -Dtest=ElkLayoutProvenanceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (result pointers are `null`).

- [ ] **Step 3: Thread the pointer through the engine** — build a lookup once per layout from the request, keyed by the layout element id, and pass the pointer into every `LaidOutNode` / `LaidOutEdge` constructor.

At the top of the layout method in `ElkLayoutEngine`, after the request is available, add:
```java
    java.util.Map<String, String> nodePointers = new java.util.HashMap<>();
    for (LayoutNode n : request.nodes()) {
      nodePointers.put(n.id(), n.sourcePointer());
    }
    java.util.Map<String, String> edgePointers = new java.util.HashMap<>();
    for (LayoutEdge e : request.edges()) {
      edgePointers.put(e.id(), e.sourcePointer());
    }
```

At each `new LaidOutNode(...)` site, append `nodePointers.get(<thatNodeId>)` as the final `sourcePointer` argument (the sites build from an ELK node whose identifier maps back to the layout node id — use that id as the key). At each `new LaidOutEdge(...)` site, append `edgePointers.get(<thatEdgeId>)`.

For `SequenceLayoutConstraints` (which rewrites geometry in `normalize`), thread the same two maps in: pass them into the `normalize`/`normalized*` methods (or construct `SequenceLayoutConstraints` with them) and append the looked-up pointer at its `new LaidOutNode(...)` (lines 165, 214) and `new LaidOutEdge(...)` (line 281) sites, keyed by the node/edge id each rebuilds.

Note: keep it a pure copy-through — do not synthesize pointers for nodes ELK adds that have no request counterpart; `map.get` yields `null` for those, which is acceptable (optional field).

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl engines/elk-layout -am test -Dtest=ElkLayoutProvenanceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Run the ELK module + dist smoke** (this module is layout-critical)

Run: `./mvnw -q -pl engines/elk-layout -am test`
Then: `./mvnw -q -pl dist-tool -am verify -Pdist-smoke`
Expected: PASS.

- [ ] **Step 6: Format + commit**

```bash
./mvnw -Pquality spotless:apply -pl engines/elk-layout
git add engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/SequenceLayoutConstraints.java engines/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutProvenanceTest.java
git commit -m "feat(elk-layout): propagate source_pointer through layout (Plan B P1)"
```

---

### Task 9: Sync the move-together set + full verification

**Files:**
- Modify: `fixtures/layout-request/basic.json`
- Modify: `fixtures/layout-result/basic.json`
- Modify: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java` (inline `layout_request_schema_version` string at ~line 381)
- Modify: `docs/agent-usage.md` (only if it names a `layout-*.schema.v1` string — grep first)
- Modify: any version-assertion surface that pins `layout-request.schema.v1` / `layout-result.schema.v1` (grep the whole tree)

**Interfaces:**
- Consumes: everything from Tasks 3–8.
- Produces: a green `./mvnw clean verify` and dist smoke with the v2 layout schemas.

- [ ] **Step 1: Find every stale `v1` layout schema reference**

Run:
```bash
grep -rn "layout-request.schema.v1\|layout-result.schema.v1" --include=*.java --include=*.json --include=*.md . | grep -v target/
```
Expected: the `basic.json` fixtures, the inline JSON in `ContractRoundTripTest`, and possibly `docs/agent-usage.md`. Record the list.

- [ ] **Step 2: Add `source_pointer` + bump the version in `fixtures/layout-request/basic.json`** — set `"layout_request_schema_version": "layout-request.schema.v2"` and add a `"source_pointer": "/nodes/<i>"` to each node (matching its position) and `"source_pointer": "/relationships/<i>"` to each edge. Use the node/edge order already in the file.

- [ ] **Step 3: Add `source_pointer` + bump the version in `fixtures/layout-result/basic.json`** — set `"layout_result_schema_version": "layout-result.schema.v2"` and add the matching `"source_pointer"` to each node/edge (same pointers as the request fixture for the same ids).

- [ ] **Step 4: Update the inline schema string in `ContractRoundTripTest`** — change the `"layout_request_schema_version": "layout-request.schema.v1"` literal (~line 381) to `...v2`, and any `layout-result.schema.v1` literal likewise. The assertions on `ContractVersions.LAYOUT_*_SCHEMA_VERSION` (lines 371–375) need no change (they read the constant).

- [ ] **Step 5: Update `docs/agent-usage.md` if needed** — if Step 1 found a `layout-*.schema.v1` token there, bump it to `v2`. The table rows at lines 43/45 reference the schema *files* (not versions) and need no change.

- [ ] **Step 6: Run the full clean gate** (schema-id constant changed ⇒ `clean` is required)

Run (sandbox-disabled): `./mvnw clean verify`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Run the quality gate + dist smoke + whitespace check**

```bash
./mvnw -Pquality verify
./mvnw -pl dist-tool -am verify -Pdist-smoke
git diff --check
```
Expected: all green; no whitespace errors.

- [ ] **Step 8: Commit**

```bash
git add fixtures/layout-request/basic.json fixtures/layout-result/basic.json contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java docs/agent-usage.md
git commit -m "test+docs: sync fixtures, round-trip, agent-usage to layout schema v2 (Plan B P1)"
```

Note: if `AgentUsageDocConsistencyTest` (dist-tool) fails, it means a `DEDIREN_*`/version token in `docs/agent-usage.md` drifted — reconcile per its message before considering P1 done.

---

## Follow-on phases (separate plans, authored after P1 lands)

- **P2 — invariants + property tests + fixture full-replace.** Introduce `LaidOutScene` + `LayoutResult → LaidOutScene` mapper; port `LayoutQuality` geometric checks to IR invariants; add jqwik `SequenceModelGenerator` property tests; regenerate the 14 idealized `layout-result` fixtures as real-engine characterization snapshots and delete idealized-oracle assertions; consider tightening `source_pointer` to `required`.
- **P3 — carve `generic-graph` into `semantics-archimate/uml/graph`.** Introduce typed `NodeRole`, the `SequenceConstraint` family, notation invariants, and UML→`LayoutIntent` lowering; delete `engines/generic-graph`; extend ArchUnit.
- **P4 — typed-IR piping.** Flip `engine-api` to speak IR end-to-end; `BuildCommand` passes IR in memory; remove the `SequenceLayoutConstraints` UML re-derivation from `elk-layout`.

## Self-Review

- **Spec coverage (P1 slice):** `ir` module scaffold (T1); provenance value/helper (T2); provenance on both public artifacts + v2 schemas (T3–T4); minimal `SceneGraph` (T5); `SceneGraph→LayoutRequest` mapper (T6); projection stamps origins (T7); layout propagates provenance (T8); move-together sync + full verify (T9). Deferred spec items (twin `LaidOutScene`, `NodeRole`/`LayoutIntent`, `semantics-*` split, invariants, fixture replace, typed-IR piping) are explicitly assigned to P2–P4.
- **Placeholder scan:** no "TBD/TODO"; the two "confirm the accessor/constructor arity against the real file" notes (T6 Step 5, T8 Step 1/3) are verification steps, not placeholders — they guard against drift in constructor shapes the plan could not fully pin without opening every call site.
- **Type consistency:** `SceneNode`/`SceneEdge`/`SceneGraph`/`SourcePointer`/`SourcePointers`/`LayoutRequestMapper.toRequest` names are used identically across T2/T5/T6/T7; the v2 `LayoutNode` (9-arg) and `LaidOutNode` (10-arg) shapes are used consistently in T3/T4/T6/T8.
- **Known risk carried into execution:** T8 depends on the ELK construction sites keying laid-out elements back to request ids; if a site has no request id in scope, thread the id from the ELK node identifier (the engine already round-trips ids for `sourceId`/`projectionId`, so the same key is available).

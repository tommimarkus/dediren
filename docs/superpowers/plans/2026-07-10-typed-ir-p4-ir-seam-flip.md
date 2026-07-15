# Typed IR P4 — IR Seam Flip Implementation Plan

Status: complete — Plan B P1–P5 all shipped by 2026.07.15.

> Erratum 2026-07-15: jqwik was removed 2026-07-14 (7b520b0). Read "jqwik
> property test" as the seeded JUnit `@ParameterizedTest` sequence property
> suite that replaced it.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Flip the `engine-api` boundary so the layout pipeline speaks the typed `ir` IR (`SceneGraph` pre-layout, `LaidOutScene` post-layout) end-to-end, and make the in-memory `build` driver pipe IR between stages with no JSON re-serialization — all behavior-preserving, no schema change.

**Architecture:** Parallel Change. Grow the IR so `SceneGraph ↔ LayoutRequest` is a lossless round-trip, then flip each engine-api interface to IR one capability at a time. The heavy engine internals (`ElkLayoutEngine`, `SequenceLayoutConstraints`, the ~14 render files) stay record-based and are adapted at their thin engine-boundary class via the `ir` mappers, so byte-stability is provable (`toResult ∘ toScene = identity`; `toRequest ∘ toSceneGraph = identity`). `CoreCommands` maps IR↔record at the CLI edges so the standalone `project | layout | render | export` wire stays byte-for-byte identical; only `build` goes JSON-free between stages. Export stays record-based (its `ExportRequest` is a wire contract in `contracts`; carrying `ir` types would break `export-request.schema.v1` and create a `contracts → ir → contracts` cycle), so `build` maps its in-memory `LaidOutScene → LayoutResult` (object map, no JSON) to assemble the export request.

**Tech Stack:** Java 21, Maven (checked-in wrapper), Jackson 3 (`tools.jackson`), JUnit 6 + AssertJ + jqwik (test), Eclipse ELK (layout), ArchUnit (boundary enforcement in `dist-tool`), google-java-format via Spotless, SpotBugs.

## Global Constraints

- **No schema change.** `layout-request.schema.v2`, `layout-result.schema.v2`, `export-request.schema.v1`, `render-*`, `build-result.schema.v1` all stay. This phase is behavior-preserving; no `ContractVersions.*` id changes. The public JSON emitted by every standalone command stays byte-for-byte identical (there is no new field — provenance `sourcePointer` already shipped in P1/P2).
- **Wire byte-stability is the acceptance oracle.** `dediren project|layout|render|export` output, and every `build` artifact + `--emit` envelope, must be byte-identical to pre-P4. Round-trip identity of the mappers is what guarantees it.
- **Dependency direction stays acyclic, rooted at contracts:** `contracts → ir → engine-api → {semantics-*, elk-layout, render, export}`; `core → engine-api, ir`; `cli` wires. `ir` never depends on `engine-api`, `core`, `cli`, or an engine (ArchUnit `irDependsOnlyOnContracts`). `contracts` never depends on `ir` (ArchUnit `contractsDependsOnNothingInternal`) — this is why export stays record-based.
- **Do not touch geometry.** `elk-layout` still consumes the stringly `uml.sequence.*` constraints via `SequenceLayoutConstraints`. The typed `LayoutIntent` vocabulary and deleting that re-derivation are P5, explicitly out of scope here.
- **Render-metadata is not retyped.** `SemanticsEngine.projectRenderMetadata → RenderMetadata` and `RenderEngine`'s `RenderMetadata` param keep their own channel (design §"Render-metadata is out of scope").
- **Java is formatted by google-java-format via Spotless.** Run `./mvnw -Pquality spotless:apply` before each commit.
- **Maven tests need the sandbox disabled** locally (JUnit `@TempDir` on read-only `/tmp`). `JsonSupportFuzzTest` also fails only under the sandbox — rerun sandbox-disabled, do not "fix" it.
- **Any module-topology change (new `ir` deps) must be validated with `-Pdist-smoke`, not only `-Pquality verify`** — the P3 lesson: appassembler classpath / first-party-artifact gaps surface only in dist-smoke.

---

## File Structure

New/changed responsibilities (untouched files are noted to bound the blast radius):

**`ir` (Task 1) — grow to be the full pre-layout truth + lossless bridge**
- Modify `ir/src/main/java/dev/dediren/ir/SceneGraph.java`: add `List<LayoutConstraint> constraints`.
- Modify `ir/src/main/java/dev/dediren/ir/SceneNode.java`: add `String sourceId`; reshape fields to mirror `LayoutNode` positionally.
- Modify `ir/src/main/java/dev/dediren/ir/SceneEdge.java`: add `String sourceId`; reshape to mirror `LayoutEdge`.
- Modify `ir/src/main/java/dev/dediren/ir/LayoutRequestMapper.java`: `toRequest` carries `constraints` + real `sourceId`; add `toSceneGraph(LayoutRequest)`.
- `ir/.../LaidOutSceneMapper.java`, `LaidOutScene`, `PlacedNode`, `RoutedEdge`, `PlacedGroup`: **untouched** — already lossless 1:1 with `LayoutResult`.

**`engine-api` (Tasks 2–4) — interfaces speak IR**
- `SemanticsEngine.java`: `projectLayoutRequest → projectScene : EngineResult<SceneGraph>`.
- `LayoutEngine.java`: `parseRequest : SceneGraph`; `layout(SceneGraph) : EngineResult<LaidOutScene>`.
- `RenderEngine.java`: `render(LaidOutScene, JsonNode, RenderMetadata)`.
- `ExportEngine.java`, `contracts/.../ExportRequest.java`: **untouched** (record-based export).
- `engine-api/pom.xml`: add `ir` dependency (Task 2).

**engine boundary classes (Tasks 2–4) — adapt via mappers, internals untouched**
- `semantics-graph/.../SemanticsRouterEngine.java`, `.../SceneProjection.java` (Task 2).
- `engines/elk-layout/.../ElkEngine.java` + `pom.xml` (Task 3). `ElkLayoutEngine.java`, `SequenceLayoutConstraints.java`, `LayoutJson.java`: **untouched**.
- `engines/render/.../SvgRenderEngine.java` + `pom.xml` (Task 4). `svg/*`, `node/*`, `style/*`: **untouched**.
- `engines/archimate-oef-export/*`, `engines/uml-xmi-export/*`: **untouched** (export stays record-based).

**`core` (Tasks 2–5) — edge mapping + IR-native build**
- `CoreCommands.java`: map IR↔record at each stage edge (Tasks 2–4).
- `core/pom.xml`: add `ir` (Task 2).
- `engine/EngineDispatch.java`: split invoke/serialize for in-memory reuse (Task 5).
- `commands/BuildCommand.java`: pipe IR in memory (Task 5).

**enforcement + docs (Task 6)**
- `dist-tool/.../ArchitectureRulesTest.java`: honesty updates to `because(...)` strings (no structural rule change).
- `docs/architecture-guidelines.md`: dependency-edge table + tier notes.

---

## Task 1: Grow the IR and make `LayoutRequest ↔ SceneGraph` a lossless round-trip

**Files:**
- Modify: `ir/src/main/java/dev/dediren/ir/SceneGraph.java`
- Modify: `ir/src/main/java/dev/dediren/ir/SceneNode.java`
- Modify: `ir/src/main/java/dev/dediren/ir/SceneEdge.java`
- Modify: `ir/src/main/java/dev/dediren/ir/LayoutRequestMapper.java`
- Modify: `semantics-graph/src/main/java/dev/dediren/semantics/graph/SceneProjection.java:107-141` (construction sites, to keep compiling; still returns `LayoutRequest`)
- Test: `ir/src/test/java/dev/dediren/ir/LayoutRequestMapperTest.java` (round-trip cases)
- Test (adjust construction args only): existing IR tests that call `new SceneNode(...)`/`new SceneEdge(...)`/`new SceneGraph(...)`

**Interfaces:**
- Produces:
  - `SceneNode(String id, String label, String sourceId, Double widthHint, Double heightHint, String role, Integer partition, LayoutLayerConstraint layerConstraint, SourcePointer origin)` — positionally mirrors `LayoutNode` (origin ↔ sourcePointer last).
  - `SceneEdge(String id, String source, String target, String label, String sourceId, String relationshipType, LayoutEdgePriority priority, SourcePointer origin)` — mirrors `LayoutEdge`.
  - `SceneGraph(String viewId, List<SceneNode> nodes, List<SceneEdge> edges, List<SceneGroup> groups, List<LayoutConstraint> constraints, LayoutPreferences preferences)`.
  - `LayoutRequestMapper.toRequest(SceneGraph) : LayoutRequest` (now carries constraints + real sourceId).
  - `LayoutRequestMapper.toSceneGraph(LayoutRequest) : SceneGraph` (new; inverse of `toRequest`).

- [ ] **Step 1: Write the failing round-trip test**

Add to `LayoutRequestMapperTest.java`. Build a `LayoutRequest` that exercises every field including `sourceId != id` and a `uml.sequence.*` constraint, assert `toRequest(toSceneGraph(request)).equals(request)`:

```java
@Test
void toSceneGraphThenToRequestIsIdentity() {
  LayoutRequest request =
      new LayoutRequest(
          ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
          "v1",
          List.of(
              new LayoutNode(
                  "n1", "N1", "src-n1", 160.0, 80.0, "lifeline", 2,
                  LayoutLayerConstraint.FIRST, "/nodes/0")),
          List.of(
              new LayoutEdge(
                  "e1", "n1", "n1", "E1", "src-e1", "Message",
                  LayoutEdgePriority.of(5), "/relationships/0")),
          List.of(new LayoutGroup("g1", "G1", List.of("n1"), GroupProvenance.visualOnly())),
          List.of(new LayoutConstraint("c1", "uml.sequence.lifeline-order", List.of("n1"))),
          new LayoutPreferences(/* use the smallest valid preferences the fixtures use */));
  assertThat(LayoutRequestMapper.toRequest(LayoutRequestMapper.toSceneGraph(request)))
      .isEqualTo(request);
}
```

(Check `LayoutEdgePriority` / `LayoutPreferences` constructors in `contracts` and mirror an existing `LayoutRequestMapperTest` fixture for the exact `LayoutPreferences` argument.)

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `./mvnw -pl ir -am test -Dtest=LayoutRequestMapperTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `toSceneGraph` does not exist; `SceneNode`/`SceneEdge`/`SceneGraph` constructors have the old arity.

- [ ] **Step 3: Reshape `SceneNode`**

```java
package dev.dediren.ir;

import dev.dediren.contracts.layout.LayoutLayerConstraint;

/** A pre-layout scene node; positionally mirrors {@link dev.dediren.contracts.layout.LayoutNode}. */
public record SceneNode(
    String id,
    String label,
    String sourceId,
    Double widthHint,
    Double heightHint,
    String role,
    Integer partition,
    LayoutLayerConstraint layerConstraint,
    SourcePointer origin) {}
```

- [ ] **Step 4: Reshape `SceneEdge`**

```java
package dev.dediren.ir;

import dev.dediren.contracts.layout.LayoutEdgePriority;

/** A pre-layout scene edge; positionally mirrors {@link dev.dediren.contracts.layout.LayoutEdge}. */
public record SceneEdge(
    String id,
    String source,
    String target,
    String label,
    String sourceId,
    String relationshipType,
    LayoutEdgePriority priority,
    SourcePointer origin) {}
```

- [ ] **Step 5: Add `constraints` to `SceneGraph`**

```java
package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.layout.LayoutPreferences;
import java.util.List;

/** The pre-layout typed scene graph produced by projection and mapped to the layout-request. */
public record SceneGraph(
    String viewId,
    List<SceneNode> nodes,
    List<SceneEdge> edges,
    List<SceneGroup> groups,
    List<LayoutConstraint> constraints,
    LayoutPreferences preferences) {
  public SceneGraph {
    nodes = listOrEmpty(nodes);
    edges = listOrEmpty(edges);
    groups = listOrEmpty(groups);
    constraints = listOrEmpty(constraints);
  }
}
```

- [ ] **Step 6: Rewrite `LayoutRequestMapper` bidirectional + lossless**

```java
public static LayoutRequest toRequest(SceneGraph graph) {
  List<LayoutNode> nodes =
      graph.nodes().stream()
          .map(n -> new LayoutNode(n.id(), n.label(), n.sourceId(), n.widthHint(),
              n.heightHint(), n.role(), n.partition(), n.layerConstraint(), pointerValue(n.origin())))
          .toList();
  List<LayoutEdge> edges =
      graph.edges().stream()
          .map(e -> new LayoutEdge(e.id(), e.source(), e.target(), e.label(), e.sourceId(),
              e.relationshipType(), e.priority(), pointerValue(e.origin())))
          .toList();
  List<LayoutGroup> groups =
      graph.groups().stream()
          .map(g -> new LayoutGroup(g.id(), g.label(), g.members(), g.provenance()))
          .toList();
  return new LayoutRequest(
      ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION, graph.viewId(), nodes, edges, groups,
      graph.constraints(), graph.preferences());
}

public static SceneGraph toSceneGraph(LayoutRequest request) {
  List<SceneNode> nodes =
      request.nodes().stream()
          .map(n -> new SceneNode(n.id(), n.label(), n.sourceId(), n.widthHint(), n.heightHint(),
              n.role(), n.partition(), n.layerConstraint(), originOf(n.sourcePointer())))
          .toList();
  List<SceneEdge> edges =
      request.edges().stream()
          .map(e -> new SceneEdge(e.id(), e.source(), e.target(), e.label(), e.sourceId(),
              e.relationshipType(), e.priority(), originOf(e.sourcePointer())))
          .toList();
  List<SceneGroup> groups =
      request.groups().stream()
          .map(g -> new SceneGroup(g.id(), g.label(), g.members(), g.provenance()))
          .toList();
  return new SceneGraph(
      request.viewId(), nodes, edges, groups, request.constraints(), request.layoutPreferences());
}

private static String pointerValue(SourcePointer pointer) {
  return pointer == null ? null : pointer.value();
}

private static SourcePointer originOf(String sourcePointer) {
  return sourcePointer == null ? null : new SourcePointer(sourcePointer);
}
```

- [ ] **Step 7: Fix the `SceneProjection` construction sites so semantics-graph still compiles (no behavior change)**

In `SceneProjection.projectLayoutRequest` (still returning `LayoutRequest` in this task), update the constructors to the new arity, passing `id` as `sourceId` (this reproduces the old `toRequest` behavior where `sourceId = n.id()`), and `origin` last:

```java
// node loop (was: new dev.dediren.ir.SceneNode(id, label, SourcePointers.node(idx), widthHint, ...))
sceneNodes.add(new dev.dediren.ir.SceneNode(
    id, label, id /* sourceId */, notation.widthHint(node), notation.heightHint(node),
    notation.layoutRole(node.type()), partition, layerConstraint,
    dev.dediren.ir.SourcePointers.node(sourceIndex)));

// edge loop
sceneEdges.add(new dev.dediren.ir.SceneEdge(
    id, source, target, label, id /* sourceId */, type, priority,
    dev.dediren.ir.SourcePointers.relationship(sourceIndex)));

// SceneGraph build (constraints slot now present; still empty here, still ignored downstream)
LayoutRequest mapped =
    dev.dediren.ir.LayoutRequestMapper.toRequest(
        new dev.dediren.ir.SceneGraph(
            selectedView.id(), sceneNodes, sceneEdges,
            java.util.List.of(), java.util.List.of(), selectedView.layoutPreferences()));
```

Also fix any `new SceneNode/SceneEdge/SceneGraph` in IR tests to the new arity.

- [ ] **Step 8: Run the round-trip test + module suites to green**

Run: `./mvnw -pl ir,semantics-graph -am test` (sandbox disabled)
Expected: PASS. Confirm `LayoutRequestMapperTest` round-trip passes and semantics-graph projection tests are unchanged-green (byte behavior of `projectLayoutRequest` is preserved: `sourceId` was already `id`, constraints still injected at the final `LayoutRequest`).

- [ ] **Step 9: Format + commit**

```bash
./mvnw -Pquality spotless:apply
git add ir/ semantics-graph/src/main/java/dev/dediren/semantics/graph/SceneProjection.java \
  semantics-graph/src/test ir/src/test
git commit -m "feat(ir): grow SceneGraph with constraints + lossless bidirectional LayoutRequest mapper (Plan B P4)"
```

---

## Task 2: Flip `SemanticsEngine` to produce `SceneGraph`

**Files:**
- Modify: `engine-api/src/main/java/dev/dediren/engine/SemanticsEngine.java`
- Modify: `engine-api/pom.xml` (add `ir`)
- Modify: `core/pom.xml` (add `ir`)
- Modify: `semantics-graph/src/main/java/dev/dediren/semantics/graph/SemanticsRouterEngine.java:105-116`
- Modify: `semantics-graph/src/main/java/dev/dediren/semantics/graph/SceneProjection.java:95-195`
- Modify: `core/src/main/java/dev/dediren/core/commands/CoreCommands.java:87-114` (`projectCommand`)
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java` (`engineApiDependsOnlyOnContracts` because-string)
- Test doubles: `core/src/test/java/dev/dediren/core/commands/BuildCommandTest.java:647` (`FakeSemanticsEngine`)
- Tests: `semantics-graph/src/test/.../SceneProjectionTest.java`, `SemanticsRouterEngineTest.java`, `SceneProjectionProvenanceTest.java`, `RouterHarness.java`; `core/.../CoreCommandsTest.java`

**Interfaces:**
- Consumes: `SceneGraph`, `LayoutRequestMapper.toRequest` (Task 1).
- Produces: `SemanticsEngine.projectScene(SourceDocument source, String view) : EngineResult<SceneGraph>`; `SceneProjection.projectScene(source, selectedView, notation) : SceneGraph`.

- [ ] **Step 1: Write the failing test — projection returns a complete SceneGraph**

In `SceneProjectionTest.java`, assert the returned `SceneGraph` carries nodes, edges, groups, constraints, and preferences (drive a UML-sequence source so constraints are non-empty):

```java
@Test
void projectSceneCarriesConstraintsAndGroups() {
  SceneGraph scene = SceneProjection.projectScene(source, umlSequenceView, umlNotation);
  assertThat(scene.constraints()).extracting(LayoutConstraint::kind)
      .contains("uml.sequence.lifeline-order", "uml.sequence.message-order");
  assertThat(scene.viewId()).isEqualTo(umlSequenceView.id());
  // and the mapped request equals the old projectLayoutRequest output:
  assertThat(LayoutRequestMapper.toRequest(scene)).isEqualTo(expectedLayoutRequest);
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./mvnw -pl semantics-graph -am test -Dtest=SceneProjectionTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `projectScene` does not exist.

- [ ] **Step 3: Add `ir` to `engine-api/pom.xml` and `core/pom.xml`**

In each `<dependencies>` block (after `contracts`):

```xml
<dependency>
  <groupId>dev.dediren</groupId>
  <artifactId>ir</artifactId>
  <version>${project.version}</version>
</dependency>
```

- [ ] **Step 4: Flip the `SemanticsEngine` interface**

```java
package dev.dediren.engine;

import dev.dediren.contracts.layout.SemanticValidationResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.ir.SceneGraph;

/** Notation-specific semantics: source validation and projection into the layout/render inputs. */
public interface SemanticsEngine {
  String id();

  EngineResult<SemanticValidationResult> validate(SourceDocument source, String profile)
      throws EngineException;

  EngineResult<SceneGraph> projectScene(SourceDocument source, String view) throws EngineException;

  EngineResult<RenderMetadata> projectRenderMetadata(SourceDocument source, String view)
      throws EngineException;
}
```

- [ ] **Step 5: Rewrite `SceneProjection.projectScene` to return the complete `SceneGraph`**

Rename `projectLayoutRequest → projectScene`, return type `SceneGraph`. Build `sceneNodes`/`sceneEdges` as in Task 1, build `sceneGroups` from the existing group-rebuild logic (produce `dev.dediren.ir.SceneGroup(id, label, members, provenance)` instead of `LayoutGroup` — identical fields), and put constraints in the graph:

```java
static SceneGraph projectScene(
    SourceDocument source, GenericGraphView selectedView, NotationSemantics notation)
    throws IOException {
  // ... build sceneNodes (with sourceId=id, origin last) and sceneEdges as in Task 1 ...
  // ... rebuild groups as today but as SceneGroup (same id/label/members/provenance) ...
  return new SceneGraph(
      selectedView.id(),
      sceneNodes,
      sceneEdges,
      sceneGroups,
      notation.layoutConstraints(source, selectedView), // the single constraint injection point, unchanged
      selectedView.layoutPreferences());
}
```

Remove the intermediate `LayoutRequestMapper.toRequest(...)` call and the `mapped.nodes()/edges()` indirection — the projection now owns the whole `SceneGraph` directly.

- [ ] **Step 6: Update `SemanticsRouterEngine.projectScene`**

```java
@Override
public EngineResult<SceneGraph> projectScene(SourceDocument source, String view)
    throws EngineException {
  Projection projection = prepareProjection(source, view);
  try {
    return new EngineResult<>(
        SceneProjection.projectScene(source, projection.view(), projection.notation()), List.of());
  } catch (IOException error) {
    throw new UncheckedIOException(error);
  }
}
```

- [ ] **Step 7: Map at the CLI edge in `CoreCommands.projectCommand`**

The `"layout-request"` target must still serialize a `layout-request` envelope, so map `SceneGraph → LayoutRequest`:

```java
if ("layout-request".equals(target)) {
  return EngineDispatch.dispatch(
      engineId,
      () -> {
        EngineResult<SceneGraph> projected = semantics.projectScene(source, view);
        return new EngineResult<>(
            LayoutRequestMapper.toRequest(projected.value()), projected.diagnostics());
      });
}
```

(Add imports `dev.dediren.ir.LayoutRequestMapper`, `dev.dediren.ir.SceneGraph`.)

- [ ] **Step 8: Update the `FakeSemanticsEngine` test double + semantics tests**

`BuildCommandTest.FakeSemanticsEngine` (line 647): implement `projectScene` returning `EngineResult<SceneGraph>` (build a small `SceneGraph`, or map its existing `LayoutRequest` fixture via `LayoutRequestMapper.toSceneGraph`). Update `SceneProjectionTest`, `SemanticsRouterEngineTest`, `SceneProjectionProvenanceTest`, `RouterHarness` to call `projectScene` and assert against the `SceneGraph` (map to `LayoutRequest` where they previously asserted request fields).

- [ ] **Step 9: Honesty update to ArchUnit `engineApiDependsOnlyOnContracts`**

The rule already passes (`ir` is not in its forbidden list, and `engine-api → ir → contracts` is acyclic). Update only the `.because(...)` text:

```java
.because(
    "engine-api is the shared engine-facing interface surface and depends only on contracts"
        + " and ir (the typed scene IR it now speaks), never core or any engine implementation")
```

- [ ] **Step 10: Run affected suites + the ArchUnit gate**

Run: `./mvnw -pl core,semantics-graph,dist-tool -am test` (sandbox disabled)
Expected: PASS. `ArchitectureRulesTest` green (no cycle; engine-api→ir allowed). `dediren project --target layout-request` behavior byte-identical (asserted via CoreCommandsTest / any project envelope test).

- [ ] **Step 11: Format + commit**

```bash
./mvnw -Pquality spotless:apply
git add engine-api/ core/ semantics-graph/ dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java
git commit -m "feat(engine-api): SemanticsEngine.projectScene returns SceneGraph; map to layout-request at the CLI edge (Plan B P4)"
```

---

## Task 3: Flip `LayoutEngine` to `SceneGraph → LaidOutScene`

**Files:**
- Modify: `engine-api/src/main/java/dev/dediren/engine/LayoutEngine.java`
- Modify: `engines/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkEngine.java`
- Modify: `engines/elk-layout/pom.xml` (add `ir`)
- Modify: `core/src/main/java/dev/dediren/core/commands/CoreCommands.java:44-79` (`layoutCommand`)
- Test doubles: `core/.../CoreCommandsTest.java:162` (`FakeLayoutEngine`), `engine-api/.../EnginesTest.java:57` (`FakeLayoutEngine`), `core/.../BuildCommandTest.java:710` (`FakeLayoutEngine`)
- Tests: `engines/elk-layout/.../ElkEngineTest.java`; `cli/.../EngineEnvelopeContractTest.java`, `cli/.../LayoutFixtureRegenerator.java`
- **Untouched:** `ElkLayoutEngine.java`, `SequenceLayoutConstraints.java`, `LayoutJson.java`, `ElkLayoutEngineTest.java` (252 refs — tests `ElkLayoutEngine` directly, below the flipped boundary).

**Interfaces:**
- Consumes: `SceneGraph`, `LayoutRequestMapper.{toRequest,toSceneGraph}`, `LaidOutSceneMapper.toScene`.
- Produces: `LayoutEngine.parseRequest(byte[]) : SceneGraph`; `LayoutEngine.layout(SceneGraph) : EngineResult<LaidOutScene>`.

- [ ] **Step 1: Write the failing test — boundary round-trip stability**

In `ElkEngineTest.java`, assert `parseRequest` yields a `SceneGraph` and `layout` a `LaidOutScene`, and that a request with `sourceId != id` lays out identically to laying out the mapped `LayoutRequest` directly:

```java
@Test
void parseRequestThenLayoutMatchesDirectRecordLayout() throws Exception {
  byte[] bytes = /* a layout-request JSON with a node whose sourceId != id */;
  ElkEngine engine = new ElkEngine();
  SceneGraph scene = engine.parseRequest(bytes);
  LaidOutScene laid = engine.layout(scene).value();
  LayoutResult viaRecord =
      new ElkLayoutEngine().layout(LayoutRequestMapper.toRequest(scene));
  assertThat(LaidOutSceneMapper.toResult(laid)).isEqualTo(viaRecord);
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./mvnw -pl engines/elk-layout -am test -Dtest=ElkEngineTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `parseRequest` returns `LayoutRequest`, `layout` takes `LayoutRequest`.

- [ ] **Step 3: Add `ir` to `engines/elk-layout/pom.xml`** (same `<dependency>` block as Task 2 Step 3).

- [ ] **Step 4: Flip the `LayoutEngine` interface**

```java
package dev.dediren.engine;

import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.SceneGraph;

/** Backend-neutral graph layout: an ELK-backed engine is the first-party implementation. */
public interface LayoutEngine {
  String id();

  /** Parses request bytes into a {@link SceneGraph}, surfacing the engine's published
   *  parse-failure envelope (e.g. {@code DEDIREN_ELK_INPUT_INVALID_JSON} / exit 3) as an
   *  {@link EngineException}. */
  SceneGraph parseRequest(byte[] input) throws EngineException;

  EngineResult<LaidOutScene> layout(SceneGraph scene) throws EngineException;
}
```

- [ ] **Step 5: Adapt `ElkEngine` at the boundary (internals untouched)**

```java
@Override
public SceneGraph parseRequest(byte[] input) throws EngineException {
  try {
    return LayoutRequestMapper.toSceneGraph(
        LayoutJson.readLayoutRequest(new ByteArrayInputStream(input)));
  } catch (LayoutJson.LayoutPreferenceValidationException error) {
    throw layoutFailed(error.getMessage());
  } catch (Exception error) {
    throw new EngineException(
        List.of(diagnostic("DEDIREN_ELK_INPUT_INVALID_JSON",
            "layout request JSON is invalid: " + error.getMessage())), 3);
  }
}

@Override
public EngineResult<LaidOutScene> layout(SceneGraph scene) throws EngineException {
  try {
    LayoutRequest request = LayoutRequestMapper.toRequest(scene);
    LayoutJson.validatePreferences(request);
    return new EngineResult<>(
        LaidOutSceneMapper.toScene(new ElkLayoutEngine().layout(request)), List.of());
  } catch (Exception error) {
    throw layoutFailed(error.getMessage());
  }
}
```

(Add imports `dev.dediren.ir.LaidOutScene`, `LaidOutSceneMapper`, `LayoutRequestMapper`, `SceneGraph`; keep `LayoutRequest` import for the local.)

- [ ] **Step 6: Map at the CLI edge in `CoreCommands.layoutCommand`**

Keep `layoutRequestBytes(inputText)` (envelope-unwrap) unchanged. After parsing, serialize the `LaidOutScene` back to a `LayoutResult` for the wire:

```java
LayoutEngine layout =
    EngineDispatch.requireEngine(engines, engineId, "layout", engines.layoutEngine(engineId));
return EngineDispatch.dispatch(
    engineId,
    () -> {
      EngineResult<LaidOutScene> laid = layout.layout(layout.parseRequest(bytes));
      return new EngineResult<>(LaidOutSceneMapper.toResult(laid.value()), laid.diagnostics());
    });
```

- [ ] **Step 7: Update the three `FakeLayoutEngine` doubles + cli tests**

`CoreCommandsTest.FakeLayoutEngine`, `EnginesTest.FakeLayoutEngine`, `BuildCommandTest.FakeLayoutEngine`: implement `parseRequest(byte[]) : SceneGraph` and `layout(SceneGraph) : EngineResult<LaidOutScene>`. `EngineEnvelopeContractTest` / `LayoutFixtureRegenerator` (cli): adjust any direct `parseRequest`/`layout` calls to the new types (map via the `ir` mappers).

- [ ] **Step 8: Run affected suites + dist-smoke (module topology changed)**

Run: `./mvnw -pl engines/elk-layout,core,cli -am test` then `./mvnw -pl dist-tool -am verify -Pdist-smoke` (sandbox disabled)
Expected: PASS. `dediren layout` byte-identical; the elk `DEDIREN_ELK_*` envelope codes preserved; dist-smoke green (elk now ships `ir` — confirm the packaged launcher classpath resolves it).

- [ ] **Step 9: Format + commit**

```bash
./mvnw -Pquality spotless:apply
git add engine-api/ engines/elk-layout/ core/ cli/src/test
git commit -m "feat(engine-api): LayoutEngine speaks SceneGraph->LaidOutScene; ElkEngine adapts at the boundary (Plan B P4)"
```

---

## Task 4: Flip `RenderEngine` to consume `LaidOutScene`

**Files:**
- Modify: `engine-api/src/main/java/dev/dediren/engine/RenderEngine.java`
- Modify: `engines/render/src/main/java/dev/dediren/plugins/render/SvgRenderEngine.java`
- Modify: `engines/render/pom.xml` (add `ir`)
- Modify: `core/src/main/java/dev/dediren/core/commands/CoreCommands.java:171-189` (`renderCommand`)
- Test doubles: `core/.../BuildCommandTest.java:763` (`FakeRenderEngine`), `core/.../EngineDispatchTest.java:233` (`FakeRenderEngine`)
- Tests: any `engines/render` test that calls `SvgRenderEngine.render(...)` directly.
- **Untouched:** `svg/*`, `node/*`, `style/*` (they keep consuming `LayoutResult` internally).

**Interfaces:**
- Consumes: `LaidOutScene`, `LaidOutSceneMapper.{toScene,toResult}`.
- Produces: `RenderEngine.render(LaidOutScene layout, JsonNode policy, RenderMetadata metadataOrNull) : EngineResult<RenderResult>`.

- [ ] **Step 1: Write the failing test — render output is scene-driven and unchanged**

In an `engines/render` test, assert `render(LaidOutScene, …)` produces the same SVG as `render` did for the equivalent `LayoutResult`:

```java
@Test
void rendersFromLaidOutSceneIdenticallyToRecord() {
  LaidOutScene scene = LaidOutSceneMapper.toScene(layoutResultFixture);
  RenderResult result =
      new SvgRenderEngine().render(scene, policyNode, metadataFixture).value();
  assertThat(result.artifacts().get(0).content()).isEqualTo(expectedSvg);
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./mvnw -pl engines/render -am test -Dtest=<thatTest> -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `render` takes `LayoutResult`.

- [ ] **Step 3: Add `ir` to `engines/render/pom.xml`** (same `<dependency>` block).

- [ ] **Step 4: Flip the `RenderEngine` interface**

```java
public interface RenderEngine {
  String id();

  EngineResult<RenderResult> render(
      LaidOutScene layout, JsonNode policy, RenderMetadata metadataOrNull) throws EngineException;
}
```

(Swap the `LayoutResult` import for `dev.dediren.ir.LaidOutScene`.)

- [ ] **Step 5: Adapt `SvgRenderEngine` at the boundary (internals untouched)**

Map the scene back to a `LayoutResult` at the top of `render(...)`, then call the existing body verbatim:

```java
@Override
public EngineResult<RenderResult> render(
    LaidOutScene scene, JsonNode policy, RenderMetadata metadataOrNull) throws EngineException {
  LayoutResult layout = LaidOutSceneMapper.toResult(scene);
  // ... existing body unchanged: RenderInputValidator.validate(layout, metadataOrNull, ...),
  //     renderSvg(layout, metadataOrNull, ...), buildArtifacts(...) ...
}
```

(Keep the `LayoutResult` import for the local + the `ParsedInput` record used by the engine's own parse/test path.)

- [ ] **Step 6: Map at the CLI edge in `CoreCommands.renderCommand`**

Keep parsing the `LayoutResult` from the layout envelope, then map to a `LaidOutScene` for the engine:

```java
LayoutResult layoutResult = parseCommandData("render", layoutText, LayoutResult.class);
JsonNode policy = parseJson("render", policyText);
RenderMetadata metadata =
    metadataText == null ? null : parseCommandData("render", metadataText, RenderMetadata.class);
RenderEngine renderEngine =
    EngineDispatch.requireEngine(engines, engineId, "render", engines.renderEngine(engineId));
return EngineDispatch.dispatch(
    engineId,
    () -> renderEngine.render(LaidOutSceneMapper.toScene(layoutResult), policy, metadata));
```

- [ ] **Step 7: Update `FakeRenderEngine` doubles + render tests**

`BuildCommandTest.FakeRenderEngine`, `EngineDispatchTest.FakeRenderEngine`: `render(LaidOutScene, …)`. Update render tests calling `render(...)` to pass a `LaidOutScene` (wrap the fixture via `LaidOutSceneMapper.toScene`). Internal `svg/*`/`node/*` tests are untouched.

- [ ] **Step 8: Run affected suites + dist-smoke**

Run: `./mvnw -pl engines/render,core,cli -am test` then `./mvnw -pl dist-tool -am verify -Pdist-smoke` (sandbox disabled)
Expected: PASS. `dediren render` SVG byte-identical (provable: `toResult ∘ toScene = identity`). dist-smoke green (render now ships `ir`).

- [ ] **Step 9: Format + commit**

```bash
./mvnw -Pquality spotless:apply
git add engine-api/ engines/render/ core/
git commit -m "feat(engine-api): RenderEngine consumes LaidOutScene; SvgRenderEngine adapts at the boundary (Plan B P4)"
```

---

## Task 5: Make `build` pipe the IR in memory (no stage re-serialization)

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/engine/EngineDispatch.java` (split invoke/serialize)
- Modify: `core/src/main/java/dev/dediren/core/commands/BuildCommand.java`
- Tests: `core/src/test/java/dev/dediren/core/commands/BuildCommandTest.java`, `core/.../EngineDispatchTest.java`
- **Export note:** `ExportEngine`/`ExportRequest` unchanged; `build` maps its in-memory `LaidOutScene → LayoutResult` (via `LaidOutSceneMapper.toResult`) to assemble the `ExportRequest` — an in-memory object map, not JSON.

**Interfaces:**
- Consumes: `SemanticsEngine.projectScene`, `LayoutEngine.layout`, `RenderEngine.render`, `ExportEngine.export`, and the mappers, all in memory.
- Produces: a `build-result` envelope byte-identical to today, with all `--emit` stage envelopes byte-identical to the standalone commands' output.

- [ ] **Step 1: Add the reusable in-memory dispatch to `EngineDispatch`**

Extract the try/catch mapping from `dispatch` into a variant that returns a structured outcome instead of serializing, so `build` can consume the `EngineResult` directly while the standalone path keeps producing envelopes. Add:

```java
/** In-memory dispatch outcome: either the engine's typed result, or a published error envelope's
 *  diagnostics + exit code (an {@link EngineException}). Unexpected failures still throw
 *  {@link PluginExecutionException}; {@link UncheckedIOException} still propagates unchanged. */
public sealed interface InMemoryOutcome<T> {
  record Value<T>(EngineResult<T> result) implements InMemoryOutcome<T> {}
  record Failure<T>(List<Diagnostic> diagnostics, int exitCode) implements InMemoryOutcome<T> {}
}

public static <T> InMemoryOutcome<T> dispatchInMemory(String engineId, EngineInvocation<T> invocation)
    throws PluginExecutionException {
  try {
    return new InMemoryOutcome.Value<>(invocation.invoke());
  } catch (EngineException error) {
    return new InMemoryOutcome.Failure<>(error.diagnostics(), error.exitCode());
  } catch (UncheckedIOException error) {
    throw error;
  } catch (Exception error) {
    throw PluginExecutionException.plugin(
        DiagnosticCode.ENGINE_FAILED.code(), engineId,
        "engine " + engineId + " failed: " + error.getMessage());
  }
}
```

Refactor the existing `dispatch(...)` to delegate: call `dispatchInMemory`, then serialize (`Value → successEnvelope`, `Failure → CommandEnvelope.error` with the exit code). Verify `EngineDispatchTest` still passes (add a `dispatchInMemory` unit test covering the three branches).

- [ ] **Step 2: Write the failing test — build stays byte-identical while going JSON-free**

`BuildCommandTest`: keep the existing golden-output assertions (they are the oracle). Add a test that a view whose layout stage warns still surfaces the warning in the build result, and that `--emit layout-request`/`layout-result`/`render-metadata` write byte-identical envelopes to what the standalone commands emit. Run the existing suite first to see it red once `buildView` is rewritten (Step 3) mid-change.

- [ ] **Step 3: Rewrite `BuildCommand.buildView` to pipe IR**

Replace the `CoreCommands.projectCommand → layoutCommand → renderCommand → exportCommand` envelope chain with direct in-memory engine calls, preserving the exact diagnostic aggregation, status rollup, `UncheckedIOException` folding, and `--emit` semantics:

```java
// 1. projectScene (semantics) — in memory
InMemoryOutcome<SceneGraph> proj =
    EngineDispatch.dispatchInMemory(SEMANTICS_ENGINE,
        () -> semantics.projectScene(source, view));   // requireEngine resolved once up front
// fold Failure -> failedView; Value -> continue. UncheckedIOException folded as today.
SceneGraph scene = /* proj.result().value() */;
if (emitSelected(EMIT_LAYOUT_REQUEST))
  writeFile(request, view, "layout-request.json",
      envelope(LayoutRequestMapper.toRequest(scene), proj diagnostics)); // same envelope bytes as standalone

// 2. layout — in memory
InMemoryOutcome<LaidOutScene> laidOutcome =
    EngineDispatch.dispatchInMemory(LAYOUT_ENGINE, () -> layout.layout(scene));
LaidOutScene laid = /* value */;
if (emitSelected(EMIT_LAYOUT_RESULT))
  writeFile(request, view, "layout-result.json",
      envelope(LaidOutSceneMapper.toResult(laid), layout diagnostics));

// 3. quality — run on the record (unchanged behavior)
List<Diagnostic> qualityHardErrors =
    LayoutQuality.validateLayoutDiagnostics(LaidOutSceneMapper.toResult(laid));
// ... same error/warning handling as CoreCommands.validateLayoutCommand ...

// 4. render lane (if renderPolicyText != null)
RenderMetadata metadata = /* projectRenderMetadata in memory; --emit render-metadata */;
InMemoryOutcome<RenderResult> render =
    EngineDispatch.dispatchInMemory(RENDER_ENGINE,
        () -> renderEngine.render(laid, renderPolicyNode, metadata));

// 5. export lanes (if oef/xmi policy present) — map laid -> LayoutResult for the record ExportRequest
LayoutResult layoutRecord = LaidOutSceneMapper.toResult(laid);
ExportRequest oefReq = new ExportRequest(EXPORT_REQUEST_SCHEMA_VERSION, source, layoutRecord, oefPolicyNode);
InMemoryOutcome<ExportResult> oef =
    EngineDispatch.dispatchInMemory(OEF_ENGINE, () -> oefEngine.export(oefReq, env, productRoot));
// ... same for XMI ...
```

Notes for the implementer:
- Resolve the five engines from `engines` once via `EngineDispatch.requireEngine` at the top of `buildView`; a `PluginExecutionException` there folds into the per-view diagnostic exactly as `runStage` does today.
- The `--emit` envelope helper must produce the SAME bytes as the standalone command: reuse `EngineDispatch`'s `successEnvelope` serialization (extract a `static String envelope(Object data, List<Diagnostic>)` helper on `EngineDispatch` if needed) so `layout-request.json` equals what `dediren project --target layout-request` prints.
- Policy JSON (`renderPolicyText`/`oefPolicyText`/`xmiPolicyText`) is parsed once to `JsonNode` in `buildView` (as `CoreCommands.exportCommand`/`renderCommand` do).
- `productRoot` comes from `DedirenPaths.productRoot()` as today.
- Preserve the `warning |= ...` rollup, `failedView(...)`, and `failureExit` computation verbatim.

- [ ] **Step 4: Run the build suite + full `-Pquality verify` + dist-smoke**

Run: `./mvnw -pl core -am test`, then `./mvnw -Pquality verify`, then `./mvnw -pl dist-tool -am verify -Pdist-smoke` (sandbox disabled)
Expected: PASS. Build output + all `--emit` envelopes byte-identical; SpotBugs clean (the sealed `InMemoryOutcome` + records); dist-smoke green.

- [ ] **Step 5: Format + commit**

```bash
./mvnw -Pquality spotless:apply
git add core/
git commit -m "perf(core): build pipes the typed IR in memory with no stage re-serialization (Plan B P4)"
```

---

## Task 6: Enforcement, docs, and full verification

**Files:**
- Modify: `docs/architecture-guidelines.md` (dependency-edge table + tier note)
- Verify: `dist-tool/.../ArchitectureRulesTest.java` (final green; `because` strings honest)
- Verify: `README.md`, `docs/agent-usage.md`, `docs/threat-model.md` (expected: no change — wire, commands, schemas, runtime posture all unchanged)

**Interfaces:** none (docs + gate).

- [ ] **Step 1: Update the dependency-edge table in `docs/architecture-guidelines.md`**

Change the `engine-api` row from `contracts` to `contracts, ir`; add `ir` to the `elk-layout` and `render` engine rows and to the `core` row; add/confirm an `ir` row showing `ir | contracts | tier between contracts and engine-api`. Add one line noting `engine-api → ir` is the P4 seam flip and that export stays on `contracts` records because `ExportRequest` is a wire contract (a `contracts → ir` edge is forbidden by ADP + `contractsDependsOnNothingInternal`).

- [ ] **Step 2: Confirm `ArchitectureRulesTest` `because` strings are honest**

Re-read `engineApiDependsOnlyOnContracts` (Task 2), `irDependsOnlyOnContracts`, `elkLayoutDoesNotImportSemantics` (its "consumes only stringly LayoutConstraints over contracts" note is still true — elk reads constraints off the mapped `LayoutRequest`). Adjust wording only where it now misstates the edges. No structural rule change.

- [ ] **Step 3: Move-together doc sweep**

Confirm no user-facing surface changed: commands, flags, artifact locations, env vars, schema ids, bundle layout are all identical. Expected result: `README.md` / `docs/agent-usage.md` / `docs/threat-model.md` need no edit (state this explicitly in the handoff; if any probe/diagnostic string did change, update them + keep `AgentUsageDocConsistencyTest` green).

- [ ] **Step 4: Full gate + dist-smoke + doc lint**

Run (sandbox disabled):
```bash
./mvnw -Pquality verify
./mvnw -pl dist-tool -am verify -Pdist-smoke
git diff --check
```
Expected: all green. Confirm the reactor test count is at least the pre-P4 count (no tests silently dropped in the fake/test-double migrations).

- [ ] **Step 5: Commit**

```bash
git add docs/architecture-guidelines.md dist-tool/src/test
git commit -m "docs(architecture): record the engine-api->ir seam flip; export stays record-based (Plan B P4)"
```

---

## Audit Gates (per CLAUDE.md, Engine-runtime row)

Before calling P4 complete:
- **test-quality-audit (Deep: runtime tests/fixtures):** focus on the mapper round-trip tests, the boundary-adapter tests (elk/render), and the rewritten `BuildCommand` — verify the byte-stability oracle is actually asserted (golden output equality, `--emit` envelope equality), not just that code runs.
- **devsecops-audit (Quick: engine dependency boundary and posture):** confirm the new `ir` compile edges don't widen any process/trust boundary and dist-smoke ships `ir` correctly.

Fix block findings; fix or explicitly accept warn/info in the handoff; rerun affected checks.

---

## Self-Review

**Spec coverage (design §Phasing P4):**
- "engine-api speaks IR end-to-end (SemanticsEngine → SceneGraph; LayoutEngine SceneGraph → LaidOutScene; render/export consume LaidOutScene)" — Tasks 2/3/4. **Deviation, documented:** *export* keeps `ExportRequest(LayoutResult)` because it is a wire contract (`export-request.schema.v1`) and a `contracts → ir` edge is a forbidden cycle; `build` still passes the in-memory scene to export via an object map (`toResult`), so the "no re-serialization" goal holds. Recorded in Task 5 + Task 6 Step 1.
- "BuildCommand passes IR in memory (no stage re-serialization)" — Task 5.
- "SceneGraph grows to carry the layout constraints" — Task 1.
- "Geometry unchanged; elk still consumes stringly constraints via SequenceLayoutConstraints" — Tasks 1/3 keep `SequenceLayoutConstraints` and `ElkLayoutEngine` untouched; constraints ride through `SceneGraph.constraints → toRequest → request.constraints()`.
- "via Parallel Change so main stays green per step" — Task 1 additive; Tasks 2–4 flip one capability atomically (interface + sole impl + `CoreCommands` edge + test doubles) each leaving main green; `BuildCommand` keeps using the unchanged `CoreCommands` signatures until Task 5.

**Placeholder scan:** the "existing body unchanged" markers in Tasks 4/5 refer to verbatim-preserved code, not unwritten code — every changed line has concrete code. `LayoutPreferences` constructor arg in Task 1 Step 1 says to mirror an existing fixture (the exact preferences record is large; the implementer copies a known-valid one).

**Type consistency:** `projectScene : EngineResult<SceneGraph>`, `parseRequest : SceneGraph`, `layout(SceneGraph) : EngineResult<LaidOutScene>`, `render(LaidOutScene, JsonNode, RenderMetadata) : EngineResult<RenderResult>`, `LayoutRequestMapper.{toRequest,toSceneGraph}`, `LaidOutSceneMapper.{toScene,toResult}`, `EngineDispatch.dispatchInMemory : InMemoryOutcome<T>` — used consistently across tasks. `SceneNode`/`SceneEdge`/`SceneGraph` new field order fixed in Task 1 and consumed unchanged after.

# Typed IR — Phase 3: Carve `generic-graph` into `semantics-graph/archimate/uml` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the single `engines/generic-graph` module — which fuses base/plain projection, the ArchiMate front-end, and the UML front-end behind a stringly `semanticProfile` — into three focused modules (`semantics-graph`, `semantics-archimate`, `semantics-uml`), each depending only on its own notation core, coordinated by one profile-routing `SemanticsEngine` over a `NotationSemantics` hook SPI. Delete `engines/generic-graph`. **The wire is unchanged**: same command envelopes, same `contracts` records, same four stringly `uml.sequence.*` constraints, engine id still `generic-graph`, no schema bump.

**Architecture:** This is a byte-stable structural carve (Plan B P3, re-sliced — the typed `LayoutIntent`/`SequenceConstraint` vocabulary is **not** introduced here; it lands in P5 with its `elk-layout` consumer). The current single class `GenericGraphProjection` threads a `String semanticProfile` and scatters `semanticProfile.equals("uml"/"archimate")` checks through one projection loop, while `GenericGraphEngine` compile-depends on **both** the `archimate` and `uml` notation cores. We invert that: the shared base projection loop stays in one place (`semantics-graph`) and delegates every notation-specific decision (legality, role, sizing, sequence constraints, source-only filtering, render selector) through a new `NotationSemantics` SPI in `engine-api`. Three implementations — `GraphNotationSemantics` (base), `ArchimateNotationSemantics`, `UmlNotationSemantics` — each live in their own module and depend only on their own notation core. One `SemanticsRouterEngine` (engine id **`generic-graph`**, unchanged) resolves the profile and delegates. `cli` `EngineWiring` injects the three implementations. Executed as an expand/contract (Parallel Change) sequence: build the new modules alongside `generic-graph` (unwired), flip `EngineWiring` in one atomic step, migrate tests, then delete the old module. `main` is green at every task boundary.

**Tech Stack:** Java 21, Maven (`mvnw`), Jackson 3 (`tools.jackson.databind`, SNAKE_CASE), JUnit Jupiter 6, AssertJ, ArchUnit (core, in `dist-tool` test scope). Design anchor: `docs/superpowers/specs/2026-07-09-typed-ir-provenance-design.md` (§Module Shape, §Phasing P3). Boundary rules: `docs/architecture-guidelines.md` §2/§5/§12.

## Global Constraints

- Product version source is root `pom.xml`, currently `2026.07.14`. Do **not** bump the version in this plan; P3 is byte-stable and non-breaking, and the version bump (if released) is a separate follow-on commit per `## Versioning`.
- **Engine id / plugin id / wire contract stays `generic-graph`.** Only the Java package moves (`dev.dediren.plugins.genericgraph` → `dev.dediren.semantics.*`). Do **not** touch the `plugins.generic-graph` JSON key, the `GenericGraphSemanticProfile` enum (`contracts`), `--plugin generic-graph`, `schemas/model.schema.json`, `README.md`, `docs/agent-usage.md`, or `fixtures/source/*.json` semantic-profile keys. `SemanticsEngine.id()` returns `"generic-graph"`. `core` `BuildCommand.SEMANTICS_ENGINE` stays `"generic-graph"`.
- **New module packages are `dev.dediren.semantics.{graph,archimate,uml}`** (top-level modules, siblings of `ir`/`archimate`/`uml`), matching the repo convention: top-level module ↔ `dev.dediren.X`, `engines/X` ↔ `dev.dediren.plugins.X`. These deliberately drop the `plugins.` segment, discharging that slice of the §12 package-rename debt.
- **Behaviour preservation is absolute.** Every command envelope, diagnostic code, exit code, and byte of `layout-request` / `render-metadata` / `semantic-validation-result` output must be identical to today's `generic-graph` output. The migrated `generic-graph` test suite (≈70 tests, incl. the ≈50-test `GenericGraphPluginTest`) is the behaviour-preservation gate: relocate methods verbatim and keep every migrated test green. When a projection line reads `semanticProfile.equals(...)`, replace it with the matching `NotationSemantics` hook call — never change what it computes.
- Java formatted by google-java-format (GOOGLE) via Spotless; run `./mvnw -Pquality spotless:apply` before each Java commit. The full gate is `./mvnw -Pquality verify`.
- Dependency direction must stay an acyclic DAG rooted at `contracts`: `contracts → {ir, engine-api}`, `engine-api → contracts`, `ir → contracts`, `{semantics-graph, semantics-archimate, semantics-uml} → {engine-api, ir, contracts, <own notation core>}`. `engine-api` must still depend only on `contracts` (the new SPI uses only `contracts` types + `tools.jackson.databind.JsonNode`). `semantics-graph` must **not** depend on `semantics-archimate`/`semantics-uml` in production scope (only via the SPI abstraction); a **test**-scope dependency for the router integration suite is allowed.
- Maven `@TempDir`/Jazzer tests fail under the Claude Code sandbox — run `./mvnw` **sandbox-disabled** (memory `maven-tests-need-sandbox-disabled`, `fuzz-test-fails-locally-passes-ci`).
- Module-scoped single-test runs need `-am -Dsurefire.failIfNoSpecifiedTests=false`. Changing a schema-id constant needs a full `clean` (memory `inlined-constant-testbed-staleness`) — not applicable here (no schema-id change), but a full `./mvnw -Pquality verify` is still the final gate because deleting a module perturbs the reactor closure.
- Git: explicit-path staging only; never `git add -A` (untracked user files present — `.bashrc`, `.claude/`, etc.). Do **not** stage `target/` artifacts. Commit on the current branch (`main`); a fix branch is optional per `## Git Hygiene`.
- Audit gate (P3 is engine/ownership-restructuring, not export/test-heavy): **Deep `test-quality-audit`** on the migrated + new tests and **Quick `devsecops-audit`** on the engine-boundary/dependency change, per the repo audit table row "Engine runtime (dispatch, EngineWiring)". No new third-party dependency is added (all edges already exist in `generic-graph`'s pom), so the devsecops pass is a diff review.

## Current-state facts (verified this session — anchors for verbatim moves)

Module `engines/generic-graph`, package `dev.dediren.plugins.genericgraph`, already compile-depends on `archimate`, `uml`, `contracts`, `ir`, `engine-api`. Four main classes:

- **`GenericGraphEngine`** — implements `dev.dediren.engine.SemanticsEngine`; `id()` → `"generic-graph"`. Public: `parseSource(byte[])`, `validate(SourceDocument, String profile)`, `projectLayoutRequest(SourceDocument, String view)`, `projectRenderMetadata(SourceDocument, String view)`; package-static `profileRequired()`. Private helpers: `prepareProjection`, `pluginData`, `validateGenericGraphPluginData` (base structural: duplicate view id → `DEDIREN_GENERIC_GRAPH_DUPLICATE_VIEW_ID`, endpoint outside view → `DEDIREN_GENERIC_GRAPH_RELATIONSHIP_ENDPOINT_OUTSIDE_VIEW`, duplicate group id → `DEDIREN_GENERIC_GRAPH_DUPLICATE_GROUP_ID`), `validateArchimateSource`, `validateUml`, `validateArchimateSourceTypes`, `validateArchimateJunctionSemantics`, `failure`. Nested private records `GenericGraphValidationError`, `Projection`. Error codes it *owns*: `DEDIREN_SEMANTIC_PROFILE_REQUIRED`, `DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED`, `DEDIREN_GENERIC_GRAPH_*`. Error codes it *forwards verbatim* from the notation cores: `DEDIREN_ARCHIMATE_*` (from `ArchimateTypeValidationException` / `ArchimateJunctionValidationException`), `DEDIREN_UML_*` (from `UmlValidationException`). `EngineException` carries exit code **3**; raw structural failures throw `UncheckedIOException` (CLI exit 2).
- **`GenericGraphProjection`** — package-private `final`. `projectLayoutRequest(SourceDocument, GenericGraphView, String semanticProfile)` and `projectRenderMetadata(...)` build a `SceneGraph` (from `ir`) and map with `LayoutRequestMapper.toRequest(...)`; the base loop iterates nodes/edges/groups and interleaves notation checks. Notation-specific private helpers to relocate: `layoutRole(profile, type)` (343–354: `"lifeline"`/`"interaction"` for UML, `"junction"` for `Archimate.isRelationshipConnectorType(type)` under archimate), `projectLayoutConstraints(source, view, profile)` (195–288, UML-sequence only), `operandOrder`, `firstMessageOfOperand`, `umlMessageSequence`, `isSourceOnlySequenceFragment(profile, view, node)` (356–361), plus the render-selector uml-properties reads (`semanticProfile.equals("uml") ? node.properties().get("uml") : null`, and the edge equivalent). Base helpers stay: node/edge/group iteration, `GroupProvenance.visualOnlyGroup()`/`semanticBacked(sourceId)`, `sourceSemanticProfile`, `indexOfNode`/`indexOfRelationship`.
- **`GenericGraphSemanticProfiles`** — `sourceSemanticProfile(GenericGraphPluginData)` → `"generic-graph"`/`"archimate"`/`"uml"` (from the `contracts` enum `GenericGraphSemanticProfile`).
- **`GenericGraphLayoutSizing`** — `widthHint(profile, node)` / `heightHint(profile, node)`; base default 160×80; ArchiMate branch (connector 28×28 via `Archimate.isRelationshipConnectorType`; label-based with `ARCHIMATE_LABEL_ICON_RESERVE = 34.0`); UML branches (sequence/state/use-case/component/deployment/structural/compact-activity via `Uml.isCompactActivityNodeType` + type checks). **`ARCHIMATE_LABEL_ICON_RESERVE = 34.0` has a cross-module consistency test** — `dist-tool` `ArchimateLabelReserveConsistencyTest` hard-codes the path `engines/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java` (line 26) and checks it equals render's constant.

Notation-core facades the new modules call (unchanged): `dev.dediren.archimate.Archimate` (`isRelationshipConnectorType`, `validateElementType`, `validateRelationshipType`, `validateRelationshipEndpointTypes`, `validateJunctionRelationshipSemantics`; exceptions `ArchimateTypeValidationException`, `ArchimateJunctionValidationException`; records `JunctionValidationNode`, `JunctionValidationRelationship`); `dev.dediren.uml.Uml` (`validateSource(SourceDocument, GenericGraphPluginData)`, `isCompactActivityNodeType`; exception `UmlValidationException`).

Engine seam (unchanged by P3): `engine-api` `SemanticsEngine` speaks `contracts` records; `Engines` registry maps `id()` → engine per capability (`Engines.of(List<SemanticsEngine>, List<LayoutEngine>, List<RenderEngine>, List<ExportEngine>)` rejects duplicate ids per capability); `core` `EngineDispatch.requireEngine`/`dispatch` own `DEDIREN_PLUGIN_UNKNOWN` / `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY` / `DEDIREN_ENGINE_FAILED`; `UncheckedIOException` is re-thrown (CLI exit 2). `elk-layout`/`render`/export are **untouched** — they never import `generic-graph` and consume the four `uml.sequence.*` constraints as stringly `LayoutConstraint`s over `contracts`.

Consumers of `dev.dediren.plugins.genericgraph` outside the module (all must be repointed): `cli/src/main/java/dev/dediren/cli/EngineWiring.java` (production; imports + constructs `GenericGraphEngine`); `cli/src/test/java/dev/dediren/cli/LayoutFixtureRegenerator.java` (test); `cli/src/test/java/dev/dediren/cli/SequenceLayoutPropertyTest.java` (test). Package-string references: `dist-tool` `ArchitectureRulesTest` (`GENERIC_GRAPH` constant + reactor-import counter + `enginesDoNotDependOnEachOther` map) and `ArchimateLabelReserveConsistencyTest` (hard-coded sizing-file path). `core` reaches the engine only through the `"generic-graph"` id string (no package import) — leave those id strings alone.

## Target module + file structure

```
semantics-graph/                (module; package dev.dediren.semantics.graph; deps: engine-api, ir, contracts)
  SemanticsRouterEngine.java       implements engine-api SemanticsEngine, id()="generic-graph"; base structural
                                   validation + profile routing over Map<GenericGraphSemanticProfile,NotationSemantics>
  SceneProjection.java             the shared base projection loop (source -> LayoutRequest / RenderMetadata),
                                   delegating every notation decision to a NotationSemantics hook
  GraphNotationSemantics.java      base/plain profile: null role, 160x80 sizing, no constraints, no filtering, null selectors
  SemanticProfiles.java            sourceSemanticProfile(GenericGraphPluginData) -> GenericGraphSemanticProfile
semantics-archimate/            (module; package dev.dediren.semantics.archimate; deps: engine-api, ir, contracts, archimate)
  ArchimateNotationSemantics.java  validate (types + junction) + "junction" role + null selectors + no constraints
  ArchimateLayoutSizing.java       ArchiMate sizing incl. ARCHIMATE_LABEL_ICON_RESERVE = 34.0
semantics-uml/                  (module; package dev.dediren.semantics.uml; deps: engine-api, ir, contracts, uml)
  UmlNotationSemantics.java        validate (Uml.validateSource) + "lifeline"/"interaction" role + uml render selectors
                                   + source-only fragment/operand filtering + delegates constraints/sizing
  UmlLayoutSizing.java             all UML sizing branches
  UmlSequenceConstraints.java      the four uml.sequence.* LayoutConstraint producers + operand/message helpers
engine-api/
  NotationSemantics.java           NEW SPI (package dev.dediren.engine)
```

`engines/generic-graph/` is deleted. `cli` `EngineWiring` gains compile deps on the three new modules; `core`/`elk-layout`/`render`/export are unchanged.

---

## Task 1: Scaffold the three empty modules + register in the reactor

**Files:**
- Create: `semantics-graph/pom.xml`, `semantics-archimate/pom.xml`, `semantics-uml/pom.xml`
- Create: `semantics-graph/src/main/java/dev/dediren/semantics/graph/package-info.java`, `semantics-archimate/src/main/java/dev/dediren/semantics/archimate/package-info.java`, `semantics-uml/src/main/java/dev/dediren/semantics/uml/package-info.java`
- Modify: `pom.xml` (root `<modules>`) — add the three modules; leave `engines/generic-graph` listed for now (Parallel Change).

**Interfaces:**
- Produces: three buildable (empty) reactor modules with the exact dependency sets the later tasks rely on.

- [ ] **Step 1: Write `semantics-graph/pom.xml`**

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

  <artifactId>semantics-graph</artifactId>

  <dependencies>
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>contracts</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>ir</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>engine-api</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>test-support</artifactId>
      <version>${project.version}</version>
      <scope>test</scope>
    </dependency>
    <!-- test-scope only: the router integration suite constructs the real notation impls (Task 7);
         production code depends on them only through the NotationSemantics SPI. -->
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>semantics-archimate</artifactId>
      <version>${project.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>dev.dediren</groupId>
      <artifactId>semantics-uml</artifactId>
      <version>${project.version}</version>
      <scope>test</scope>
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

- [ ] **Step 2: Write `semantics-archimate/pom.xml`** — identical to Step 1 but `<artifactId>semantics-archimate</artifactId>`, add a compile dep on `archimate` (`<artifactId>archimate</artifactId>`, `<version>${project.version}</version>`), and **remove** the two test-scope `semantics-*` deps (an archimate test must not pull `semantics-uml`).

- [ ] **Step 3: Write `semantics-uml/pom.xml`** — identical shape, `<artifactId>semantics-uml</artifactId>`, compile dep on `uml` instead of `archimate`, and no `semantics-*` test deps.

- [ ] **Step 4: Write the three `package-info.java`** — one per package, e.g.:

```java
/**
 * Base/plain projection and the profile-routing {@code SemanticsEngine} for the Dediren semantics
 * front end (Plan B P3). Routes a source model by {@code GenericGraphSemanticProfile} to a
 * notation-specific {@link dev.dediren.engine.NotationSemantics} and runs the shared projection.
 *
 * @see docs/superpowers/specs/2026-07-09-typed-ir-provenance-design.md
 */
package dev.dediren.semantics.graph;
```

(archimate/uml variants: one-line summary naming the ArchiMate / UML front end.)

- [ ] **Step 5: Register the three modules in root `pom.xml`** — inside `<modules>`, add `<module>semantics-graph</module>`, `<module>semantics-archimate</module>`, `<module>semantics-uml</module>`. Keep `<module>engines/generic-graph</module>` (removed in Task 8).

- [ ] **Step 6: Verify the reactor builds the empty modules**

Run (sandbox-disabled): `./mvnw -q -pl semantics-graph,semantics-archimate,semantics-uml -am compile`
Expected: BUILD SUCCESS (three modules compile with only `package-info`).

- [ ] **Step 7: Commit**

```bash
git add pom.xml semantics-graph/pom.xml semantics-archimate/pom.xml semantics-uml/pom.xml \
  semantics-graph/src/main/java/dev/dediren/semantics/graph/package-info.java \
  semantics-archimate/src/main/java/dev/dediren/semantics/archimate/package-info.java \
  semantics-uml/src/main/java/dev/dediren/semantics/uml/package-info.java
git commit -m "build(semantics): scaffold semantics-graph/archimate/uml modules (Plan B P3)"
```

---

## Task 2: The `NotationSemantics` SPI in `engine-api`

**Files:**
- Create: `engine-api/src/main/java/dev/dediren/engine/NotationSemantics.java`
- Test: `engine-api/src/test/java/dev/dediren/engine/NotationSemanticsTest.java`

**Interfaces:**
- Produces: `interface NotationSemantics` — the notation-specific hooks the base projection delegates. All parameter/return types are `contracts` types (plus `tools.jackson.databind.JsonNode`), so `engine-api` still depends only on `contracts`.
  - `void validate(SourceDocument source, GenericGraphPluginData pluginData) throws EngineException`
  - `String layoutRole(String sourceType)` (nullable)
  - `double widthHint(SourceNode node)` / `double heightHint(SourceNode node)`
  - `boolean isSourceOnlyNode(GenericGraphView view, SourceNode node)`
  - `List<LayoutConstraint> layoutConstraints(SourceDocument source, GenericGraphView view)`
  - `JsonNode nodeRenderProperties(SourceNode node)` (nullable) / `JsonNode edgeRenderProperties(SourceRelationship relationship)` (nullable)

- [ ] **Step 1: Write the failing test** (a trivial in-line no-op implementation to pin the surface)

```java
package dev.dediren.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NotationSemanticsTest {
  /** A notation that contributes nothing — the shape the base/plain profile will use. */
  private static final NotationSemantics NEUTRAL =
      new NotationSemantics() {
        @Override
        public void validate(SourceDocument source, GenericGraphPluginData pluginData) {}

        @Override
        public String layoutRole(String sourceType) {
          return null;
        }

        @Override
        public double widthHint(SourceNode node) {
          return 160.0;
        }

        @Override
        public double heightHint(SourceNode node) {
          return 80.0;
        }

        @Override
        public boolean isSourceOnlyNode(GenericGraphView view, SourceNode node) {
          return false;
        }

        @Override
        public List<LayoutConstraint> layoutConstraints(SourceDocument source, GenericGraphView view) {
          return List.of();
        }

        @Override
        public JsonNode nodeRenderProperties(SourceNode node) {
          return null;
        }

        @Override
        public JsonNode edgeRenderProperties(SourceRelationship relationship) {
          return null;
        }
      };

  @Test
  void neutralNotationContributesNothing() {
    assertThat(NEUTRAL.layoutRole("Lifeline")).isNull();
    assertThat(NEUTRAL.widthHint(null)).isEqualTo(160.0);
    assertThat(NEUTRAL.isSourceOnlyNode(null, null)).isFalse();
    assertThat(NEUTRAL.layoutConstraints(null, null)).isEmpty();
    assertThat(NEUTRAL.nodeRenderProperties(null)).isNull();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (sandbox-disabled): `./mvnw -q -pl engine-api -am test -Dtest=NotationSemanticsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compile error, `NotationSemantics` does not exist.

- [ ] **Step 3: Write the SPI**

```java
package dev.dediren.engine;

import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One notation's semantic knowledge: legality plus the notation-specific projection decisions the
 * shared base projection in {@code semantics-graph} delegates. The profile router holds one instance
 * per {@link dev.dediren.contracts.source.GenericGraphSemanticProfile} and never switches on a
 * string. Introduced by Plan B P3 to replace the stringly {@code semanticProfile} threaded through
 * the old single {@code generic-graph} projection. In P3 layout constraints stay the stringly {@link
 * LayoutConstraint} DTO on the wire; P5 retypes them to a typed {@code LayoutIntent}.
 */
public interface NotationSemantics {

  /**
   * Notation legality; throws {@link EngineException} on the first violation, carrying the exact
   * diagnostic code / exit code the notation core raises. A no-op for the plain graph profile.
   */
  void validate(SourceDocument source, GenericGraphPluginData pluginData) throws EngineException;

  /** Layout role for a source node type ({@code "lifeline"}/{@code "interaction"}/{@code
   * "junction"}), or {@code null} for none. */
  String layoutRole(String sourceType);

  /** Width sizing hint for a source node in this notation. */
  double widthHint(SourceNode node);

  /** Height sizing hint for a source node in this notation. */
  double heightHint(SourceNode node);

  /** True when a source node is notation chrome that must not become a scene node (UML-sequence
   * {@code CombinedFragment}/{@code InteractionOperand}); false for notations that keep all nodes. */
  boolean isSourceOnlyNode(GenericGraphView view, SourceNode node);

  /** Notation layout constraints for the view (the four stringly {@code uml.sequence.*}), or empty. */
  List<LayoutConstraint> layoutConstraints(SourceDocument source, GenericGraphView view);

  /** Per-node render-metadata selector properties (the {@code uml} subtree), or {@code null}. */
  JsonNode nodeRenderProperties(SourceNode node);

  /** Per-edge render-metadata selector properties (the {@code uml} subtree), or {@code null}. */
  JsonNode edgeRenderProperties(SourceRelationship relationship);
}
```

> Verify the exact `contracts` package paths against source before compiling (`SourceRelationship`, `GenericGraphView`, `LayoutConstraint`). If Jackson is `com.fasterxml.jackson.databind.JsonNode` in this build rather than `tools.jackson.databind.JsonNode`, match whatever `contracts`/`generic-graph` already import — do not add a new Jackson coordinate.

- [ ] **Step 4: Run test to verify it passes**

Run (sandbox-disabled): `./mvnw -q -pl engine-api -am test -Dtest=NotationSemanticsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Spotless + commit**

```bash
./mvnw -q -Pquality -pl engine-api spotless:apply
git add engine-api/src/main/java/dev/dediren/engine/NotationSemantics.java \
  engine-api/src/test/java/dev/dediren/engine/NotationSemanticsTest.java
git commit -m "feat(engine-api): add NotationSemantics SPI for the semantics carve (Plan B P3)"
```

---

## Task 3: `semantics-graph` — base projection + router + graph profile

**Files:**
- Create: `semantics-graph/src/main/java/dev/dediren/semantics/graph/SemanticProfiles.java`
- Create: `semantics-graph/src/main/java/dev/dediren/semantics/graph/GraphNotationSemantics.java`
- Create: `semantics-graph/src/main/java/dev/dediren/semantics/graph/SceneProjection.java`
- Create: `semantics-graph/src/main/java/dev/dediren/semantics/graph/SemanticsRouterEngine.java`
- Test: `semantics-graph/src/test/java/dev/dediren/semantics/graph/GraphNotationSemanticsTest.java`, `SceneProjectionTest.java` (base/plain projection, relocated from `GenericGraphProjectionTest`)

**Interfaces:**
- Consumes: `NotationSemantics` (Task 2); `ir` `SceneGraph`/`SceneNode`/`SceneEdge`/`SceneGroup`/`LayoutRequestMapper`; `contracts` `SourceDocument`/`GenericGraphPluginData`/`GenericGraphView`/`GenericGraphSemanticProfile`/`LayoutRequest`/`RenderMetadata`/`SemanticValidationResult`/`LayoutConstraint`; `engine-api` `SemanticsEngine`/`EngineResult`/`EngineException`.
- Produces:
  - `SemanticProfiles.sourceSemanticProfile(GenericGraphPluginData)` → `GenericGraphSemanticProfile` (typed; defaults to `GENERIC_GRAPH` when the source omits it).
  - `GraphNotationSemantics implements NotationSemantics` — the base profile (no-op validate, null role, 160×80, no constraints, no filtering, null selectors).
  - `SceneProjection.projectLayoutRequest(SourceDocument, GenericGraphView, NotationSemantics)` and `.projectRenderMetadata(SourceDocument, GenericGraphView, NotationSemantics)` — the shared base loop, delegating every notation decision to the hook.
  - `SemanticsRouterEngine implements SemanticsEngine`, `id()` → `"generic-graph"`; constructor `SemanticsRouterEngine(Map<GenericGraphSemanticProfile, NotationSemantics> notations)`; owns `profileRequired()` (`DEDIREN_SEMANTIC_PROFILE_REQUIRED`), unsupported-profile (`DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED`), base structural validation (`validateGenericGraphPluginData` → `DEDIREN_GENERIC_GRAPH_*`), profile resolution, and dispatch.

- [ ] **Step 1: Relocate the base/plain projection test first (it must fail to compile)**

Copy `engines/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphProjectionTest.java` to `semantics-graph/src/test/java/dev/dediren/semantics/graph/SceneProjectionTest.java`. Repackage to `dev.dediren.semantics.graph`. Rewrite each call `GenericGraphProjection.projectLayoutRequest(source, view, "generic-graph")` → `SceneProjection.projectLayoutRequest(source, view, new GraphNotationSemantics())` (and the render-metadata equivalent). Keep the assertions byte-identical (2 nodes / 1 edge; render metadata `semanticProfile == "generic-graph"` with nodes `client`, `api`). Add a `GraphNotationSemanticsTest` asserting: `layoutRole("Lifeline")` → `null`; `widthHint`/`heightHint` → 160/80; `layoutConstraints` empty; `nodeRenderProperties` null; `validate` throws nothing.

- [ ] **Step 2: Run to verify failure**

Run (sandbox-disabled): `./mvnw -q -pl semantics-graph -am test -Dtest=SceneProjectionTest,GraphNotationSemanticsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `SceneProjection` / `GraphNotationSemantics` do not exist.

- [ ] **Step 3: Implement `SemanticProfiles` and `GraphNotationSemantics`**

`SemanticProfiles`: relocate `GenericGraphSemanticProfiles.sourceSemanticProfile` but return the **typed** `GenericGraphSemanticProfile` (not the string): `pluginData.semanticProfile() != null ? pluginData.semanticProfile() : GenericGraphSemanticProfile.GENERIC_GRAPH`. (The stringly `profileName` switch is deleted — routing is now typed.)

`GraphNotationSemantics implements NotationSemantics`: `validate` is a no-op; `layoutRole` returns `null`; `widthHint`/`heightHint` return `160.0`/`80.0` (the base defaults from `GenericGraphLayoutSizing`); `isSourceOnlyNode` returns `false`; `layoutConstraints` returns `List.of()`; `nodeRenderProperties`/`edgeRenderProperties` return `null`.

- [ ] **Step 4: Implement `SceneProjection`** — relocate `GenericGraphProjection`'s `projectLayoutRequest` / `projectRenderMetadata` base loop and its base helpers (`indexOfNode`, `indexOfRelationship`, group/`GroupProvenance` mapping, `SceneGraph` construction, `LayoutRequestMapper.toRequest`, constraint injection into the final `LayoutRequest`). Replace the stringly `String semanticProfile` parameter with `NotationSemantics notation`, and replace each notation-specific call verbatim:
  - `layoutRole(semanticProfile, type)` → `notation.layoutRole(type)`
  - `GenericGraphLayoutSizing.widthHint(semanticProfile, node)` → `notation.widthHint(node)` (and height)
  - `isSourceOnlySequenceFragment(semanticProfile, view, node)` → `notation.isSourceOnlyNode(view, node)`
  - `projectLayoutConstraints(source, view, semanticProfile)` → `notation.layoutConstraints(source, view)`
  - render selector `semanticProfile.equals("uml") ? node.properties().get("uml") : null` → `notation.nodeRenderProperties(node)` (and the edge equivalent)
  Keep the `RenderMetadata.semanticProfile` field value equal to the source profile string (`SemanticProfiles.sourceSemanticProfile(pluginData)` mapped back to its wire name via the existing `GenericGraphSemanticProfile` `@JsonProperty`) so render-metadata output is byte-identical. Do not change any base computation.

- [ ] **Step 5: Implement `SemanticsRouterEngine`** — relocate `GenericGraphEngine`'s public surface and base helpers:

```java
public final class SemanticsRouterEngine implements SemanticsEngine {
  private final Map<GenericGraphSemanticProfile, NotationSemantics> notations;

  public SemanticsRouterEngine(Map<GenericGraphSemanticProfile, NotationSemantics> notations) {
    this.notations = Map.copyOf(notations);
  }

  @Override
  public String id() {
    return "generic-graph";
  }
  // validate(source, profile): null -> profileRequired();
  //   parse the profile string to GenericGraphSemanticProfile; ARCHIMATE/UML -> notations.get(p).validate(source, pluginData(source));
  //   anything else (incl. GENERIC_GRAPH / unknown) -> DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED.
  // projectLayoutRequest / projectRenderMetadata(source, view): prepareProjection ->
  //   base validateGenericGraphPluginData(source, pluginData); profile = SemanticProfiles.sourceSemanticProfile(pluginData);
  //   notations.get(profile).validate(source, pluginData); select view; SceneProjection.project*(source, view, notations.get(profile)).
  // parseSource, pluginData, validateGenericGraphPluginData, failure, profileRequired: relocated verbatim.
}
```

Preserve `GenericGraphEngine`'s **exact** `validate()` and `prepareProjection()` call sequences and error codes/paths/exit codes (the migrated `GenericGraphEngineTest`/`GenericGraphPluginTest` in Task 7 are the gate). The `validate` command must accept only `archimate`/`uml` (default → `DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED`), exactly as today; projection resolves the profile from the source. `pluginData(source)` still throws `UncheckedIOException("missing plugins.generic-graph")` when absent (CLI exit 2).

- [ ] **Step 6: Run the base tests green**

Run (sandbox-disabled): `./mvnw -q -pl semantics-graph -am test -Dtest=SceneProjectionTest,GraphNotationSemanticsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Spotless + commit**

```bash
./mvnw -q -Pquality -pl semantics-graph spotless:apply
git add semantics-graph/src/main semantics-graph/src/test/java/dev/dediren/semantics/graph/SceneProjectionTest.java \
  semantics-graph/src/test/java/dev/dediren/semantics/graph/GraphNotationSemanticsTest.java
git commit -m "feat(semantics-graph): base projection loop + profile router (Plan B P3)"
```

---

## Task 4: `semantics-archimate` — ArchiMate notation semantics

**Files:**
- Create: `semantics-archimate/src/main/java/dev/dediren/semantics/archimate/ArchimateLayoutSizing.java`
- Create: `semantics-archimate/src/main/java/dev/dediren/semantics/archimate/ArchimateNotationSemantics.java`
- Test: `semantics-archimate/src/test/java/dev/dediren/semantics/archimate/ArchimateNotationSemanticsTest.java`, `ArchimateLayoutSizingTest.java`

**Interfaces:**
- Consumes: `NotationSemantics`; `dev.dediren.archimate.Archimate` + its exceptions/records; `contracts` source types.
- Produces: `ArchimateNotationSemantics implements NotationSemantics` — `validate` runs the ArchiMate type + junction validation exactly as `GenericGraphEngine` does (`validateArchimateSource`/`validateArchimateSourceTypes`/`validateArchimateJunctionSemantics`, preserving which run in the validate path vs projection path), `layoutRole(type)` → `"junction"` iff `Archimate.isRelationshipConnectorType(type)` else `null`, sizing via `ArchimateLayoutSizing`, `isSourceOnlyNode` → false, `layoutConstraints` → empty, render selectors → null. `ArchimateLayoutSizing.ARCHIMATE_LABEL_ICON_RESERVE = 34.0` (public — the dist-tool consistency test reads it).

- [ ] **Step 1: Relocate the ArchiMate sizing + notation tests (fail first)** — move the ArchiMate cases from `GenericGraphLayoutSizingTest` (e.g. connector 28×28; label-and-corner-icon sizing) into `ArchimateLayoutSizingTest`, calling `ArchimateLayoutSizing.widthHint(node)` (no profile param). Move the ArchiMate validation/role/render tests from `GenericGraphPluginTest` (see Task 7 inventory: `validatesArchimateSourceSemantics`, `projectsJunctionRoleOntoArchimateLayoutNodes`, `rejectsArchimateJunction*`, `rejectsUnknownArchimate*TypeForRenderMetadata`, `sizesArchimateNodesToFitLabelAndCornerIcon`, …) into `ArchimateNotationSemanticsTest`, re-expressed as direct calls on `new ArchimateNotationSemantics()` (validate / layoutRole / widthHint). Keep every diagnostic code and dimension assertion identical.

- [ ] **Step 2: Run to verify failure** — `./mvnw -q -pl semantics-archimate -am test -Dtest=ArchimateNotationSemanticsTest,ArchimateLayoutSizingTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (classes missing).

- [ ] **Step 3: Implement `ArchimateLayoutSizing`** — relocate the ArchiMate branch of `GenericGraphLayoutSizing` (`isRelationshipConnectorType` → 28×28; `archimateWidthHint`/`archimateHeightHint`; `ARCHIMATE_LABEL_ICON_RESERVE = 34.0` as a `public static final double`; `roundUp`, label-token width, min 160×80). Signatures become `static double widthHint(SourceNode)` / `heightHint(SourceNode)` (no profile param).

- [ ] **Step 4: Implement `ArchimateNotationSemantics`** — relocate `GenericGraphEngine`'s `validateArchimateSource`/`validateArchimateSourceTypes`/`validateArchimateJunctionSemantics` (constructing `JunctionValidationNode`/`JunctionValidationRelationship`, catching `ArchimateTypeValidationException`/`ArchimateJunctionValidationException` and rethrowing as `EngineException` with the forwarded code/message/path and exit 3) into `validate`. `layoutRole` returns `"junction"` iff `Archimate.isRelationshipConnectorType(sourceType)`. `widthHint`/`heightHint` delegate to `ArchimateLayoutSizing`. `isSourceOnlyNode` → false; `layoutConstraints` → `List.of()`; `nodeRenderProperties`/`edgeRenderProperties` → null.

- [ ] **Step 5: Run green** — same command as Step 2 → PASS.

- [ ] **Step 6: Spotless + commit**

```bash
./mvnw -q -Pquality -pl semantics-archimate spotless:apply
git add semantics-archimate/src
git commit -m "feat(semantics-archimate): ArchiMate legality, role, and sizing (Plan B P3)"
```

---

## Task 5: `semantics-uml` — UML notation semantics + sequence constraints

**Files:**
- Create: `semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlLayoutSizing.java`
- Create: `semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlSequenceConstraints.java`
- Create: `semantics-uml/src/main/java/dev/dediren/semantics/uml/UmlNotationSemantics.java`
- Test: `semantics-uml/src/test/java/dev/dediren/semantics/uml/UmlNotationSemanticsTest.java`, `UmlLayoutSizingTest.java`, `UmlSequenceConstraintsTest.java`

**Interfaces:**
- Consumes: `NotationSemantics`; `dev.dediren.uml.Uml` + `UmlValidationException`; `contracts` source + `LayoutConstraint`.
- Produces: `UmlNotationSemantics implements NotationSemantics` — `validate` → `Uml.validateSource(source, pluginData)` (rethrow `UmlValidationException` → `EngineException` verbatim, exit 3); `layoutRole` → `"lifeline"` for `Lifeline`, `"interaction"` for `Interaction`, else null; sizing via `UmlLayoutSizing`; `isSourceOnlyNode(view, node)` → `view.kind() == UML_SEQUENCE && type ∈ {CombinedFragment, InteractionOperand}`; `layoutConstraints(source, view)` → `UmlSequenceConstraints.of(source, view)` (the four `uml.sequence.*`, empty unless `UML_SEQUENCE`); `nodeRenderProperties(node)` → `node.properties().get("uml")`, `edgeRenderProperties(rel)` → `rel.properties().get("uml")`.
- `UmlSequenceConstraints.of(SourceDocument, GenericGraphView) → List<LayoutConstraint>` producing the exact kinds/ids/subjects: `uml.sequence.lifeline-order`, `uml.sequence.message-order` (sorted by the `uml.sequence` BigInteger then source index), `uml.sequence.fragment-open` (first-message-of-operand for operand 0), `uml.sequence.operand-open` (operands index > 0) — ids `view.id() + ".uml.sequence.<kind>"`, fragment/operand emitted only when non-empty.

- [ ] **Step 1: Relocate the UML tests (fail first)** — move the UML sizing cases from `GenericGraphLayoutSizingTest` (e.g. `Class` classifier 240×130 from compartments; compact-activity 32×32; lifeline 140×48) into `UmlLayoutSizingTest`. Move the UML constraint/role/filtering/render tests from `GenericGraphPluginTest` (Task 7 inventory: `projectsUmlSequenceLayoutConstraints`, `projectsFragmentAndOperandOpenConstraintsForSequenceFragments`, `projectsUmlSequenceMessageOrderWithLargeIntegralSequence`, `excludesSequenceFragmentSemanticNodesFromLayoutRequest`, `projectsLifelineRoleOntoSequenceLayoutNodes`, `projectsUml*RenderMetadata`, `projectsUmlStructuralSizeHintsFromCompartments`, `validatesUmlProfile`, `rejectsInvalidUmlRelationshipEndpoint`, …) into `UmlNotationSemanticsTest` / `UmlSequenceConstraintsTest`, re-expressed as direct calls. Keep every constraint kind/id/subject list and dimension byte-identical.

- [ ] **Step 2: Run to verify failure** — `./mvnw -q -pl semantics-uml -am test -Dtest=UmlNotationSemanticsTest,UmlLayoutSizingTest,UmlSequenceConstraintsTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

- [ ] **Step 3: Implement `UmlLayoutSizing`** — relocate the UML branches of `GenericGraphLayoutSizing` (sequence/state-machine/use-case/component/deployment/structural-compartment/compact-activity) plus their private helpers; signatures `static double widthHint(SourceNode)` / `heightHint(SourceNode)`.

- [ ] **Step 4: Implement `UmlSequenceConstraints`** — relocate `GenericGraphProjection.projectLayoutConstraints` + `operandOrder` + `firstMessageOfOperand` + `umlMessageSequence` into `static List<LayoutConstraint> of(SourceDocument, GenericGraphView)`, gated on `view.kind() == UML_SEQUENCE` (else `List.of()`).

- [ ] **Step 5: Implement `UmlNotationSemantics`** — `validate` relocates `GenericGraphEngine.validateUml`; role/sizing/filtering/selectors/constraints as in the Interfaces block, delegating to `UmlLayoutSizing` and `UmlSequenceConstraints`.

- [ ] **Step 6: Run green** — same command as Step 2 → PASS.

- [ ] **Step 7: Spotless + commit**

```bash
./mvnw -q -Pquality -pl semantics-uml spotless:apply
git add semantics-uml/src
git commit -m "feat(semantics-uml): UML legality, roles, sizing, and sequence constraints (Plan B P3)"
```

---

## Task 6: Flip `EngineWiring` to the router (atomic Parallel-Change swap)

**Files:**
- Modify: `cli/pom.xml` (add compile deps on `semantics-graph`, `semantics-archimate`, `semantics-uml`; the `ir` dep already exists; the `generic-graph` dep is removed in Task 8)
- Modify: `cli/src/main/java/dev/dediren/cli/EngineWiring.java`
- Test: existing `cli` `EngineEnvelopeContractTest` / `MainTest` (semantics envelopes) are the gate — no new test; they must stay green with the router wired.

**Interfaces:**
- Consumes: `SemanticsRouterEngine`, `GraphNotationSemantics`, `ArchimateNotationSemantics`, `UmlNotationSemantics`, `GenericGraphSemanticProfile`.
- Produces: `Engines` registry whose semantics slot is the router (still id `"generic-graph"`), behaviourally identical.

- [ ] **Step 1: Add the three compile deps to `cli/pom.xml`** — `semantics-graph`, `semantics-archimate`, `semantics-uml`, each `<version>${project.version}</version>` (compile scope). Leave the existing `generic-graph` dep for now.

- [ ] **Step 2: Rewrite `EngineWiring.defaults()`**

```java
package dev.dediren.cli;

import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.engine.Engines;
import dev.dediren.engine.NotationSemantics;
import dev.dediren.plugins.archimateoef.OefExportEngine;
import dev.dediren.plugins.elklayout.ElkEngine;
import dev.dediren.plugins.render.SvgRenderEngine;
import dev.dediren.plugins.umlxmi.XmiExportEngine;
import dev.dediren.semantics.archimate.ArchimateNotationSemantics;
import dev.dediren.semantics.graph.GraphNotationSemantics;
import dev.dediren.semantics.graph.SemanticsRouterEngine;
import dev.dediren.semantics.uml.UmlNotationSemantics;
import java.util.List;
import java.util.Map;

public final class EngineWiring {
  private EngineWiring() {}

  public static Engines defaults() {
    Map<GenericGraphSemanticProfile, NotationSemantics> notations =
        Map.of(
            GenericGraphSemanticProfile.GENERIC_GRAPH, new GraphNotationSemantics(),
            GenericGraphSemanticProfile.ARCHIMATE, new ArchimateNotationSemantics(),
            GenericGraphSemanticProfile.UML, new UmlNotationSemantics());
    return Engines.of(
        List.of(new SemanticsRouterEngine(notations)),
        List.of(new ElkEngine()),
        List.of(new SvgRenderEngine()),
        List.of(new OefExportEngine(), new XmiExportEngine()));
  }
}
```

Update the class javadoc: the semantics slot is now the profile router over `NotationSemantics`; `EngineWiring` is still the only cli class that touches engine implementations. (The `dev.dediren.semantics.*` packages are also engine-implementation edges confined to this class — Task 9 extends ArchUnit to cover them.)

- [ ] **Step 3: Run the cli semantics gate** (sandbox-disabled)

Run: `./mvnw -q -pl cli -am test -Dtest=EngineEnvelopeContractTest,MainTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — every semantics envelope (validate/project for archimate/uml/base) is byte-identical through the router. If a test still imports `GenericGraphEngine`, it is handled in Task 7; if `MainTest` asserts the `"generic-graph"` id/version strings, they are unchanged and stay green.

- [ ] **Step 4: Commit**

```bash
git add cli/pom.xml cli/src/main/java/dev/dediren/cli/EngineWiring.java
git commit -m "refactor(cli): wire the semantics router in place of GenericGraphEngine (Plan B P3)"
```

---

## Task 7: Migrate the remaining `generic-graph` tests + cli test consumers

**Files:**
- Create/Modify: `semantics-graph` router integration test (relocated from `GenericGraphPluginTest` base cases + `GenericGraphEngineTest`), plus `GenericGraphProvenanceTest` → `semantics-*` provenance tests, `GenericGraphSemanticProfilesTest` → `SemanticProfilesTest`, and the `Main`/`PluginResult` harness equivalent.
- Modify: `cli/src/test/java/dev/dediren/cli/LayoutFixtureRegenerator.java`, `cli/src/test/java/dev/dediren/cli/SequenceLayoutPropertyTest.java` — repoint `GenericGraphEngine` → `SemanticsRouterEngine` (constructed with the three notation impls; these are `cli` test files that already test-depend on the engine modules).

**Interfaces:**
- Consumes: `SemanticsRouterEngine`, the three `NotationSemantics` impls.
- Produces: full behavioural coverage relocated, with `engines/generic-graph` now having no unique test left (precondition for Task 8).

Test relocation map (from the `generic-graph` suite):

| Source test | Destination | Note |
| --- | --- | --- |
| `GenericGraphProjectionTest` | `semantics-graph` `SceneProjectionTest` | done in Task 3 |
| `GenericGraphSemanticProfilesTest` | `semantics-graph` `SemanticProfilesTest` | now returns the typed enum |
| `GenericGraphLayoutSizingTest` (archimate cases) | `semantics-archimate` `ArchimateLayoutSizingTest` | done in Task 4 |
| `GenericGraphLayoutSizingTest` (uml cases) | `semantics-uml` `UmlLayoutSizingTest` | done in Task 5 |
| `GenericGraphProvenanceTest` | split: base+shared → `semantics-graph`; sequence-skip provenance → `semantics-uml` | `sourcePointer` `/nodes/N`,`/relationships/N` unchanged |
| `GenericGraphPluginTest` archimate cases | `semantics-archimate` `ArchimateNotationSemanticsTest` | done in Task 4 |
| `GenericGraphPluginTest` uml cases | `semantics-uml` `UmlNotationSemanticsTest`/`UmlSequenceConstraintsTest` | done in Task 5 |
| `GenericGraphPluginTest` base/routing cases + `GenericGraphEngineTest` + `MainTest` | `semantics-graph` `SemanticsRouterEngineTest` (envelope-shaped, constructing the router with all three notations) | uses `semantics-archimate`/`semantics-uml` test-scope deps (Task 1) |

- [ ] **Step 1: Relocate the envelope harness** — the `generic-graph` test tree's `Main.executeForTesting(args, stdin)` + `PluginResult` simulate the CLI around the engine. Create a `semantics-graph` test-scope `RouterHarness` (same behaviour) that constructs `new SemanticsRouterEngine(Map.of(...three notations...))` and drives `validate`/`project` into `CommandEnvelope.ok/error`. Repackage. `moduleName()`-style asserts become `"generic-graph"` (the id is unchanged).

- [ ] **Step 2: Relocate the base/routing + engine-seam cases** — move `GenericGraphPluginTest`'s base/routing tests (`validateWithoutProfileReturnsProfileRequiredEnvelope`, `validateRejectsUnsupportedProfile`, `projectsBasicViewToLayoutRequest`, `rejectsDuplicateViewIds`, `rejectsDuplicateGroupIdsWithinView`, `rejectsViewRelationshipEndpointOutsideView`, `projectsLayoutPreferences`, `projectsRichViewGroups`, `projectsGroupRolesIntoProvenance`, `projectsNodePlacementHintsOntoLayoutNodes`, `projectsRelationshipPriorityOntoLayoutEdge`, …) and all of `GenericGraphEngineTest` (id, envelope round-trip parity, profile-required/unsupported exit 3, profile-precedence, raw parse-failure) into `SemanticsRouterEngineTest`, driven through `RouterHarness`. Keep the asserted schema strings `layout-request.schema.v2`, `semantic-validation-result.schema.v1`, `render-metadata.schema.v1` and every fixture-embedded `"2026.07.14"` string identical (memory: `GenericGraphPluginTest` is a version-assertion surface — the strings must keep matching the product version; `AgentUsageDocConsistencyTest`/version-policy still apply).

- [ ] **Step 3: Repoint the two cli test consumers** — in `LayoutFixtureRegenerator.java` and `SequenceLayoutPropertyTest.java`, replace `import ...genericgraph.GenericGraphEngine` + `new GenericGraphEngine()` with `new SemanticsRouterEngine(Map.of(GENERIC_GRAPH, new GraphNotationSemantics(), ARCHIMATE, new ArchimateNotationSemantics(), UML, new UmlNotationSemantics()))` (import from `dev.dediren.semantics.*`). Behaviour identical.

- [ ] **Step 4: Run the migrated suites** (sandbox-disabled)

Run: `./mvnw -q -pl semantics-graph,semantics-archimate,semantics-uml,cli -am test`
Expected: PASS across all four modules. Cross-check the migrated count roughly matches the old ≈70 `generic-graph` tests (no silent coverage loss — memory: golden/idealized fixtures hide real defects, so do not delete a behavioural assertion; either relocate it or record why in the commit).

- [ ] **Step 5: Commit**

```bash
git add semantics-graph/src/test semantics-archimate/src/test semantics-uml/src/test \
  cli/src/test/java/dev/dediren/cli/LayoutFixtureRegenerator.java \
  cli/src/test/java/dev/dediren/cli/SequenceLayoutPropertyTest.java
git commit -m "test(semantics): migrate generic-graph behaviour suite into semantics-* modules (Plan B P3)"
```

---

## Task 8: Delete `engines/generic-graph`

**Files:**
- Delete: `engines/generic-graph/` (whole module)
- Modify: root `pom.xml` (remove `<module>engines/generic-graph</module>`)
- Modify: `cli/pom.xml` (remove the `generic-graph` compile dep — now unused)

**Interfaces:**
- Produces: a reactor with no `dev.dediren.plugins.genericgraph` production classes. (Behavioural coverage already relocated in Tasks 3–7.)

- [ ] **Step 1: Confirm no remaining references** (sandbox-disabled)

Run: `rg -l "dev\.dediren\.plugins\.genericgraph|>generic-graph<|artifactId>generic-graph" --glob '!**/target/**'`
Expected: only `dist-tool` `ArchitectureRulesTest.java` (the `GENERIC_GRAPH` constant, handled in Task 9) and root `pom.xml`/`cli/pom.xml` (this task). No `src/main` or `src/test` Java import of the package should remain. The wire-contract string `"generic-graph"` (engine id, `plugins.generic-graph` JSON key, `GenericGraphSemanticProfile`, README, `docs/agent-usage.md`, fixtures) legitimately remains — do **not** touch it.

- [ ] **Step 2: Remove the module dir and pom references**

```bash
git rm -r engines/generic-graph
```
Then edit root `pom.xml` (drop the module line) and `cli/pom.xml` (drop the `generic-graph` dependency block).

- [ ] **Step 3: Verify the reactor builds without it** (sandbox-disabled)

Run: `./mvnw -q -pl cli,semantics-graph,semantics-archimate,semantics-uml -am test`
Expected: PASS (no dangling reference to the deleted module).

- [ ] **Step 4: Commit**

```bash
git add -u pom.xml cli/pom.xml engines/generic-graph
git commit -m "refactor: delete engines/generic-graph, superseded by semantics-* (Plan B P3)"
```

---

## Task 9: Extend ArchUnit + fix the label-reserve consistency test

**Files:**
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java`
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/ArchimateLabelReserveConsistencyTest.java`

**Interfaces:**
- Produces: the boundary matrix extended to the three `dev.dediren.semantics.*` modules; the `generic-graph` package references removed.

- [ ] **Step 1: Update `ArchitectureRulesTest` constants** — remove `GENERIC_GRAPH`; add:

```java
  private static final String SEMANTICS = "dev.dediren.semantics..";
  private static final String SEMANTICS_GRAPH = "dev.dediren.semantics.graph..";
  private static final String SEMANTICS_ARCHIMATE = "dev.dediren.semantics.archimate..";
  private static final String SEMANTICS_UML = "dev.dediren.semantics.uml..";
```

- [ ] **Step 2: Update `reactorProductionClassesWereImported`** — delete the `genericGraphClasses` counter/branch/assertion; add `semanticsGraphClasses`/`semanticsArchimateClasses`/`semanticsUmlClasses` counters (branch on `dev.dediren.semantics.graph`/`.archimate`/`.uml`) each asserted `.isPositive()`.

- [ ] **Step 3: Extend the ban lists** — add `SEMANTICS` to `contractsDependsOnNothingInternal`, `engineApiDependsOnlyOnContracts` (contracts and engine-api must not depend on the semantics modules), and `coreDoesNotDependOnEngineImplementations` (core must not depend on semantics engines). Add `SEMANTICS` to `onlyEngineWiringTouchesEngineImplementations` (the `resideInAPackage(PLUGINS)` check becomes `resideInAnyPackage(PLUGINS, SEMANTICS)`) so only `EngineWiring` touches semantics implementations too.

- [ ] **Step 4: Replace the `generic-graph` entry in `enginesDoNotDependOnEachOther`** — remove `"generic-graph", GENERIC_GRAPH` from the `enginePackages` map (the four `plugins.*` engines remain). Add a new rule enforcing the semantics-module boundaries:

```java
  @Test
  void semanticsModulesAreIndependentAndLeaf() {
    // semantics-uml and semantics-archimate never depend on each other; neither depends on
    // semantics-graph in production; and none depends on core/cli or another engine (they reach
    // shared code only through contracts, ir, engine-api, and their own notation core).
    noClasses().that().resideInAPackage(SEMANTICS_ARCHIMATE)
        .should().dependOnClassesThat().resideInAnyPackage(SEMANTICS_UML, SEMANTICS_GRAPH)
        .because("the ArchiMate front end must not depend on the UML front end or the router (§2)")
        .check(PRODUCTION_CLASSES);
    noClasses().that().resideInAPackage(SEMANTICS_UML)
        .should().dependOnClassesThat().resideInAnyPackage(SEMANTICS_ARCHIMATE, SEMANTICS_GRAPH)
        .because("the UML front end must not depend on the ArchiMate front end or the router (§2)")
        .check(PRODUCTION_CLASSES);
    noClasses().that().resideInAPackage(SEMANTICS)
        .should().dependOnClassesThat().resideInAnyPackage(CORE, CLI, PLUGINS)
        .because("the semantics front ends are leaf libraries behind engine-api; they must not"
            + " depend on core, cli, or another engine implementation (§2, §5)")
        .check(PRODUCTION_CLASSES);
  }
```

Also confirm `svgEmitterDoesNotImportElk` and `exportersDoNotImportSvgEmitter` are unaffected (they are — no semantics edge). The `elk-layout` never imports semantics-* is covered by `semanticsModulesAreIndependentAndLeaf`'s converse: add, if desired, an explicit `noClasses().resideInAPackage(ELK_LAYOUT).should().dependOnClassesThat().resideInAnyPackage(SEMANTICS)` for symmetry with the design's "elk-layout never imports a semantics-* module".

- [ ] **Step 4b: Add the explicit elk-layout guard**

```java
  @Test
  void elkLayoutDoesNotImportSemantics() {
    noClasses().that().resideInAPackage(ELK_LAYOUT)
        .should().dependOnClassesThat().resideInAnyPackage(SEMANTICS)
        .because("elk-layout consumes only stringly LayoutConstraints over contracts; a compile"
            + " edge to a semantics front end would recreate the notation coupling P3 removed (§2)")
        .check(PRODUCTION_CLASSES);
  }
```

- [ ] **Step 5: Fix `ArchimateLabelReserveConsistencyTest`** — update the hard-coded path from `engines/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java` to `semantics-archimate/src/main/java/dev/dediren/semantics/archimate/ArchimateLayoutSizing.java`, and update the constant name it greps for if it changed (still `ARCHIMATE_LABEL_ICON_RESERVE = 34.0`). Confirm the render-side constant path/assertion is unchanged.

- [ ] **Step 6: Run the dist-tool architecture suite** (sandbox-disabled)

Run: `./mvnw -q -pl dist-tool -am test -Dtest=ArchitectureRulesTest,ArchimateLabelReserveConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — all boundary rules green with the semantics modules, no `generic-graph` reference.

- [ ] **Step 7: Commit**

```bash
git add dist-tool/src/test/java/dev/dediren/tools/dist/ArchitectureRulesTest.java \
  dist-tool/src/test/java/dev/dediren/tools/dist/ArchimateLabelReserveConsistencyTest.java
git commit -m "test(dist-tool): extend ArchUnit boundary matrix to semantics-* modules (Plan B P3)"
```

---

## Task 10: Full-gate verification + audits

**Files:** none (verification only).

- [ ] **Step 1: Full quality gate** (sandbox-disabled)

Run: `./mvnw -Pquality verify`
Expected: BUILD SUCCESS — Spotless clean, SpotBugs clean, every module's tests green, ArchUnit green. A full (not `-pl`) run is required because deleting a module changes the reactor closure (memory `inlined-constant-testbed-staleness`).

- [ ] **Step 2: Distribution smoke** (sandbox-disabled)

Run: `./mvnw -pl dist-tool -am verify -Pdist-smoke`
Expected: BUILD SUCCESS — the bundled launcher still resolves the `generic-graph` semantics engine (id unchanged) and the manifest/`required_plugins` still list `generic-graph`.

- [ ] **Step 3: Confirm the wire is byte-stable** — spot-check one archimate and one uml `build`/`project` through `bin/dediren` (or the cli `MainTest` envelopes) and confirm stdout JSON (`layout-request`/`render-metadata`) matches pre-carve output. Confirm `git status --short` shows only intended files and no `target/`/`*.svg` staged.

- [ ] **Step 4: Deep `test-quality-audit`** on the migrated + new tests — verify the relocation preserved assertion strength (no golden/idealized weakening; the router integration suite exercises the same envelopes; provenance `sourcePointer` cases survived the split). Fix or explicitly accept findings.

- [ ] **Step 5: Quick `devsecops-audit`** on the engine-boundary/dependency diff — confirm no new third-party dependency (all edges pre-existed in `generic-graph`), the `engine-api` SPI surface stays contracts-only, and no process/reflection/service-loading was introduced. Fix or accept findings.

- [ ] **Step 6: Handoff** — report: modules added (`semantics-graph/archimate/uml`), module deleted (`engines/generic-graph`), engine id unchanged (`generic-graph`), schema unchanged (no bump), tests relocated (count), ArchUnit extended, both audits' outcomes, and any accepted findings. Note that P3 is byte-stable and non-breaking; a version bump is optional and, if released, is a separate follow-on commit + `v<version>` tag per `## Versioning`.

---

## Self-review (spec coverage)

- §Module Shape (four rows: `ir` unchanged; `semantics-graph`/`archimate`/`uml`) → Tasks 1,3,4,5. `ir` is untouched (it already has `SceneGraph`/mappers from P1/P2).
- "The `engines/generic-graph` carve" (three-way split; module deleted; §12 package-rename slice discharged) → Tasks 3–5 (split), 8 (delete), package moved to `dev.dediren.semantics.*`.
- "Engine seam stays stable" (one `SemanticsEngine`, typed profile router, `EngineWiring` unchanged in shape) → Tasks 3 (router), 6 (wiring). Registry/dispatch/error codes unchanged.
- "Dependency direction" (acyclic, rooted at contracts; notation cores imported only by their `semantics-*`) → Task 1 poms, enforced by Task 9 ArchUnit.
- "ArchUnit extends the Phase-1 matrix" (ir leaf; siblings independent; only semantics-* import notation cores; core blind to engines; elk-layout never imports semantics-*) → Task 9.
- Byte-stability / no schema bump / engine id kept → Global Constraints + Tasks 6/8/10.
- Non-goals honoured: no typed `LayoutIntent`/`SequenceConstraint` (deferred to P5), no `NormalizationPass` SPI, no render-metadata retyping, no wire rename, no new notation.

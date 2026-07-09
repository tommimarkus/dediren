# Typed IR — Phase 2: Post-layout IR, IR Invariants, Property Tests, Fixture Full-Replace — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce the post-layout twin IR (`LaidOutScene`), relocate the geometric layout-quality checks onto it so quality diagnostics carry `source_pointer`, turn the UML-sequence defect class into named IR invariants backed by jqwik property tests, and fully replace the idealized golden `layout-result` fixtures with real-engine characterization snapshots.

**Architecture:** Builds on P1 (the `ir` module already has the pre-layout `SceneGraph` + `SourcePointer`, and `layout-request`/`layout-result` are schema v2 carrying `source_pointer`). P2 adds `LaidOutScene`/`PlacedNode`/`RoutedEdge`/`PlacedGroup` + a `LayoutResult↔LaidOutScene` mapper, moves `core`'s `LayoutQuality` geometry onto the typed scene (behaviour-preserving; `LayoutQuality` becomes a thin adapter), promotes the sequence checks to named invariants, and replaces hand-authored fixture geometry with output regenerated from the real ELK engine. Executed in three phases: **P2a** (twin IR + relocation), **P2b** (new sequence invariants + jqwik), **P2c** (fixture full-replace).

**Tech Stack:** Java 21, Maven (`mvnw`), Jackson 3 (SNAKE_CASE), JUnit Jupiter 6, AssertJ, **new: jqwik** (test scope, on the JUnit Platform). Design anchor: `docs/superpowers/specs/2026-07-09-typed-ir-provenance-design.md` (§Invariants And Testing, §Phasing P2).

## Global Constraints

- Product version source is root `pom.xml`, currently `2026.07.14`. Do **not** bump the version in this plan; the version bump is a separate release step.
- Java formatted by google-java-format (GOOGLE) via Spotless; run `./mvnw -Pquality spotless:apply` before each Java commit. The gate is `./mvnw -Pquality verify`.
- `ir` depends only on `contracts`. Invariant logic that must stay backend-neutral may live in `ir` or `core`; it must not depend on `engine-api` or any engine. The ArchUnit `ir` rule from P1 must stay green.
- **Role stays a `String` in P2** (`"junction"/"lifeline"/"interaction"`), mirroring `LaidOutNode.role()`. The typed `NodeRole` enum is deferred to P3 — do not introduce it here.
- **Behaviour preservation in P2a:** `LayoutQuality`'s three public methods must produce the SAME diagnostics/report as before (existing `LayoutQuality*Test` suites stay green), with the single additive change that diagnostics may now carry an optional `source_pointer`.
- Diagnostics gaining `source_pointer` is an additive contract change to the diagnostic shape (envelope + layout-result `diagnostic` def). It is additive (optional field), so no schema-major break; keep `ContractRoundTripTest`/`SchemaValidatorTest` green.
- **P2c is destructive to test oracles.** Every checked-in `fixtures/layout-result/*.json` becomes real-engine output. Oracle-use assertions (that assert hand-authored geometry or "stays silent on idealized coords") are deleted or reworked to assert IR invariants on real geometry; input-use consumers are repointed to regenerated fixtures. Do not silently weaken an assertion — either it asserts a real IR invariant or it is deleted with its rationale in the commit message.
- Maven `@TempDir`/Jazzer tests fail under the Claude Code sandbox — run `./mvnw` **sandbox-disabled** (memory `maven-tests-need-sandbox-disabled`, `fuzz-test-fails-locally-passes-ci`).
- Module-scoped single-test runs need `-am -Dsurefire.failIfNoSpecifiedTests=false`.
- Git: explicit-path staging only; never `git add -A` (untracked user files present). Commit on the current branch.

## Fixture → consumer reference (for P2c)

All 15 `fixtures/layout-result/*.json` feed **`core` `LayoutQualityFixtureSweepTest`** (whole-dir ORACLE sweep) and, for most, the render matrix via **`engines/render` `RenderScenarios`** (`standard()`+`sequence()`) plus `render/MainTest`. Additional per-fixture consumers:

| Fixture | Oracle-use (delete/rework) | Input-use (regenerate + repoint) |
| --- | --- | --- |
| basic | `cli` `CliLayoutRenderCommandTest.validateLayoutReportsQualityFromFile`; sweep; `contracts` round-trip/schema (schema-only) | render `SvgRenderEngineTest`/`MainTest`, `cli` `EngineEnvelopeContractTest`, RenderScenarios |
| uml-sequence-validatable | `cli` `CliLayoutRenderCommandTest.validateLayoutAcceptsSequenceLifelineMessageEndpoints`; sweep; `contracts` schema | (none) |
| uml-sequence-fragments | `render` `SequenceFragmentAlignmentTest` (idealized LAYOUT, stale); sweep | render `MainTest`, `uml-xmi-export` `MainTest`, RenderScenarios#sequence |
| uml-sequence-fragment-chrome | `render` `SequenceFragmentAlignmentTest` (CHROME_LAYOUT — already real-engine); sweep | (none) |
| archimate-oef-basic | sweep | `archimate-oef-export` `OefExportEngineTest`/`MainTest`, `render` `MainTest`, `cli` `CliLayoutRenderCommandTest`/`EngineEnvelopeContractTest`, `dist-tool` `DistTool.java`, RenderScenarios — **8+ consumers, highest fan-out** |
| uml-basic | sweep | `cli` `EngineEnvelopeContractTest`/`CliLayoutRenderCommandTest`, `dist-tool` `DistTool.java`, `render` `MainTest`, `uml-xmi-export` `XmiExportEngineTest`/`MainTest`, RenderScenarios |
| pipeline-rich, uml-activity, uml-basic, uml-complex-class, uml-component-basic, uml-data, uml-deployment-basic, uml-sequence-basic, uml-state-machine-basic, uml-use-case-basic | sweep | render `MainTest`, `uml-xmi-export` `MainTest`, RenderScenarios (per RenderScenarios list) |

---

## Phase P2a — Post-layout twin IR + behaviour-preserving invariant relocation

### Task 1: The post-layout scene records (`LaidOutScene`)

**Files:**
- Create: `ir/src/main/java/dev/dediren/ir/PlacedNode.java`, `RoutedEdge.java`, `PlacedGroup.java`, `LaidOutScene.java`
- Test: `ir/src/test/java/dev/dediren/ir/LaidOutSceneTest.java`

**Interfaces:**
- Produces: `record PlacedNode(String id, String sourceId, String projectionId, double x, double y, double width, double height, String label, String role, SourcePointer origin)`; `record RoutedEdge(String id, String source, String target, String sourceId, String projectionId, java.util.List<String> routingHints, java.util.List<dev.dediren.contracts.layout.Point> points, String label, SourcePointer origin)`; `record PlacedGroup(String id, String sourceId, String projectionId, dev.dediren.contracts.layout.GroupProvenance provenance, double x, double y, double width, double height, java.util.List<String> members, String label)`; `record LaidOutScene(String viewId, java.util.List<PlacedNode> nodes, java.util.List<RoutedEdge> edges, java.util.List<PlacedGroup> groups, java.util.List<dev.dediren.contracts.Diagnostic> warnings)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LaidOutSceneTest {
  @Test
  void placedNodeCarriesGeometryRoleAndOrigin() {
    PlacedNode n = new PlacedNode("n1", "n1", "p1", 1, 2, 30, 40, "N1", "lifeline",
        new SourcePointer("/nodes/0"));
    assertThat(n.x()).isEqualTo(1);
    assertThat(n.role()).isEqualTo("lifeline");
    assertThat(n.origin().value()).isEqualTo("/nodes/0");
  }

  @Test
  void sceneDefaultsEmptyCollections() {
    LaidOutScene scene = new LaidOutScene("v1", null, null, null, null);
    assertThat(scene.nodes()).isEmpty();
    assertThat(scene.edges()).isEmpty();
    assertThat(scene.groups()).isEmpty();
    assertThat(scene.warnings()).isEmpty();
  }
}
```

- [ ] **Step 2: Run to verify fail**: `./mvnw -q -pl ir -am test -Dtest=LaidOutSceneTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (types missing).

- [ ] **Step 3: Create the records.** Mirror the P1 `SceneGraph` style (compact constructors default lists via `dev.dediren.contracts.util.ContractCollections.listOrEmpty`). `PlacedNode`/`RoutedEdge` carry `SourcePointer origin`; `PlacedGroup` mirrors `LaidOutGroup` (no origin — `LaidOutGroup` has none). `LaidOutScene` defaults its four lists.

```java
package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.Diagnostic;
import java.util.List;

public record LaidOutScene(
    String viewId,
    List<PlacedNode> nodes,
    List<RoutedEdge> edges,
    List<PlacedGroup> groups,
    List<Diagnostic> warnings) {
  public LaidOutScene {
    nodes = listOrEmpty(nodes);
    edges = listOrEmpty(edges);
    groups = listOrEmpty(groups);
    warnings = listOrEmpty(warnings);
  }
}
```

(`PlacedNode` = plain record with the 10 components above; `RoutedEdge` defaults `routingHints`/`points` via `listOrEmpty`; `PlacedGroup` defaults `members`.)

- [ ] **Step 4: Run to verify pass** (same command) → PASS.
- [ ] **Step 5: Format + commit**

```bash
./mvnw -Pquality spotless:apply -pl ir
git add ir/src/main/java/dev/dediren/ir/PlacedNode.java ir/src/main/java/dev/dediren/ir/RoutedEdge.java ir/src/main/java/dev/dediren/ir/PlacedGroup.java ir/src/main/java/dev/dediren/ir/LaidOutScene.java ir/src/test/java/dev/dediren/ir/LaidOutSceneTest.java
git commit -m "feat(ir): post-layout LaidOutScene twin (Plan B P2)"
```

### Task 2: `LayoutResult ↔ LaidOutScene` mapper

**Files:**
- Create: `ir/src/main/java/dev/dediren/ir/LaidOutSceneMapper.java`
- Test: `ir/src/test/java/dev/dediren/ir/LaidOutSceneMapperTest.java`

**Interfaces:**
- Consumes: `LaidOutScene` (Task 1), `contracts.layout.LayoutResult`/`LaidOutNode`/`LaidOutEdge`/`LaidOutGroup`.
- Produces: `LaidOutSceneMapper.toScene(LayoutResult) -> LaidOutScene` (re-wraps each node/edge `sourcePointer` string into `SourcePointer` via `origin`); `LaidOutSceneMapper.toResult(LaidOutScene) -> LayoutResult` (stamps `ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION`, unwraps `origin` back to the `sourcePointer` string). Round-trips a `LayoutResult` unchanged.

- [ ] **Step 1: Write the failing test** — assert `toResult(toScene(result))` preserves node/edge geometry + ids + `sourcePointer`, and that `toScene` maps `sourcePointer` string `"/nodes/0"` to `origin().value()` `"/nodes/0"`. Use a `LayoutResult` built with the 10-arg `LaidOutNode`/9-arg `LaidOutEdge` (v2) carrying a pointer.

- [ ] **Step 2–4:** RED → implement the mapper (symmetric to P1's `LayoutRequestMapper`: `origin = laidOutNode.sourcePointer()==null ? null : new SourcePointer(laidOutNode.sourcePointer())`; reverse unwraps `origin==null ? null : origin.value()`) → GREEN. Preserve `role`, `projectionId`, `routingHints`, `points`, `provenance`, `warnings` exactly.

- [ ] **Step 5: Commit** `feat(ir): LayoutResult<->LaidOutScene mapper (Plan B P2)`.

### Task 3: `source_pointer` on the `Diagnostic` contract (additive)

**Files:**
- Modify: `contracts/src/main/java/dev/dediren/contracts/Diagnostic.java`
- Modify: `schemas/layout-result.schema.json` (the `diagnostic` `$defs` — add optional `source_pointer`)
- Modify: whichever envelope/diagnostic schema the `Diagnostic` serializes under (grep `"path"` in `schemas/` for the diagnostic shape; add `source_pointer` alongside)
- Test: `contracts/src/test/java/dev/dediren/contracts/DiagnosticProvenanceTest.java`

**Interfaces:**
- Produces: `Diagnostic` gains a trailing nullable `String sourcePointer` component (serializes `source_pointer`), with a back-compat constructor preserving the current arity.

- [ ] **Step 1:** Read `Diagnostic.java` first to see its current components + factory methods. Write a failing test: a `Diagnostic` with a `sourcePointer` round-trips as `source_pointer`, and the existing factories still compile.
- [ ] **Step 2–4:** RED → append `String sourcePointer` as the trailing component; keep existing constructors/factories delegating `null`; add optional `source_pointer` to the `diagnostic` schema def(s) (NOT required) → GREEN. Keep `ContractRoundTripTest`/`SchemaValidatorTest` green (run them: `./mvnw -q -pl contracts -am test`).
- [ ] **Step 5: Commit** `feat(contracts): diagnostics carry optional source_pointer (Plan B P2)`.

### Task 4: Relocate the geometric checks onto `LaidOutScene` (behaviour-preserving)

**Files:**
- Create: `ir/src/main/java/dev/dediren/ir/quality/SceneGeometry.java` (backend-neutral geometry helpers over `LaidOutScene`) — or under `core` if it needs `Diagnostic` codes; decide by dependency: keep pure geometry in `ir`, keep `Diagnostic`/code mapping in `core`.
- Modify: `core/src/main/java/dev/dediren/core/quality/LayoutQuality.java` (becomes a thin adapter)
- Test: `ir/src/test/java/dev/dediren/ir/quality/SceneGeometryTest.java`

**Interfaces:**
- Produces: geometry predicates/counters that operate on `PlacedNode`/`RoutedEdge`/`PlacedGroup` — one method per existing private helper in `LayoutQuality` (`rectanglesOverlap`, `segmentIntersectsRect`, `pointOnNodePerimeter`, `onLifelineAxis`, `isSequenceContainer`, `distanceToRoute`, `selfLoopEscapesNode`, `hasExcessiveDetour`, `countCloseParallelRoutes`, group boundary/label-band, label-space, edge-label dissociation, edge-crossings). Same numeric thresholds and semantics.
- `LayoutQuality.validateLayout/validateLayoutDiagnostics/layoutQualityWarnings` keep their public signatures but internally `LaidOutSceneMapper.toScene(result)` and delegate to `SceneGeometry`. Output identical to today, EXCEPT diagnostics now set `sourcePointer` from the offending `PlacedNode`/`RoutedEdge` `origin`.

**Steps:** This is a mechanical port — the same algorithms, `LaidOutNode.x()` → `PlacedNode.x()`, `edge.points()` → `RoutedEdge.points()`, `node.role()` → `PlacedNode.role()`. Do it in small, individually-tested moves:

- [ ] **Step 1:** Port the 6 hard-error checks (`validateLayoutDiagnostics`) first. Write a test that feeds a `LaidOutScene` with a known defect (e.g. an edge endpoint off the node perimeter) and asserts the `SceneGeometry` check flags it AND that the resulting `Diagnostic` carries the offending element's `sourcePointer`. RED → port → GREEN.
- [ ] **Step 2:** Make `LayoutQuality.validateLayoutDiagnostics(result)` = `SceneGeometry.hardErrors(LaidOutSceneMapper.toScene(result))`. Run the FULL existing quality suite to confirm behaviour preserved: `./mvnw -q -pl core -am test -Dtest='LayoutQuality*Test'` → all previously-passing assertions still pass.
- [ ] **Step 3:** Port the warning counters into `SceneGeometry` returning the same `LayoutQualityReport` field values; make `LayoutQuality.validateLayout` delegate. Re-run the quality suite → green.
- [ ] **Step 4:** Thread `sourcePointer` into `layoutQualityWarnings`/hard-error diagnostics from the element `origin` (null when the offending element has no origin). Add an assertion to a core test that a warning/error diagnostic for a node with a known `source_pointer` carries it.
- [ ] **Step 5: Commit** `refactor(core): run layout-quality invariants on the typed LaidOutScene with provenance (Plan B P2)`.

### Task 5: P2a verification

- [ ] Run `./mvnw -pl ir,core,cli -am test` (quality + validate-layout path) sandbox-disabled → green. Confirm no existing `LayoutQuality`/`validate-layout` behaviour changed except the additive `source_pointer` on diagnostics.
- [ ] Commit any test touch-ups; `./mvnw -Pquality spotless:apply` for touched modules.

---

## Phase P2b — Named sequence invariants + jqwik property tests

### Task 6: Add jqwik as a managed test dependency

**Files:**
- Modify: root `pom.xml` (`<properties>` add `<jqwik.version>` — use the current stable 1.9.x; `<dependencyManagement>` add `net.jqwik:jqwik` test scope)
- Modify: the module poms that will host property tests (`core` and/or `ir`, `engines/elk-layout`) — add `net.jqwik:jqwik` `<scope>test</scope>`
- Test: a trivial `@Property` smoke test in the first module that uses it, to prove the JUnit-Platform wiring works.

- [ ] **Step 1:** Add the managed dep. **Step 2:** Add a one-line `@Property boolean anyIntIsItself(@ForAll int i){ return i == i; }` smoke test in `core`. **Step 3:** Run it: `./mvnw -q -pl core -am test -Dtest=<SmokeName> -Dsurefire.failIfNoSpecifiedTests=false` → PASS (proves jqwik discovers on the JUnit platform alongside Jupiter 6). **Step 4: devsecops-audit Quick** on the new dependency (posture only). **Step 5: Commit** `build: add jqwik test dependency for IR property tests (Plan B P2)`.

### Task 7: Named sequence geometric invariants on `LaidOutScene`

**Files:**
- Modify: `ir/src/main/java/dev/dediren/ir/quality/SceneGeometry.java` (or a new `SequenceInvariants.java` in `ir`)
- Test: `ir/src/test/java/dev/dediren/ir/quality/SequenceInvariantsTest.java`

**Interfaces:**
- Produces named, individually-callable invariants over a `LaidOutScene`, each returning violations (with the offending element `origin`): `messageEndpointsOnLifelineAxis` (promotes today's `onLifelineAxis`), `messageYStrictlyIncreasing` (new — messages ordered top-to-bottom by their y), `interactionFrameEnclosesLifelines` (new — the `role=="interaction"` frame rect contains all `role=="lifeline"` nodes + message routes). These are the memory-recorded defect class (A frame-encloses, C/D monotonic + on-axis).

- [ ] **Step 1:** Write failing tests: construct a `LaidOutScene` that VIOLATES each invariant (message endpoint off the lifeline axis; two messages with decreasing y; a lifeline outside the frame) and assert the invariant reports it with the right `sourcePointer`; and a satisfying scene reports none.
- [ ] **Step 2–4:** RED → implement each invariant → GREEN.
- [ ] **Step 5:** Wire them into the quality report/diagnostics so real sequence layouts are checked (they run when the scene has `role=="lifeline"`/`"interaction"` nodes). Confirm the existing sequence `validate-layout` behaviour is preserved or strengthened (not regressed) via `./mvnw -q -pl core,engines/elk-layout -am test`.
- [ ] **Step 6: Commit** `feat(ir): named sequence layout invariants with provenance (Plan B P2)`.

### Task 8: `SequenceModelGenerator` + property tests

**Files:**
- Create: `<host-module>/src/test/java/.../SequenceModelGenerator.java` (jqwik `@Provide` producing valid UML-sequence `SourceDocument`s: N lifelines, M messages with monotone sequence, optional fragments)
- Create: `<host-module>/src/test/java/.../SequenceLayoutPropertyTest.java`
- Note the host module must have `generic-graph`/`elk-layout` on the test classpath to run the real project→layout path; pick `engines/elk-layout` (it already depends on `contracts`/`ir` and can add `generic-graph` test-scope) or a small integration test module — confirm the dependency direction stays legal (test scope only).

**Interfaces:**
- Produces: `@Property` tests asserting, for every generated model run through the real `GenericGraphProjection → LayoutRequestMapper → ElkLayoutEngine` path, then `LaidOutSceneMapper.toScene`: `messageEndpointsOnLifelineAxis` ∧ `messageYStrictlyIncreasing` ∧ `interactionFrameEnclosesLifelines` ∧ no node overlap all hold.

- [ ] **Step 1:** Write the generator (seeded/bounded: 2–5 lifelines, 1–12 messages, 0–2 fragments; emit valid `properties["uml"]` shapes per `UmlSequenceValidation`'s rules). **Step 2:** Write the `@Property` test. **Step 3:** Run it: `./mvnw -q -pl <module> -am test -Dtest=SequenceLayoutPropertyTest -Dsurefire.failIfNoSpecifiedTests=false`. If jqwik shrinks to a real minimal counterexample, that is a genuine engine defect — STOP and report it (do not weaken the invariant); it likely belongs to the sequence defect class and may need an elk-layout fix in its own task. **Step 4:** GREEN. **Step 5: Commit** `test: jqwik property tests pin sequence layout invariants (Plan B P2)`.

---

## Phase P2c — Fixture full-replace (real-engine characterization snapshots)

### Task 9: The fixture regenerator (opt-in, real-engine)

**Files:**
- Create: `<test-support or dist-tool>/.../LayoutFixtureRegenerator.java` (an opt-in generator, gated like `ElkLayoutRenderArtifacts`) that, for each source model under `fixtures/source/`, runs `GenericGraphProjection.projectLayoutRequest → LayoutRequestMapper.toRequest → ElkLayoutEngine.layout`, and writes the resulting `LayoutResult` (v2) JSON to `fixtures/layout-result/<name>.json`.
- Create: `scripts/regen-layout-fixtures.sh` (invokes the generator via `./mvnw` with the gating `-D` property; documents the determinism precondition).

**Interfaces:**
- Produces: a deterministic regenerator. Precondition (state it in the script header): reproducible geometry requires the bundled Liberation Sans font + the pinned ELK version (the existing hermeticity fix — memory `visual-defect-test-suite`).

- [ ] **Step 1:** Map each `fixtures/layout-result/<name>.json` to its `fixtures/source/<name>.json` (grep both dirs; confirm every layout-result has a corresponding source, and flag any that don't — those may be pure synthetic fixtures needing a hand-authored-but-schema-valid replacement rather than regeneration).
- [ ] **Step 2:** Implement the generator modelled on `ElkLayoutRenderArtifacts` (gate on `Boolean.getBoolean("dediren.regen-layout-fixtures")`; write pretty-printed JSON via `JsonSupport`). **Step 3:** Run it once (`bash scripts/regen-layout-fixtures.sh`) and inspect the diff of the regenerated fixtures — geometry will change from idealized to real; the `source_pointer`s should now be real projection output. **Step 4: Commit the generator + script** (NOT the regenerated fixtures yet) `build: add opt-in real-engine layout-fixture regenerator (Plan B P2)`.

### Task 10: Rework the oracle-use tests

**Files (delete/rework the oracle assertions named in the fixture table):**
- Modify: `core/src/test/java/dev/dediren/core/quality/LayoutQualityFixtureSweepTest.java` — change semantics from "asserts silent on idealized coords" to "IR invariants hold on real-engine geometry": either (a) run each source through the real engine in-test and assert the invariants, or (b) assert the regenerated fixtures satisfy the invariants. Delete assertions that only make sense on hand-authored geometry.
- Modify: `cli/.../CliLayoutRenderCommandTest.java` — `validateLayoutReportsQualityFromFile` and `validateLayoutAcceptsSequenceLifelineMessageEndpoints` assert quality verdicts on hand-authored fixtures; rework to assert on real-engine output (or move the guarantee to the property tests + delete the fixture-oracle version, documenting why in the commit).
- Modify: `engines/render/.../SequenceFragmentAlignmentTest.java` — the `uml-sequence-fragments.json` idealized-LAYOUT derivations; repoint to the regenerated fixture or the already-real `uml-sequence-fragment-chrome.json` pattern.

- [ ] For each: replace the oracle assertion with a real-invariant assertion or delete it with rationale. Run each touched suite scoped. Commit `test: rework layout-quality oracle tests onto real-engine geometry (Plan B P2)`.

### Task 11: Regenerate fixtures + repoint input consumers

**Files:** `fixtures/layout-result/*.json` (all 15) + the input-use consumers per the fixture table.

- [ ] **Step 1:** Run the regenerator; stage the regenerated `fixtures/layout-result/*.json`.
- [ ] **Step 2:** Run the input-use suites and fix fallout, grouped by module (regenerated geometry shifts coordinates, so any test asserting a specific coordinate/bbox/SVG substring off these fixtures must be updated to the new real values or loosened to a structural assertion): `engines/render` (RenderScenarios consumers, `MainTest`, `SvgAppearanceAuditTest`, `RenderDeterminismTest`, `SvgAuditTest`), `engines/archimate-oef-export` (`OefExportEngineTest`, `MainTest`), `engines/uml-xmi-export` (`XmiExportEngineTest`, `MainTest`), `cli` (`EngineEnvelopeContractTest`, `CliLayoutRenderCommandTest`), `dist-tool` (`DistTool.java` reads `archimate-oef-basic`/`uml-basic`). Handle the **8-consumer `archimate-oef-basic`** carefully — its OEF-export assertions depend on geometry.
- [ ] **Step 3:** Keep `contracts` `ContractRoundTripTest`/`SchemaValidatorTest` green (they need only schema-valid v2 shape).
- [ ] **Step 4: Commit** the regenerated fixtures + consumer fixes together (they move together) `test: regenerate layout-result fixtures from real engine + repoint consumers (Plan B P2)`.

### Task 12: P2 final verification + audits

- [ ] `./mvnw clean verify` (full reactor) sandbox-disabled → green.
- [ ] `./mvnw -Pquality verify` → green (spotless + SpotBugs; suppress any new IR-record `EI_EXPOSE_REP` in `spotbugs-exclude.xml` + record in `docs/architecture-guidelines.md §12`, matching the P1/contracts pattern).
- [ ] `./mvnw -pl dist-tool -am verify -Pdist-smoke` → green; `git diff --check` clean.
- [ ] **Deep `test-quality-audit`** over the relocated invariants, property tests, and the regenerated fixtures + reworked oracle tests (per the repo audit table). **Quick `devsecops-audit`** over the jqwik dependency + the regenerator. Fix blocks; accept/record warn-info in the handoff.
- [ ] Update `docs/agent-usage.md`/`README.md` if the diagnostics `source_pointer` addition is agent-facing surface (keep `AgentUsageDocConsistencyTest` green).

---

## Follow-on

- **P3** — carve `generic-graph` into `semantics-archimate/uml/graph`; typed `NodeRole`, the `SequenceConstraint` family (makes the sequence structural rules unrepresentable rather than validated), UML→`LayoutIntent` lowering; delete `engines/generic-graph`; extend ArchUnit.
- **P4** — flip `engine-api` to speak IR end-to-end; `BuildCommand` pipes IR in memory; remove `SequenceLayoutConstraints`' UML re-derivation from `elk-layout`.
- **Deferred P1 minors to close here or in P3:** enforce non-null `origin` on `SceneNode`/`SceneEdge`; broaden the `ir` ArchUnit rule's forbidden-package list to sibling parity; fix the stale comment line-numbers in `LayoutRequestMapperTest`.

## Self-Review

- **Spec coverage:** P2a (twin IR T1–2, diagnostics provenance T3, invariant relocation T4–5) + P2b (jqwik T6, named invariants T7, property tests T8) + P2c (regenerator T9, oracle rework T10, fixture regen + repoint T11, verify+audit T12) cover the spec's §Invariants catalog, jqwik choice, and full-replace fixture policy. Structural *unrepresentability* is explicitly deferred to P3 (needs the typed `SequenceConstraint`); P2 delivers structural/geometric *checks* — noted in Global Constraints.
- **Placeholder scan:** the code-complete tasks (T1–T4, T6) carry real code; the mechanical-sweep tasks (T4 port, T10–T11) name exact files/methods/fixtures from the reference table rather than inline-duplicating 20 helper bodies or 15 fixture diffs — regenerated fixture *content* is generated output, not authorable, so those steps are procedural by nature (run the generator, inspect, commit).
- **Type consistency:** `LaidOutScene`/`PlacedNode`/`RoutedEdge`/`PlacedGroup`, `LaidOutSceneMapper.toScene/toResult`, `SceneGeometry`/`SequenceInvariants`, and `Diagnostic.sourcePointer` are used consistently across T1–T11.
- **Known execution risk (from P1):** the fixture full-replace fan-out (archimate-oef-basic 8+ consumers) and real-engine warnings (accepted ArchiMate connector-through-node debt) mean T10–T11 will surface cross-module fallout not fully enumerable up front — expect the executing agent to iterate `clean verify` to green, as P1's Task 9 did.

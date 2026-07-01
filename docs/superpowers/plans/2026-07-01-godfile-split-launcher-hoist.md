# God-file Split (fine-grained) + Launcher Hoist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose `plugins/render/Main.java` (3,826 LOC) and `plugins/uml-xmi-export/Main.java` (1,734 LOC) into fine-grained, per-notation packages, and hoist the duplicated plugin appassembler launcher execution to the parent `pom.xml` — with zero behavior change.

**Architecture:** Pure **move-and-delegate** refactor. Members are relocated verbatim into focused classes/packages; call sites are updated to reference the new class; no logic is changed. Each plugin's `Main` keeps its name and `moduleName()`/`main()`/`executeForTesting(...)` signatures so manifests, capability probes, and appassembler `<mainClass>` are untouched. The existing test suites are the oracle: byte-identical SVG/XMI output means the extraction was correct.

**Tech Stack:** Java 21, Maven (checked-in wrapper `./mvnw`), JUnit, appassembler-maven-plugin, ArchUnit/Enforcer (arch rules), `xmllint`/`curl` (uml-xmi schema validation).

**Spec:** `docs/superpowers/specs/2026-07-01-godfile-split-launcher-hoist-design.md`

## Global Constraints

- **Byte-identical output.** No change to generated SVG/PNG (render) or XMI (uml-xmi). The regression/golden render tests and the uml-xmi export tests are the byte-stability oracle. A red test = a wrong move; fix the move, never the test.
- **Entry classes unchanged.** `dev.dediren.plugins.render.Main` and `dev.dediren.plugins.umlxmi.Main` keep package, class name, and public method signatures (`moduleName`, `main`, `executeForTesting`, `PluginResult`).
- **Visibility seam.** Types shared across new subpackages become `public` within the plugin; types used by a single subpackage stay package-private there. This violates no architecture rule (ArchUnit governs cross-*module* edges, not intra-plugin visibility).
- **No new dependencies, no schema/contract/fixture/README change.** Internal refactor only (plus root + plugin `pom.xml` for S4).
- **Maven runs sandbox-disabled.** `@TempDir` needs a writable `/tmp` and Maven writes to `~/.m2`; run every `./mvnw` command with the sandbox disabled.
- **Out of scope (YAGNI):** the §6 `RenderInputValidator` UML-vocab duplication debt (relocate the class only), notation-core asymmetry, any output/schema change, CLI launcher change.
- **Git:** work on branch `refactor/godfile-split-launcher-hoist` in worktree `.worktrees/godfile-split-launcher-hoist`; explicit-path staging (`git add <path>`), never `git add -A`; commits are SSH-signed (sandbox-disabled).

## The Move-and-Delegate Cycle (applies to every extraction task)

Because members already exist and move verbatim, each extraction task follows this cycle instead of red/green TDD. The named members are the "code"; do not rewrite bodies.

1. **Create** the target file with the correct `package` declaration and a `final class` (private constructor for static-only utility classes).
2. **Move** the listed members (methods, records, enums, constants) verbatim from the source `Main.java` into the new class. Preserve order and bodies exactly.
3. **Adjust visibility:** make members referenced from another subpackage `public`; leave single-subpackage members package-private.
4. **Update call sites:** in `Main.java` (and any already-extracted class) replace `foo(...)` with `NewClass.foo(...)`; add imports.
5. **Compile:** `./mvnw -q -pl plugins/<plugin> -am -DskipTests compile` → expect `BUILD SUCCESS`. The compiler catches every missed reference.
6. **Test lane green:** run the task's test command → expect `BUILD SUCCESS`, `Failures: 0, Errors: 0`.
7. **Commit** with explicit paths.

Test commands (sandbox-disabled):
- Render: `./mvnw -pl plugins/render -am test`
- uml-xmi: `./mvnw -pl plugins/uml-xmi-export -am test`

---

## Task 0: Baseline snapshot

**Files:** none (verification only).

- [ ] **Step 1: Confirm clean baseline on the branch**

Run (sandbox-disabled): `./mvnw -pl plugins/render,plugins/uml-xmi-export,cli -am test`
Expected: `BUILD SUCCESS`, all `Failures: 0, Errors: 0`.

- [ ] **Step 2: Record the render + uml-xmi source LOC for later comparison**

Run: `wc -l plugins/render/src/main/java/dev/dediren/plugins/render/Main.java plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`
Expected: `3826` and `1734` (± minor). Note them; `Main` LOC must fall sharply by the end.

No commit.

---

## RENDER — extracted bottom-up (leaves first, orchestrator last)

### Task 1: `.style` — StyleResolver + Resolved* records

**Files:**
- Create: `plugins/render/src/main/java/dev/dediren/plugins/render/style/ResolvedStyle.java`
- Create: `.../render/style/ResolvedNodeStyle.java`, `ResolvedEdgeStyle.java`, `ResolvedGroupStyle.java`
- Create: `.../render/style/StyleResolver.java`
- Modify: `.../render/Main.java`

**Interfaces:**
- Produces (all `public`):
  - `record ResolvedStyle(...)`, `record ResolvedNodeStyle(...)`, `record ResolvedEdgeStyle(...)`, `record ResolvedGroupStyle(...)` (move from Main lines ~3784–3826, bodies verbatim, including their nested accessors/compact methods).
  - `StyleResolver.baseStyle(RenderPolicy) -> ResolvedStyle`
  - `StyleResolver.nodeStyle(...) -> ResolvedNodeStyle`
  - `StyleResolver.edgeStyle(...) -> ResolvedEdgeStyle`
  - `StyleResolver.groupStyle(...) -> ResolvedGroupStyle`
- Consumes: `contracts` types (`RenderPolicy`, `SvgNodeStyle`, `SvgEdgeStyle`, `SvgGroupStyle`).

**Members to move into `StyleResolver`:** `baseStyle`, `nodeStyle`, `edgeStyle`, `groupStyle`, `mergeNodeStyle`, `mergeEdgeStyle`, `mergeGroupStyle` (make the four public-named resolvers `public`; keep `merge*` package-private in `.style`).

- [ ] **Step 1:** Create the four record files (one public record each), moving bodies verbatim.
- [ ] **Step 2:** Create `StyleResolver` (final, private ctor); move the 7 methods; make the 4 resolvers `public`.
- [ ] **Step 3:** In `Main.java` delete the moved members; replace call sites with `StyleResolver.baseStyle(...)` etc.; import the records and `StyleResolver`. (Many methods take `ResolvedNodeStyle style` params — the import fixes them all.)
- [ ] **Step 4:** Compile — `./mvnw -q -pl plugins/render -am -DskipTests compile` → `BUILD SUCCESS`.
- [ ] **Step 5:** Test — `./mvnw -pl plugins/render -am test` → `BUILD SUCCESS`, 0 failures.
- [ ] **Step 6:** Commit
```bash
git add plugins/render/src/main/java/dev/dediren/plugins/render/style/ \
        plugins/render/src/main/java/dev/dediren/plugins/render/Main.java
git commit -m "refactor(render): extract style resolution to .style package"
```

### Task 2: `.svg` — move Svg primitives

**Files:**
- Move: `.../render/Svg.java` → `.../render/svg/Svg.java`
- Modify: `.../render/Main.java` (+ any other referencing class)

**Interfaces:** Produces `public final class Svg` with its existing primitive methods (make the ones used cross-package `public`). Consumes: none internal.

- [ ] **Step 1:** Create `.../render/svg/Svg.java` with `package dev.dediren.plugins.render.svg;`, body verbatim from the old `Svg.java`; delete the old file. Make cross-package methods `public`.
- [ ] **Step 2:** Add `import dev.dediren.plugins.render.svg.Svg;` where referenced; fix call sites (unqualified → imported).
- [ ] **Step 3:** Compile → `BUILD SUCCESS`.
- [ ] **Step 4:** Test — `./mvnw -pl plugins/render -am test` → 0 failures.
- [ ] **Step 5:** Commit
```bash
git add plugins/render/src/main/java/dev/dediren/plugins/render/svg/Svg.java \
        plugins/render/src/main/java/dev/dediren/plugins/render/Main.java
git rm plugins/render/src/main/java/dev/dediren/plugins/render/Svg.java 2>/dev/null; \
git add -u plugins/render/src/main/java/dev/dediren/plugins/render/
git commit -m "refactor(render): move Svg primitives to .svg package"
```

### Task 3: `.node` — NodeShapeSupport (decorator predicates)

**Files:**
- Create: `.../render/node/NodeShapeSupport.java`
- Modify: `.../render/Main.java`

**Interfaces:** Produces `public final class NodeShapeSupport` exposing (all `public`): `shouldRenderPlainNodeLabel`, `umlDecoratorSuppliesNodeLabel`, `isUmlDecorator`, `hasArchimateCornerIcon`, `isArchimateCutCornerRectangle`, `isArchimateRoundedRectangle`, `archimateIconKind`, `decoratorName`. Consumes: `contracts` `SvgNodeDecorator`, and `ArchimateIconKind` (see Task 7 — for now `archimateIconKind` returns the enum; extract order note below).

> **Order note:** `archimateIconKind` returns `ArchimateIconKind`, which moves in Task 7. To keep leaves-first order, **leave `archimateIconKind` and `decoratorName` in Main until Task 7**, and move only the boolean predicates here. Move `archimateIconKind`/`decoratorName` into `.node.archimate` alongside the enum in Task 7.

**Members to move here:** `shouldRenderPlainNodeLabel`, `umlDecoratorSuppliesNodeLabel`, `isUmlDecorator`, `hasArchimateCornerIcon`, `isArchimateCutCornerRectangle`, `isArchimateRoundedRectangle`, `umlCompactControlNodeLabelOutside`, `archimateJunctionLabelOutside`.

- [ ] **Step 1–7:** Apply the Move-and-Delegate Cycle. Test: `./mvnw -pl plugins/render -am test`.
- [ ] **Commit:** `git commit -m "refactor(render): extract decorator predicates to .node.NodeShapeSupport"`

### Task 4: `.node` — NodeLabels

**Files:**
- Create: `.../render/node/NodeLabels.java`
- Create: `.../render/node/NodeLabelLines.java`, `.../render/node/NodeLabelPosition.java` (public records)
- Modify: `.../render/Main.java`

**Interfaces:** Produces `public final class NodeLabels` (public: `nodeLabel`, `nodeLabelLinesAndSize`, `nodeLabelPosition`, `nodeLabelMaxWidth`, `nodeLabelFirstLineY`, `nodeLabelBoxes`, `nodeLabelBox`, `archimateLabelTopReserve`), plus public records `NodeLabelLines`, `NodeLabelPosition`. Consumes: `ResolvedNodeStyle` (Task 1), `Svg` (Task 2), `LabelBox` (Task 5 — see note), `contracts`.

> **`LabelBox` note:** `nodeLabelBoxes`/`nodeLabelBox` return `LabelBox`, which lives with Geometry (Task 5). Extract `LabelBox` as a `public record` in `.svg` **first as a micro-step of this task** (create `.../render/svg/LabelBox.java`, move the record from Main), then `NodeLabels` and `Geometry` both import it.

**Members to move here:** `nodeLabel`, `labelLengthAttributes`, `nodeLabelLinesAndSize`, `wrappedNodeLabelLines`, `labelWrapTokens`, `splitCamelToken`, `nodeLabelPosition`, `nodeLabelMaxWidth`, `nodeLabelLineHeight`, `nodeLabelFirstLineY`, `archimateLabelTopReserve`, `nodeLabelBoxes`, `nodeLabelBox`. Constants: `NODE_LABEL_VERTICAL_PADDING`, `NODE_LABEL_MIN_FONT_SIZE`.

- [ ] **Step 1:** Create `.../render/svg/LabelBox.java` (public record) from Main.
- [ ] **Step 2–7:** Move-and-Delegate Cycle for `NodeLabels` + the two label records.
- [ ] **Commit:** `git add` the new files + Main; `git commit -m "refactor(render): extract node label layout to .node.NodeLabels"`

### Task 5: `.svg` — Geometry (bounds + obstacle boxes)

**Files:**
- Create: `.../render/svg/Geometry.java`
- Create: `.../render/svg/SvgBounds.java` (public)
- Modify: `.../render/Main.java`

**Interfaces:** Produces `public final class Geometry` (public: `svgBounds`, `nodeObstacleBoxes`, `labelObstacleBoxesForEdge`, `groupObstacleBoxes`, `edgeRouteObstacleBoxes`, `edgeRouteObstacleBoxesForOtherEdges`, `labelBox`) and `public final class SvgBounds`. Consumes: `LabelBox`, `NodeLabels` (for label boxes), `ResolvedNodeStyle`, `contracts`.

**Members to move here:** `svgBounds`, `nodeObstacleBoxes`, `labelObstacleBoxesForEdge`, `groupObstacleBoxes`, `edgeRouteObstacleBoxes`, `edgeRouteObstacleBoxesForOtherEdges`, `labelBox`; class `SvgBounds` → its own public file.

- [ ] **Step 1–7:** Move-and-Delegate Cycle. Test: `./mvnw -pl plugins/render -am test`.
- [ ] **Commit:** `git commit -m "refactor(render): extract bounds/obstacle geometry to .svg.Geometry"`

### Task 6: `.svg` — EdgeRenderer

**Files:**
- Create: `.../render/svg/EdgeRenderer.java`
- Create: `.../render/svg/Segment.java`, `RoundedCorner.java`, `EdgeLabel.java`, `LineJump.java` (public records)
- Modify: `.../render/Main.java`

**Interfaces:** Produces `public final class EdgeRenderer` (public entry points used by the orchestrator: `edgePath`, `edgeMarker`, `edgeLabel(...)`, `lineJumps`, `lineJumpMasks`; keep helpers package-private in `.svg`) + public records `Segment`, `RoundedCorner`, `EdgeLabel`, `LineJump`. Consumes: `ResolvedEdgeStyle` (Task 1), `Svg` (Task 2), `Geometry`/`LabelBox` (Task 5), `contracts`.

**Members to move here (the edge cluster):** `edgeMarker`, `lineJumpMasks`, `backdropFillAt`, `pointInsideRect`, `edgePath`, `edgeLabelBackground`, `edgeLabel` (both overloads), `edgeLabelBackgroundBox`, `edgeLabelVisibleBox`, `edgeLabelFontSize`, `markerName`, `markerFill`, `markerStroke`, `pathData`, `roundedPathDataWithLineJumps`, `roundedPathData`, `roundedCorner`, `shiftedToward`, `distance`, `segmentProgress`, `lineJumps`, `isSharedJunctionPair`, `dedupeJumps`, `nearlyEqual`, `insideSegment`, `edgeLabelCandidate`, `orderedValues`, `labelOffsetCandidates`, `firstClearVerticalLabel`, `verticalLabelCandidates` (both overloads), `autoHorizontalLabelOffset`, `firstHorizontalSegment`, `verticalSegments`. Constants: the `EDGE_LABEL_*` and `EDGE_ROUTE_LABEL_OBSTACLE_PADDING` set. Records: `Segment`, `RoundedCorner`, `EdgeLabel`, `LineJump`.

- [ ] **Step 1:** Create the four record files.
- [ ] **Step 2–7:** Move-and-Delegate Cycle for `EdgeRenderer`. Compile catches the ~35 internal references; verify `BUILD SUCCESS`.
- [ ] **Test:** `./mvnw -pl plugins/render -am test` → 0 failures.
- [ ] **Commit:** `git commit -m "refactor(render): extract edge routing/label rendering to .svg.EdgeRenderer"`

### Task 7: `.node.archimate` — ArchimateShapes + ArchimateIcons

**Files:**
- Create: `.../render/node/archimate/ArchimateShapes.java`
- Create: `.../render/node/archimate/ArchimateIcons.java`
- Create: `.../render/node/archimate/ArchimateIconKind.java`, `TargetIconStyle.java` (public enums)
- Modify: `.../render/Main.java`, `.../render/node/NodeShapeSupport.java` (see Task 3 note)

**Interfaces:** Produces `public final class ArchimateShapes` (public: `archimateCutCornerShape`, `archimateJunctionRadius`) and `public final class ArchimateIcons` (public: `archimateNodeDecorator`, `archimateIconBody`) + public enums `ArchimateIconKind`, `TargetIconStyle`. Consumes: `ResolvedNodeStyle`, `Svg`, `contracts` `SvgNodeDecorator`.

**Members to move — ArchimateShapes:** `archimateCutCornerShape`, `archimateJunctionRadius`. Constants: `ARCHIMATE_ICON_SIZE`, `ARCHIMATE_ICON_TOP_INSET`, `ARCHIMATE_LABEL_ICON_RESERVE` (co-locate with icons if only icons use them).
**Members to move — ArchimateIcons (the ~1,700 LOC family):** `archimateNodeDecorator`, `archimateIconBody`, `archimateServiceIconBody`, `archimateActorIconBody`, `archimateApplicationComponentIconBody`, `archimateTargetIconBody`, `archimateDocumentIconBody`, `archimateContractIconBody`, `archimateCapabilityIconBody`, `archimateWorkPackageIconBody`, `archimateWavyDocumentIconBody`, `archimateFoldedDocumentIconBody`, `archimateFacilityIconBody`, `archimateEquipmentIconBody`, `archimateGearPath`, `archimateTechnologyNodeIconBody`, `archimateMaterialIconBody`, `archimateNetworkIconBody`, `archimateDistributionNetworkIconBody`, `archimatePathIconBody`; enums `ArchimateIconKind`, `TargetIconStyle`; plus `archimateIconKind`, `decoratorName` (moved from Main per Task 3 note).

- [ ] **Step 1:** Create the two enum files (public).
- [ ] **Step 2:** Create `ArchimateShapes` + `ArchimateIcons`; move members verbatim.
- [ ] **Step 3:** Update `NodeShapeSupport` if it referenced `archimateIconKind`/`decoratorName` (now `ArchimateIcons.archimateIconKind`).
- [ ] **Step 4:** Update `Main` dispatchers; imports.
- [ ] **Step 5:** Compile → `BUILD SUCCESS`; Test → `./mvnw -pl plugins/render -am test` 0 failures.
- [ ] **Commit:** `git commit -m "refactor(render): extract ArchiMate shapes + icon family to .node.archimate"`

### Task 8: `.node.uml` — UmlShapes + UmlDecorators (+ relocations)

**Files:**
- Create: `.../render/node/uml/UmlShapes.java`, `UmlDecorators.java`
- Move: `.../render/UmlSequenceRenderer.java`, `UmlSequenceModel.java`, `RenderInputValidator.java` → `.../render/node/uml/`
- Modify: `.../render/Main.java`

**Interfaces:** Produces `public final class UmlShapes` (public: `umlNodeShape`) and `public final class UmlDecorators` (public: `umlNodeDecorator`). `UmlSequenceRenderer`, `UmlSequenceModel`, `RenderInputValidator` keep their public API, new package. Consumes: `ResolvedNodeStyle`, `Svg`, `NodeLabels`, `contracts`.

**Members to move — UmlShapes:** `umlNodeShape`, `umlDeploymentTargetShape`, `umlArtifactShape`, `umlActorShape`, `umlUseCaseShape`, `umlFinalStateShape`, `umlPseudostateShape`, `umlFilledCircleShape`, `umlDiamondShape`, `umlBarShape`, `umlTextCircleShape`.
**Members to move — UmlDecorators:** `umlNodeDecorator`, `umlStereotypeLabel`, `umlComponentGlyph`, `umlClassifierNotation`, `umlAttributeLines`, `umlOperationLines`, `umlLiteralLines`, `umlVisibilitySymbol`, `textField`.

- [ ] **Step 1:** Move the 3 existing files into `.../node/uml/` (update their `package` line + any inbound imports).
- [ ] **Step 2–7:** Move-and-Delegate Cycle for `UmlShapes` + `UmlDecorators`.
- [ ] **Test:** `./mvnw -pl plugins/render -am test` → 0 failures (this exercises the moved `UmlSequenceRenderer`/`RenderInputValidator`).
- [ ] **Commit:** `git commit -m "refactor(render): extract UML shapes/decorators + relocate sequence/validator to .node.uml"`

### Task 9: `.svg` — SvgDocument + make Main thin

**Files:**
- Create: `.../render/svg/SvgDocument.java`
- Modify: `.../render/Main.java`

**Interfaces:** Produces `public final class SvgDocument` with the orchestration entry point, e.g. `public static String renderSvg(LayoutResult, RenderMetadata, RenderPolicy)` plus `public static List<RenderArtifact> buildArtifacts(String mode, String svg)`. Consumes: every prior render class. `Main.renderFromStdin` now delegates to `SvgDocument`.

**Members to move here:** `renderSvg`, `interactiveMode`, `interactionStyleBlock`, `interactionScriptBlock`, `buildArtifacts`, `htmlWrap`, `groupDecorator`, `nodeShape` (dispatch), `nodeDecorator` (dispatch), `archimateJunctionRadius` if still in Main. Constants: `DEFAULT_HIGHLIGHT_STROKE`, `DEFAULT_HIGHLIGHT_STROKE_WIDTH`, `GROUP_*` obstacle constants. Record `RenderInput` stays with `Main` (used by `renderFromStdin`) or moves to `.svg` if only `SvgDocument` uses it — keep with whichever calls it.

**Main after this task contains only:** `moduleName`, `main`, `executeForTesting`, `execute`, `capabilitiesJson`, `renderFromStdin` (delegating to `SvgDocument`), `exitWithDiagnostic`, and `RenderInput` if it stays.

- [ ] **Step 1:** Create `SvgDocument`; move the orchestration members.
- [ ] **Step 2:** Rewrite `Main.renderFromStdin` to call `SvgDocument.renderSvg(...)` / `SvgDocument.buildArtifacts(...)`.
- [ ] **Step 3:** Compile → `BUILD SUCCESS`.
- [ ] **Step 4:** Test — `./mvnw -pl plugins/render,cli -am test` → 0 failures (full render + CLI e2e).
- [ ] **Step 5:** Verify Main shrank: `wc -l .../render/Main.java` → expect ≲ 250 LOC.
- [ ] **Commit:** `git commit -m "refactor(render): extract SvgDocument orchestrator; Main is now a thin entrypoint"`

---

## UML-XMI — extracted bottom-up (shared helpers first, dispatch last)

### Task 10: `.build` — XmiHelpers, IdentifierMap, ExportScope, exceptions

**Files:**
- Create: `.../umlxmi/build/XmiHelpers.java`, `IdentifierMap.java`, `ExportScope.java`, `XmiValidationException.java`, `XmiExportException.java`
- Modify: `.../umlxmi/Main.java`

**Interfaces:** Produces (public): `XmiHelpers` static helpers, `IdentifierMap` (public class), `ExportScope` (public record), `XmiValidationException`/`XmiExportException` (public). Consumes: `contracts`, Jackson `JsonNode`.

**Members to move — XmiHelpers:** `writeEmptyPackagedElement`, `writeReferencedClassifierIds`, `attr`, `text`, `textField`, `umlArray`, `umlString` (both overloads), `umlTextArray`, `umlPositiveInt`, `umlSequence`, `multiplicityBounds`, `isXmlId`, `semanticGroupSourceId`, `slug`. Constants: `XMI_NS`, `UML_NS`, `XMI_VERSION`, `UML_VERSION` (shared by writers).
**Move as own files:** `IdentifierMap` (class, 1664), `ExportScope` (record, 1509), `XmiValidationException` (1703), `XmiExportException` (1716).

- [ ] **Step 1–7:** Move-and-Delegate Cycle. Test: `./mvnw -pl plugins/uml-xmi-export -am test`.
- [ ] **Commit:** `git commit -m "refactor(uml-xmi): extract shared XMI helpers/identifiers/exceptions to .build"`

### Task 11: `.policy` — PolicyValidation

**Files:** Create `.../umlxmi/policy/PolicyValidation.java`; modify `Main.java`.
**Interfaces:** Produces `public static void PolicyValidation.validatePolicy(JsonNode)`. Consumes: `XmiExportException` (Task 10).
**Members to move:** `validatePolicy`.

- [ ] **Cycle + Commit:** `git commit -m "refactor(uml-xmi): extract policy validation to .policy"`

### Task 12: `.schema` — SchemaValidation

**Files:** Create `.../umlxmi/schema/SchemaValidation.java`; modify `Main.java`.
**Interfaces:** Produces (public) `validateXmiToAvailableStandards`, `validateXmiDocumentAndIds`, `commandAvailable`. Consumes: `XmiValidationException`, env map, `contracts` diagnostics.
**Members to move:** `validateXmiToAvailableStandards`, `validateXmiDocumentAndIds`, `validateOmgXmiSchema`, `xmiSchemaValidator`, `resolveOmgXmiSchemaPath`, `xmiSchemaErrorsAreOnlyUnavailableUmlSchema`, `commandAvailable`. Constants: `XMI_SCHEMA_VALIDATOR`, `OMG_XMI_SCHEMA_URL`, `XMI_SCHEMA_PATH_ENV`, `SCHEMA_CACHE_DIR_ENV`, `SCHEMA_FETCHER`.

- [ ] **Cycle + Commit:** `git commit -m "refactor(uml-xmi): extract schema validation to .schema"`

### Task 13: `.write.classifier` — ClassifierWriter

**Files:** Create `.../umlxmi/write/classifier/ClassifierWriter.java`; modify `Main.java`.
**Interfaces:** Produces (public) `writeClassifier`, `writeEnumeration`. Consumes: `XmiHelpers`, `IdentifierMap`.
**Members to move:** `writeClassifier`, `writeOwnedAttribute`, `writeOwnedOperation`, `writeEnumeration`.

- [ ] **Cycle + Commit:** `git commit -m "refactor(uml-xmi): extract classifier writer to .write.classifier"`

### Task 14: `.write.component` — ComponentWriter

**Files:** Create `.../umlxmi/write/component/ComponentWriter.java`; modify `Main.java`.
**Interfaces:** Produces (public) `writeComponent`, `writeComponentRelationships`. Consumes: `XmiHelpers`, `IdentifierMap`.
**Members to move:** `writeComponent`, `writeOwnedPort`, `writeComponentRelationships`, `isComponentExportRelationship`, `isComponentEndpoint`, `componentRelationshipXmiType`.

- [ ] **Cycle + Commit:** `git commit -m "refactor(uml-xmi): extract component writer to .write.component"`

### Task 15: `.write.deployment` — DeploymentWriter

**Files:** Create `.../umlxmi/write/deployment/DeploymentWriter.java`; modify `Main.java`.
**Interfaces:** Produces (public) `writeDeployment`, `writeDeploymentRelationships`. Consumes: `XmiHelpers`, `IdentifierMap`.
**Members to move:** `writeDeploymentRelationships`, `isDeploymentExportRelationship`, `writeDeployment`, `writeManifestation`, `writeCommunicationPath`, `isDeployedArtifactType`, `isDeploymentTargetType`, `isManifestedElementType`.

- [ ] **Cycle + Commit:** `git commit -m "refactor(uml-xmi): extract deployment writer to .write.deployment"`

### Task 16: `.write.usecase` — UseCaseWriter

**Files:** Create `.../umlxmi/write/usecase/UseCaseWriter.java`; modify `Main.java`.
**Interfaces:** Produces (public) `writeUseCase`. Consumes: `XmiHelpers`, `IdentifierMap`.
**Members to move:** `writeUseCase`, `writeExtensionPoint`, `writeInclude`, `writeExtend`.

- [ ] **Cycle + Commit:** `git commit -m "refactor(uml-xmi): extract use-case writer to .write.usecase"`

### Task 17: `.write.statemachine` — StateMachineWriter

**Files:** Create `.../umlxmi/write/statemachine/StateMachineWriter.java`; modify `Main.java`.
**Interfaces:** Produces (public) `writeStateMachine`. Consumes: `XmiHelpers`, `IdentifierMap`.
**Members to move:** `writeStateMachine`, `writeStateMachineRegion`, `writeStateMachineVertex`, `writeTransition`.

- [ ] **Cycle + Commit:** `git commit -m "refactor(uml-xmi): extract state-machine writer to .write.statemachine"`

### Task 18: `.write.interaction` — InteractionWriter (sequence)

**Files:**
- Create: `.../umlxmi/write/interaction/InteractionWriter.java`
- Create: `.../umlxmi/write/interaction/CombinedFragmentExport.java`, `OperandExport.java`, `TopLevelInteractionFragment.java`, `MessageExport.java` (public records)
- Modify: `Main.java`

**Interfaces:** Produces (public) `writeInteraction`, `validateSelectedCombinedFragmentOperators`, `validateExportableSequenceScope`. Consumes: `XmiHelpers`, `IdentifierMap`, `XmiExportException`.
**Members to move:** `writeInteraction`, `sequenceMessages`, `combinedFragments`, `messagesBySourceId`, `nestedCombinedFragmentIds`, `operandOwnedMessageIds`, `writeTopLevelInteractionFragments`, `combinedFragmentSequence` (both overloads), `writeCombinedFragment`, `writeOperand`, `writeMessageOccurrence`, `writeSequenceMessage`, `validateSelectedCombinedFragmentOperators`, `validateExportableSequenceScope`, `unsupportedSequenceNode`; records `CombinedFragmentExport`, `OperandExport`, `TopLevelInteractionFragment`, `MessageExport`. Constants: `UNSUPPORTED_SEQUENCE_*`, `MISSING_SEQUENCE_MESSAGE_INTERACTION`, `SUPPORTED_SEQUENCE_FRAGMENT_OPERATORS`.

- [ ] **Step 1:** Create the four record files.
- [ ] **Step 2–7:** Move-and-Delegate Cycle. Test: `./mvnw -pl plugins/uml-xmi-export -am test`.
- [ ] **Commit:** `git commit -m "refactor(uml-xmi): extract interaction/sequence writer to .write.interaction"`

### Task 19: `.write.activity` — ActivityWriter

**Files:** Create `.../umlxmi/write/activity/ActivityWriter.java`; modify `Main.java`.
**Interfaces:** Produces (public) `writeActivity`. Consumes: `XmiHelpers`, `IdentifierMap`.
**Members to move:** `writeActivity`, `writeActivityNode`, `writeActivityEdge`, `activityNodeXmiType`, `activityEdgeXmiType`.

- [ ] **Cycle + Commit:** `git commit -m "refactor(uml-xmi): extract activity writer to .write.activity"`

### Task 20: `.build` — XmiBuilder (dispatch) + make Main thin

**Files:** Create `.../umlxmi/build/XmiBuilder.java`; modify `Main.java`.
**Interfaces:** Produces `public static String XmiBuilder.buildXmi(ExportRequest, UmlXmiExportPolicy)` dispatching each node type to the matching writer (Tasks 13–19). Consumes: all writers + helpers. `Main.exportFromStdin` delegates to `XmiBuilder` + `SchemaValidation`.
**Members to move:** `buildXmi` (+ any residual private dispatch helpers).
**Main after this task contains only:** `moduleName`, `main`, `executeForTesting`, `execute`, `capabilitiesJson`, `exportFromStdin` (delegating), `genericGraphPluginData`, `exitWithDiagnostic`.

- [ ] **Step 1:** Create `XmiBuilder`; move `buildXmi`; wire dispatch to the writer classes.
- [ ] **Step 2:** Rewrite `Main.exportFromStdin` to call `XmiBuilder.buildXmi(...)` then `SchemaValidation.validateXmiToAvailableStandards(...)`.
- [ ] **Step 3:** Compile → `BUILD SUCCESS`; Test — `./mvnw -pl plugins/uml-xmi-export,cli -am test` → 0 failures.
- [ ] **Step 4:** Verify Main shrank: `wc -l .../umlxmi/Main.java` → expect ≲ 200 LOC.
- [ ] **Commit:** `git commit -m "refactor(uml-xmi): extract buildXmi dispatch to .build.XmiBuilder; Main is now thin"`

---

## S4 — Launcher hoist

### Task 21: Hoist plugin appassembler execution to parent pluginManagement

**Files:**
- Modify: `pom.xml` (parent `<pluginManagement>` appassembler entry)
- Modify: `plugins/render/pom.xml`, `plugins/generic-graph/pom.xml`, `plugins/elk-layout/pom.xml`, `plugins/archimate-oef-export/pom.xml`, `plugins/uml-xmi-export/pom.xml`
- Leave unchanged: `cli/pom.xml`

**Interfaces:** Parent pluginManagement gains a shared `<execution id="assemble">` (phase `package`, goal `assemble`, `<extraJvmArguments>${dediren.launcher.jvmArgs}</extraJvmArguments>`). Each plugin pom keeps only its per-module `<programs>` block referencing execution `id=assemble`.

- [ ] **Step 1:** In parent `pom.xml`, add to the existing appassembler `<pluginManagement>` entry:
```xml
<executions>
  <execution>
    <id>assemble</id>
    <phase>package</phase>
    <goals><goal>assemble</goal></goals>
    <configuration>
      <extraJvmArguments>${dediren.launcher.jvmArgs}</extraJvmArguments>
    </configuration>
  </execution>
</executions>
```
- [ ] **Step 2:** In each of the 5 plugin poms, replace the full appassembler `<executions>` block with the minimal inheriting form (example for render):
```xml
<plugin>
  <artifactId>appassembler-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>assemble</id>
      <configuration>
        <programs>
          <program>
            <mainClass>dev.dediren.plugins.render.Main</mainClass>
            <id>render</id>
          </program>
        </programs>
      </configuration>
    </execution>
  </executions>
</plugin>
```
Use each plugin's own `mainClass` and program `id` (`generic-graph`, `elk-layout`, `archimate-oef-export`, `uml-xmi-export`).
- [ ] **Step 3:** Build the launchers — `./mvnw -q -pl cli,plugins/render,plugins/generic-graph,plugins/elk-layout,plugins/archimate-oef-export,plugins/uml-xmi-export -am -DskipTests package` → `BUILD SUCCESS`.
- [ ] **Step 4:** Confirm each plugin still produces its launcher script:
```bash
for p in render generic-graph elk-layout archimate-oef-export uml-xmi-export; do \
  ls plugins/$p/target/appassembler/bin/ 2>/dev/null; done
```
Expected: each plugin's launcher script present (same names as before).
- [ ] **Step 5:** Dist smoke — `./mvnw -pl dist-tool -am verify -Pdist-smoke` → `BUILD SUCCESS`.
- [ ] **Step 6:** `git diff --check` → no whitespace errors.
- [ ] **Commit:**
```bash
git add pom.xml plugins/*/pom.xml
git commit -m "build: hoist plugin appassembler execution to parent pluginManagement"
```

---

## Task 22: Full verification + audit gates

**Files:** none (verification + fixups only).

- [ ] **Step 1: Full reactor test** — `./mvnw test` → `BUILD SUCCESS`, all modules 0 failures/errors.
- [ ] **Step 2: Dist smoke** — `./mvnw -pl dist-tool -am verify -Pdist-smoke` → `BUILD SUCCESS`.
- [ ] **Step 3: Whitespace** — `git diff --check` (against `main`) → clean.
- [ ] **Step 4: Confirm outcomes** — `Main` LOC now thin (render ≲ 250, uml-xmi ≲ 200); no `Main.java` over ~400 LOC remains in either plugin.
- [ ] **Step 5: Audit gates (per CLAUDE.md `## Audit Gates`)** — render + export changes:
  - `souroldgeezer-audit:test-quality-audit` **Deep** on the render + uml-xmi test suites/fixtures.
  - `souroldgeezer-audit:devsecops-audit` **Quick** on the export boundary + the S4 pom change.
  Fix block findings; fix or explicitly accept warn/info findings, then rerun affected checks.
- [ ] **Step 6:** No commit unless an audit fixup is needed (then commit that fixup with an explicit message).

---

## Self-Review (plan author)

**Spec coverage:**
- render fine-grained split (spec §4) → Tasks 1–9 (style, svg/Svg, node predicates, labels, geometry, edge, archimate, uml, orchestrator). ✓
- uml-xmi fine-grained split (spec §5) → Tasks 10–20 (helpers, policy, schema, 7 writers, dispatch). ✓
- Visibility seam (spec §6) → Global Constraints + per-task "make public where cross-package". ✓
- S4 plugins-only, CLI explicit (spec §7) → Task 21 (5 plugin poms, `cli/pom.xml` untouched). ✓
- Behavior preservation + verification (spec §3, §9) → Move-and-Delegate Cycle + Task 0 baseline + Task 22 full run. ✓
- Audit gates (spec §10) → Task 22 Step 5. ✓

**Placeholder scan:** No "TBD/TODO"; every task lists exact members, files, commands, expected output. "Similar to Task N" avoided — each task repeats its member list. Method bodies are moved verbatim (not rewritten), which is the correct content for a mechanical extraction; the member lists are the actionable spec.

**Type consistency:** New class names are used consistently across consuming tasks (`StyleResolver`, `Resolved*Style`, `Svg`, `LabelBox`, `NodeLabels`, `Geometry`/`SvgBounds`, `EdgeRenderer`, `ArchimateIcons`/`ArchimateIconKind`, `UmlShapes`/`UmlDecorators`, `XmiHelpers`/`IdentifierMap`/`ExportScope`, per-family `*Writer`, `XmiBuilder`, `SchemaValidation`, `PolicyValidation`). Extraction order is leaves-first so no class calls back into `Main`.

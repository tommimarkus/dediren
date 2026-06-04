# Dediren UML Sequence MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to execute this plan only after the user explicitly approves implementation and confirms whether to use a worktree. Do not execute this plan directly on `main` without explicit consent.

**Goal:** Add the first usable UML sequence diagram slice to Dediren: source validation, projection, ELK-backed layout ordering, SVG rendering, XMI export, CLI pipeline coverage, docs, and version metadata.

**Architecture:** Keep Dediren contract-first. Additive public shape changes move together through schemas, contracts, fixtures, projection, renderer, exporter, docs, and tests. Keep `apps:cli` thin. Keep UML semantics in `modules/uml` and `modules/plugins/uml-xmi-export`; keep SVG sequence drawing in `modules/plugins/svg-render`; keep layout behavior in `modules/plugins/elk-layout` using ELK-first structure and options before custom geometry.

**Tech Stack:** Java 21, Maven Wrapper, JSON Schema, first-party process-boundary plugins, Eclipse ELK, SVG, UML 2.5.1 semantics.

**Primary Model Assignment:**

- `gpt-5.3-codex`: implementation agents for Java/schema/plugin changes.
- `gpt-5.5`: architecture and UML semantic review checkpoints.
- `gpt-5.4-mini`: mechanical fixture, docs, and stale-version sweep tasks after the design is locked.

---

## Preflight

1. Check repository state:

   ```bash
   git status --short --branch
   ```

   Expected: note the current branch and any pre-existing changed files. Treat pre-existing work as user-owned.

2. Confirm implementation workspace:

   - If still on `main`, ask the user whether to create/use a worktree.
   - If using a worktree, follow `superpowers:using-git-worktrees`.
   - Do not stage or commit the existing roadmap file unless the user wants this planning work included.

3. Load only the relevant context:

   - `docs/superpowers/plans/2026-06-04-dediren-uml-expansion-roadmap.md`
   - `schemas/model.schema.json`
   - `schemas/render-metadata.schema.json`
   - `schemas/svg-render-policy.schema.json`
   - `modules/contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java`
   - `modules/uml/src/main/java/dev/dediren/uml/Uml.java`
   - `modules/plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java`
   - `modules/plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java`
   - `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`
   - `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/SvgNodeDecorator.java`
   - `modules/plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`
   - `README.md`
   - `docs/agent-usage.md`

4. Use official UML research artifacts already captured in the roadmap as semantic reference:

   - OMG UML 2.5.1 specification: `https://www.omg.org/spec/UML`
   - OMG UML 2.5.1 machine-readable XMI: `https://www.omg.org/spec/UML/machine-readable`
   - Local research copies, if still present: `/tmp/dediren-uml-2.5.1-UML.xmi` and `/tmp/dediren-uml-2.5.1.pdf`

---

## Sequence MVP Contract

Implement this first slice as an additive UML surface:

- New view kind: `uml-sequence`
- New UML node types:
  - `Interaction`
  - `Lifeline`
  - `ExecutionSpecification`
  - `Gate`
  - `DestructionOccurrenceSpecification`
- New UML relationship type:
  - `Message`
- Supported message sorts:
  - `synchCall`
  - `asynchCall`
  - `asynchSignal`
  - `reply`
  - `createMessage`
  - `deleteMessage`
- Source conventions:
  - A sequence view contains one `Interaction` group or node and at least two `Lifeline` nodes for a meaningful interaction.
  - `Lifeline` nodes may carry `properties.uml.interaction` to name their owning interaction.
  - `Message` relationships connect lifeline endpoints for the MVP.
  - `Message` relationships must carry a stable order through `properties.uml.sequence` as an integer greater than or equal to `1`.
  - `Message` relationships may carry `properties.uml.message_sort`; default is `synchCall`.
  - `ExecutionSpecification`, `Gate`, and `DestructionOccurrenceSpecification` are accepted vocabulary in this slice. Their exact geometry may be compact in SVG until the combined-fragment slice.
- Projection conventions:
  - Project UML edge selector properties, not only node selector properties.
  - Emit sequence layout constraints from source order:
    - `uml.sequence.lifeline-order`
    - `uml.sequence.message-order`
- Layout conventions:
  - Prefer ELK layered graph structure, fixed ports, hierarchy, and supported ordering options.
  - If a final minor normalization step is required after ELK output, keep it sequence-scoped and backed by a failing test that demonstrates the ELK-only gap.
- Render conventions:
  - Render lifeline heads, dashed lifeline stems, horizontal message arrows, message labels, execution bars, create/delete markers, and an interaction frame where available.
  - Enter sequence render mode when UML metadata contains `Lifeline` nodes or `Message` edges.
- Export conventions:
  - Emit UML XMI `uml:Interaction`, nested `lifeline`, and nested `message` elements.
  - Deterministically synthesize message occurrence ids from the source relationship id when authored occurrence nodes are absent.

---

## Task 1: Write Failing Contract And Schema Tests

**Goal:** Pin the public surface before implementation.

**Files to edit:**

- `schemas/model.schema.json`
- `modules/contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java`
- `modules/contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`
- `fixtures/source/valid-uml-sequence-basic.json`

**Steps:**

1. Add `fixtures/source/valid-uml-sequence-basic.json` with one interaction, two lifelines, and three ordered messages:

   - `customer: Lifeline`
   - `service: Lifeline`
   - `m1: Message`, sequence `1`, sort `synchCall`
   - `m2: Message`, sequence `2`, sort `reply`
   - `m3: Message`, sequence `3`, sort `asynchSignal`

2. Add a contract round-trip test that parses the fixture and asserts:

   - `semantic_profile` is `uml`
   - view kind is `uml-sequence`
   - relationship ids remain stable
   - relationship `properties.uml.sequence` values remain numeric
   - relationship `properties.uml.message_sort` values remain strings

3. Add `uml-sequence` to the schema view kind enum.

4. Add `UML_SEQUENCE("uml-sequence")` to `GenericGraphViewKind`.

5. Run:

   ```bash
   ./mvnw -pl modules/contracts -am test -Dtest=ContractRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

   Expected before implementation: failure on missing view kind support. Expected after implementation: green contract tests.

**Self-review checkpoint:**

- Confirm the fixture uses only Dediren source contract fields that already exist, except the additive view kind and UML values.
- Confirm no UML semantics were added to `modules:contracts` beyond the view kind enum.

---

## Task 2: Add UML Sequence Vocabulary And Validation

**Goal:** Make `modules/uml` accept valid sequence elements and reject invalid sequence endpoints/order.

**Files to edit:**

- `modules/uml/src/main/java/dev/dediren/uml/Uml.java`
- `modules/uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`

**Steps:**

1. Extend the UML vocabulary in `Uml.java`:

   - Add sequence node types to a dedicated `SEQUENCE_TYPES` set.
   - Add `Message` to relationship types.
   - Add a `MESSAGE_SORTS` set with the supported sorts from the MVP contract.

2. Add validation behavior:

   - `validateElementType("Lifeline")` succeeds.
   - `validateRelationshipType("Message")` succeeds.
   - `validateRelationshipEndpoints("Message", fromType, toType)` accepts lifeline-to-lifeline and lifeline-to-destruction occurrence endpoints.
   - `Message` cannot target structural classifier nodes such as `Class`.
   - `ControlFlow` and `ObjectFlow` behavior remains unchanged.

3. Add validation of relationship UML properties:

   - `properties.uml.sequence` must be present for `Message`.
   - `properties.uml.sequence` must be an integer greater than or equal to `1`.
   - `properties.uml.message_sort`, when present, must be in `MESSAGE_SORTS`.

4. If existing validator entry points do not pass relationship properties to `modules/uml`, add a narrow helper that callers can use without moving orchestration into `modules:contracts`.

5. Add focused tests:

   - `acceptsUmlSequenceVocabulary`
   - `rejectsMessageWithoutSequenceOrder`
   - `rejectsMessageWithNonPositiveSequenceOrder`
   - `rejectsUnknownMessageSort`
   - `rejectsMessageToClassEndpoint`
   - Existing activity and structural tests stay green.

6. Run:

   ```bash
   ./mvnw -pl modules/uml -am test
   ```

   Expected: all UML validation tests pass.

**Self-review checkpoint:**

- Confirm UML-specific validation stayed inside `modules/uml`.
- Confirm no sequence implementation depends on plugin internals.

---

## Task 3: Project Sequence Metadata And Constraints

**Goal:** Ensure the generic graph plugin carries enough sequence data for layout and render plugins.

**Files to edit:**

- `modules/plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java`
- `modules/plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java`
- `modules/plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`
- `schemas/render-metadata.schema.json`, only if existing selector `properties` cannot represent edge UML metadata

**Steps:**

1. Add failing tests in `GenericGraphPluginTest` using `valid-uml-sequence-basic.json`:

   - `projectsUmlSequenceViewKind`
   - `projectsUmlSequenceEdgeRenderMetadata`
   - `projectsUmlSequenceLayoutConstraints`
   - `projectsUmlSequenceLifelineSizeHints`

2. Project edge selector properties for UML relationships:

   - For `semantic_profile = "uml"`, copy `relationship.properties.uml` into the render metadata selector for that edge.
   - Preserve existing behavior for non-UML profiles.

3. Add sequence layout constraints:

   - `uml.sequence.lifeline-order` subjects are lifeline ids in source order.
   - `uml.sequence.message-order` subjects are message ids sorted by `properties.uml.sequence`, then source order for stable tie diagnostics.

4. Add deterministic size hints:

   - `Interaction`: wide frame hint, for example `360 x 260`.
   - `Lifeline`: head hint, for example `140 x 48`.
   - `ExecutionSpecification`: compact vertical bar hint, for example `16 x 72`.
   - `Gate`: compact connector hint, for example `24 x 24`.
   - `DestructionOccurrenceSpecification`: compact marker hint, for example `24 x 24`.

5. Run:

   ```bash
   ./mvnw -pl modules/plugins/generic-graph -am test -Dtest=GenericGraphPluginTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

   Expected: projection tests pass and existing UML class/data/activity projection tests remain green.

**Self-review checkpoint:**

- Confirm source relationships are not mutated during projection.
- Confirm render metadata preserves message ordering and sort without relying on string parsing.

---

## Task 4: Add ELK Sequence Ordering Support

**Goal:** Produce stable, readable sequence layout geometry from the existing plugin pipeline.

**Files to edit:**

- `modules/plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java`
- Add a helper if needed:
  - `modules/plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/SequenceLayoutConstraints.java`
- `modules/plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java`

**Steps:**

1. Add tests with a layout request equivalent to the sequence fixture:

   - `laysOutSequenceLifelinesInConstraintOrder`
   - `laysOutSequenceMessagesInConstraintOrder`
   - `ignoresSequenceConstraintsForNonSequenceGraphs`

2. Parse recognized constraints:

   - `uml.sequence.lifeline-order`
   - `uml.sequence.message-order`

3. Apply ELK-first structure:

   - Use layered layout direction `RIGHT`.
   - Use fixed node order for lifeline head nodes where ELK supports it.
   - Use fixed ports for messages where practical.
   - Keep lifeline heads in one horizontal band.
   - Keep message route y positions increasing by message order.

4. If ELK alone does not preserve message y order, add a sequence-scoped normalization stage that:

   - Runs only when recognized sequence constraints are present.
   - Leaves non-sequence graphs untouched.
   - Adjusts only lifeline head alignment, lifeline x ordering, and message edge bend-point y ordering.
   - Uses layout result contracts already supported by render plugins.

5. Capture evidence in tests:

   - Lifeline x coordinates increase in constraint order.
   - Message first segment y coordinates increase in message order.
   - Existing generic and activity layout tests remain green.

6. Run:

   ```bash
   ./mvnw -pl modules/plugins/elk-layout -am test
   ```

   Expected: ELK plugin tests pass.

**Self-review checkpoint:**

- Confirm no global ELK behavior changed for generic, ArchiMate, class, data, or activity views.
- Confirm any normalization is isolated behind explicit sequence constraints and documented in the helper name or method name.

---

## Task 5: Render Sequence Diagrams To SVG

**Goal:** Render a basic UML sequence diagram without custom authored geometry.

**Files to edit:**

- `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`
- `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/SvgNodeDecorator.java`
- `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/SvgRenderPolicyValidator.java`
- `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/RenderInputValidator.java`
- Add helper classes if needed:
  - `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/UmlSequenceRenderer.java`
  - `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/UmlSequenceModel.java`
- `modules/plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`
- `fixtures/render-policy/uml-svg.json`
- `fixtures/render-metadata/uml-sequence-basic.json`
- `fixtures/layout-result/uml-sequence-basic.json`

**Steps:**

1. Add render fixtures:

   - `fixtures/render-metadata/uml-sequence-basic.json` with node selectors for `Interaction`, `Lifeline`, and optional execution/destruction elements, plus edge selectors for `Message`.
   - `fixtures/layout-result/uml-sequence-basic.json` matching the layout contract with lifeline heads and routed message edges.

2. Extend SVG policy validation and decorator mapping:

   - Add decorators for `UML_LIFELINE`, `UML_INTERACTION`, `UML_EXECUTION_SPECIFICATION`, `UML_GATE`, `UML_DESTRUCTION_OCCURRENCE`.
   - Add edge style handling for `Message` in `uml-svg.json`.

3. Add `UmlSequenceRenderer` behind a narrow dispatch:

   - `Main` detects sequence mode when metadata contains a UML `Lifeline` selector or UML `Message` selector.
   - In sequence mode, render sequence-specific layers and do not duplicate them through the generic edge/node path.

4. Render layers in deterministic order:

   - Interaction frame background and title.
   - Lifeline heads.
   - Dashed lifeline stems extending to the diagram bottom.
   - Execution bars.
   - Message arrows and labels in sequence order.
   - Delete marker for `deleteMessage`.

5. Implement message sort appearance:

   - `synchCall`: solid line, filled or closed call arrow according to existing marker capability.
   - `asynchCall` and `asynchSignal`: solid line, open arrow.
   - `reply`: dashed line, open arrow.
   - `createMessage`: solid line to created lifeline head.
   - `deleteMessage`: solid line ending at destruction marker.

6. Add SVG tests that parse output and assert:

   - SVG contains lifeline head labels.
   - Lifeline stems are dashed.
   - Message labels appear in order.
   - Reply message has dashed stroke.
   - Delete message renders an `X` marker when present in fixture.
   - Existing class, data, activity, and generic render tests remain green.

7. Run:

   ```bash
   ./mvnw -pl modules/plugins/svg-render -am test -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

   Expected: SVG renderer tests pass.

**Self-review checkpoint:**

- Confirm the renderer consumes structured metadata instead of parsing SVG text or labels.
- Confirm sequence rendering does not change non-sequence SVG output.

---

## Task 6: Export Sequence Diagrams To UML XMI

**Goal:** Make the UML/XMI export plugin emit valid interaction, lifeline, and message structures for the MVP fixture.

**Files to edit:**

- `modules/plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`
- Add helper classes if needed:
  - `modules/plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/UmlSequenceExport.java`
- `modules/plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`
- `fixtures/export/uml-sequence-basic.xmi`

**Steps:**

1. Add failing exporter tests:

   - `exportsSequenceInteractionLifelinesAndMessages`
   - `exportsSequenceMessagesInSequenceOrder`
   - `rejectsInvalidSequenceMessageEndpoint`

2. Extend source scoping:

   - Include selected `Interaction` owners when any selected lifeline or message belongs to them.
   - Include lifelines referenced by selected messages.
   - Preserve existing activity scoping behavior.

3. Emit XMI:

   - `packagedElement xmi:type="uml:Interaction"` for each interaction.
   - Nested `lifeline` elements for lifelines owned by the interaction.
   - Nested `message` elements for ordered messages.
   - Deterministic synthetic send and receive event ids when authored occurrence nodes are absent.
   - `messageSort` attribute from `properties.uml.message_sort`, defaulting to `synchCall`.

4. Do not emit unsupported combined fragments in this slice. If a source includes `CombinedFragment`, validation should reject it until the next slice adds support.

5. Run:

   ```bash
   ./mvnw -pl modules/plugins/uml-xmi-export -am test -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false
   ```

   Expected: UML/XMI export tests pass.

**Self-review checkpoint:**

- Confirm XMI ids are deterministic and valid XML ids under existing exporter rules.
- Confirm unsupported interaction concepts fail with structured diagnostics instead of silent omission.

---

## Task 7: Wire CLI Pipeline Fixtures And Docs

**Goal:** Make sequence diagrams usable by a user or downstream agent through the documented workflow.

**Files to edit:**

- `apps/cli/src/test/java/dev/dediren/cli/MainTest.java`
- `README.md`
- `docs/agent-usage.md`
- `fixtures/source/valid-uml-sequence-basic.json`
- `fixtures/render-policy/uml-svg.json`
- Any distribution fixture index or metadata files that enumerate example fixtures

**Steps:**

1. Add CLI coverage for:

   - validating the sequence source fixture
   - projecting it through generic graph
   - laying it out with the bundled ELK plugin
   - rendering SVG
   - exporting UML XMI

2. Update `README.md`:

   - Add `uml-sequence` to supported view kinds.
   - Add a compact sequence source example.
   - Add commands for validate, layout, render, and UML XMI export using the fixture.
   - Note supported MVP concepts and explicitly name combined fragments, state machines, use cases, deployment, and UMLDI as later slices.

3. Update `docs/agent-usage.md`:

   - Add command-oriented sequence diagram handoff guidance.
   - Keep it bundle-local and concise.
   - Mention that success/failure must be determined from stdout JSON envelopes.

4. Run:

   ```bash
   ./mvnw -pl modules/core,apps/cli -am test
   ```

   Expected: CLI and core pipeline tests pass.

**Self-review checkpoint:**

- Confirm README and agent guide agree on commands, fixture names, artifact paths, and supported UML scope.
- Confirm CLI remains only argument parsing, request assembly, core invocation, and envelope printing.

---

## Task 8: Version, Distribution, And Stale-Version Sweep

**Goal:** Keep additive public surface and first-party plugin metadata consistent.

**Files to edit:**

- `pom.xml`
- `fixtures/plugins/*.manifest.json`
- `fixtures/source/*.json`, only version-bearing `required_plugins[]` entries
- `README.md`
- `docs/agent-usage.md`
- `apps/cli/src/test/java/dev/dediren/cli/MainTest.java`
- `modules/contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`
- `modules/plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`
- `modules/plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`
- `tools/dist/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

**Steps:**

1. Because `uml-sequence` is additive public surface, perform a pre-1.0 minor version bump:

   ```bash
   ./mvnw build-helper:parse-version versions:set -DnewVersion='${parsedVersion.majorVersion}.${parsedVersion.nextMinorVersion}.0' -DprocessAllModules=true -DgenerateBackupPoms=false
   ```

2. Update first-party plugin manifests, source fixture `required_plugins[].version`, README examples, agent guide examples, distribution metadata, and version assertion tests to match the new root version.

3. Run stale-version search over the known surfaces:

   ```bash
   rg -n "0\\.20\\.0|0\\.21\\.0|version" pom.xml README.md docs/agent-usage.md fixtures/plugins fixtures/source apps/cli/src/test modules/contracts/src/test modules/plugins/archimate-oef-export/src/test modules/plugins/generic-graph/src/test tools/dist/src/test
   ```

   Expected: no stale old version remains in version assertion surfaces. Replace `0.20.0` and `0.21.0` with the actual old and new versions observed during execution.

4. Do not create the annotated git tag until implementation is ready to commit and the user approves release-style git actions.

**Self-review checkpoint:**

- Confirm all first-party plugin manifest versions match the root POM.
- Confirm the version bump is in the same implementation commit as the public surface addition.

---

## Task 9: Full Verification And Audit Gates

**Goal:** Prove the sequence slice is coherent across contracts, plugins, CLI, docs, and distribution.

**Verification commands:**

1. Narrow checks:

   ```bash
   ./mvnw -pl modules/contracts -am test
   ./mvnw -pl modules/uml -am test
   ./mvnw -pl modules/plugins/generic-graph -am test
   ./mvnw -pl modules/plugins/elk-layout -am test
   ./mvnw -pl modules/plugins/svg-render -am test
   ./mvnw -pl modules/plugins/uml-xmi-export -am test
   ./mvnw -pl modules/core,apps/cli -am test
   ```

2. Broad checks:

   ```bash
   ./mvnw test
   ./mvnw -pl tools/dist -am verify -Pdist-smoke
   git diff --check
   ```

3. Audit gates required by repository guidance for this plan:

   - `souroldgeezer-audit:test-quality-audit`
     - Depth: Deep
     - Scope: contracts, UML validation, generic graph projection, ELK layout, SVG render, UML XMI export, CLI fixtures.
   - `souroldgeezer-audit:devsecops-audit`
     - Depth: Quick
     - Scope: plugin process boundaries, dependency posture, generated artifacts, docs, distribution metadata.

4. Fix block findings. Fix warn/info findings or record accepted residual risk in the handoff, then rerun affected checks.

5. Finish with:

   ```bash
   git status --short --branch
   ```

   Expected: only intentional implementation files changed. No generated `target/`, `dist/`, `.cache/`, downloaded Maven wrapper jar, or generated SVG artifacts staged by default.

---

## Implementation Order And Review Checkpoints

Execute in this order:

1. Contract and schema tests.
2. UML validation.
3. Generic graph projection.
4. ELK sequence layout.
5. SVG rendering.
6. UML XMI export.
7. CLI/docs.
8. Version/distribution.
9. Verification and audits.

Pause for review after:

- Task 1, because it locks public shape.
- Task 4, because layout ordering may require an ELK-scoped normalization decision.
- Task 6, because XMI semantics should be checked against UML 2.5.1 before docs claim export support.
- Task 9, before commit/tag/PR actions.

---

## Completion Criteria

The sequence MVP is complete only when all criteria are true:

- `uml-sequence` validates through source schema and contract round-trip tests.
- `Message` validation rejects missing/invalid sequence order, invalid message sort, and invalid endpoints.
- Generic graph projection emits edge UML render metadata and sequence layout constraints.
- ELK layout output preserves lifeline and message ordering for sequence fixtures.
- SVG output renders lifelines, dashed stems, ordered messages, labels, and reply styling.
- UML XMI export emits deterministic interaction, lifeline, and message elements.
- README and `docs/agent-usage.md` document the exact supported MVP surface and commands.
- Product/plugin versions are consistent with the additive public surface.
- Narrow and broad verification commands pass.
- Required test-quality and DevSecOps audit gates are complete.

# Dediren UML Expansion Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand Dediren's first-party UML profile beyond the current class/data/activity slice with contract-first, vertically sliced support for missing UML diagram families, starting with sequence diagrams.

**Architecture:** Keep Dediren JSON as the authored source and preserve the existing module split: `modules:contracts` owns public records and schema constants, `modules:uml` owns UML vocabulary and semantic validation, `modules/plugins/generic-graph` owns projection, `modules/plugins/elk-layout` owns generated geometry, `modules/plugins/svg-render` owns SVG notation, and `modules/plugins/uml-xmi-export` owns UML/XMI compatibility output. Each slice must deliver a working source fixture through validate, project, layout, validate-layout, render, optional export, docs, distribution smoke coverage when public bundle behavior changes, and focused tests.

**Tech Stack:** Java 21+, Maven Wrapper, Jackson, public JSON schemas, first-party process-boundary plugins, official Eclipse ELK Java libraries, SVG render policy, UML 2.5.1 XMI export, `xmllint`/schema cache for export validation.

---

## Scope Check

This is a multi-slice roadmap, not a single executable implementation plan. Each vertical slice below must get its own detailed `docs/superpowers/plans/YYYY-MM-DD-<slice>.md` before implementation. Do not implement multiple diagram families in one branch unless a later plan proves the shared contract work is smaller and safer than separate delivery.

Current live support is limited to:

- `plugins.generic-graph.semantic_profile: "uml"` in `schemas/model.schema.json`.
- View kinds `uml-class`, `uml-data`, and `uml-activity`.
- UML vocabulary in `modules/uml/src/main/java/dev/dediren/uml/Uml.java` for `Package`, `Class`, `Interface`, `DataType`, `Enumeration`, a small activity node set, and structural/activity relationships.
- UML SVG notation and UML/XMI export for the current class/data/activity slice.

The first missing customer-visible capability is UML sequence diagrams. Sequence support belongs to UML Interactions, not to the existing activity slice.

## Research Basis

Official UML sources:

- OMG UML 2.5.1 specification page: `https://www.omg.org/spec/UML`.
- OMG UML 2.5.1 machine-readable page: `https://www.omg.org/spec/UML/machine-readable`.
- Normative machine-readable files named by OMG: `UML/20161101/UML.xmi`, `PrimitiveTypes.xmi`, `StandardProfile.xmi`, and `UMLDI.xmi`.

Local research downloaded the official UML 2.5.1 metamodel XMI to `/tmp/dediren-uml-2.5.1-UML.xmi` and the formal PDF to `/tmp/dediren-uml-2.5.1.pdf`. Those are research artifacts only and must not be committed.

OpenAI model assignment sources:

- `https://developers.openai.com/api/docs/models/gpt-5.5/`
- `https://developers.openai.com/api/docs/models/gpt-5.3-codex`
- `https://developers.openai.com/api/docs/models/gpt-5.4-mini`

Use those assignments as planning guidance, not hard runtime requirements. If the local Codex environment exposes only older repo-conventional model labels, map `gpt-5.3-codex` work to `gpt-5-codex` and `gpt-5.4-mini` work to the closest available mini model.

## Missing UML Families From The Official Metamodel

The official UML 2.5.1 metamodel packages relevant to Dediren expansion include:

- Interactions: `Interaction`, `Lifeline`, `Message`, `MessageEnd`, `MessageOccurrenceSpecification`, `OccurrenceSpecification`, `ExecutionSpecification`, `ActionExecutionSpecification`, `BehaviorExecutionSpecification`, `CombinedFragment`, `InteractionOperand`, `InteractionUse`, `Gate`, `StateInvariant`, `GeneralOrdering`, `MessageSort`, and `InteractionOperatorKind`.
- StateMachines: `StateMachine`, `Region`, `State`, `FinalState`, `Pseudostate`, `Transition`, `ConnectionPointReference`, `ProtocolStateMachine`, `ProtocolTransition`, `PseudostateKind`, and `TransitionKind`.
- UseCases: `Actor`, `UseCase`, `Include`, `Extend`, and `ExtensionPoint`.
- StructuredClassifiers and Components: `Component`, `Port`, `Connector`, `ConnectorEnd`, `ComponentRealization`, `Collaboration`, `CollaborationUse`, and `ConnectorKind`.
- Deployments: `Node`, `Device`, `ExecutionEnvironment`, `Artifact`, `Deployment`, `DeploymentSpecification`, `Manifestation`, and `CommunicationPath`.
- Actions and deeper Activities: pins, invocation actions, accept event actions, structured activity nodes, expansion regions, loop/conditional nodes, and additional object/control node variants.
- Packages, CommonStructure, Classification, Values, and CommonBehavior contain cross-cutting metaclasses needed for richer package/profile/object/state/action support.

## Boundary Rules

- Keep `apps:cli` thin. CLI changes should expose existing core/plugin commands and fixture examples only.
- Keep semantic validation in `modules/uml`; do not put UML metaclass legality in `modules:core`.
- Keep public JSON shapes synchronized across `schemas/`, `modules:contracts`, fixtures, plugin mapping code, schema tests, and round-trip tests.
- Keep first-party plugins as process-boundary plugins. They may depend on `modules:contracts`; they must not depend on `modules:core`.
- Do not duplicate layout/routing features already provided by ELK. Try official ELK options, graph structure, ports, hierarchy, and real-render evidence before adding custom placement or route geometry code.
- Treat UML/XMI as compatibility export. Dediren source JSON remains the authored source of truth.
- Add UMLDI only after a slice proves model XMI without diagram interchange is insufficient for the target workflow.

## Model Assignment Rules

- Use `gpt-5.5` for semantic design, scope decomposition, metamodel interpretation, final audit synthesis, and high-risk contract decisions.
- Use `gpt-5.3-codex` for agentic coding slices that touch Java modules, schemas, fixtures, render/export plugins, and integration tests.
- Use `gpt-5.4-mini` for bounded docs updates, stale-version searches, manifest/version mechanical edits, and checklist-style verification summaries.

## Vertical Slice Roadmap

### Task 0: Coverage Matrix And Slice Specs

**Model:** `gpt-5.5`

**Purpose:** Create a durable UML coverage matrix so future work expands deliberately instead of chasing one-off element names.

**Files:**

- Create: `docs/superpowers/specs/2026-06-04-dediren-uml-expansion-design.md`
- Create or update: `docs/superpowers/plans/2026-06-04-dediren-uml-sequence-mvp.md`

- [ ] Map current Dediren support to official UML 2.5.1 metamodel families.
- [ ] Mark each family as supported, first-slice candidate, deferred, or out of scope.
- [ ] Define slice acceptance rules: source-valid, view-readable, render-ready, optional XMI export, docs, and distribution impact.
- [ ] Write the dedicated sequence MVP implementation plan before code edits.
- [ ] Verify with `git diff --check`.

### Task 1: Sequence MVP

**Model:** `gpt-5.3-codex`

**Purpose:** Deliver the first usable UML sequence diagram workflow.

**First working output:** A `uml-sequence` view that validates, projects, lays out, renders SVG, and exports deterministic UML/XMI for a simple request/response interaction.

**Primary files:**

- Modify: `schemas/model.schema.json`
- Modify: `modules/contracts/src/main/java/dev/dediren/contracts/source/GenericGraphViewKind.java`
- Modify: `modules/uml/src/main/java/dev/dediren/uml/Uml.java`
- Modify: `modules/plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphProjection.java`
- Modify: `modules/plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java`
- Modify: `modules/plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java`
- Modify: `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`
- Modify: `modules/plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/RenderInputValidator.java`
- Modify: `modules/plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`
- Create: `fixtures/source/valid-uml-sequence-basic.json`
- Create: `fixtures/render-metadata/uml-sequence-basic.json`
- Create: `fixtures/layout-result/uml-sequence-basic.json`
- Create: `fixtures/export/uml-sequence-basic.xmi`
- Update affected tests under `modules/uml`, `modules/contracts`, `modules/plugins/generic-graph`, `modules/plugins/elk-layout`, `modules/plugins/svg-render`, `modules/plugins/uml-xmi-export`, and `apps/cli`.

- [ ] Add `uml-sequence` view kind to schema and contract enum.
- [ ] Add UML Interactions MVP vocabulary: `Interaction`, `Lifeline`, `Message`, `MessageOccurrenceSpecification`, `ExecutionSpecification`, `DestructionOccurrenceSpecification`, `Gate`.
- [ ] Add enum validation for `MessageSort`: `synchCall`, `asynchCall`, `asynchSignal`, `createMessage`, `deleteMessage`, `reply`.
- [ ] Define source JSON conventions under `properties.uml`: interaction id, lifeline represented element, message sort, sequence/order index, optional source/target gates, activation start/end references.
- [ ] Add projection rules for lifeline nodes and message relationships.
- [ ] Add layout contract support for sequence ordering. Prefer typed layout constraints over implicit ordering in labels. If the existing `LayoutConstraint` shape is too weak, update the schema and records in the same slice.
- [ ] Try ELK-first layout using direction, ports, hierarchy, and ordering constraints. Add custom geometry only if real-render evidence proves ELK cannot preserve lifeline columns and message order.
- [ ] Render lifeline heads, dashed vertical lifelines, horizontal messages, reply arrows, create/delete markers, found/lost gates, and activation bars.
- [ ] Export UML/XMI model content for the MVP interaction.
- [ ] Update README and `docs/agent-usage.md` with sequence authoring and command examples.
- [ ] Run:

```bash
./mvnw -pl modules/uml,modules/contracts,modules/plugins/generic-graph,modules/plugins/elk-layout,modules/plugins/svg-render,modules/plugins/uml-xmi-export,apps/cli -am test
git diff --check
```

### Task 2: Sequence Combined Fragments

**Model:** `gpt-5.3-codex`

**Purpose:** Add the sequence constructs that make diagrams useful for branching, looping, and concurrent flows.

**First working output:** A rendered `uml-sequence` fixture with `alt`, `opt`, `loop`, and `par` combined fragments, guarded operands, and XMI export.

**Primary files:** Same modules as Task 1, plus new sequence-fragment fixtures.

- [ ] Add vocabulary and validation for `CombinedFragment`, `InteractionOperand`, `InteractionConstraint`, `InteractionUse`, and `GeneralOrdering`.
- [ ] Add `InteractionOperatorKind` validation for `seq`, `alt`, `opt`, `break`, `par`, `strict`, `loop`, `critical`, `neg`, `assert`, `ignore`, and `consider`.
- [ ] Represent fragment frames as source-backed groups or typed nodes only after choosing the smaller contract impact.
- [ ] Render combined fragment frames with operator labels and operand separators.
- [ ] Preserve message ordering inside operands and across nested fragments.
- [ ] Export fragment and operand XMI.
- [ ] Run the Task 1 verification lane and inspect generated SVG evidence for readability.

### Task 3: State Machines

**Model:** `gpt-5.3-codex`

**Purpose:** Add lifecycle/state modeling as a separate UML behavior slice.

**First working output:** A `uml-state-machine` view with states, pseudostates, transitions, guards, and triggers.

**Primary files:** Contracts/schema, `modules/uml`, generic graph projection/sizing, SVG renderer, UML/XMI exporter, CLI fixture tests.

- [ ] Add `uml-state-machine` view kind.
- [ ] Add vocabulary and validation for `StateMachine`, `Region`, `State`, `FinalState`, `Pseudostate`, `Transition`, and selected connection-point elements.
- [ ] Add `PseudostateKind` and `TransitionKind` validation.
- [ ] Render UML state notation: rounded states, initial/final nodes, choice/junction/fork/join/deep-history/shallow-history markers, transition labels with event/guard/effect text.
- [ ] Export state machine XMI for selected source/layout scope.
- [ ] Run:

```bash
./mvnw -pl modules/uml,modules/contracts,modules/plugins/generic-graph,modules/plugins/svg-render,modules/plugins/uml-xmi-export,apps/cli -am test
git diff --check
```

### Task 4: Use Case Diagrams

**Model:** `gpt-5.3-codex`

**Purpose:** Add actor/system-goal diagrams for implementation handoff and requirements context.

**First working output:** A `uml-use-case` view with actors, use cases, include/extend relationships, and a subject boundary.

**Primary files:** Contracts/schema, `modules/uml`, generic graph projection/sizing, SVG renderer, UML/XMI exporter, README, agent guide.

- [ ] Add `uml-use-case` view kind.
- [ ] Add `Actor`, `UseCase`, `Include`, `Extend`, and `ExtensionPoint`.
- [ ] Validate relationship endpoints and include/extend direction.
- [ ] Render actor stick figures, use-case ellipses, include/extend dashed arrows, and subject boundary grouping.
- [ ] Export use-case XMI.
- [ ] Run focused module tests and `git diff --check`.

### Task 5: Component And Composite Structure

**Model:** `gpt-5.3-codex`

**Purpose:** Add implementation-structure diagrams without collapsing them into ArchiMate application components.

**First working output:** `uml-component` and/or `uml-composite-structure` views with components, ports, connectors, and realizations.

**Primary files:** Contracts/schema, `modules/uml`, generic graph projection/sizing, ELK port handling, SVG renderer, UML/XMI exporter.

- [ ] Add view kind(s) after deciding whether component and composite structure need separate acceptance slices.
- [ ] Add `Component`, `Port`, `Connector`, `ConnectorEnd`, `ComponentRealization`, `Collaboration`, and `CollaborationUse`.
- [ ] Add `ConnectorKind` validation.
- [ ] Render component symbols, provided/required interfaces, ports, and connectors.
- [ ] Reuse ELK ports before adding custom connector geometry.
- [ ] Export component/composite XMI.
- [ ] Run module tests plus route-quality checks.

### Task 6: Deployment Diagrams

**Model:** `gpt-5.3-codex`

**Purpose:** Add runtime deployment notation that complements, but does not replace, ArchiMate technology views.

**First working output:** A `uml-deployment` view with nodes/devices/execution environments, artifacts, deployments, manifestations, and communication paths.

**Primary files:** Contracts/schema, `modules/uml`, generic graph projection/sizing, SVG renderer, UML/XMI exporter, README, agent guide.

- [ ] Add `uml-deployment` view kind.
- [ ] Add `Node`, `Device`, `ExecutionEnvironment`, `Artifact`, `Deployment`, `DeploymentSpecification`, `Manifestation`, and `CommunicationPath`.
- [ ] Render UML deployment notation with nested nodes/artifacts where groups are appropriate.
- [ ] Export deployment XMI.
- [ ] Run module tests, CLI fixture tests, and `git diff --check`.

### Task 7: Object, Package, Profile, And Richer Classification Support

**Model:** `gpt-5.5` for semantic design, then `gpt-5.3-codex` for implementation.

**Purpose:** Fill structural modeling gaps that support richer class/package/profile workflows.

**First working output:** Dedicated fixtures for object snapshots and package/profile relationships.

**Primary files:** Contracts/schema, `modules/uml`, SVG renderer, UML/XMI exporter, fixtures, docs.

- [ ] Add `InstanceSpecification`, `Slot`, `InstanceValue`, richer `PackageImport`, `PackageMerge`, `Profile`, `Stereotype`, and selected classification metaclasses only when a fixture needs them.
- [ ] Keep source JSON authoring ergonomic; do not mirror every UML metamodel association unless it affects validation, rendering, or export.
- [ ] Export deterministic XMI for supported structural additions.
- [ ] Run contract, UML, render, and export tests.

### Task 8: UMLDI Compatibility Export

**Model:** `gpt-5.3-codex`

**Purpose:** Add diagram interchange geometry only if downstream tool import needs more than model XMI.

**First working output:** Optional UMLDI output derived from layout result for at least one supported UML diagram family.

**Primary files:**

- Modify or create: `schemas/uml-xmi-export-policy.schema.json`
- Modify: `modules/plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java`
- Create or update: export fixtures and schema-cache validation tests.

- [ ] Decide whether UMLDI is emitted by `uml-xmi` under policy or a separate export plugin.
- [ ] Map layout result nodes/edges/groups to UMLDI shapes/connectors without making layout result the source of truth.
- [ ] Validate XML well-formedness, XMI ids, and available OMG XMI schema paths.
- [ ] Run export tests and distribution smoke if bundle behavior changes.

### Task 9: Docs, Distribution, Versioning, And Audits

**Model:** `gpt-5.4-mini` for docs/mechanical edits; `gpt-5.5` for final audit synthesis.

**Purpose:** Keep public product surfaces and release posture coherent after each UML expansion slice.

**Files:**

- Modify: `README.md`
- Modify: `docs/agent-usage.md`
- Modify: `fixtures/plugins/*.manifest.json` when version changes
- Modify: `fixtures/source/*` and `fixtures/export-policy/*` examples
- Modify: `tools/dist/src/test/java/dev/dediren/tools/dist/DistModuleTest.java` and/or `DistTool.java` when bundle smoke changes
- Modify: root `pom.xml` for version bumps when required

- [ ] Update README and agent usage docs in the same change as any user-facing command, workflow, plugin/runtime behavior, public artifact, or example change.
- [ ] For public UML surface additions, bump the product version according to AGENTS.md SemVer intent.
- [ ] After a version bump, update matching first-party plugin manifests, `required_plugins[].version` fixture entries, docs examples, distribution metadata, and version assertion tests.
- [ ] Run stale-version search over `pom.xml`, `README.md`, `docs/agent-usage.md`, `fixtures/plugins`, and `fixtures/source`.
- [ ] Run distribution checks when bundle content or smoke behavior changes:

```bash
./mvnw test
./mvnw -pl tools/dist -am verify -Pdist-smoke
git diff --check
```

## Cross-Slice Verification Lanes

Use the narrowest lane first, then broaden when the change crosses contracts, plugins, CLI behavior, public docs, or distribution:

```bash
./mvnw -pl modules/contracts -am test
./mvnw -pl modules/uml,modules/plugins/generic-graph -am test
./mvnw -pl modules/plugins/svg-render,apps/cli -am test
./mvnw -pl modules/plugins/uml-xmi-export,apps/cli -am test
./mvnw -pl modules/plugins/elk-layout -am test
./mvnw test
./mvnw -pl tools/dist -am verify -Pdist-smoke
git diff --check
```

## Audit Gates

When a slice is implemented, apply the repo audit gates from AGENTS.md:

- Contract/schema changes: focused schema and round-trip tests.
- Plugin runtime or process-boundary changes: plugin/runtime checks plus `souroldgeezer-audit:devsecops-audit` Quick.
- UML render/export changes: `souroldgeezer-architecture:architecture-design` Review for UML render/export evidence and `souroldgeezer-audit:test-quality-audit` Deep for fixtures/tests.
- Distribution/release changes: full tests, dist smoke, stale-version search, and release/version-policy checks.

Fix block findings. Fix warn/info findings or explicitly accept them in the handoff, then rerun affected checks.

## Deferred Or Out-Of-Scope Until Proven Needed

- Full UML 2.5.1 conformance claims. Dediren should claim supported slices only.
- Full UMLDI for every diagram family.
- Complete action metamodel support beyond what activity/sequence/state fixtures require.
- Magic path discovery, implicit plugin discovery from `PATH`, or renderer-specific source geometry.
- Replacing ArchiMate with UML deployment/component views. They answer related but different questions.

## Self-Review

- Spec coverage: the roadmap covers sequence, fragments, state machine, use case, component/composite structure, deployment, richer structure, UMLDI, docs/distribution/versioning, and audit gates.
- Placeholder scan: no unstated implementation promises are intentionally left. Each slice names first working output, primary files, model, and verification lane.
- Type consistency: view kinds use the existing `uml-*` naming pattern; metaclass names match the official UML 2.5.1 metamodel spelling; model assignments follow the rule section.

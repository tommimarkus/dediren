# God-file split (fine-grained) + launcher hoist — design

- **Date:** 2026-07-01
- **Status:** Approved (brainstorming)
- **Branch:** `refactor/godfile-split-launcher-hoist` (off `main` bd83e47)
- **Scenarios:** S3 (plugin `Main.java` god-files) and S4 (duplicated appassembler
  launcher config), from the repository simplification analysis.

## 1. Context & motivation

Two plugin entrypoints carry an entire backend in one file — the documented debt
in `docs/architecture-guidelines.md` §8 and §12:

- `plugins/render/Main.java` — 3,826 LOC, 147 methods.
- `plugins/uml-xmi-export/Main.java` — 1,734 LOC.

A single file carrying validation + mapping + serialization + per-notation
geometry mixes many reasons to change and raises cognitive load (Parnas 1972;
ISO/IEC 25010 maintainability). Separately, the appassembler launcher execution
is copy-pasted across all five plugin poms, differing only in `mainClass` and
program `id` (S4).

This design decomposes both entrypoints into focused, per-notation units and
centralizes the launcher execution, with **no behavior change**.

## 2. Goals / non-goals

**Goals**

- Fine-grained, per-notation decomposition of both god-files; each `Main` becomes
  a thin orchestrator.
- Byte-identical `render` SVG/PNG and `uml-xmi` XMI output (determinism is an
  owned quality force, guidelines §9).
- Hoist the shared plugin appassembler execution to the parent
  `<pluginManagement>`; plugin poms keep only their per-module `<programs>`.

**Non-goals (out of scope, YAGNI)**

- The §6 UML-vocabulary duplication debt in `RenderInputValidator` (relocate the
  class only; do not fix the debt here).
- Notation-core asymmetry (§6), any schema/contract/output change, and any CLI
  launcher change.

## 3. Guiding principle & safety net

Pure **move-and-delegate** refactor: relocate methods/types into focused classes
and delegate from the old call sites; change no logic.

- Entry classes `dev.dediren.plugins.render.Main` and `…umlxmi.Main` keep their
  names and `moduleName()` / `main()` / `executeForTesting(...)` signatures, so
  plugin manifests, capability probes, and appassembler `<mainClass>` are
  untouched.
- Safety net: the 3,618 LOC render test suite, 787 LOC uml-xmi suite, full
  `./mvnw test`, and `dist-tool -Pdist-smoke`. Golden SVG/XMI artifact hashes are
  captured before and after each commit and diffed to prove byte-stability.

## 4. Target structure — `render/`

```
dev.dediren.plugins.render
  Main                 thin: main/execute/capabilities/renderFromStdin/diagnostic
  PluginResult, SvgRasterizer            (unchanged)
  .svg
    SvgDocument        renderSvg assembly, interactive*/htmlWrap, group decorators
    EdgeRenderer       Segment/RoundedCorner/LineJump/EdgeLabel + edge geometry
    Svg                low-level primitives (moved from root)
  .style
    StyleResolver      style-resolution logic
    ResolvedStyle, ResolvedNodeStyle, ResolvedEdgeStyle, ResolvedGroupStyle
  .node
    NodeLabels         label layout/positioning + NodeLabelLines/Position, LabelBox
    NodeShapeSupport   shared shape helpers, SvgBounds
  .node.archimate
    ArchimateShapes    cut-corner + archimate node shapes
    ArchimateIcons     the ~1,600 LOC icon family
    ArchimateIconKind, TargetIconStyle
  .node.uml
    UmlShapes          umlNodeShape family (deployment/artifact/actor/usecase/…)
    UmlSequenceRenderer, UmlSequenceModel  (moved here)
    RenderInputValidator                   (relocated; vocab debt left as-is)
```

## 5. Target structure — `uml-xmi-export/`

```
dev.dediren.plugins.umlxmi
  Main                 thin: main/execute/capabilities/exportFromStdin
  .build   XmiBuilder            buildXmi node-type dispatch + shared XmiWrite helpers + IdentifierMap
  .policy  PolicyValidation
  .schema  SchemaValidation      xmllint/curl/env + *_VALIDATOR_UNAVAILABLE diagnostics
  .write.classifier    ClassifierWriter    (class/enum/attribute/operation)
  .write.component     ComponentWriter     (component/port/relationships)
  .write.deployment    DeploymentWriter    (deployment/manifestation/communication path)
  .write.usecase       UseCaseWriter       (usecase/extension point/include/extend)
  .write.statemachine  StateMachineWriter  (state machine/region/vertex/transition)
  .write.interaction   InteractionWriter   (sequence: interaction/fragments/operands/messages)
  .write.activity      ActivityWriter      (activity/node/edge)
```

The `buildXmi` dispatch maps each source node type (`Class`, `Interface`,
`Enumeration`, `Component`, `Node`/`Artifact`, `UseCase`/`Actor`, `StateMachine`,
`Interaction`, `Activity`, …) to the matching writer.

## 6. The visibility seam

The shared types (`ResolvedNodeStyle` et al., helper records, `Svg`) are today
`private` nested in `Main` — the exact reason the split was previously blocked.
Splitting into **subpackages** means package-private no longer reaches across
them, so these plugin-internal shared types become **`public` within the
plugin**.

This is acceptable and violates no architecture rule: plugins are leaf
executables whose only external contract is the JSON wire (§4); there are no
external Java consumers of these types; and ArchUnit governs cross-*module* edges
(no plugin → `core`), not intra-plugin visibility. A type used by a single
subpackage stays package-private there.

## 7. S4 — launcher hoist (plugins only)

The parent `<pluginManagement>` already pins the appassembler version and sets
`assembleDirectory` / `repositoryLayout=flat` / `repositoryName=lib`. Add one
shared `<execution>` there:

- `id=assemble`, `phase=package`, `goal=assemble`,
  `extraJvmArguments=${dediren.launcher.jvmArgs}`.

Each of the **five plugin poms** then declares only its `<programs>` block
(per-module `mainClass` + `id`), referencing execution `id=assemble` to inherit
phase/goal/JVM args — removing ~6 duplicated lines per pom and centralizing the
launcher JVM args and phase.

**CLI stays explicit.** `cli/pom.xml` adds `-Ddediren.version=${project.version}`
to its `extraJvmArguments`, and the app/plugin boundary is deliberate; it is not
folded into the plugin execution.

## 8. Commit sequence

Each commit keeps the suite green and output byte-identical.

1. `refactor(render): extract style seam + svg/edge packages`
2. `refactor(render): extract node shapes/labels/archimate-icons/uml packages`
3. `refactor(uml-xmi): split writers by diagram family`
4. `build: hoist plugin appassembler execution to parent pluginManagement` (S4)

## 9. Verification

Per repository verification lanes (all sandbox-disabled — Maven `@TempDir` needs
a writable `/tmp` and Maven writes to `~/.m2`):

- `./mvnw -pl plugins/render,cli -am test`
- `./mvnw -pl plugins/uml-xmi-export,cli -am test`
- `./mvnw test`
- `./mvnw -pl dist-tool -am verify -Pdist-smoke`
- `git diff --check`

Byte-stability: capture golden SVG/XMI artifact hashes before the first commit,
re-capture after each, and diff.

## 10. Risks & audit gates

- **Spotless collision (accepted):** based off `main`, so when the in-flight
  `feat/spotless-spotbugs-quality-gates` reformat lands, the new/moved files need
  a `google-java-format` pass. Mitigation: write new files in that style;
  document "run spotless after merge."
- **Diff size:** large; the per-subpackage commits are the reviewable unit.
- **Audit gates (CLAUDE.md `## Audit Gates`):** render + export changes →
  `test-quality-audit` **Deep** + `devsecops-audit` **Quick** before completion.

## 11. Files that move together

Internal-only refactor: no schema/contract/fixture/README change is expected
(output and wire contract are unchanged). S4 touches root `pom.xml` and the five
plugin poms; dist-smoke covers the launcher change.

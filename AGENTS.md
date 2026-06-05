# AGENTS.md

## Start Here

- Check `git status --short --branch` before editing.
- Use this file for repository work guidance. Use `docs/agent-usage.md` for
  downstream agents that author Dediren JSON or run packaged bundles.
- Before changing behavior, load only the relevant local context:
  - Product boundary question: `docs/superpowers/specs/2026-05-08-dediren-design.md`
  - Existing slice or planned task: matching file under `docs/superpowers/plans/`
  - User-facing command or workflow: `README.md`
  - Bundle-local agent authoring or runtime guidance: `docs/agent-usage.md`
- Treat `README.md` as the main user-facing document. Keep it current when
  commands, workflows, plugin/runtime behavior, public artifacts, or examples
  change.
- Treat `docs/agent-usage.md` as the shipped, token-efficient guide for agents
  using a Dediren archive. Keep it bundle-local and command-oriented.
- Treat plans as task guidance and implementation history. Live code and tests
  are the current truth when they disagree with a plan.
- Do not revive retired pre-Maven guidance from old plans. The live product is
  Java 21+ built with the checked-in Maven Wrapper.
- For ELK layout/routing changes, start from the ELK-first rule: try official
  ELK Layered options, graph structure, ports, hierarchy, and real-render
  evidence before adding custom placement or route geometry code.

## Skill Routing

- Use Superpowers skills for the work process: brainstorming, planning,
  TDD/debugging discipline, parallelization, review flow, and verification
  before completion. Use Sour Old Geezer skills for implementation-domain
  judgment: software design, test quality, DevSecOps, architecture notation,
  API/app/infra design, and repo-specific operational posture. They are
  complementary: let Superpowers shape how the work proceeds and Sour Old
  Geezer shape what "good" means inside the changed domain.
- Keep policy-skill initialization out of this file. Project-local workflow and
  release rules live in `## Git Hygiene`, `## Versioning`, and `README.md`.
- Use `souroldgeezer-design:software-design` for module boundaries,
  dependency direction, responsibility ownership, coupling, refactors,
  plugin/core split, Java code shape, or plan-to-code design drift.
- Use `souroldgeezer-audit:test-quality-audit` for test confidence.
- Use `souroldgeezer-audit:devsecops-audit` for CI/CD, dependency,
  artifact, release, or process-boundary posture.
- Use `souroldgeezer-architecture:architecture-design` for ArchiMate/OEF,
  UML, notation semantics, render/export evidence, and cross-notation review.

## Architecture Rules

- Keep `dediren` contract-first. Public JSON schemas, fixtures, command
  envelopes, diagnostics, plugin manifests, and runtime capability output are
  the stable product surface.
- Keep `cli` thin. CLI code should parse arguments, assemble requests, call
  `core`, and print envelopes.
- Keep orchestration, validation, plugin discovery, plugin execution, and
  backend-neutral quality checks in `core`.
- Keep shared protocol records and schema-version constants in
  `contracts`. Do not put orchestration or plugin implementation logic there.
- First-party plugins are executable process-boundary plugins. They may depend
  on `contracts`; they must not depend on `core`.
- Do not duplicate layout or routing features already provided by ELK. Express
  layout intent through ELK graph structure, ports, hierarchy, and options,
  then let ELK compute geometry and routes.
- SVG styling belongs only in SVG render policy/config and the SVG render
  plugin.
- ArchiMate/OEF semantics belong in the `archimate-oef` export plugin. UML/XMI
  semantics belong in the `uml-xmi` export plugin.

## Files That Move Together

- Public JSON shape changes: update `schemas/`, `contracts`, fixtures, plugin
  mapping code, and schema/round-trip tests together.
- Plugin protocol or runtime changes: update manifests, runtime capability
  handling, plugin envelope validation, CLI behavior, README notes, and
  compatibility tests together.
- User-facing command, workflow, install, artifact-location, or
  agent-authoring changes: update `README.md` and `docs/agent-usage.md` in the
  same change.
- Bundle-local agent guide changes that affect examples, redistributed files,
  command handoff, diagnostics, runtime probes, or plugin environment variables
  must stay consistent with `README.md` and distribution tests.
- ELK layout changes: update `plugins/elk-layout`, CLI/distribution
  smoke coverage, and README/agent runtime notes together.
- SVG render policy changes: update `schemas/svg-render-policy.schema.json`,
  `contracts`, render fixtures, `plugins/svg-render`, CLI render tests, and
  README examples together.
- OEF export changes: update export schemas, policy fixtures, source/layout
  fixtures, `plugins/archimate-oef-export`, CLI export tests, and README
  examples together.
- UML/XMI export changes: update export schemas, policy fixtures,
  source/layout fixtures, `plugins/uml-xmi-export`, CLI export tests, and
  README examples together.

## Versioning

- The product version source is root `pom.xml`.
- Prefer Maven-calculated version bumps for POM changes so the next numeric
  version is not hand-entered. For a patch bump, run:
  `./mvnw build-helper:parse-version versions:set -DnewVersion='${parsedVersion.majorVersion}.${parsedVersion.minorVersion}.${parsedVersion.nextIncrementalVersion}' -DprocessAllModules=true -DgenerateBackupPoms=false`.
  For minor use
  `-DnewVersion='${parsedVersion.majorVersion}.${parsedVersion.nextMinorVersion}.0'`;
  for major use
  `-DnewVersion='${parsedVersion.nextMajorVersion}.0.0'`.
- Version bumps live in the same commit as the content change that requires
  them.
- Every product/plugin version bump must also create the matching annotated git
  tag `v<version>` on the commit containing that bump before pushing.
- First-party plugin manifests under `fixtures/plugins/*.manifest.json`,
  source fixture `required_plugins[].version` entries, README bundle examples,
  `docs/agent-usage.md` examples, distribution metadata, and tests that assert
  version strings must match the product version.
- Known version assertion surfaces include
  `cli/src/test/java/dev/dediren/cli/MainTest.java`,
  `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`,
  `plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`,
  `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`,
  and `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`.
- `.github/workflows/release.yml` validates tag `v<version>` against root
  `pom.xml`; update it only if the product version source changes.
- Use SemVer intent while pre-1.0:
  - Major: backwards-incompatible public product or plugin contract changes.
  - Minor: additive compatible public surface changes or runtime migration
    cutovers.
  - Patch: compatible fixes, docs, tests, and internal refactors.
- Public schema ids such as `model.schema.v1` change only when the contract
  family intentionally changes.
- After every product/plugin version bump, run a stale-version search over
  `pom.xml`, `README.md`, `docs/agent-usage.md`, `fixtures/plugins`, and
  `fixtures/source`.

## Plugin Runtime Rules

- Plugins communicate with JSON stdin/stdout and command envelopes. Agents
  should be able to decide success or failure from stdout JSON alone.
- Preserve valid plugin error envelopes and return a non-zero CLI exit.
- Normalize runtime boundary failures into structured diagnostics, including
  missing executable, timeout, invalid JSON, schema mismatch, unsupported
  capability, id mismatch, and missing runtime dependency cases.
- Do not add implicit plugin discovery from `PATH`. Discovery is explicit:
  bundled first-party plugins, project plugin directories such as
  `.dediren/plugins`, then user-configured directories.
- Keep stderr for human debugging only.

## ELK Runtime

- `plugins/elk-layout` is the first-party official Java ELK plugin.
- It uses Eclipse ELK Java libraries and the Maven appassembler launcher.
- Java 21 or newer is required.

## Verification

Start with the narrow lane for the files touched, then run broader checks when
the change crosses contracts, plugins, CLI behavior, or public docs.

Docs-only guidance changes:

```bash
git diff --check
```

General Java changes:

```bash
./mvnw test
```

Contract/schema changes:

```bash
./mvnw -pl contracts -am test
```

Plugin runtime changes:

```bash
./mvnw -pl core,cli -am test
```

ELK changes:

```bash
./mvnw -pl plugins/elk-layout -am test
./mvnw -pl dist-tool -am verify -Pdist-smoke
```

SVG render changes:

```bash
./mvnw -pl plugins/svg-render,cli -am test
```

OEF export changes:

```bash
./mvnw -pl plugins/archimate-oef-export,cli -am test
```

UML/XMI export changes:

```bash
./mvnw -pl plugins/uml-xmi-export,cli -am test
```

Distribution/release changes:

```bash
./mvnw test
./mvnw -pl dist-tool -am verify -Pdist-smoke
git diff --check
```

## Audit Gates

When work is based on a plan in `docs/superpowers/plans`, run the audit
validation named by that plan before calling the work complete.

| Work area | `test-quality-audit` | `devsecops-audit` |
| --- | --- | --- |
| Vertical slice or broad pipeline | Deep: Java tests/fixtures | Quick: dependencies, process boundaries, artifacts, docs |
| Plugin runtime | Deep: runtime tests/fixtures | Quick: plugin process boundary and dependency posture |
| ELK runtime | Deep: bounded ELK test suite | Quick: implementation diff |
| SVG render | Quick: changed contract/plugin/CLI tests | Quick: schema, renderer, README, dependency posture |
| OEF or UML/XMI export | Deep: export tests/fixtures | Quick: export boundary |

Fix block findings. Fix warn/info findings or explicitly accept them in the
handoff, then rerun affected checks.

## Git Hygiene

- Start and finish by checking `git status --short --branch`.
- Treat pre-existing modified, staged, or untracked files as user work unless
  you created them in this turn.
- Do not revert, restage, format, or otherwise clean up unrelated user work.
- Before staging, review `git diff -- <path>` for each file you touched and
  stage only intentional changes.
- Do not use `git add -A` when unrelated files exist. Prefer explicit paths.
- Do not commit ignored/generated outputs by default. In this repo that
  includes `dist/`, `target/`, `.cache/`, downloaded `.mvn/wrapper/maven-wrapper.jar`,
  and generated `*.svg` files.
- If a task creates render/test artifacts, report their paths instead of
  staging them unless the user asked for tracked examples.
- Keep commits scoped to the requested change and mention any skipped
  verification or accepted audit findings in the handoff.

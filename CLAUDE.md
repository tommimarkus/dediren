# CLAUDE.md

This file is the canonical repository work guidance for AI coding agents. Other
agent tools should be pointed here from their own entrypoint files (for example,
`AGENTS.md`).

## Start Here

- Check `git status --short --branch` before editing.
- Use this file for repository work guidance. Use `docs/agent-usage.md` for
  downstream agents that author Dediren JSON or run packaged bundles.
- Before changing behavior, load only the relevant local context:
  - Product boundary question: `docs/superpowers/specs/2026-05-08-dediren-design.md`
    (as amended; for the MCP surface also
    `docs/superpowers/specs/2026-07-14-dediren-mcp-server-design.md`)
  - Existing slice or planned task: matching file under `docs/superpowers/plans/`
  - User-facing command or workflow: `README.md`
  - Bundle-local agent authoring or runtime guidance: `docs/agent-usage.md`
  - Trust-boundary or security-posture question: `docs/threat-model.md`
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
- This file initializes all five `souroldgeezer-policy` skills:
  `planning-policy` under `## Planning`, `scope-policy` under `## Scope`,
  `tdd-policy` under `## Test-First`, `release-policy` under `## Versioning`,
  and `git-workflow-policy` under `## Git Hygiene`. The user-facing release
  runbook lives in `README.md`. Keep other policy-skill initialization out of
  this file unless a section explicitly adopts it.
- `planning-policy` owns the *gate* (is there an approved approach before build
  work starts); Superpowers owns the *brainstorm* that fills it. They compose —
  the gate does not replace the brainstorming and planning skills above.
- The policies divide cleanly and do not substitute for one another:
  `planning-policy` bounds *when* work may start, `scope-policy` bounds *how
  wide* it may reach, `tdd-policy` bounds *what order* code and tests land in,
  and `software-design` still owns how minimal the solution inside that
  footprint is.
- Use `souroldgeezer-design:software-design` for module boundaries,
  dependency direction, responsibility ownership, coupling, refactors,
  plugin/core split, Java code shape, or plan-to-code design drift.
- Use `souroldgeezer-audit:test-quality-audit` for test confidence.
- Use `souroldgeezer-audit:devsecops-audit` for CI/CD, dependency,
  artifact, release, or process-boundary posture.
- Use `souroldgeezer-architecture:architecture-design` for ArchiMate/OEF,
  UML, notation semantics, render/export evidence, and cross-notation review.

## Planning

`planning-policy: brainstorm in the host plan lane, converge on an approach, and
obtain user approval before implementation; scope = new feature or build work;
exceptions = trivial edits, hotfixes, docs-only guidance changes, and work a
domain skill owns end to end (logged); delegation = subagents-by-default; opt out
per task by saying "skip planning" (logged)`

The line above initializes `souroldgeezer-policy:planning-policy` for this
repository. The rules below are its options and exceptions; they are standing
enforcement authority before new feature or build work begins.

- "Plan this" enters plan mode; it is not a request for prose about a plan.
  "Just do it" is an accepted opt-out phrase alongside "skip planning".
- A prose plan is not a dispatchable plan. Once the approach is agreed and the
  work has two or more delegated steps, groom it into the `planning-policy` plan
  contract — one leaf per step carrying `portable_tier`, `read_set`/`write_set`,
  `settled_decisions`, and one `acceptance_command` — and run its
  `validate_plan_contract.py` before `ExitPlanMode`. The host plan-mode workflow
  produces prose and will not do this on its own; invoke the skill.
- Docs-only guidance changes are exempt because they have their own one-line
  verification lane (`## Verification`), not because they are unimportant.
- Parallel agents must not run Maven — use an edit-only wave, then one central
  build (`## Verification`).
- Once the plan is approved, hand the domain judgment to the owning skill per
  `## Skill Routing` — planning-policy governs the gate, not the design.

## Scope

`scope-policy: balanced — change only within that level's footprint (the minimum
edit plus refactoring confined to the touched functions/files); record wider
findings instead of doing them. Escalate one rung only when the level is
insurmountable; escalation auto. Opt out per task by saying "open scope"
(logged)`

The line above initializes `souroldgeezer-policy:scope-policy` for this
repository. The rules below are its options and exceptions; they are standing
enforcement authority for how wide a change may reach.

- Out-of-level work is recorded, not done, and this repo already has the
  destinations: `docs/architecture-guidelines.md §12` for known debt, a plan
  under `docs/superpowers/plans/` for deferred remediation. Route it to
  `issue-ops`.
- Scope level says nothing about how minimal the solution is inside its
  footprint; that judgment stays with `souroldgeezer-design:software-design`.

## Architecture Rules

- The rules below are the quick reference. Full rationale, the allowed
  dependency-edge table, stability tiers, enforcement (ArchUnit/Enforcer), and
  the known-debt register live in `docs/architecture-guidelines.md`.
- Keep `dediren` contract-first. Public JSON schemas, fixtures, command
  envelopes, and diagnostics are the stable product surface.
- Keep `cli` thin. CLI code should parse arguments, assemble requests, call
  `core`, and print envelopes.
- Keep orchestration, validation, engine dispatch, and backend-neutral quality
  checks in `core`.
- Keep shared protocol records and schema-version constants in
  `contracts`. Do not put orchestration or plugin implementation logic there.
- First-party engines are library modules behind `engine-api`. Engines never
  depend on `core`; `core` never depends on engine implementations; only the
  `cli` `EngineWiring` class constructs them.
- `mcp-server` adapts the CLI's command surface to MCP stdio (`dediren mcp`) and
  owns protocol-specific media adaptation, including optional bounded SVG-to-PNG
  response attachments. Its allowed edges are `contracts`, `core`, `engine-api`
  only (ArchUnit-pinned); tool results keep the same envelopes the CLI prints as
  their primary text content. Core retains orchestration and does not negotiate
  MCP media or rasterize output.
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
- Breaking schema-version bumps: update the schema and the `ContractVersions`
  constant. For the hand-authorable input families registered in
  `KnownSchemaVersions` (source model, render policy, both export policies,
  layout request): also append the new version to the family (the old one
  becomes a prior version) and add a `### <from> → <to>` subsection under
  `## Migration` in `docs/agent-usage.md` — `MigrationRegistryTest` fails the
  build if a superseded registered version has no upgrade steps, or if a
  heading's `<to>` is not the version that superseded `<from>`. Generated
  engine-seam schemas (envelope, layout/render/export results, …) have no
  family: bump the constant, fixtures, and mapping code only. If the version
  *field* is renamed, add the old field name to the family's `versionFields`.
- Engine contract or runtime changes: update `engine-api`, `ir` (the
  SceneGraph/LaidOutScene seam types), `core` dispatch, `cli` `EngineWiring`,
  CLI behavior, README notes, and the engine envelope regression tests
  together.
- User-facing command, workflow, install, artifact-location, or
  agent-authoring changes: update `README.md` and `docs/agent-usage.md` in the
  same change.
- Bundle-local agent guide changes that affect examples, redistributed files,
  command handoff, diagnostics, runtime probes, or plugin environment variables
  must stay consistent with `README.md` and distribution tests.
  `AgentUsageDocConsistencyTest` (dist-tool) enforces that every `DEDIREN_*`
  token and CalVer version string in `docs/agent-usage.md` exists in source and
  matches the product version; keep it green when renaming codes or env vars.
  The same test also enforces the reverse direction: every production
  (`src/main`) `DEDIREN_*` token must be documented in the guide, individually
  or via a documented family prefix — a new diagnostic code needs a
  `## Repair Rules` entry or an internal-families extension.
- ELK layout changes: update `engines/elk-layout`, CLI/distribution
  smoke coverage, and README/agent runtime notes together.
- Anything that moves laid-out geometry ripples through four checked-in
  artifact stages, and they must be regenerated **in this order** — each stage
  is rendered from the previous one, so regenerating out of order bakes in
  stale input:
  1. `fixtures/layout-result/*.json` — `scripts/regen-layout-fixtures.sh`
     (`LayoutFixtureFreshnessTest` is the always-on gate).
  2. `engines/render/src/test/resources/golden/*.svg` —
     `scripts/regen-render-goldens.sh`. Rendered *from* stage 1
     (`RenderScenarios`), and `RenderGoldenTest` is the repo's only geometry
     oracle, so read the diff rather than re-baselining blind.
  3. `engines/render/src/paint-test/resources/raster-golden/` (PNGs +
     `manifest.json`) — the opt-in `-Prender-paint` lane with
     `-Ddediren.render.paint.regenerate-goldens=true`.
  4. `docs/architecture/dediren.dediren/generated/` — the four published
     self-model views and both exports, rebuilt with
     `"$BUNDLE/bin/dediren" build --package docs/architecture/dediren.dediren/package.json`
     from a bundle built from the working tree. **No test pins stage 4**, so it
     goes stale silently while the README hero and the Pages site keep serving
     it; check it explicitly rather than trusting a green build.
  Pinned geometry literals in tests move with stage 1 too — `SequenceSelfMessageHookTest`
  carries a `d=` path, and `LayoutQualityFixtureSweepTest` pins per-fixture
  crossing counts.
- Render policy changes: update `schemas/render-policy.schema.json`,
  `contracts`, render fixtures, `engines/render`, CLI render tests, and
  README examples together.
- OEF export changes: update export schemas, policy fixtures, source/layout
  fixtures, `engines/archimate-oef-export`, CLI export tests, and README
  examples together.
- UML/XMI export changes: update export schemas, policy fixtures,
  source/layout fixtures, `engines/uml-xmi-export`, CLI export tests, and
  README examples together.
- Engine runtime, schema-cache fetching, envelope validation, XML
  parser hardening, or release workflow changes: update
  `docs/threat-model.md` in the same change.
- MCP surface changes: update `mcp-server` (tools, `ToolSchemas`,
  `GuideCatalog` topics), the `## MCP Server` section of
  `docs/agent-usage.md`, the MCP rows of `docs/threat-model.md`, and the
  dist-tool packaged-MCP stdio smoke together.
- Package model changes: update `schemas/package.schema.json` +
  `schemas/package-build-result.schema.json`, the `contracts` `pkg/` records,
  `fixtures/package*`, `core` `pkg/` (`PackageBuildCommand` / `PackageValidator`)
  with the `DEDIREN_PACKAGE_*` codes, the `cli` `--package` wiring, the
  `mcp-server` `dediren_build` package argument, and
  `README.md`/`docs/agent-usage.md`/`docs/threat-model.md` together.
- Runtime dependencies or reflective surfaces on the cli classpath: the bundle
  ships one shrink-merged `lib/` jar, so a new ServiceLoader registration,
  annotation-driven library, or reflection-reached class needs a matching keep
  rule in `dist-tool` `bundle-shrink.pro` (and a licence attribution in
  `DistTool.THIRD_PARTY_ATTRIBUTIONS`) in the same change — `-Pdist-smoke` is
  the gate that catches a miss. Attribution labels must agree with each
  dependency's effective-pom licence: the cli `resolved-licence-report`
  execution (license-maven-plugin) normalizes and allowlist-gates resolved
  licences, and `DistTool` diffs the map against that report before writing
  notices. A new dependency may need its pom's licence spelling added to
  `licenseMerges` in `cli/pom.xml`; a licence outside the approved set is a
  deliberate decision, not a config tweak.

## Versioning

`release-policy: calver YYYY.0M.MICRO, annotated v<version> git tags, version
bump in its own commit; local bump and tag creation are authorized after
verification, but pushing the tag publishes and needs explicit per-release
authority`

The line above initializes `souroldgeezer-policy:release-policy` for this
repository. The rules below are its options and exceptions; they are standing
enforcement authority for matching version, tag, and release actions.

- The product version source is root `pom.xml`.
- Use CalVer with the shape `YYYY.0M.MICRO`: four-digit year, zero-padded
  month, and a within-month micro counter (for example `2026.06.0`, then
  `2026.06.1`).
  - First release in a new month: set the new year and zero-padded month with
    micro `0`:
    `./mvnw versions:set -DnewVersion='<YYYY>.<0M>.0' -DprocessAllModules=true -DgenerateBackupPoms=false`.
  - Additional release in the same month: increment the micro:
    `./mvnw versions:set -DnewVersion='<YYYY>.<0M>.<next-micro>' -DprocessAllModules=true -DgenerateBackupPoms=false`.
  - Set the version explicitly. Do not use `build-helper:parse-version` for the
    bump; it drops the zero-padded month.
- CalVer encodes the release date, not compatibility. Communicate
  backwards-incompatible product or plugin contract changes in the release
  notes and through schema-id changes, never through the version number.
- The version-bump commit contains only the version-source update and the
  synchronized version-assertion surfaces listed below.
- Sequence the bump after integration: once the motivating change is merged or
  rebased onto the integration branch (`main`), assess whether it is being
  released; if so, add the separate version-bump commit and its `v<version>` tag
  on `main` as a follow-on. A change is being released when release work is
  requested or it reaches an explicit release/distribution surface; a change
  that is not being released leaves the version untouched. The bump never rides
  inside the content commit or ahead of it, and one bump may cover several
  already-integrated changes.
- Every product/plugin version bump must create the matching annotated git tag
  `v<version>` on the version-bump commit before pushing.
- **Pushing the tag is publication** — it triggers
  `.github/workflows/release.yml`, which runs `gh release create` and un-drafts
  the GitHub Release. Mutating or deleting a tag, re-pointing a release, and
  yanking artifacts always need explicit authority and are never routine
  corrections.
- Source fixture `required_plugins[].version` entries, README bundle examples,
  `docs/agent-usage.md` examples, distribution metadata, and tests that assert
  version strings must match the product version.
- Known version assertion surfaces include
  `cli/src/test/java/dev/dediren/cli/MainTest.java`,
  `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`,
  and `engines/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`.
  `docs/features/README.md` and `docs/features/source-model.md` also cite the
  product version in examples; no test pins them, so sweep them by hand.
- `.github/workflows/release.yml` validates tag `v<version>` against root
  `pom.xml`; update it only if the product version source changes.
- Public schema ids such as `model.schema.v1` change only when the contract
  family intentionally changes. They are the durable compatibility signal,
  since CalVer does not encode compatibility.
- After every product/plugin version bump, run a stale-version search over
  `pom.xml`, `README.md`, `docs/agent-usage.md`, `docs/features`, and
  `fixtures/source`.

## Engine Runtime Rules

- Command envelopes on stdout remain the agent contract. Agents should be able
  to decide success or failure from stdout JSON alone. Under `dediren mcp`,
  stdout carries JSON-RPC frames instead and the same envelopes ride inside
  tool results; `StdoutIntegrity` keeps stray writes off the frame channel.
- Preserve valid engine error envelopes (an `EngineException` becomes the
  published error envelope with its exit code) and return a non-zero CLI exit.
- The registry resolves engine lookups in memory: an unknown engine id yields
  `DEDIREN_PLUGIN_UNKNOWN`; an id bound only under another capability yields
  `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`. An unexpected in-memory engine
  failure is `DEDIREN_ENGINE_FAILED`.
- An engine-side resource failure is reported by the engine that owns the
  resource, as a structured error envelope core preserves (for example an
  export engine emits `DEDIREN_OEF_SCHEMA_UNAVAILABLE` /
  `DEDIREN_XMI_SCHEMA_UNAVAILABLE` when its pinned schema cannot be fetched or
  loaded; both export lanes fetch with the Java HTTP client and validate in-JVM
  with no external fetcher or validator).
- There is no engine discovery of any kind: no `PATH` lookup, no manifest
  directories, no executable overrides. The bundled set is constructed
  explicitly in `cli` `EngineWiring`. The optional `mcp-server` `resvg`
  executable is protocol media adaptation, not an engine or render dependency.
- Keep stderr for human debugging only.
- Log through SLF4J at `debug`/`trace` only. `Logger.info`/`warn`/`error` are
  banned in first-party code and `ArchitectureRulesTest` fails the build on
  them: anything an agent must act on belongs in the envelope's `diagnostics[]`,
  so logging must never become the notification channel. Only `cli` binds an
  SLF4J provider at runtime; `test-support` binds one for
  tests, and `schema-cache` binds `slf4j-simple` at test scope only (it is the
  one logging module that does not depend on test-support). Library modules take
  `slf4j-api` alone; `LoggingProviderLocalityTest` (dist-tool) pins this.
  Logging is off unless `DEDIREN_LOG_LEVEL` says otherwise.

## ELK Runtime

- `engines/elk-layout` is the first-party Java ELK engine module.
- It uses Eclipse ELK Java libraries; the single `bin/dediren` (cli) launcher
  hosts it in-process. There is no per-plugin appassembler launcher.
- Java 21 or newer is required.

## Code Style

- Java is formatted by **google-java-format (GOOGLE style)** enforced via
  Spotless; SpotBugs (Max effort, Medium threshold, correctness only) runs
  alongside it. Both live in the opt-in `quality` profile.
- Run `./mvnw -Pquality spotless:apply` before committing Java changes; the gate
  (`./mvnw -Pquality verify`) fails on unformatted code or SpotBugs findings.
- SpotBugs suppressions live in `spotbugs-exclude.xml` and must be recorded as
  known debt in `docs/architecture-guidelines.md §12` — never suppress silently.
- Security scanning is CodeQL's job (CI), not SpotBugs; do not add FindSecBugs.

## Test-First

`tdd-policy: test-first — a failing test precedes implementation;
RED→GREEN→REFACTOR; shipped behavior stays covered by a test that fails on
regression. Scope: */src/main/java/**, schemas/**, fixtures/**,
dist-tool/src/main/**, bundle-shrink.pro, **/pom.xml. Exceptions: spikes,
prototypes, throwaway probes, generated code (logged). Enforcement: model. Opt
out per task by saying "skip TDD" (logged)`

The line above initializes `souroldgeezer-policy:tdd-policy` for this repository.
The rules below are its options and exceptions; they are standing enforcement
authority before implementation code changes.

- Run the narrow lane from `## Verification` and watch the new test fail before
  implementing. Watching it fail is the point — it proves the test can fail.
- Scope covers production Java in every module plus the contract surfaces that
  behave like production code here: a schema or fixture change lands behind a
  failing contract/round-trip test, and packaging changes (`dist-tool`,
  `bundle-shrink.pro`, poms) behind a failing check, because `-Pdist-smoke`
  otherwise catches a miss late. Test sources, generated output, and docs are
  outside scope.
- `test-after` is not a variant. Landing implementation before its test is an
  opt-out downgrade that relaxes the invariant, and is logged as such.
- No PreToolUse gate is installed for this, consistent with `lean CI,
  local-first validation`.
- Parallel agents must not run Maven (`## Planning`), so a delegated leaf writes
  its failing test and states the lane; the parent runs the central build that
  turns RED into GREEN.
- Test adequacy, brittleness, and suite strategy stay with
  `souroldgeezer-audit:test-quality-audit` (`## Audit Gates`) — tdd-policy owns
  ordering only.

## Verification

Start with the narrow lane for the files touched, then run broader checks when
the change crosses contracts, plugins, CLI behavior, or public docs.

Every lane below runs parallel by default: `.mvn/maven.config` pins `-T 0.4C`
(6 threads on a 16-core host, scaling down on smaller machines), and the root
pom's `dediren.test.jvmArgs` runs test JVMs C1-only. Together they halve
`./mvnw test` (29.1s -> 13.6s). That file takes no comments, so change the
thread count here and there together. This is build-level parallelism only — it
does not license parallel agents to run Maven (`## Planning`).

Docs-only guidance changes:

```bash
git diff --check
```

General Java changes:

```bash
./mvnw test
```

Coverage (local, opt-in JaCoCo gate — LINE + BRANCH, not run in CI):

```bash
./mvnw -Pcoverage verify
```

Code style + static analysis (local, opt-in gate — fails on violations; not
run in CI — validation is local-first):

```bash
./mvnw -Pquality verify          # full gate (format + SpotBugs + tests)
./mvnw -Pquality spotless:check  # formatting only
./mvnw -Pquality spotless:apply  # auto-fix formatting
```

Contract/schema changes:

```bash
./mvnw -pl contracts -am test
```

Engine dispatch changes (`core` dispatch, `cli` `EngineWiring`):

```bash
./mvnw -pl core,cli -am test
```

ELK changes:

```bash
./mvnw -pl engines/elk-layout -am test
./mvnw -pl dist-tool -am verify -Pdist-smoke
```

SVG render changes:

```bash
./mvnw -pl engines/render,cli -am test
```

ASCII render changes:

```bash
./mvnw -pl engines/ascii-render,cli -am test
```

Chromium-backed decorated-paint and raster verification (opt-in):

```bash
./scripts/test-render-paint.sh
./scripts/test-render-paint.sh -Dtest='SvgPaintAudit*Test'
./scripts/test-render-paint.sh -Dtest='RasterDiffTest,RasterGoldenTest'
```

The wrapper activates `-Prender-paint`; the opt-in profile does not change
emitted SVG, the default build, or the shipped platform-neutral Java runtime.
Every other rule in this lane — pinned versions, direct Maven invocation, launch
hardening, fonts, goldens, and what may be committed — lives in
[`docs/features/svg-render.md` § Chromium Render-Paint Verification](docs/features/svg-render.md#chromium-render-paint-verification).
Change them there, not here.

OEF export changes:

```bash
./mvnw -pl engines/archimate-oef-export,cli -am test
```

UML/XMI export changes:

```bash
./mvnw -pl engines/uml-xmi-export,cli -am test
```

draw.io lane changes:

```bash
./mvnw -pl engines/drawio,cli -am test
```

Real-standards conformance (opt-in; first run fetches the pinned real
schemas, so it needs network or a warm `~/.cache/dediren-real-schemas`):

```bash
./mvnw -pl engines/archimate-oef-export,engines/uml-xmi-export -am test \
  -Dtest=RealSchemaConformanceTest -Ddediren.real-schemas=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

ArchiMate relationship-legality conformance (opt-in; needs a local oracle file
of allowed `Source|Relationship|Target` lines derived from the copyrighted
ArchiMate 3.2 Appendix B.5 tables — never committed). Asserts the legality model
never rejects a spec-legal endpoint and still catches the bulk of illegal ones:

```bash
./mvnw -pl archimate -am test \
  -Dtest=ArchimateRelationshipLegalityConformanceTest#modelMatchesRelationshipTableOracle \
  -Ddediren.archimate-oracle=/path/to/allowed-triples.txt \
  -Dsurefire.failIfNoSpecifiedTests=false
```

MCP server changes:

```bash
./mvnw -pl mcp-server,cli -am test
./mvnw -pl dist-tool -am verify -Pdist-smoke
```

Distribution/release changes:

```bash
./mvnw test
./mvnw -pl dist-tool -am test
./mvnw -pl dist-tool -am verify -Pdist-smoke
git diff --check
```

## Audit Gates

When work is based on a plan in `docs/superpowers/plans`, run the audit
validation named by that plan before calling the work complete.

| Work area | `test-quality-audit` | `devsecops-audit` |
| --- | --- | --- |
| Vertical slice or broad pipeline | Deep: Java tests/fixtures | Quick: dependencies, process boundaries, artifacts, docs |
| Engine runtime (dispatch, `EngineWiring`) | Deep: runtime tests/fixtures | Quick: engine dependency boundary and posture |
| ELK runtime | Deep: bounded ELK test suite | Quick: implementation diff |
| SVG render | Quick: changed contract/plugin/CLI tests | Quick: schema, renderer, README, dependency posture |
| OEF or UML/XMI export | Deep: export tests/fixtures | Quick: export boundary |

Fix block findings. Fix warn/info findings or explicitly accept them in the
handoff, then rerun affected checks.

## Git Hygiene

`git-workflow-policy: direct main allowed (branches optional), integration at
author discretion, approved multi-step plans run in a worktree removed at a
verified closeout, clean worktree, explicit-path staging, stop before destructive
git actions`

The line above initializes `souroldgeezer-policy:git-workflow-policy` for this
repository, overriding the skill's `no direct main` / `feature branches`
defaults. The rules below are its options and exceptions and are standing
enforcement authority for matching branch, staging, commit, merge, and
integration actions.

- Use a feature/fix branch when isolation helps, and do not mix unrelated tasks
  in one commit or branch.
- Land a branch into `main` with a local `--no-ff` merge or a GitHub Pull
  Request, chosen per change. Delegate PR lifecycle writes to `pr-ops`.
- Approved multi-step plans (`## Planning`) are implemented in a worktree under
  repository `.worktrees/<name>`, not the primary checkout. Closeout is part of
  the same task, not a follow-up: verify the merge (`git branch --merged`), then
  `git worktree remove <path>`, `git worktree prune`, and `git branch -d` —
  never `-D`. Do not leave integrated worktrees or branches behind. Subagents do
  not reliably inherit a worktree cwd, so a delegated fleet either receives
  absolute paths or runs in the primary checkout.
- Version-edit placement: version files may change on any branch, including
  `main`. `## Versioning` (`release-policy`) governs the version source, the
  separate-commit rule, tagging, and release execution.
- Stop before destructive git actions: history rewrites, force-push, branch
  deletion, and tags. Tags and releases are governed by `release-policy`.
- Start and finish by checking `git status --short --branch`.
- Treat pre-existing modified, staged, or untracked files as user work unless
  you created them in this turn.
- Do not revert, restage, format, or otherwise clean up unrelated user work.
- Treat checked-in third-party, upstream-generated, vendored, wrapper,
  generated, and legal/canonical files as protected surfaces. Do not make
  incidental edits to their line endings, executable bits, whitespace,
  formatting, comments, checksums, URLs, versions, or generated content.
- In this repo, protected examples include `mvnw`, `mvnw.cmd`,
  `.mvn/wrapper/maven-wrapper.properties`, ignored Maven Wrapper artifacts,
  generated `THIRD-PARTY-NOTICES.md`, bundled dependency/SBOM/checksum outputs
  under `target/` or `dist/`, and canonical legal text such as `LICENSE`.
- Edit protected surfaces only when the user request or an approved plan
  specifically targets that surface. If tooling rewrites one accidentally,
  inspect the diff and restore only the accidental change before continuing.
- Before staging, review `git diff -- <path>` for each file you touched and
  stage only intentional changes. Never `git add -A` when unrelated files exist.
- Do not commit ignored/generated outputs by default. In this repo that
  includes `dist/`, `target/`, `.cache/`, downloaded `.mvn/wrapper/maven-wrapper.jar`,
  and generated `*.svg` files.
- If a task creates render/test artifacts, report their paths instead of
  staging them unless the user asked for tracked examples.
- Keep commits scoped to the requested change and mention any skipped
  verification or accepted audit findings in the handoff.

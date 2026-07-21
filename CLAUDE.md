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
- This file initializes `souroldgeezer-policy:git-workflow-policy` under
  `## Git Hygiene` and `souroldgeezer-policy:release-policy` under
  `## Versioning`. The user-facing release runbook lives in `README.md`. Keep
  other policy-skill initialization out of this file unless a section explicitly
  adopts it.
- Use `souroldgeezer-design:software-design` for module boundaries,
  dependency direction, responsibility ownership, coupling, refactors,
  plugin/core split, Java code shape, or plan-to-code design drift.
- Use `souroldgeezer-audit:test-quality-audit` for test confidence.
- Use `souroldgeezer-audit:devsecops-audit` for CI/CD, dependency,
  artifact, release, or process-boundary posture.
- Use `souroldgeezer-architecture:architecture-design` for ArchiMate/OEF,
  UML, notation semantics, render/export evidence, and cross-notation review.

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
- `mcp-server` adapts the CLI's command surface to MCP stdio (`dediren mcp`).
  Its allowed edges are `contracts`, `core`, `engine-api` only (ArchUnit-pinned);
  tool results carry the same envelopes the CLI prints.
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
bump in its own commit`

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
- A version bump lives in its own commit, separate from the content change that
  motivates it. The version-bump commit contains only the version-source update
  and the synchronized version-assertion surfaces listed below.
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
- A missing runtime dependency is reported by the engine that owns the
  dependency, as a structured error envelope core preserves (for example the
  XMI export engine emits `DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE` when
  `xmllint` is absent; the OEF lane validates in-JVM and needs no external
  validator).
- There is no engine discovery of any kind: no `PATH` lookup, no manifest
  directories, no executable overrides. The bundled set is constructed
  explicitly in `cli` `EngineWiring`.
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

Coverage (local, opt-in JaCoCo gate — LINE + BRANCH, not run in CI):

```bash
./mvnw -Pcoverage verify
```

Code style + static analysis (local, opt-in gate — fails on violations; CI runs
the same checks report-only):

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

OEF export changes:

```bash
./mvnw -pl engines/archimate-oef-export,cli -am test
```

UML/XMI export changes:

```bash
./mvnw -pl engines/uml-xmi-export,cli -am test
```

MCP server changes:

```bash
./mvnw -pl mcp-server,cli -am test
./mvnw -pl dist-tool -am verify -Pdist-smoke
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
| Engine runtime (dispatch, `EngineWiring`) | Deep: runtime tests/fixtures | Quick: engine dependency boundary and posture |
| ELK runtime | Deep: bounded ELK test suite | Quick: implementation diff |
| SVG render | Quick: changed contract/plugin/CLI tests | Quick: schema, renderer, README, dependency posture |
| OEF or UML/XMI export | Deep: export tests/fixtures | Quick: export boundary |

Fix block findings. Fix warn/info findings or explicitly accept them in the
handoff, then rerun affected checks.

## Git Hygiene

`git-workflow-policy: direct main allowed (branches optional), integration at
author discretion, clean worktree, explicit-path staging`

The line above initializes `souroldgeezer-policy:git-workflow-policy` for this
repository, overriding the skill's `no direct main` / `feature branches`
defaults. The rules below are its options and exceptions and are standing
enforcement authority for matching branch, staging, commit, merge, and
integration actions.

- Branches are optional. Direct commits to `main` are allowed for any change;
  use a feature/fix branch when isolation helps. Do not mix unrelated tasks in
  one commit or branch.
- Integration is at author discretion: land a branch into `main` with a local
  `--no-ff` merge or a GitHub Pull Request, chosen per change. Delegate PR
  lifecycle writes to `pr-ops`.
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
  stage only intentional changes.
- Do not use `git add -A` when unrelated files exist. Prefer explicit paths.
- Do not commit ignored/generated outputs by default. In this repo that
  includes `dist/`, `target/`, `.cache/`, downloaded `.mvn/wrapper/maven-wrapper.jar`,
  and generated `*.svg` files.
- If a task creates render/test artifacts, report their paths instead of
  staging them unless the user asked for tracked examples.
- Keep commits scoped to the requested change and mention any skipped
  verification or accepted audit findings in the handoff.

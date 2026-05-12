# AGENTS.md

## Start Here

- Check `git status --short --branch` before editing. This repo is often worked
  on directly on `main` and may be ahead of `origin/main`.
- Before changing behavior, load only the relevant local context:
  - Product boundary question: `docs/superpowers/specs/2026-05-08-dediren-design.md`
  - Existing slice or planned task: matching file under `docs/superpowers/plans/`
  - User-facing command or workflow: `README.md`
- Treat `README.md` as the main user-facing document. Keep it current when
  commands, workflows, plugin/runtime behavior, public artifacts, or examples
  change; if a user-visible change intentionally does not need a README update,
  say why in the handoff.
- Treat plans as task guidance and implementation history. Live code and tests
  are the current truth when they disagree with a plan.

## Skill Routing

- Use `souroldgeezer-design:software-design` for build/review/lookup questions
  about module boundaries, dependency direction, responsibility ownership,
  coupling, refactors, plugin/core split, Rust code shape, Java helper shape,
  or plan-to-code design drift.
- In `software-design` review mode for this repo, start from contract
  ownership and dependency direction before implementation details.
- Delegate test confidence to `souroldgeezer-audit:test-quality-audit`,
  security/process posture to `souroldgeezer-audit:devsecops-audit`, and OEF or
  ArchiMate model semantics to `souroldgeezer-architecture:architecture-design`.

## Architecture Rules

- Keep `dediren` contract-first. Public JSON schemas, fixtures, command
  envelopes, diagnostics, plugin manifests, and runtime capability output are
  the stable product surface.
- Keep `dediren-cli` thin. CLI code should parse arguments, assemble requests,
  call `dediren-core`, and print envelopes.
- Keep orchestration, validation, plugin discovery, plugin execution, and
  backend-neutral quality checks in `dediren-core`.
- Keep shared protocol structs and schema-version constants in
  `dediren-contracts`. Do not put orchestration or plugin implementation logic
  there.
- First-party plugins are executable process-boundary plugins. They may depend
  on `dediren-contracts`; they must not depend on `dediren-core`.
- Do not duplicate layout or routing features already provided by ELK. Express
  layout intent through ELK graph structure, ports, hierarchy, and options, then
  let ELK compute geometry and routes. Keep `dediren` code focused on contract
  mapping, diagnostics, normalization, and regression coverage around ELK
  behavior.

## Contract Boundaries

- Source graph JSON is semantic and plugin-typed. Do not add authored absolute
  geometry or presentation styling to source data.
- Layout requests express layout intent, not concrete coordinates.
- Layout results may contain generated geometry, routes, metrics, warnings, and
  provenance.
- SVG styling belongs only in `svg-render` policy/config and the SVG render
  plugin.
- ArchiMate/OEF semantics belong in the `archimate-oef` export plugin. OEF view
  geometry must come from generated layout results.
- The core must not own domain vocabularies, semantic type validation, view
  semantics, ELK-specific interpretation, SVG rendering, or OEF serialization.

## Files That Move Together

- Public JSON shape changes: update `schemas/`, `dediren-contracts`, fixtures,
  Rust/Java/plugin mapping code, and schema/round-trip tests together.
- Plugin protocol or runtime changes: update manifests, runtime capability
  handling, plugin envelope validation, CLI behavior, README notes, and
  compatibility tests together.
- User-facing command, workflow, install, or artifact-location changes: update
  `README.md` in the same change so the public instructions match the live
  behavior.
- ELK layout changes: keep the Rust `elk-layout` adapter and Java helper
  contract aligned. The helper lives under
  `crates/dediren-plugin-elk-layout/java` and is invoked through
  `DEDIREN_ELK_COMMAND`.
- SVG render policy changes: update `schemas/svg-render-policy.schema.json`,
  `dediren-contracts`, render fixtures, `dediren-plugin-svg-render`, CLI render
  tests, and README examples together.
- OEF export changes: update export schemas, policy fixtures, source/layout
  fixtures, `dediren-plugin-archimate-oef-export`, CLI export tests, and README
  examples together.

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

## ELK Helper

- Fixture mode with `DEDIREN_ELK_RESULT_FIXTURE` takes precedence over
  `DEDIREN_ELK_COMMAND` and is for deterministic Rust adapter tests.
- Real ELK work requires the SDKMAN/Gradle Java helper:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
```

- Run ignored real-helper tests only after the helper is built.

## Verification

Start with the narrow lane for the files touched, then run broader checks when
the change crosses contracts, plugins, CLI behavior, or public docs.

Docs-only guidance changes:

```bash
git diff --check
```

General Rust changes:

```bash
cargo fmt --all -- --check
cargo test --workspace --locked
```

Contract/schema changes:

```bash
cargo test -p dediren-contracts --test schema_contracts
cargo test -p dediren-contracts --test contract_roundtrip
```

Plugin runtime changes:

```bash
cargo test -p dediren-core --test plugin_runtime
cargo test -p dediren --test plugin_compat
```

ELK helper changes:

```bash
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
cargo test -p dediren-plugin-elk-layout --test elk_layout_plugin -- --ignored
cargo test -p dediren --test cli_layout -- --ignored
```

SVG render changes:

```bash
cargo test -p dediren-plugin-svg-render --test svg_render_plugin
cargo test -p dediren --test cli_render
```

OEF export changes:

```bash
cargo test -p dediren-plugin-archimate-oef-export --test oef_export_plugin
cargo test -p dediren --test cli_export
```

## Audit Gates

When work is based on a plan in `docs/superpowers/plans`, run the audit
validation named by that plan before calling the work complete.

| Work area | `test-quality-audit` | `devsecops-audit` |
| --- | --- | --- |
| Vertical slice or broad pipeline | Deep: Rust tests/fixtures | Quick: dependencies, process boundaries, artifacts, docs |
| Plugin runtime | Deep: runtime tests/fixtures | Quick: plugin process boundary and dependency posture |
| ELK runtime | Deep: bounded ELK test suite | Quick: implementation diff |
| SVG render | Quick: changed contract/plugin/CLI tests | Quick: schema, renderer, README, dependency posture |
| OEF export | Deep: export tests/fixtures | Quick: export boundary |

Fix block findings. Fix warn/info findings or explicitly accept them in the
handoff, then rerun affected checks.

## Git Hygiene

- Start and finish by checking `git status --short --branch`.
- Treat any pre-existing modified, staged, or untracked files as user work
  unless you created them in this turn.
- Do not revert, restage, format, or otherwise clean up unrelated user work.
- Before staging, review `git diff -- <path>` for each file you touched and
  stage only intentional changes.
- Do not use `git add -A` when unrelated files exist. Prefer explicit paths.
- Do not use `git add -f` unless the user explicitly names the ignored path to
  track.
- Do not commit ignored/generated outputs by default. In this repo that includes
  `target/`, `.cache/gradle/`,
  `crates/dediren-plugin-elk-layout/java/.gradle/`,
  `crates/dediren-plugin-elk-layout/java/build/`, and generated `*.svg` files
  outside `.github/`.
- If a task creates render/test artifacts, report their paths instead of
  staging them unless the user asked for tracked examples.
- Keep commits scoped to the requested change and mention any skipped
  verification or accepted audit findings in the handoff.
- Do not run destructive cleanup such as `git reset --hard`, branch deletion, or
  worktree removal unless the user explicitly asks for that action.

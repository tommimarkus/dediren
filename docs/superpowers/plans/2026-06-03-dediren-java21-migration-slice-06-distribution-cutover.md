# Dediren Java 21 Migration Slice 06: Distribution Cutover And Rust Retirement

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` for this slice. Do not delete Rust until all Java parity gates are green.

**Goal:** Replace Rust/Cargo release tooling with Java/Gradle distribution tooling, update public docs, bump/tag the product version, and delete Rust-only source cleanly.

**Architecture:** Gradle owns build, test, distribution, third-party notices, bundle metadata, and release workflow inputs. Runtime artifacts preserve current command names, plugin manifests, schemas, fixtures, docs, license, and Java ELK runtime behavior.

**Tech Stack:** Java 21+, Gradle application/distribution tasks, Gradle License Report, GitHub Actions, tar, shell smoke scripts, Java CLI launchers.

---

## Files

- Create: `tools/dist/src/main/java/dev/dediren/tools/dist/*.java` or Gradle task classes under `buildSrc` if selected in Slice 00.
- Modify: `tools/dist/build.gradle.kts`
- Modify: `.github/workflows/release.yml`
- Modify: `README.md`
- Modify: `docs/agent-usage.md`
- Modify: `fixtures/plugins/*.manifest.json`
- Modify: `fixtures/source/*.json`
- Modify: `schemas/bundle.schema.json` only if bundle metadata shape intentionally changes.
- Read parity source: `xtask/src/main.rs`
- Read parity tests: `xtask/tests/dist.rs`
- Read parity source: `.github/workflows/release.yml`
- Read parity docs: `README.md`
- Read parity docs: `docs/agent-usage.md`
- Delete after green Java parity: `Cargo.toml`
- Delete after green Java parity: `Cargo.lock`
- Delete after green Java parity: `crates/`
- Delete after green Java parity: `xtask/`
- Delete after green Java parity: Rust-specific CI, docs, and install instructions.
- Delete after green Java parity: SDKMAN project setup and SDKMAN-dependent helper instructions.

## Tasks

- [x] **Task 0: Review migration ledgers before deleting Rust**
  - Model: `gpt-5`
  - Read the Rust-to-Java migration ledger and Rust test suite migration ledger in the roadmap.
  - For each ledger row, record the Java target module, the Java test class or Gradle task proving it, and the commit where it landed.
  - Stop before deletion if any Rust crate, Rust test lane, fixture family, docs surface, release workflow surface, or `xtask` behavior lacks Java proof.
  - Expected: a complete ledger review is included in the slice handoff.

### 2026-06-03 Migration Ledger Review

Reviewed the roadmap Rust-to-Java migration ledger and Rust test suite
migration ledger before deleting the tracked Rust workspace. No target-state
Rust crate, test lane, fixture family, docs surface, release workflow surface,
or distribution behavior remains without Java proof.

| Ledger surface | Java proof | Commit evidence |
| --- | --- | --- |
| Gradle foundation and Java modules | root Gradle build, wrapper, Java module graph, contract/runtime app layout | `1b46607` |
| `crates/dediren-contracts` and schema tests | `modules/contracts`, `ContractVersionsTest`, schema contract and round-trip Java tests | `a133bc2` |
| `crates/dediren-core`, `crates/dediren-cli`, plugin runtime testbed | `modules/core`, `apps/cli`, `testbeds/plugin-runtime`, command/runtime/CLI tests | `2bd6a0f` |
| ArchiMate, UML, generic-graph, schema-cache | `modules/archimate`, `modules/uml`, `modules/plugins/generic-graph`, `modules/schema-cache` tests | `787f13e`, `5664866`, `57dfbe5` |
| ArchiMate OEF export | `modules/plugins/archimate-oef-export` XML, policy, schema-cache, and endpoint tests | `4e09dae` |
| UML XMI export | `modules/plugins/uml-xmi-export` XML, policy, scoped export, ID, and endpoint tests | `5664866` |
| SVG render plugin | `modules/plugins/svg-render` render contract, semantic, route, label, bounds, and XML tests | `66810c7`, `1803f6a`, `fb95f39`, `4c6c059`, `57dfbe5` |
| Rust ELK adapter and helper | `modules/plugins/elk-layout` official Eclipse ELK Java plugin, route-quality tests, CLI/bundle smoke | `57e818c`, current slice |
| `xtask` distribution tooling | `tools/dist` Gradle tasks for notices, build, smoke, metadata, stale artifact pruning, and clean-env smoke | current slice |
| Rust/Cargo/SKD helper docs and CI | Java 21 README, agent docs, Gradle release workflow, active stale-surface search | current slice |
| Fixture families | Existing fixtures retained and exercised by Java contract, CLI, plugin, render/export, ELK, and dist tests | slices 01-06 |

Retired Rust-only test hooks: the old ELK result fixture environment and
external helper command are not carried into the target state because the
first-party ELK plugin now executes official Java ELK directly. Deterministic
coverage is provided by Java unit tests plus the distribution smoke against
the packaged Java launcher.

- [x] **Task 1: Add Gradle dist build**
  - Model: `gpt-5-codex`
  - Build release binaries or launcher scripts for `dediren` and each first-party plugin.
  - Assemble bundle layout with `bin/`, `plugins/`, `schemas/`, `fixtures/`, `docs/`, `LICENSE`, `THIRD-PARTY-NOTICES.md`, and `bundle.json`.
  - Preserve bundle archive naming unless version strategy explicitly changes it.
  - Run: `./gradlew :tools:dist:distBuild`
  - Expected: a complete bundle directory and archive are created under `dist/`.

- [x] **Task 2: Add Gradle dist smoke**
  - Model: `gpt-5-codex`
  - Smoke an unpacked bundle with clean env.
  - Verify `dediren --help`, `dediren --version`, every first-party plugin capability probe, project/layout/validate-layout/render pipeline, ArchiMate OEF export, UML XMI export, clean-env behavior, and fixture-mode repair behavior.
  - Ensure Java 21+ is checked before official Java ELK runtime tests.
  - Run: `./gradlew :tools:dist:distSmoke`
  - Expected: pass.

- [x] **Task 3: Add third-party notices**
  - Model: `gpt-5-codex`
  - Replace Rust `cargo-about` notices with Gradle-based dependency notices for the Java product.
  - Include official Eclipse ELK dependencies and all Java runtime dependencies redistributed or compiled into the bundle.
  - Preserve root bundle `THIRD-PARTY-NOTICES.md`.
  - Run: `./gradlew :tools:dist:thirdPartyNotices`
  - Expected: generated notices are included in the bundle root.

### 2026-06-03 Gradle Distribution Build Checkpoint

Completed the first Java distribution build path:

- `tools/dist` now exposes Gradle Wrapper tasks for `distBuild`, `distSmoke`,
  and `thirdPartyNotices`.
- `distBuild` assembles Java application launchers for the CLI and all
  first-party plugins into `bin/`, copies runtime jars into `lib/`, copies
  first-party manifests into `plugins/`, excludes source fixture manifests from
  `fixtures/`, and writes `bundle.json`.
- `thirdPartyNotices` writes a bundle-root `THIRD-PARTY-NOTICES.md` covering
  redistributed Java runtime jars, including official Eclipse ELK, EMF,
  Xtext, Jackson, Picocli, and validator dependencies.
- The Java runtime now loads bundled manifests from `<bundle-root>/plugins`
  and resolves plain manifest executable names from `<bundle-root>/bin` for
  that installed-bundle case.
- The CLI now flushes plugin stdout before exiting so launcher-script based
  commands produce machine-readable envelopes when captured by agents or smoke
  tests.

Verification:

```bash
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :tools:dist:compileJava
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :tools:dist:thirdPartyNotices
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :tools:dist:distBuild
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :modules:core:test --tests dev.dediren.core.plugins.PluginRuntimeTest.bundledRegistryLoadsDistributionPluginManifests --tests dev.dediren.core.plugins.PluginRuntimeTest.bundledRegistryResolvesDistributionExecutablesFromBundleBin
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :tools:dist:distSmoke
```

Result: the bundle archive was created under `dist/`, bundled plugin discovery
and executable resolution tests passed, and the expanded smoke passed through
`dediren --help`, `dediren --version`, every first-party plugin capability
probe, generic graph projection with dirty plugin override env scrubbed,
official Java ELK layout, validate-layout, SVG render, ArchiMate OEF export,
and UML XMI export from the unpacked archive. The export checks use local
minimal OEF/XMI schemas so the smoke remains deterministic and offline.

- [x] **Task 4: Update GitHub release workflow**
  - Model: `gpt-5-codex`
  - Replace Cargo/xtask release steps with Java/Gradle steps.
  - Keep target matrix only if native target-specific artifacts still exist; otherwise simplify to Java runtime artifacts with clear platform support.
  - Keep release permissions scoped to the publish job.
  - Preserve checksum generation and release asset upload behavior.
  - Run: `./gradlew test :tools:dist:distBuild :tools:dist:distSmoke`
  - Expected: local release-equivalent gate passes.

- [x] **Task 5: Update public docs**
  - Model: `gpt-5-mini`
  - Update `README.md` and `docs/agent-usage.md` for Java 21 install/build/smoke commands, Gradle usage, official Java ELK runtime, plugin capability probes, bundle contents, and repair diagnostics.
  - Remove Cargo install instructions, Rust workspace prerequisites, `cargo-about`, `xtask`, `crates/`, SDKMAN, `.sdkmanrc`, `DEDIREN_ELK_BUILD_USE_SDKMAN`, and `elkrs` target-state references.
  - Explain any fixture-only repair loop that remains.
  - Run: `rg -n "cargo|Cargo|Rust|crates/|xtask|cargo-about|SDKMAN|sdkman|\\.sdkmanrc|DEDIREN_ELK_BUILD_USE_SDKMAN|elkrs|rust-elkrs" README.md docs/agent-usage.md`
  - Expected: only historical plan references or explicitly accepted migration notes remain.

- [x] **Task 6: Bump version and update release surfaces**
  - Model: `gpt-5-codex`
  - Use `souroldgeezer-policy:release-policy` preflight before editing version sources.
  - Bump the product/plugin version according to the roadmap strategy.
  - Update plugin manifests, fixture `required_plugins[].version`, README bundle examples, `docs/agent-usage.md` examples, `bundle.json` tests, and release workflow/version assertions.
  - Run stale-version search over the previous version, the new version, and any skipped patch versions.
  - Expected: old version appears only in historical docs or accepted migration notes.

- [x] **Task 7: Delete Rust-only source**
  - Model: `gpt-5-codex`
  - Delete Cargo workspace files and Rust crates only after Java gates pass and Task 0 proves every Rust surface has migrated or been explicitly retired.
  - Keep checked-in schemas, fixtures, docs, license, and GitHub release workflow.
  - Run: `rg -n "Cargo.toml|Cargo.lock|crates/|cargo test|cargo xtask|cargo install|rust-version|SDKMAN|sdkman|\\.sdkmanrc|DEDIREN_ELK_BUILD_USE_SDKMAN|elkrs|rust-elkrs" .`
  - Expected: no target-state Rust, SDKMAN, or `elkrs` references remain.

- [x] **Task 8: Run final audits**
  - Model: `gpt-5`
  - Use `souroldgeezer-design:software-design` Review for final dependency direction and Rust retirement completeness.
  - Use `souroldgeezer-audit:test-quality-audit` Deep across Java unit/integration/smoke tests.
  - Use `souroldgeezer-audit:devsecops-audit` Quick for release workflow, dependency/license evidence, Gradle Wrapper, and artifacts.
  - Use `souroldgeezer-architecture:architecture-design` Review for final ArchiMate/OEF/UML render/export evidence if those outputs changed.
  - Fix block findings. Document accepted warn/info findings in the final handoff.

### 2026-06-03 Distribution Cutover Final Audit And Verification

Audit notes:

- `souroldgeezer-design:software-design` Review: no block findings. Gradle
  modules preserve the contract-first split: contracts are data/schema records,
  core owns orchestration/runtime, CLI is a thin adapter, and first-party
  plugins stay process-boundary applications without depending on core.
- `souroldgeezer-audit:test-quality-audit` Deep: no block findings. Coverage
  now includes Java unit/plugin tests, CLI process-boundary integration tests,
  official Java ELK behavior, full distribution build, and packaged runtime
  smoke across project/layout/validate-layout/render/OEF/XMI workflows.
  Accepted limit: no mutation evidence is available.
- `souroldgeezer-audit:devsecops-audit` Quick with GitHub Actions extension:
  no block findings. The release workflow uses SHA-pinned third-party actions,
  explicit top-level/job permissions, checkout credential persistence disabled,
  and job timeouts. Accepted warn/info: the Java bundle has jar-based
  third-party notices and GitHub artifact attestations, but no full SBOM or
  license-report generator yet.
- `souroldgeezer-architecture:architecture-design` Review: no block findings.
  Generated ArchiMate/OEF and UML/XMI outputs remain driven by source/layout
  contracts and verified through plugin, CLI, and bundle smoke tests.

Verification:

```bash
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew test :tools:dist:distBuild :tools:dist:distSmoke
git diff --check
rg -n "cargo|Cargo|Rust|crates/|xtask|cargo-about|SDKMAN|sdkman|\\.sdkmanrc|DEDIREN_ELK_BUILD_USE_SDKMAN|DEDIREN_ELK_COMMAND|DEDIREN_ELK_RESULT_FIXTURE|elkrs|rust-elkrs|target/debug" README.md docs/agent-usage.md AGENTS.md .github/workflows build.gradle.kts settings.gradle.kts gradle.properties fixtures modules apps tools
rg -n "0\\.16\\.0|0\\.17\\.0" build.gradle.kts README.md docs/agent-usage.md fixtures/plugins fixtures/source apps modules tools .github/workflows
```

Result: Gradle and whitespace checks passed. The stale Rust/Cargo/SDKMAN/ELK
env search returned no active target-state matches. The version search found
only intentional `0.18.0` surfaces and no `0.16.0` or `0.17.0` matches in
active release surfaces. `0.17.0` was skipped for this migration because an
existing annotated tag already points at the retired ELK replacement commit.

- [x] **Task 9: Verify, commit, and tag**
  - Model: `gpt-5-mini`
  - Run: `./gradlew test :tools:dist:distBuild :tools:dist:distSmoke`
  - Run: `git diff --check`
  - Run final stale search for previous version, new version, `Cargo`, `Rust`, SDKMAN, and `elkrs` terms across `README.md`, `docs/agent-usage.md`, `fixtures/plugins`, `fixtures/source`, `.github/workflows`, and Gradle files.
  - Commit message: `feat: migrate dediren to java 21`
  - Create annotated tag: `git tag -a v<new-version> -m "Release <new-version>"`

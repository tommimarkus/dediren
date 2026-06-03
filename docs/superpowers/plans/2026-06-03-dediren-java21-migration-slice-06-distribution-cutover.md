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

- [ ] **Task 0: Review migration ledgers before deleting Rust**
  - Model: `gpt-5`
  - Read the Rust-to-Java migration ledger and Rust test suite migration ledger in the roadmap.
  - For each ledger row, record the Java target module, the Java test class or Gradle task proving it, and the commit where it landed.
  - Stop before deletion if any Rust crate, Rust test lane, fixture family, docs surface, release workflow surface, or `xtask` behavior lacks Java proof.
  - Expected: a complete ledger review is included in the slice handoff.

- [ ] **Task 1: Add Gradle dist build**
  - Model: `gpt-5-codex`
  - Build release binaries or launcher scripts for `dediren` and each first-party plugin.
  - Assemble bundle layout with `bin/`, `plugins/`, `schemas/`, `fixtures/`, `docs/`, `LICENSE`, `THIRD-PARTY-NOTICES.md`, and `bundle.json`.
  - Preserve bundle archive naming unless version strategy explicitly changes it.
  - Run: `./gradlew :tools:dist:distBuild`
  - Expected: a complete bundle directory and archive are created under `dist/`.

- [ ] **Task 2: Add Gradle dist smoke**
  - Model: `gpt-5-codex`
  - Smoke an unpacked bundle with clean env.
  - Verify `dediren --help`, `dediren --version`, every first-party plugin capability probe, project/layout/validate-layout/render pipeline, ArchiMate OEF export, UML XMI export, clean-env behavior, and fixture-mode repair behavior.
  - Ensure Java 21+ is checked before official Java ELK runtime tests.
  - Run: `./gradlew :tools:dist:distSmoke`
  - Expected: pass.

- [ ] **Task 3: Add third-party notices**
  - Model: `gpt-5-codex`
  - Replace Rust `cargo-about` notices with Gradle-based dependency notices for the Java product.
  - Include official Eclipse ELK dependencies and all Java runtime dependencies redistributed or compiled into the bundle.
  - Preserve root bundle `THIRD-PARTY-NOTICES.md`.
  - Run: `./gradlew :tools:dist:thirdPartyNotices`
  - Expected: generated notices are included in the bundle root.

- [ ] **Task 4: Update GitHub release workflow**
  - Model: `gpt-5-codex`
  - Replace Cargo/xtask release steps with Java/Gradle steps.
  - Keep target matrix only if native target-specific artifacts still exist; otherwise simplify to Java runtime artifacts with clear platform support.
  - Keep release permissions scoped to the publish job.
  - Preserve checksum generation and release asset upload behavior.
  - Run: `./gradlew test :tools:dist:distBuild :tools:dist:distSmoke`
  - Expected: local release-equivalent gate passes.

- [ ] **Task 5: Update public docs**
  - Model: `gpt-5-mini`
  - Update `README.md` and `docs/agent-usage.md` for Java 21 install/build/smoke commands, Gradle usage, official Java ELK runtime, plugin capability probes, bundle contents, and repair diagnostics.
  - Remove Cargo install instructions, Rust workspace prerequisites, `cargo-about`, `xtask`, `crates/`, SDKMAN, `.sdkmanrc`, `DEDIREN_ELK_BUILD_USE_SDKMAN`, and `elkrs` target-state references.
  - Explain any fixture-only repair loop that remains.
  - Run: `rg -n "cargo|Cargo|Rust|crates/|xtask|cargo-about|SDKMAN|sdkman|\\.sdkmanrc|DEDIREN_ELK_BUILD_USE_SDKMAN|elkrs|rust-elkrs" README.md docs/agent-usage.md`
  - Expected: only historical plan references or explicitly accepted migration notes remain.

- [ ] **Task 6: Bump version and update release surfaces**
  - Model: `gpt-5-codex`
  - Use `souroldgeezer-policy:release-policy` preflight before editing version sources.
  - Bump the product/plugin version according to the roadmap strategy.
  - Update plugin manifests, fixture `required_plugins[].version`, README bundle examples, `docs/agent-usage.md` examples, `bundle.json` tests, and release workflow/version assertions.
  - Run stale-version search over the previous version, the new version, and any skipped patch versions.
  - Expected: old version appears only in historical docs or accepted migration notes.

- [ ] **Task 7: Delete Rust-only source**
  - Model: `gpt-5-codex`
  - Delete Cargo workspace files and Rust crates only after Java gates pass and Task 0 proves every Rust surface has migrated or been explicitly retired.
  - Keep checked-in schemas, fixtures, docs, license, and GitHub release workflow.
  - Run: `rg -n "Cargo.toml|Cargo.lock|crates/|cargo test|cargo xtask|cargo install|rust-version|SDKMAN|sdkman|\\.sdkmanrc|DEDIREN_ELK_BUILD_USE_SDKMAN|elkrs|rust-elkrs" .`
  - Expected: no target-state Rust, SDKMAN, or `elkrs` references remain.

- [ ] **Task 8: Run final audits**
  - Model: `gpt-5`
  - Use `souroldgeezer-design:software-design` Review for final dependency direction and Rust retirement completeness.
  - Use `souroldgeezer-audit:test-quality-audit` Deep across Java unit/integration/smoke tests.
  - Use `souroldgeezer-audit:devsecops-audit` Quick for release workflow, dependency/license evidence, Gradle Wrapper, and artifacts.
  - Use `souroldgeezer-architecture:architecture-design` Review for final ArchiMate/OEF/UML render/export evidence if those outputs changed.
  - Fix block findings. Document accepted warn/info findings in the final handoff.

- [ ] **Task 9: Verify, commit, and tag**
  - Model: `gpt-5-mini`
  - Run: `./gradlew test :tools:dist:distBuild :tools:dist:distSmoke`
  - Run: `git diff --check`
  - Run final stale search for previous version, new version, `Cargo`, `Rust`, SDKMAN, and `elkrs` terms across `README.md`, `docs/agent-usage.md`, `fixtures/plugins`, `fixtures/source`, `.github/workflows`, and Gradle files.
  - Commit message: `feat: migrate dediren to java 21`
  - Create annotated tag: `git tag -a v<new-version> -m "Release <new-version>"`

# Dediren Java 21 Migration Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` for implementation slices and `superpowers:verification-before-completion` before any completion claim. Steps in low-level slice plans use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move Dediren from the Rust Cargo workspace to a clean Java 21+ Gradle product while preserving the public JSON contracts, CLI workflow, process-boundary plugin model, and official Java ELK layout behavior.

**Architecture:** Keep the checked-in JSON schemas, fixtures, command envelopes, diagnostics, plugin manifests, and runtime capability output as the stable product surface. Rebuild the implementation as Java modules that mirror the current Rust responsibilities: CLI stays thin, core owns orchestration and plugin execution, contracts own shared protocol records, and first-party plugins remain executable process-boundary plugins. Use official Eclipse ELK Java dependencies for layout; do not use `elkrs` in the target state.

**Tech Stack:** Java 21+, Gradle Kotlin DSL, Gradle Wrapper, JUnit 5, AssertJ, Jackson, NetworkNT JSON Schema Validator, XMLUnit, Picocli, Eclipse ELK Java artifacts, Gradle License Report, GitHub Actions.

---

## Current Evidence

- The planning worktree was created from `main` at `4771725`, version `0.16.0`.
- The planning base is still a Rust workspace with a Rust `dediren-plugin-elk-layout` adapter and a Java helper under `crates/dediren-plugin-elk-layout/java`.
- A sibling worktree `elkrs-layout-replacement` exists at `v0.17.0` and replaces the Java helper with `elkrs`.
- Therefore execution must start by selecting the real implementation base:
  - If starting from `main` / `v0.16.0`, the ELK work is mostly preservation and migration into the Java product.
  - If starting from `elkrs-layout-replacement` / `v0.17.0`, first revert ELK from `elkrs` to official Java ELK before or during the Java migration.

## Planning Assumptions

- Public schema family ids such as `model.schema.v1`, `layout-request.schema.v1`, and `plugin.protocol.v1` stay unchanged unless a slice explicitly proposes a contract break.
- CLI command names and plugin ids stay stable: `dediren`, `generic-graph`, `elk-layout`, `svg-render`, `archimate-oef`, and `uml-xmi`.
- The target repository has no required Rust toolchain, Cargo workspace, `crates/`, `xtask`, `Cargo.toml`, or `Cargo.lock`.
- The target distribution still ships native launcher scripts or platform launchers named like the current commands.
- Java 21 is the minimum runtime and compilation target. The build may run on newer JDKs, but bytecode is released for 21.
- Gradle is the build and distribution owner because the repo already has a Gradle-based Java ELK helper.
- The target project uses the checked-in Gradle Wrapper. It does not support SDKMAN project setup, `.sdkmanrc`, or SDKMAN-dependent build/runtime scripts.
- First-party plugins stay separate executables even though they are implemented in Java.

## Model Roster

| Work type | Assigned model | Why |
| --- | --- | --- |
| Architecture decisions, slice review, migration-risk triage | `gpt-5` | Best fit for cross-boundary reasoning and tradeoffs. |
| Java implementation, TDD loops, build wiring, CLI/runtime porting | `gpt-5-codex` | Best fit for code edits in a repo with verification gates. |
| Mechanical stale-version searches, docs alignment, manifest surface checks | `gpt-5-mini` | Cheap fit for deterministic search and checklist work. |
| Test-quality and DevSecOps audit synthesis | `gpt-5` | Needs judgment across test confidence, release posture, and process boundaries. |

If the local agent runner does not expose these exact model names, substitute the closest available coding model for `gpt-5-codex`, the strongest reasoning model for `gpt-5`, and the smallest reliable model for `gpt-5-mini`.

## Sour Old Geezer Skill Routing

- Use `souroldgeezer-design:software-design` for every slice that changes module boundaries, dependency direction, Gradle module layout, package ownership, or plugin/core split.
- Use `souroldgeezer-architecture:architecture-design` when validating ArchiMate/OEF/UML behavior, rendered evidence, and cross-notation handoff facts.
- Use `souroldgeezer-audit:test-quality-audit` in Deep mode for contract, CLI, plugin, render/export, and migration parity suites before Rust removal.
- Use `souroldgeezer-audit:devsecops-audit` in Quick mode for Gradle Wrapper, release workflow, dependency/license reporting, plugin process execution, and distribution artifacts.
- Use `souroldgeezer-policy:release-policy` lookup/preflight before the version bump and annotated tag slice.

## High-Level Stages

1. **Freeze behavior and branch base.** Capture baseline command behavior, fixture outputs, test gates, branch delta from `v0.16.0` and `v0.17.0`, and accepted contract surfaces.
2. **Create Java build foundation.** Add Gradle wrapper, root build conventions, module layout, dependency catalog, formatting/static checks, and smoke modules without deleting Rust.
3. **Port contracts first.** Recreate protocol records, schema constants, command envelopes, diagnostics, schema validation helpers, and round-trip tests in Java.
4. **Port core orchestration.** Recreate plugin discovery, manifest loading, runtime probing, command execution, env allowlists, timeout handling, and structured diagnostics.
5. **Port the CLI.** Recreate user-facing commands with Picocli, keep CLI thin, and drive all behavior through the Java core.
6. **Port semantic plugins.** Move generic graph projection, semantic validation, fragment assembly behavior, ArchiMate rules, UML rules, and schema cache behavior.
7. **Restore and migrate official Java ELK.** Use official Eclipse ELK Java artifacts directly in the Java `elk-layout` plugin. Remove `elkrs` dependencies and unsupported `elkrs` behavior notes from the target state.
8. **Port render/export plugins.** Move SVG rendering, ArchiMate OEF export, UML XMI export, and XML/schema validation with parity fixtures.
9. **Replace release and distribution tooling.** Move `xtask` behavior into Gradle tasks, update GitHub Actions, release artifacts, third-party notices, bundle metadata, README, and `docs/agent-usage.md`.
10. **Retire Rust cleanly.** Delete Rust crates and Cargo metadata only after Java passes the compatibility gates. Run stale Rust/elkrs/version searches and tag the release commit.

## Rust-To-Java Migration Ledger

Every Rust package and Rust-only support surface must have one of these outcomes before Rust deletion: **ported**, **replaced by a Java test/support equivalent**, or **retired because the migrated Java product no longer needs that implementation detail**. A slice handoff must not mark a Rust surface deleted until the matching Java proof is green.

| Rust surface | Java target | Slice | Required proof before Rust deletion |
| --- | --- | --- | --- |
| `crates/dediren-contracts` | `modules/contracts` | 01 | Java schema, fixture, and round-trip tests cover every Rust public protocol type and schema constant. |
| `crates/dediren-core/src/io.rs` | `modules/core/.../io` | 02 | Java IO tests cover file/stdin input, missing files, invalid JSON, and envelope data extraction. |
| `crates/dediren-core/src/plugins.rs` | `modules/core/.../plugins` | 02 | Java plugin runtime tests cover manifests, explicit discovery, env allowlists, process execution, timeout, schema mismatch, and structured diagnostics. |
| `crates/dediren-core/src/commands.rs` | `modules/core/.../commands` | 02 | Java command tests cover validate, project, layout, render, export, and command-specific env candidate ownership. |
| `crates/dediren-core/src/source.rs` | `modules/core/.../source` and `modules/plugins/generic-graph` | 03 | Java source assembly tests cover fragments, duplicate ids, plugin data merges, scalar conflicts, and stdin-without-base rejection. |
| `crates/dediren-core/src/validate.rs` | `modules/core/.../validation` and semantic plugins | 02, 03 | Java validation tests preserve schema-first validation and plugin-owned semantic validation dispatch. |
| `crates/dediren-core/src/quality.rs` | `modules/core/.../quality` | 02 | Java layout quality tests cover every Rust layout-quality case. |
| `crates/dediren-cli` | `apps/cli` | 02 | Java CLI tests cover every Rust CLI test file: help, validate, project, layout, render, export, pipeline, plugin compatibility, and real ELK render lanes. |
| `crates/dediren-archimate` | `modules/archimate` | 03 | Java ArchiMate tests cover the curated relationship oracle, supported vocabulary, endpoint rules, and connector rules. |
| `crates/dediren-uml` | `modules/uml` | 03 | Java UML tests cover supported class/data/activity views, vocabulary, multiplicity, endpoint rules, and view restrictions. |
| `crates/dediren-plugin-generic-graph` | `modules/plugins/generic-graph` | 03 | Java plugin executable tests cover capabilities, semantic validation, projection to layout request, and projection to render metadata. |
| `crates/dediren-plugin-schema-cache` | `modules/schema-cache` | 03 | Java tests cover cache dir, direct schema path, download/cache behavior, and structured unavailable-schema diagnostics. |
| `crates/dediren-plugin-elk-layout/src/main.rs` | `modules/plugins/elk-layout` | 05 | Java plugin executable tests cover fixture mode, explicit command compatibility if retained, bundled runtime behavior, capabilities, diagnostics, and real official Java ELK output. |
| `crates/dediren-plugin-elk-layout/java` | `modules/plugins/elk-layout` | 05 | Existing Java helper behavior is migrated into the first-party Java plugin or explicitly replaced by equivalent official Java ELK code. |
| `elkrs` branch backend, if implementation starts from `v0.17.0` | no target dependency | 05 | `elkrs-core`, `elkrs-layered`, `rust-elkrs`, and unsupported `elkrs` limitation docs are absent from target-state code and docs. |
| `crates/dediren-plugin-svg-render` | `modules/plugins/svg-render` | 04 | Java SVG tests cover render contracts, ArchiMate nodes/groups/relationships, UML nodes/activity/relationships, edge labels, viewbox, line jumps, and shared endpoint behavior. |
| `crates/dediren-plugin-archimate-oef-export` | `modules/plugins/archimate-oef-export` | 04 | Java OEF tests cover policy validation, generated XML, geometry rounding, semantic grouping, junctions, endpoint validation, and schema validation. |
| `crates/dediren-plugin-uml-xmi-export` | `modules/plugins/uml-xmi-export` | 04 | Java XMI tests cover policy validation, scoped export, generated XMI, id deduplication, activity output, endpoint validation, and XML id validation. |
| `crates/dediren-plugin-runtime-testbed` | `testbeds/plugin-runtime` | 02 | Java testbed executable or equivalent test fixture covers every plugin-runtime behavior currently supplied by the Rust testbed. |
| `xtask` | `tools/dist` | 06 | Gradle dist tasks cover build, smoke, bundle metadata, stale artifact pruning, third-party notices, target policy, and clean-environment smoke. |
| `Cargo.toml`, `Cargo.lock`, Rust toolchain expectations | none in target | 06 | Final stale search confirms no target-state Cargo/Rust build dependency remains. |
| `.sdkmanrc` and SDKMAN helper scripts | none in target | 05, 06 | Final stale search confirms no target-state SDKMAN setup or SDKMAN runtime requirement remains. |
| `.github/workflows/release.yml` Cargo/xtask steps | Gradle release workflow | 06 | Workflow tests and local release-equivalent Gradle gate prove Java release behavior. |
| `schemas/` | `schemas/` plus Java schema tests | 01, 06 | Schemas remain checked-in canonical contracts and compile under Java schema tests. |
| `fixtures/` | `fixtures/` plus Java fixture tests | 01-06 | Fixtures remain shared parity evidence and are exercised by Java contract, CLI, plugin, render/export, ELK, and dist tests. |
| `README.md` and `docs/agent-usage.md` Rust instructions | Java 21/Gradle instructions | 06 | Stale-doc search confirms no target-state Rust, Cargo, `xtask`, or `elkrs` guidance remains. |

## Rust Test Suite Migration Ledger

The Java migration is not complete until each Rust test lane has a named Java successor or is explicitly retired with a reason.

| Rust test lane | Java successor |
| --- | --- |
| `crates/dediren-contracts/tests/contract_roundtrip.rs` | `modules/contracts` round-trip tests for every public contract family. |
| `crates/dediren-contracts/tests/schema_contracts.rs` | Java schema contract suite plus README/docs/fixture version-surface checks. |
| `crates/dediren-core/tests/plugin_runtime.rs` | `modules/core` plugin runtime tests and `testbeds/plugin-runtime`. |
| `crates/dediren-core/tests/commands.rs` | `modules/core` command orchestration tests. |
| `crates/dediren-core/tests/layout_quality.rs` | `modules/core` layout quality tests. |
| `crates/dediren-cli/tests/cli_help.rs` | `apps/cli` help/version tests. |
| `crates/dediren-cli/tests/cli_validate.rs` | `apps/cli` validate tests. |
| `crates/dediren-cli/tests/cli_project.rs` | `apps/cli` project tests. |
| `crates/dediren-cli/tests/cli_layout.rs` | `apps/cli` layout and validate-layout tests, including fixture and real Java ELK lanes. |
| `crates/dediren-cli/tests/cli_render.rs` | `apps/cli` render integration tests. |
| `crates/dediren-cli/tests/cli_export.rs` | `apps/cli` export integration tests. |
| `crates/dediren-cli/tests/cli_pipeline.rs` | Java end-to-end pipeline tests for project, layout, validate-layout, render, and export. |
| `crates/dediren-cli/tests/plugin_compat.rs` | Java CLI/plugin compatibility tests for all first-party plugin executables. |
| `crates/dediren-cli/tests/real_elk_render.rs` | Java real official ELK render evidence tests. |
| `crates/dediren-archimate/tests/relationship_rules.rs` | `modules/archimate` relationship rules tests. |
| `crates/dediren-uml/tests/uml_validation.rs` | `modules/uml` validation tests. |
| `crates/dediren-plugin-generic-graph/tests/generic_graph_plugin.rs` | `modules/plugins/generic-graph` executable plugin tests. |
| `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs` | `modules/plugins/elk-layout` executable plugin tests. |
| `crates/dediren-plugin-svg-render/tests/svg_render_plugin*.rs` | `modules/plugins/svg-render` SVG rendering test suite. |
| `crates/dediren-plugin-archimate-oef-export/tests/oef_export_plugin.rs` | `modules/plugins/archimate-oef-export` OEF export test suite. |
| `crates/dediren-plugin-uml-xmi-export/tests/uml_xmi_export_plugin.rs` | `modules/plugins/uml-xmi-export` XMI export test suite. |
| `xtask/tests/dist.rs` | `tools/dist` Gradle dist build, smoke, notice, bundle metadata, and release-surface tests. |

## Version And Release Strategy

- Planning-only docs do not require a version bump.
- Implementation requires a version bump in the same commit that first changes shipped behavior or public distribution contents.
- Default bump: minor if the JSON contracts, CLI commands, plugin ids, and capabilities stay compatible.
- Escalate to a major SemVer-intent bump only if a slice removes or renames a stable command, plugin id, schema family, envelope field, diagnostic code, or stable artifact path.
- The final content commit must create the matching annotated `v<version>` tag before push, per `AGENTS.md`.

## Plan Files

- High level: this roadmap.
- Mid level: `docs/superpowers/plans/2026-06-03-dediren-java21-migration-tracks.md`.
- Low-level slices:
  - `docs/superpowers/plans/2026-06-03-dediren-java21-migration-slice-00-foundation.md`
  - `docs/superpowers/plans/2026-06-03-dediren-java21-migration-slice-01-contracts.md`
  - `docs/superpowers/plans/2026-06-03-dediren-java21-migration-slice-02-core-cli-runtime.md`
  - `docs/superpowers/plans/2026-06-03-dediren-java21-migration-slice-03-semantic-plugins.md`
  - `docs/superpowers/plans/2026-06-03-dediren-java21-migration-slice-04-render-export.md`
  - `docs/superpowers/plans/2026-06-03-dediren-java21-migration-slice-05-official-java-elk.md`
  - `docs/superpowers/plans/2026-06-03-dediren-java21-migration-slice-06-distribution-cutover.md`

## Go/No-Go Gates

- Do not delete any Rust package until the Rust-to-Java migration ledger and Rust test suite migration ledger have both been reviewed line by line in the current implementation branch.
- Do not delete Rust until Java contract, CLI, plugin runtime, render/export, official Java ELK, distribution, and docs gates are green.
- Do not accept fixture-only layout proof for the final ELK slice. Real official Java ELK render evidence is required.
- Do not collapse first-party plugins into in-process modules; preserving process-boundary plugins is part of the product contract.
- Do not add implicit plugin discovery from `PATH`.
- Do not add authored geometry or styling to source graph JSON.
- Do not ship with stale `elkrs`, Cargo, Rust install, or old version examples in user-facing docs.
- Do not ship with SDKMAN as a project prerequisite or runtime path.

## Software-Design Record

- Mode: Build planning.
- Extensions: Java and Rust.
- Reference path: `souroldgeezer-design` software reference sections 2-7 and 9.
- Evidence layers: static repo files, git branch history, runtime baseline tests, and memory-derived prior-session notes.
- Assimilation: current Rust crate boundaries are useful as responsibility boundaries but not as target folder names; Java modules should preserve ownership while removing Cargo-specific coupling.
- Delegations: architecture-design for ArchiMate/OEF/UML evidence, test-quality-audit for proof strength, devsecops-audit for release and process-boundary posture, release-policy for version/tag preflight.
- Limit: This is a planning artifact. It does not claim the Java migration is implemented.

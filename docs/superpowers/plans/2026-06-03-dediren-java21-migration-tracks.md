# Dediren Java 21 Migration Track Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to execute one low-level slice at a time. Refresh local context at the start of each slice instead of carrying stale assumptions.

**Goal:** Define the mid-level tracks that convert the Rust workspace into a Java 21+ product without breaking Dediren's contract-first boundaries.

**Architecture:** Treat the existing Rust crates as a responsibility map, not as a Java package blueprint. The Java target uses explicit modules for contracts, core, CLI, semantic vocabularies, plugins, and distribution tooling, with first-party plugins executed as separate processes.

**Tech Stack:** Java 21+, Gradle multi-project build, Jackson, NetworkNT JSON Schema Validator, Picocli, JUnit 5, AssertJ, XMLUnit, official Eclipse ELK Java libraries.

---

## Target Source Layout

```text
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
gradle/wrapper/
apps/cli/
modules/contracts/
modules/core/
modules/archimate/
modules/uml/
modules/schema-cache/
modules/plugins/generic-graph/
modules/plugins/elk-layout/
modules/plugins/svg-render/
modules/plugins/archimate-oef-export/
modules/plugins/uml-xmi-export/
test-support/
testbeds/plugin-runtime/
tools/dist/
schemas/
fixtures/
docs/
```

## Boundary Decisions

- `apps/cli` parses arguments, reads files/stdin, calls `modules/core`, and prints command envelopes.
- `modules/core` owns orchestration, plugin discovery, manifest validation, runtime capability probing, command execution, env allowlists, timeouts, schema checks, and backend-neutral layout quality checks.
- `modules/contracts` owns Java records for public protocol data and schema-version constants only.
- `modules/archimate` and `modules/uml` own domain vocabularies and semantic validation helpers used by plugins.
- `modules/schema-cache` owns schema cache/download mechanics as a first-party plugin implementation.
- Each `modules/plugins/*` project builds an executable plugin and depends on `modules/contracts`; plugins do not depend on `modules/core`.
- `test-support` owns shared fixture loaders, JSON assertions, process helpers, and schema-test helpers that replace Rust `tests/common` modules.
- `testbeds/plugin-runtime` owns the migrated plugin-runtime executable used to prove process-boundary behavior. It is test infrastructure, not a shipped first-party plugin.
- `tools/dist` owns Gradle distribution, bundle metadata, release archive layout, smoke tests, and third-party notices.

## Track Matrix

| Track | Scope | Primary files | First model | Verification |
| --- | --- | --- | --- | --- |
| A. Foundation | Gradle wrapper, module layout, conventions, dependency catalog | `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml` | `gpt-5-codex` | `./gradlew test`, `git diff --check` |
| B. Contracts | Java protocol records, schema constants, schema validation tests | `modules/contracts`, `schemas`, `fixtures` | `gpt-5-codex` | `./gradlew :modules:contracts:test` |
| C. Core Runtime | plugin manifests, registry, process execution, env allowlist, diagnostics | `modules/core` | `gpt-5-codex` | `./gradlew :modules:core:test` |
| D. CLI | command parsing, JSON IO, envelope printing, exit status behavior | `apps/cli` | `gpt-5-codex` | `./gradlew :apps:cli:test` and CLI fixture tests |
| E. Semantic Plugins | generic graph, ArchiMate rules, UML rules, schema cache | `modules/archimate`, `modules/uml`, `modules/plugins/generic-graph`, `modules/schema-cache` | `gpt-5-codex` | plugin tests and contract fixtures |
| F. Official Java ELK | direct official Eclipse ELK Java plugin, real render proof, no `elkrs` | `modules/plugins/elk-layout` | `gpt-5-codex` | Java ELK unit tests, real render tests |
| G. Render/Export | SVG render, ArchiMate OEF XML, UML XMI XML | `modules/plugins/svg-render`, `modules/plugins/archimate-oef-export`, `modules/plugins/uml-xmi-export` | `gpt-5-codex` | SVG, OEF, XMI plugin tests |
| H. Test Support | migrated runtime testbed and shared parity helpers | `test-support`, `testbeds/plugin-runtime` | `gpt-5-codex` | plugin runtime and CLI/process tests |
| I. Distribution | Gradle dist, bundle smoke, release workflow, notices, docs | `tools/dist`, `.github/workflows/release.yml`, `README.md`, `docs/agent-usage.md` | `gpt-5-codex` | dist build, dist smoke, stale search |
| J. Retirement | delete Rust-only source and tooling after Java parity | `Cargo.toml`, `Cargo.lock`, `crates`, `xtask` | `gpt-5-codex` | full Gradle gate, migration-ledger review, stale Rust search |

## Cross-Track Dependency Graph

```text
A Foundation
  -> B Contracts
    -> C Core Runtime
      -> H Test Support
      -> D CLI
      -> E Semantic Plugins
        -> F Official Java ELK
        -> G Render/Export
          -> I Distribution
            -> J Retirement
```

## Coverage Rule

Each track must close with a Rust coverage note that names the Rust crate, Rust test files, fixture families, docs surfaces, and release/runtime surfaces that were ported or intentionally deferred. Track J may delete Rust only after those notes cover every line in the roadmap migration ledgers.

## Contract-Preserving Translation Rules

- Keep schema files checked in under `schemas/`; Java records conform to schemas, not the reverse.
- Keep fixture JSON under `fixtures/` as shared parity evidence.
- Use Jackson configured to reject unknown fields where the Rust implementation currently rejects unsupported fields.
- Preserve diagnostic codes unless a slice explicitly declares a public diagnostic replacement and version impact.
- Preserve command envelopes on stdout for success and plugin errors.
- Preserve non-zero process exits for valid plugin error envelopes.
- Keep stderr for human debugging only.
- Keep env forwarding manifest-owned; do not inherit ambient process env except manifest-allowed names.

## Validation Ladder

1. `git status --short --branch`
2. narrow Gradle module test for the slice
3. affected plugin/CLI integration tests
4. schema and fixture parity tests
5. official Java ELK real-render tests for layout-affecting slices
6. distribution build and smoke for release slices
7. stale Rust/elkrs/version search before retirement
8. `git diff --check`

## Audit Gates

- Track A and H: `souroldgeezer-audit:devsecops-audit` Quick for Gradle Wrapper, dependency resolution, release workflow, permissions, and artifact publication.
- Tracks B through G: `souroldgeezer-audit:test-quality-audit` Deep for contract, plugin, integration, render/export, and real-layout proof.
- Track F and G: `souroldgeezer-architecture:architecture-design` Review for ArchiMate/OEF/UML render/export evidence when package semantics are affected.
- Track I: `souroldgeezer-design:software-design` Review for dependency direction and retirement completeness before deleting Rust.

## Incremental Grooming Rule

Execute one low-level slice at a time. At the start of each slice, refresh `git status`, the touched files, and the previous slice's verification output. At the end of each slice, update the next slice if evidence changed the assumptions.

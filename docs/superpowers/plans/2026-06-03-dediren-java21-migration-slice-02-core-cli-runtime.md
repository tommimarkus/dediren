# Dediren Java 21 Migration Slice 02: Core, CLI, And Plugin Runtime

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` for this slice. Do not remove Rust CLI or core code in this slice.

**Goal:** Port core orchestration, plugin runtime behavior, and the user-facing CLI to Java while keeping current Rust behavior available for parity tests.

**Architecture:** `modules/core` owns orchestration and plugin execution. `apps/cli` is an adapter over core commands and prints JSON command envelopes. First-party and third-party plugins remain separate executables discovered explicitly through manifests.

**Tech Stack:** Java 21, Picocli, Jackson, JUnit 5, AssertJ, Gradle application plugin.

---

## Files

- Create: `modules/core/src/main/java/dev/dediren/core/io/*.java`
- Create: `modules/core/src/main/java/dev/dediren/core/plugins/*.java`
- Create: `modules/core/src/main/java/dev/dediren/core/commands/*.java`
- Create: `modules/core/src/main/java/dev/dediren/core/quality/*.java`
- Create: `modules/core/src/test/java/dev/dediren/core/plugins/*Test.java`
- Create: `modules/core/src/test/java/dev/dediren/core/commands/*Test.java`
- Create: `test-support/src/main/java/dev/dediren/testsupport/*.java`
- Create: `testbeds/plugin-runtime/src/main/java/dev/dediren/testbeds/pluginruntime/Main.java`
- Create: `testbeds/plugin-runtime/src/test/java/dev/dediren/testbeds/pluginruntime/*Test.java`
- Create: `apps/cli/src/main/java/dev/dediren/cli/Main.java`
- Create: `apps/cli/src/test/java/dev/dediren/cli/*Test.java`
- Read parity source: `crates/dediren-core/src`
- Read parity source: `crates/dediren-cli/src`
- Read parity source: `crates/dediren-plugin-runtime-testbed/src/main.rs`
- Read parity source: `crates/dediren-cli/tests/common/mod.rs`
- Read parity source: `crates/dediren-core/tests/common/mod.rs`
- Read parity tests: `crates/dediren-core/tests/plugin_runtime.rs`
- Read parity tests: `crates/dediren-core/tests/commands.rs`
- Read parity tests: `crates/dediren-cli/tests`

## Tasks

- [ ] **Task 1: Port IO and envelope parsing**
  - Model: `gpt-5-codex`
  - Add Java IO helpers that read from stdin or a file path and parse command data from success envelopes.
  - Preserve missing-file diagnostics and invalid JSON diagnostics.
  - Add tests mirroring CLI missing input behavior.
  - Run: `./gradlew :modules:core:test --tests '*Io*'`
  - Expected: pass.

- [ ] **Task 2: Port plugin manifest registry**
  - Model: `gpt-5-codex`
  - Add manifest loading from bundled manifests, source fixture manifests, project plugin directories, and `DEDIREN_PLUGIN_DIRS`.
  - Preserve explicit discovery order and no implicit `PATH` discovery.
  - Add tests for unknown plugin, unsupported capability, manifest schema mismatch, executable override env, and installed bundle lookup.
  - Expected: Java tests match the Rust plugin discovery semantics.

- [ ] **Task 3: Port plugin process execution**
  - Model: `gpt-5-codex`
  - Port `crates/dediren-plugin-runtime-testbed/src/main.rs` into `testbeds/plugin-runtime` before replacing Rust plugin runtime tests.
  - Add process runner with stdin JSON, stdout capture, stderr capture, timeout, pipe draining, and non-zero error envelope preservation.
  - Preserve structured diagnostics for missing executable, timeout, invalid JSON, invalid success envelope, schema mismatch, unsupported capability, id mismatch, and missing runtime dependency.
  - Add tests based on every behavior currently supplied by `dediren-plugin-runtime-testbed`; do not depend on the Rust testbed after this task.
  - Run: `./gradlew :modules:core:test --tests '*PluginRuntime*'`
  - Expected: pass.

- [ ] **Task 4: Port env allowlist ownership**
  - Model: `gpt-5-codex`
  - Add command-specific env candidate handling equivalent to the current `LAYOUT_RUNTIME_ENV_ALLOWLIST` behavior.
  - Preserve manifest-owned env forwarding: only manifest-listed env names reach capability probes and commands.
  - Add tests for `DEDIREN_ELK_COMMAND`, `DEDIREN_ELK_RESULT_FIXTURE`, schema cache env, OEF env, UML/XMI env, and ambient env rejection.
  - Expected: pass.

- [ ] **Task 5: Port layout quality checks**
  - Model: `gpt-5-codex`
  - Add backend-neutral layout quality validation for overlaps, invalid routes, connector-through-node, group boundary issues, excessive detours, and close parallel routes.
  - Add tests equivalent to `crates/dediren-core/tests/layout_quality.rs`.
  - Run: `./gradlew :modules:core:test --tests '*LayoutQuality*'`
  - Expected: pass.

- [ ] **Task 6: Port CLI commands**
  - Model: `gpt-5-codex`
  - Add Picocli commands: `validate`, `project`, `layout`, `validate-layout`, `render`, `export`, `--version`, and `--help`.
  - CLI code reads arguments, calls core command services, prints stdout JSON, and sets exit status.
  - Add CLI fixture tests for every Rust CLI test lane: `cli_help.rs`, `cli_validate.rs`, `cli_project.rs`, `cli_layout.rs`, `cli_render.rs`, `cli_export.rs`, `cli_pipeline.rs`, `plugin_compat.rs`, and the fixture-mode portions of `real_elk_render.rs`.
  - Run: `./gradlew :apps:cli:test`
  - Expected: pass.

- [ ] **Task 7: Add shared Java test support**
  - Model: `gpt-5-codex`
  - Port the useful behavior from Rust `tests/common` modules into `test-support`: workspace path resolution, fixture loading, JSON command helpers, plugin executable path helpers, schema assertions, and clean environment command helpers.
  - Wire `modules/core`, `apps/cli`, and plugin test suites to use `test-support` instead of duplicating helpers.
  - Run: `./gradlew :test-support:test`
  - Expected: pass.

- [ ] **Task 8: Run audit checks**
  - Model: `gpt-5`
  - Use `souroldgeezer-design:software-design` Review for CLI thinness, core ownership, and plugin dependency direction.
  - Use `souroldgeezer-audit:test-quality-audit` Deep for core and CLI tests.
  - Use `souroldgeezer-audit:devsecops-audit` Quick for process execution, env forwarding, and timeout handling.
  - Include `testbeds/plugin-runtime` in the review so the Rust runtime testbed is not silently lost.
  - Fix block findings before commit.

- [ ] **Task 9: Verify and commit**
  - Model: `gpt-5-mini`
  - Run: `./gradlew :test-support:test :testbeds:plugin-runtime:test :modules:core:test :apps:cli:test`
  - Run: `cargo test -p dediren-core --test plugin_runtime --locked`
  - Run: `cargo test -p dediren --test plugin_compat --locked`
  - Run: `cargo test -p dediren --test cli_help --locked`
  - Add a handoff note mapping each Rust CLI/core/runtime-testbed test file to the Java successor test class.
  - Run: `git diff --check`
  - Commit message: `feat: port core cli and plugin runtime to java`

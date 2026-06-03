# Dediren Java 21 Migration Slice 05: Official Java ELK

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` for this slice. Real Java ELK evidence is mandatory before completion.

**Goal:** Ensure the target Java product uses official Eclipse ELK Java libraries for layout and contains no `elkrs` runtime dependency.

**Architecture:** `modules/plugins/elk-layout` is a Java executable plugin using official Eclipse ELK artifacts. It owns ELK graph construction, ELK options, ports, hierarchy, generated geometry, warnings, and runtime capability output. It does not depend on core and it does not rewrite ELK route points after layout.

**Tech Stack:** Java 21, official Eclipse ELK Java artifacts, Jackson, JUnit 5, AssertJ, Gradle application plugin.

---

## Files

- Create: `modules/plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/Main.java`
- Create: `modules/plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java`
- Create: `modules/plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/JsonContracts.java`
- Create: `modules/plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/*Test.java`
- Modify: `gradle/libs.versions.toml`
- Modify: `fixtures/plugins/elk-layout.manifest.json`
- Modify: `README.md`
- Modify: `docs/agent-usage.md`
- Delete or replace in target state: `crates/dediren-plugin-elk-layout/java/.sdkmanrc`
- Delete or replace in target state: SDKMAN logic in `crates/dediren-plugin-elk-layout/java/scripts/*.sh`
- Read seed source: `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk`
- Read seed tests: `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk`
- Read parity source: `crates/dediren-plugin-elk-layout/src/main.rs`
- Read parity tests: `crates/dediren-plugin-elk-layout/tests/elk_layout_plugin.rs`
- Read parity tests: `crates/dediren-cli/tests/cli_layout.rs`
- Read parity tests: `crates/dediren-cli/tests/real_elk_render.rs`
- If executing from `elkrs-layout-replacement`, read: `crates/dediren-plugin-elk-layout/src/elkrs_backend.rs`

## Tasks

- [ ] **Task 1: Confirm ELK base state**
  - Model: `gpt-5-mini`
  - Run: `rg -n "elkrs|org.eclipse.elk|DEDIREN_ELK_COMMAND|runtimes/elk-layout-java|rust-elkrs|external-elk" Cargo.toml Cargo.lock README.md docs/agent-usage.md crates modules fixtures/plugins`
  - Expected on `main`: official Java helper references exist and `elkrs` does not.
  - Expected on `elkrs-layout-replacement`: `elkrs` references exist and must be removed or superseded by this slice.

- [ ] **Task 2: Add official Java ELK dependencies**
  - Model: `gpt-5-codex`
  - Add official Eclipse ELK dependencies to the Gradle version catalog and `modules/plugins/elk-layout`.
  - Required artifacts: `org.eclipse.elk.core`, `org.eclipse.elk.graph`, and `org.eclipse.elk.alg.layered`.
  - Keep bytecode target at Java 21.
  - Run: `./gradlew :modules:plugins:elk-layout:dependencies --configuration runtimeClasspath`
  - Expected: official ELK dependencies appear; `elkrs` does not.

- [ ] **Task 3: Port Java helper into executable plugin**
  - Model: `gpt-5-codex`
  - Move the existing helper behavior from `crates/dediren-plugin-elk-layout/java` into `modules/plugins/elk-layout`.
  - Replace SDKMAN-dependent helper scripts with Gradle Wrapper/application launchers owned by the Java product.
  - Preserve input contract `layout-request.schema.v1` and output contract `layout-result.schema.v1`.
  - Preserve diagnostics for invalid input JSON, invalid contract, dangling edges, missing group members, empty groups, and layout failure.
  - Capability output reports `id: "elk-layout"` and `capabilities: ["layout"]`.
  - Runtime kind should clearly identify official Java ELK, for example `official-java-elk`; update schema tests and docs if this is a public expectation.

- [ ] **Task 4: Remove or neutralize `elkrs` behavior**
  - Model: `gpt-5-codex`
  - If the implementation base contains `elkrs`, remove `elkrs-core` and `elkrs-layered` dependencies.
  - Delete or stop compiling `elkrs_backend.rs` and tests whose only purpose is `rust-elkrs`.
  - Replace `DEDIREN_ELK_OPTION_UNSUPPORTED` behavior caused by `elkrs` gaps with official Java ELK behavior or Java ELK diagnostics.
  - Update README and `docs/agent-usage.md` to remove `elkrs` backend limitations.

- [ ] **Task 5: Preserve ELK-first route quality rules**
  - Model: `gpt-5`
  - Use `souroldgeezer-design:software-design` Review on ELK ownership.
  - Verify that route quality is expressed through ELK graph structure, ports, hierarchy, and official ELK options.
  - Block custom route-point rewriting unless a failing real-render test and attempted ELK options are documented in this slice.

- [ ] **Task 6: Add real official Java ELK tests**
  - Model: `gpt-5-codex`
  - Port or recreate tests for basic layout, layout preferences, grouped cross-group routes, UML class/data/activity render cases, ArchiMate render cases, and route quality.
  - Cover every Rust ELK lane: `elk_layout_plugin.rs`, the real/ignored lanes in `cli_layout.rs`, and all real render cases in `real_elk_render.rs`.
  - Run: `./gradlew :modules:plugins:elk-layout:test`
  - Run Java CLI real-layout integration tests once CLI wiring is available.
  - Expected: tests prove generated node geometry and route points come from official Java ELK.

- [ ] **Task 7: Add ELK Rust coverage note**
  - Model: `gpt-5-mini`
  - In the slice handoff, map the Rust adapter, Java helper source, Java helper tests, CLI layout tests, real render tests, manifest env names, README ELK runtime section, and agent-usage ELK section to Java target code/tests/docs.
  - Include `.sdkmanrc`, `source "$HOME/.sdkman/bin/sdkman-init.sh"`, and `DEDIREN_ELK_BUILD_USE_SDKMAN` as legacy surfaces that must not exist in target-state docs or runtime scripts.
  - If starting from `elkrs-layout-replacement`, include a separate deletion note for every `elkrs` source, dependency, diagnostic, fixture limitation, and doc statement.
  - Expected: no Rust ELK or `elkrs` surface remains without a target-state decision.

- [ ] **Task 8: Run audits**
  - Model: `gpt-5`
  - Use `souroldgeezer-audit:test-quality-audit` Deep on ELK Java tests.
  - Use `souroldgeezer-audit:devsecops-audit` Quick on Java dependency posture and plugin process boundary.
  - Use `souroldgeezer-architecture:architecture-design` Review if ArchiMate or UML render evidence changes.
  - Fix block findings before commit.

- [ ] **Task 9: Verify and commit**
  - Model: `gpt-5-mini`
  - Run: `./gradlew :modules:plugins:elk-layout:test :apps:cli:test`
  - Run: `rg -n "elkrs|rust-elkrs" Cargo.toml Cargo.lock README.md docs/agent-usage.md modules fixtures/plugins`
  - Expected: no target-state `elkrs` references remain.
  - Run: `git diff --check`
  - Commit message: `feat: use official java elk layout`

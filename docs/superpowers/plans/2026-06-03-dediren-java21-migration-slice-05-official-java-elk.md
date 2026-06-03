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

- [x] **Task 1: Confirm ELK base state**
  - Model: `gpt-5-mini`
  - Run: `rg -n "elkrs|org.eclipse.elk|DEDIREN_ELK_COMMAND|runtimes/elk-layout-java|rust-elkrs|external-elk" Cargo.toml Cargo.lock README.md docs/agent-usage.md crates modules fixtures/plugins`
  - Expected on `main`: official Java helper references exist and `elkrs` does not.
  - Expected on `elkrs-layout-replacement`: `elkrs` references exist and must be removed or superseded by this slice.

- [x] **Task 2: Add official Java ELK dependencies**
  - Model: `gpt-5-codex`
  - Add official Eclipse ELK dependencies to the Gradle version catalog and `modules/plugins/elk-layout`.
  - Required artifacts: `org.eclipse.elk.core`, `org.eclipse.elk.graph`, and `org.eclipse.elk.alg.layered`.
  - Keep bytecode target at Java 21.
  - Run: `./gradlew :modules:plugins:elk-layout:dependencies --configuration runtimeClasspath`
  - Expected: official ELK dependencies appear; `elkrs` does not.

### 2026-06-03 ELK Base And Dependency Checkpoint

Confirmed the current ELK migration base:

- `gradle/libs.versions.toml` declares official Eclipse ELK `0.11.0` artifacts for core, graph, and layered.
- `modules/plugins/elk-layout/build.gradle.kts` depends on those catalog aliases and targets the Java 21 Gradle build.
- The base-state search found official Java ELK and legacy external-helper surfaces, but no `elkrs` or `rust-elkrs` target-state dependency references in the searched active paths.
- `DEDIREN_ELK_COMMAND`, `external-elk`, and `runtimes/elk-layout-java` remain as legacy Rust/helper cutover surfaces for Tasks 3, 7, and 9.

Verification:

```bash
rg -n "elkrs|org.eclipse.elk|DEDIREN_ELK_COMMAND|runtimes/elk-layout-java|rust-elkrs|external-elk" Cargo.toml Cargo.lock README.md docs/agent-usage.md crates modules fixtures/plugins
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :modules:plugins:elk-layout:dependencies --configuration runtimeClasspath
```

Result: official `org.eclipse.elk:org.eclipse.elk.core`, `org.eclipse.elk:org.eclipse.elk.graph`, and `org.eclipse.elk:org.eclipse.elk.alg.layered` appeared on the Java ELK module runtime classpath; no `elkrs` dependency appeared in the search or Gradle report.

- [x] **Task 3: Port Java helper into executable plugin**
  - Model: `gpt-5-codex`
  - Move the existing helper behavior from `crates/dediren-plugin-elk-layout/java` into `modules/plugins/elk-layout`.
  - Replace SDKMAN-dependent helper scripts with Gradle Wrapper/application launchers owned by the Java product.
  - Preserve input contract `layout-request.schema.v1` and output contract `layout-result.schema.v1`.
  - Preserve diagnostics for invalid input JSON, invalid contract, dangling edges, missing group members, empty groups, and layout failure.
  - Capability output reports `id: "elk-layout"` and `capabilities: ["layout"]`.
  - Runtime kind should clearly identify official Java ELK, for example `official-java-elk`; update schema tests and docs if this is a public expectation.

- [x] **Task 4: Remove or neutralize `elkrs` behavior**
  - Model: `gpt-5-codex`
  - If the implementation base contains `elkrs`, remove `elkrs-core` and `elkrs-layered` dependencies.
  - Delete or stop compiling `elkrs_backend.rs` and tests whose only purpose is `rust-elkrs`.
  - Replace `DEDIREN_ELK_OPTION_UNSUPPORTED` behavior caused by `elkrs` gaps with official Java ELK behavior or Java ELK diagnostics.
  - Update README and `docs/agent-usage.md` to remove `elkrs` backend limitations.

- [x] **Task 5: Preserve ELK-first route quality rules**
  - Model: `gpt-5`
  - Use `souroldgeezer-design:software-design` Review on ELK ownership.
  - Verify that route quality is expressed through ELK graph structure, ports, hierarchy, and official ELK options.
  - Block custom route-point rewriting unless a failing real-render test and attempted ELK options are documented in this slice.

### 2026-06-03 Official Java ELK Plugin Checkpoint

Ported the official Eclipse ELK helper into the root Java product module:

- `modules/plugins/elk-layout` now owns the executable plugin entrypoint, `layout` command, `capabilities` command, JSON reader, official ELK layout engine, and Java tests.
- The ELK engine uses shared Java contracts from `modules/contracts` instead of local plugin protocol records.
- `LayoutEdge` gained a five-argument convenience constructor for existing Java tests while preserving the public JSON shape.
- `gradle/libs.versions.toml` now includes the ELK-required `org.eclipse.xtext:org.eclipse.xtext.xbase.lib` runtime dependency.
- Capabilities report `id: "elk-layout"`, `capabilities: ["layout"]`, and runtime metadata `kind: "official-java-elk"` with algorithm `org.eclipse.elk.layered`.
- Legacy helper scripts under `crates/dediren-plugin-elk-layout/java/scripts` are now wrapper shims for the root Gradle module; `.sdkmanrc` was removed and the scripts contain no SDKMAN logic.
- Route-quality ownership remains ELK-first: tests assert Layered-only dependencies, no Libavoid backend, no post-ELK route rewrites, and route quality through graph structure, ports, hierarchy, and official ELK options.
- The target-state search for `elkrs|rust-elkrs` in `Cargo.toml`, `Cargo.lock`, `README.md`, `docs/agent-usage.md`, `modules`, and `fixtures/plugins` returned no matches.

Verification:

```bash
GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew :modules:plugins:elk-layout:test
crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh
crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh capabilities
rg -n "SDKMAN|sdkman|DEDIREN_ELK_BUILD_USE_SDKMAN|\\.sdkmanrc|sdk env|sdkman-init" crates/dediren-plugin-elk-layout/java modules/plugins/elk-layout docs/superpowers/plans/2026-06-03-dediren-java21-migration-slice-05-official-java-elk.md
rg -n "elkrs|rust-elkrs" Cargo.toml Cargo.lock README.md docs/agent-usage.md modules fixtures/plugins
```

Result: ELK module tests passed, wrapper-based install distribution built successfully, the script-launched executable reported official Java ELK capabilities, no SDKMAN references remain in runtime scripts/modules, and no target-state `elkrs` references were found.

- [ ] **Task 6: Add real official Java ELK tests**
  - Model: `gpt-5-codex`
  - Port or recreate tests for basic layout, layout preferences, grouped cross-group routes, UML class/data/activity render cases, ArchiMate render cases, and route quality.
  - Cover every Rust ELK lane: `elk_layout_plugin.rs`, the real/ignored lanes in `cli_layout.rs`, and all real render cases in `real_elk_render.rs`.
  - Run: `./gradlew :modules:plugins:elk-layout:test`
  - Run Java CLI real-layout integration tests once CLI wiring is available.
  - Expected: tests prove generated node geometry and route points come from official Java ELK.

- [x] **Task 7: Add ELK Rust coverage note**
  - Model: `gpt-5-mini`
  - In the slice handoff, map the Rust adapter, Java helper source, Java helper tests, CLI layout tests, real render tests, manifest env names, README ELK runtime section, and agent-usage ELK section to Java target code/tests/docs.
  - Include `.sdkmanrc`, `source "$HOME/.sdkman/bin/sdkman-init.sh"`, and `DEDIREN_ELK_BUILD_USE_SDKMAN` as legacy surfaces that must not exist in target-state docs or runtime scripts.
  - If starting from `elkrs-layout-replacement`, include a separate deletion note for every `elkrs` source, dependency, diagnostic, fixture limitation, and doc statement.
  - Expected: no Rust ELK or `elkrs` surface remains without a target-state decision.

### 2026-06-03 ELK Coverage Note

Rust and legacy helper surfaces now have explicit Java target-state decisions:

- `crates/dediren-plugin-elk-layout/java/src/main/java/dev/dediren/elk/*` moved to `modules/plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/*`.
- `crates/dediren-plugin-elk-layout/java/src/test/java/dev/dediren/elk/*` moved to `modules/plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/*`.
- The moved Java tests now use shared `modules/contracts` layout records rather than local plugin protocol records.
- `crates/dediren-plugin-elk-layout/java/.sdkmanrc` was removed.
- `crates/dediren-plugin-elk-layout/java/scripts/build-elk-layout.sh` now runs the root Gradle Wrapper target `:modules:plugins:elk-layout:installDist`.
- `crates/dediren-plugin-elk-layout/java/scripts/elk-layout.sh` now executes the root Java plugin install distribution and does not source SDKMAN.
- `crates/dediren-plugin-elk-layout/src/main.rs` remains a legacy Rust adapter until the distribution cutover removes Rust crates or rewires first-party bundled manifests to Java launchers.
- `fixtures/plugins/elk-layout.manifest.json` still declares the legacy Rust executable and `DEDIREN_ELK_COMMAND` environment while Rust compatibility remains. Target-state cutover must replace this with the Java launcher path and remove `DEDIREN_ELK_COMMAND` from allowed env.
- Rust ignored CLI/render lanes in `crates/dediren-cli/tests/cli_layout.rs` and `crates/dediren-cli/tests/real_elk_render.rs` remain source-parity references for Task 6 and slice 06. The Java ELK module already carries the official ELK route-quality matrix; Java CLI/bundle render evidence is still a cutover task.
- README and `docs/agent-usage.md` still describe Rust adapter and SDKMAN-era helper behavior. They remain open for the distribution/docs cutover task, not for the Java ELK module implementation.
- No `elkrs`/`rust-elkrs` source, dependency, diagnostic, fixture limitation, or doc statement was found in the active target-state search paths for this branch.

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

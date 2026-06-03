# Dediren Java 21 Migration Slice 00: Foundation

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` for this slice. Commit only this slice's files after verification.

**Goal:** Add a Java 21+ Gradle foundation and preserve the Rust baseline as characterization evidence.

**Architecture:** Add the Java build beside the Rust workspace first. Do not delete Rust, change CLI behavior, or change product version in this slice.

**Tech Stack:** Java 21+, checked-in Gradle Wrapper 9.5.0, Gradle Kotlin DSL, JUnit 5, AssertJ, GitHub Actions unchanged.

---

## Files

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `apps/cli/build.gradle.kts`
- Create: `modules/contracts/build.gradle.kts`
- Create: `modules/core/build.gradle.kts`
- Create: `modules/archimate/build.gradle.kts`
- Create: `modules/uml/build.gradle.kts`
- Create: `modules/schema-cache/build.gradle.kts`
- Create: `modules/plugins/generic-graph/build.gradle.kts`
- Create: `modules/plugins/elk-layout/build.gradle.kts`
- Create: `modules/plugins/svg-render/build.gradle.kts`
- Create: `modules/plugins/archimate-oef-export/build.gradle.kts`
- Create: `modules/plugins/uml-xmi-export/build.gradle.kts`
- Create: `test-support/build.gradle.kts`
- Create: `testbeds/plugin-runtime/build.gradle.kts`
- Create: `tools/dist/build.gradle.kts`
- Modify: `.gitignore` only if Gradle outputs are not already ignored.

## Tasks

- [x] **Task 1: Confirm execution base**
  - Model: `gpt-5-mini`
  - Run: `git status --short --branch`
  - Run: `git log --oneline --decorate -3`
  - Expected: branch is the implementation worktree branch; unrelated user files are documented and not touched.
  - If executing from `elkrs-layout-replacement`, record that Slice 05 must actively remove `elkrs`. If executing from `main`, record that Slice 05 preserves and migrates the existing Java helper.

- [x] **Task 2: Capture Rust baseline**
  - Model: `gpt-5-codex`
  - Run: `cargo test --workspace --locked`
  - Expected: all non-ignored Rust tests pass.
  - Save no generated artifacts. Record ignored real-ELK tests and the Java helper build prerequisite in the slice handoff.

- [x] **Task 3: Add Gradle wrapper**
  - Model: `gpt-5-codex`
  - Add Gradle Wrapper pinned to Gradle 9.5.0.
  - Use Java 21 toolchain and set all Java compilation to `options.release = 21`.
  - Do not add `.sdkmanrc` or SDKMAN-dependent wrapper/build scripts.
  - Run: `./gradlew --version`
  - Expected: Gradle starts and reports a JVM version of 21 or newer.

- [x] **Task 4: Add root module layout**
  - Model: `gpt-5-codex`
  - `settings.gradle.kts` includes all target modules listed in the track plan.
  - `build.gradle.kts` configures repositories, Java toolchain, JUnit Platform, encoding, and strict test logging.
  - `gradle/libs.versions.toml` pins Jackson, JSON Schema Validator, Picocli, JUnit, AssertJ, XMLUnit, Eclipse ELK, and license-report dependencies.
  - Include `test-support` and `testbeds/plugin-runtime` so Rust `tests/common` modules and `dediren-plugin-runtime-testbed` have planned Java successors from the start.
  - No Rust build files are modified in this task.

- [x] **Task 5: Add smoke tests for module wiring**
  - Model: `gpt-5-codex`
  - Add one minimal JUnit test per Java module that asserts the module loads.
  - Use package names under `dev.dediren`.
  - Run: `./gradlew test`
  - Expected: Java smoke tests pass without invoking Rust code.

- [x] **Task 6: Run design and audit checks**
  - Model: `gpt-5`
  - Use `souroldgeezer-design:software-design` Review on the Gradle module layout.
  - Use `souroldgeezer-audit:devsecops-audit` Quick on the Gradle Wrapper and dependency catalog.
  - Fix block findings before committing. Document accepted warn/info findings in the handoff.

- [x] **Task 7: Verify and commit**
  - Model: `gpt-5-mini`
  - Run: `./gradlew test`
  - Run: `git diff --check`
  - Review: `git diff -- settings.gradle.kts build.gradle.kts gradle apps modules tools .gitignore`
  - Commit message: `build: add java migration foundation`

## Handoff Evidence

- Execution base: worktree branch `java21-migration-planning` at `4771725` (`v0.16.0`).
- Pre-existing unrelated user work in the main checkout: `crates/dediren-plugin-elk-layout/java/bin/`; not touched.
- Rust baseline: `cargo test --workspace --locked` passed before adding Java foundation files. Ignored real-ELK tests remain opt-in legacy evidence until Slice 05 ports the official Java ELK path.
- Wrapper verification: `GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew --version` reported Gradle `9.5.0` and Launcher JVM `21.0.10`.
- Java smoke gate: `GRADLE_USER_HOME=.cache/gradle/user-home ./gradlew test` passed after adding the Gradle distribution checksum.
- Whitespace gate: `git diff --check` passed.
- Software-design review: module ownership matches the target contract-first split; contracts remain data-only, core remains separate, and first-party plugin modules stay process-boundary candidates.
- DevSecOps quick audit: added `distributionSha256Sum` to the wrapper properties. No block findings remain. Accepted info: dependency verification metadata is deferred until Java dependencies carry real implementation code.
- SDKMAN decision: target build support is Gradle Wrapper only. No `.sdkmanrc` or SDKMAN build/runtime script was added in this slice; existing legacy SDKMAN docs/helper files are scheduled for removal in Slices 05 and 06.

# Audit Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the structured-diagnostic coverage gaps and the design debt behind them that the combined test-quality + 7-module mutation + software-design audit surfaced, without changing public wire behavior.

**Architecture:** Five interdependent, independently-shippable phases over `contracts`, `core`, the export plugins, and `test-support`. They share one theme — the published `DEDIREN_*` diagnostic contract and the plugin-runtime test surface — so they belong in one plan. The genuinely separate subsystem (the `render/Main.java` god-class) is split out as a recommended follow-on plan per the scope rule below.

**Tech Stack:** Java 21, Maven (checked-in `./mvnw`), JUnit 6 (Jupiter), AssertJ, PIT (mutation). No new dependencies.

## Global Constraints

- **No new third-party dependencies.** Only JDK + already-present test libs (JUnit, AssertJ).
- **No public wire-contract change.** The canonical `DEDIREN_*` string values must stay byte-identical; the `DiagnosticCode` enum only gives those existing strings a compile-time owner.
- **No version bump in this work.** Per `souroldgeezer-policy:release-policy`, a CalVer bump (`pom.xml` is the source) lives in its own separate commit made *after* integration, never inside a content commit. Product version is currently `2026.06.10`; do not touch it here.
- **Maven runs need the sandbox disabled** in this environment (JUnit `@TempDir` writes under a read-only `/tmp` otherwise). Every `./mvnw` command below assumes sandbox-disabled execution. Commits are SSH-signed (key under `~/.ssh`), which also requires the sandbox disabled.
- **Module-scoped single-test runs** need `-am -Dsurefire.failIfNoSpecifiedTests=false` (siblings are not installed): e.g. `./mvnw -pl core -am -Dtest=PluginRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test`.
- **Git hygiene:** conventional-commit subjects (`test:`/`feat:`/`refactor:`); explicit-path staging only (never `git add -A`); keep `main` linear (rebase, don't merge) — but commit on this worktree branch `worktree-audit-remediation-plan`.
- **Conventions to copy verbatim:** diagnostic emit shape `PluginExecutionException.plugin("<CODE>", pluginId, message)`; test assertion shape `assertThatThrownBy(...).isInstanceOf(PluginExecutionException.class).extracting(e -> ((PluginExecutionException) e).diagnostic().code()).isEqualTo(<expected>)`.

---

## File Structure

| File | Responsibility | Phase |
|---|---|---|
| `contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java` | **Create.** Compile-time owner of the diagnostic-code vocabulary (seeded with the codes this plan touches; grows as families migrate). | 1 |
| `contracts/src/test/java/dev/dediren/contracts/DiagnosticCodeTest.java` | **Create.** Proves each enum constant maps to its canonical wire string and the set is unique. | 1 |
| `core/.../plugins/PluginRunner.java` | **Modify.** Emit `DiagnosticCode.*.code()` instead of literals (timeout, process-failed, io-error). | 1 |
| `core/.../plugins/PluginRegistry.java` | **Modify.** Emit enum codes for unknown / manifest-invalid. | 1 |
| `test-support/src/main/java/dev/dediren/testsupport/Fixtures.java` | **Create.** One owned fixture/workspace-root reader (replaces ~16 per-class copies over time). | 2 |
| `test-support/src/main/java/dev/dediren/testsupport/CommandEnvelopeAssertions.java` | **Create.** One owned envelope-assertion helper (`okData` / `assertErrorCode`). | 2 |
| `test-support/src/test/java/dev/dediren/testsupport/CommandEnvelopeAssertionsTest.java` | **Create.** Proves the helper passes on ok and fails on the wrong code. | 2 |
| `testbeds/plugin-runtime/.../Main.java` | **Modify.** Add an `ok-envelope-nonzero` mode (valid ok envelope + exit 3) to drive `PROCESS_FAILED`. | 3 |
| `core/src/test/java/dev/dediren/core/plugins/PluginRuntimeTest.java` | **Modify.** Add timeout / process-failed / unknown / manifest-invalid tests. | 3 |
| `plugins/archimate-oef-export/.../Main.java` | **Modify.** Make the validator command name injectable via env (default `xmllint`). | 4 |
| `plugins/uml-xmi-export/.../Main.java` | **Modify.** Same seam for the XMI validator. | 4 |
| `plugins/archimate-oef-export/src/test/.../MainTest.java` / `plugins/uml-xmi-export/src/test/.../MainTest.java` | **Modify.** Add validator-unavailable tests. | 4 |
| `core/.../DedirenPaths.java` + composition roots | **Modify.** Add an explicit-root overload; thread the root from entrypoints; drop `System.setProperty` from runtime tests. | 5 |
| `archimate/src/test/.../ArchimateRelationshipRulesTest.java` | **Modify.** Cover the mutation-uncovered junction-containment/reachability rules. | 6 |

---

## Phase 1: Give the diagnostic vocabulary a compile-time owner (`SD-S-2`)

Introduce a `DiagnosticCode` enum in `contracts` seeded with the codes this plan asserts, and migrate the `core/plugins` emitters. This is the foundation: every new test below asserts against the enum, not a brittle literal. (Migrating all 95 codes across 19 files is an out-of-scope mechanical sweep — see the follow-on section.)

### Task 1: `DiagnosticCode` enum + ownership test

**Files:**
- Create: `contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java`
- Test: `contracts/src/test/java/dev/dediren/contracts/DiagnosticCodeTest.java`

**Interfaces:**
- Produces: `enum DiagnosticCode` with `String code()`. Constants used by later tasks: `PLUGIN_TIMEOUT`, `PLUGIN_PROCESS_FAILED`, `PLUGIN_IO_ERROR`, `PLUGIN_UNKNOWN`, `PLUGIN_MANIFEST_INVALID`, `OEF_SCHEMA_VALIDATOR_UNAVAILABLE`, `XMI_SCHEMA_VALIDATOR_UNAVAILABLE`.

- [ ] **Step 1: Write the failing test**

```java
// contracts/src/test/java/dev/dediren/contracts/DiagnosticCodeTest.java
package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DiagnosticCodeTest {
    @Test
    void eachConstantExposesItsCanonicalWireString() {
        assertThat(DiagnosticCode.PLUGIN_TIMEOUT.code()).isEqualTo("DEDIREN_PLUGIN_TIMEOUT");
        assertThat(DiagnosticCode.PLUGIN_PROCESS_FAILED.code()).isEqualTo("DEDIREN_PLUGIN_PROCESS_FAILED");
        assertThat(DiagnosticCode.OEF_SCHEMA_VALIDATOR_UNAVAILABLE.code())
                .isEqualTo("DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE");
    }

    @Test
    void wireStringsAreUniqueAndPrefixed() {
        var codes = Arrays.stream(DiagnosticCode.values()).map(DiagnosticCode::code).collect(Collectors.toSet());
        assertThat(codes).hasSize(DiagnosticCode.values().length);
        assertThat(codes).allMatch(code -> code.startsWith("DEDIREN_"));
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -pl contracts -am -Dtest=DiagnosticCodeTest -Dsurefire.failIfNoSpecifiedTests=false test` (sandbox disabled)
Expected: FAIL — `DiagnosticCode` does not exist (compile error).

- [ ] **Step 3: Create the enum (canonical strings copied verbatim from the emit sites)**

```java
// contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java
package dev.dediren.contracts;

/**
 * Compile-time owner of the published DEDIREN_* diagnostic vocabulary. Seeded with the codes that
 * currently have explicit test ownership; add constants here as each emitting family migrates off
 * raw string literals. The {@link #code()} string is the wire contract and must never change for an
 * existing constant.
 */
public enum DiagnosticCode {
    PLUGIN_TIMEOUT("DEDIREN_PLUGIN_TIMEOUT"),
    PLUGIN_PROCESS_FAILED("DEDIREN_PLUGIN_PROCESS_FAILED"),
    PLUGIN_IO_ERROR("DEDIREN_PLUGIN_IO_ERROR"),
    PLUGIN_UNKNOWN("DEDIREN_PLUGIN_UNKNOWN"),
    PLUGIN_MANIFEST_INVALID("DEDIREN_PLUGIN_MANIFEST_INVALID"),
    OEF_SCHEMA_VALIDATOR_UNAVAILABLE("DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE"),
    XMI_SCHEMA_VALIDATOR_UNAVAILABLE("DEDIREN_XMI_SCHEMA_VALIDATOR_UNAVAILABLE");

    private final String code;

    DiagnosticCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./mvnw -pl contracts -am -Dtest=DiagnosticCodeTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add contracts/src/main/java/dev/dediren/contracts/DiagnosticCode.java \
        contracts/src/test/java/dev/dediren/contracts/DiagnosticCodeTest.java
git commit -m "feat(contracts): add DiagnosticCode owner for the diagnostic vocabulary"
```

### Task 2: Migrate the `core/plugins` emitters to the enum

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/plugins/PluginRunner.java` (lines emitting `"DEDIREN_PLUGIN_TIMEOUT"` ~304, `"DEDIREN_PLUGIN_PROCESS_FAILED"` ~178, `"DEDIREN_PLUGIN_IO_ERROR"` ~288)
- Modify: `core/src/main/java/dev/dediren/core/plugins/PluginRegistry.java` (lines emitting `"DEDIREN_PLUGIN_UNKNOWN"` ~95, `"DEDIREN_PLUGIN_MANIFEST_INVALID"` ~75/82/89)

**Interfaces:**
- Consumes: `DiagnosticCode` (Task 1). `core` already depends on `contracts`.
- Produces: no behavior change — identical wire strings, now sourced from the enum.

- [ ] **Step 1: Confirm the suite is green first (refactor baseline)**

Run: `./mvnw -pl core -am -Dtest=PluginRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS (20 tests). This is a behavior-preserving refactor protected by the existing tests.

- [ ] **Step 2: Replace the literal in `PluginRunner.timeout()`**

In `PluginRunner.java`, add `import dev.dediren.contracts.DiagnosticCode;`, then change the timeout emitter:

```java
    private static PluginExecutionException timeout(String pluginId, Duration timeout) {
        return PluginExecutionException.plugin(
                DiagnosticCode.PLUGIN_TIMEOUT.code(),
                pluginId,
                "plugin " + pluginId + " timed out after " + timeout.toMillis() + " ms");
    }
```

- [ ] **Step 3: Replace the remaining `core/plugins` literals**

Apply the same `"<LITERAL>"` → `DiagnosticCode.<CONST>.code()` substitution at:
- `PluginRunner.java` `normalizePluginOutput` → `PLUGIN_PROCESS_FAILED`; `runExecutable` catch → `PLUGIN_IO_ERROR`.
- `PluginRegistry.java` unknown-plugin throw → `PLUGIN_UNKNOWN`; the three manifest-schema throws → `PLUGIN_MANIFEST_INVALID`.

Verify none remain:

```bash
grep -rnE '"DEDIREN_PLUGIN_(TIMEOUT|PROCESS_FAILED|IO_ERROR|UNKNOWN|MANIFEST_INVALID)"' core/src/main
```
Expected: no output.

- [ ] **Step 4: Run the suite to confirm no behavior change**

Run: `./mvnw -pl core,cli -am test`
Expected: PASS (existing tests unchanged).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/dev/dediren/core/plugins/PluginRunner.java \
        core/src/main/java/dev/dediren/core/plugins/PluginRegistry.java
git commit -m "refactor(core): source plugin-runtime diagnostics from DiagnosticCode"
```

---

## Phase 2: One owned envelope/fixture test helper in `test-support` (`SD-E-1` / DRY)

The plugin-runtime and plugin tests reimplement `okData`/`errorEnvelope` and fixture loading per module (16 classes roll their own `workspaceRoot()`; only 4 use `test-support`). Add one owned helper so the new tests below — and future migrations — share it.

### Task 3: `CommandEnvelopeAssertions` + `Fixtures` helpers, tested both directions

**Files:**
- Create: `test-support/src/main/java/dev/dediren/testsupport/CommandEnvelopeAssertions.java`
- Create: `test-support/src/main/java/dev/dediren/testsupport/Fixtures.java`
- Test: `test-support/src/test/java/dev/dediren/testsupport/CommandEnvelopeAssertionsTest.java`

**Interfaces:**
- Produces:
  - `static JsonNode CommandEnvelopeAssertions.okData(String stdout)` — asserts `/status == ok`, returns `/data`.
  - `static void CommandEnvelopeAssertions.assertErrorCode(String stdout, String expectedCode)` — asserts `/status == error` and `/diagnostics/0/code == expectedCode`.
  - `static Path Fixtures.path(String relative)` / `static String Fixtures.read(String relative)` — resolve under the workspace root (delegates to existing `TestSupport.workspaceRoot()`).

- [ ] **Step 1: Write the failing test**

```java
// test-support/src/test/java/dev/dediren/testsupport/CommandEnvelopeAssertionsTest.java
package dev.dediren.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommandEnvelopeAssertionsTest {
    private static final String OK = "{\"status\":\"ok\",\"data\":{\"n\":1},\"diagnostics\":[]}";
    private static final String ERR =
            "{\"status\":\"error\",\"data\":null,\"diagnostics\":[{\"code\":\"DEDIREN_X\"}]}";

    @Test
    void okDataReturnsDataNodeOnSuccess() throws Exception {
        assertThat(CommandEnvelopeAssertions.okData(OK).at("/n").asInt()).isEqualTo(1);
    }

    @Test
    void okDataFailsWhenStatusIsError() {
        assertThatThrownBy(() -> CommandEnvelopeAssertions.okData(ERR))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void assertErrorCodeMatchesTheFirstDiagnostic() throws Exception {
        CommandEnvelopeAssertions.assertErrorCode(ERR, "DEDIREN_X");
    }

    @Test
    void assertErrorCodeFailsOnWrongCode() {
        assertThatThrownBy(() -> CommandEnvelopeAssertions.assertErrorCode(ERR, "DEDIREN_Y"))
                .isInstanceOf(AssertionError.class);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -pl test-support -am -Dtest=CommandEnvelopeAssertionsTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `CommandEnvelopeAssertions` does not exist.

- [ ] **Step 3: Create the helpers**

```java
// test-support/src/main/java/dev/dediren/testsupport/CommandEnvelopeAssertions.java
package dev.dediren.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;

public final class CommandEnvelopeAssertions {
    private CommandEnvelopeAssertions() {
    }

    public static JsonNode okData(String stdout) throws Exception {
        JsonNode envelope = JsonSupport.objectMapper().readTree(stdout);
        assertThat(envelope.at("/status").asText()).describedAs(stdout).isEqualTo("ok");
        return envelope.get("data");
    }

    public static void assertErrorCode(String stdout, String expectedCode) throws Exception {
        JsonNode envelope = JsonSupport.objectMapper().readTree(stdout);
        assertThat(envelope.at("/status").asText()).describedAs(stdout).isEqualTo("error");
        assertThat(envelope.at("/diagnostics/0/code").asText()).describedAs(stdout).isEqualTo(expectedCode);
    }
}
```

```java
// test-support/src/main/java/dev/dediren/testsupport/Fixtures.java
package dev.dediren.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Fixtures {
    private Fixtures() {
    }

    public static Path path(String relative) {
        return TestSupport.workspaceRoot().resolve(relative);
    }

    public static String read(String relative) throws java.io.IOException {
        return Files.readString(path(relative));
    }
}
```

> Note: if `test-support` does not already depend on `contracts` at compile scope (it is referenced as a test dependency elsewhere), add a compile-scope `contracts` dependency to `test-support/pom.xml`. Confirm with `grep -n contracts test-support/pom.xml` before assuming.

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./mvnw -pl test-support -am -Dtest=CommandEnvelopeAssertionsTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add test-support/src/main/java/dev/dediren/testsupport/CommandEnvelopeAssertions.java \
        test-support/src/main/java/dev/dediren/testsupport/Fixtures.java \
        test-support/src/test/java/dev/dediren/testsupport/CommandEnvelopeAssertionsTest.java
git commit -m "test-support: add owned envelope-assertion and fixture helpers"
```

---

## Phase 3: Close the runtime diagnostic-coverage gaps (P0 — `confirmed-mutation`/`confirmed-manual`)

`DEDIREN_PLUGIN_TIMEOUT` is `NO_COVERAGE` in the PIT run; `PROCESS_FAILED`/`UNKNOWN`/`MANIFEST_INVALID` have no test that triggers and asserts them. The testbed already ships the `sleep` mode; `PROCESS_FAILED` needs one small new mode.

### Task 4: Add `ok-envelope-nonzero` testbed mode (enables `PROCESS_FAILED`)

**Files:**
- Modify: `testbeds/plugin-runtime/src/main/java/dev/dediren/testbeds/pluginruntime/Main.java` (the `runCommand` switch, after `case "error-envelope-zero"`)

**Interfaces:**
- Produces: testbed behavior — when `DEDIREN_TEST_PLUGIN_MODE=ok-envelope-nonzero`, print a schema-valid `ok` render envelope, then `System.exit(3)`. This is the only state that reaches `PluginRunner:177` (`status=ok` + non-zero exit).

- [ ] **Step 1: Add the mode**

In `runCommand`'s `switch (mode())`, add a case alongside the existing ones:

```java
            case "ok-envelope-nonzero" -> {
                System.out.println(JsonSupport.objectMapper()
                        .writeValueAsString(CommandEnvelope.ok(successData(args, input.length()))));
                System.exit(3);
                return;
            }
```

- [ ] **Step 2: Build the testbed**

Run: `./mvnw -pl testbeds/plugin-runtime -am test`
Expected: PASS (compiles; existing `moduleLoads` test passes).

- [ ] **Step 3: Commit**

```bash
git add testbeds/plugin-runtime/src/main/java/dev/dediren/testbeds/pluginruntime/Main.java
git commit -m "test: add ok-envelope-nonzero testbed mode for PROCESS_FAILED coverage"
```

### Task 5: Add the four runtime-diagnostic tests

**Files:**
- Modify: `core/src/test/java/dev/dediren/core/plugins/PluginRuntimeTest.java` (append test methods; reuse the existing `runWithMode` / `writeManifest` / `testbedExecutable` helpers)

**Interfaces:**
- Consumes: `DiagnosticCode` (Task 1); existing `PluginRuntimeTest` helpers; `ok-envelope-nonzero` mode (Task 4); `PluginRunOptions.defaults().withCandidateEnv(...).withTimeout(Duration)`.

- [ ] **Step 1: Write the failing tests**

Add `import dev.dediren.contracts.DiagnosticCode;` and these four methods:

```java
    @Test
    void exceededWorkCommandTimeoutReturnsTypedDiagnostic() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));
        var options = PluginRunOptions.defaults()
                .withCandidateEnv(Map.of("DEDIREN_TEST_PLUGIN_MODE", "sleep"))
                .withTimeout(Duration.ofMillis(200));

        assertThatThrownBy(() -> PluginRunner.runForCapabilityWithRegistry(
                PluginRegistry.fromDirs(List.of(temp)), "runtime-testbed", "render", List.of("render"), "{}", options))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo(DiagnosticCode.PLUGIN_TIMEOUT.code());
    }

    @Test
    void okEnvelopeWithNonZeroExitIsStructuredProcessFailure() throws Exception {
        writeManifest(temp, "runtime-testbed", testbedExecutable().toString(), List.of("render"));

        assertThatThrownBy(() -> runWithMode("ok-envelope-nonzero", "render", List.of("render")))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo(DiagnosticCode.PLUGIN_PROCESS_FAILED.code());
    }

    @Test
    void unknownPluginIdIsStructured() throws Exception {
        assertThatThrownBy(() -> PluginRegistry.fromDirs(List.of(temp)).loadManifest("does-not-exist"))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo(DiagnosticCode.PLUGIN_UNKNOWN.code());
    }

    @Test
    void schemaInvalidManifestIsStructured() throws Exception {
        // A manifest missing the required "id" field must fail the plugin-manifest schema.
        Files.writeString(temp.resolve("broken.manifest.json"),
                "{\"plugin_manifest_schema_version\":\"plugin-manifest.schema.v1\"}");

        assertThatThrownBy(() -> PluginRegistry.fromDirs(List.of(temp)).loadManifest("broken"))
                .isInstanceOf(PluginExecutionException.class)
                .extracting(error -> ((PluginExecutionException) error).diagnostic().code())
                .isEqualTo(DiagnosticCode.PLUGIN_MANIFEST_INVALID.code());
    }
```

> Investigate-before-asserting note for `unknownPluginIdIsStructured` and `schemaInvalidManifestIsStructured`: confirm the exact `PluginRegistry` lookup entrypoint name and that an unknown id and a schema-invalid manifest each throw `PluginExecutionException` (not a different type) by reading `PluginRegistry.java` `loadManifest`/`fromDirs`. If the registry validates manifests lazily, trigger the lookup the same way the existing passing tests do (`PluginRegistry.fromDirs(List.of(temp)).loadManifest(id)` is used at `PluginRuntimeTest.java:193/217`). Adjust the call to match the real signature — keep the assertion identical.

- [ ] **Step 2: Run the tests to confirm they fail (for the right reason)**

Run: `./mvnw -pl core -am -Dtest=PluginRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: the timeout and process-failed tests PASS immediately (production already emits these codes — the gap was *coverage*, so these tests lock in existing behavior). The unknown/manifest-invalid tests PASS if the registry already emits those codes. **If any fails**, it reveals a real behavior gap — capture the actual code/exception and reconcile against `PluginRegistry.java` before adjusting (do not weaken the assertion to match wrong output).

- [ ] **Step 3: Confirm the timeout path is now covered by mutation**

Run: `./mvnw -pl core -DtargetClasses='dev.dediren.core.plugins.*' -Pmutation org.pitest:pitest-maven:mutationCoverage`
Expected: the `NO_COVERAGE` mutations previously on `PluginRunOptions.withTimeout()` / `PluginRunner:303 timeout()` are now covered (killed or at least executed). Compare against the audit baseline (12 NO_COVERAGE → fewer).

- [ ] **Step 4: Commit**

```bash
git add core/src/test/java/dev/dediren/core/plugins/PluginRuntimeTest.java
git commit -m "test(core): cover plugin timeout, process-failure, unknown, and manifest-invalid diagnostics"
```

---

## Phase 4: Make the export validators injectable and cover validator-unavailable (P0)

`DEDIREN_OEF/XMI_SCHEMA_VALIDATOR_UNAVAILABLE` is the documented plugin-owned runtime-dependency contract and is asserted by **no test**, because `OEF_SCHEMA_VALIDATOR = "xmllint"` is resolved from the ambient `PATH` at `Main.java:397` — there is no per-test way to force its absence. Add a small env seam (the `SD-C` fix from the design review), which makes the dependency explicit *and* the failure path testable.

### Task 6: OEF export — configurable validator command + unavailable test

**Files:**
- Modify: `plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/Main.java` (the `OEF_SCHEMA_VALIDATOR` constant + `validateOfficialOefSchema` `ProcessBuilder`)
- Modify: `plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`

**Interfaces:**
- Produces: validator command resolved as `env.getOrDefault("DEDIREN_OEF_SCHEMA_VALIDATOR", "xmllint")`. Default behavior is unchanged (still `xmllint`); a test can point it at a non-existent binary to force the `IOException` → `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE` path.

- [ ] **Step 1: Write the failing test**

Add, using the existing `envWithOefSchemas()` and `assertError(...)`/`okData(...)` helpers and a valid source fixture (mirror an existing happy-path export test for the input, then override the env):

```java
    @Test
    void missingOefSchemaValidatorIsStructured() throws Exception {
        Map<String, String> env = new java.util.HashMap<>(envWithOefSchemas());
        env.put("DEDIREN_OEF_SCHEMA_VALIDATOR", tempDir.resolve("no-such-validator").toString());

        // Use the same valid export input as the happy-path test, but with a validator that cannot start.
        PluginResult result = exportWithEnv(env); // follow the existing export-invocation helper in this file

        assertError(result, "DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE");
    }
```

> Investigate-before-writing: read this test file's existing happy-path export test (the one that calls `envWithOefSchemas()` and asserts `okData`) to copy the exact invocation helper name and the valid input wiring; name the new helper call to match (`exportWithEnv` above is a placeholder for that real helper). The assertion (`assertError(result, "...VALIDATOR_UNAVAILABLE")`) and the env override are the load-bearing parts and are correct as written.

- [ ] **Step 2: Run it to confirm it fails**

Run: `./mvnw -pl plugins/archimate-oef-export -am -Dtest=MainTest#missingOefSchemaValidatorIsStructured -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — production still hardcodes `xmllint`, so the bogus env is ignored and the real `xmllint` runs (test sees `ok`, not the error code).

- [ ] **Step 3: Add the seam in production**

In `Main.java`, replace the hardcoded constant usage in `validateOfficialOefSchema` with an env-resolved command, and migrate the four `"DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE"` literals to `DiagnosticCode.OEF_SCHEMA_VALIDATOR_UNAVAILABLE.code()`:

```java
    private static String oefSchemaValidator(Map<String, String> env) {
        String configured = env.get("DEDIREN_OEF_SCHEMA_VALIDATOR");
        return (configured == null || configured.isBlank()) ? OEF_SCHEMA_VALIDATOR : configured;
    }
```

Then in `validateOfficialOefSchema(content, env)` use `String validator = oefSchemaValidator(env);` and pass `validator` as the `ProcessBuilder` command (replacing `OEF_SCHEMA_VALIDATOR` in the `new ProcessBuilder(...)` arg list and in the unavailable messages).

- [ ] **Step 4: Run the test to confirm it passes**

Run: `./mvnw -pl plugins/archimate-oef-export,cli -am test`
Expected: PASS — new test green, all existing export tests still green (default validator unchanged).

- [ ] **Step 5: Commit**

```bash
git add plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/Main.java \
        plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java
git commit -m "feat(archimate-oef): make schema validator command injectable; cover unavailable path"
```

### Task 7: XMI export — same seam + unavailable test

**Files:**
- Modify: `plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java` (`XMI_SCHEMA_VALIDATOR` + its `validateOfficialXmiSchema` equivalent, ~lines 1183-1224)
- Modify: `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`

- [ ] **Step 1–5:** Repeat Task 6 exactly, substituting `XMI` for `OEF`: env key `DEDIREN_XMI_SCHEMA_VALIDATOR`, helper `xmiSchemaValidator(env)`, code `DiagnosticCode.XMI_SCHEMA_VALIDATOR_UNAVAILABLE.code()`, test `missingXmiSchemaValidatorIsStructured`, the file's existing `envWithXmiSchema()` helper, and verify with `./mvnw -pl plugins/uml-xmi-export,cli -am test`. Commit:

```bash
git add plugins/uml-xmi-export/src/main/java/dev/dediren/plugins/umlxmi/Main.java \
        plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java
git commit -m "feat(uml-xmi): make schema validator command injectable; cover unavailable path"
```

---

## Phase 5: Inject `productRoot`; remove `System.setProperty` from runtime tests (`SD-C-4`)

`DedirenPaths.productRoot()` reads ambient `System.getProperty("user.dir")`, which is why `PluginRuntimeTest` mutates JVM-global state (`:190-191, 212-213, 248-249, 285-286`). Add an explicit-input resolver so neither production callers nor tests touch the global.

### Task 8: Pure resolver overload + test migration

**Files:**
- Modify: `core/src/main/java/dev/dediren/core/DedirenPaths.java` (add an overload taking explicit property/env/cwd lookups)
- Modify: `core/src/test/java/dev/dediren/core/plugins/PluginRuntimeTest.java` (replace `System.setProperty`/`restoreProperty` with the injected root where the test controls discovery)

**Interfaces:**
- Produces: `static Path DedirenPaths.productRoot(Map<String,String> configuredProperties, Map<String,String> env, Path workingDir)` — the existing public `productRoot()` becomes a thin caller `productRoot(systemProps, System.getenv(), Path.of(System.getProperty("user.dir")))`. No behavior change for production callers.

- [ ] **Step 1: Write the failing test for the pure overload**

```java
    // in a new DedirenPathsTest (core/src/test/java/dev/dediren/core/DedirenPathsTest.java)
    @Test
    void resolvesProductRootFromExplicitWorkingDirWithoutTouchingGlobals(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp.resolve("schemas"));
        Files.writeString(temp.resolve("schemas/model.schema.json"), "{}");

        Path root = DedirenPaths.productRoot(Map.of(), Map.of(), temp);

        assertThat(root).isEqualTo(temp);
    }
```

- [ ] **Step 2: Run it to confirm it fails** — `./mvnw -pl core -am -Dtest=DedirenPathsTest -Dsurefire.failIfNoSpecifiedTests=false test` → FAIL (overload missing).

- [ ] **Step 3: Add the overload; make the existing `productRoot()` delegate**

Refactor `DedirenPaths` so the resolution logic (property → env → walk-up from a starting dir) operates on injected inputs, and `productRoot()` supplies the ambient ones. Keep the `requireProductRoot`/`isProductRoot` validation identical.

- [ ] **Step 4: Migrate the tests off `System.setProperty`**

Where `PluginRuntimeTest` set `user.dir`/`dediren.bundle.root` to control discovery, prefer the already-present explicit seams (`PluginRegistry.bundled(Map.of("DEDIREN_PLUGIN_DIRS", …))` at `:215`, or pass the root through the registry). Remove the `System.setProperty`/`restoreProperty(... )` blocks and the `restoreProperty` helper once unused.

> Investigate-before-migrating: two tests (`bundledRegistryUsesExplicitBundleRootOutsideCurrentWorkingDirectory`, `bundledRegistryResolvesDistributionExecutablesFromBundleBin`) assert the *fallback* `user.dir` walk and the `dediren.bundle.root` property specifically. Keep one focused test for each ambient path (they legitimately exercise the global resolver), but isolate them with `@org.junit.jupiter.api.parallel.Execution(SAME_THREAD)` (or a dedicated nested class) and convert the rest to the injected seam. Do not delete coverage of the ambient resolver — relocate it.

- [ ] **Step 5: Run + commit**

Run: `./mvnw -pl core,cli -am test` → PASS.
```bash
git add core/src/main/java/dev/dediren/core/DedirenPaths.java \
        core/src/test/java/dev/dediren/core/DedirenPathsTest.java \
        core/src/test/java/dev/dediren/core/plugins/PluginRuntimeTest.java
git commit -m "refactor(core): resolve product root from explicit inputs; de-global runtime tests"
```

---

## Phase 6: Close the archimate junction-rule coverage gap (`confirmed-mutation`)

PIT on `archimate` showed 60% line coverage / 28 NO_COVERAGE concentrated in `Archimate.isJunctionContainmentRelationship`, `validateJunctionReachableTargets`, `validateJunctionRelationshipSemantics`, `isJunctionContainerType` — the junction containment/reachability rules. The existing tests cover mixed/direction junctions but not containment/reachability.

### Task 9: Junction-containment / reachability tests

**Files:**
- Modify: `archimate/src/test/java/dev/dediren/archimate/ArchimateRelationshipRulesTest.java`

- [ ] **Step 1: Investigate the public junction API.** Read `archimate/src/main/java/dev/dediren/archimate/Archimate.java` for the public entrypoint that reaches `validateJunctionRelationshipSemantics`/`isJunctionContainmentRelationship` (likely the same `validate*` method the existing junction tests at `ArchimateRelationshipRulesTest:71-103` call), and note the `DEDIREN_ARCHIMATE_JUNCTION_*` codes those branches emit (containment vs reachability).

- [ ] **Step 2: Write failing tests** that construct (a) a junction-containment relationship that violates the containment rule and (b) a junction whose targets are unreachable, each asserting the specific `DEDIREN_ARCHIMATE_JUNCTION_*` code and JSON path — following the exact construction pattern of the existing junction tests in the same file (build the relationship triple → call the same `Archimate.validate…` entry → `assertThatThrownBy(...).isInstanceOf(ArchimateTypeValidationException.class)` and assert `.code()` + path). Add the matching valid (passing) control for each rule so the pair is `POS-5`.

- [ ] **Step 3: Run** `./mvnw -pl archimate -am -Dtest=ArchimateRelationshipRulesTest -Dsurefire.failIfNoSpecifiedTests=false test`. The tests pass if production already enforces these rules (the gap is coverage). If a rule is unenforced, that is a real defect — file it, do not weaken the test.

- [ ] **Step 4: Re-run mutation** `./mvnw -pl archimate -Pmutation org.pitest:pitest-maven:mutationCoverage` and confirm the four junction methods' NO_COVERAGE count dropped from the audit baseline (28).

- [ ] **Step 5: Commit**

```bash
git add archimate/src/test/java/dev/dediren/archimate/ArchimateRelationshipRulesTest.java
git commit -m "test(archimate): cover junction containment and reachability rules"
```

---

## Out of scope — recommended follow-on plans

Per the writing-plans scope rule, the following are separable subsystems that each warrant their own brainstorm + plan; they are **not** included as tasks here because they cannot be specified as complete, non-placeholder code without work this plan's scope does not cover.

1. **`render/Main.java` god-class extraction (`SD-B-1`) + render assertion-density (`33%` mutation strength).** This is the repo's primary design hotspot (3849 LOC, 99 methods) and the worst-verified surface — but extracting it is a large behavior-preserving refactor whose moved bodies cannot be pre-written here, and it should be protected by both the existing structural `MainTest` suite *and* new geometry oracles. **Entry conditions / target for its own plan:** introduce an owned `SvgRenderer` (geometry + style + ArchiMate-icon generation) behind the existing `UmlSequenceRenderer`/`RenderInputValidator` split, leaving `Main` a thin transport adapter (target ≤ ~400 LOC); raise render mutation test-strength by closing the ~180 logic-branch survivors (`NegateConditionals`/`ConditionalsBoundary`) and adding tolerance assertions on load-bearing geometry — **without** reintroducing pixel-pinning. Safety net: the structural suite is green and refactor-resistant, so the extraction is low-risk move-by-move.

2. **Full `DiagnosticCode` migration sweep (19 files / 95 codes).** Phase 1 establishes the owner and migrates the `core/plugins` + export-validator families. Migrating the remaining emitters (`Archimate`, `Uml`, `LayoutQuality`, `SourceValidator`, `CoreCommands`, the other plugin `Main`s) and converting `AgentUsageDocConsistencyTest` from a string-scan to an enum-completeness check is mechanical but broad; do it as one focused sweep with the recipe `"<LITERAL>"` → `DiagnosticCode.<CONST>.code()` and `grep -rE '"DEDIREN_[A-Z_]+"' */src/main` reaching zero as the exit condition.

3. **Fixture-loader consolidation sweep (16 test classes).** Phase 2 adds the owned `Fixtures` helper; migrating the 16 classes that roll their own `workspaceRoot()`/reader onto it is a separate mechanical task, gated by each module's tests staying green.

---

## Self-Review

- **Spec coverage:** render extraction → follow-on #1 (with entry conditions); `DiagnosticCode` enum → Phase 1 (+ sweep follow-on #2); `productRoot` injection → Phase 5; P0 diagnostic-gap tests incl. timeout → Phase 3; validator-unavailable → Phase 4; test-support fixture/envelope consolidation → Phase 2 (+ sweep follow-on #3); archimate junction coverage → Phase 6; render assertion-density → follow-on #1. **All listed findings are mapped.**
- **Placeholder scan:** the two `Investigate-before-writing` notes (Tasks 5, 6, 9) name the exact file:lines to read and give the load-bearing assertion verbatim; they are investigation steps, not unfinished code. The `exportWithEnv`/`xmiSchemaValidator` names are explicitly flagged as "match the real helper name in this file." No `TBD`/`handle edge cases`/`similar to` placeholders.
- **Type consistency:** `DiagnosticCode.<CONST>.code()` returns `String`, matching `diagnostic().code()` (a `String`) in every assertion; `PluginRunOptions.defaults().withCandidateEnv(...).withTimeout(Duration)` matches the verified record API; `PluginRunner.runForCapabilityWithRegistry(registry, id, capability, args, input, options)` matches the existing test calls; `CommandEnvelope.ok(JsonNode)` matches the testbed usage.
- **Verification:** every code task ends with a module-scoped `./mvnw` command (sandbox-disabled, with `-am`/`failIfNoSpecifiedTests` per the global constraints) and an explicit-path commit; two tasks add a PIT re-run to confirm the gap closed against the audit baseline.

# Test-Quality Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the actionable findings from the 2026-06-07 deep test-quality audit — fix the one material coverage gap (`SchemaAssertions`), de-characterize golden/geometry tests, replace floor-relative and "did-not-throw" oracles with contract-anchored ones, and remove non-hermetic / config-text tests.

**Architecture:** Pure test-quality work. Almost every task changes only `*Test.java` files; the SUT is treated as correct and is not modified except Task 1 (which fixes a genuinely misleading helper in `test-support` main). Each task strengthens an oracle, then proves the new oracle is meaningful with a one-shot mutation-sanity check (temporarily break the SUT, confirm the test fails, revert). Work is phased by audit priority so each phase is independently valuable and shippable.

**Tech Stack:** Java 21, Maven Wrapper (`./mvnw`), JUnit 5 (Jupiter), AssertJ, Jackson, networknt json-schema-validator. No Mockito — process-boundary plugins run for real.

**Conventions you must know:**
- Run a single test class: `./mvnw -pl <module> -am test -Dtest=ClassName`. Run one method: `-Dtest='ClassName#methodName'`.
- Tests run under Maven with `user.dir` set to the **module** directory, not the repo root. The established way to reach the repo root is to walk up from `user.dir` until a sentinel file (`schemas/model.schema.json`) is found. Task 1 centralizes this in `TestSupport.workspaceRoot()`.
- This repo allows direct commits to `main` (see `CLAUDE.md` → Git Hygiene). Commit per task with explicit paths; never `git add -A`.
- Whitespace gate: `git diff --check` must be clean before each commit.
- Do **not** edit files under `docs/superpowers/plans/` (frozen history) or any generated/`target/`/`dist/` output.

**Audit smell codes referenced** (from the audit report): `I-HC-B3` snapshot-only oracle, `HC-3` pasted/unexplained literals, `HC-2` self-referential round-trip, `HC-4` weak/echoed oracle, `I-HC-A11` non-hermetic seam, `HC-7` config/source-text characterization, `LC-10` floor-relative bound, `LC-2` weak assertion.

---

## File Structure

| File | Responsibility | Tasks |
|---|---|---|
| `test-support/src/main/java/dev/dediren/testsupport/TestSupport.java` | Resolve true repo root via sentinel (fix) | 1 |
| `test-support/src/test/java/dev/dediren/testsupport/TestSupportTest.java` | Pin repo-root + normalization contract | 1 |
| `test-support/src/test/java/dev/dediren/testsupport/SchemaAssertionsTest.java` | **New** — cover all 4 `SchemaAssertions` methods | 2 |
| `plugins/generic-graph/src/test/.../GenericGraphLayoutSizingTest.java` | Exact sizing oracle (not floor-relative) | 3 |
| `core/src/test/.../source/SourceValidatorTest.java` | Bundle-root oracle + isolation negative case | 4 |
| `cli/src/test/.../CliLayoutRenderCommandTest.java` | Split contract assertions from styling | 5 |
| `plugins/uml-xmi-export/src/test/.../umlxmi/MainTest.java` | Spec-named primary oracle over goldens | 6 |
| `plugins/svg-render/src/test/.../svgrender/MainTest.java` | Geometry de-characterization + policy round-trip break | 7, 8 |
| `plugins/elk-layout/src/test/.../ElkLayoutRenderArtifacts.java` (test helper), `ElkLayoutEngineTest.java`, `MainTest.java` | Opt-in render artifacts; behavioral capability oracle; accepted ELK-first guard | 9a, 9b, 9c |
| `plugins/elk-layout/src/test/.../LayoutJsonTest.java` | Remove self-referential round-trip | 10 |
| `uml/src/test/.../UmlValidationTest.java` | Parameterized multiplicity boundaries | 11 |
| various `*Test.java` | Inferred error-envelope partitions | 12 |
| `pom.xml` profile (optional) | PIT mutation coverage | 13 |

---

## Model Assignment (subagent-driven execution)

Lowest model tier that can handle each task. The plan bakes exact code/derivation
into every task so no task requires Opus-level design judgment; reviewers match
the implementer tier.

| Task | Implementer | Reviewers |
|---|---|---|
| 1 TestSupport repo-root fix | Haiku | Sonnet |
| 2 SchemaAssertionsTest | Haiku | Sonnet |
| 3 generic-graph exact sizing | Sonnet | Sonnet |
| 4 core bundle-root oracle | Sonnet | Sonnet |
| 5 CLI styling split | Haiku | Sonnet |
| 6 uml-xmi spec oracle | Sonnet | Sonnet |
| 7 svg-render geometry de-characterization | Sonnet | Sonnet |
| 8 svg-render policy round-trip (literal table) | Sonnet | Sonnet |
| 9a elk render artifacts opt-in | Haiku | Sonnet |
| 9b elk layered-only behavioral test | Sonnet | Sonnet |
| 9c elk route-geometry guard: accept + comment | Haiku | Sonnet |
| 10 dist-tool YAML trim | Sonnet | Sonnet |
| 11 elk self-referential round-trip delete | Haiku | Sonnet |
| 12 uml multiplicity parameterize | Sonnet | Sonnet |
| 13 inferred error-envelope partitions | Sonnet | Sonnet |
| 14 PIT mutation profile (config + run + report) | Sonnet | Sonnet |

Final whole-implementation review: Opus (the only Opus invocation).

---

## Phase 1 — P0: Close the coverage gap

### Task 1: Make `TestSupport.workspaceRoot()` resolve the real repository root

**Why:** `workspaceRoot()` currently returns `user.dir` (the module dir under Maven), so it does not point at the repo root and its only test (`isAbsolute()`) cannot fail (audit: `LC-2`, `HC-2`). Task 2 needs a correct repo root. Fix the helper to mirror the walk-up-to-sentinel convention every consumer already hand-rolls.

**Files:**
- Modify: `test-support/src/main/java/dev/dediren/testsupport/TestSupport.java`
- Test: `test-support/src/test/java/dev/dediren/testsupport/TestSupportTest.java`

- [ ] **Step 1: Replace the weak test with a contract-anchored one**

Replace the entire body of `TestSupportTest.java` with:

```java
package dev.dediren.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TestSupportTest {
    @Test
    void resolvesRepositoryRootContainingSchemaSentinel() {
        Path root = TestSupport.workspaceRoot();

        // The repo root is the directory that holds the schema sentinel, not the module dir.
        assertThat(root.resolve("schemas/model.schema.json")).exists();
        assertThat(Files.isDirectory(root)).isTrue();
    }

    @Test
    void returnsAbsoluteNormalizedPath() {
        Path root = TestSupport.workspaceRoot();

        assertThat(root).isAbsolute();
        assertThat(root).isEqualTo(root.normalize());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl test-support -am test -Dtest=TestSupportTest`
Expected: FAIL — `resolvesRepositoryRootContainingSchemaSentinel` fails because the current `workspaceRoot()` returns `.../test-support`, whose `schemas/model.schema.json` does not exist.

- [ ] **Step 3: Implement the walk-up resolution**

Replace the body of `TestSupport.java` with:

```java
package dev.dediren.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

public final class TestSupport {
    private static final String REPOSITORY_ROOT_SENTINEL = "schemas/model.schema.json";

    private TestSupport() {
    }

    /**
     * Resolves the repository root by walking up from the current working directory
     * (the module directory under Maven) until the schema sentinel is found.
     */
    public static Path workspaceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(REPOSITORY_ROOT_SENTINEL))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Could not locate repository root containing " + REPOSITORY_ROOT_SENTINEL);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -q -pl test-support -am test -Dtest=TestSupportTest`
Expected: PASS (both tests).

- [ ] **Step 5: Mutation-sanity check**

Temporarily change `REPOSITORY_ROOT_SENTINEL` to `"schemas/does-not-exist.json"`, rerun Step 4, and confirm `resolvesRepositoryRootContainingSchemaSentinel` now fails with `IllegalStateException`. Revert the change.

- [ ] **Step 6: Commit**

```bash
git add test-support/src/main/java/dev/dediren/testsupport/TestSupport.java \
        test-support/src/test/java/dev/dediren/testsupport/TestSupportTest.java
git commit -m "test: resolve real repo root in TestSupport.workspaceRoot and pin the contract"
```

---

### Task 2: Add `SchemaAssertionsTest` covering all four public methods

**Why:** `SchemaAssertions` (the contract-critical JSON-schema seam) has **zero in-module tests** — the suite's single most material gap. Cover `compile`, `validate`, `validateFixture`, `assertSchemaValid`, anchoring on real schemas/fixtures and the documented sorted-error contract.

**Grounding facts:** `schemas/model.schema.json` requires `model_schema_version`, `nodes`, `relationships`, `plugins` (so `{}` yields ≥4 errors → sortable). `fixtures/source/valid-basic.json` is schema-valid. The error paths return `List.of(error.getMessage())`. Use `TestSupport.workspaceRoot()` (fixed in Task 1) as `repositoryRoot`.

**Files:**
- Create: `test-support/src/test/java/dev/dediren/testsupport/SchemaAssertionsTest.java`

- [ ] **Step 1: Write the failing test**

Create the file with:

```java
package dev.dediren.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaAssertionsTest {
    private static final String MODEL_SCHEMA = "schemas/model.schema.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path root = TestSupport.workspaceRoot();

    @Test
    void compileReturnsEmptyForAValidSchemaFile() {
        assertThat(SchemaAssertions.compile(root, MODEL_SCHEMA)).isEmpty();
    }

    @Test
    void compileReturnsErrorMessageForMissingSchemaFile() {
        List<String> errors = SchemaAssertions.compile(root, "schemas/does-not-exist.schema.json");

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isNotBlank();
    }

    @Test
    void validateFixtureReturnsEmptyForASchemaValidDocument() {
        assertThat(SchemaAssertions.validateFixture(root, MODEL_SCHEMA, "fixtures/source/valid-basic.json"))
                .isEmpty();
    }

    @Test
    void validateReturnsSortedNonEmptyErrorsForADocumentMissingRequiredProperties() throws Exception {
        // {} violates every required property of model.schema.json -> multiple messages.
        JsonNode empty = MAPPER.readTree("{}");

        List<String> errors = SchemaAssertions.validate(root, MODEL_SCHEMA, empty);

        assertThat(errors).hasSizeGreaterThan(1);
        assertThat(errors).isSorted();
    }

    @Test
    void assertSchemaValidPassesForAValidDocumentAndFailsForAnInvalidOne() throws Exception {
        JsonNode valid = MAPPER.readTree("""
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [],
                  "relationships": [],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """);
        JsonNode invalid = MAPPER.readTree("{}");

        SchemaAssertions.assertSchemaValid(root, MODEL_SCHEMA, valid); // must not throw

        assertThatThrownBy(() -> SchemaAssertions.assertSchemaValid(root, MODEL_SCHEMA, invalid))
                .isInstanceOf(AssertionError.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it compiles and passes**

Run: `./mvnw -q -pl test-support -am test -Dtest=SchemaAssertionsTest`
Expected: PASS (5 tests). `schemas/model.schema.json` declares `required: [model_schema_version, nodes, relationships, plugins]` (4 properties), so `{}` deterministically yields ≥4 messages — the `hasSizeGreaterThan(1)` and `isSorted()` assertions are safe. Do not assert exact networknt message text (it is library-version-specific).

- [ ] **Step 3: Mutation-sanity check (proves the oracle bites)**

In `SchemaAssertions.validate`, temporarily remove `.sorted()` from the stream pipeline and rerun Step 2. Confirm `validateReturnsSortedNonEmptyErrorsForADocumentMissingRequiredProperties` still passes ONLY if the unsorted order happens to be sorted; to force the check, also temporarily reverse order with `.sorted(java.util.Comparator.reverseOrder())` and confirm the test now FAILS. Revert both changes.

- [ ] **Step 4: Re-run to confirm green after revert**

Run: `./mvnw -q -pl test-support -am test -Dtest=SchemaAssertionsTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add test-support/src/test/java/dev/dediren/testsupport/SchemaAssertionsTest.java
git commit -m "test: cover SchemaAssertions (compile/validate/validateFixture/assertSchemaValid)"
```

---

## Phase 2 — P1: Replace weak / characterization oracles

### Task 3: Assert an exact computed sizing value instead of a floor-relative bound

**Why:** `umlClassifierSizingUsesCompartmentText` asserts `> 220.0` / `>= 120.0`, which are the SUT's own `UML_STRUCTURAL_MIN_WIDTH/HEIGHT` floors — any mutation that still lands above the floor survives (`LC-10`). Assert exact values derived from the documented char-width/padding rule.

**Grounding (all constants/formulas confirmed from `GenericGraphLayoutSizing.java`):**
- `UML_TEXT_CHAR_WIDTH=8.0`, `UML_TEXT_HORIZONTAL_PADDING=32.0`, `UML_STRUCTURAL_MIN_WIDTH=220.0`, `UML_STRUCTURAL_MIN_HEIGHT=120.0`, `UML_TITLE_ROW_HEIGHT=15.0`, `UML_TITLE_PADDING=8.0`, `UML_MEMBER_ROW_HEIGHT=14.0`, `UML_COMPARTMENT_PADDING=8.0`, `UML_OPERATION_COMPARTMENT_EXTRA=14.0`.
- `roundUp(v, step) = Math.ceil(v/step)*step`.
- `width = roundUp(max(maxChars*8 + 32, 220), 20)` where `maxChars` is the longest of: stereotype line (null for `Class`), the label, and each attribute/operation line.
- `umlTitleHeight("Class") = max(1*15 + 8, 28) = 28.0` (titleLines=1 because `Class` has no stereotype; only Interface/DataType/Enumeration do).
- `umlCompartmentHeight(n) = n==0 ? 0 : n*14 + 8`. `operationExtra = 14` when operations>0.
- `height = roundUp(max(titleHeight + compartment(attrs) + compartment(ops) + operationExtra, 120), 10)`.
- **A label-only classifier yields height = 120 (the floor)** — so the test must include members to assert above the floor.

**Chosen test classifier:** label `"CustomerRepositoryGatewayA"` (26 chars, dominates width), 3 short attributes, 2 short operations (short names so member lines stay < 26 chars and do not affect width).
- width = `roundUp(max(26*8 + 32 = 240, 220), 20)` = **240.0**.
- height = `roundUp(max(28 + (3*14+8=50) + (2*14+8=36) + 14, 120), 10)` = `roundUp(max(128, 120), 10)` = **130.0**.

**Files:**
- Modify: `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizingTest.java`

- [ ] **Step 1: Replace `umlClassifierSizingUsesCompartmentText` with exact-value assertions**

```java
    @Test
    void umlClassifierSizingUsesCompartmentText() throws Exception {
        JsonNode uml = JsonSupport.objectMapper().readTree("""
                {
                  "attributes": [
                    {"visibility": "private", "name": "a", "type": "B"},
                    {"visibility": "private", "name": "c", "type": "D"},
                    {"visibility": "private", "name": "e", "type": "F"}
                  ],
                  "operations": [
                    {"visibility": "public", "name": "g", "return_type": "H"},
                    {"visibility": "public", "name": "i", "return_type": "J"}
                  ]
                }
                """);
        // 26-char label dominates width; 3 attrs + 2 ops push height above the 120 floor.
        SourceNode classifier = new SourceNode(
                "repository", "Class", "CustomerRepositoryGatewayA", Map.of("uml", uml));

        // width  = roundUp(max(26*8 + 32, 220), 20) = roundUp(240, 20) = 240.0
        // height = roundUp(max(28 + (3*14+8) + (2*14+8) + 14, 120), 10) = roundUp(128, 10) = 130.0
        assertThat(GenericGraphLayoutSizing.widthHint("uml", classifier)).isEqualTo(240.0);
        assertThat(GenericGraphLayoutSizing.heightHint("uml", classifier)).isEqualTo(130.0);
    }
```

Keep the existing `JsonNode`/`JsonSupport`/`Map` imports.

- [ ] **Step 2: If the run shows a different value, re-derive (do not blind-paste)**

If the height assertion fails showing 140.0, it means `umlStereotypeCharCount("Class")` is non-null (titleLines=2 → titleHeight=38 → height=`roundUp(150,10)`=150) — re-read `umlStereotypeCharCount`, recompute, and update the literal with a corrected comment. If width differs, a member line exceeded 26 chars — shorten the member names. Never replace the literal with the raw observed value without confirming it matches the formula.

- [ ] **Step 3: Run to verify it passes**

Run: `./mvnw -q -pl plugins/generic-graph -am test -Dtest='GenericGraphLayoutSizingTest'`
Expected: PASS. If `EXPECTED_TITLE_ONLY_HEIGHT` is wrong, the failure message prints the actual value — re-derive from the rule (do not just paste the actual value without confirming it matches the formula).

- [ ] **Step 4: Mutation-sanity check**

Temporarily change `UML_STRUCTURAL_MIN_WIDTH` to `200.0` in the SUT and confirm the width assertion is unaffected (240 > floor, proving we no longer test the floor); then temporarily change `UML_TEXT_HORIZONTAL_PADDING` to `30.0` and confirm the width assertion now FAILS (238 ≠ 240). Revert both.

- [ ] **Step 5: Commit**

```bash
git add plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizingTest.java
git commit -m "test: assert exact UML classifier sizing instead of floor-relative bound"
```

---

### Task 4: Strengthen the core bundle-root oracle and add an isolation negative case

**Why:** `validateSourceUsesExplicitBundleRootOutsideCurrentWorkingDirectory` only asserts `exitCode == 0`; it cannot distinguish "resolved from bundle.root" from "fell back to cwd" (`HC-4`, `I-HC-A11`). Assert the resolved envelope data, and add a negative case proving resolution actually depends on `dediren.bundle.root`.

**Grounding:** `ValidationResult` is `record(int exitCode, CommandEnvelope<JsonNode> envelope)`; `CommandEnvelope` exposes `.data()` (a `JsonNode`) and `.status()`. The OK data contains `model_schema_version`, `node_count`, `relationship_count`.

**Files:**
- Modify: `core/src/test/java/dev/dediren/core/source/SourceValidatorTest.java`

- [ ] **Step 1: Strengthen the positive test's assertions**

In `validateSourceUsesExplicitBundleRootOutsideCurrentWorkingDirectory`, replace the single `assertThat(result.exitCode()).isZero();` with:

```java
            assertThat(result.exitCode()).isZero();
            JsonNode data = result.envelope().data();
            assertThat(data.path("node_count").asInt()).isZero();
            assertThat(data.path("relationship_count").asInt()).isZero();
            assertThat(data.path("model_schema_version").asText()).isEqualTo("model.schema.v1");
```

Add the import `import com.fasterxml.jackson.databind.JsonNode;` if not present.

- [ ] **Step 2: Add the isolation negative test**

Add this method after the positive one:

```java
    @Test
    void validateSourceFailsWhenBundleRootIsAbsentAndCwdHasNoSchema() throws Exception {
        Path outsideBundle = temp.resolve("outside-bundle-no-schema");
        Files.createDirectories(outsideBundle); // deliberately no schemas/ dir
        String originalUserDir = System.getProperty("user.dir");
        String originalBundleRoot = System.getProperty("dediren.bundle.root");
        System.setProperty("user.dir", outsideBundle.toString());
        System.clearProperty("dediren.bundle.root");
        try {
            ValidationResult result = SourceValidator.validateSourceJson("""
                    {
                      "model_schema_version": "model.schema.v1",
                      "nodes": [],
                      "relationships": [],
                      "plugins": { "generic-graph": { "views": [] } }
                    }
                    """, null);

            // With no bundle root and no schema reachable from cwd, validation cannot succeed.
            assertThat(result.exitCode()).isNotZero();
        } finally {
            restoreProperty("user.dir", originalUserDir);
            restoreProperty("dediren.bundle.root", originalBundleRoot);
        }
    }
```

- [ ] **Step 3: Run to verify both pass**

Run: `./mvnw -q -pl core -am test -Dtest='SourceValidatorTest'`
Expected: PASS. If the negative test does not return non-zero, read `SourceValidator.validateSourceJson` to learn how it reports a missing schema, and assert the actual failure signal (a non-zero exit and an error-status envelope). Do not weaken to a tautology.

- [ ] **Step 4: Mutation-sanity check**

Temporarily make the positive test's expectation wrong (`node_count` == 1) and confirm it fails; revert. This proves the new oracle reads real envelope data.

- [ ] **Step 5: Commit**

```bash
git add core/src/test/java/dev/dediren/core/source/SourceValidatorTest.java
git commit -m "test: assert resolved envelope data and add bundle-root isolation negative case"
```

> **Note (carried forward, not fixed here):** this test mutates process-global `System.setProperty("user.dir"/"dediren.bundle.root")`. It is safe under single-threaded Surefire but unsafe under parallel execution. Isolating that global state is tracked as a follow-up in Task 14; do not enable Surefire parallelism without it.

---

### Task 5: Split stable contract assertions from styling internals in the CLI render test

**Why:** `renderCommandRunsJavaSvgPlugin` asserts svg-render styling literals (`font-size="15.4"`, `stroke="#ffffff"`, `fill="#374151"`) the policy fixture never set — characterizing another plugin's internals at the CLI seam (`I-HC-B1`, `I-HC-B3`, `I-LC-3`). Keep the stable `data-dediren-*` / artifact-kind contract assertions and the load-bearing negative clause; drop the styling pins.

**Grounding (current method tail, exact):**
```java
        assertThat(result.exitCode()).isZero();
        assertThat(envelope.at("/status").asText()).isEqualTo("ok");
        assertThat(envelope.at("/data/artifact_kind").asText()).isEqualTo("svg");
        assertThat(envelope.at("/data/content").asText())
                .contains("<svg", "data-dediren-node-id=\"client\"", "data-dediren-edge-id=\"client-calls-api\"");
        assertThat(envelope.at("/data/content").asText())
                .contains("fill=\"none\" font-size=\"15.4\" font-weight=\"600\" stroke=\"#ffffff\" stroke-width=\"2\">calls</text>")
                .contains("fill=\"#374151\" font-size=\"15.4\" font-weight=\"600\">calls</text>")
                .doesNotContain("data-dediren-edge-label-background");
```
The second `assertThat(...content...)` block pins svg-render styling internals (font-size/fill/stroke) — delete those two `.contains(...)` lines but **keep** the `.doesNotContain("data-dediren-edge-label-background")` contract clause by folding it onto the first content assertion.

**Files:**
- Modify: `cli/src/test/java/dev/dediren/cli/CliLayoutRenderCommandTest.java`

- [ ] **Step 1: Replace the two content-assertion blocks with this exact code**

```java
        assertThat(result.exitCode()).isZero();
        assertThat(envelope.at("/status").asText()).isEqualTo("ok");
        assertThat(envelope.at("/data/artifact_kind").asText()).isEqualTo("svg");
        assertThat(envelope.at("/data/content").asText())
                .contains("<svg", "data-dediren-node-id=\"client\"", "data-dediren-edge-id=\"client-calls-api\"")
                .doesNotContain("data-dediren-edge-label-background");
```

This deletes the `font-size=\"15.4\"` / `fill=\"#374151\"` styling pins and preserves the id/artifact-kind/status contract plus the negative clause.

- [ ] **Step 3: Run to verify it passes**

Run: `./mvnw -q -pl cli -am test -Dtest='CliLayoutRenderCommandTest#renderCommandRunsJavaSvgPlugin'`
Expected: PASS.

- [ ] **Step 4: Mutation-sanity check**

Confirm the kept negative clause still bites: temporarily make the test assert `.contains("data-dediren-edge-label-background")` and confirm it FAILS, then restore `doesNotContain`.

- [ ] **Step 5: Commit**

```bash
git add cli/src/test/java/dev/dediren/cli/CliLayoutRenderCommandTest.java
git commit -m "test: stop pinning svg-render styling internals in CLI render test"
```

---

### Task 6: Make OMG-spec-named assertions the primary oracle in uml-xmi-export

**Why:** `outputsXmi` and `exportsUmlSequenceCombinedFragments` use whole-document goldens as the sole/primary oracle with no schema provenance (in-pipeline XSD is `xsd:any lax`) — `I-HC-B3`, `I-LC-3`. Promote structural, spec-named assertions to primary; keep the golden as a regression backstop.

**Files:**
- Modify: `plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`

- [ ] **Step 1: Locate the golden-only assertions**

Run: `grep -n "isEqualTo(fixture\|fixture(\"fixtures/export\|containsSubsequence\|\\bcontains(" plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java`

- [ ] **Step 2: Add spec-named structural assertions before each golden**

For `exportsUmlSequenceCombinedFragments`, add (above the existing `isEqualTo(fixture(...))`):

```java
        assertThat(xml).containsSubsequence(
                "<uml:Interaction",
                "fragment",
                "<fragment xmi:type=\"uml:CombinedFragment\"",
                "interactionOperator=\"alt\"",
                "<operand xmi:type=\"uml:InteractionOperand\"");
```

For `outputsXmi`, add a `containsSubsequence` of the OMG-named structures the basic fixture must contain (open `fixtures/export/uml-basic.xmi` to read the exact element/attribute spellings, e.g. `<uml:Model`, `packagedElement`, `xmi:type="uml:Class"`). Use the spellings verbatim from the fixture's element names (these are the OMG contract, not styling).

- [ ] **Step 3: Demote the golden with a provenance comment**

Immediately above each surviving `assertThat(xml).isEqualTo(fixture(...))`, add:

```java
        // Regression backstop only; the spec-named assertions above are the primary oracle.
        // Update this golden via a reviewed baseline refresh when the XMI contract changes intentionally.
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q -pl plugins/uml-xmi-export -am test -Dtest='MainTest#exportsUmlSequenceCombinedFragments+outputsXmi'`
Expected: PASS.

- [ ] **Step 5: Mutation-sanity check**

Temporarily edit the SUT-produced XML check by changing one expected token in the `containsSubsequence` (e.g. `interactionOperator="alt"` → `"opt"`) and confirm the test FAILS independently of the golden line; revert.

- [ ] **Step 6: Commit**

```bash
git add plugins/uml-xmi-export/src/test/java/dev/dediren/plugins/umlxmi/MainTest.java
git commit -m "test: assert OMG-spec XMI structures as primary oracle, golden as backstop"
```

---

### Task 7: De-characterize svg-render route geometry literals

**Why:** `addsLineJumpForLaterCrossingEdge` and `keepsRoundedRouteCornersWhenAddingLineJumps` pin pasted bezier control points (e.g. `Q 106.0 100.0 100.0 106.0`) with no provenance (`I-HC-B3`, `HC-3`). Keep the structural line-jump-mask assertions; replace coordinate literals with a relational/invariant oracle.

**Grounding (current assertions, exact):**
`addsLineJumpForLaterCrossingEdge` (the earlier edge must NOT jump, the later crossing edge MUST, plus a white mask):
```java
            assertThat(firstPath.getAttribute("d")).doesNotContain(" Q ");
            assertThat(frontPath.getAttribute("d"))
                    .contains("L 100.0 94.0")
                    .contains("Q 106.0 100.0 100.0 106.0");
            Element masks = childGroupWithAttribute(frontEdge, "data-dediren-line-jump-masks", "front-edge");
            assertThat(firstChildElement(masks, "path").getAttribute("stroke")).isEqualTo("#ffffff");
```
`keepsRoundedRouteCornersWhenAddingLineJumps` (an L-shaped route that both rounds its bend and gains a jump):
```java
            assertThat(frontPath.getAttribute("d"))
                    .contains("Q 106.0 40.0 100.0 46.0")
                    .contains("L 100.0 92.0 Q 100.0 100.0 108.0 100.0");
```

**Files:**
- Modify: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [ ] **Step 1: Replace the `addsLineJumpForLaterCrossingEdge` geometry block with these exact assertions**

```java
            // Contract: the earlier edge does not jump; the later crossing edge gains a
            // quadratic jump arc; the jump carries a white mask. Coordinate-free oracle.
            assertThat(firstPath.getAttribute("d")).doesNotContain(" Q ");
            assertThat(frontPath.getAttribute("d")).contains(" Q ");
            Element masks = childGroupWithAttribute(frontEdge, "data-dediren-line-jump-masks", "front-edge");
            assertThat(firstChildElement(masks, "path").getAttribute("stroke")).isEqualTo("#ffffff");
```

- [ ] **Step 2: Replace the `keepsRoundedRouteCornersWhenAddingLineJumps` geometry block**

```java
            String frontD = frontPath.getAttribute("d");
            // The L-shaped route contributes one rounded corner (a Q segment) and the
            // crossing contributes at least one jump arc (another Q), so a route that lost
            // its rounding or its jump would fall below 2 quadratic segments.
            int quadraticSegments = frontD.split(" Q ", -1).length - 1;
            assertThat(quadraticSegments).isGreaterThanOrEqualTo(2);
```

- [ ] **Step 3: Run to verify it passes**

Run: `./mvnw -q -pl plugins/svg-render -am test -Dtest='MainTest#addsLineJumpForLaterCrossingEdge+keepsRoundedRouteCornersWhenAddingLineJumps'`
Expected: PASS. If counting `Q` commands is ambiguous, prefer asserting `>= 1` jump mask and the already-present structural count; do not reintroduce coordinate literals.

- [ ] **Step 4: Mutation-sanity check**

Confirm the kept mask assertion bites: temporarily strip the `data-dediren-line-jump-masks` emission expectation token and confirm the test fails; revert.

- [ ] **Step 5: Commit**

```bash
git add plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "test: assert line-jump/corner invariants instead of pasted bezier coordinates"
```

---

### Task 8: Break the policy-input style round-trips in svg-render

**Why:** `coversEachArchimateNodeTypeFromPolicy` and `coversEachUmlNodeTypeFromPolicy` re-read the same policy fixture fed to the SUT and assert fill/stroke/decorator equality — a round-trip that can only fail if the renderer drops the style entirely (`HC-2`, `HC-4`). Keep the requirement-derived expectations (the independent `expectedArchimateIconKind` mapping, the morphology check, the spec stereotype labels); replace the echo-from-input with assertions that do not read the rendered value back out of the live policy file. The `for`-loop stays (parameterization is intentionally **out of scope** to keep this change mechanical).

**Files:**
- Modify: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [ ] **Step 1: De-echo `coversEachArchimateNodeTypeFromPolicy`'s assertion loop**

Replace the second `for` loop body (the one that reads `expectedFill`/`expectedDecorator` and asserts `shape.getAttribute("fill")`/`"stroke"`/icon-size) with this — it drops the fill/stroke echo and the unanchored `"22"` icon-size, keeping the independent `expectedArchimateIconKind` mapping and morphology check:

```java
            index = 0;
            for (var fields = nodeStyles.fields(); fields.hasNext(); ) {
                var field = fields.next();
                String id = "archimate-node-" + index;
                String decoratorKind = field.getValue().at("/decorator").asText(); // navigation only
                Element node = groupWithAttribute(document, "data-dediren-node-id", id);
                if (!"archimate_and_junction".equals(decoratorKind)
                        && !"archimate_or_junction".equals(decoratorKind)) {
                    Element decorator = childGroupWithAttribute(node, "data-dediren-node-decorator", decoratorKind);
                    String expectedKind = expectedArchimateIconKind(field.getKey()); // requirement-derived, not echoed
                    assertThat(decorator.getAttribute("data-dediren-icon-kind")).isEqualTo(expectedKind);
                    assertDistinctArchimateIconMorphology(field.getKey(), expectedKind, decorator);
                }
                index++;
            }
```

- [ ] **Step 2: Build a frozen expectation table for the UML method**

The UML method's only per-type assertion echoes `data-dediren-node-shape` == the policy's `/decorator`. Replace that with a test-owned literal table. First read `fixtures/render-policy/uml-svg.json` and, for each entry under `/style/node_type_overrides` that is NOT a sequence node type, record its `/decorator` value. Add this constant to the test class (complete every non-sequence entry from the fixture):

```java
    // Frozen once from fixtures/render-policy/uml-svg.json (node_type_overrides[*].decorator).
    // Pinning here (not re-reading the live file) is the contract: an intentional policy change updates this map.
    private static final java.util.Map<String, String> EXPECTED_UML_NODE_SHAPES = java.util.Map.ofEntries(
            java.util.Map.entry("Class", "<decorator-value-from-fixture>")
            // ... one entry per non-sequence node type present in the fixture
    );
```

- [ ] **Step 3: Point the UML loop at the frozen table**

In `coversEachUmlNodeTypeFromPolicy`, replace the echo assertion `assertThat(shape.getAttribute("data-dediren-node-shape")).isEqualTo(expectedDecorator);` with:

```java
                assertThat(shape.getAttribute("data-dediren-node-shape"))
                        .isEqualTo(EXPECTED_UML_NODE_SHAPES.get(field.getKey()));
```

Keep the existing decorator-group existence check and the final `assertThat(content).contains("&#171;interface&#187;", "&#171;dataType&#187;", "&#171;enumeration&#187;");` stereotype assertion unchanged.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q -pl plugins/svg-render -am test -Dtest='MainTest'`
Expected: PASS (whole class, to confirm no helper regressions). If `EXPECTED_UML_NODE_SHAPES.get(...)` returns null for some type, you missed a fixture entry — add it.

- [ ] **Step 5: Mutation-sanity check**

Change one value in `EXPECTED_UML_NODE_SHAPES` to a wrong shape and confirm the UML test fails; revert. This proves the table is now the oracle (not the live file).

- [ ] **Step 6: Commit**

```bash
git add plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "test: replace svg-render policy style round-trips with requirement-derived oracles"
```

---

## Phase 3 — P2: Remove non-hermetic and config-text tests

> Tasks 9a–9c carry exact code. Tasks 10–12 include a short read step to locate exact lines, then a specified edit. Where a value must come from the SUT, read it — do not fabricate literals.

### Task 9a: Make elk render-artifact writing opt-in (hermetic by default)

**Why:** ~40 `ElkLayoutEngineTest` tests call `ElkLayoutRenderArtifacts.write(...)`, which boots the svg-render plugin and reads `fixtures/render-policy/default-svg.json` — yet no assertion consumes the SVG (`I-HC-A11`). `ElkLayoutRenderArtifacts` is a **test helper** (`src/test/...`), so this is a test-only change. Gate `write` to a no-op unless explicitly opted in; the helper remains a debug-only artifact dumper.

**Grounding (current helper, exact):** `write(LayoutResult)` wraps `writeSvg(...)`; `write(JsonNode)` converts and delegates to `write(LayoutResult)`.

**Files:**
- Modify: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutRenderArtifacts.java`

- [ ] **Step 1: Add the opt-in guard to `write(LayoutResult)`**

Replace the existing `write(LayoutResult)` method with:

```java
    static void write(LayoutResult result) {
        if (!Boolean.getBoolean("dediren.elk.render-artifacts")) {
            return; // debug-only; default test runs stay hermetic and do not boot svg-render
        }
        try {
            writeSvg(testMethodName(), result);
        } catch (Exception error) {
            throw new AssertionError("failed to write ELK layout SVG render artifact", error);
        }
    }
```

`write(JsonNode)` delegates to this method, so it becomes a no-op transitively — leave it unchanged.

- [ ] **Step 2: Run the module to confirm green and hermetic**

Run: `./mvnw -q -pl plugins/elk-layout -am test`
Expected: PASS. The ~40 `write(...)` calls now return immediately; svg-render is no longer booted from `ElkLayoutEngineTest`.

- [ ] **Step 3: Confirm the opt-in path still works (manual, optional)**

Run: `./mvnw -q -pl plugins/elk-layout -am test -Dtest='ElkLayoutEngineTest' -Ddediren.elk.render-artifacts=true`
Expected: PASS, and `.test-output/renders/elk-layout/*.svg` files appear. (This is a manual confirmation; do not add a CI test that sets the property, to keep CI hermetic.)

- [ ] **Step 4: Commit**

```bash
git add plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutRenderArtifacts.java
git commit -m "test: make elk render-artifact writing opt-in so layout tests stay hermetic"
```

---

### Task 9b: Replace the elk pom-grep with a behavioral capabilities assertion

**Why:** `elkModuleUsesLayeredOnly` reads `pom.xml`/`../../pom.xml` as strings and asserts absence of `org.eclipse.elk.alg.libavoid` (`HC-7`) — it would miss a transitive Libavoid inclusion. The behavioral contract ("only the layered backend is wired in") is observable from the `capabilities` command, which `MainTest.capabilitiesReportOfficialJavaElkRuntime` already exercises via `Main.executeForTesting(new String[]{"capabilities"}, "")`.

**Files:**
- Modify: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/MainTest.java`
- Modify: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java`

- [ ] **Step 1: Strengthen the capabilities test to prove no Libavoid backend**

In `MainTest.capabilitiesReportOfficialJavaElkRuntime`, after the existing `runtime.algorithms` assertions, add an assertion that no advertised algorithm mentions libavoid:

```java
        JsonNode algorithms = capabilities.path("runtime").path("algorithms");
        for (JsonNode algorithm : algorithms) {
            assertFalse(
                algorithm.asText().contains("libavoid"),
                "ELK runtime must not advertise a Libavoid backend algorithm");
        }
```

Ensure `import static org.junit.jupiter.api.Assertions.assertFalse;` is present (add if missing).

- [ ] **Step 2: Delete the pom-grep test**

Remove the entire `elkModuleUsesLayeredOnly` method (and its now-unused `Files`/`Path` imports only if no other test in `ElkLayoutEngineTest` uses them — `elkHelperDoesNotOwnPostElkRouteGeometry` still reads source, so keep them).

- [ ] **Step 3: Run to verify both files are green**

Run: `./mvnw -q -pl plugins/elk-layout -am test -Dtest='MainTest,ElkLayoutEngineTest'`
Expected: PASS.

- [ ] **Step 4: Mutation-sanity check**

Temporarily add `"org.eclipse.elk.alg.libavoid"` to the `algorithms` array in `Main.capabilitiesJson()` and confirm the new assertion FAILS; revert.

- [ ] **Step 5: Commit**

```bash
git add plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/MainTest.java \
        plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "test: assert no Libavoid backend via capabilities, drop pom source-text grep"
```

---

### Task 9c: Accept the ELK-first route-geometry guard with a clarifying comment

**Why:** `elkHelperDoesNotOwnPostElkRouteGeometry` asserts that `ElkLayoutEngine.java` source does not contain custom routing method names (`HC-7`). Although source-text, this is an intentional **architectural fitness function** enforcing the ELK-first rule in `CLAUDE.md` ("Do not duplicate layout or routing features already provided by ELK"). The audit allows accepting such a check with rationale rather than removing it. Document its intent so future readers do not mistake it for a characterization smell.

**Files:**
- Modify: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java`

- [ ] **Step 1: Add a rationale comment above `elkHelperDoesNotOwnPostElkRouteGeometry`**

```java
    // Architectural fitness function for the ELK-first rule (CLAUDE.md): the helper must not
    // re-implement post-ELK route geometry. Intentionally a source-text guard, not behavioral —
    // accepted per the 2026-06-07 test-quality audit. The geometry-outcome tests in this class
    // (route-through-node count, corner counts) prove the rendered result; this guard prevents
    // the prohibited code from being added in the first place.
    @Test
```

(Place the comment immediately above the existing `@Test` annotation for that method.)

- [ ] **Step 2: Run to verify green**

Run: `./mvnw -q -pl plugins/elk-layout -am test -Dtest='ElkLayoutEngineTest#elkHelperDoesNotOwnPostElkRouteGeometry'`
Expected: PASS (no behavior change).

- [ ] **Step 3: Commit**

```bash
git add plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "test: document ELK-first route-geometry guard as an accepted fitness function"
```

---

### Task 10: Trim the dist-tool release-workflow text characterization test

**Why:** `releaseWorkflowPublishesSingleJavaArchive` uses a hand-rolled YAML parser to assert ~30 verbatim `release.yml` lines (`I-HC-A6`, `HC-3`). The real "single platform-neutral java archive" invariant is already proven behaviorally by `buildProducesVersionOnlyJavaArchive`. Keep only the load-bearing negative guards.

**Files:**
- Modify: `dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

- [ ] **Step 1: Identify keep-vs-drop assertions**

Run: `grep -n "releaseWorkflowPublishesSingleJavaArchive\|doesNotContain\|matrix\|target\|upload-artifact\|yamlBlock\|workflowStep" dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java`

- [ ] **Step 2: Reduce the test to negative invariants**

Keep only the assertions that guard a real regression: no build matrix / no per-arch target tokens in the published archive name, and exactly one upload of one `.tar.gz`. Delete the verbatim-line assertions and the `yamlBlock`/`workflowStep*` helper machinery if nothing else uses it. Add a comment that workflow YAML linting is out of scope for this unit (a follow-up can add `actionlint` in CI).

- [ ] **Step 3: Run to verify it passes**

Run: `./mvnw -q -pl dist-tool -am test -Dtest='DistModuleTest'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add dist-tool/src/test/java/dev/dediren/tools/dist/DistModuleTest.java
git commit -m "test: keep dist-tool release-workflow negative invariants, drop verbatim YAML pinning"
```

---

### Task 11: Remove the self-referential elk-layout envelope round-trip

**Why:** `readsLayoutRequestAndWritesLayoutResultEnvelope` hand-builds a `LayoutResult`, round-trips it through `EnvelopeWriter.ok`, and asserts its own literal — Jackson symmetry only, no engine invoked (`HC-2`). It duplicates `MainTest.validRequestReturnsOkEnvelopeWithLayoutResult`.

**Files:**
- Modify/Delete: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutJsonTest.java`

- [ ] **Step 1: Confirm the duplication**

Run: `grep -n "readsLayoutRequestAndWritesLayoutResultEnvelope\|validRequestReturnsOkEnvelopeWithLayoutResult\|class LayoutJsonTest" plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutJsonTest.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/MainTest.java`
Read both to confirm `MainTest` drives the real engine to an OK envelope.

- [ ] **Step 2: Delete the round-trip test (or re-root it)**

If `MainTest.validRequestReturnsOkEnvelopeWithLayoutResult` covers the contract, delete `readsLayoutRequestAndWritesLayoutResultEnvelope`. If it is the only test in `LayoutJsonTest`, delete the whole file. If `LayoutJsonTest` also holds genuine JSON-parsing edge cases, keep those and remove only the round-trip method.

- [ ] **Step 3: Run to verify the module is still green**

Run: `./mvnw -q -pl plugins/elk-layout -am test`
Expected: PASS, with the duplicative test gone.

- [ ] **Step 4: Commit**

```bash
git add -u plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/
git commit -m "test: remove self-referential layout-result envelope round-trip (covered by MainTest)"
```

---

### Task 12: Parameterize UML multiplicity boundary coverage

**Why:** UML multiplicity tests accept `1..*/0..1/1/*` but reject only `2..1` (`LC-11`); empty, malformed, and negative-bound cases are uncovered.

**Files:**
- Modify: `uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`

- [ ] **Step 1: Find the current multiplicity tests and the validator API**

Run: `grep -n "multiplicity\|Multiplicity\|2\\.\\.1\|0\\.\\.1\|1\\.\\.\\*" uml/src/test/java/dev/dediren/uml/UmlValidationTest.java`
Read the method(s) to learn the exact validation entry point and the expected accept/reject signal (boolean, thrown exception, or diagnostic code).

- [ ] **Step 2: Add parameterized accept/reject cases**

Add a `@ParameterizedTest` with `@ValueSource(strings = {...})` for valid forms asserting acceptance, and another for invalid forms asserting rejection, using the same signal the existing `2..1` test uses. Invalid set must include at least: `""` (empty), `"abc"` (non-numeric), `"-1"` (negative), `"1.."` (malformed), `"2..1"` (inverted), `"1..0"`.

```java
    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "-1", "1..", "2..1", "1..0"})
    void rejectsInvalidMultiplicity(String multiplicity) {
        // assert rejection via the same mechanism the existing 2..1 test uses
    }
```

- [ ] **Step 3: Run to verify it passes**

Run: `./mvnw -q -pl uml -am test -Dtest='UmlValidationTest'`
Expected: PASS. If any "invalid" string is actually accepted by the grammar, that is a real SUT finding — stop and report it rather than deleting the case.

- [ ] **Step 4: Commit**

```bash
git add uml/src/test/java/dev/dediren/uml/UmlValidationTest.java
git commit -m "test: parameterize UML multiplicity boundary coverage (empty/malformed/negative)"
```

---

## Phase 4 — P3: Inferred gaps and mutation confirmation (optional, verify-first)

### Task 13: Add the inferred error-envelope partitions

**Why:** The gap report inferred missing negative coverage. Each is "add a contract oracle for a documented failure mode." Treat as a checklist; each sub-item is its own commit.

**Files (per sub-item):** the matching module's existing `*Test.java`.

These diagnostic constants are **confirmed present** in the SUT (verified during plan refinement): `DEDIREN_VALIDATE_PROFILE_REQUIRED`, `DEDIREN_VALIDATE_PLUGIN_REQUIRED`, `DEDIREN_ARCHIMATE_JUNCTION_DIRECTION_INCOMPLETE`, `DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED`, `DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED`, `DEDIREN_ARCHIMATE_GROUP_SOURCE_NOT_GROUPING`, `DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE`. The contracts `warning` status and schema-cache env-var precedence still need confirmation in Step 1.

- [ ] **Step 1: For each target, read the existing error test in that module and the SUT trigger**

For each sub-item, open the module's existing error-envelope test (e.g. core `PluginRuntimeTest`, archimate `ArchimateRelationshipRulesTest`, archimate-oef `MainTest`) to copy its exact setup/assertion style, and read the SUT to learn the input that triggers the diagnostic. Confirm the two unverified paths:

```bash
grep -rn "warning" contracts/src/main/java/dev/dediren/contracts/CommandEnvelope.java
grep -rn "XDG\|LOCALAPPDATA\|getenv\|System.getenv" schema-cache/src/main
```

If `warning` status is never produced by any command, skip that sub-item and note it.

- [ ] **Step 2: Add one negative test per confirmed path, mirroring the module's matrix style**

Each test drives the failure input and asserts the exact `DEDIREN_*` code, the error `path` (JSON-Pointer), and the non-zero exit. Worked example shape (adapt to the module's actual test helper and trigger):

```java
    @Test
    void validateWithoutPluginForProfileReturnsProfileRequiredError() throws Exception {
        // drive the documented failure (e.g. `validate --profile uml` with no --plugin)
        CliResult result = Main.executeForTesting(new String[]{"validate", "--profile", "uml", /* no --plugin */}, "", env);
        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isNotZero();
        assertThat(envelope.at("/status").asText()).isEqualTo("error");
        assertThat(envelope.at("/error/diagnostics/0/code").asText()).isEqualTo("DEDIREN_VALIDATE_PLUGIN_REQUIRED");
    }
```

(Confirm the exact envelope error JSON-Pointer shape from the module's existing error test before asserting.) Highest-value sub-item: **schema-cache env-var precedence** — assert explicit > XDG > LOCALAPPDATA > HOME with overlapping inputs.

- [ ] **Step 3: Run the affected module test, then commit per sub-item**

```bash
./mvnw -q -pl <module> -am test -Dtest='<ModuleTest>'
git add <module>/src/test/java/.../<ModuleTest>.java
git commit -m "test: add <diagnostic> error-envelope coverage"
```

---

### Task 14: Add a scoped PIT mutation profile and report survivors (config + run + report only)

**Why:** This audit was static-only. PIT confirms whether the no-throw oracles, round-trips, and `contains` assertions actually kill mutants. Scope here is **add the profile, run one module, and report survivors** — acting on survivors is a follow-up that may spawn new `[mutation]`-tagged tasks (out of scope for this task).

**Heads-up:** a worktree `.claude/worktrees/add-pitest` (branch `worktree-add-pitest`) already exists at the same base commit — a prior pitest attempt. **Inspect it first** (`git -C .claude/worktrees/add-pitest diff main`) and, if it already contains a working profile, port/reuse that instead of writing a new one. Do not delete that worktree.

**Files:**
- Modify: root `pom.xml` (add a `mutation` profile). Version-bump rules do **not** apply (no product version change).

- [ ] **Step 1: Check the existing pitest worktree**

Run: `git -C .claude/worktrees/add-pitest log --oneline -5 && git -C .claude/worktrees/add-pitest diff main -- pom.xml`
If it already has a usable profile, adapt it; otherwise proceed to Step 2.

- [ ] **Step 2: Add the `mutation` profile to root `pom.xml`**

Inside `<profiles>`, add (resolve both versions to the current latest stable on Maven Central and pin exact numbers — no ranges):

```xml
    <profile>
      <id>mutation</id>
      <build>
        <plugins>
          <plugin>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-maven</artifactId>
            <version>LATEST_STABLE</version>
            <dependencies>
              <dependency>
                <groupId>org.pitest</groupId>
                <artifactId>pitest-junit5-plugin</artifactId>
                <version>LATEST_STABLE</version>
              </dependency>
            </dependencies>
            <configuration>
              <targetClasses>
                <param>dev.dediren.uml.*</param>
                <param>dev.dediren.archimate.*</param>
                <param>dev.dediren.contracts.*</param>
                <param>dev.dediren.plugins.genericgraph.GenericGraphLayoutSizing</param>
                <param>dev.dediren.plugins.elklayout.*</param>
                <param>dev.dediren.plugins.svgrender.*</param>
                <param>dev.dediren.plugins.umlxmi.*</param>
              </targetClasses>
              <outputFormats>
                <param>HTML</param>
                <param>XML</param>
              </outputFormats>
              <timestampedReports>false</timestampedReports>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </profile>
```

- [ ] **Step 3: Run mutation analysis on one module**

Run: `./mvnw -pl uml -am -Pmutation org.pitest:pitest-maven:mutationCoverage`
Expected: a report under `uml/target/pit-reports/`. If the run errors on plugin resolution, the pinned versions are wrong — correct them from Maven Central and rerun.

- [ ] **Step 4: Report survivors (do not act on them here)**

Read `uml/target/pit-reports/.../mutations.xml`. In the task hand-off, list survived mutants in methods this plan touched or in no-throw acceptance tests, as a findings list for follow-up. Do not chase 100%; do not modify tests in this task.

- [ ] **Step 5: Commit the profile (reports are gitignored under `target/`)**

```bash
git add pom.xml
git commit -m "build: add scoped pitest mutation-coverage profile"
```

---

## Self-Review

- **Spec coverage:** Every worklist item from the audit maps to a task — P0 SchemaAssertions→Task 2, TestSupport→Task 1; P1 goldens→Task 6, svg-render geometry→Task 7, cli styling→Task 5, policy round-trips→Task 8, core bundle-root→Task 4, generic-graph sizing→Task 3; P2 elk hermeticity/greps→Tasks 9a/9b/9c, dist YAML→Task 10, elk round-trip→Task 11, uml multiplicity→Task 12; P3 error envelopes→Task 13, PIT→Task 14. The `moduleLoads` ceremony (P3 cosmetic) is intentionally **not** scheduled — the audit rated it low/info and "keep as cheap smoke checks."
- **Placeholder scan:** After refinement, Tasks 1, 2, 5, 7, 8, 9a–9c carry complete exact code. Remaining read steps (Task 3 height re-derivation only if the run disagrees, Task 6 fixture XML spellings, Task 8 UML decorator transcription, Tasks 10–13 locate-then-edit, Task 14 version pinning) are explicit procedures citing the exact file/method/formula — not vague TODOs. No `EXPECTED_*` symbolic constants remain (Task 3 now uses literal 240.0 / 130.0 with the derivation shown).
- **Type consistency:** `TestSupport.workspaceRoot()` (Task 1) returns the repo root used by Task 2. `ValidationResult.envelope().data()` / `.status()` and `CommandEnvelope.data()` (Task 4) match the read records. `SourceNode(id, type, label, properties)` constructor matches the existing sizing test usage (Task 3). `ElkLayoutRenderArtifacts` is confirmed a `src/test` helper (Task 9a). `Main.executeForTesting(String[], String)` exists in elk `Main` and is already used by `MainTest` (Task 9b).
- **Model downshift:** No task requires Opus; all 16 task-units are Haiku or Sonnet because design judgment (geometry invariants, the de-echo strategy, the layered-only behavioral oracle, the pitest config) was resolved during refinement and baked in. Opus is used once, for the final whole-implementation review.
```

# ELK Algorithm Field + Compatibility Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the public `algorithm` layout selector (accepting only `layered` for now) and build the cross-field compatibility gate that rejects layered-only knobs under a non-layered algorithm — the framework that will govern alternate algorithms in a later slice.

**Architecture:** Slice 4 of the umbrella spec `docs/superpowers/specs/2026-07-05-dediren-elk-layered-capability-vocabulary-design.md`, scoped per the user's decision to **gate-only, algorithms-later**. The `LayoutAlgorithm` contract enum is forward-ready (holds `layered` + the intended future values) so the compatibility gate is genuinely unit-testable now; the **public schema and boundary accept only `layered`**, so no half-working algorithm is exposed. The gate lives in `ElkLayoutEngine.validateLayoutPreferences` (already invoked in the layout flow) and is dormant in production (only `layered` reaches the engine via the string boundary) but complete and tested, ready to activate when a future slice widens the accepted-set.

**Tech Stack:** Java 21+ via the Maven Wrapper; Jackson 3 (`tools.jackson`) with global `SNAKE_CASE`; Eclipse ELK Layered 0.11.0; JUnit 5 + AssertJ; `SchemaAssertions` (networknt).

## Global Constraints

- **Public surface is `layered`-only.** Both schemas and the `LayoutJson` boundary accept `algorithm` ∈ `["layered"]`. Requesting any other value is rejected as an unsupported value (existing `DEDIREN_ELK_LAYOUT_FAILED` path). No new `DEDIREN_` code is introduced (avoids `AgentUsageDocConsistencyTest` churn).
- **`LayoutAlgorithm` enum is forward-ready:** `LAYERED, TREE, RADIAL, FORCE, STRESS, PACKED` (kebab `@JsonProperty` values). Only `LAYERED` is publicly reachable; the others exist so the gate's rejection path is testable and so the future algorithm slice is a boundary/schema widening. Document this divergence (enum broader than schema) in the enum or plan — it is intentional forward-compat, not an inconsistency.
- **The gate rejects layered-only knobs under a non-layered algorithm.** Layered-only = `cycle_breaking`, `layering`, `crossing`, `placement`, `compaction`, `high_degree_nodes`, `thoroughness`. NOT gated (algorithm-agnostic): `routing`, `direction`, `density`, `wrapping`, `mode`. The gate throws `IllegalArgumentException` (surfaces as `DEDIREN_ELK_LAYOUT_FAILED`), message naming the offending path and the `layered`-only restriction.
- **Behavior preservation:** in production `algorithm` is absent or `layered`, so the gate is a no-op and `ElkLayeredOptions` is unchanged (still wires the layered algorithm). Verified by the full elk-layout suite staying green.
- **`LayoutPreferences` grows 13→14 components** (append `algorithm` last). Keep FOUR convenience constructors so no call site breaks: 4-arg `(direction,density,wrapping,routing)`, 5-arg `(mode,…,routing)`, 9-arg `(…,placement)`, 13-arg `(…,thoroughness)` — all delegating to the 14-arg canonical with trailing nulls. Do NOT edit existing call sites. (This is the 5th constructor on this record — a known accumulating smell; `LayoutPreferences` stops growing after this slice since slice 5 is element-scoped. Do not refactor the constructor scheme in this slice.)
- **Jackson:** record components use global `SNAKE_CASE` (no `@JsonProperty` on components); enum constants carry `@JsonProperty`. 14-arg canonical is Jackson's entry point.
- **Schema id stays `model.schema.v1`.**
- **Env:** Maven tests need the sandbox DISABLED. Module runs need `-am -Dsurefire.failIfNoSpecifiedTests=false`. `./mvnw -Pquality spotless:apply` before every Java commit. Explicit-path staging only; never `git add -A`.

---

### Task 1: Contract — `LayoutAlgorithm` enum + `algorithm` field

**Files:**
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutAlgorithm.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutPreferences.java`
- Test: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`

**Interfaces produced (consumed by Tasks 3–4):** `LayoutPreferences.algorithm()` → `LayoutAlgorithm`; `LayoutAlgorithm.LAYERED` (JSON `"layered"`) plus forward values `TREE/RADIAL/FORCE/STRESS/PACKED`.

- [ ] **Step 1: Write the failing test**

Add to `ContractRoundTripTest`:
```java
  @Test
  void layoutPreferencesRoundTripsAlgorithm() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    assertThat(mapper.readValue("{\"algorithm\":\"layered\"}", LayoutPreferences.class).algorithm())
        .isEqualTo(LayoutAlgorithm.LAYERED);
    // Forward-ready values deserialize at the contract layer (the public boundary still restricts to layered).
    assertThat(mapper.readValue("{\"algorithm\":\"tree\"}", LayoutPreferences.class).algorithm())
        .isEqualTo(LayoutAlgorithm.TREE);
    assertThat(mapper.writeValueAsString(LayoutAlgorithm.LAYERED)).isEqualTo("\"layered\"");
  }
```
Add the `LayoutAlgorithm` import if the test package doesn't wildcard-import `dev.dediren.contracts.layout`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#layoutPreferencesRoundTripsAlgorithm -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `LayoutAlgorithm` and `algorithm()` do not exist (compile error).

- [ ] **Step 3: Create the enum**

`LayoutAlgorithm.java`:
```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Layout algorithm selector. Only {@link #LAYERED} is currently accepted by the public schemas and
 * the plugin boundary; the remaining constants are forward-ready vocabulary so the algorithm
 * compatibility gate can be validated ahead of exposing alternate algorithms.
 */
public enum LayoutAlgorithm {
  @JsonProperty("layered")
  LAYERED,

  @JsonProperty("tree")
  TREE,

  @JsonProperty("radial")
  RADIAL,

  @JsonProperty("force")
  FORCE,

  @JsonProperty("stress")
  STRESS,

  @JsonProperty("packed")
  PACKED
}
```

- [ ] **Step 4: Extend `LayoutPreferences` (14-arg canonical + four convenience constructors)**

Replace the whole body of `LayoutPreferences.java`:
```java
package dev.dediren.contracts.layout;

public record LayoutPreferences(
    LayoutMode mode,
    LayoutDirection direction,
    LayoutDensity density,
    LayoutWrapping wrapping,
    LayoutRoutingPreferences routing,
    LayoutCycleBreaking cycleBreaking,
    LayoutLayeringPreferences layering,
    LayoutCrossingPreferences crossing,
    LayoutPlacementPreferences placement,
    LayoutCompaction compaction,
    LayoutComponentsPreferences components,
    LayoutHighDegreeNodes highDegreeNodes,
    LayoutThoroughness thoroughness,
    LayoutAlgorithm algorithm) {

  public LayoutPreferences(
      LayoutDirection direction,
      LayoutDensity density,
      LayoutWrapping wrapping,
      LayoutRoutingPreferences routing) {
    this(
        null, direction, density, wrapping, routing, null, null, null, null, null, null, null, null,
        null);
  }

  public LayoutPreferences(
      LayoutMode mode,
      LayoutDirection direction,
      LayoutDensity density,
      LayoutWrapping wrapping,
      LayoutRoutingPreferences routing) {
    this(
        mode, direction, density, wrapping, routing, null, null, null, null, null, null, null, null,
        null);
  }

  public LayoutPreferences(
      LayoutMode mode,
      LayoutDirection direction,
      LayoutDensity density,
      LayoutWrapping wrapping,
      LayoutRoutingPreferences routing,
      LayoutCycleBreaking cycleBreaking,
      LayoutLayeringPreferences layering,
      LayoutCrossingPreferences crossing,
      LayoutPlacementPreferences placement) {
    this(
        mode, direction, density, wrapping, routing, cycleBreaking, layering, crossing, placement,
        null, null, null, null, null);
  }

  public LayoutPreferences(
      LayoutMode mode,
      LayoutDirection direction,
      LayoutDensity density,
      LayoutWrapping wrapping,
      LayoutRoutingPreferences routing,
      LayoutCycleBreaking cycleBreaking,
      LayoutLayeringPreferences layering,
      LayoutCrossingPreferences crossing,
      LayoutPlacementPreferences placement,
      LayoutCompaction compaction,
      LayoutComponentsPreferences components,
      LayoutHighDegreeNodes highDegreeNodes,
      LayoutThoroughness thoroughness) {
    this(
        mode, direction, density, wrapping, routing, cycleBreaking, layering, crossing, placement,
        compaction, components, highDegreeNodes, thoroughness, null);
  }
}
```

- [ ] **Step 5: Run the new test, full contracts suite, and elk-layout test-compile**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#layoutPreferencesRoundTripsAlgorithm -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl contracts -am test` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl plugins/elk-layout -am test-compile` (sandbox disabled) — Expected: BUILD SUCCESS (the 13-arg convenience constructor keeps slice-3's `ElkLayoutEngineTest` compiling).

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add contracts/src/main/java/dev/dediren/contracts/layout/LayoutAlgorithm.java contracts/src/main/java/dev/dediren/contracts/layout/LayoutPreferences.java contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java
git commit -m "Add LayoutAlgorithm contract type and algorithm preference"
```

---

### Task 2: Widen public schemas (algorithm: layered only)

**Files:**
- Modify: `schemas/model.schema.json` (`layoutPreferences` object)
- Modify: `schemas/layout-request.schema.json` (identical)
- Test: `contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java`

- [ ] **Step 1: Write the failing test**

Add to `SchemaValidatorTest`:
```java
  @Test
  void layoutRequestAcceptsLayeredAlgorithmAndRejectsOthers() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String template =
        """
        {
          "layout_request_schema_version": "layout-request.schema.v1",
          "view_id": "main",
          "nodes": [],
          "edges": [],
          "groups": [],
          "constraints": [],
          "layout_preferences": { "algorithm": "%s" }
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "layered"))))
        .describedAs("layered algorithm must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "tree"))))
        .describedAs("non-layered algorithm is not publicly exposed yet")
        .isNotEmpty();
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#layoutRequestAcceptsLayeredAlgorithmAndRejectsOthers -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `additionalProperties:false` rejects the unknown `algorithm` key, so the first assertion fails.

- [ ] **Step 3: Extend both schemas**

In BOTH `schemas/model.schema.json` and `schemas/layout-request.schema.json`, add one property to `layoutPreferences`. Put `algorithm` first among the properties for readability (add a comma after it):
```json
        "algorithm": { "enum": ["layered"] },
```
(No new `$def` is needed.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#layoutRequestAcceptsLayeredAlgorithmAndRejectsOthers -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#allPublicSchemasCompile -Dsurefire.failIfNoSpecifiedTests=false` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add schemas/model.schema.json schemas/layout-request.schema.json contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java
git commit -m "Accept layered algorithm selector in public schemas"
```

---

### Task 3: Boundary validation in `LayoutJson`

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutJson.java`
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutJsonTest.java`

- [ ] **Step 1: Write the failing test**

Add to `LayoutJsonTest`:
```java
  @Test
  void readsLayeredAlgorithm() throws Exception {
    String json =
        """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "constraints": [],
              "layout_preferences": { "algorithm": "layered" }
            }
            """;

    LayoutRequest request =
        LayoutJson.readLayoutRequest(new java.io.ByteArrayInputStream(json.getBytes()));

    assertEquals(LayoutAlgorithm.LAYERED, request.layoutPreferences().algorithm());
  }

  @Test
  void rejectsUnsupportedAlgorithmWithStructuredError() {
    String json =
        """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "constraints": [],
              "layout_preferences": { "algorithm": "tree" }
            }
            """;

    LayoutJson.LayoutPreferenceValidationException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            LayoutJson.LayoutPreferenceValidationException.class,
            () -> LayoutJson.readLayoutRequest(new java.io.ByteArrayInputStream(json.getBytes())));
    org.junit.jupiter.api.Assertions.assertTrue(
        ex.getMessage().contains("$.layout_preferences.algorithm"),
        "error must name the offending path, was: " + ex.getMessage());
  }
```
`rejectsUnsupportedAlgorithmWithStructuredError` is the TDD driver.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=LayoutJsonTest#rejectsUnsupportedAlgorithmWithStructuredError -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — before Step 3 the boundary does not check `algorithm`, so `"tree"` reaches Jackson which maps it to `LayoutAlgorithm.TREE` without error; `assertThrows` fails because nothing is thrown.

- [ ] **Step 3: Add null-rejection and value-rejection**

In `LayoutJson.rejectExplicitPreferenceNulls`, after the slice-3 graph-tuning block, add:
```java
    rejectNull(preferences.get("algorithm"), "$.layout_preferences.algorithm");
```

In `LayoutJson.rejectUnsupportedPreferenceValues`, after the slice-3 graph-tuning block, add:
```java
    rejectUnsupportedText(
        preferences.get("algorithm"), "$.layout_preferences.algorithm", Set.of("layered"));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=LayoutJsonTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS (all methods).

- [ ] **Step 5: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutJson.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutJsonTest.java
git commit -m "Restrict algorithm selector to layered at the boundary"
```

---

### Task 4: Algorithm-compatibility gate

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java`
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java` (ADDITIVE — new import + one `@Test` method)

**Interfaces:** consumes Task 1 types. Produces: `ElkLayoutEngine.validateLayoutPreferences` rejects layered-only knobs under a non-layered algorithm. Dormant in production (only `layered` reaches the engine via the boundary); testable via typed construction.

Note on test design: only the negative (rejection) case gets a dedicated test. The positive path — `layered` (or absent) algorithm permitting layered-only knobs — is already exercised exhaustively by the rest of this suite (every existing fixture uses layered/absent algorithm with various preferences), so a dedicated positive test would be redundant and would risk a false negative by laying out an empty graph. The negative test throws inside `validate()` before ELK runs, so it needs no real graph.

- [ ] **Step 1: Write the failing test**

In `ElkLayoutEngineTest.java`, add the `LayoutAlgorithm` import if absent:
```java
import dev.dediren.contracts.layout.LayoutAlgorithm;
```
(`LayoutRequest`, `LayoutLayeringPreferences`, `LayoutLayeringStrategy`, and the JUnit assertions are already imported/available in this test.)

Append this test:
```java
  @Test
  void nonLayeredAlgorithmRejectsLayeredOnlyPreferences() {
    LayoutPreferences prefs =
        new LayoutPreferences(
            null, null, null, null, null, null,
            new LayoutLayeringPreferences(LayoutLayeringStrategy.NETWORK_SIMPLEX),
            null, null, null, null, null, null,
            LayoutAlgorithm.TREE);
    LayoutRequest request =
        new LayoutRequest("layout-request.schema.v1", "main", null, null, null, null, prefs);

    IllegalArgumentException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> new ElkLayoutEngine().layout(request));
    org.junit.jupiter.api.Assertions.assertTrue(
        ex.getMessage().contains("layered"),
        "message should explain the layered-only restriction, was: " + ex.getMessage());
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#nonLayeredAlgorithmRejectsLayeredOnlyPreferences -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — no gate yet, so `layout(request)` does not throw at validation time (it proceeds toward ELK with a TREE-tagged but otherwise empty graph); `assertThrows(IllegalArgumentException.class, ...)` fails because the expected validation exception is not raised.

- [ ] **Step 3: Add the gate**

In `ElkLayoutEngine.java`, add the import:
```java
import dev.dediren.contracts.layout.LayoutAlgorithm;
```

Extend `validateLayoutPreferences` (which currently only calls `validateRoutingPreferences`) to also call the gate:
```java
  private static void validateLayoutPreferences(LayoutPreferences preferences, String path) {
    if (preferences == null) {
      return;
    }
    validateRoutingPreferences(preferences.routing(), path + ".routing");
    validateAlgorithmCompatibility(preferences, path);
  }
```

Add the gate methods:
```java
  private static void validateAlgorithmCompatibility(LayoutPreferences preferences, String path) {
    LayoutAlgorithm algorithm = preferences.algorithm();
    if (algorithm == null || algorithm == LayoutAlgorithm.LAYERED) {
      return;
    }
    rejectLayeredOnly(preferences.cycleBreaking() != null, path + ".cycle_breaking");
    rejectLayeredOnly(preferences.layering() != null, path + ".layering");
    rejectLayeredOnly(preferences.crossing() != null, path + ".crossing");
    rejectLayeredOnly(preferences.placement() != null, path + ".placement");
    rejectLayeredOnly(preferences.compaction() != null, path + ".compaction");
    rejectLayeredOnly(preferences.highDegreeNodes() != null, path + ".high_degree_nodes");
    rejectLayeredOnly(preferences.thoroughness() != null, path + ".thoroughness");
  }

  private static void rejectLayeredOnly(boolean present, String path) {
    if (present) {
      throw new IllegalArgumentException(path + " is only supported for the 'layered' algorithm");
    }
  }
```

- [ ] **Step 4: Run the new tests, then the full elk-layout suite**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#nonLayeredAlgorithmRejectsLayeredOnlyPreferences+layeredAlgorithmAllowsLayeredOnlyPreferences -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl plugins/elk-layout -am test` (sandbox disabled) — Expected: PASS (the gate is a no-op for existing layered/absent-algorithm fixtures).

- [ ] **Step 5: Confirm the test-file change is additive**

Run: `git diff -- plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java | grep '^-' | grep -v '^---'`
Expected: no output.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "Gate layered-only preferences behind the layered algorithm"
```

---

### Task 5: Document the algorithm selector

**Files:**
- Modify: `docs/features/layout.md` (add "### Algorithm" before "### Graph tuning")
- Modify: `README.md` and `docs/agent-usage.md` (one sentence each)

- [ ] **Step 1: Add the layout.md subsection**

Insert immediately before the `### Graph tuning` heading in `docs/features/layout.md`:
```markdown
### Algorithm

`layout_preferences.algorithm` selects the layout algorithm.

| Value | Algorithm |
| --- | --- |
| `layered` (default) | ELK Layered — the hierarchical, directed algorithm Dediren is built around. |

`layered` is currently the only supported value. The layering, crossing,
placement, compaction, high-degree-node, and thoroughness options apply only to
the `layered` algorithm; requesting them under a different algorithm is rejected.
Additional algorithms may be added in future releases.

```

- [ ] **Step 2: Add the one-line mentions**

In `README.md` and `docs/agent-usage.md`, in the same layout-preferences paragraph as the earlier slices, add: ``The `algorithm` option selects the layout algorithm; `layered` (the default) is currently the only supported value.``

- [ ] **Step 3: Verify whitespace**

Run: `git diff --check`
Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add docs/features/layout.md README.md docs/agent-usage.md
git commit -m "Document the layout algorithm selector"
```

---

## Final verification (after Tasks 1–5)

```bash
./mvnw test                                   # sandbox disabled — full integration
./mvnw -pl dist-tool -am verify -Pdist-smoke  # sandbox disabled
git diff --check
```

## Self-Review Notes

- **Spec coverage:** implements the `algorithm` row of the umbrella spec's graph-scoped vocabulary and the conditional-validity/gate mechanism (spec §Conditional validity), scoped to `layered`-only public exposure per the user's gate-only decision.
- **Deferred (documented, not lost):** the alternate algorithms `tree/radial/force/stress/packed` are forward-ready in the enum but not schema/boundary-accepted; exposing each (with per-algorithm real-render evidence, per spec §Risks) is a future slice that widens the accepted-set and activates the already-built gate.
- **Testability of the gate:** the gate's rejection path is exercised now via typed construction (`LayoutAlgorithm.TREE`) at the engine level, so it is not untested dead code, even though the string boundary keeps it dormant in production.
- **Constructor accumulation:** `LayoutPreferences` now carries four convenience constructors; this is the ceiling (slice 5 is element-scoped and does not extend this record). Flagged for a possible future builder refactor, out of scope here.
- **Threat model:** additive validated value on an existing field + an internal cross-field check; no new parser, trust boundary, or `DEDIREN_` code, so `docs/threat-model.md` needs no change.

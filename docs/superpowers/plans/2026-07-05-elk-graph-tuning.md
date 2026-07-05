# ELK Graph Tuning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Dediren-owned symbolic vocabulary for four ELK graph-tuning controls — compaction, connected-component separation/spacing, high-degree-node treatment, and thoroughness — mapping each to its ELK option internally.

**Architecture:** Slice 3 of the umbrella spec `docs/superpowers/specs/2026-07-05-dediren-elk-layered-capability-vocabulary-design.md`. Extends the existing `layout_preferences` block. Values are Dediren-owned enums (with a symbolic tier for numeric ELK options — never raw numbers in the contract), mapped to ELK constants only inside `elk-layout`. No `algorithm` gate yet (slice 4); knobs apply to the always-`layered` algorithm.

**Tech Stack:** Java 21+ via the Maven Wrapper; Jackson 3 (`tools.jackson`) with global `SNAKE_CASE`; Eclipse ELK Layered 0.11.0; JUnit 5 + AssertJ; `SchemaAssertions` (networknt).

## Global Constraints

- **No raw ELK names OR raw numeric ranges in public JSON.** `thoroughness` and `components.spacing` are symbolic tiers, not integers/doubles. ELK constants and the tier→number mapping live only in `plugins/elk-layout`.
- **Exact ELK targets (verified against ELK 0.11.0):**
  - `compaction` → `LayeredOptions.COMPACTION_POST_COMPACTION_STRATEGY` (type `GraphCompactionStrategy`): `off`→`NONE`, `left`→`LEFT`, `right`→`RIGHT`, `balanced`→`LEFT_RIGHT_CONSTRAINT_LOCKING`.
  - `components.separate` (Boolean) → `CoreOptions.SEPARATE_CONNECTED_COMPONENTS`.
  - `components.spacing` (symbolic) → `CoreOptions.SPACING_COMPONENT_COMPONENT` (Double): `compact`→`20.0`, `readable`→`40.0`, `spacious`→`60.0`.
  - `high_degree_nodes` → `LayeredOptions.HIGH_DEGREE_NODES_TREATMENT` (Boolean): `off`→`false`, `on`→`true`.
  - `thoroughness` (symbolic) → `LayeredOptions.THOROUGHNESS` (Integer): `low`→`3`, `normal`→`7` (ELK default), `high`→`21`.
- **Behavior preservation:** none of these five ELK options are set today. So EVERY field is conditional-set: only `setProperty` when the Dediren field is present; absent → ELK default, byte-identical to current output. Verified by the full elk-layout suite in Task 4.
- **`LayoutPreferences` grows 9→13 components.** Keep THREE convenience constructors so no existing call site breaks: 4-arg `(direction, density, wrapping, routing)`, 5-arg `(mode, direction, density, wrapping, routing)`, and 9-arg `(mode, direction, density, wrapping, routing, cycleBreaking, layering, crossing, placement)` — the 9-arg is used by slice-2's `ElkLayoutEngineTest`. All delegate to the 13-arg canonical with trailing nulls. Do NOT edit existing call sites.
- **Jackson:** record components rely on global `SNAKE_CASE` (no `@JsonProperty` on components); enum constants carry `@JsonProperty("<kebab>")`. The 13-arg canonical constructor is Jackson's entry point.
- **Schema id stays `model.schema.v1`.**
- **Env:** Maven tests need the sandbox DISABLED. Module runs need `-am -Dsurefire.failIfNoSpecifiedTests=false`. `./mvnw -Pquality spotless:apply` before every Java commit. Explicit-path staging only; never `git add -A`.

---

### Task 1: Contract types for graph tuning

**Files:**
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutCompaction.java`
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutComponentsSpacing.java`
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutComponentsPreferences.java`
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutHighDegreeNodes.java`
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutThoroughness.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutPreferences.java`
- Test: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`

**Interfaces produced (consumed by Tasks 3–4):**
- `LayoutPreferences.compaction()` → `LayoutCompaction`
- `LayoutPreferences.components()` → `LayoutComponentsPreferences` (`.separate()` → `Boolean`, `.spacing()` → `LayoutComponentsSpacing`)
- `LayoutPreferences.highDegreeNodes()` → `LayoutHighDegreeNodes`
- `LayoutPreferences.thoroughness()` → `LayoutThoroughness`

- [ ] **Step 1: Write the failing test**

Add to `ContractRoundTripTest`:
```java
  @Test
  void layoutPreferencesRoundTripsGraphTuning() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "compaction": "balanced",
          "components": { "separate": false, "spacing": "spacious" },
          "high_degree_nodes": "on",
          "thoroughness": "high"
        }
        """;
    LayoutPreferences prefs = mapper.readValue(json, LayoutPreferences.class);
    assertThat(prefs.compaction()).isEqualTo(LayoutCompaction.BALANCED);
    assertThat(prefs.components().separate()).isEqualTo(Boolean.FALSE);
    assertThat(prefs.components().spacing()).isEqualTo(LayoutComponentsSpacing.SPACIOUS);
    assertThat(prefs.highDegreeNodes()).isEqualTo(LayoutHighDegreeNodes.ON);
    assertThat(prefs.thoroughness()).isEqualTo(LayoutThoroughness.HIGH);
    assertThat(mapper.writeValueAsString(LayoutThoroughness.NORMAL)).isEqualTo("\"normal\"");
  }
```
Add any needed imports for the new types at the top of the test.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#layoutPreferencesRoundTripsGraphTuning -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — new types do not exist (compile error).

- [ ] **Step 3: Create the enums**

`LayoutCompaction.java`:
```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutCompaction {
  @JsonProperty("off")
  OFF,

  @JsonProperty("left")
  LEFT,

  @JsonProperty("right")
  RIGHT,

  @JsonProperty("balanced")
  BALANCED
}
```

`LayoutComponentsSpacing.java`:
```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutComponentsSpacing {
  @JsonProperty("compact")
  COMPACT,

  @JsonProperty("readable")
  READABLE,

  @JsonProperty("spacious")
  SPACIOUS
}
```

`LayoutHighDegreeNodes.java`:
```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutHighDegreeNodes {
  @JsonProperty("off")
  OFF,

  @JsonProperty("on")
  ON
}
```

`LayoutThoroughness.java`:
```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutThoroughness {
  @JsonProperty("low")
  LOW,

  @JsonProperty("normal")
  NORMAL,

  @JsonProperty("high")
  HIGH
}
```

- [ ] **Step 4: Create the sub-record**

`LayoutComponentsPreferences.java`:
```java
package dev.dediren.contracts.layout;

public record LayoutComponentsPreferences(Boolean separate, LayoutComponentsSpacing spacing) {}
```

- [ ] **Step 5: Extend `LayoutPreferences` (13-arg canonical + three convenience constructors)**

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
    LayoutThoroughness thoroughness) {

  public LayoutPreferences(
      LayoutDirection direction,
      LayoutDensity density,
      LayoutWrapping wrapping,
      LayoutRoutingPreferences routing) {
    this(
        null, direction, density, wrapping, routing, null, null, null, null, null, null, null,
        null);
  }

  public LayoutPreferences(
      LayoutMode mode,
      LayoutDirection direction,
      LayoutDensity density,
      LayoutWrapping wrapping,
      LayoutRoutingPreferences routing) {
    this(mode, direction, density, wrapping, routing, null, null, null, null, null, null, null, null);
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
        null, null, null, null);
  }
}
```
(Formatting will be normalized by `spotless:apply`; the exact line wrapping does not matter, the argument order does.)

- [ ] **Step 6: Run the new test, then the full contracts suite**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#layoutPreferencesRoundTripsGraphTuning -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl contracts -am test` (sandbox disabled) — Expected: PASS (no Jackson multi-constructor regression).

- [ ] **Step 7: Confirm elk-layout still test-compiles against the 13-arg record**

Run: `./mvnw -pl plugins/elk-layout -am test-compile` (sandbox disabled)
Expected: BUILD SUCCESS — proves the slice-2 `ElkLayoutEngineTest` 9-arg call sites still resolve via the 9-arg convenience constructor.

- [ ] **Step 8: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add contracts/src/main/java/dev/dediren/contracts/layout/ contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java
git commit -m "Add contract types for ELK graph tuning"
```

---

### Task 2: Widen public schemas

**Files:**
- Modify: `schemas/model.schema.json` (the `layoutPreferences` object + add one `$def`)
- Modify: `schemas/layout-request.schema.json` (identical changes)
- Test: `contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java`

- [ ] **Step 1: Write the failing test**

Add to `SchemaValidatorTest`:
```java
  @Test
  void layoutRequestAcceptsGraphTuningAndRejectsUnknown() throws Exception {
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
          "layout_preferences": {
            "compaction": "%s",
            "components": { "separate": false, "spacing": "spacious" },
            "high_degree_nodes": "on",
            "thoroughness": "high"
          }
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "balanced"))))
        .describedAs("valid graph-tuning must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "squish"))))
        .describedAs("unknown compaction must be rejected")
        .isNotEmpty();
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#layoutRequestAcceptsGraphTuningAndRejectsUnknown -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `additionalProperties:false` rejects the unknown keys, so the first assertion fails.

- [ ] **Step 3: Extend both schemas**

In BOTH `schemas/model.schema.json` and `schemas/layout-request.schema.json`, add four properties to `layoutPreferences` after `"placement"` (add a comma after the `"placement"` line):
```json
        "compaction": { "enum": ["off", "left", "right", "balanced"] },
        "components": { "$ref": "#/$defs/layoutComponentsPreferences" },
        "high_degree_nodes": { "enum": ["off", "on"] },
        "thoroughness": { "enum": ["low", "normal", "high"] }
```
and add one `$def` sibling (add a comma after the `layoutPlacementPreferences` def's closing brace):
```json
    "layoutComponentsPreferences": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "separate": { "type": "boolean" },
        "spacing": { "enum": ["compact", "readable", "spacious"] }
      }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#layoutRequestAcceptsGraphTuningAndRejectsUnknown -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#allPublicSchemasCompile -Dsurefire.failIfNoSpecifiedTests=false` — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add schemas/model.schema.json schemas/layout-request.schema.json contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java
git commit -m "Accept ELK graph-tuning preferences in public schemas"
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
  void readsGraphTuningPreferences() throws Exception {
    String json =
        """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "constraints": [],
              "layout_preferences": {
                "compaction": "left",
                "components": { "separate": true, "spacing": "compact" },
                "high_degree_nodes": "on",
                "thoroughness": "low"
              }
            }
            """;

    LayoutRequest request =
        LayoutJson.readLayoutRequest(new java.io.ByteArrayInputStream(json.getBytes()));

    assertEquals(LayoutCompaction.LEFT, request.layoutPreferences().compaction());
    assertEquals(Boolean.TRUE, request.layoutPreferences().components().separate());
    assertEquals(
        LayoutComponentsSpacing.COMPACT, request.layoutPreferences().components().spacing());
    assertEquals(LayoutHighDegreeNodes.ON, request.layoutPreferences().highDegreeNodes());
    assertEquals(LayoutThoroughness.LOW, request.layoutPreferences().thoroughness());
  }

  @Test
  void rejectsUnknownThoroughnessWithStructuredError() {
    String json =
        """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "constraints": [],
              "layout_preferences": { "thoroughness": "extreme" }
            }
            """;

    LayoutJson.LayoutPreferenceValidationException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            LayoutJson.LayoutPreferenceValidationException.class,
            () -> LayoutJson.readLayoutRequest(new java.io.ByteArrayInputStream(json.getBytes())));
    org.junit.jupiter.api.Assertions.assertTrue(
        ex.getMessage().contains("$.layout_preferences.thoroughness"),
        "error must name the offending path, was: " + ex.getMessage());
  }
```
`rejectsUnknownThoroughnessWithStructuredError` is the TDD driver.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=LayoutJsonTest#rejectsUnknownThoroughnessWithStructuredError -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — before Step 3 the boundary does not check `thoroughness`, so parsing reaches Jackson which throws the wrong exception type; `assertThrows(LayoutPreferenceValidationException.class, ...)` fails.

- [ ] **Step 3: Add null-rejection and value-rejection**

In `LayoutJson.rejectExplicitPreferenceNulls`, after the `placement` block added in slice 2, add:
```java
    rejectNull(preferences.get("compaction"), "$.layout_preferences.compaction");
    rejectNull(preferences.get("high_degree_nodes"), "$.layout_preferences.high_degree_nodes");
    rejectNull(preferences.get("thoroughness"), "$.layout_preferences.thoroughness");

    JsonNode components = preferences.get("components");
    if (components != null) {
      rejectNull(components, "$.layout_preferences.components");
      if (components.isObject()) {
        rejectNull(components.get("separate"), "$.layout_preferences.components.separate");
        rejectNull(components.get("spacing"), "$.layout_preferences.components.spacing");
      }
    }
```

In `LayoutJson.rejectUnsupportedPreferenceValues`, after the `placement` block added in slice 2, add:
```java
    rejectUnsupportedText(
        preferences.get("compaction"),
        "$.layout_preferences.compaction",
        Set.of("off", "left", "right", "balanced"));
    rejectUnsupportedText(
        preferences.get("high_degree_nodes"),
        "$.layout_preferences.high_degree_nodes",
        Set.of("off", "on"));
    rejectUnsupportedText(
        preferences.get("thoroughness"),
        "$.layout_preferences.thoroughness",
        Set.of("low", "normal", "high"));

    JsonNode components = preferences.get("components");
    if (components != null && components.isObject()) {
      rejectUnsupportedText(
          components.get("spacing"),
          "$.layout_preferences.components.spacing",
          Set.of("compact", "readable", "spacious"));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=LayoutJsonTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS (all `LayoutJsonTest` methods).

- [ ] **Step 5: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutJson.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutJsonTest.java
git commit -m "Validate ELK graph-tuning preferences at the boundary"
```

---

### Task 4: Map graph tuning to ELK options

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayeredOptions.java`
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java` (ADDITIVE — new imports + one `@Test` method only)

**Interfaces:** consumes Task 1 types. Produces: the ELK root reflects requested graph-tuning options; absent fields preserve today's behavior (nothing set).

- [ ] **Step 1: Write the failing test**

In `ElkLayoutEngineTest.java`, add import if absent:
```java
import org.eclipse.elk.alg.layered.options.GraphCompactionStrategy;
```
(`LayeredOptions`, `CoreOptions` are already imported from slices 1–2.)

Append this test:
```java
  @Test
  void layeredRootMapsGraphTuningToElkOptions() {
    LayoutPreferences prefs =
        new LayoutPreferences(
            null, null, null, null, null, null, null, null, null,
            LayoutCompaction.BALANCED,
            new LayoutComponentsPreferences(Boolean.FALSE, LayoutComponentsSpacing.SPACIOUS),
            LayoutHighDegreeNodes.ON,
            LayoutThoroughness.HIGH);
    ElkNode root = ElkLayeredOptions.configuredRoot(Direction.RIGHT, prefs);

    assertEquals(
        GraphCompactionStrategy.LEFT_RIGHT_CONSTRAINT_LOCKING,
        root.getProperty(LayeredOptions.COMPACTION_POST_COMPACTION_STRATEGY));
    assertEquals(
        Boolean.FALSE, root.getProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS));
    assertEquals(
        Double.valueOf(60.0), root.getProperty(CoreOptions.SPACING_COMPONENT_COMPONENT));
    assertEquals(Boolean.TRUE, root.getProperty(LayeredOptions.HIGH_DEGREE_NODES_TREATMENT));
    assertEquals(Integer.valueOf(21), root.getProperty(LayeredOptions.THOROUGHNESS));
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#layeredRootMapsGraphTuningToElkOptions -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — these properties are not set today.

- [ ] **Step 3: Add the mapping**

In `ElkLayeredOptions.java`, add import:
```java
import dev.dediren.contracts.layout.LayoutComponentsPreferences;
import org.eclipse.elk.alg.layered.options.GraphCompactionStrategy;
```

At the end of `configureRoot` (after the slice-2 phase-strategy block), add:
```java
    GraphCompactionStrategy compaction = compactionStrategy(preferences);
    if (compaction != null) {
      root.setProperty(LayeredOptions.COMPACTION_POST_COMPACTION_STRATEGY, compaction);
    }
    Boolean separateComponents = componentsSeparate(preferences);
    if (separateComponents != null) {
      root.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, separateComponents);
    }
    Double componentSpacing = componentsSpacing(preferences);
    if (componentSpacing != null) {
      root.setProperty(CoreOptions.SPACING_COMPONENT_COMPONENT, componentSpacing);
    }
    Boolean highDegree = highDegreeNodes(preferences);
    if (highDegree != null) {
      root.setProperty(LayeredOptions.HIGH_DEGREE_NODES_TREATMENT, highDegree);
    }
    Integer thoroughness = thoroughness(preferences);
    if (thoroughness != null) {
      root.setProperty(LayeredOptions.THOROUGHNESS, thoroughness);
    }
```

Add these helper methods to the class:
```java
  private static GraphCompactionStrategy compactionStrategy(LayoutPreferences preferences) {
    if (preferences == null || preferences.compaction() == null) {
      return null;
    }
    return switch (preferences.compaction()) {
      case OFF -> GraphCompactionStrategy.NONE;
      case LEFT -> GraphCompactionStrategy.LEFT;
      case RIGHT -> GraphCompactionStrategy.RIGHT;
      case BALANCED -> GraphCompactionStrategy.LEFT_RIGHT_CONSTRAINT_LOCKING;
    };
  }

  private static Boolean componentsSeparate(LayoutPreferences preferences) {
    LayoutComponentsPreferences components =
        preferences == null ? null : preferences.components();
    return components == null ? null : components.separate();
  }

  private static Double componentsSpacing(LayoutPreferences preferences) {
    LayoutComponentsPreferences components =
        preferences == null ? null : preferences.components();
    if (components == null || components.spacing() == null) {
      return null;
    }
    return switch (components.spacing()) {
      case COMPACT -> 20.0;
      case READABLE -> 40.0;
      case SPACIOUS -> 60.0;
    };
  }

  private static Boolean highDegreeNodes(LayoutPreferences preferences) {
    if (preferences == null || preferences.highDegreeNodes() == null) {
      return null;
    }
    return switch (preferences.highDegreeNodes()) {
      case OFF -> Boolean.FALSE;
      case ON -> Boolean.TRUE;
    };
  }

  private static Integer thoroughness(LayoutPreferences preferences) {
    if (preferences == null || preferences.thoroughness() == null) {
      return null;
    }
    return switch (preferences.thoroughness()) {
      case LOW -> 3;
      case NORMAL -> 7;
      case HIGH -> 21;
    };
  }
```

- [ ] **Step 4: Run the new test, then the full elk-layout suite**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#layeredRootMapsGraphTuningToElkOptions -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl plugins/elk-layout -am test` (sandbox disabled) — Expected: PASS. If any existing test fails, STOP and report BLOCKED (do not edit fixtures) — it would mean an absent-field path changed behavior.

- [ ] **Step 5: Confirm the test-file change is additive**

Run: `git diff -- plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java | grep '^-' | grep -v '^---'`
Expected: no output.

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayeredOptions.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "Map ELK graph-tuning preferences to ELK options"
```

---

### Task 5: Document graph tuning

**Files:**
- Modify: `docs/features/layout.md` (add "### Graph tuning" before "### Layered phase strategies")
- Modify: `README.md` and `docs/agent-usage.md` (one sentence each)

- [ ] **Step 1: Add the layout.md subsection**

Insert immediately before the `### Layered phase strategies` heading in `docs/features/layout.md`:
```markdown
### Graph tuning

Optional graph-level tuning under `layout_preferences`. Omitted options keep
ELK's defaults. Numeric ELK options are exposed as symbolic tiers, never raw
numbers.

| Option | Values | Controls |
| --- | --- | --- |
| `compaction` | `off`, `left`, `right`, `balanced` | Post-layout horizontal compaction of the drawing. |
| `components.separate` | `true`, `false` | Whether disconnected components are laid out separately. |
| `components.spacing` | `compact`, `readable`, `spacious` | Gap between separated components. |
| `high_degree_nodes` | `off`, `on` | Special treatment for nodes with many edges. |
| `thoroughness` | `low`, `normal`, `high` | How hard ELK works to improve the layout (more thorough = slower). |

```

- [ ] **Step 2: Add the one-line mentions**

In `README.md` and `docs/agent-usage.md`, in the same layout-preferences paragraph that mentions `routing.style` and the phase strategies, add: ``Graph tuning (`compaction`, `components`, `high_degree_nodes`, `thoroughness`) is also configurable under `layout_preferences`; see the Layout feature page.``

- [ ] **Step 3: Verify whitespace**

Run: `git diff --check`
Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add docs/features/layout.md README.md docs/agent-usage.md
git commit -m "Document ELK graph tuning"
```

---

## Final verification (after Tasks 1–5)

```bash
./mvnw test                                   # sandbox disabled — full integration
./mvnw -pl dist-tool -am verify -Pdist-smoke  # sandbox disabled
git diff --check
```

## Self-Review Notes

- **Spec coverage:** implements the `compaction`, `components.separate`, `components.spacing`, `high_degree_nodes`, and `thoroughness` rows of the umbrella spec's graph-scoped vocabulary (slice 3).
- **Symbolic tiers:** `thoroughness` (low/normal/high → 3/7/21) and `components.spacing` (compact/readable/spacious → 20/40/60) keep raw ELK numbers out of the contract, per spec §Risks. `normal`/tier values are Dediren-chosen; `normal`=7 matches ELK's thoroughness default.
- **Behavior preservation:** all five options are conditional-set (none pinned today); absent → ELK default. Verified by the full elk-layout suite in Task 4.
- **No conditional validity yet:** the `algorithm` gate arrives in slice 4; these knobs are layered-relevant and applied unconditionally until then.
- **Threat model:** additive accepted values on an existing validated block; no new parser/trust boundary, so `docs/threat-model.md` needs no change.

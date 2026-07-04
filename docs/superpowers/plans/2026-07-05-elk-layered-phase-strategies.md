# ELK Layered Phase Strategies Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Dediren-owned symbolic vocabulary for four ELK Layered phase strategies — cycle breaking, layer assignment, crossing minimization (+ greedy switch), and node placement — mapping each to its ELK option internally.

**Architecture:** Slice 2 of the umbrella spec `docs/superpowers/specs/2026-07-05-dediren-elk-layered-capability-vocabulary-design.md`. Extends the existing `layout_preferences` block with four new graph-scoped controls. Values are Dediren-owned enums (kebab-case), validated by Dediren and mapped to `org.eclipse.elk.alg.layered.options.*` constants only inside `elk-layout`. No `algorithm` field yet (slice 4), so these knobs apply unconditionally to the always-`layered` algorithm; no cross-field validity is needed.

**Tech Stack:** Java 21+ via the Maven Wrapper; Jackson 3 (`tools.jackson`) with a global `SNAKE_CASE` naming strategy; Eclipse ELK Layered 0.11.0; JUnit 5 + AssertJ; `SchemaAssertions` (networknt).

## Global Constraints

- **No raw ELK names in public JSON.** ELK enum constants appear only in `plugins/elk-layout`. Public values are Dediren kebab-case enums.
- **Exact ELK targets (verified against ELK 0.11.0):**
  - `cycle_breaking` → `LayeredOptions.CYCLE_BREAKING_STRATEGY` : `greedy`→`GREEDY`, `depth-first`→`DEPTH_FIRST`, `model-order`→`MODEL_ORDER`.
  - `layering.strategy` → `LayeredOptions.LAYERING_STRATEGY` : `network-simplex`→`NETWORK_SIMPLEX`, `longest-path`→`LONGEST_PATH`, `coffman-graham`→`COFFMAN_GRAHAM`, `min-width`→`MIN_WIDTH`, `stretch-width`→`STRETCH_WIDTH`, `breadth-first`→`BF_MODEL_ORDER`, `depth-first`→`DF_MODEL_ORDER`.
  - `crossing.strategy` → `LayeredOptions.CROSSING_MINIMIZATION_STRATEGY` : `layer-sweep`→`LAYER_SWEEP`, `none`→`NONE`.
  - `crossing.greedy_switch` → `LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_TYPE` : `off`→`OFF`, `one-sided`→`ONE_SIDED`, `two-sided`→`TWO_SIDED`.
  - `placement.strategy` → `LayeredOptions.NODE_PLACEMENT_STRATEGY` : `brandes-koepf`→`BRANDES_KOEPF`, `network-simplex`→`NETWORK_SIMPLEX`, `linear-segments`→`LINEAR_SEGMENTS`, `simple`→`SIMPLE`.
- **Behavior preservation:** when a field is ABSENT, the ELK property must be left exactly as today. cycle_breaking / layering / crossing.strategy / crossing.greedy_switch are currently UNSET (ELK defaults) — so only `setProperty` when the Dediren field is present. `NODE_PLACEMENT_STRATEGY` is currently ALWAYS set to `BRANDES_KOEPF` — so placement defaults to `BRANDES_KOEPF` when absent. This keeps all existing fixtures and the pristine-routing invariant tests byte-identical.
- **`LayoutPreferences` must stay backward-compatible.** All 14 existing `new LayoutPreferences(...)` call sites (all in `ElkLayoutEngineTest.java`) use the 4-arg `(direction, density, wrapping, routing)` or 5-arg `(mode, direction, density, wrapping, routing)` forms. Keep BOTH as explicit constructors delegating to the new 9-arg canonical with nulls. Do NOT edit those call sites.
- **Jackson:** record components rely on the global `SNAKE_CASE` strategy (no `@JsonProperty` on components). Enum constants carry `@JsonProperty("<kebab-value>")` exactly like `LayoutRoutingStyle`. The 9-arg canonical constructor is Jackson's entry point; the two shorter constructors are Java convenience only.
- **Schema id stays `model.schema.v1`** (additive optional fields).
- **Env:** Maven tests need the sandbox DISABLED. Module runs need `-am -Dsurefire.failIfNoSpecifiedTests=false`. Run `./mvnw -Pquality spotless:apply` before every Java commit. Explicit-path staging only; never `git add -A`.

---

### Task 1: Contract types for the four phase strategies

**Files:**
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutCycleBreaking.java`
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutLayeringStrategy.java`
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutLayeringPreferences.java`
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutCrossingStrategy.java`
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutGreedySwitch.java`
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutCrossingPreferences.java`
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutPlacementStrategy.java`
- Create: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutPlacementPreferences.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutPreferences.java`
- Test: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`

**Interfaces produced (consumed by Tasks 3–4):**
- `LayoutPreferences.cycleBreaking()` → `LayoutCycleBreaking`
- `LayoutPreferences.layering()` → `LayoutLayeringPreferences` (`.strategy()` → `LayoutLayeringStrategy`)
- `LayoutPreferences.crossing()` → `LayoutCrossingPreferences` (`.strategy()` → `LayoutCrossingStrategy`, `.greedySwitch()` → `LayoutGreedySwitch`)
- `LayoutPreferences.placement()` → `LayoutPlacementPreferences` (`.strategy()` → `LayoutPlacementStrategy`)

- [ ] **Step 1: Write the failing test**

Add to `ContractRoundTripTest`:

```java
  @Test
  void layoutPreferencesRoundTripsPhaseStrategies() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    String json =
        """
        {
          "cycle_breaking": "model-order",
          "layering": { "strategy": "coffman-graham" },
          "crossing": { "strategy": "layer-sweep", "greedy_switch": "two-sided" },
          "placement": { "strategy": "network-simplex" }
        }
        """;
    LayoutPreferences prefs = mapper.readValue(json, LayoutPreferences.class);
    assertThat(prefs.cycleBreaking()).isEqualTo(LayoutCycleBreaking.MODEL_ORDER);
    assertThat(prefs.layering().strategy()).isEqualTo(LayoutLayeringStrategy.COFFMAN_GRAHAM);
    assertThat(prefs.crossing().strategy()).isEqualTo(LayoutCrossingStrategy.LAYER_SWEEP);
    assertThat(prefs.crossing().greedySwitch()).isEqualTo(LayoutGreedySwitch.TWO_SIDED);
    assertThat(prefs.placement().strategy()).isEqualTo(LayoutPlacementStrategy.NETWORK_SIMPLEX);
    assertThat(mapper.writeValueAsString(LayoutLayeringStrategy.NETWORK_SIMPLEX))
        .isEqualTo("\"network-simplex\"");
  }
```

Add the imports it needs at the top of the test (alongside the existing `dev.dediren.contracts.layout.*` imports — if that package is wildcard-imported already, none are needed; otherwise import the five new types).

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#layoutPreferencesRoundTripsPhaseStrategies -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — the new types do not exist (compile error).

- [ ] **Step 3: Create the enums**

`LayoutCycleBreaking.java`:
```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutCycleBreaking {
  @JsonProperty("greedy")
  GREEDY,

  @JsonProperty("depth-first")
  DEPTH_FIRST,

  @JsonProperty("model-order")
  MODEL_ORDER
}
```

`LayoutLayeringStrategy.java`:
```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutLayeringStrategy {
  @JsonProperty("network-simplex")
  NETWORK_SIMPLEX,

  @JsonProperty("longest-path")
  LONGEST_PATH,

  @JsonProperty("coffman-graham")
  COFFMAN_GRAHAM,

  @JsonProperty("min-width")
  MIN_WIDTH,

  @JsonProperty("stretch-width")
  STRETCH_WIDTH,

  @JsonProperty("breadth-first")
  BREADTH_FIRST,

  @JsonProperty("depth-first")
  DEPTH_FIRST
}
```

`LayoutCrossingStrategy.java`:
```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutCrossingStrategy {
  @JsonProperty("layer-sweep")
  LAYER_SWEEP,

  @JsonProperty("none")
  NONE
}
```

`LayoutGreedySwitch.java`:
```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutGreedySwitch {
  @JsonProperty("off")
  OFF,

  @JsonProperty("one-sided")
  ONE_SIDED,

  @JsonProperty("two-sided")
  TWO_SIDED
}
```

`LayoutPlacementStrategy.java`:
```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutPlacementStrategy {
  @JsonProperty("brandes-koepf")
  BRANDES_KOEPF,

  @JsonProperty("network-simplex")
  NETWORK_SIMPLEX,

  @JsonProperty("linear-segments")
  LINEAR_SEGMENTS,

  @JsonProperty("simple")
  SIMPLE
}
```

- [ ] **Step 4: Create the sub-records**

`LayoutLayeringPreferences.java`:
```java
package dev.dediren.contracts.layout;

public record LayoutLayeringPreferences(LayoutLayeringStrategy strategy) {}
```

`LayoutCrossingPreferences.java`:
```java
package dev.dediren.contracts.layout;

public record LayoutCrossingPreferences(
    LayoutCrossingStrategy strategy, LayoutGreedySwitch greedySwitch) {}
```

`LayoutPlacementPreferences.java`:
```java
package dev.dediren.contracts.layout;

public record LayoutPlacementPreferences(LayoutPlacementStrategy strategy) {}
```

- [ ] **Step 5: Extend `LayoutPreferences` (backward-compatible)**

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
    LayoutPlacementPreferences placement) {

  public LayoutPreferences(
      LayoutDirection direction,
      LayoutDensity density,
      LayoutWrapping wrapping,
      LayoutRoutingPreferences routing) {
    this(null, direction, density, wrapping, routing, null, null, null, null);
  }

  public LayoutPreferences(
      LayoutMode mode,
      LayoutDirection direction,
      LayoutDensity density,
      LayoutWrapping wrapping,
      LayoutRoutingPreferences routing) {
    this(mode, direction, density, wrapping, routing, null, null, null, null);
  }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#layoutPreferencesRoundTripsPhaseStrategies -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS

- [ ] **Step 7: Run the full contracts suite** (catches any Jackson multi-constructor regression)

Run: `./mvnw -pl contracts -am test` (sandbox disabled)
Expected: PASS

- [ ] **Step 8: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add contracts/src/main/java/dev/dediren/contracts/layout/ contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java
git commit -m "Add contract types for ELK layered phase strategies"
```

---

### Task 2: Widen public schemas

**Files:**
- Modify: `schemas/model.schema.json` (the `layoutPreferences` object + add three `$defs`)
- Modify: `schemas/layout-request.schema.json` (identical changes)
- Test: `contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java`

**Interfaces:** none consumed. Produces schemas accepting the four new controls and rejecting unknown values.

- [ ] **Step 1: Write the failing test**

Add to `SchemaValidatorTest`:
```java
  @Test
  void layoutRequestAcceptsPhaseStrategiesAndRejectsUnknown() throws Exception {
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
            "cycle_breaking": "model-order",
            "layering": { "strategy": "%s" },
            "crossing": { "strategy": "layer-sweep", "greedy_switch": "two-sided" },
            "placement": { "strategy": "network-simplex" }
          }
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "coffman-graham"))))
        .describedAs("valid phase strategies must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "bogus-strategy"))))
        .describedAs("unknown layering strategy must be rejected")
        .isNotEmpty();
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#layoutRequestAcceptsPhaseStrategiesAndRejectsUnknown -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `additionalProperties:false` on `layoutPreferences` rejects the unknown `cycle_breaking`/`layering`/`crossing`/`placement` keys, so the first assertion fails.

- [ ] **Step 3: Extend both schemas**

In BOTH `schemas/model.schema.json` and `schemas/layout-request.schema.json`, add four properties to the `layoutPreferences` object (after the existing `"routing"` property):
```json
        "cycle_breaking": { "enum": ["greedy", "depth-first", "model-order"] },
        "layering": { "$ref": "#/$defs/layoutLayeringPreferences" },
        "crossing": { "$ref": "#/$defs/layoutCrossingPreferences" },
        "placement": { "$ref": "#/$defs/layoutPlacementPreferences" }
```
(add a comma after `"routing"`'s line so the object stays valid JSON), and add three `$defs` siblings to the existing `layoutRoutingPreferences` def:
```json
    "layoutLayeringPreferences": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "strategy": {
          "enum": ["network-simplex", "longest-path", "coffman-graham", "min-width", "stretch-width", "breadth-first", "depth-first"]
        }
      }
    },
    "layoutCrossingPreferences": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "strategy": { "enum": ["layer-sweep", "none"] },
        "greedy_switch": { "enum": ["off", "one-sided", "two-sided"] }
      }
    },
    "layoutPlacementPreferences": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "strategy": { "enum": ["brandes-koepf", "network-simplex", "linear-segments", "simple"] }
      }
    }
```
(add a comma after the `layoutRoutingPreferences` def's closing brace so the `$defs` object stays valid).

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#layoutRequestAcceptsPhaseStrategiesAndRejectsUnknown -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS. Then run the schema-compile guard: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#allPublicSchemasCompile -Dsurefire.failIfNoSpecifiedTests=false` — Expected: PASS (both schemas still compile).

- [ ] **Step 5: Commit**

```bash
git add schemas/model.schema.json schemas/layout-request.schema.json contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java
git commit -m "Accept ELK layered phase strategies in public schemas"
```

---

### Task 3: Boundary validation in `LayoutJson`

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutJson.java`
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutJsonTest.java`

**Interfaces:** consumes the Task 1 contract types. Produces boundary validation that accepts valid phase-strategy values and rejects unknown ones with the existing `LayoutPreferenceValidationException` / explicit-null diagnostics.

- [ ] **Step 1: Write the failing test**

Add to `LayoutJsonTest`:
```java
  @Test
  void readsPhaseStrategyPreferences() throws Exception {
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
                "cycle_breaking": "depth-first",
                "layering": { "strategy": "min-width" },
                "crossing": { "strategy": "none", "greedy_switch": "one-sided" },
                "placement": { "strategy": "linear-segments" }
              }
            }
            """;

    LayoutRequest request =
        LayoutJson.readLayoutRequest(new java.io.ByteArrayInputStream(json.getBytes()));

    assertEquals(LayoutCycleBreaking.DEPTH_FIRST, request.layoutPreferences().cycleBreaking());
    assertEquals(
        LayoutLayeringStrategy.MIN_WIDTH, request.layoutPreferences().layering().strategy());
    assertEquals(
        LayoutCrossingStrategy.NONE, request.layoutPreferences().crossing().strategy());
    assertEquals(
        LayoutGreedySwitch.ONE_SIDED, request.layoutPreferences().crossing().greedySwitch());
    assertEquals(
        LayoutPlacementStrategy.LINEAR_SEGMENTS, request.layoutPreferences().placement().strategy());
  }

  @Test
  void rejectsUnknownCycleBreakingWithStructuredError() {
    String json =
        """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "constraints": [],
              "layout_preferences": { "cycle_breaking": "bogus" }
            }
            """;

    LayoutJson.LayoutPreferenceValidationException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            LayoutJson.LayoutPreferenceValidationException.class,
            () -> LayoutJson.readLayoutRequest(new java.io.ByteArrayInputStream(json.getBytes())));
    org.junit.jupiter.api.Assertions.assertTrue(
        ex.getMessage().contains("$.layout_preferences.cycle_breaking"),
        "error must name the offending path, was: " + ex.getMessage());
  }
```

The positive `readsPhaseStrategyPreferences` test documents the happy path (it passes once Task 1's accessors exist); `rejectsUnknownCycleBreakingWithStructuredError` is the TDD driver for THIS task — it fails until Step 3 adds boundary validation.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=LayoutJsonTest#rejectsUnknownCycleBreakingWithStructuredError -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — before Step 3 the boundary does not check `cycle_breaking`, so `readLayoutRequest` proceeds to Jackson `treeToValue`, which throws a Jackson deserialization exception (unknown enum value) rather than the expected `LayoutPreferenceValidationException`. The `assertThrows` fails on the wrong exception type.

- [ ] **Step 3: Add null-rejection and value-rejection**

In `LayoutJson.rejectExplicitPreferenceNulls`, after the existing `rejectNull(preferences.get("wrapping"), ...)` line, add:
```java
    rejectNull(preferences.get("cycle_breaking"), "$.layout_preferences.cycle_breaking");

    JsonNode layering = preferences.get("layering");
    if (layering != null) {
      rejectNull(layering, "$.layout_preferences.layering");
      if (layering.isObject()) {
        rejectNull(layering.get("strategy"), "$.layout_preferences.layering.strategy");
      }
    }

    JsonNode crossing = preferences.get("crossing");
    if (crossing != null) {
      rejectNull(crossing, "$.layout_preferences.crossing");
      if (crossing.isObject()) {
        rejectNull(crossing.get("strategy"), "$.layout_preferences.crossing.strategy");
        rejectNull(crossing.get("greedy_switch"), "$.layout_preferences.crossing.greedy_switch");
      }
    }

    JsonNode placement = preferences.get("placement");
    if (placement != null) {
      rejectNull(placement, "$.layout_preferences.placement");
      if (placement.isObject()) {
        rejectNull(placement.get("strategy"), "$.layout_preferences.placement.strategy");
      }
    }
```

In `LayoutJson.rejectUnsupportedPreferenceValues`, after the existing `wrapping` block and before the `routing` block, add:
```java
    rejectUnsupportedText(
        preferences.get("cycle_breaking"),
        "$.layout_preferences.cycle_breaking",
        Set.of("greedy", "depth-first", "model-order"));

    JsonNode layering = preferences.get("layering");
    if (layering != null && layering.isObject()) {
      rejectUnsupportedText(
          layering.get("strategy"),
          "$.layout_preferences.layering.strategy",
          Set.of(
              "network-simplex",
              "longest-path",
              "coffman-graham",
              "min-width",
              "stretch-width",
              "breadth-first",
              "depth-first"));
    }

    JsonNode crossing = preferences.get("crossing");
    if (crossing != null && crossing.isObject()) {
      rejectUnsupportedText(
          crossing.get("strategy"),
          "$.layout_preferences.crossing.strategy",
          Set.of("layer-sweep", "none"));
      rejectUnsupportedText(
          crossing.get("greedy_switch"),
          "$.layout_preferences.crossing.greedy_switch",
          Set.of("off", "one-sided", "two-sided"));
    }

    JsonNode placement = preferences.get("placement");
    if (placement != null && placement.isObject()) {
      rejectUnsupportedText(
          placement.get("strategy"),
          "$.layout_preferences.placement.strategy",
          Set.of("brandes-koepf", "network-simplex", "linear-segments", "simple"));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=LayoutJsonTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS (all `LayoutJsonTest` methods).

- [ ] **Step 5: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutJson.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutJsonTest.java
git commit -m "Validate ELK layered phase-strategy preferences at the boundary"
```

---

### Task 4: Map phase strategies to ELK options

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayeredOptions.java`
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java` (ADDITIVE — new imports + one new `@Test` method + one helper only)

**Interfaces:** consumes Task 1 contract types. Produces: the ELK root reflects requested phase strategies; absent fields preserve today's behavior (placement default `BRANDES_KOEPF`; others unset).

- [ ] **Step 1: Write the failing test**

In `ElkLayoutEngineTest.java`, add imports if absent:
```java
import org.eclipse.elk.alg.layered.options.CrossingMinimizationStrategy;
import org.eclipse.elk.alg.layered.options.CycleBreakingStrategy;
import org.eclipse.elk.alg.layered.options.GreedySwitchType;
import org.eclipse.elk.alg.layered.options.LayeringStrategy;
```
(`LayeredOptions` and `NodePlacementStrategy` are already imported.)

Append this test and helper:
```java
  @Test
  void layeredRootMapsPhaseStrategiesToElkOptions() {
    LayoutPreferences prefs =
        new LayoutPreferences(
            null, null, null, null, null,
            LayoutCycleBreaking.MODEL_ORDER,
            new LayoutLayeringPreferences(LayoutLayeringStrategy.COFFMAN_GRAHAM),
            new LayoutCrossingPreferences(LayoutCrossingStrategy.NONE, LayoutGreedySwitch.ONE_SIDED),
            new LayoutPlacementPreferences(LayoutPlacementStrategy.NETWORK_SIMPLEX));
    ElkNode root = ElkLayeredOptions.configuredRoot(Direction.RIGHT, prefs);

    assertEquals(
        CycleBreakingStrategy.MODEL_ORDER, root.getProperty(LayeredOptions.CYCLE_BREAKING_STRATEGY));
    assertEquals(LayeringStrategy.COFFMAN_GRAHAM, root.getProperty(LayeredOptions.LAYERING_STRATEGY));
    assertEquals(
        CrossingMinimizationStrategy.NONE,
        root.getProperty(LayeredOptions.CROSSING_MINIMIZATION_STRATEGY));
    assertEquals(
        GreedySwitchType.ONE_SIDED,
        root.getProperty(LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_TYPE));
    assertEquals(
        NodePlacementStrategy.NETWORK_SIMPLEX,
        root.getProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY));

    // Absent phase-strategy fields preserve today's defaults.
    ElkNode bare = ElkLayeredOptions.configuredRoot(Direction.RIGHT, null);
    assertEquals(
        NodePlacementStrategy.BRANDES_KOEPF,
        bare.getProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY));
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#layeredRootMapsPhaseStrategiesToElkOptions -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — placement is pinned and the other properties are unset.

- [ ] **Step 3: Add the mapping**

In `ElkLayeredOptions.java`, add imports:
```java
import dev.dediren.contracts.layout.LayoutCrossingPreferences;
import dev.dediren.contracts.layout.LayoutLayeringPreferences;
import dev.dediren.contracts.layout.LayoutPlacementPreferences;
import org.eclipse.elk.alg.layered.options.CrossingMinimizationStrategy;
import org.eclipse.elk.alg.layered.options.CycleBreakingStrategy;
import org.eclipse.elk.alg.layered.options.GreedySwitchType;
import org.eclipse.elk.alg.layered.options.LayeringStrategy;
```

Replace the pinned placement line (currently `root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.BRANDES_KOEPF);`) with:
```java
    root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, placementStrategy(preferences));
```

Immediately after the `root.setProperty(LayeredOptions.MERGE_HIERARCHY_EDGES, mergeEdges);` line at the end of `configureRoot`, add the conditional phase-strategy assignments:
```java
    CycleBreakingStrategy cycleBreaking = cycleBreakingStrategy(preferences);
    if (cycleBreaking != null) {
      root.setProperty(LayeredOptions.CYCLE_BREAKING_STRATEGY, cycleBreaking);
    }
    LayeringStrategy layering = layeringStrategy(preferences);
    if (layering != null) {
      root.setProperty(LayeredOptions.LAYERING_STRATEGY, layering);
    }
    CrossingMinimizationStrategy crossing = crossingStrategy(preferences);
    if (crossing != null) {
      root.setProperty(LayeredOptions.CROSSING_MINIMIZATION_STRATEGY, crossing);
    }
    GreedySwitchType greedySwitch = greedySwitchType(preferences);
    if (greedySwitch != null) {
      root.setProperty(LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_TYPE, greedySwitch);
    }
```

Add these helper methods to the class (near the existing `endpointMerging` helper):
```java
  private static NodePlacementStrategy placementStrategy(LayoutPreferences preferences) {
    LayoutPlacementPreferences placement = preferences == null ? null : preferences.placement();
    if (placement == null || placement.strategy() == null) {
      return NodePlacementStrategy.BRANDES_KOEPF;
    }
    return switch (placement.strategy()) {
      case BRANDES_KOEPF -> NodePlacementStrategy.BRANDES_KOEPF;
      case NETWORK_SIMPLEX -> NodePlacementStrategy.NETWORK_SIMPLEX;
      case LINEAR_SEGMENTS -> NodePlacementStrategy.LINEAR_SEGMENTS;
      case SIMPLE -> NodePlacementStrategy.SIMPLE;
    };
  }

  private static CycleBreakingStrategy cycleBreakingStrategy(LayoutPreferences preferences) {
    if (preferences == null || preferences.cycleBreaking() == null) {
      return null;
    }
    return switch (preferences.cycleBreaking()) {
      case GREEDY -> CycleBreakingStrategy.GREEDY;
      case DEPTH_FIRST -> CycleBreakingStrategy.DEPTH_FIRST;
      case MODEL_ORDER -> CycleBreakingStrategy.MODEL_ORDER;
    };
  }

  private static LayeringStrategy layeringStrategy(LayoutPreferences preferences) {
    LayoutLayeringPreferences layering = preferences == null ? null : preferences.layering();
    if (layering == null || layering.strategy() == null) {
      return null;
    }
    return switch (layering.strategy()) {
      case NETWORK_SIMPLEX -> LayeringStrategy.NETWORK_SIMPLEX;
      case LONGEST_PATH -> LayeringStrategy.LONGEST_PATH;
      case COFFMAN_GRAHAM -> LayeringStrategy.COFFMAN_GRAHAM;
      case MIN_WIDTH -> LayeringStrategy.MIN_WIDTH;
      case STRETCH_WIDTH -> LayeringStrategy.STRETCH_WIDTH;
      case BREADTH_FIRST -> LayeringStrategy.BF_MODEL_ORDER;
      case DEPTH_FIRST -> LayeringStrategy.DF_MODEL_ORDER;
    };
  }

  private static CrossingMinimizationStrategy crossingStrategy(LayoutPreferences preferences) {
    LayoutCrossingPreferences crossing = preferences == null ? null : preferences.crossing();
    if (crossing == null || crossing.strategy() == null) {
      return null;
    }
    return switch (crossing.strategy()) {
      case LAYER_SWEEP -> CrossingMinimizationStrategy.LAYER_SWEEP;
      case NONE -> CrossingMinimizationStrategy.NONE;
    };
  }

  private static GreedySwitchType greedySwitchType(LayoutPreferences preferences) {
    LayoutCrossingPreferences crossing = preferences == null ? null : preferences.crossing();
    if (crossing == null || crossing.greedySwitch() == null) {
      return null;
    }
    return switch (crossing.greedySwitch()) {
      case OFF -> GreedySwitchType.OFF;
      case ONE_SIDED -> GreedySwitchType.ONE_SIDED;
      case TWO_SIDED -> GreedySwitchType.TWO_SIDED;
    };
  }
```

- [ ] **Step 4: Run the new test, then the full elk-layout suite**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#layeredRootMapsPhaseStrategiesToElkOptions -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled) — Expected: PASS.
Then: `./mvnw -pl plugins/elk-layout -am test` (sandbox disabled) — Expected: PASS. This confirms the absent-field default path left every existing geometry/invariant test unchanged.

- [ ] **Step 5: Confirm the test-file change is additive**

Run: `git diff -- plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java | grep '^-' | grep -v '^---'`
Expected: no output (nothing removed — the change is purely additions: imports + one method).

- [ ] **Step 6: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayeredOptions.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "Map ELK layered phase-strategy preferences to ELK options"
```

---

### Task 5: Document the phase strategies

**Files:**
- Modify: `docs/features/layout.md` (add a "### Layered phase strategies" subsection before "### Routing styles")
- Modify: `README.md` and `docs/agent-usage.md` (one sentence each, in the layout-preferences area)

**Interfaces:** none (docs only).

- [ ] **Step 1: Add the layout.md subsection**

Insert immediately before the `### Routing styles` heading in `docs/features/layout.md`:
```markdown
### Layered phase strategies

`layout_preferences` exposes the ELK Layered pipeline stages as Dediren-owned
options. All are optional; when omitted, Dediren keeps its defaults.

| Option | Values | Controls |
| --- | --- | --- |
| `cycle_breaking` | `greedy` (default), `depth-first`, `model-order` | How edges in cycles are reversed for layering. |
| `layering.strategy` | `network-simplex` (default), `longest-path`, `coffman-graham`, `min-width`, `stretch-width`, `breadth-first`, `depth-first` | How nodes are assigned to layers. |
| `crossing.strategy` | `layer-sweep` (default), `none` | The crossing-minimization pass. |
| `crossing.greedy_switch` | `off`, `one-sided`, `two-sided` | Greedy post-pass that swaps adjacent nodes to cut crossings. |
| `placement.strategy` | `brandes-koepf` (default), `network-simplex`, `linear-segments`, `simple` | How nodes are positioned within their layers. |

These map to ELK Layered options internally; Dediren validates the values and
rejects unknown ones with a structured error envelope.

```

- [ ] **Step 2: Add the one-line mentions**

In `README.md` and `docs/agent-usage.md`, in the same layout-preferences paragraph that now mentions `routing.style` (from slice 1), add: ``Layered phase strategies (`cycle_breaking`, `layering.strategy`, `crossing.strategy`, `crossing.greedy_switch`, `placement.strategy`) are configurable under `layout_preferences`; see the Layout feature page for values.``

- [ ] **Step 3: Verify whitespace**

Run: `git diff --check`
Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add docs/features/layout.md README.md docs/agent-usage.md
git commit -m "Document ELK layered phase strategies"
```

---

## Final verification (after Tasks 1–5)

```bash
./mvnw test                                   # sandbox disabled — full integration
./mvnw -pl dist-tool -am verify -Pdist-smoke  # sandbox disabled
git diff --check
```

## Self-Review Notes

- **Spec coverage:** implements the `cycle_breaking`, `layering.strategy`, `crossing.strategy`, `crossing.greedy_switch`, and `placement.strategy` rows of the umbrella spec's graph-scoped vocabulary (slice 2).
- **Deferred within this slice:** `layering.node_promotion` (spec-marked "optional refinement") — left to a later increment to keep the slice bounded; note it in the ledger so it is not lost.
- **No conditional validity yet:** the `algorithm` field arrives in slice 4; until then these knobs apply to the always-`layered` algorithm, so no cross-field rejection is needed. When slice 4 lands, these become layered-only and must be gated then.
- **Behavior preservation:** absent fields set no new ELK property except placement, which keeps its current `BRANDES_KOEPF`; verified by running the full elk-layout suite in Task 4 Step 4.
- **Threat model:** widens accepted values of already-validated fields; no new parser or trust boundary, so `docs/threat-model.md` needs no change.

# ELK Routing Styles (Polyline + Spline) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the Dediren `routing.style` vocabulary from `orthogonal`-only to `orthogonal | polyline | spline`, mapping each to the matching ELK `EdgeRouting` constant internally.

**Architecture:** This is slice 1 of the umbrella spec `docs/superpowers/specs/2026-07-05-dediren-elk-layered-capability-vocabulary-design.md`. The public JSON enum (source model + layout request), the `contracts` enum, the plugin boundary validator, and the ELK option mapper all learn two new Dediren-owned symbolic values. No raw ELK names enter the contract; the `elk-layout` helper is the only place the ELK `EdgeRouting` constant appears.

**Tech Stack:** Java 21+ built with the checked-in Maven Wrapper; Jackson (`tools.jackson`) via `dev.dediren.contracts.json.JsonSupport`; Eclipse ELK Layered; JUnit 5 + AssertJ; `networknt` JSON Schema via `dev.dediren.testsupport.SchemaAssertions`.

## Global Constraints

- **No raw ELK option names in public JSON.** Symbolic values are Dediren-owned (kebab-case). The ELK `EdgeRouting` enum appears only in `plugins/elk-layout`. (Spec §Purpose non-goals.)
- **ELK mapping:** `orthogonal → EdgeRouting.ORTHOGONAL`, `polyline → EdgeRouting.POLYLINE`, `spline → EdgeRouting.SPLINES`. (Note the ELK constant is plural `SPLINES`.)
- **`orthogonal` stays the default** when `routing.style` is absent — existing fixtures and behavior are unchanged.
- **Schema id stays `model.schema.v1`.** Adding optional enum members is backward-compatible; compatibility is communicated in release notes, not the version number. (CLAUDE.md §Versioning.)
- **`plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java` has uncommitted user modifications** (pre-existing working-tree change). Task 4 edits this file — make **additive** changes only (new imports, one new `@Test` method, one comment edit). Do not reformat or restage unrelated hunks; integrate around the user's edits.
- **Run Maven tests with the sandbox disabled.** `./mvnw test` fails on read-only `/tmp` under the sandbox (JUnit `@TempDir`). Module-scoped runs need `-am`.
- **Explicit-path staging only.** Untracked dotfiles and the modified test file exist in the tree; never `git add -A`. Stage exactly the files each task lists.
- **Format before commit:** `./mvnw -Pquality spotless:apply` for any Java change.

---

### Task 1: Add `POLYLINE` and `SPLINE` to the contract enum

**Files:**
- Modify: `contracts/src/main/java/dev/dediren/contracts/layout/LayoutRoutingStyle.java`
- Test: `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`

**Interfaces:**
- Produces: `LayoutRoutingStyle.POLYLINE` (JSON `"polyline"`), `LayoutRoutingStyle.SPLINE` (JSON `"spline"`). Consumed by Task 4.

- [ ] **Step 1: Write the failing test**

Add this method to `ContractRoundTripTest` (it already imports `LayoutRoutingStyle`, `LayoutRoutingProfile`, `LayoutEndpointMerging`, `JsonSupport`/mapper, and uses AssertJ):

```java
  @Test
  void routingStyleAcceptsPolylineAndSpline() throws Exception {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    assertThat(mapper.readValue("\"polyline\"", LayoutRoutingStyle.class))
        .isEqualTo(LayoutRoutingStyle.POLYLINE);
    assertThat(mapper.readValue("\"spline\"", LayoutRoutingStyle.class))
        .isEqualTo(LayoutRoutingStyle.SPLINE);
    assertThat(mapper.writeValueAsString(LayoutRoutingStyle.SPLINE)).isEqualTo("\"spline\"");
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#routingStyleAcceptsPolylineAndSpline -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `POLYLINE` / `SPLINE` do not resolve (compile error) or deserialization fails.

- [ ] **Step 3: Write minimal implementation**

Replace the body of `contracts/src/main/java/dev/dediren/contracts/layout/LayoutRoutingStyle.java` with:

```java
package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutRoutingStyle {
  @JsonProperty("orthogonal")
  ORTHOGONAL,

  @JsonProperty("polyline")
  POLYLINE,

  @JsonProperty("spline")
  SPLINE
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl contracts -am test -Dtest=ContractRoundTripTest#routingStyleAcceptsPolylineAndSpline -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS

- [ ] **Step 5: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add contracts/src/main/java/dev/dediren/contracts/layout/LayoutRoutingStyle.java contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java
git commit -m "Add polyline and spline to LayoutRoutingStyle enum"
```

---

### Task 2: Widen the public schema `routing.style` enum

**Files:**
- Modify: `schemas/model.schema.json:115`
- Modify: `schemas/layout-request.schema.json:105`
- Test: `contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java`

**Interfaces:**
- Consumes: nothing. Produces: JSON schemas that accept `"polyline"`/`"spline"` and still reject unknown styles.

- [ ] **Step 1: Write the failing test**

Add this method to `SchemaValidatorTest` (it already imports `SchemaAssertions`, has `workspaceRoot()`, and uses AssertJ):

```java
  @Test
  void layoutRequestRoutingStyleAcceptsSplineAndRejectsUnknown() throws Exception {
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
          "layout_preferences": { "routing": { "style": "%s" } }
        }
        """;
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "spline"))))
        .describedAs("spline style must validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/layout-request.schema.json",
                mapper.readTree(String.format(template, "curved"))))
        .describedAs("unknown style must be rejected")
        .isNotEmpty();
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#layoutRequestRoutingStyleAcceptsSplineAndRejectsUnknown -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `"spline"` is rejected by the current `["orthogonal"]` enum, so the first assertion fails.

- [ ] **Step 3: Widen both schema enums**

In `schemas/model.schema.json`, change line 115 from:

```json
        "style": { "enum": ["orthogonal"] },
```
to:
```json
        "style": { "enum": ["orthogonal", "polyline", "spline"] },
```

Make the identical change in `schemas/layout-request.schema.json` (line 105).

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl contracts -am test -Dtest=SchemaValidatorTest#layoutRequestRoutingStyleAcceptsSplineAndRejectsUnknown -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS (both assertions).

- [ ] **Step 5: Commit**

```bash
git add schemas/model.schema.json schemas/layout-request.schema.json contracts/src/test/java/dev/dediren/contracts/SchemaValidatorTest.java
git commit -m "Accept polyline and spline routing styles in public schemas"
```

---

### Task 3: Accept the new styles at the plugin boundary

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutJson.java:83`
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutJsonTest.java`

**Interfaces:**
- Consumes: `LayoutRoutingStyle.SPLINE` (Task 1). Produces: `LayoutJson.readLayoutRequest` no longer rejects `polyline`/`spline`.

- [ ] **Step 1: Write the failing test**

Add this method to `LayoutJsonTest` (it imports `dev.dediren.contracts.layout.*`, `JsonSupport`, and uses `assertEquals`):

```java
  @Test
  void readsSplineRoutingStyle() throws Exception {
    String json =
        """
            {
              "layout_request_schema_version": "layout-request.schema.v1",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "constraints": [],
              "layout_preferences": { "routing": { "style": "spline" } }
            }
            """;

    LayoutRequest request = LayoutJson.readLayoutRequest(new java.io.ByteArrayInputStream(json.getBytes()));

    assertEquals(LayoutRoutingStyle.SPLINE, request.layoutPreferences().routing().style());
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=LayoutJsonTest#readsSplineRoutingStyle -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `LayoutPreferenceValidationException: $.layout_preferences.routing.style has unsupported value: spline`.

- [ ] **Step 3: Widen the accepted set**

In `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutJson.java`, change the `style` acceptance call (around line 82-83) from:

```java
    rejectUnsupportedText(
        routing.get("style"), "$.layout_preferences.routing.style", Set.of("orthogonal"));
```
to:
```java
    rejectUnsupportedText(
        routing.get("style"),
        "$.layout_preferences.routing.style",
        Set.of("orthogonal", "polyline", "spline"));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=LayoutJsonTest#readsSplineRoutingStyle -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS

- [ ] **Step 5: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/LayoutJson.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/LayoutJsonTest.java
git commit -m "Accept polyline and spline styles in elk-layout boundary validation"
```

---

### Task 4: Map the requested style to ELK `EdgeRouting`

**Files:**
- Modify: `plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayeredOptions.java` (add import; replace line 76; add helper near line 171)
- Test: `plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java` (**additive only** — see Global Constraints)

**Interfaces:**
- Consumes: `LayoutRoutingStyle` (Task 1), `LayoutPreferences.routing()`, `ElkLayeredOptions.configuredRoot(Direction, LayoutPreferences)`.
- Produces: the ELK root's `CoreOptions.EDGE_ROUTING` property reflects the requested style; default `EdgeRouting.ORTHOGONAL`.

- [ ] **Step 1: Write the failing test**

In `ElkLayoutEngineTest.java`, add these imports if absent:

```java
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.EdgeRouting;
```

Append this test method and its helper to the class:

```java
  @Test
  void layeredRootMapsRoutingStyleToElkEdgeRouting() {
    assertEquals(
        EdgeRouting.ORTHOGONAL,
        ElkLayeredOptions.configuredRoot(Direction.RIGHT, null).getProperty(CoreOptions.EDGE_ROUTING));
    assertEquals(
        EdgeRouting.POLYLINE,
        ElkLayeredOptions.configuredRoot(Direction.RIGHT, routingStylePreferences(LayoutRoutingStyle.POLYLINE))
            .getProperty(CoreOptions.EDGE_ROUTING));
    assertEquals(
        EdgeRouting.SPLINES,
        ElkLayeredOptions.configuredRoot(Direction.RIGHT, routingStylePreferences(LayoutRoutingStyle.SPLINE))
            .getProperty(CoreOptions.EDGE_ROUTING));
  }

  private static LayoutPreferences routingStylePreferences(LayoutRoutingStyle style) {
    return new LayoutPreferences(
        null, null, null, new LayoutRoutingPreferences(style, null, LayoutEndpointMerging.AUTO));
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#layeredRootMapsRoutingStyleToElkEdgeRouting -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — the POLYLINE/SPLINES assertions fail because `configureRoot` hardcodes `EdgeRouting.ORTHOGONAL`.

- [ ] **Step 3: Add the mapping**

In `ElkLayeredOptions.java`, add the import (alongside the existing `dev.dediren.contracts.layout` imports):

```java
import dev.dediren.contracts.layout.LayoutRoutingStyle;
```

Replace line 76:

```java
    root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
```
with:
```java
    root.setProperty(CoreOptions.EDGE_ROUTING, routingStyle(preferences));
```

Add this helper method to the class (e.g. directly above the existing `endpointMerging` helper near line 171):

```java
  private static EdgeRouting routingStyle(LayoutPreferences preferences) {
    LayoutRoutingPreferences routing = preferences == null ? null : preferences.routing();
    LayoutRoutingStyle style = routing == null ? null : routing.style();
    if (style == null) {
      return EdgeRouting.ORTHOGONAL;
    }
    return switch (style) {
      case ORTHOGONAL -> EdgeRouting.ORTHOGONAL;
      case POLYLINE -> EdgeRouting.POLYLINE;
      case SPLINE -> EdgeRouting.SPLINES;
    };
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl plugins/elk-layout -am test -Dtest=ElkLayoutEngineTest#layeredRootMapsRoutingStyleToElkEdgeRouting -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS

- [ ] **Step 5: Fix the now-stale comment**

In `ElkLayoutEngineTest.java` near line 28-30, the comment claims `LayoutRoutingStyle has no other value`. Update it to reflect that orthogonal is now one of several styles and these fixtures use the default:

```java
  // Tolerance for calling a route segment axis-aligned. These fixtures use the default ORTHOGONAL
  // routing style (polyline and spline exist but are not requested here), so every segment must move
  // along exactly one axis. A
```

(Keep the remaining two lines of the original comment intact.)

- [ ] **Step 6: Run the full elk-layout suite**

Run: `./mvnw -pl plugins/elk-layout -am test` (sandbox disabled)
Expected: PASS — confirms the default-orthogonal assumption in existing route-geometry tests still holds.

- [ ] **Step 7: Format and commit**

```bash
./mvnw -Pquality spotless:apply
git add plugins/elk-layout/src/main/java/dev/dediren/plugins/elklayout/ElkLayeredOptions.java plugins/elk-layout/src/test/java/dev/dediren/plugins/elklayout/ElkLayoutEngineTest.java
git commit -m "Map polyline and spline routing styles to ELK EdgeRouting"
```

---

### Task 5: Document the new routing styles

**Files:**
- Modify: `docs/features/layout.md` (add a "Routing styles" subsection near the Junction routing section, ~line 58)
- Modify: `README.md` (ELK Runtime / layout preferences area)
- Modify: `docs/agent-usage.md` (layout preferences area)

**Interfaces:** none (docs only).

- [ ] **Step 1: Add the layout.md subsection**

Insert before the `### Junction routing` heading in `docs/features/layout.md`:

```markdown
### Routing styles

`layout_preferences.routing.style` selects how ELK draws edges:

| Value | Route shape |
| --- | --- |
| `orthogonal` (default) | Axis-aligned segments with right-angle bends. |
| `polyline` | Straight point-to-point segments; diagonal bends allowed. |
| `spline` | Smooth curved routes. |

The style is Dediren-owned vocabulary; the `elk-layout` plugin maps it to the
matching ELK edge-routing mode. `orthogonal` remains the default when the field
is omitted.

```

- [ ] **Step 2: Note the values in README and agent-usage**

In `README.md` and `docs/agent-usage.md`, wherever `layout_preferences` / `routing` is described, add a sentence: ``The `routing.style` option accepts `orthogonal` (default), `polyline`, or `spline`.`` If neither doc currently enumerates `layout_preferences.routing`, add the sentence to the nearest layout-preferences paragraph (README ~§ELK Runtime; agent-usage near the `mode: "packed"` note around line 563).

- [ ] **Step 3: Verify docs whitespace**

Run: `git diff --check`
Expected: no output (no whitespace errors).

- [ ] **Step 4: Commit**

```bash
git add docs/features/layout.md README.md docs/agent-usage.md
git commit -m "Document polyline and spline routing styles"
```

---

### Task 6 (conditional): Version bump — only if this slice is being released

Do this task **only** if the slice is being released/distributed now. Per CLAUDE.md §Versioning, a change that is not being released leaves the version untouched, and the bump is always a separate follow-on commit.

**Files:**
- Modify: root `pom.xml` (+ all modules via `-DprocessAllModules`)
- Modify: version-assertion surfaces the bump touches (see CLAUDE.md §Versioning list)

- [ ] **Step 1: Bump the version** (choose new-month vs micro-increment per the rule)

```bash
./mvnw versions:set -DnewVersion='<YYYY>.<0M>.<next-micro>' -DprocessAllModules=true -DgenerateBackupPoms=false
```

- [ ] **Step 2: Sync version-assertion surfaces and search for stragglers**

Update `fixtures/plugins/*.manifest.json`, `fixtures/source` `required_plugins[].version`, README/agent-usage bundle examples, and dist metadata to match. Then:

```bash
grep -rn "<previous-version>" pom.xml README.md docs/agent-usage.md fixtures/plugins fixtures/source
```
Expected: no stale hits.

- [ ] **Step 3: Verify and commit in its own commit, then tag**

```bash
./mvnw test   # sandbox disabled
git add pom.xml **/pom.xml fixtures/plugins README.md docs/agent-usage.md
git commit -m "Bump version to <version>"
git tag -a v<version> -m "v<version>"
```

(Do not push tags without the user's go-ahead — tags are a stop-point per git-workflow-policy.)

---

## Final verification (after Tasks 1–5)

Run the broader gates the change crosses (contracts + plugin + CLI + distribution):

```bash
./mvnw test                                   # sandbox disabled
./mvnw -pl dist-tool -am verify -Pdist-smoke  # sandbox disabled
git diff --check
```

Optional real-render evidence (spline routes are not axis-aligned, so this is visual, not asserted): render any layered fixture with `layout_preferences.routing.style: "spline"` and confirm curved edges. Note: git-ignored render SVGs can lag code — regenerate before reviewing.

## Self-Review Notes

- **Spec coverage:** implements the `routing.style: orthogonal | polyline | spline` row of the spec's graph-scoped vocabulary table and the "Routing styles" slice-1 entry. Conditional validity (spec §Conditional validity) is not exercised here because routing style is valid under every algorithm; it lands with slice 4 when `algorithm` is introduced.
- **Deferred to later slices:** all other vocabulary rows, the `algorithm` gate, and element-scoped hints.
- **Threat model:** this change only widens the accepted value set of an already-validated field; it introduces no new parser, fetch, or trust boundary, so `docs/threat-model.md` needs no update (verify during Task 3 that no threat-model text enumerates the routing-style set — it currently does not).

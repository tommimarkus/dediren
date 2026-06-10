# Interactive SVG (Node-Select Edge Highlight) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a reader select a node in rendered SVG output and have its connected edges highlight, delivered as a static SVG, an interactive self-contained SVG, an HTML wrapper, or both.

**Architecture:** Add a render-policy `interactive` mode (`none|svg|html|both`, default `svg`) and an optional `style.interaction` block. The graph renderer emits edge `source`/`target` data attributes plus a small embedded `<style>`/`<script>` interaction layer when the mode is not `none`. The render-result contract reshapes from a single artifact to an ordered `artifacts[]` list (schema id `v1`→`v2`) so `both` can return an SVG and an HTML wrapper together. UML sequence output follows the packaging modes but emits no highlight script.

**Tech Stack:** Java 21, Maven Wrapper (`./mvnw`), Jackson (SNAKE_CASE), JUnit 5 + AssertJ, JSON Schema (draft 2020-12), vanilla browser JS (no dependencies).

**Spec:** `docs/superpowers/specs/2026-06-10-interactive-svg-design.md`

---

## Execution environment notes

- Work on `main` (direct commits allowed by repo policy). Subagent-driven work runs in the main repo, not a worktree (see project memory: subagents don't reliably honor worktrees here).
- Maven tests use JUnit `@TempDir`; the sandbox makes `/tmp` read-only and breaks them. Run every `./mvnw` command with the sandbox disabled.
- Module-scoped test runs need `-am` and, when filtering with `-Dtest=`, `-Dsurefire.failIfNoSpecifiedTests=false` (siblings aren't installed).

---

## Task 1: Reshape render-result to an artifacts list (contract + schema + all call sites)

This is an atomic contract reshape: the `RenderResult` record signature changes, so every constructor and every test reading `/content`/`/artifact_kind` for **render** results must change together to keep the build green. Export results (`uml-xmi+xml`, `archimate-oef+xml`) are a different contract — do not touch them.

**Files:**
- Create: `contracts/src/main/java/dev/dediren/contracts/render/RenderArtifact.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/render/RenderResult.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/ContractVersions.java:10`
- Modify: `schemas/render-result.schema.json`
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java:119-121`
- Modify: `testbeds/plugin-runtime/src/main/java/dev/dediren/testbeds/pluginruntime/Main.java:76-78,118-120`
- Modify: `dist-tool/src/main/java/dev/dediren/tools/dist/DistTool.java` (`assertSvgRenderOutput` reads `artifacts[0]`; leave `assertArtifactKind` export check as-is)
- Test (modify): `contracts/src/test/java/dev/dediren/contracts/ContractVersionsTest.java:17`
- Test (modify): `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java:414-419,452`
- Test (modify): `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java:44-49,1736-1738`
- Test (modify): `cli/src/test/java/dev/dediren/cli/MainTest.java` (render blocks only)
- Test (modify): `cli/src/test/java/dev/dediren/cli/CliLayoutRenderCommandTest.java:181-182`

- [ ] **Step 1: Update the contract round-trip test to the new shape (failing test first)**

In `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`, replace the `RenderResult` construction (lines 414-417) and the final assertion (line 452):

```java
        RenderResult result = new RenderResult(
                ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
                List.of(new dev.dediren.contracts.render.RenderArtifact("svg", "<svg></svg>")));
```

```java
        assertThat(JsonSupport.objectMapper().valueToTree(result).at("/artifacts/0/artifact_kind").asText())
                .isEqualTo("svg");
        assertThat(JsonSupport.objectMapper().valueToTree(result).at("/render_result_schema_version").asText())
                .isEqualTo("render-result.schema.v2");
```

In `contracts/src/test/java/dev/dediren/contracts/ContractVersionsTest.java:17`, update the expected constant:

```java
        assertThat(ContractVersions.RENDER_RESULT_SCHEMA_VERSION).isEqualTo("render-result.schema.v2");
```

- [ ] **Step 2: Run the contracts tests to verify they fail to compile/pass**

Run: `./mvnw -pl contracts test -Dtest=ContractVersionsTest,ContractRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `RenderArtifact` does not exist / `RenderResult` constructor mismatch.

- [ ] **Step 3: Create the `RenderArtifact` record**

`contracts/src/main/java/dev/dediren/contracts/render/RenderArtifact.java`:

```java
package dev.dediren.contracts.render;

public record RenderArtifact(String artifactKind, String content) {
}
```

- [ ] **Step 4: Change `RenderResult` to hold an artifacts list**

Replace the entire body of `contracts/src/main/java/dev/dediren/contracts/render/RenderResult.java`:

```java
package dev.dediren.contracts.render;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record RenderResult(String renderResultSchemaVersion, List<RenderArtifact> artifacts) {
    public RenderResult {
        artifacts = listOrEmpty(artifacts);
    }
}
```

- [ ] **Step 5: Bump the schema-version constant**

`contracts/src/main/java/dev/dediren/contracts/ContractVersions.java:10`:

```java
    public static final String RENDER_RESULT_SCHEMA_VERSION = "render-result.schema.v2";
```

- [ ] **Step 6: Rewrite the render-result JSON schema**

Replace the whole of `schemas/render-result.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://dediren.dev/schemas/render-result.schema.json",
  "type": "object",
  "additionalProperties": false,
  "required": ["render_result_schema_version", "artifacts"],
  "properties": {
    "render_result_schema_version": { "const": "render-result.schema.v2" },
    "artifacts": {
      "type": "array",
      "minItems": 1,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["artifact_kind", "content"],
        "properties": {
          "artifact_kind": { "enum": ["svg", "html"] },
          "content": { "type": "string" }
        }
      }
    }
  }
}
```

- [ ] **Step 7: Update the svg-render plugin construction**

In `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`, replace lines 119-121:

```java
        String content = renderSvg(input.layoutResult(), input.renderMetadata(), input.policy());
        var result = new RenderResult(
                ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
                List.of(new RenderArtifact("svg", content)));
        stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(result)));
```

Add imports near the other contract imports at the top of the file:

```java
import dev.dediren.contracts.render.RenderArtifact;
import java.util.List;
```

(If `java.util.List` is already imported, skip the duplicate.)

- [ ] **Step 8: Update the runtime testbed construction**

In `testbeds/plugin-runtime/src/main/java/dev/dediren/testbeds/pluginruntime/Main.java`, replace the two `new RenderResult(...)` calls (lines 76-78 and 118-120):

```java
                        JsonSupport.objectMapper().valueToTree(new RenderResult(
                                ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
                                List.of(new RenderArtifact("svg", "x".repeat(1024 * 1024)))))));
```

```java
            case "render" -> mapper.valueToTree(new RenderResult(
                    ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
                    List.of(new RenderArtifact("svg", "<svg data-input-length=\"" + inputLength + "\"></svg>"))));
```

Add imports:

```java
import dev.dediren.contracts.render.RenderArtifact;
import java.util.List;
```

(`java.util.List` is already imported in this file — verify and skip if present.)

- [ ] **Step 9: Update the svg-render test helper and the direct-shape test**

In `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`, change the `okContent` helper (line 1736-1738) so every existing call site keeps working by reading the first artifact:

```java
    private static String okContent(PluginResult result) throws Exception {
        return okData(result).at("/artifacts/0/content").asText();
    }
```

Update the `outputsSvg` test body (lines 44-49):

```java
            JsonNode data = okData(render(renderInput("fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json")));

            String content = data.at("/artifacts/0/content").asText();

            assertThat(data.at("/render_result_schema_version").asText()).isEqualTo("render-result.schema.v2");
            assertThat(data.at("/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
            assertThat(content).contains("<svg", "Client", "API");
```

- [ ] **Step 10: Update the CLI render assertions**

In `cli/src/test/java/dev/dediren/cli/MainTest.java`, there are six **render** blocks that read the SVG result (lines ~129-130, 244-245, 345-346, 460-461, 582-583, 701-702). Each currently reads:

```java
        String svg = renderData.at("/content").asText();
        assertThat(renderData.at("/artifact_kind").asText()).isEqualTo("svg");
```

Replace each with:

```java
        String svg = renderData.at("/artifacts/0/content").asText();
        assertThat(renderData.at("/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
```

Do **not** change the export blocks (`exportData.at("/content")` / `"uml-xmi+xml"`); those are export results.

In `cli/src/test/java/dev/dediren/cli/CliLayoutRenderCommandTest.java:181-182`, replace:

```java
        assertThat(envelope.at("/data/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
        assertThat(envelope.at("/data/artifacts/0/content").asText())
```

Leave the export assertions at lines 208-209 and 234-235 unchanged.

- [ ] **Step 11: Build everything and run the affected suites**

Run: `./mvnw -pl contracts,plugins/svg-render,cli,testbeds/plugin-runtime -am test` (sandbox disabled)
Expected: PASS. If a CLI render block was missed, the failure message points at the exact `/content` path — fix it the same way.

- [ ] **Step 12: Commit**

```bash
git add contracts/src/main/java/dev/dediren/contracts/render/RenderArtifact.java \
        contracts/src/main/java/dev/dediren/contracts/render/RenderResult.java \
        contracts/src/main/java/dev/dediren/contracts/ContractVersions.java \
        schemas/render-result.schema.json \
        plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java \
        testbeds/plugin-runtime/src/main/java/dev/dediren/testbeds/pluginruntime/Main.java \
        contracts/src/test/java/dev/dediren/contracts/ContractVersionsTest.java \
        contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java \
        plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java \
        cli/src/test/java/dev/dediren/cli/MainTest.java \
        cli/src/test/java/dev/dediren/cli/CliLayoutRenderCommandTest.java
git commit -m "feat(contracts): render-result v2 artifacts list"
```

---

## Task 2: Add policy fields (`interactive` mode + `style.interaction`)

Add the policy surface only — the renderer ignores the new fields until Task 3, so behavior is unchanged here.

**Files:**
- Create: `contracts/src/main/java/dev/dediren/contracts/render/SvgInteractionStyle.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/render/RenderPolicy.java`
- Modify: `contracts/src/main/java/dev/dediren/contracts/render/SvgStylePolicy.java`
- Modify: `schemas/svg-render-policy.schema.json`
- Test (modify): `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`

- [ ] **Step 1: Write the failing round-trip assertion**

In `contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java`, inside `renderContractsRoundTripPoliciesMetadataAndResults()`, after the existing `decoratorPolicy` assertions, add:

```java
        var interactivePolicy = JsonSupport.readValue("""
                {
                  "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
                  "interactive": "both",
                  "page": { "width": 640, "height": 360 },
                  "margin": { "top": 24, "right": 24, "bottom": 24, "left": 24 },
                  "style": {
                    "interaction": {
                      "highlight_stroke": "#ff8800",
                      "highlight_stroke_width": 5
                    }
                  }
                }
                """, RenderPolicy.class);

        assertThat(interactivePolicy.interactive()).isEqualTo("both");
        assertThat(interactivePolicy.style().interaction().highlightStroke()).isEqualTo("#ff8800");
        assertThat(interactivePolicy.style().interaction().highlightStrokeWidth()).isEqualTo(5.0);
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -pl contracts test -Dtest=ContractRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `interactive()` / `interaction()` methods do not exist.

- [ ] **Step 3: Create the interaction style record**

`contracts/src/main/java/dev/dediren/contracts/render/SvgInteractionStyle.java`:

```java
package dev.dediren.contracts.render;

public record SvgInteractionStyle(String highlightStroke, Double highlightStrokeWidth) {
}
```

- [ ] **Step 4: Add `interactive` to `RenderPolicy`**

Replace `contracts/src/main/java/dev/dediren/contracts/render/RenderPolicy.java`:

```java
package dev.dediren.contracts.render;

public record RenderPolicy(
        String svgRenderPolicySchemaVersion,
        String semanticProfile,
        Page page,
        Margin margin,
        SvgStylePolicy style,
        String interactive) {
}
```

- [ ] **Step 5: Add `interaction` to `SvgStylePolicy`**

In `contracts/src/main/java/dev/dediren/contracts/render/SvgStylePolicy.java`, add the field as the last component (after `groupOverrides`) and leave the compact constructor body unchanged:

```java
public record SvgStylePolicy(
        SvgBackgroundStyle background,
        SvgFontStyle font,
        SvgNodeStyle node,
        SvgEdgeStyle edge,
        SvgGroupStyle group,
        Map<String, SvgNodeStyle> nodeTypeOverrides,
        Map<String, SvgEdgeStyle> edgeTypeOverrides,
        Map<String, SvgGroupStyle> groupTypeOverrides,
        Map<String, SvgNodeStyle> nodeOverrides,
        Map<String, SvgEdgeStyle> edgeOverrides,
        Map<String, SvgGroupStyle> groupOverrides,
        SvgInteractionStyle interaction) {
```

(The `mapOrEmpty(...)` lines in the compact constructor stay as-is; `interaction` needs no normalization.)

- [ ] **Step 6: Add the schema properties**

In `schemas/svg-render-policy.schema.json`, add a top-level `interactive` property. After the `"semantic_profile": { ... }` block (ends at line ~15), insert:

```json
    "interactive": {
      "enum": ["none", "svg", "html", "both"]
    },
```

In the `$defs/style` object `properties` map (after `"group_overrides": { "$ref": "#/$defs/groupOverrideMap" }`), add:

```json
        ,"interaction": { "$ref": "#/$defs/interaction" }
```

In `$defs`, add a new `interaction` definition (place it alongside the other `$defs` entries, reusing the existing `color` def):

```json
    "interaction": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "highlight_stroke": { "$ref": "#/$defs/color" },
        "highlight_stroke_width": { "type": "number", "minimum": 0, "maximum": 24 }
      }
    },
```

- [ ] **Step 7: Run the contract test to verify it passes**

Run: `./mvnw -pl contracts test -Dtest=ContractRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS.

- [ ] **Step 8: Confirm existing policy fixtures still validate against the schema**

Run: `./mvnw -pl contracts,plugins/svg-render -am test` (sandbox disabled)
Expected: PASS — existing fixtures omit the new optional fields and remain valid.

- [ ] **Step 9: Commit**

```bash
git add contracts/src/main/java/dev/dediren/contracts/render/SvgInteractionStyle.java \
        contracts/src/main/java/dev/dediren/contracts/render/RenderPolicy.java \
        contracts/src/main/java/dev/dediren/contracts/render/SvgStylePolicy.java \
        schemas/svg-render-policy.schema.json \
        contracts/src/test/java/dev/dediren/contracts/ContractRoundTripTest.java
git commit -m "feat(contracts): add interactive mode and interaction style to render policy"
```

---

## Task 3: Graph renderer emits edge endpoints + interaction layer

Default mode is `svg`, so a render with no `interactive` field now produces an interactive SVG. The interaction markup is emitted only when the resolved mode is not `none`, and only for the graph renderer (not UML sequence).

**Files:**
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java` (`renderSvg` graph branch, edge loop, closing append; add helpers)
- Test (modify): `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [ ] **Step 1: Write the failing tests**

In `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`, inside the `RenderContracts` nested class, add:

```java
        @Test
        void defaultModeEmitsInteractionLayerAndEdgeEndpoints() throws Exception {
            String content = okContent(render(renderInput(
                    "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json")));

            assertThat(content).contains("data-dediren-edge-source", "data-dediren-edge-target");
            assertThat(content).contains("<style", "dediren-edge-highlighted");
            assertThat(content).contains("<script", "data-dediren-node-id");

            Document document = svgDocument(content);
            var edges = document.getElementsByTagName("g");
            boolean anyEdgeHasSource = false;
            for (int i = 0; i < edges.getLength(); i++) {
                Element g = (Element) edges.item(i);
                if (!g.getAttribute("data-dediren-edge-id").isEmpty()) {
                    assertThat(g.getAttribute("data-dediren-edge-source")).isNotEmpty();
                    assertThat(g.getAttribute("data-dediren-edge-target")).isNotEmpty();
                    anyEdgeHasSource = true;
                }
            }
            assertThat(anyEdgeHasSource).isTrue();
        }

        @Test
        void noneModeSuppressesInteractionLayer() throws Exception {
            ObjectNode input = (ObjectNode) renderInput(
                    "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json");
            ((ObjectNode) input.at("/policy")).put("interactive", "none");

            String content = okContent(render(input));

            assertThat(content).doesNotContain("data-dediren-edge-source");
            assertThat(content).doesNotContain("<script");
            assertThat(content).doesNotContain("dediren-edge-highlighted");
        }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — no `data-dediren-edge-source`, no `<script>` in default output.

- [ ] **Step 3: Add mode + interaction-style resolution helpers**

In `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`, add these helpers (place near the other private static helpers, e.g. just below `renderSvg`). Add `import dev.dediren.contracts.render.SvgInteractionStyle;` at the top.

```java
    private static final String DEFAULT_HIGHLIGHT_STROKE = "#1f6feb";
    private static final double DEFAULT_HIGHLIGHT_STROKE_WIDTH = 3.0;

    private static String interactiveMode(RenderPolicy policy) {
        String mode = policy.interactive();
        return mode == null ? "svg" : mode;
    }

    private static String interactionStyleBlock(RenderPolicy policy) {
        String stroke = DEFAULT_HIGHLIGHT_STROKE;
        double width = DEFAULT_HIGHLIGHT_STROKE_WIDTH;
        SvgInteractionStyle interaction = policy.style() == null ? null : policy.style().interaction();
        if (interaction != null) {
            if (interaction.highlightStroke() != null) {
                stroke = interaction.highlightStroke();
            }
            if (interaction.highlightStrokeWidth() != null) {
                width = interaction.highlightStrokeWidth();
            }
        }
        return "<style>g.dediren-edge-highlighted &gt; path{stroke:" + attr(stroke)
                + ";stroke-width:" + styleNumber(width) + ";}</style>";
    }

    private static String interactionScriptBlock() {
        return "<script>//<![CDATA[\n"
                + "(function(){\n"
                + "var root=document.currentScript&&document.currentScript.closest?document.currentScript.closest('svg'):null;\n"
                + "if(!root){root=document.querySelector('svg');}\n"
                + "if(!root){return;}\n"
                + "var selected=null;\n"
                + "function clear(){var hl=root.querySelectorAll('.dediren-edge-highlighted');for(var i=0;i<hl.length;i++){hl[i].classList.remove('dediren-edge-highlighted');}selected=null;}\n"
                + "function select(id){clear();var edges=root.querySelectorAll('[data-dediren-edge-source]');for(var i=0;i<edges.length;i++){var e=edges[i];if(e.getAttribute('data-dediren-edge-source')===id||e.getAttribute('data-dediren-edge-target')===id){e.classList.add('dediren-edge-highlighted');}}selected=id;}\n"
                + "root.addEventListener('click',function(ev){var t=ev.target;var n=t.closest?t.closest('[data-dediren-node-id]'):null;if(n){var id=n.getAttribute('data-dediren-node-id');if(id===selected){clear();}else{select(id);}}else{clear();}});\n"
                + "document.addEventListener('keydown',function(ev){if(ev.key==='Escape'){clear();}});\n"
                + "})();\n"
                + "//]]></script>";
    }
```

Note: `&gt;` is used inside the `<style>` text so the child combinator survives XML serialization; browsers parse the CSS `>` correctly. `attr(...)` and `styleNumber(...)` are existing helpers in this file.

- [ ] **Step 4: Emit the style block and edge endpoint attributes in the graph branch**

In `renderSvg`, the graph branch builds `svg`. Capture the mode once at the start of the graph branch (after the UML sequence early return at line ~133-135):

```java
        boolean interactive = !"none".equals(interactiveMode(policy));
```

After the background `<rect ... />` append (the `String.format(... "<rect ... fill=\"%s\"/>" ...)` at lines ~148-155) and before the content group `<g font-family=...>` append, add:

```java
        if (interactive) {
            svg.append(interactionStyleBlock(policy));
        }
```

In the edge loop, change the edge group open (line 194) to include endpoints when interactive:

```java
            svg.append("<g data-dediren-edge-id=\"").append(attr(edge.id())).append("\"");
            if (interactive) {
                svg.append(" data-dediren-edge-source=\"").append(attr(edge.source()))
                        .append("\" data-dediren-edge-target=\"").append(attr(edge.target())).append("\"");
            }
            svg.append(">");
```

Change the closing append (line 219) from `svg.append("</g></svg>\n");` to:

```java
        svg.append("</g>");
        if (interactive) {
            svg.append(interactionScriptBlock());
        }
        svg.append("</svg>\n");
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS — including the existing structural tests (scoped `groupWithAttribute` assertions are unaffected by the added top-level `<style>`/`<script>` and the extra edge attributes).

- [ ] **Step 6: Commit**

```bash
git add plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java \
        plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "feat(svg-render): emit edge endpoints and node-select highlight layer"
```

---

## Task 4: Packaging — `html` and `both` artifacts + mode validation

**Files:**
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java` (`renderFromStdin`, add `buildArtifacts`/`htmlWrap`)
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/RenderInputValidator.java` (`validateRenderPolicy`)
- Create: `fixtures/render-policy/interactive-svg.json`
- Test (modify): `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [ ] **Step 1: Create the interactive fixture**

`fixtures/render-policy/interactive-svg.json`:

```json
{
  "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
  "interactive": "both",
  "page": { "width": 1200, "height": 800 },
  "margin": { "top": 32, "right": 32, "bottom": 32, "left": 32 },
  "style": {
    "interaction": {
      "highlight_stroke": "#ff8800",
      "highlight_stroke_width": 5
    }
  }
}
```

- [ ] **Step 2: Write the failing tests**

In `MainTest.java` `RenderContracts`, add:

```java
        @Test
        void htmlModeWrapsInteractiveSvg() throws Exception {
            ObjectNode input = (ObjectNode) renderInput(
                    "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json");
            ((ObjectNode) input.at("/policy")).put("interactive", "html");

            JsonNode data = okData(render(input));

            assertThat(data.at("/artifacts").size()).isEqualTo(1);
            assertThat(data.at("/artifacts/0/artifact_kind").asText()).isEqualTo("html");
            String html = data.at("/artifacts/0/content").asText();
            assertThat(html).startsWith("<!DOCTYPE html");
            assertThat(html).contains("<svg", "<script", "data-dediren-edge-source");
        }

        @Test
        void bothModeReturnsSvgThenHtml() throws Exception {
            JsonNode data = okData(render(renderInput(
                    "fixtures/layout-result/basic.json", "fixtures/render-policy/interactive-svg.json")));

            assertThat(data.at("/artifacts").size()).isEqualTo(2);
            assertThat(data.at("/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
            assertThat(data.at("/artifacts/1/artifact_kind").asText()).isEqualTo("html");
            assertThat(data.at("/artifacts/0/content").asText()).startsWith("<svg");
            assertThat(data.at("/artifacts/1/content").asText()).startsWith("<!DOCTYPE html");
        }

        @Test
        void invalidInteractiveModeIsRejected() throws Exception {
            ObjectNode input = (ObjectNode) renderInput(
                    "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json");
            ((ObjectNode) input.at("/policy")).put("interactive", "bogus");

            error(render(input), "DEDIREN_SVG_POLICY_INVALID");
        }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — only an `svg` artifact is produced; `bogus` is not rejected.

- [ ] **Step 4: Validate the interactive mode value**

In `RenderInputValidator.java`, at the very top of `validateRenderPolicy(RenderPolicy policy)` — **before** the `style == null` early return — add:

```java
        String interactive = policy.interactive();
        if (interactive != null
                && !interactive.equals("none")
                && !interactive.equals("svg")
                && !interactive.equals("html")
                && !interactive.equals("both")) {
            throw new PolicyValidationException(
                    "interactive", "SVG render policy interactive must be one of none, svg, html, both");
        }
```

- [ ] **Step 5: Build the artifact list in `renderFromStdin`**

In `Main.java`, replace the result construction added in Task 1 (the `renderSvg(...)` + `new RenderResult(...)` lines) with:

```java
        String svg = renderSvg(input.layoutResult(), input.renderMetadata(), input.policy());
        var result = new RenderResult(
                ContractVersions.RENDER_RESULT_SCHEMA_VERSION,
                buildArtifacts(interactiveMode(input.policy()), svg));
        stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(result)));
```

Add the packaging helpers near the other helpers:

```java
    private static List<RenderArtifact> buildArtifacts(String mode, String svg) {
        return switch (mode) {
            case "html" -> List.of(new RenderArtifact("html", htmlWrap(svg)));
            case "both" -> List.of(new RenderArtifact("svg", svg), new RenderArtifact("html", htmlWrap(svg)));
            default -> List.of(new RenderArtifact("svg", svg));
        };
    }

    private static String htmlWrap(String svg) {
        return "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
                + "<title>dediren diagram</title></head>\n<body>\n" + svg + "</body>\n</html>\n";
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java \
        plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/RenderInputValidator.java \
        fixtures/render-policy/interactive-svg.json \
        plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "feat(svg-render): package interactive output as svg/html/both"
```

---

## Task 5: Interaction style overrides reflected in CSS

**Files:**
- Test (modify): `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

(The implementation already reads the override in Task 3's `interactionStyleBlock`; this task locks the behavior with a test.)

- [ ] **Step 1: Write the failing test**

In `MainTest.java` `RenderContracts`, add:

```java
        @Test
        void interactionStyleOverridesAppearInCss() throws Exception {
            String svg = okData(render(renderInput(
                    "fixtures/layout-result/basic.json", "fixtures/render-policy/interactive-svg.json")))
                    .at("/artifacts/0/content").asText();

            assertThat(svg).contains("stroke:#ff8800", "stroke-width:5");
        }

        @Test
        void interactionStyleDefaultsWhenOmitted() throws Exception {
            String svg = okContent(render(renderInput(
                    "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json")));

            assertThat(svg).contains("stroke:#1f6feb", "stroke-width:3");
        }
```

- [ ] **Step 2: Run them**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS (implementation from Task 3 already supports this). `styleNumber` renders whole-number doubles without a decimal (`3.0`→`"3"`, `5.0`→`"5"`), so `stroke-width:3` and `stroke-width:5` are exact matches.

- [ ] **Step 3: Commit**

```bash
git add plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "test(svg-render): lock interaction highlight style overrides"
```

---

## Task 6: Docs, full verification, and distribution smoke

**Files:**
- Modify: `README.md` (render extraction examples + a short interactive-modes note)
- Modify: `docs/agent-usage.md` (render extraction examples)

- [ ] **Step 1: Update README render extraction**

In `README.md`, the render examples pipe `jq -r '.data.content'` to a file (lines ~143, 206, 215, 269, and similar). For SVG render results, change the extraction to select from the artifacts list. Replace each render extraction line of the form:

```bash
jq -r '.data.content' render-result.json > diagram.svg
```

with:

```bash
jq -r '.data.artifacts[] | select(.artifact_kind=="svg") | .content' render-result.json > diagram.svg
```

Apply the same change to the UML sequence/state-machine render extractions (the `sequence-render-result.json`, `state-machine-render-result.json`, etc. SVG extractions). Leave **export** extractions (`.xmi`, OEF) untouched — those are export results and keep `.data.content`.

Add a short note in the render section describing the modes:

```markdown
The SVG render policy accepts an optional `interactive` mode: `none` (static),
`svg` (default — a self-contained interactive SVG that highlights a node's
edges on click), `html` (an HTML page wrapping the interactive SVG), or `both`
(an `svg` and an `html` artifact). The render result returns an ordered
`artifacts[]` list; select the artifact you want by `artifact_kind`. Optional
`style.interaction.highlight_stroke` and `style.interaction.highlight_stroke_width`
control the highlight appearance.
```

- [ ] **Step 2: Update docs/agent-usage.md render extraction**

In `docs/agent-usage.md`, change the SVG render extraction lines (`jq -r '.data.content' ... > *.svg` at lines ~129, 175, 230, 287, 341, 394, 454 where the output is `.svg`) to:

```bash
jq -r '.data.artifacts[] | select(.artifact_kind=="svg") | .content' render-result.json > diagram.svg
```

(matching each file's variable/output names). Leave `.xmi`/OEF export extractions unchanged.

- [ ] **Step 3: Run the doc whitespace check and the SVG/CLI suites**

Run: `git diff --check` (sandbox disabled if needed)
Expected: no whitespace errors.

Run: `./mvnw -pl plugins/svg-render,cli -am test` (sandbox disabled)
Expected: PASS.

- [ ] **Step 4: Full build + distribution smoke**

Run: `./mvnw test` (sandbox disabled)
Expected: PASS — this catches `AgentUsageDocConsistencyTest` (dist-tool) and any other version/doc-consistency tests.

Run: `./mvnw -pl dist-tool -am verify -Pdist-smoke` (sandbox disabled)
Expected: PASS — the packaged bundle renders end-to-end with the new artifacts shape.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/agent-usage.md
git commit -m "docs: interactive SVG modes and artifacts-list extraction"
```

---

## Post-integration follow-on (separate, not part of feature commits)

Per `## Versioning` in CLAUDE.md, once this work is integrated on `main` and is being released, add a **separate** version-bump commit (its own commit) bumping root `pom.xml` to the next within-month micro and creating the annotated `v<version>` tag. Do not fold the bump into any feature commit above. This is flagged for the integrator, not executed inside the feature tasks.

---

## Audit gates (per CLAUDE.md "Audit Gates" — SVG render row)

- **test-quality-audit (Quick):** changed contract/plugin/CLI tests — confirm the new render tests assert real behavior (artifact kinds/order, endpoint attributes, mode suppression, style overrides) rather than mirroring implementation strings only.
- **devsecops-audit (Quick):** schema, renderer, README, dependency posture — confirm no new runtime dependency was introduced (the interaction layer is hand-written vanilla JS), the embedded `<script>` is CDATA-wrapped and emits only renderer-controlled values, and `additionalProperties:false` is preserved on the new schema objects.

Fix block findings; fix or explicitly accept warn/info findings in the handoff, then rerun the affected `./mvnw` checks.

---

## Self-review (completed during planning)

- **Spec coverage:** modes `none|svg|html|both` default `svg` (Tasks 2-4); edges-only click highlight with Escape/empty-canvas clear (Task 3 script); policy-configurable highlight style with defaults (Tasks 2,3,5); multi-artifact `render-result.v2` (Task 1); UML sequence packaged but no script (graph-only injection in `renderSvg` graph branch, Task 3). All covered.
- **Type consistency:** `RenderArtifact(artifactKind, content)`, `RenderResult(renderResultSchemaVersion, List<RenderArtifact> artifacts)`, `SvgInteractionStyle(highlightStroke, highlightStrokeWidth)`, `RenderPolicy(..., interactive)` used consistently across tasks. JSON names are SNAKE_CASE (`artifact_kind`, `highlight_stroke`, `render_result_schema_version`). Helper names `interactiveMode`, `interactionStyleBlock`, `interactionScriptBlock`, `buildArtifacts`, `htmlWrap` are stable across Tasks 3-5.
- **Placeholder scan:** no TBD/TODO; every code step shows full code and exact expected output (`styleNumber` formatting confirmed: whole doubles render without a decimal).

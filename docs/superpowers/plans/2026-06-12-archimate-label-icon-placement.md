# ArchiMate Label / Icon Placement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep ArchiMate node names centered while making boxes size to fit name + corner icon, and move junction labels below their circle so nothing overlaps the type icon or the filled junction dot.

**Architecture:** Two plugins change. `plugins/generic-graph` (`GenericGraphLayoutSizing`) replaces the flat 160×80 ArchiMate node hint with label-aware width/height that reserves the corner-icon column. `plugins/svg-render` (`Main`) keeps labels centered but (a) reserves the icon column in the label width budget, (b) shrinks the font when the block is too tall, and (c) routes And/Or junction labels to a position below the circle. All changes are internal layout/render details — no schema, contract, or render-policy change.

**Tech Stack:** Java 21, Maven Wrapper (`./mvnw`), JUnit 5 + AssertJ.

> **Sandbox note:** All `./mvnw` test commands in this plan must run with the command sandbox disabled — JUnit `@TempDir` needs a writable `/tmp`. Module-scoped runs use `-am`.

> **Coupling note:** The per-side icon reserve is defined twice on purpose — `ARCHIMATE_LABEL_ICON_RESERVE = 34.0` in both `GenericGraphLayoutSizing` (sizing) and `Main` (rendering). They must stay equal so a sized box fits the reserved label. Each definition carries a comment pointing at the other.

---

## Task 1: Label-aware ArchiMate node sizing (`plugins/generic-graph`)

**Files:**
- Modify: `plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java`
- Test: `plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java`

- [ ] **Step 1: Write the failing test**

Add this method inside `GenericGraphPluginTest` (next to `projectsArchimateJunctionsAsSmallLayoutNodes`, around line 970):

```java
    @Test
    void sizesArchimateNodesToFitLabelAndCornerIcon() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"project", "--target", "layout-request", "--view", "main"},
                """
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "2026.06.4" },
                    { "id": "archimate-oef", "version": "2026.06.4" }
                  ],
                  "nodes": [
                    { "id": "short", "type": "ApplicationComponent", "label": "API", "properties": {} },
                    { "id": "long", "type": "ApplicationComponent", "label": "Application Collaboration", "properties": {} },
                    { "id": "flow-junction", "type": "AndJunction", "label": "", "properties": {} }
                  ],
                  "relationships": [
                    { "id": "short-to-long", "type": "Association", "source": "long", "target": "short", "label": "", "properties": {} }
                  ],
                  "plugins": {
                    "generic-graph": {
                      "semantic_profile": "archimate",
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["short", "long", "flow-junction"],
                          "relationships": ["short-to-long"]
                        }
                      ]
                    }
                  }
                }
                """);

        JsonNode data = okData(result);
        double shortWidth = layoutRequestNode(data, "short").at("/width_hint").asDouble();
        double longWidth = layoutRequestNode(data, "long").at("/width_hint").asDouble();
        double shortHeight = layoutRequestNode(data, "short").at("/height_hint").asDouble();

        assertThat(shortWidth).isEqualTo(160.0);
        assertThat(shortHeight).isEqualTo(80.0);
        assertThat(longWidth).isGreaterThan(shortWidth);
        assertThat(layoutRequestNode(data, "flow-junction").at("/width_hint").asDouble()).isEqualTo(28.0);
        assertThat(layoutRequestNode(data, "flow-junction").at("/height_hint").asDouble()).isEqualTo(28.0);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run (sandbox disabled):
```bash
./mvnw -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest#sizesArchimateNodesToFitLabelAndCornerIcon -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — `longWidth` equals `shortWidth` (both 160.0) because ArchiMate sizing is currently flat.

- [ ] **Step 3: Add the label-aware sizing implementation**

In `GenericGraphLayoutSizing.java`, add constants after line 20 (`UML_OPERATION_COMPARTMENT_EXTRA`):

```java
    private static final double ARCHIMATE_MIN_WIDTH = 160.0;
    private static final double ARCHIMATE_MIN_HEIGHT = 80.0;
    private static final double ARCHIMATE_TEXT_CHAR_WIDTH = 8.7;
    // Must equal ARCHIMATE_LABEL_ICON_RESERVE in plugins/svg-render Main: per-side
    // room reserved so a centered label clears the upper-right type icon.
    private static final double ARCHIMATE_LABEL_ICON_RESERVE = 34.0;
    private static final double ARCHIMATE_LINE_HEIGHT = 18.0;
    private static final double ARCHIMATE_VERTICAL_PADDING = 28.0;
```

In `widthHint`, add an ArchiMate branch immediately after the connector check (after line 28, before the UML checks):

```java
        if (semanticProfile.equals("archimate")) {
            return archimateWidthHint(sourceNode);
        }
```

In `heightHint`, add the matching branch immediately after its connector check (after line 61):

```java
        if (semanticProfile.equals("archimate")) {
            return archimateHeightHint(sourceNode);
        }
```

Add these private helpers (e.g. just before `umlSequenceWidthHint` at line 91):

```java
    private static double archimateWidthHint(SourceNode sourceNode) {
        double content = archimateLongestTokenChars(sourceNode.label()) * ARCHIMATE_TEXT_CHAR_WIDTH
                + 2.0 * ARCHIMATE_LABEL_ICON_RESERVE;
        return roundUp(Math.max(content, ARCHIMATE_MIN_WIDTH), 10.0);
    }

    private static double archimateHeightHint(SourceNode sourceNode) {
        double widthBudget = archimateWidthHint(sourceNode) - 2.0 * ARCHIMATE_LABEL_ICON_RESERVE;
        double content = archimateEstimatedLineCount(sourceNode.label(), widthBudget) * ARCHIMATE_LINE_HEIGHT
                + ARCHIMATE_VERTICAL_PADDING;
        return roundUp(Math.max(content, ARCHIMATE_MIN_HEIGHT), 10.0);
    }

    private static int archimateLongestTokenChars(String label) {
        int longest = 0;
        for (String token : label.trim().split("\\s+")) {
            longest = Math.max(longest, token.length());
        }
        return Math.max(longest, 1);
    }

    private static int archimateEstimatedLineCount(String label, double widthBudget) {
        if (widthBudget <= 0.0) {
            return 1;
        }
        double total = label.trim().length() * ARCHIMATE_TEXT_CHAR_WIDTH;
        return Math.max(1, (int) Math.ceil(total / widthBudget));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run (sandbox disabled):
```bash
./mvnw -pl plugins/generic-graph -am test -Dtest=GenericGraphPluginTest#sizesArchimateNodesToFitLabelAndCornerIcon -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS. (`long` = "Application Collaboration", longest token "Collaboration" = 13 chars → `13×8.7 + 68 = 181.1` → roundUp 190 > 160; `short` = "API" → `3×8.7 + 68 = 94.1` → floored to 160; junction stays 28.)

- [ ] **Step 5: Run the full generic-graph suite to catch regressions**

Run (sandbox disabled):
```bash
./mvnw -pl plugins/generic-graph -am test
```
Expected: PASS. (No existing test pins ArchiMate `width_hint`/`height_hint`, so nothing should break; if a fixture-driven test fails, inspect whether it relied on the old flat 160×80 and update its expectation to the label-aware value.)

- [ ] **Step 6: Commit**

```bash
git add plugins/generic-graph/src/main/java/dev/dediren/plugins/genericgraph/GenericGraphLayoutSizing.java plugins/generic-graph/src/test/java/dev/dediren/plugins/genericgraph/GenericGraphPluginTest.java
git commit -m "feat(generic-graph): size archimate nodes to fit label and corner icon"
```

---

## Task 2: Reserve the icon column and keep labels centered (`plugins/svg-render`)

**Files:**
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`
- Test: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [ ] **Step 1: Write the failing test**

Add this method inside the ArchiMate nested test class in `MainTest` (next to `rendersDetailedArchimateIconMorphology`, around line 1010). It renders one icon-bearing two-line node at a representative size and checks the label is centered (not pushed down) and clears the icon column:

```java
        @Test
        void archimateLabelStaysCenteredAndClearsCornerIcon() throws Exception {
            JsonNode input = archimateRenderInput(
                    fixtureJson("fixtures/render-policy/archimate-svg.json"),
                    """
                    [
                      { "id": "appcomp", "source_id": "appcomp", "projection_id": "appcomp", "x": 40, "y": 40, "width": 190, "height": 80, "label": "Application Component" }
                    ]
                    """,
                    "[]",
                    """
                    {
                      "appcomp": { "type": "ApplicationComponent", "source_id": "appcomp" }
                    }
                    """);

            Document document = svgDocument(okContent(render(input)));
            Element node = groupWithAttribute(document, "data-dediren-node-id", "appcomp");
            Element label = (Element) node.getElementsByTagName("text").item(0);

            // Centered vertically: middle baseline, anchored at/above the node center (never pushed below it).
            assertThat(label.getAttribute("dominant-baseline")).isEqualTo("middle");
            double labelY = Double.parseDouble(label.getAttribute("y"));
            assertThat(labelY).isBetween(60.0, 80.0); // node center = 80; first of two lines sits just above it

            // Wraps to two lines, each centered on the node center x.
            org.w3c.dom.NodeList tspans = label.getElementsByTagName("tspan");
            assertThat(tspans.getLength()).isEqualTo(2);
            assertThat(Double.parseDouble(label.getAttribute("x"))).isEqualTo(135.0); // 40 + 190/2

            // Widest line clears the icon column (icon left edge = x + width - 28 = 202).
            double fontSize = Double.parseDouble(label.getAttribute("font-size"));
            int widestChars = 0;
            for (int i = 0; i < tspans.getLength(); i++) {
                widestChars = Math.max(widestChars, tspans.item(i).getTextContent().length());
            }
            double widestHalf = widestChars * fontSize * 0.62 / 2.0;
            assertThat(135.0 + widestHalf).isLessThanOrEqualTo(202.0);
        }
```

- [ ] **Step 2: Run test to verify it fails**

Run (sandbox disabled):
```bash
./mvnw -pl plugins/svg-render -am test -Dtest=MainTest#archimateLabelStaysCenteredAndClearsCornerIcon -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — with the current `width - 20` budget the label does not wrap to two lines (it stays one line "Application Component" that overruns the icon), so the tspan-count and clearance assertions fail.

- [ ] **Step 3: Add the icon-column reserve, height-aware shrink, and predicate**

In `Main.java`, add constants after line 55 (`ARCHIMATE_ICON_SIZE`):

```java
    // Must equal ARCHIMATE_LABEL_ICON_RESERVE in plugins/generic-graph
    // GenericGraphLayoutSizing: per-side room reserved so a centered label clears
    // the upper-right type icon.
    private static final double ARCHIMATE_LABEL_ICON_RESERVE = 34.0;
    private static final double NODE_LABEL_VERTICAL_PADDING = 8.0;
    private static final double NODE_LABEL_MIN_FONT_SIZE = 9.0;
```

Add this predicate next to `isUmlDecorator` (around line 2487):

```java
    private static boolean hasArchimateCornerIcon(SvgNodeDecorator decorator) {
        return decorator != null
                && !isUmlDecorator(decorator)
                && decorator != SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
                && decorator != SvgNodeDecorator.ARCHIMATE_OR_JUNCTION;
    }
```

Replace `nodeLabelMaxWidth` (lines 944-946) with a decorator-aware version:

```java
    private static double nodeLabelMaxWidth(LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
        double reserve = hasArchimateCornerIcon(style.decorator())
                ? 2.0 * ARCHIMATE_LABEL_ICON_RESERVE
                : 20.0;
        return Math.max(node.width() - reserve, fontSize * 3.0);
    }
```

Replace `nodeLabelLinesAndSize` (lines 848-859) with a version that threads `style` and shrinks for height too:

```java
    private static NodeLabelLines nodeLabelLinesAndSize(LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
        List<String> lines = wrappedNodeLabelLines(node, style, fontSize);
        double maxWidth = nodeLabelMaxWidth(node, style, fontSize);
        double widestLine = lines.stream()
                .mapToDouble(line -> estimateTextWidth(line, fontSize))
                .max()
                .orElse(0.0);
        double widthFontSize = widestLine > maxWidth ? fontSize * maxWidth / widestLine : fontSize;
        double availableHeight = node.height() - NODE_LABEL_VERTICAL_PADDING;
        double blockHeight = lines.size() * nodeLabelLineHeight(fontSize);
        double heightFontSize = blockHeight > availableHeight ? fontSize * availableHeight / blockHeight : fontSize;
        double labelFontSize = Math.max(Math.min(widthFontSize, heightFontSize), NODE_LABEL_MIN_FONT_SIZE);
        return new NodeLabelLines(lines, labelFontSize);
    }
```

Replace the `wrappedNodeLabelLines` signature line (line 861) to thread `style`:

```java
    private static List<String> wrappedNodeLabelLines(LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
```

and inside it replace the `nodeLabelMaxWidth` call (line 863) with:

```java
        double maxWidth = nodeLabelMaxWidth(node, style, fontSize);
```

Update the single caller in `nodeLabel` (line 812) to pass `style`:

```java
        NodeLabelLines label = nodeLabelLinesAndSize(node, style, fontSize);
```

- [ ] **Step 4: Run test to verify it passes**

Run (sandbox disabled):
```bash
./mvnw -pl plugins/svg-render -am test -Dtest=MainTest#archimateLabelStaysCenteredAndClearsCornerIcon -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS. (maxWidth = 190 − 68 = 122; "Application Component" wraps to "Application"/"Component", widest "Application" ≈ 95 < 122 so font stays 14; centered right edge 135 + 47.5 = 182.5 ≤ 202.)

- [ ] **Step 5: Run the full svg-render suite to catch regressions**

Run (sandbox disabled):
```bash
./mvnw -pl plugins/svg-render -am test
```
Expected: PASS. Existing ArchiMate tests use short labels ("Component", "Actor", "Data", "Node") that do not trigger new wrapping or shrink, so assertions should hold. If a label-position/font assertion changed, confirm the new value reflects the centered-with-reserve behavior and update it.

- [ ] **Step 6: Commit**

```bash
git add plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "feat(svg-render): reserve corner-icon column and keep archimate labels centered"
```

---

## Task 3: Junction labels render below the circle (`plugins/svg-render`)

**Files:**
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`
- Test: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [ ] **Step 1: Write the failing test**

Add this method inside the ArchiMate nested test class in `MainTest` (next to the test from Task 2):

```java
        @Test
        void archimateJunctionLabelRendersBelowCircle() throws Exception {
            JsonNode input = archimateRenderInput(
                    fixtureJson("fixtures/render-policy/archimate-svg.json"),
                    """
                    [
                      { "id": "j", "source_id": "j", "projection_id": "j", "x": 40, "y": 40, "width": 60, "height": 60, "label": "And Junction" }
                    ]
                    """,
                    "[]",
                    """
                    {
                      "j": { "type": "AndJunction", "source_id": "j" }
                    }
                    """);

            Document document = svgDocument(okContent(render(input)));
            Element node = groupWithAttribute(document, "data-dediren-node-id", "j");
            Element label = (Element) node.getElementsByTagName("text").item(0);

            // Circle: cx,cy = node center (70,70); radius = min(60,60)/2 - strokeWidth.
            double strokeWidth = Double.parseDouble(
                    ((Element) node.getElementsByTagName("circle").item(0)).getAttribute("stroke-width"));
            double radius = 30.0 - strokeWidth;
            double labelY = Double.parseDouble(label.getAttribute("y"));

            // Label sits below the filled circle, on the page background (not over the black fill).
            assertThat(labelY).isGreaterThan(70.0 + radius);
            assertThat(label.getAttribute("dominant-baseline")).isEmpty();
            assertThat(Double.parseDouble(label.getAttribute("x"))).isEqualTo(70.0);
        }
```

- [ ] **Step 2: Run test to verify it fails**

Run (sandbox disabled):
```bash
./mvnw -pl plugins/svg-render -am test -Dtest=MainTest#archimateJunctionLabelRendersBelowCircle -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: FAIL — today the junction label is centered in the box (`labelY` ≈ 70, the node center), which is not greater than `70 + radius`, and it carries `dominant-baseline="middle"`.

- [ ] **Step 3: Add the junction radius helper, predicate, and position branch**

In `Main.java`, add a junction-radius helper next to `nodeShape` (e.g. just before line 317):

```java
    private static double archimateJunctionRadius(LaidOutNode node, ResolvedNodeStyle style) {
        return Math.max(4.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
    }
```

In the junction branch of `nodeShape` (line 321), replace the inline radius computation:

```java
            double radius = archimateJunctionRadius(node, style);
```

Add the outside-label predicate next to `umlCompactControlNodeLabelOutside` (around line 942):

```java
    private static boolean archimateJunctionLabelOutside(SvgNodeDecorator decorator) {
        return decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
                || decorator == SvgNodeDecorator.ARCHIMATE_OR_JUNCTION;
    }
```

In `nodeLabelPosition`, add a junction branch as the FIRST check (immediately after line 920, before the `umlCompactControlNodeLabelOutside` check):

```java
        if (archimateJunctionLabelOutside(style.decorator())) {
            double radius = archimateJunctionRadius(node, style);
            double gap = 6.0;
            double firstLineY = node.y() + node.height() / 2.0 + radius + gap + fontSize;
            return new NodeLabelPosition(node.x() + node.width() / 2.0, firstLineY, false);
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run (sandbox disabled):
```bash
./mvnw -pl plugins/svg-render -am test -Dtest=MainTest#archimateJunctionLabelRendersBelowCircle -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS. (radius = 30 − 1.25 = 28.75; labelY = 70 + 28.75 + 6 + 14 = 118.75 > 98.75; baseline attribute absent because `centerBaseline` is false; x = 70.)

- [ ] **Step 5: Run the full svg-render suite**

Run (sandbox disabled):
```bash
./mvnw -pl plugins/svg-render -am test
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "feat(svg-render): render archimate junction labels below the circle"
```

---

## Task 4: Make the coverage artifact use representative sizes

**Files:**
- Modify: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java` (the `coversEachArchimateNodeTypeFromPolicy` builder, around lines 914-952)

This task improves the visual artifact so it mirrors real proportions; it has no new failing-test-first step (it changes an artifact-generating test, and the existing icon-morphology assertions in that test must keep passing).

- [ ] **Step 1: Size each node from its label and give it more height**

In `coversEachArchimateNodeTypeFromPolicy`, replace the hardcoded node dimensions. Change the node-building block (currently `"width": 128, "height": 68` with the literal `formatted(...)` args) so width is derived from the label's longest token and height is 80. Replace the `nodes.add(...)` call (lines 924-935) with:

```java
                // The renderer wraps camelCase type keys (e.g. "ImplementationEvent") into
                // separate lines, so size width from the longest camel/space token.
                int longestToken = 1;
                for (String token : nodeType.replaceAll("(?<=[a-z])(?=[A-Z])", " ").trim().split("\\s+")) {
                    longestToken = Math.max(longestToken, token.length());
                }
                int nodeWidth = (int) Math.max(160.0, Math.ceil((longestToken * 8.7 + 68.0) / 10.0) * 10.0);
                nodes.add(JsonSupport.objectMapper().readTree("""
                        {
                          "id": "%s",
                          "source_id": "%s",
                          "projection_id": "%s",
                          "x": %d,
                          "y": %d,
                          "width": %d,
                          "height": 80,
                          "label": "%s"
                        }
                        """.formatted(id, id, id, 32 + (index % 6) * 220, 40 + (index / 6) * 110, nodeWidth, nodeType)));
```

(The column pitch grows from 150 to 220 and the row pitch from 95 to 110 so the larger boxes do not overlap in the artifact.)

- [ ] **Step 2: Run the coverage test**

Run (sandbox disabled):
```bash
./mvnw -pl plugins/svg-render -am test -Dtest=MainTest#coversEachArchimateNodeTypeFromPolicy -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: PASS — the icon-kind/morphology assertions are size-independent and still hold.

- [ ] **Step 3: Rasterize and visually confirm**

Run:
```bash
magick -background white -density 150 .test-output/renders/svg-render-plugin/svg_renderer_covers_each_archimate_node_type.svg "$TMPDIR/archimate_after.png"
```
Then open `$TMPDIR/archimate_after.png` and confirm: every centered name clears its top-right icon, two-line names (Application/Technology families) sit centered, and And/Or junction names render below their circle. Report the path in the handoff.

- [ ] **Step 4: Commit**

```bash
git add plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "test(svg-render): size archimate coverage nodes representatively"
```

---

## Task 5: Cross-cutting verification and audit gates

**Files:** none (verification only).

- [ ] **Step 1: Run the combined module suite**

Run (sandbox disabled):
```bash
./mvnw -pl plugins/generic-graph,plugins/svg-render,cli -am test
```
Expected: PASS.

- [ ] **Step 2: Whitespace/diff hygiene**

Run:
```bash
git diff --check
```
Expected: no output.

- [ ] **Step 3: Audit gates (per CLAUDE.md SVG-render row)**

- Quick `souroldgeezer-audit:test-quality-audit` over the changed tests in `GenericGraphPluginTest` and `MainTest` (assertion strength, no vacuous checks).
- Quick `souroldgeezer-audit:devsecops-audit` over the diff (confirm no new dependencies or process boundaries; renderer/sizing only).

Fix any block findings; record warn/info findings or accept them in the handoff.

- [ ] **Step 4: Final handoff note**

Summarize: tasks completed, the rasterized artifact path from Task 4, full suite result, and any accepted audit findings. Do not stage `.test-output/` or generated `*.svg` artifacts (they are gitignored per CLAUDE.md).

---

## Self-Review

- **Spec coverage:**
  - §1 (centered + icon-column reserve) → Task 2.
  - §2 (label-aware sizing) → Task 1.
  - §3 (junction label below circle, positional readability) → Task 3.
  - §4 tests (sizing long>short/floor/connector; svg centered + clears icon; junction below circle; representative coverage artifact) → Tasks 1, 2, 3, 4.
  - Verification + audit gates → Task 5.
- **Placeholder scan:** none — every code step shows full code and exact commands.
- **Type consistency:** `ARCHIMATE_LABEL_ICON_RESERVE` (= 34.0) is defined in both modules with cross-referencing comments and used consistently; `nodeLabelMaxWidth`, `nodeLabelLinesAndSize`, and `wrappedNodeLabelLines` all gain the `ResolvedNodeStyle style` parameter and every call site is updated; `archimateJunctionRadius` is used in both `nodeShape` and `nodeLabelPosition`; `hasArchimateCornerIcon` and `archimateJunctionLabelOutside` predicates are referenced where defined.

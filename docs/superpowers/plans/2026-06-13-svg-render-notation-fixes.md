# SVG Render Notation Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct three ArchiMate/UML node-rendering defects in the `svg-render` plugin (square Interaction elements, solid-bordered Grouping, mis-routed Actor label) and record two reviewed-and-intentional behaviors as won't-fix comments.

**Architecture:** All rendering logic lives in one file, `plugins/svg-render/.../Main.java`. Node base-shape selection is in `nodeShape()`/`isArchimateRoundedRectangle()`; group-container borders in the `renderSvg()` group loop; UML decorator labels in `umlNodeDecorator()`. Each fix is a small, local change driven by a focused failing test in `MainTest.java`. The two coverage SVGs under `.test-output/` are git-ignored regenerated artifacts, used only as visual proof.

**Tech Stack:** Java 21, Maven Wrapper (`./mvnw`), JUnit 5 + AssertJ, w3c DOM assertions.

---

## Background: findings being addressed

From an independent review of the regenerated coverage renders
(`.test-output/renders/svg-render-plugin/svg_renderer_covers_each_archimate_node_type.svg`
and `..._uml_node_type.svg`):

| ID | Finding | Disposition | Mechanism (verified in source) |
|---|---|---|---|
| ARCH-V-001 | Business/Application/Technology **Interaction** render as square rectangles; every other behavior element is rounded. | **Fix** | The three interaction decorators are absent from `isArchimateRoundedRectangle()` (`Main.java:2539-2557`), so `nodeShape()` falls through to `archimate_rectangle`. |
| ARCH-V-003 | **Grouping** renders with a solid border; ArchiMate's defining notation is a dashed border. | **Fix** | `nodeShape()` (`Main.java:347-368`) emits a solid `archimate_rectangle` for `ARCHIMATE_GROUPING`; the group-container path (`Main.java:181-191`) is also solid. |
| ARCH-L-005 | **Actor** label sits above the figure (`y=615`, overlapping the head) instead of below. | **Fix** | `UML_ACTOR` is in `umlDecoratorSuppliesNodeLabel()` (`Main.java:2507-2513`), so `umlNodeDecorator()` routes it to the classifier-title branch (`Main.java:2254-2255` → `umlClassifierNotation()` `:2389`, `y = node.y()+15`). The correct actor branch (`Main.java:2264-2271`, `y = node.y()+height-8`, below the figure) is **unreachable dead code**. |
| ARCH-V-002 | UML compact control nodes (initial/activity-final/decision/merge) place labels diagonally up-left (~56px x offset). | **Won't-fix** (user decision) | Deliberate `umlCompactControlNodeLabelOutside()` diagonal placement (`Main.java:943-964`) for edge-avoidance. Document intent only. |
| ARCH-L-004 | UML final-state / pseudostate render no label. | **Won't-fix** (user decision) | Deliberate suppression in `shouldRenderPlainNodeLabel()` (`Main.java:2499-2505`); unnamed final/initial pseudostates are valid UML. Document intent only. |

The skill-reference "junction unsupported in source" note lives in the
`souroldgeezer-architecture` skill repo, not this repo, and is out of scope here.

## File Structure

- **Modify:** `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java`
  - `isArchimateRoundedRectangle(...)` — add three interaction decorators (Task 1).
  - `nodeShape(...)` — dashed border for `ARCHIMATE_GROUPING` node (Task 2).
  - `renderSvg(...)` group loop — dashed border for grouping containers (Task 2).
  - `umlNodeDecorator(...)` — route `UML_ACTOR` away from classifier branch (Task 3).
  - `umlCompactControlNodeLabelOutside(...)` and `shouldRenderPlainNodeLabel(...)` — won't-fix comments (Task 4).
- **Modify (tests):** `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`
  - Add three focused tests; extend the existing grouping-container test.
- **Regenerated (git-ignored, do not commit):** the two coverage SVGs under `.test-output/renders/svg-render-plugin/`.

## Conventions for every test below

- Add new `@Test` methods **inside the same `@Nested` test class** that already contains `coversEachArchimateNodeTypeFromPolicy()` / `rendersUmlClassCompartments()` (place each new test next to the related existing one).
- Reuse existing helpers already used throughout `MainTest.java`: `fixtureJson(...)`, `render(...)`, `okContent(...)`, `svgDocument(...)`, `semanticRenderInput(...)`, `groupWithAttribute(...)`, `firstElementWithAttribute(...)`, `firstChildElement(...)`, `JsonSupport.objectMapper()`. Do not add new helpers.
- `semanticRenderInput(profile, nodesArray, edgesArray, metadataNodesObject, metadataGroupsObject, policy)` is the builder used by the coverage tests (see `MainTest.java:953-959`).
- Run commands from repo root. Module-scoped runs need the documented flags:
  `-am -Dsurefire.failIfNoSpecifiedTests=false`, and the Maven sandbox must be
  disabled (JUnit `@TempDir` writes fail under the read-only sandbox `/tmp`).

---

### Task 1: ARCH-V-001 — Interaction elements render as rounded rectangles

**Files:**
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java` (`isArchimateRoundedRectangle`, lines 2539-2557)
- Test: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [ ] **Step 1: Write the failing test**

Add this method inside the same `@Nested` class, after `coversEachArchimateNodeTypeFromPolicy()`:

```java
        @Test
        void archimateInteractionElementsRenderRoundedRectangle() throws Exception {
            JsonNode policy = fixtureJson("fixtures/render-policy/archimate-svg.json");
            for (String type : java.util.List.of(
                    "BusinessInteraction", "ApplicationInteraction", "TechnologyInteraction")) {
                ArrayNode nodes = JsonSupport.objectMapper().createArrayNode();
                nodes.add(JsonSupport.objectMapper().readTree("""
                        {
                          "id": "n",
                          "source_id": "n",
                          "projection_id": "n",
                          "x": 40, "y": 40, "width": 180, "height": 80,
                          "label": "%s"
                        }
                        """.formatted(type)));
                ObjectNode metadataNodes = JsonSupport.objectMapper().createObjectNode();
                metadataNodes.set("n", JsonSupport.objectMapper().readTree("""
                        { "type": "%s", "source_id": "n" }
                        """.formatted(type)));

                Document document = svgDocument(okContent(render(semanticRenderInput(
                        "archimate",
                        nodes,
                        JsonSupport.objectMapper().createArrayNode(),
                        metadataNodes,
                        JsonSupport.objectMapper().createObjectNode(),
                        policy))));

                Element shape = firstElementWithAttribute(
                        groupWithAttribute(document, "data-dediren-node-id", "n"),
                        "data-dediren-node-shape");
                assertThat(shape.getAttribute("data-dediren-node-shape"))
                        .as("%s is a behavior element and must render rounded", type)
                        .isEqualTo("archimate_rounded_rectangle");
            }
        }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest='MainTest#archimateInteractionElementsRenderRoundedRectangle' -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — actual `archimate_rectangle`, expected `archimate_rounded_rectangle`.

- [ ] **Step 3: Write minimal implementation**

In `Main.java`, in `isArchimateRoundedRectangle(...)`, add the three interaction decorators to the return chain. Change the final two lines of the method from:

```java
                || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_PROCESS
                || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_EVENT;
    }
```

to:

```java
                || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_PROCESS
                || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_EVENT
                || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_INTERACTION
                || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_INTERACTION
                || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_INTERACTION;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest='MainTest#archimateInteractionElementsRenderRoundedRectangle' -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java \
        plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "fix(svg-render): render ArchiMate interaction elements as rounded rectangles"
```

---

### Task 2: ARCH-V-003 — Grouping renders with a dashed border

ArchiMate's Grouping is defined by a dashed boundary. Fix both surfaces where a
grouping is drawn: the node-shape path (coverage sheet) and the group-container
path (real groupings). Other group types stay solid.

**Files:**
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java` (`nodeShape` lines 347-368; group loop lines 181-191)
- Test: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [ ] **Step 1: Write the failing tests**

Add this method inside the same `@Nested` class, after the test added in Task 1:

```java
        @Test
        void archimateGroupingNodeRendersDashedBorder() throws Exception {
            JsonNode policy = fixtureJson("fixtures/render-policy/archimate-svg.json");
            ArrayNode nodes = JsonSupport.objectMapper().createArrayNode();
            nodes.add(JsonSupport.objectMapper().readTree("""
                    {
                      "id": "n",
                      "source_id": "n",
                      "projection_id": "n",
                      "x": 40, "y": 40, "width": 160, "height": 80,
                      "label": "Grouping"
                    }
                    """));
            ObjectNode metadataNodes = JsonSupport.objectMapper().createObjectNode();
            metadataNodes.set("n", JsonSupport.objectMapper().readTree("""
                    { "type": "Grouping", "source_id": "n" }
                    """));

            Document document = svgDocument(okContent(render(semanticRenderInput(
                    "archimate",
                    nodes,
                    JsonSupport.objectMapper().createArrayNode(),
                    metadataNodes,
                    JsonSupport.objectMapper().createObjectNode(),
                    policy))));

            Element shape = firstElementWithAttribute(
                    groupWithAttribute(document, "data-dediren-node-id", "n"),
                    "data-dediren-node-shape");
            assertThat(shape.getAttribute("data-dediren-node-shape")).isEqualTo("archimate_rectangle");
            assertThat(shape.getAttribute("stroke-dasharray")).isEqualTo("3 2");
        }
```

Also extend the existing `archimateGroupingMetadataRendersGroupDecorator()` test
(the container case). Immediately before its final
`childGroupWithAttribute(group, "data-dediren-group-decorator", "archimate_grouping");`
line, add:

```java
            Element groupRect = firstChildElement(group, "rect");
            assertThat(groupRect.getAttribute("stroke-dasharray")).isEqualTo("3 2");
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest='MainTest#archimateGroupingNodeRendersDashedBorder+archimateGroupingMetadataRendersGroupDecorator' -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — `stroke-dasharray` is empty (no attribute) on both the node rect and the container rect.

- [ ] **Step 3a: Implement the node-shape dashed border**

In `Main.java` `nodeShape(...)`, the block at lines 347-356 currently reads:

```java
        String shapeName = "archimate_rectangle";
        double rx = 0.0;
        if (decorator == null) {
            rx = style.rx();
        } else if (isArchimateCutCornerRectangle(decorator)) {
            return archimateCutCornerShape(node, style);
        } else if (isArchimateRoundedRectangle(decorator)) {
            rx = Math.max(1.0, style.rx());
            shapeName = "archimate_rounded_rectangle";
        }
        return String.format(
                Locale.ROOT,
                "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                shapeName,
                node.x(),
                node.y(),
                node.width(),
                node.height(),
                styleNumber(rx),
                attr(style.fill()),
                attr(style.stroke()),
                styleNumber(style.strokeWidth()));
```

Replace that entire block with (adds a `dashArray` suffix; ArchiMate Grouping is
the canonical dashed-boundary element, so it is special-cased here):

```java
        String shapeName = "archimate_rectangle";
        double rx = 0.0;
        String dashArray = "";
        if (decorator == null) {
            rx = style.rx();
        } else if (isArchimateCutCornerRectangle(decorator)) {
            return archimateCutCornerShape(node, style);
        } else if (isArchimateRoundedRectangle(decorator)) {
            rx = Math.max(1.0, style.rx());
            shapeName = "archimate_rounded_rectangle";
        } else if (decorator == SvgNodeDecorator.ARCHIMATE_GROUPING) {
            dashArray = " stroke-dasharray=\"3 2\"";
        }
        return String.format(
                Locale.ROOT,
                "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"%s/>",
                shapeName,
                node.x(),
                node.y(),
                node.width(),
                node.height(),
                styleNumber(rx),
                attr(style.fill()),
                attr(style.stroke()),
                styleNumber(style.strokeWidth()),
                dashArray);
```

- [ ] **Step 3b: Implement the group-container dashed border**

In `Main.java` `renderSvg(...)`, the group rectangle at lines 181-191 currently reads:

```java
            svg.append(String.format(
                    Locale.ROOT,
                    "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
                    group.x(),
                    group.y(),
                    group.width(),
                    group.height(),
                    styleNumber(style.rx()),
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth())));
```

Replace with (dash only grouping containers; all other group types stay solid):

```java
            String groupDashArray = style.decorator() == SvgNodeDecorator.ARCHIMATE_GROUPING
                    ? " stroke-dasharray=\"3 2\""
                    : "";
            svg.append(String.format(
                    Locale.ROOT,
                    "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"%s/>",
                    group.x(),
                    group.y(),
                    group.width(),
                    group.height(),
                    styleNumber(style.rx()),
                    attr(style.fill()),
                    attr(style.stroke()),
                    styleNumber(style.strokeWidth()),
                    groupDashArray));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest='MainTest#archimateGroupingNodeRendersDashedBorder+archimateGroupingMetadataRendersGroupDecorator' -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS (both)

- [ ] **Step 5: Commit**

```bash
git add plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java \
        plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "fix(svg-render): render ArchiMate grouping with a dashed border"
```

---

### Task 3: ARCH-L-005 — Actor label sits below the figure

`UML_ACTOR` must keep suppressing the generic plain label (it supplies its own),
but must NOT be drawn via the classifier-title path. Exclude it from the
classifier branch so the existing actor branch (`node.y()+height-8`, below the
figure) becomes reachable. It stays in `umlDecoratorSuppliesNodeLabel(...)` so
`shouldRenderPlainNodeLabel(...)` still returns false (no double label).

**Files:**
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java` (`umlNodeDecorator`, lines 2254-2255)
- Test: `plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java`

- [ ] **Step 1: Write the failing test**

Add this method inside the same `@Nested` class, after `rendersUmlClassCompartments()`:

```java
        @Test
        void umlActorLabelSitsBelowFigure() throws Exception {
            JsonNode policy = fixtureJson("fixtures/render-policy/uml-svg.json");
            ArrayNode nodes = JsonSupport.objectMapper().createArrayNode();
            nodes.add(JsonSupport.objectMapper().readTree("""
                    {
                      "id": "n",
                      "source_id": "n",
                      "projection_id": "n",
                      "x": 40, "y": 40, "width": 180, "height": 96,
                      "label": "Actor"
                    }
                    """));
            ObjectNode metadataNodes = JsonSupport.objectMapper().createObjectNode();
            metadataNodes.set("n", JsonSupport.objectMapper().readTree("""
                    { "type": "Actor", "source_id": "n" }
                    """));

            Document document = svgDocument(okContent(render(semanticRenderInput(
                    "uml",
                    nodes,
                    JsonSupport.objectMapper().createArrayNode(),
                    metadataNodes,
                    JsonSupport.objectMapper().createObjectNode(),
                    policy))));

            Element node = groupWithAttribute(document, "data-dediren-node-id", "n");
            org.w3c.dom.NodeList texts = node.getElementsByTagName("text");
            assertThat(texts.getLength()).as("actor renders exactly one label").isEqualTo(1);
            Element label = (Element) texts.item(0);
            // Stick-figure feet are at node.y() + height * 0.78 = 40 + 74.88 = 114.88.
            double feetY = 40 + 96 * 0.78;
            assertThat(Double.parseDouble(label.getAttribute("y")))
                    .as("label must sit below the figure")
                    .isGreaterThan(feetY);
            // Exact placement contract: node.y() + height - 8.
            assertThat(Double.parseDouble(label.getAttribute("y"))).isEqualTo(40 + 96 - 8.0);
            assertThat(Double.parseDouble(label.getAttribute("x"))).isEqualTo(40 + 180 / 2.0);
        }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest='MainTest#umlActorLabelSitsBelowFigure' -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: FAIL — current `y=55.0` (`node.y()+15`), which is `< 114.88` and `!= 128.0`.

- [ ] **Step 3: Write minimal implementation**

In `Main.java` `umlNodeDecorator(...)`, the branch at lines 2254-2255 currently reads:

```java
        if (umlDecoratorSuppliesNodeLabel(decorator)) {
            body = umlClassifierNotation(node, style, decorator, selector);
        } else if (decorator == SvgNodeDecorator.UML_PACKAGE) {
```

Change the condition so the actor is not treated as a classifier (its dedicated
branch below then handles the label, placing it under the figure):

```java
        if (umlDecoratorSuppliesNodeLabel(decorator) && decorator != SvgNodeDecorator.UML_ACTOR) {
            body = umlClassifierNotation(node, style, decorator, selector);
        } else if (decorator == SvgNodeDecorator.UML_PACKAGE) {
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest='MainTest#umlActorLabelSitsBelowFigure' -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java \
        plugins/svg-render/src/test/java/dev/dediren/plugins/svgrender/MainTest.java
git commit -m "fix(svg-render): place UML actor label below the figure"
```

---

### Task 4: Document the won't-fix decisions (ARCH-V-002, ARCH-L-004)

Record the two reviewed-and-intentional behaviors in the code so future reviews
do not re-flag them. Comments only — no behavior change, no test.

**Files:**
- Modify: `plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java` (`umlCompactControlNodeLabelOutside` line 959; `shouldRenderPlainNodeLabel` lines 2499-2505)

- [ ] **Step 1: Annotate the UML compact-control diagonal placement (ARCH-V-002)**

In `Main.java`, the method at line 959 currently reads:

```java
    private static boolean umlCompactControlNodeLabelOutside(SvgNodeDecorator decorator) {
        return decorator == SvgNodeDecorator.UML_INITIAL_NODE
```

Insert a comment directly above the method signature:

```java
    // Reviewed (ARCH-V-002, won't-fix): these compact glyphs deliberately place their
    // label diagonally up-left (see nodeLabelPosition) to keep it clear of the in/out
    // flows that enter and leave initial/final/decision/merge nodes. This is intentional
    // and differs from ArchiMate junction labels, which center below the circle.
    private static boolean umlCompactControlNodeLabelOutside(SvgNodeDecorator decorator) {
        return decorator == SvgNodeDecorator.UML_INITIAL_NODE
```

- [ ] **Step 2: Annotate the final-state / pseudostate label suppression (ARCH-L-004)**

In `Main.java`, the method at lines 2499-2505 currently reads:

```java
    private static boolean shouldRenderPlainNodeLabel(LaidOutNode node, SvgNodeDecorator decorator) {
        return node.label() != null
                && !node.label().isEmpty()
                && !umlDecoratorSuppliesNodeLabel(decorator)
                && decorator != SvgNodeDecorator.UML_FINAL_STATE
                && decorator != SvgNodeDecorator.UML_PSEUDOSTATE;
    }
```

Insert a comment directly above the method signature:

```java
    // Reviewed (ARCH-L-004, won't-fix): final states and pseudostates are intentionally
    // unlabeled. Unnamed final/initial pseudostates are valid UML, so these glyph-only
    // shapes suppress the plain label rather than rendering an empty or placeholder name.
    private static boolean shouldRenderPlainNodeLabel(LaidOutNode node, SvgNodeDecorator decorator) {
        return node.label() != null
                && !node.label().isEmpty()
                && !umlDecoratorSuppliesNodeLabel(decorator)
                && decorator != SvgNodeDecorator.UML_FINAL_STATE
                && decorator != SvgNodeDecorator.UML_PSEUDOSTATE;
    }
```

- [ ] **Step 3: Verify it still compiles (no behavior change)**

Run: `./mvnw -pl plugins/svg-render -am test -Dtest='MainTest#coversEachUmlNodeTypeFromPolicy' -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled)
Expected: PASS (comments only)

- [ ] **Step 4: Commit**

```bash
git add plugins/svg-render/src/main/java/dev/dediren/plugins/svgrender/Main.java
git commit -m "docs(svg-render): record won't-fix rationale for UML control/state labels"
```

---

### Task 5: Full verification and artifact regeneration

**Files:** none modified — verification only.

- [ ] **Step 1: Run the full svg-render module test suite**

Run: `./mvnw -pl plugins/svg-render,cli -am test -Dsurefire.failIfNoSpecifiedTests=false` (sandbox disabled — this is the CLAUDE.md "SVG render changes" lane)
Expected: BUILD SUCCESS, 0 failures. This also regenerates the two coverage SVGs under `.test-output/renders/svg-render-plugin/`.

- [ ] **Step 2: Visually confirm the three fixes in the regenerated ArchiMate sheet**

Run: `grep -oE '<g data-dediren-node-id="archimate-node-(5|28|40|54)">[^/]*data-dediren-node-shape="[^"]*"( x="[^"]*")?[^>]*' .test-output/renders/svg-render-plugin/svg_renderer_covers_each_archimate_node_type.svg`
Expected:
- node-5 (Grouping): `archimate_rectangle` with `stroke-dasharray="3 2"`.
- node-28 / node-40 / node-54 (Interactions): `archimate_rounded_rectangle`.

- [ ] **Step 3: Visually confirm the actor fix in the regenerated UML sheet**

Run: `grep -oE '<g data-dediren-node-id="uml-node-17">.*</g></g>' .test-output/renders/svg-render-plugin/svg_renderer_covers_each_uml_node_type.svg | grep -oE 'legs?|389.6 [0-9.]*|<text x="[0-9.]*" y="[0-9.]*"'`
Expected: the single `<text ...>` `y` value is greater than the feet `y` (`674.9`) — i.e. `y=688.0` — and `x=350.0`.

- [ ] **Step 4: Confirm no committed files were left dirty and artifacts are not staged**

Run: `git status --short --branch`
Expected: clean except for any expected state; the `.test-output/` SVGs are git-ignored and must NOT appear as staged. Do not `git add` them.

---

## Notes for the implementer

- **No version bump.** These are plugin bug fixes. Per `## Versioning` in CLAUDE.md, the version is bumped in its own follow-on commit only when the change is being released. Leave `pom.xml` untouched unless the user explicitly asks to cut a release.
- **Do not commit `.test-output/` SVGs** — they are git-ignored generated artifacts; report their paths instead.
- **Audit gate (SVG render lane):** per CLAUDE.md Audit Gates, run a quick `test-quality-audit` over the changed contract/plugin/CLI tests and a quick `devsecops-audit` over the renderer diff before declaring done; fix block findings, accept or fix warn/info findings in the handoff.

## Self-Review

- **Spec coverage:** ARCH-V-001 → Task 1; ARCH-V-003 (node + container) → Task 2; ARCH-L-005 → Task 3; ARCH-V-002 + ARCH-L-004 won't-fix documentation → Task 4; regeneration + verification → Task 5. All five findings accounted for; the skill-repo junction-doc note is explicitly out of scope.
- **Placeholder scan:** every code step contains complete before/after Java; no TBD/TODO.
- **Type/name consistency:** `SvgNodeDecorator.ARCHIMATE_BUSINESS_INTERACTION/APPLICATION_INTERACTION/TECHNOLOGY_INTERACTION`, `ARCHIMATE_GROUPING`, `UML_ACTOR`, and `style.decorator()` all match the live `Main.java` enum and method usages verified during planning. Helper names (`fixtureJson`, `render`, `okContent`, `svgDocument`, `semanticRenderInput`, `groupWithAttribute`, `firstElementWithAttribute`, `firstChildElement`) match existing `MainTest.java` usage. The `stroke-dasharray="3 2"` value matches the existing grouping-icon idiom (`Main.java:1266`).

# ArchiMate Export Render Artifacts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the highest-confidence ArchiMate artifact causes: OEF connections serializing attachment endpoints as bendpoints, weak OEF reference diagnostics, SVG edge labels colliding with route/group geometry, and route masks using the wrong local background.

**Architecture:** Keep ELK layout unchanged because generated layout endpoints validate on node perimeters. Fix OEF geometry in `archimate-oef-export`, and fix visible SVG artifacts inside the render plugin by making label placement and line-jump masking aware of already-rendered diagram geometry. Preserve existing plugin contracts and update docs/tests where the exported/rendered artifact shape changes.

**Tech Stack:** Java 21, Maven multi-module build, Jackson JSON trees, Dediren contracts, Eclipse ELK-produced layout results, SVG string rendering, ArchiMate Open Exchange Format XML.

---

## File Structure

- Modify `plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/Main.java`
  - Convert first/last layout route points to OEF `sourceAttachment`/`targetAttachment`.
  - Emit only intermediate points as OEF `bendpoint`.
  - Validate layout/source references before XML generation so failures are Dediren diagnostics rather than empty XML attributes.
- Modify `plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`
  - Assert OEF connection geometry uses attachment elements.
  - Assert missing source/view references return deterministic diagnostics.
- Modify `fixtures/export/oef-basic.xml`
  - Update the golden OEF fixture to the new attachment-oriented connection XML.
- Modify `plugins/render/src/main/java/dev/dediren/plugins/render/Main.java`
  - Add route/group obstacle boxes to edge-label placement.
  - Expand label candidate search enough to escape dense route bands.
  - Use local group fill for line-jump masks instead of always using page background fill.
- Modify `plugins/render/src/test/java/dev/dediren/plugins/render/MainTest.java`
  - Add DOM-level tests for route-aware label placement.
  - Add a DOM-level test for line-jump masks over colored group backgrounds.
- Modify `docs/features/exports.md`
  - Document that OEF route endpoints are exported as attachments and bendpoints are intermediate-only.
- Modify `docs/features/svg-render.md`
  - Document that edge labels avoid node, group, and route obstacles, and that line jumps use local group fill.

Do not modify `plugins/elk-layout` for this plan. The findings show ELK layout endpoint geometry is already covered and passing for the reproduced pipeline view.

---

### Task 1: Export OEF Attachments Instead Of Endpoint Bendpoints

**Files:**
- Modify: `plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/Main.java`
- Modify: `plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`
- Modify: `fixtures/export/oef-basic.xml`

- [ ] **Step 1: Write the failing OEF geometry test**

Edit `plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`.

Add this import beside the existing Jackson imports:

```java
import com.fasterxml.jackson.databind.node.ArrayNode;
```

Replace the existing `roundsDecimalGeometryToIntegerOefCoordinates` test with this complete method:

```java
    @Test
    void emitsAttachmentsForRouteEndpointsAndBendpointsOnlyForIntermediatePoints() throws Exception {
        JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
        ((com.fasterxml.jackson.databind.node.ArrayNode) source.get("nodes")).addObject()
                .put("id", "customer-domain")
                .put("type", "Grouping")
                .put("label", "Customer Domain")
                .set("properties", JsonSupport.objectMapper().createObjectNode());
        JsonNode layout = fixtureJson("fixtures/layout-result/archimate-oef-basic.json");
        ((ObjectNode) layout.at("/nodes/0")).put("x", 40.25);
        ((ObjectNode) layout.at("/nodes/0")).put("y", 40.75);
        ((ObjectNode) layout.at("/nodes/0")).put("width", 180.6);
        ((ObjectNode) layout.at("/nodes/0")).put("height", 80.4);
        ArrayNode points = (ArrayNode) layout.at("/edges/0/points");
        ((ObjectNode) points.get(0)).put("x", 220.2);
        ((ObjectNode) points.get(0)).put("y", 80.8);
        points.insertObject(1).put("x", 260.6).put("y", 80.4);
        layoutWithGroups(layout, 10.6, 11.5, 520.4, 180.6);

        String xml = exportXml(source, layout);

        assertThat(xml).contains("x=\"11\" y=\"12\" w=\"520\" h=\"181\"");
        assertThat(xml).contains("x=\"40\" y=\"41\" w=\"181\" h=\"80\"");
        assertThat(xml).contains(
                "<sourceAttachment x=\"220\" y=\"81\"/>"
                        + "<bendpoint x=\"261\" y=\"80\"/>"
                        + "<targetAttachment x=\"300\" y=\"80\"/>");
        assertThat(xml).doesNotContain("<bendpoint x=\"220\" y=\"81\"/>");
        assertThat(xml).doesNotContain("<bendpoint x=\"300\" y=\"80\"/>");
    }
```

Update `fixtures/export/oef-basic.xml` to this exact content:

```xml
<model xmlns="http://www.opengroup.org/xsd/archimate/3.0/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.opengroup.org/xsd/archimate/3.0/ http://www.opengroup.org/xsd/archimate/3.1/archimate3_Model.xsd" identifier="id-dediren-oef-basic-model"><name xml:lang="en">Dediren OEF Basic</name><elements><element identifier="id-el-orders-component" xsi:type="ApplicationComponent"><name xml:lang="en">Orders Component</name></element><element identifier="id-el-orders-service" xsi:type="ApplicationService"><name xml:lang="en">Orders Service</name></element></elements><relationships><relationship identifier="id-rel-orders-realizes-service" source="id-el-orders-component" target="id-el-orders-service" xsi:type="Realization"><name xml:lang="en">realizes</name></relationship></relationships><views><diagrams><view identifier="id-view-main" xsi:type="Diagram" viewpoint="Application Cooperation"><name xml:lang="en">Main</name><node identifier="id-vn-main-orders-component" xsi:type="Element" elementRef="id-el-orders-component" x="40" y="40" w="180" h="80"/><node identifier="id-vn-main-orders-service" xsi:type="Element" elementRef="id-el-orders-service" x="300" y="40" w="180" h="80"/><connection identifier="id-vc-main-orders-realizes-service" xsi:type="Relationship" relationshipRef="id-rel-orders-realizes-service" source="id-vn-main-orders-component" target="id-vn-main-orders-service"><sourceAttachment x="220" y="80"/><targetAttachment x="300" y="80"/></connection></view></diagrams></views></model>
```

- [ ] **Step 2: Run the focused exporter test and verify it fails**

Run:

```bash
./mvnw -pl plugins/archimate-oef-export -am -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL. The new assertion should fail because the exporter still emits endpoint coordinates as `<bendpoint>` elements.

- [ ] **Step 3: Implement OEF connection attachment serialization**

Edit `plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/Main.java`.

Add this import beside the other layout imports:

```java
import dev.dediren.contracts.layout.Point;
```

In `buildOef`, replace the current loop that writes every `edge.points()` entry as a bendpoint with this call:

```java
            writeConnectionGeometry(xml, edge.points());
```

The whole connection-writing block should read:

```java
        for (var edge : request.layoutResult().edges()) {
            xml.append("<connection identifier=\"").append(attr(viewConnectionIds.get(edge.id())))
                    .append("\" xsi:type=\"Relationship\" relationshipRef=\"")
                    .append(attr(relationshipIds.get(edge.sourceId())))
                    .append("\" source=\"").append(attr(viewNodeIds.get(edge.source())))
                    .append("\" target=\"").append(attr(viewNodeIds.get(edge.target())))
                    .append("\">");
            writeConnectionGeometry(xml, edge.points());
            xml.append("</connection>");
        }
```

Add these helper methods below `buildOef` and above `validateOfficialOefSchema`:

```java
    private static void writeConnectionGeometry(StringBuilder xml, List<Point> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        writeLocation(xml, "sourceAttachment", points.getFirst());
        for (int index = 1; index < points.size() - 1; index++) {
            writeLocation(xml, "bendpoint", points.get(index));
        }
        if (points.size() > 1) {
            writeLocation(xml, "targetAttachment", points.getLast());
        }
    }

    private static void writeLocation(StringBuilder xml, String elementName, Point point) {
        xml.append("<").append(elementName)
                .append(" x=\"").append(formatNumber(point.x()))
                .append("\" y=\"").append(formatNumber(point.y()))
                .append("\"/>");
    }
```

- [ ] **Step 4: Run the focused exporter test and verify it passes**

Run:

```bash
./mvnw -pl plugins/archimate-oef-export -am -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS. The output should include `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Commit OEF geometry serialization**

Run:

```bash
git add plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/Main.java plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java fixtures/export/oef-basic.xml
git commit -m "fix: export oef route endpoints as attachments"
```

---

### Task 2: Add Deterministic OEF Reference Diagnostics

**Files:**
- Modify: `plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/Main.java`
- Modify: `plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`

- [ ] **Step 1: Write failing tests for missing layout/source references**

Edit `plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java`.

Add these two tests after `rejectsGroupWhoseSourceNodeIsNotGroupingType`:

```java
    @Test
    void rejectsLayoutNodeWhoseSourceIdDoesNotResolveToSourceNode() throws Exception {
        JsonNode input = exportInputJson();
        ((ObjectNode) input.at("/layout_result/nodes/0")).put("source_id", "missing-source-node");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithOefSchemas());

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_OEF_LAYOUT_REFERENCE_UNRESOLVED");
        assertThat(result.stdout()).contains("layout node orders-component source_id missing-source-node");
        assertThat(result.stdout()).contains("$.layout_result.nodes[0].source_id");
    }

    @Test
    void rejectsLayoutEdgeWhoseRelationshipRefDoesNotResolveToSourceRelationship() throws Exception {
        JsonNode input = exportInputJson();
        ((ObjectNode) input.at("/layout_result/edges/0")).put("source_id", "missing-relationship");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithOefSchemas());

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_OEF_LAYOUT_REFERENCE_UNRESOLVED");
        assertThat(result.stdout()).contains("layout edge orders-realizes-service source_id missing-relationship");
        assertThat(result.stdout()).contains("$.layout_result.edges[0].source_id");
    }
```

- [ ] **Step 2: Run the focused exporter test and verify it fails**

Run:

```bash
./mvnw -pl plugins/archimate-oef-export -am -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL. The missing references currently flow into XML generation and return schema-shaped failures instead of `DEDIREN_OEF_LAYOUT_REFERENCE_UNRESOLVED`.

- [ ] **Step 3: Implement pre-export reference validation**

Edit `plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/Main.java`.

In `exportFromStdin`, extend the validation block so it calls `validateLayoutReferences(request)`:

```java
        try {
            validateArchimateTypes(request);
            validateArchimateJunctionSemantics(request);
            validateArchimateGroupSemantics(request);
            validateLayoutReferences(request);
        } catch (ArchimateTypeValidationException error) {
            return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
        } catch (ArchimateJunctionValidationException error) {
            return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
        } catch (GroupSemanticValidationException error) {
            return exitWithDiagnostic(stdout, error.code(), error.getMessage(), error.path());
        } catch (OefReferenceValidationException error) {
            return exitWithDiagnostic(stdout, error.code(), error.getMessage(), error.path());
        }
```

Add this method below `validateArchimateGroupSemantics`:

```java
    private static void validateLayoutReferences(ExportRequest request)
            throws OefReferenceValidationException {
        Set<String> sourceNodeIds = request.source().nodes().stream()
                .map(SourceNode::id)
                .collect(Collectors.toSet());
        Set<String> sourceRelationshipIds = request.source().relationships().stream()
                .map(relationship -> relationship.id())
                .collect(Collectors.toSet());
        Set<String> layoutNodeIds = request.layoutResult().nodes().stream()
                .map(node -> node.id())
                .collect(Collectors.toSet());

        for (int index = 0; index < request.layoutResult().nodes().size(); index++) {
            var node = request.layoutResult().nodes().get(index);
            if (!sourceNodeIds.contains(node.sourceId())) {
                throw new OefReferenceValidationException(
                        "$.layout_result.nodes[" + index + "].source_id",
                        "layout node " + node.id() + " source_id " + node.sourceId()
                                + " does not resolve to a source node");
            }
        }
        for (int index = 0; index < request.layoutResult().edges().size(); index++) {
            var edge = request.layoutResult().edges().get(index);
            if (!sourceRelationshipIds.contains(edge.sourceId())) {
                throw new OefReferenceValidationException(
                        "$.layout_result.edges[" + index + "].source_id",
                        "layout edge " + edge.id() + " source_id " + edge.sourceId()
                                + " does not resolve to a source relationship");
            }
            if (!layoutNodeIds.contains(edge.source())) {
                throw new OefReferenceValidationException(
                        "$.layout_result.edges[" + index + "].source",
                        "layout edge " + edge.id() + " source " + edge.source()
                                + " does not resolve to a layout node");
            }
            if (!layoutNodeIds.contains(edge.target())) {
                throw new OefReferenceValidationException(
                        "$.layout_result.edges[" + index + "].target",
                        "layout edge " + edge.id() + " target " + edge.target()
                                + " does not resolve to a layout node");
            }
        }
    }
```

Add this nested exception class near the existing nested exception classes at the bottom of the file:

```java
    private static final class OefReferenceValidationException extends Exception {
        private final String path;

        private OefReferenceValidationException(String path, String message) {
            super(message);
            this.path = path;
        }

        String code() {
            return "DEDIREN_OEF_LAYOUT_REFERENCE_UNRESOLVED";
        }

        String path() {
            return path;
        }
    }
```

- [ ] **Step 4: Run the focused exporter test and verify it passes**

Run:

```bash
./mvnw -pl plugins/archimate-oef-export -am -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS. The output should include `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Commit OEF reference diagnostics**

Run:

```bash
git add plugins/archimate-oef-export/src/main/java/dev/dediren/plugins/archimateoef/Main.java plugins/archimate-oef-export/src/test/java/dev/dediren/plugins/archimateoef/MainTest.java
git commit -m "fix: validate oef layout references before export"
```

---

### Task 3: Make SVG Edge Labels Route And Group Aware

**Files:**
- Modify: `plugins/render/src/main/java/dev/dediren/plugins/render/Main.java`
- Modify: `plugins/render/src/test/java/dev/dediren/plugins/render/MainTest.java`

- [ ] **Step 1: Write failing SVG label obstacle tests**

Edit `plugins/render/src/test/java/dev/dediren/plugins/render/MainTest.java`.

Add these tests inside the `RouteAndLabelRendering` nested class after `placesSharedSourceLabelsOnLastHorizontalBranch`:

```java
        @Test
        void movesEdgeLabelAwayFromOtherRouteSegments() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    """
                    [
                      { "id": "source-node", "source_id": "source-node", "projection_id": "source-node", "x": 300, "y": 190, "width": 170, "height": 90, "label": "Source" }
                    ]
                    """,
                    """
                    [
                      {
                        "id": "labeled-edge",
                        "source": "source-node",
                        "target": "target-node",
                        "source_id": "labeled-edge",
                        "projection_id": "labeled-edge",
                        "points": [
                          { "x": 478, "y": 248 },
                          { "x": 542, "y": 248 },
                          { "x": 688, "y": 248 },
                          { "x": 849, "y": 248 },
                          { "x": 849, "y": 361 }
                        ],
                        "label": "requests payment authorization"
                      },
                      {
                        "id": "crossing-route",
                        "source": "a",
                        "target": "b",
                        "source_id": "crossing-route",
                        "projection_id": "crossing-route",
                        "points": [
                          { "x": 397, "y": 313 },
                          { "x": 687, "y": 313 }
                        ],
                        "label": ""
                      }
                    ]
                    """,
                    "{ \"edge\": { \"label_horizontal_position\": \"near_start\" } }");
            Document document = svgDocument(okContent(render(input)));

            Element label = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "labeled-edge"), "text");
            double[] labelBox = textBox(label);

            assertThat(rectIntersectsHorizontalSegment(labelBox, 397.0, 687.0, 313.0, 6.0)).isFalse();
        }

        @Test
        void movesEdgeLabelAwayFromGroupTitleBand() throws Exception {
            JsonNode input = styledInlineInput(
                    """
                    [
                      {
                        "id": "application-services",
                        "source_id": "application-services",
                        "projection_id": "application-services",
                        "x": 250,
                        "y": 70,
                        "width": 590,
                        "height": 450,
                        "members": [],
                        "label": "Application Services"
                      }
                    ]
                    """,
                    "[]",
                    """
                    [
                      {
                        "id": "group-title-edge",
                        "source": "left",
                        "target": "right",
                        "source_id": "group-title-edge",
                        "projection_id": "group-title-edge",
                        "points": [
                          { "x": 300, "y": 80 },
                          { "x": 620, "y": 80 }
                        ],
                        "label": "must clear group label"
                      }
                    ]
                    """,
                    "{ \"edge\": { \"label_horizontal_position\": \"center\", \"label_horizontal_side\": \"above\" } }");
            Document document = svgDocument(okContent(render(input)));

            Element label = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "group-title-edge"), "text");
            double[] labelBox = textBox(label);

            assertThat(rectanglesIntersect(labelBox, 250.0, 70.0, 840.0, 94.0)).isFalse();
        }
```

Add these helper methods near the existing `textBox` helper:

```java
    private static boolean rectIntersectsHorizontalSegment(
            double[] box,
            double segmentMinX,
            double segmentMaxX,
            double segmentY,
            double padding) {
        return rectanglesIntersect(
                box,
                segmentMinX,
                segmentY - padding,
                segmentMaxX,
                segmentY + padding);
    }

    private static boolean rectanglesIntersect(
            double[] box,
            double minX,
            double minY,
            double maxX,
            double maxY) {
        return box[0] < maxX && box[2] > minX && box[1] < maxY && box[3] > minY;
    }
```

- [ ] **Step 2: Run the focused render test and verify it fails**

Run:

```bash
./mvnw -pl plugins/render -am -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL. At least `movesEdgeLabelAwayFromOtherRouteSegments` should fail because route segments are not label obstacles yet.

- [ ] **Step 3: Add route and group obstacle boxes to label placement**

Edit `plugins/render/src/main/java/dev/dediren/plugins/render/Main.java`.

Add these constants after `EDGE_LABEL_OUTLINE_WIDTH`:

```java
    private static final double EDGE_ROUTE_LABEL_OBSTACLE_PADDING = 6.0;
    private static final double GROUP_BORDER_LABEL_OBSTACLE_PADDING = 4.0;
    private static final double GROUP_TITLE_LABEL_OBSTACLE_HEIGHT = 24.0;
```

Replace both calls to `nodeObstacleBoxes(result)` used for edge-label placement with `labelObstacleBoxes(result)`:

```java
        List<LabelBox> occupiedLabelBoxes = labelObstacleBoxes(result);
```

and:

```java
        List<LabelBox> occupiedBoxes = labelObstacleBoxes(result);
```

Add these helpers above the existing `nodeObstacleBoxes` method:

```java
    private static List<LabelBox> labelObstacleBoxes(LayoutResult result) {
        List<LabelBox> boxes = nodeObstacleBoxes(result);
        boxes.addAll(groupObstacleBoxes(result));
        boxes.addAll(edgeRouteObstacleBoxes(result.edges()));
        return boxes;
    }

    private static List<LabelBox> groupObstacleBoxes(LayoutResult result) {
        List<LabelBox> boxes = new ArrayList<>();
        for (LaidOutGroup group : result.groups()) {
            double left = group.x();
            double top = group.y();
            double right = group.x() + group.width();
            double bottom = group.y() + group.height();
            boxes.add(new LabelBox(left, top, right, top + GROUP_TITLE_LABEL_OBSTACLE_HEIGHT));
            boxes.add(new LabelBox(left, top, right, top).expanded(0.0, GROUP_BORDER_LABEL_OBSTACLE_PADDING));
            boxes.add(new LabelBox(left, bottom, right, bottom).expanded(0.0, GROUP_BORDER_LABEL_OBSTACLE_PADDING));
            boxes.add(new LabelBox(left, top, left, bottom).expanded(GROUP_BORDER_LABEL_OBSTACLE_PADDING, 0.0));
            boxes.add(new LabelBox(right, top, right, bottom).expanded(GROUP_BORDER_LABEL_OBSTACLE_PADDING, 0.0));
        }
        return boxes;
    }

    private static List<LabelBox> edgeRouteObstacleBoxes(List<LaidOutEdge> edges) {
        List<LabelBox> boxes = new ArrayList<>();
        for (LaidOutEdge edge : edges) {
            for (int index = 0; index < edge.points().size() - 1; index++) {
                Point start = edge.points().get(index);
                Point end = edge.points().get(index + 1);
                if (distance(start, end) < 0.001) {
                    continue;
                }
                LabelBox segmentBox = new LabelBox(
                        Math.min(start.x(), end.x()),
                        Math.min(start.y(), end.y()),
                        Math.max(start.x(), end.x()),
                        Math.max(start.y(), end.y()));
                boolean horizontal = nearlyEqual(start.y(), end.y());
                boolean vertical = nearlyEqual(start.x(), end.x());
                if (horizontal) {
                    boxes.add(segmentBox.expanded(0.0, EDGE_ROUTE_LABEL_OBSTACLE_PADDING));
                } else if (vertical) {
                    boxes.add(segmentBox.expanded(EDGE_ROUTE_LABEL_OBSTACLE_PADDING, 0.0));
                } else {
                    boxes.add(segmentBox.expanded(
                            EDGE_ROUTE_LABEL_OBSTACLE_PADDING,
                            EDGE_ROUTE_LABEL_OBSTACLE_PADDING));
                }
            }
        }
        return boxes;
    }
```

- [ ] **Step 4: Expand edge-label candidate search**

In `plugins/render/src/main/java/dev/dediren/plugins/render/Main.java`, replace the inline horizontal `offsets` construction in `edgeLabel(...)` with:

```java
            List<Double> offsets = labelOffsetCandidates(baseOffset);
```

Add this helper below `edgeLabel(...)`:

```java
    private static List<Double> labelOffsetCandidates(double baseOffset) {
        List<Double> offsets = new ArrayList<>();
        offsets.add(baseOffset);
        offsets.add(baseOffset < 0.0 ? 18.0 : -10.0);
        for (double distance : List.of(28.0, 56.0, 84.0, 112.0, 140.0)) {
            offsets.add(baseOffset + distance);
            offsets.add(baseOffset - distance);
        }
        return orderedValues(offsets.stream().mapToDouble(Double::doubleValue).toArray());
    }
```

Replace the vertical segment branch in `edgeLabel(...)` with:

```java
        Optional<Segment> vertical = firstVerticalSegment(edge);
        if (vertical.isPresent()) {
            Segment segment = vertical.get();
            double minY = edge.points().stream().mapToDouble(Point::y).min().orElse(segment.start().y());
            double maxY = edge.points().stream().mapToDouble(Point::y).max().orElse(segment.end().y());
            double labelY = (minY + maxY) / 2.0;
            List<EdgeLabel> candidates = List.of(
                    edgeLabelCandidate(segment.start().x() - 6.0, labelY, "end", edge.label(), fontSize),
                    edgeLabelCandidate(segment.start().x() + 6.0, labelY, "start", edge.label(), fontSize),
                    edgeLabelCandidate(segment.start().x() - 34.0, labelY, "end", edge.label(), fontSize),
                    edgeLabelCandidate(segment.start().x() + 34.0, labelY, "start", edge.label(), fontSize),
                    edgeLabelCandidate(segment.start().x() - 62.0, labelY, "end", edge.label(), fontSize),
                    edgeLabelCandidate(segment.start().x() + 62.0, labelY, "start", edge.label(), fontSize));
            for (EdgeLabel candidate : candidates) {
                LabelBox candidateBox = edgeLabelVisibleBox(candidate, style.labelPresentation());
                if (occupiedBoxes.stream().noneMatch(candidateBox::overlaps)) {
                    return candidate;
                }
            }
            return candidates.getFirst();
        }
```

- [ ] **Step 5: Run the focused render test and verify it passes**

Run:

```bash
./mvnw -pl plugins/render -am -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS. The output should include `Tests run: 77, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 6: Commit route-aware label placement**

Run:

```bash
git add plugins/render/src/main/java/dev/dediren/plugins/render/Main.java plugins/render/src/test/java/dev/dediren/plugins/render/MainTest.java
git commit -m "fix: keep svg edge labels clear of route obstacles"
```

---

### Task 4: Use Local Group Fill For SVG Line-Jump Masks

**Files:**
- Modify: `plugins/render/src/main/java/dev/dediren/plugins/render/Main.java`
- Modify: `plugins/render/src/test/java/dev/dediren/plugins/render/MainTest.java`

- [ ] **Step 1: Write the failing line-jump mask color test**

Edit `plugins/render/src/test/java/dev/dediren/plugins/render/MainTest.java`.

Add this test inside `RouteAndLabelRendering` after `addsLineJumpForLaterCrossingEdge`:

```java
        @Test
        void usesGroupFillForLineJumpMaskInsideGroup() throws Exception {
            JsonNode input = styledInlineInput(
                    """
                    [
                      {
                        "id": "colored-group",
                        "source_id": "colored-group",
                        "projection_id": "colored-group",
                        "x": 40,
                        "y": 40,
                        "width": 240,
                        "height": 220,
                        "members": [],
                        "label": "Colored Group"
                      }
                    ]
                    """,
                    "[]",
                    """
                    [
                      {
                        "id": "back-edge",
                        "source": "left",
                        "target": "right",
                        "source_id": "back-edge",
                        "projection_id": "back-edge",
                        "points": [
                          { "x": 60, "y": 140 },
                          { "x": 260, "y": 140 }
                        ],
                        "label": "back"
                      },
                      {
                        "id": "front-edge",
                        "source": "top",
                        "target": "bottom",
                        "source_id": "front-edge",
                        "projection_id": "front-edge",
                        "points": [
                          { "x": 160, "y": 60 },
                          { "x": 160, "y": 240 }
                        ],
                        "label": "front"
                      }
                    ]
                    """,
                    """
                    {
                      "background": { "fill": "#ffffff" },
                      "group": { "fill": "#fef3c7", "stroke": "#d97706" }
                    }
                    """);
            Document document = svgDocument(okContent(render(input)));

            Element frontEdge = groupWithAttribute(document, "data-dediren-edge-id", "front-edge");
            Element masks = childGroupWithAttribute(frontEdge, "data-dediren-line-jump-masks", "front-edge");
            Element maskPath = firstChildElement(masks, "path");

            assertThat(maskPath.getAttribute("stroke")).isEqualTo("#fef3c7");
        }
```

- [ ] **Step 2: Run the focused render test and verify it fails**

Run:

```bash
./mvnw -pl plugins/render -am -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL. The mask stroke should currently be `#ffffff` because `lineJumpMasks` uses the page background fill.

- [ ] **Step 3: Compute line-jump mask fill from local group backdrop**

Edit `plugins/render/src/main/java/dev/dediren/plugins/render/Main.java`.

Replace this call in the edge render loop:

```java
            svg.append(lineJumpMasks(edge, lineJumps, base.backgroundFill()));
```

with:

```java
            svg.append(lineJumpMasks(edge, lineJumps, result, metadata, policy, base));
```

Replace the existing `lineJumpMasks` method with:

```java
    private static String lineJumpMasks(
            LaidOutEdge edge,
            List<LineJump> lineJumps,
            LayoutResult result,
            RenderMetadata metadata,
            RenderPolicy policy,
            ResolvedStyle base) {
        if (lineJumps.isEmpty()) {
            return "";
        }
        StringBuilder masks = new StringBuilder();
        masks.append("<g data-dediren-line-jump-masks=\"").append(attr(edge.id())).append("\">");
        for (LineJump jump : lineJumps) {
            String maskFill = backdropFillAt(jump.x(), jump.y(), result, metadata, policy, base);
            masks.append("<path d=\"").append(attr(jump.maskPath()))
                    .append("\" fill=\"none\" stroke=\"").append(attr(maskFill))
                    .append("\" stroke-width=\"6\"/>");
        }
        masks.append("</g>");
        return masks.toString();
    }
```

Add these helpers below `lineJumpMasks`:

```java
    private static String backdropFillAt(
            double x,
            double y,
            LayoutResult result,
            RenderMetadata metadata,
            RenderPolicy policy,
            ResolvedStyle base) {
        for (int index = result.groups().size() - 1; index >= 0; index--) {
            LaidOutGroup group = result.groups().get(index);
            if (pointInsideRect(x, y, group.x(), group.y(), group.width(), group.height())) {
                return groupStyle(policy, metadata, group.id(), base).fill();
            }
        }
        return base.backgroundFill();
    }

    private static boolean pointInsideRect(
            double x,
            double y,
            double rectX,
            double rectY,
            double width,
            double height) {
        return x >= rectX && x <= rectX + width && y >= rectY && y <= rectY + height;
    }
```

- [ ] **Step 4: Run the focused render test and verify it passes**

Run:

```bash
./mvnw -pl plugins/render -am -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS. The output should include `Tests run: 78, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 5: Commit local-fill line-jump masks**

Run:

```bash
git add plugins/render/src/main/java/dev/dediren/plugins/render/Main.java plugins/render/src/test/java/dev/dediren/plugins/render/MainTest.java
git commit -m "fix: mask svg line jumps with local group fill"
```

---

### Task 5: Document Export And Render Artifact Behavior

**Files:**
- Modify: `docs/features/exports.md`
- Modify: `docs/features/svg-render.md`

- [ ] **Step 1: Update export documentation**

Edit `docs/features/exports.md`.

In the `ArchiMate OEF` section, after the paragraph ending with `see [Layout](layout.md#junction-routing)).`, add:

```markdown

OEF diagram connections preserve generated layout routes using OEF attachment
semantics: the first route point is emitted as `sourceAttachment`, the last route
point as `targetAttachment`, and only intermediate route points are emitted as
`bendpoint`. This keeps schema-valid XML closer to how importing tools expect
relationship anchors and avoids treating node attachment points as free-standing
bendpoints.
```

- [ ] **Step 2: Update render documentation**

Edit `docs/features/svg-render.md`.

After the `Edge label presentation` paragraph, add:

```markdown

Edge label placement avoids node boxes, group title/border bands, existing route
segments, and labels already placed earlier in the SVG. Line-jump masks use the
local group fill when the jump occurs inside a group, falling back to the page
background outside groups.
```

- [ ] **Step 3: Review docs diff**

Run:

```bash
git diff -- docs/features/exports.md docs/features/svg-render.md
```

Expected: The diff contains only the two documentation additions above.

- [ ] **Step 4: Commit docs**

Run:

```bash
git add docs/features/exports.md docs/features/svg-render.md
git commit -m "docs: describe archimate export render artifact handling"
```

---

### Task 6: End-To-End Verification And Artifact Smoke Test

**Files:**
- No source files modified in this task.
- Reads generated `/tmp` artifacts only.

- [ ] **Step 1: Run all focused regression suites**

Run:

```bash
./mvnw -pl plugins/elk-layout -am -Dtest=ElkLayoutEngineTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl plugins/archimate-oef-export -am -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl plugins/render -am -Dtest=MainTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl cli -am -Dtest=CliLayoutRenderCommandTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected:

```text
ElkLayoutEngineTest: Tests run: 44, Failures: 0, Errors: 0, Skipped: 0
archimate-oef MainTest: Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
render MainTest: Tests run: 78, Failures: 0, Errors: 0, Skipped: 0
CliLayoutRenderCommandTest: Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
```

- [ ] **Step 2: Build the current local distribution bundle**

Run:

```bash
./mvnw -pl dist-tool -am verify -Pdist-build
BUNDLE=dist/dediren-agent-bundle-2026.06.7
test -x "$BUNDLE/bin/dediren"
test -x "$BUNDLE/bin/dediren-plugin-archimate-oef-export"
test -x "$BUNDLE/bin/dediren-plugin-render"
```

Expected:

```text
BUILD SUCCESS
dist/dediren-agent-bundle-2026.06.7.tar.gz
```

- [ ] **Step 3: Build a fresh pipeline layout with the current local bundle**

Run:

```bash
BUNDLE=dist/dediren-agent-bundle-2026.06.7
"$BUNDLE/bin/dediren" project --plugin generic-graph --target layout-request --view main --input "$BUNDLE/fixtures/source/valid-pipeline-archimate.json" > /tmp/dediren-plan-pipeline-layout-request.json
"$BUNDLE/bin/dediren" layout --plugin elk-layout --input /tmp/dediren-plan-pipeline-layout-request.json > /tmp/dediren-plan-pipeline-layout-result.json
"$BUNDLE/bin/dediren" validate-layout --input /tmp/dediren-plan-pipeline-layout-result.json
```

Expected validate-layout JSON includes:

```json
{"status":"ok","overlap_count":0,"connector_through_node_count":0,"invalid_route_count":0,"warning_count":0}
```

- [ ] **Step 4: Export OEF with local schema files**

Run:

```bash
BUNDLE=dist/dediren-agent-bundle-2026.06.7
mkdir -p /tmp/dediren-oef-schema
curl -fsSL -o /tmp/dediren-oef-schema/archimate3_Model.xsd https://www.opengroup.org/xsd/archimate/3.1/archimate3_Model.xsd
curl -fsSL -o /tmp/dediren-oef-schema/archimate3_View.xsd https://www.opengroup.org/xsd/archimate/3.1/archimate3_View.xsd
curl -fsSL -o /tmp/dediren-oef-schema/archimate3_Diagram.xsd https://www.opengroup.org/xsd/archimate/3.1/archimate3_Diagram.xsd
DEDIREN_OEF_SCHEMA_DIR=/tmp/dediren-oef-schema "$BUNDLE/bin/dediren" export --plugin archimate-oef --policy "$BUNDLE/fixtures/export-policy/default-oef.json" --source "$BUNDLE/fixtures/source/valid-pipeline-archimate.json" --layout /tmp/dediren-plan-pipeline-layout-result.json > /tmp/dediren-plan-pipeline-oef-result.json
jq -r '.data.content' /tmp/dediren-plan-pipeline-oef-result.json > /tmp/dediren-plan-pipeline.xml
rg '<sourceAttachment|<targetAttachment|<bendpoint' /tmp/dediren-plan-pipeline.xml
```

Expected:

```text
The export command returns status ok.
The XML contains sourceAttachment and targetAttachment elements.
The XML contains bendpoint elements only for intermediate route points.
No connection has its first or last route coordinate serialized as bendpoint.
```

- [ ] **Step 5: Render SVG and inspect structural evidence**

Run:

```bash
BUNDLE=dist/dediren-agent-bundle-2026.06.7
"$BUNDLE/bin/dediren" project --plugin generic-graph --target render-metadata --view main --input "$BUNDLE/fixtures/source/valid-pipeline-archimate.json" > /tmp/dediren-plan-pipeline-render-metadata.json
"$BUNDLE/bin/dediren" render --plugin svg-render --policy "$BUNDLE/fixtures/render-policy/archimate-svg.json" --metadata /tmp/dediren-plan-pipeline-render-metadata.json --input /tmp/dediren-plan-pipeline-layout-result.json > /tmp/dediren-plan-pipeline-render-result.json
jq -r '.data.artifacts[] | select(.artifact_kind=="svg") | .content' /tmp/dediren-plan-pipeline-render-result.json > /tmp/dediren-plan-pipeline.svg
rg 'data-dediren-edge-id|data-dediren-line-jump-masks|<text|<path' /tmp/dediren-plan-pipeline.svg
```

Expected:

```text
The render command returns status ok.
The SVG contains data-dediren-edge-id attributes.
Edge label text remains inside the SVG viewBox.
Line-jump masks, when present inside colored groups, use the local group fill.
```

- [ ] **Step 6: Confirm no tracked work remains outside completed commits**

Run:

```bash
git status --short
```

Expected: no tracked source, fixture, or docs changes remain unstaged or uncommitted. The plan file `docs/superpowers/plans/2026-06-25-archimate-export-render-artifacts.md` and the pre-existing `docs/superpowers/plans/2026-06-25-inherited-followups.md` may appear as untracked files if the plan itself has not been committed.

---

## Final Acceptance Criteria

- OEF exports use `sourceAttachment` for first route points, `targetAttachment` for last route points, and `bendpoint` only for intermediate route points.
- OEF exporter returns `DEDIREN_OEF_LAYOUT_REFERENCE_UNRESOLVED` for unresolved layout/source references before writing XML.
- SVG edge labels avoid route segments, group title bands, group borders, node boxes, and previously placed labels.
- SVG line-jump masks use local group fill when the jump lies inside a group.
- Existing ELK endpoint tests remain unchanged and passing.
- Documentation explains the new OEF and SVG artifact behavior.

## Self-Review Notes

- Spec coverage: all findings with implementation impact are mapped to tasks. ELK layout source changes are intentionally excluded because the reproduced layout already validates and ELK tests cover endpoint placement.
- Placeholder scan: no task relies on unspecified behavior; code snippets name exact methods, constants, tests, and commands.
- Type consistency: helper names used across tasks are `writeConnectionGeometry`, `writeLocation`, `validateLayoutReferences`, `labelObstacleBoxes`, `groupObstacleBoxes`, `edgeRouteObstacleBoxes`, `labelOffsetCandidates`, `backdropFillAt`, and `pointInsideRect`.

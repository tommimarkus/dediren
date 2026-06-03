package dev.dediren.plugins.svgrender;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.contracts.json.JsonSupport;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

class MainTest {
    @Test
    void moduleLoads() {
        assertThat(Main.moduleName()).isEqualTo("svg-render");
    }

    @Test
    void reportsCapabilities() throws Exception {
        PluginResult result = Main.executeForTesting(new String[]{"capabilities"}, "");

        JsonNode capabilities = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(capabilities.at("/id").asText()).isEqualTo("svg-render");
        assertThat(capabilities.at("/runtime/artifact_kind").asText()).isEqualTo("svg");
        assertThat(capabilities.at("/capabilities").toString()).contains("render");
    }

    @Nested
    class RenderContracts {
        @Test
        void outputsSvg() throws Exception {
            JsonNode data = okData(render(renderInput("fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json")));

            String content = data.at("/content").asText();

            assertThat(data.at("/render_result_schema_version").asText()).isEqualTo("render-result.schema.v1");
            assertThat(content).contains("<svg", "Client", "API");
        }

        @Test
        void appliesRichPolicyStyles() throws Exception {
            String content = okContent(render(renderInput("fixtures/layout-result/basic.json", "fixtures/render-policy/rich-svg.json")));
            Document document = svgDocument(content);

            Element background = firstChildElement(document.getDocumentElement(), "rect");
            assertThat(background.getAttribute("fill")).isEqualTo("#f8fafc");

            Element apiNode = groupWithAttribute(document, "data-dediren-node-id", "api");
            Element apiRect = firstChildElement(apiNode, "rect");
            assertThat(apiRect.getAttribute("fill")).isEqualTo("#ecfeff");
            assertThat(apiRect.getAttribute("stroke")).isEqualTo("#0891b2");

            Element clientNode = groupWithAttribute(document, "data-dediren-node-id", "client");
            Element clientRect = firstChildElement(clientNode, "rect");
            assertThat(clientRect.getAttribute("fill")).isEqualTo("#ffffff");
            assertThat(clientRect.getAttribute("stroke")).isEqualTo("#1f2937");

            Element callsEdge = groupWithAttribute(document, "data-dediren-edge-id", "client-calls-api");
            Element callsPath = firstChildElement(callsEdge, "path");
            Element callsLabel = firstChildElement(callsEdge, "text");
            assertThat(callsPath.getAttribute("stroke")).isEqualTo("#7c3aed");
            assertThat(callsLabel.getAttribute("fill")).isEqualTo("#5b21b6");
        }

        @Test
        void preservesStyleNumberPrecision() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    """
                    [
                      {
                        "id": "node-a",
                        "source_id": "node-a",
                        "projection_id": "node-a",
                        "x": 32,
                        "y": 40,
                        "width": 160,
                        "height": 80,
                        "label": "Node A"
                      }
                    ]
                    """,
                    "[]",
                    """
                    {
                      "font": { "family": "Inter", "size": 13.5 },
                      "node": { "stroke_width": 1.25, "rx": 0.5 },
                      "edge": { "stroke_width": 1.25 },
                      "group": { "stroke_width": 1.25, "rx": 0.5, "label_size": 13.5 }
                    }
                    """);
            Document document = svgDocument(okContent(render(input)));

            Element viewport = firstChildElement(document.getDocumentElement(), "g");
            assertThat(viewport.getAttribute("font-family")).isEqualTo("Inter");
            assertThat(viewport.getAttribute("font-size")).isEqualTo("13.5");

            Element node = groupWithAttribute(document, "data-dediren-node-id", "node-a");
            Element shape = firstChildElement(node, "rect");
            assertThat(shape.getAttribute("rx")).isEqualTo("0.5");
            assertThat(shape.getAttribute("stroke-width")).isEqualTo("1.25");
        }

        @Test
        void allowsSchemaValidNonAsciiFontFamily() throws Exception {
            String fontFamily = "\\u00c5".repeat(120);
            JsonNode input = styledInlineInput(
                    "[]",
                    "[]",
                    "[]",
                    "{ \"font\": { \"family\": \"" + fontFamily + "\", \"size\": 14 } }");

            Document document = svgDocument(okContent(render(input)));

            Element viewport = firstChildElement(document.getDocumentElement(), "g");
            assertThat(viewport.getAttribute("font-family")).isEqualTo("\u00c5".repeat(120));
        }

        @Test
        void appliesBaseAndOverrideGroupStylesToGroupElements() throws Exception {
            JsonNode input = styledInlineInput(
                    """
                    [
                      {
                        "id": "base-group",
                        "source_id": "base-group",
                        "projection_id": "base-group",
                        "x": 16,
                        "y": 24,
                        "width": 220,
                        "height": 140,
                        "members": ["node-a"],
                        "label": "Base Group"
                      },
                      {
                        "id": "override-group",
                        "source_id": "override-group",
                        "projection_id": "override-group",
                        "x": 260,
                        "y": 24,
                        "width": 220,
                        "height": 140,
                        "members": ["node-b"],
                        "label": "Override Group"
                      }
                    ]
                    """,
                    "[]",
                    "[]",
                    """
                    {
                      "group": {
                        "fill": "#e0f2fe",
                        "stroke": "#0284c7",
                        "stroke_width": 1.25,
                        "rx": 6.5,
                        "label_fill": "#0c4a6e",
                        "label_size": 13.5
                      },
                      "group_overrides": {
                        "override-group": {
                          "fill": "#fef3c7",
                          "stroke": "#d97706",
                          "stroke_width": 2.5,
                          "rx": 3.25,
                          "label_fill": "#78350f",
                          "label_size": 15.75
                        }
                      }
                    }
                    """);
            Document document = svgDocument(okContent(render(input)));

            Element baseGroup = groupWithAttribute(document, "data-dediren-group-id", "base-group");
            Element baseRect = firstChildElement(baseGroup, "rect");
            Element baseLabel = firstChildElement(baseGroup, "text");
            assertThat(baseRect.getAttribute("fill")).isEqualTo("#e0f2fe");
            assertThat(baseRect.getAttribute("stroke")).isEqualTo("#0284c7");
            assertThat(baseRect.getAttribute("stroke-width")).isEqualTo("1.25");
            assertThat(baseRect.getAttribute("rx")).isEqualTo("6.5");
            assertThat(baseLabel.getAttribute("fill")).isEqualTo("#0c4a6e");
            assertThat(baseLabel.getAttribute("font-size")).isEqualTo("13.5");
            assertThat(baseLabel.getTextContent()).isEqualTo("Base Group");

            Element overrideGroup = groupWithAttribute(document, "data-dediren-group-id", "override-group");
            Element overrideRect = firstChildElement(overrideGroup, "rect");
            Element overrideLabel = firstChildElement(overrideGroup, "text");
            assertThat(overrideRect.getAttribute("fill")).isEqualTo("#fef3c7");
            assertThat(overrideRect.getAttribute("stroke")).isEqualTo("#d97706");
            assertThat(overrideRect.getAttribute("stroke-width")).isEqualTo("2.5");
            assertThat(overrideRect.getAttribute("rx")).isEqualTo("3.25");
            assertThat(overrideLabel.getAttribute("fill")).isEqualTo("#78350f");
            assertThat(overrideLabel.getAttribute("font-size")).isEqualTo("15.75");
            assertThat(overrideLabel.getTextContent()).isEqualTo("Override Group");
        }

        @Test
        void rejectsProfileMismatch() throws Exception {
            JsonNode input = archimateStyleInput();
            ((ObjectNode) input.at("/render_metadata")).put("semantic_profile", "bpmn2");

            JsonNode envelope = error(render(input), "DEDIREN_RENDER_METADATA_PROFILE_MISMATCH");

            assertThat(envelope.at("/diagnostics/0/message").asText()).contains("bpmn2", "archimate");
        }

        @Test
        void rejectsTypePolicyWithoutMetadata() throws Exception {
            ObjectNode input = (ObjectNode) archimateStyleInput();
            input.remove("render_metadata");

            JsonNode envelope = error(render(input), "DEDIREN_RENDER_METADATA_REQUIRED");

            assertThat(envelope.at("/diagnostics/0/message").asText()).contains("requires render metadata");
        }

        @Test
        void rejectsUnsafePolicyColorBeforeRendering() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    """
                    [
                      {
                        "id": "node-a",
                        "source_id": "node-a",
                        "projection_id": "node-a",
                        "x": 32,
                        "y": 40,
                        "width": 160,
                        "height": 80,
                        "label": "Node A"
                      }
                    ]
                    """,
                    "[]",
                    """
                    { "node": { "fill": "url(https://attacker.example/x.svg#p)" } }
                    """);

            JsonNode envelope = error(render(input), "DEDIREN_SVG_POLICY_INVALID");

            assertThat(envelope.at("/diagnostics/0/message").asText()).contains("node.fill", "#RRGGBB");
        }

        @Test
        void rejectsUnsupportedArchimatePolicyType() throws Exception {
            JsonNode input = archimateStyleInput();
            ObjectNode overrides = (ObjectNode) input.at("/policy/style/node_type_overrides");
            overrides.set("BogusElement", JsonSupport.objectMapper().readTree("{\"fill\":\"#ffffff\"}"));

            JsonNode envelope = error(render(input), "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");

            assertThat(envelope.at("/diagnostics/0/path").asText())
                    .isEqualTo("policy.style.node_type_overrides.BogusElement");
        }

        @Test
        void rejectsUnsupportedUmlPolicyType() throws Exception {
            JsonNode input = umlStyleInput();
            ObjectNode overrides = (ObjectNode) input.at("/policy/style/node_type_overrides");
            overrides.set("BogusUml", JsonSupport.objectMapper().readTree("{\"fill\":\"#ffffff\"}"));

            JsonNode envelope = error(render(input), "DEDIREN_UML_ELEMENT_TYPE_UNSUPPORTED");

            assertThat(envelope.at("/diagnostics/0/path").asText())
                    .isEqualTo("policy.style.node_type_overrides.BogusUml");
        }
    }

    @Nested
    class SemanticRendering {
        @Test
        void appliesArchimateNodeDecoratorsFromTypeOverrides() throws Exception {
            JsonNode input = archimateStyleInput();
            ((ObjectNode) input.at("/policy/style/node_type_overrides/ApplicationComponent"))
                    .put("decorator", "archimate_application_component");
            ((ObjectNode) input.at("/policy/style/node_type_overrides/ApplicationService"))
                    .put("decorator", "archimate_application_service");

            Document document = svgDocument(okContent(render(input)));

            Element component = groupWithAttribute(document, "data-dediren-node-id", "orders-component");
            Element service = groupWithAttribute(document, "data-dediren-node-id", "orders-service");
            Element componentDecorator = childGroupWithAttribute(
                    component,
                    "data-dediren-node-decorator",
                    "archimate_application_component");
            Element serviceDecorator = childGroupWithAttribute(
                    service,
                    "data-dediren-node-decorator",
                    "archimate_application_service");

            assertThat(childElements(componentDecorator, "rect")).hasSizeGreaterThanOrEqualTo(2);
            assertThat(childElements(serviceDecorator, "rect")).hasSizeGreaterThanOrEqualTo(1);
            assertThat(childElements(serviceDecorator, "path")).isEmpty();
        }

        @Test
        void rendersArchimateJunctionNodesAsCircleSymbols() throws Exception {
            JsonNode policy = fixtureJson("fixtures/render-policy/archimate-svg.json");
            JsonNode input = archimateRenderInput(
                    policy,
                    """
                    [
                      {
                        "id": "and-junction",
                        "source_id": "and-junction",
                        "projection_id": "and-junction",
                        "x": 80,
                        "y": 80,
                        "width": 28,
                        "height": 28,
                        "label": ""
                      },
                      {
                        "id": "or-junction",
                        "source_id": "or-junction",
                        "projection_id": "or-junction",
                        "x": 160,
                        "y": 80,
                        "width": 28,
                        "height": 28,
                        "label": ""
                      }
                    ]
                    """,
                    "[]",
                    """
                    {
                      "and-junction": { "type": "AndJunction", "source_id": "and-junction" },
                      "or-junction": { "type": "OrJunction", "source_id": "or-junction" }
                    }
                    """,
                    "{}");

            Document document = svgDocument(okContent(render(input)));

            Element andShape = firstElementWithAttribute(
                    groupWithAttribute(document, "data-dediren-node-id", "and-junction"),
                    "data-dediren-node-shape");
            assertThat(andShape.getAttribute("data-dediren-node-shape")).isEqualTo("archimate_and_junction");
            assertThat(andShape.getAttribute("fill")).isEqualTo("#111827");

            Element orShape = firstElementWithAttribute(
                    groupWithAttribute(document, "data-dediren-node-id", "or-junction"),
                    "data-dediren-node-shape");
            assertThat(orShape.getAttribute("data-dediren-node-shape")).isEqualTo("archimate_or_junction");
            assertThat(orShape.getAttribute("fill")).isEqualTo("#ffffff");
        }

        @Test
        void rendersUmlClassCompartments() throws Exception {
            Document document = svgDocument(okContent(render(umlStyleInput())));
            String content = okContent(render(umlStyleInput()));

            Element order = groupWithAttribute(document, "data-dediren-node-id", "class-order");
            Element shape = firstElementWithAttribute(order, "data-dediren-node-shape");

            assertThat(shape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_class");
            childGroupWithAttribute(order, "data-dediren-node-decorator", "uml_class");
            assertThat(content).contains("id : OrderId", "+ submit() : void");
        }

        @Test
        void rendersUmlEnumerationLiterals() throws Exception {
            String content = okContent(render(umlStyleInput()));
            Document document = svgDocument(content);

            Element enumeration = groupWithAttribute(document, "data-dediren-node-id", "enum-order-status");
            Element shape = firstElementWithAttribute(enumeration, "data-dediren-node-shape");

            assertThat(shape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_enumeration");
            childGroupWithAttribute(enumeration, "data-dediren-node-decorator", "uml_enumeration");
            assertThat(content).contains("&#171;enumeration&#187;", "Submitted");
        }

        @Test
        void appliesArchimateRealizationEdgeNotation() throws Exception {
            JsonNode input = archimateStyleInput();
            ((ObjectNode) input.at("/policy/style/edge_type_overrides/Realization"))
                    .put("line_style", "dashed")
                    .put("marker_end", "hollow_triangle");

            Document document = svgDocument(okContent(render(input)));

            Element edge = groupWithAttribute(document, "data-dediren-edge-id", "orders-realizes-service");
            Element path = firstChildElement(edge, "path");
            assertThat(path.getAttribute("stroke-dasharray")).isEqualTo("8 5");
            assertThat(path.getAttribute("marker-end")).isEqualTo("url(#marker-end-orders-realizes-service)");

            Element marker = marker(document, "marker-end-orders-realizes-service", "data-dediren-edge-marker-end");
            Element markerPath = firstChildElement(marker, "path");
            assertThat(marker.getAttribute("data-dediren-edge-marker-end")).isEqualTo("hollow_triangle");
            assertThat(markerPath.getAttribute("fill")).isEqualTo("#ffffff");
            assertThat(markerPath.getAttribute("stroke")).isEqualTo("#374151");
        }

        @Test
        void appliesUmlRelationshipMarkers() throws Exception {
            String content = okContent(render(umlStyleInput()));
            Document document = svgDocument(content);

            Element edge = groupWithAttribute(document, "data-dediren-edge-id", "order-has-lines");
            Element path = firstChildElement(edge, "path");

            assertThat(path.getAttribute("marker-start")).isEqualTo("url(#marker-start-order-has-lines)");
            assertThat(content).contains("data-dediren-edge-marker-start=\"filled_diamond\"");
        }

        @Test
        void edgeIdOverrideCanDisableMarker() throws Exception {
            JsonNode input = archimateStyleInput();
            ObjectNode edgeOverrides = (ObjectNode) input.at("/policy/style/edge_overrides");
            edgeOverrides.set("orders-realizes-service", JsonSupport.objectMapper().readTree("""
                    {
                      "marker_end": "none",
                      "line_style": "solid"
                    }
                    """));

            Document document = svgDocument(okContent(render(input)));

            Element edge = groupWithAttribute(document, "data-dediren-edge-id", "orders-realizes-service");
            Element path = firstChildElement(edge, "path");
            assertThat(path.hasAttribute("marker-end")).isFalse();
            assertThat(path.hasAttribute("stroke-dasharray")).isFalse();
        }
    }

    @Nested
    class RouteAndLabelRendering {
        @Test
        void placesEdgeLabelNearRouteMidpointForVerticalRoute() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    "[]",
                    """
                    [
                      {
                        "id": "routed-edge",
                        "source": "source-node",
                        "target": "target-node",
                        "source_id": "routed-edge",
                        "projection_id": "routed-edge",
                        "points": [
                          { "x": 100, "y": 0 },
                          { "x": 100, "y": 100 },
                          { "x": 100, "y": 200 }
                        ],
                        "label": "routed label"
                      }
                    ]
                    """,
                    "{}");
            Document document = svgDocument(okContent(render(input)));

            Element label = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "routed-edge"), "text");

            assertThat(label.getAttribute("text-anchor")).isEqualTo("end");
            assertThat(Double.parseDouble(label.getAttribute("x"))).isCloseTo(94.0, org.assertj.core.data.Offset.offset(3.0));
            assertThat(Double.parseDouble(label.getAttribute("y"))).isCloseTo(100.0, org.assertj.core.data.Offset.offset(6.0));
        }

        @Test
        void defaultsHorizontalEdgeLabelNearStart() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    "[]",
                    """
                    [
                      {
                        "id": "horizontal-edge",
                        "source": "source-node",
                        "target": "target-node",
                        "source_id": "horizontal-edge",
                        "projection_id": "horizontal-edge",
                        "points": [
                          { "x": 0, "y": 120 },
                          { "x": 100, "y": 120 }
                        ],
                        "label": "start"
                      }
                    ]
                    """,
                    "{}");
            Document document = svgDocument(okContent(render(input)));

            Element label = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "horizontal-edge"), "text");

            assertThat(Double.parseDouble(label.getAttribute("x"))).isCloseTo(18.0, org.assertj.core.data.Offset.offset(1.0));
        }

        @Test
        void allowsCenteredHorizontalEdgeLabelsByPolicy() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    "[]",
                    """
                    [
                      {
                        "id": "horizontal-edge",
                        "source": "source-node",
                        "target": "target-node",
                        "source_id": "horizontal-edge",
                        "projection_id": "horizontal-edge",
                        "points": [
                          { "x": 0, "y": 120 },
                          { "x": 100, "y": 120 }
                        ],
                        "label": "center label"
                      }
                    ]
                    """,
                    "{ \"edge\": { \"label_horizontal_position\": \"center\" } }");
            Document document = svgDocument(okContent(render(input)));

            Element label = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "horizontal-edge"), "text");

            assertThat(Double.parseDouble(label.getAttribute("x"))).isCloseTo(50.0, org.assertj.core.data.Offset.offset(1.0));
        }

        @Test
        void paintsEdgeLabelWithBackgroundHalo() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    "[]",
                    """
                    [
                      {
                        "id": "labeled-edge",
                        "source": "source-node",
                        "target": "target-node",
                        "source_id": "labeled-edge",
                        "projection_id": "labeled-edge",
                        "points": [
                          { "x": 0, "y": 100 },
                          { "x": 200, "y": 100 }
                        ],
                        "label": "clear label"
                      }
                    ]
                    """,
                    "{ \"background\": { \"fill\": \"#f8fafc\" } }");
            Document document = svgDocument(okContent(render(input)));

            Element label = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "labeled-edge"), "text");

            assertThat(label.getAttribute("paint-order")).isEqualTo("stroke");
            assertThat(label.getAttribute("stroke")).isEqualTo("#f8fafc");
            assertThat(label.getAttribute("stroke-width")).isEqualTo("4");
        }

        @Test
        void addsLineJumpForLaterCrossingEdge() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    "[]",
                    """
                    [
                      {
                        "id": "first-edge",
                        "source": "left",
                        "target": "right",
                        "source_id": "first-edge",
                        "projection_id": "first-edge",
                        "points": [
                          { "x": 0, "y": 100 },
                          { "x": 200, "y": 100 }
                        ],
                        "label": "first"
                      },
                      {
                        "id": "front-edge",
                        "source": "top",
                        "target": "bottom",
                        "source_id": "front-edge",
                        "projection_id": "front-edge",
                        "points": [
                          { "x": 100, "y": 0 },
                          { "x": 100, "y": 200 }
                        ],
                        "label": "front"
                      }
                    ]
                    """,
                    "{}");
            Document document = svgDocument(okContent(render(input)));

            Element firstPath = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "first-edge"), "path");
            Element frontEdge = groupWithAttribute(document, "data-dediren-edge-id", "front-edge");
            Element frontPath = firstChildElement(frontEdge, "path");

            assertThat(firstPath.getAttribute("d")).doesNotContain(" Q ");
            assertThat(frontPath.getAttribute("d"))
                    .contains("L 100.0 94.0")
                    .contains("Q 106.0 100.0 100.0 106.0");
            Element masks = childGroupWithAttribute(frontEdge, "data-dediren-line-jump-masks", "front-edge");
            assertThat(firstChildElement(masks, "path").getAttribute("stroke")).isEqualTo("#ffffff");
        }

        @Test
        void cropsSmallDiagramToContentBounds() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    """
                    [
                      {
                        "id": "node-a",
                        "source_id": "node-a",
                        "projection_id": "node-a",
                        "x": 320,
                        "y": 240,
                        "width": 120,
                        "height": 64,
                        "label": "Node A"
                      }
                    ]
                    """,
                    "[]",
                    "{}");

            Document document = svgDocument(okContent(render(input)));

            assertThat(document.getDocumentElement().getAttribute("viewBox")).isEqualTo("304.0 224.0 152.0 96.0");
        }
    }

    private static PluginResult render(JsonNode input) throws Exception {
        return Main.executeForTesting(new String[]{"render"}, JsonSupport.objectMapper().writeValueAsString(input));
    }

    private static JsonNode okData(PluginResult result) throws Exception {
        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
        assertThat(result.exitCode()).describedAs(result.stderr()).isZero();
        assertThat(envelope.at("/status").asText()).describedAs(result.stdout()).isEqualTo("ok");
        return envelope.get("data");
    }

    private static String okContent(PluginResult result) throws Exception {
        return okData(result).at("/content").asText();
    }

    private static JsonNode error(PluginResult result, String expectedCode) throws Exception {
        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
        assertThat(result.exitCode()).describedAs(result.stderr()).isNotZero();
        assertThat(envelope.at("/status").asText()).describedAs(result.stdout()).isEqualTo("error");
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo(expectedCode);
        return envelope;
    }

    private static JsonNode archimateStyleInput() throws Exception {
        ObjectNode input = JsonSupport.objectMapper().createObjectNode();
        input.set("layout_result", fixtureJson("fixtures/layout-result/archimate-oef-basic.json"));
        input.set("render_metadata", fixtureJson("fixtures/render-metadata/archimate-basic.json"));
        input.set("policy", fixtureJson("fixtures/render-policy/archimate-svg.json"));
        return input;
    }

    private static JsonNode umlStyleInput() throws Exception {
        ObjectNode input = JsonSupport.objectMapper().createObjectNode();
        input.set("layout_result", fixtureJson("fixtures/layout-result/uml-basic.json"));
        input.set("render_metadata", fixtureJson("fixtures/render-metadata/uml-basic.json"));
        input.set("policy", fixtureJson("fixtures/render-policy/uml-svg.json"));
        return input;
    }

    private static JsonNode archimateRenderInput(
            JsonNode policy,
            String nodes,
            String edges,
            String metadataNodes,
            String metadataEdges) throws Exception {
        ObjectNode layout = JsonSupport.objectMapper().createObjectNode();
        layout.put("layout_result_schema_version", "layout-result.schema.v1");
        layout.put("view_id", "archimate-coverage");
        layout.set("nodes", JsonSupport.objectMapper().readTree(nodes));
        layout.set("edges", JsonSupport.objectMapper().readTree(edges));
        layout.set("groups", JsonSupport.objectMapper().createArrayNode());
        layout.set("warnings", JsonSupport.objectMapper().createArrayNode());

        ObjectNode metadata = JsonSupport.objectMapper().createObjectNode();
        metadata.put("render_metadata_schema_version", "render-metadata.schema.v1");
        metadata.put("semantic_profile", "archimate");
        metadata.set("nodes", JsonSupport.objectMapper().readTree(metadataNodes));
        metadata.set("edges", JsonSupport.objectMapper().readTree(metadataEdges));

        ObjectNode input = JsonSupport.objectMapper().createObjectNode();
        input.set("layout_result", layout);
        input.set("render_metadata", metadata);
        input.set("policy", policy);
        return input;
    }

    private static JsonNode renderInput(String layoutPath, String policyPath) throws Exception {
        ObjectNode input = JsonSupport.objectMapper().createObjectNode();
        input.set("layout_result", fixtureJson(layoutPath));
        input.set("policy", fixtureJson(policyPath));
        return input;
    }

    private static JsonNode styledInlineInput(String groups, String nodes, String edges, String style) throws Exception {
        ObjectNode layout = JsonSupport.objectMapper().createObjectNode();
        layout.put("layout_result_schema_version", "layout-result.schema.v1");
        layout.put("view_id", "inline-test");
        layout.set("groups", JsonSupport.objectMapper().readTree(groups));
        layout.set("nodes", JsonSupport.objectMapper().readTree(nodes));
        layout.set("edges", JsonSupport.objectMapper().readTree(edges));
        layout.set("warnings", JsonSupport.objectMapper().createArrayNode());

        ObjectNode policy = JsonSupport.objectMapper().createObjectNode();
        policy.put("svg_render_policy_schema_version", "svg-render-policy.schema.v1");
        ObjectNode page = policy.putObject("page");
        page.put("width", 640);
        page.put("height", 360);
        ObjectNode margin = policy.putObject("margin");
        margin.put("top", 16);
        margin.put("right", 16);
        margin.put("bottom", 16);
        margin.put("left", 16);
        policy.set("style", JsonSupport.objectMapper().readTree(style));

        ObjectNode input = JsonSupport.objectMapper().createObjectNode();
        input.set("layout_result", layout);
        input.set("policy", policy);
        return input;
    }

    private static Document svgDocument(String content) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
    }

    private static Element groupWithAttribute(Document document, String name, String value) {
        var elements = document.getElementsByTagName("g");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (value.equals(element.getAttribute(name))) {
                return element;
            }
        }
        throw new AssertionError("expected SVG group with " + name + "=" + value);
    }

    private static Element firstChildElement(Element parent, String tagName) {
        var children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child && child.getTagName().equals(tagName)) {
                return child;
            }
        }
        throw new AssertionError("expected <" + parent.getTagName() + "> to contain <" + tagName + ">");
    }

    private static Element childGroupWithAttribute(Element parent, String name, String value) {
        for (Element element : childElements(parent, "g")) {
            if (value.equals(element.getAttribute(name))) {
                return element;
            }
        }
        throw new AssertionError("expected child group with " + name + "=" + value);
    }

    private static Element firstElementWithAttribute(Element parent, String name) {
        var children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child && child.hasAttribute(name)) {
                return child;
            }
        }
        throw new AssertionError("expected child element with " + name);
    }

    private static Element marker(Document document, String id, String markerAttribute) {
        var elements = document.getElementsByTagName("marker");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(element.getAttribute("id")) && element.hasAttribute(markerAttribute)) {
                return element;
            }
        }
        throw new AssertionError("expected marker " + id);
    }

    private static java.util.List<Element> childElements(Element parent, String tagName) {
        var elements = new java.util.ArrayList<Element>();
        var children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child && child.getTagName().equals(tagName)) {
                elements.add(child);
            }
        }
        return elements;
    }

    private static JsonNode fixtureJson(String path) throws Exception {
        return JsonSupport.objectMapper().readTree(fixture(path));
    }

    private static String fixture(String path) throws Exception {
        return Files.readString(workspaceRoot().resolve(path));
    }

    private static Path workspaceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("schemas/model.schema.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from user.dir");
    }
}

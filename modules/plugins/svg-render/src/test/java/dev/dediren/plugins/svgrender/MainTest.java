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

package dev.dediren.plugins.svgrender;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.testsupport.SchemaAssertions;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
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

            String content = data.at("/artifacts/0/content").asText();

            assertThat(data.at("/render_result_schema_version").asText()).isEqualTo("render-result.schema.v2");
            assertThat(data.at("/artifacts/0/artifact_kind").asText()).isEqualTo("svg");
            assertThat(content).contains("<svg", "Client", "API");
        }

        @Test
        void umlPolicyFixtureMatchesSvgPolicySchema() {
            assertThat(SchemaAssertions.validateFixture(
                    workspaceRoot(),
                    "schemas/svg-render-policy.schema.json",
                    "fixtures/render-policy/uml-svg.json"))
                    .isEmpty();
        }

        @Test
        void rendersUmlStateMachineNotation() throws Exception {
            String content = okContent(render(renderInput(
                    "fixtures/layout-result/uml-state-machine-basic.json",
                    "fixtures/render-policy/uml-svg.json",
                    "fixtures/render-metadata/uml-state-machine-basic.json")));
            Document document = svgDocument(content);

            assertThat(content).contains(">Draft<");
            groupWithAttribute(document, "data-dediren-node-id", "draft");
            groupWithAttribute(document, "data-dediren-node-id", "initial");
            groupWithAttribute(document, "data-dediren-node-id", "payment-choice");
            groupWithAttribute(document, "data-dediren-edge-id", "t-approve");
            groupWithAttribute(document, "data-dediren-group-id", "order-lifecycle-frame");

            Element draft = groupWithAttribute(document, "data-dediren-node-id", "draft");
            Element draftShape = firstChildElement(draft, "rect");
            assertThat(draftShape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_state");
            assertThat(Double.parseDouble(draftShape.getAttribute("rx"))).isGreaterThanOrEqualTo(14.0);

            Element initial = groupWithAttribute(document, "data-dediren-node-id", "initial");
            Element initialShape = firstChildElement(initial, "circle");
            assertThat(initialShape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_pseudostate");
            assertThat(childElements(initial, "text")).isEmpty();

            Element paymentChoice = groupWithAttribute(document, "data-dediren-node-id", "payment-choice");
            Element choiceShape = firstChildElement(paymentChoice, "path");
            assertThat(choiceShape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_pseudostate");

            Element approve = groupWithAttribute(document, "data-dediren-edge-id", "t-approve");
            Element approvePath = firstChildElement(approve, "path");
            assertMarkerForStyle(document, approvePath, "t-approve", "end", "open_arrow");
            Element approveLabel = firstChildElement(approve, "text");
            assertThat(approveLabel.getAttribute("text-anchor")).isEqualTo("middle");

            Element closed = groupWithAttribute(document, "data-dediren-node-id", "closed");
            Element finalShape = firstChildElement(closed, "g");
            assertThat(finalShape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_final_state");
            assertThat(childElements(finalShape, "circle")).hasSize(2);
            assertThat(childElements(closed, "text")).isEmpty();
        }

        @Test
        void rendersUmlUseCaseNotation() throws Exception {
            String content = okContent(render(renderInput(
                    "fixtures/layout-result/uml-use-case-basic.json",
                    "fixtures/render-policy/uml-svg.json",
                    "fixtures/render-metadata/uml-use-case-basic.json")));
            Document document = svgDocument(content);

            assertThat(content).contains(">Customer<", ">Place Order<", "Order Service");
            groupWithAttribute(document, "data-dediren-node-id", "customer");
            groupWithAttribute(document, "data-dediren-node-id", "place-order");
            groupWithAttribute(document, "data-dediren-node-id", "payment-extension");
            groupWithAttribute(document, "data-dediren-edge-id", "include-authentication");
            groupWithAttribute(document, "data-dediren-edge-id", "extend-discount");
            groupWithAttribute(document, "data-dediren-group-id", "order-service-boundary");

            Element customer = groupWithAttribute(document, "data-dediren-node-id", "customer");
            Element actorShape = firstChildElement(customer, "g");
            assertThat(actorShape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_actor");
            assertThat(childElements(actorShape, "circle")).hasSize(1);
            assertThat(childElements(actorShape, "path")).hasSize(1);

            Element placeOrder = groupWithAttribute(document, "data-dediren-node-id", "place-order");
            Element useCaseShape = firstChildElement(placeOrder, "ellipse");
            assertThat(useCaseShape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_use_case");

            Element paymentExtension = groupWithAttribute(document, "data-dediren-node-id", "payment-extension");
            Element extensionShape = firstChildElement(paymentExtension, "rect");
            assertThat(extensionShape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_extension_point");

            Element include = groupWithAttribute(document, "data-dediren-edge-id", "include-authentication");
            Element includePath = firstChildElement(include, "path");
            assertMarkerForStyle(document, includePath, "include-authentication", "end", "open_arrow");
            assertThat(includePath.getAttribute("stroke-dasharray")).isEqualTo("8 5");

            Element extend = groupWithAttribute(document, "data-dediren-edge-id", "extend-discount");
            Element extendPath = firstChildElement(extend, "path");
            assertMarkerForStyle(document, extendPath, "extend-discount", "end", "open_arrow");
            assertThat(extendPath.getAttribute("stroke-dasharray")).isEqualTo("8 5");
        }

        @Test
        void rendersUmlComponentNotation() throws Exception {
            String content = okContent(render(renderInput(
                    "fixtures/layout-result/uml-component-basic.json",
                    "fixtures/render-policy/uml-svg.json",
                    "fixtures/render-metadata/uml-component-basic.json")));
            Document document = svgDocument(content);

            assertThat(content).contains(">Order API<", ">REST<", ">PaymentGateway<");
            groupWithAttribute(document, "data-dediren-node-id", "component-order-api");
            groupWithAttribute(document, "data-dediren-node-id", "port-rest-api");
            groupWithAttribute(document, "data-dediren-edge-id", "order-api-uses-payment");
            groupWithAttribute(document, "data-dediren-group-id", "orders-package-boundary");
            groupWithAttribute(document, "data-dediren-group-id", "order-api-boundary");

            Element orderApi = groupWithAttribute(document, "data-dediren-node-id", "component-order-api");
            Element componentShape = firstChildElement(orderApi, "rect");
            assertThat(componentShape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_component");
            Element componentDecorator = childElementWithAttribute(
                    orderApi,
                    "g",
                    "data-dediren-node-decorator",
                    "uml_component");
            assertThat(childElements(componentDecorator, "rect")).hasSizeGreaterThanOrEqualTo(2);

            Element restPort = groupWithAttribute(document, "data-dediren-node-id", "port-rest-api");
            Element portShape = firstChildElement(restPort, "rect");
            assertThat(portShape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_port");

            Element usage = groupWithAttribute(document, "data-dediren-edge-id", "order-api-uses-payment");
            Element usagePath = firstChildElement(usage, "path");
            assertMarkerForStyle(document, usagePath, "order-api-uses-payment", "end", "open_arrow");
            assertThat(usagePath.getAttribute("stroke-dasharray")).isEqualTo("8 5");
        }

        @Test
        void rendersUmlDeploymentNotation() throws Exception {
            String content = okContent(render(renderInput(
                    "fixtures/layout-result/uml-deployment-basic.json",
                    "fixtures/render-policy/uml-svg.json",
                    "fixtures/render-metadata/uml-deployment-basic.json")));
            Document document = svgDocument(content);

            assertThat(content).contains(
                    ">Production Node<",
                    ">Orders Runtime<",
                    ">orders-service.jar<",
                    "&#171;device&#187;",
                    "&#171;executionEnvironment&#187;");
            groupWithAttribute(document, "data-dediren-node-id", "device-prod-node");
            groupWithAttribute(document, "data-dediren-node-id", "ee-orders-runtime");
            groupWithAttribute(document, "data-dediren-node-id", "artifact-orders-service");
            groupWithAttribute(document, "data-dediren-node-id", "deployment-spec-orders");
            groupWithAttribute(document, "data-dediren-edge-id", "deploy-orders-service");
            groupWithAttribute(document, "data-dediren-edge-id", "artifact-manifests-order-api");
            groupWithAttribute(document, "data-dediren-edge-id", "orders-runtime-payment-path");
            groupWithAttribute(document, "data-dediren-group-id", "prod-node-boundary");

            Element device = groupWithAttribute(document, "data-dediren-node-id", "device-prod-node");
            Element deviceShape = firstChildElement(device, "g");
            assertThat(deviceShape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_device");
            assertThat(childElements(deviceShape, "rect")).hasSize(1);
            assertThat(childElements(deviceShape, "path"))
                    .extracting(path -> path.getAttribute("data-dediren-deployment-target-part"))
                    .contains("top", "side");

            Element artifact = groupWithAttribute(document, "data-dediren-node-id", "artifact-orders-service");
            Element artifactShape = firstChildElement(artifact, "g");
            assertThat(artifactShape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_artifact");
            assertThat(childElements(artifactShape, "path"))
                    .extracting(path -> path.getAttribute("data-dediren-artifact-part"))
                    .contains("body", "fold");

            Element spec = groupWithAttribute(document, "data-dediren-node-id", "deployment-spec-orders");
            Element specShape = firstChildElement(spec, "g");
            assertThat(specShape.getAttribute("data-dediren-node-shape"))
                    .isEqualTo("uml_deployment_specification");

            Element deployment = groupWithAttribute(document, "data-dediren-edge-id", "deploy-orders-service");
            Element deploymentPath = firstChildElement(deployment, "path");
            assertMarkerForStyle(document, deploymentPath, "deploy-orders-service", "end", "open_arrow");
            assertThat(deploymentPath.getAttribute("stroke-dasharray")).isEqualTo("8 5");

            Element manifestation = groupWithAttribute(document, "data-dediren-edge-id", "artifact-manifests-order-api");
            Element manifestationPath = firstChildElement(manifestation, "path");
            assertMarkerForStyle(document, manifestationPath, "artifact-manifests-order-api", "end", "open_arrow");
            assertThat(manifestationPath.getAttribute("stroke-dasharray")).isEqualTo("8 5");

            Element communicationPath = groupWithAttribute(document, "data-dediren-edge-id", "orders-runtime-payment-path");
            Element communicationPathShape = firstChildElement(communicationPath, "path");
            assertThat(communicationPathShape.hasAttribute("marker-end")).isFalse();
            assertThat(communicationPathShape.hasAttribute("stroke-dasharray")).isFalse();
        }

        @Test
        void rejectsMalformedUmlSequenceMessageMetadata() throws Exception {
            assertInvalidUmlSequenceMessageMetadata(
                    properties -> properties.remove("sequence"),
                    "render_metadata.edges.m1.properties.sequence");
            assertInvalidUmlSequenceMessageMetadata(
                    properties -> properties.put("sequence", 0),
                    "render_metadata.edges.m1.properties.sequence");
            assertInvalidUmlSequenceMessageMetadata(
                    properties -> properties.put("sequence", 1.25),
                    "render_metadata.edges.m1.properties.sequence");
            assertInvalidUmlSequenceMessageMetadata(
                    properties -> properties.put("message_sort", "lostMessage"),
                    "render_metadata.edges.m1.properties.message_sort");
            assertInvalidUmlSequenceMessageMetadata(
                    properties -> properties.put("message_sort", 3),
                    "render_metadata.edges.m1.properties.message_sort");
        }

        @Test
        void rejectsMalformedUmlSequenceCombinedFragmentMetadata() throws Exception {
            assertInvalidUmlCombinedFragmentMetadata(
                    properties -> properties.remove("operator"),
                    "render_metadata.nodes.cf-availability.properties.operator");
            assertInvalidUmlCombinedFragmentMetadata(
                    properties -> properties.put("operator", 3),
                    "render_metadata.nodes.cf-availability.properties.operator");
            assertInvalidUmlCombinedFragmentMetadata(
                    properties -> properties.put("operator", "ignore"),
                    "render_metadata.nodes.cf-availability.properties.operator");
            assertInvalidUmlCombinedFragmentMetadata(
                    properties -> properties.put("operands", "op-in-stock"),
                    "render_metadata.nodes.cf-availability.properties.operands");
            assertInvalidUmlCombinedFragmentMetadata(
                    properties -> properties.putArray("operands"),
                    "render_metadata.nodes.cf-availability.properties.operands");
            assertInvalidUmlCombinedFragmentMetadata(
                    properties -> properties.putArray("operands").add(3),
                    "render_metadata.nodes.cf-availability.properties.operands");
            assertInvalidUmlCombinedFragmentMetadata(
                    properties -> properties.putArray("operands").add("op-missing"),
                    "render_metadata.nodes.cf-availability.properties.operands");
            assertInvalidUmlCombinedFragmentMetadata(
                    properties -> properties.put("covered", "customer"),
                    "render_metadata.nodes.cf-availability.properties.covered");
            assertInvalidUmlCombinedFragmentMetadata(
                    properties -> properties.putArray("covered").add(3),
                    "render_metadata.nodes.cf-availability.properties.covered");
        }

        @Test
        void rejectsMalformedUmlSequenceInteractionOperandMetadata() throws Exception {
            assertInvalidUmlInteractionOperandMetadata(
                    properties -> properties.remove("combined_fragment"),
                    "render_metadata.nodes.op-in-stock.properties.combined_fragment");
            assertInvalidUmlInteractionOperandMetadata(
                    properties -> properties.put("combined_fragment", 3),
                    "render_metadata.nodes.op-in-stock.properties.combined_fragment");
            assertInvalidUmlInteractionOperandMetadata(
                    properties -> properties.put("order", 0),
                    "render_metadata.nodes.op-in-stock.properties.order");
            assertInvalidUmlInteractionOperandMetadata(
                    properties -> properties.put("order", 1.25),
                    "render_metadata.nodes.op-in-stock.properties.order");
            assertInvalidUmlInteractionOperandMetadata(
                    properties -> properties.put("fragments", "m1"),
                    "render_metadata.nodes.op-in-stock.properties.fragments");
            assertInvalidUmlInteractionOperandMetadata(
                    properties -> properties.putArray("fragments"),
                    "render_metadata.nodes.op-in-stock.properties.fragments");
            assertInvalidUmlInteractionOperandMetadata(
                    properties -> properties.putArray("fragments").add(3),
                    "render_metadata.nodes.op-in-stock.properties.fragments");
            assertInvalidUmlInteractionOperandMetadata(
                    properties -> properties.putArray("fragments").add("m-missing"),
                    "render_metadata.nodes.op-in-stock.properties.fragments");
            assertInvalidUmlInteractionOperandMetadata(
                    properties -> properties.put("guard", 3),
                    "render_metadata.nodes.op-in-stock.properties.guard");
        }

        @Test
        void rejectsUmlSequenceCombinedFragmentMetadataWithInvalidOperandCount() throws Exception {
            JsonNode input = umlSequenceFragmentsStyleInput();
            ((ArrayNode) input.at("/render_metadata/nodes/cf-coupon/properties/operands")).add("op-in-stock");

            JsonNode envelope = error(render(input), "DEDIREN_UML_COMBINED_FRAGMENT_METADATA_INVALID");

            assertThat(envelope.at("/diagnostics/0/path").asText())
                    .isEqualTo("render_metadata.nodes.cf-coupon.properties.operands");
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

        @Test
        void archimateGroupingMetadataRendersGroupDecorator() throws Exception {
            ObjectNode layout = JsonSupport.objectMapper().createObjectNode();
            layout.put("layout_result_schema_version", "layout-result.schema.v1");
            layout.put("view_id", "main");
            layout.set("nodes", JsonSupport.objectMapper().createArrayNode());
            layout.set("edges", JsonSupport.objectMapper().createArrayNode());
            layout.set("groups", JsonSupport.objectMapper().readTree("""
                    [
                      {
                        "id": "customer-domain",
                        "source_id": "customer-domain",
                        "projection_id": "customer-domain",
                        "provenance": { "semantic_backed": { "source_id": "customer-domain" } },
                        "x": 20,
                        "y": 20,
                        "width": 240,
                        "height": 140,
                        "members": [],
                        "label": "Customer Domain"
                      }
                    ]
                    """));
            layout.set("warnings", JsonSupport.objectMapper().createArrayNode());

            ObjectNode metadata = JsonSupport.objectMapper().createObjectNode();
            metadata.put("render_metadata_schema_version", "render-metadata.schema.v1");
            metadata.put("semantic_profile", "archimate");
            metadata.set("nodes", JsonSupport.objectMapper().createObjectNode());
            metadata.set("edges", JsonSupport.objectMapper().createObjectNode());
            metadata.set("groups", JsonSupport.objectMapper().readTree("""
                    {
                      "customer-domain": {
                        "type": "Grouping",
                        "source_id": "customer-domain"
                      }
                    }
                    """));

            JsonNode policy = JsonSupport.objectMapper().readTree("""
                    {
                      "svg_render_policy_schema_version": "svg-render-policy.schema.v1",
                      "semantic_profile": "archimate",
                      "page": { "width": 400, "height": 240 },
                      "margin": { "top": 24, "right": 24, "bottom": 24, "left": 24 },
                      "style": {
                        "group_type_overrides": {
                          "Grouping": {
                            "decorator": "archimate_grouping",
                            "fill": "#fef9c3",
                            "stroke": "#a16207"
                          }
                        }
                      }
                    }
                    """);

            ObjectNode input = JsonSupport.objectMapper().createObjectNode();
            input.set("layout_result", layout);
            input.set("render_metadata", metadata);
            input.set("policy", policy);

            Document document = svgDocument(okContent(render(input)));

            Element group = groupWithAttribute(document, "data-dediren-group-id", "customer-domain");
            assertThat(group.getAttribute("data-dediren-group-type")).isEqualTo("Grouping");
            assertThat(group.getAttribute("data-dediren-group-source-id")).isEqualTo("customer-domain");
            childGroupWithAttribute(group, "data-dediren-group-decorator", "archimate_grouping");
        }

        @Test
        void coversEachArchimateNodeTypeFromPolicy() throws Exception {
            JsonNode policy = fixtureJson("fixtures/render-policy/archimate-svg.json");
            ObjectNode nodeStyles = (ObjectNode) policy.at("/style/node_type_overrides");
            ArrayNode nodes = JsonSupport.objectMapper().createArrayNode();
            ObjectNode metadataNodes = JsonSupport.objectMapper().createObjectNode();
            int index = 0;
            for (var fields = nodeStyles.fields(); fields.hasNext(); ) {
                var field = fields.next();
                String nodeType = field.getKey();
                String id = "archimate-node-" + index;
                nodes.add(JsonSupport.objectMapper().readTree("""
                        {
                          "id": "%s",
                          "source_id": "%s",
                          "projection_id": "%s",
                          "x": %d,
                          "y": %d,
                          "width": 128,
                          "height": 68,
                          "label": "%s"
                        }
                        """.formatted(id, id, id, 32 + (index % 6) * 150, 40 + (index / 6) * 95, nodeType)));
                metadataNodes.set(id, JsonSupport.objectMapper().readTree("""
                        {
                          "type": "%s",
                          "source_id": "%s"
                        }
                        """.formatted(nodeType, id)));
                index++;
            }

            String content = okContent(render(semanticRenderInput(
                    "archimate",
                    nodes,
                    JsonSupport.objectMapper().createArrayNode(),
                    metadataNodes,
                    JsonSupport.objectMapper().createObjectNode(),
                    policy)));
            assertThat(writeRenderArtifact("svg_renderer_covers_each_archimate_node_type", content)).exists();
            Document document = svgDocument(content);

            index = 0;
            for (var fields = nodeStyles.fields(); fields.hasNext(); ) {
                var field = fields.next();
                String id = "archimate-node-" + index;
                String decoratorKind = field.getValue().at("/decorator").asText(); // navigation only
                Element node = groupWithAttribute(document, "data-dediren-node-id", id);
                if (!"archimate_and_junction".equals(decoratorKind)
                        && !"archimate_or_junction".equals(decoratorKind)) {
                    Element decorator = childGroupWithAttribute(node, "data-dediren-node-decorator", decoratorKind);
                    String expectedKind = expectedArchimateIconKind(field.getKey()); // requirement-derived, not echoed
                    assertThat(decorator.getAttribute("data-dediren-icon-kind")).isEqualTo(expectedKind);
                    assertDistinctArchimateIconMorphology(field.getKey(), expectedKind, decorator);
                }
                index++;
            }
        }

        @Test
        void rendersDetailedArchimateIconMorphology() throws Exception {
            JsonNode input = archimateRenderInput(
                    fixtureJson("fixtures/render-policy/archimate-svg.json"),
                    """
                    [
                      { "id": "component", "source_id": "component", "projection_id": "component", "x": 40, "y": 40, "width": 160, "height": 80, "label": "Component" },
                      { "id": "actor", "source_id": "actor", "projection_id": "actor", "x": 240, "y": 40, "width": 160, "height": 80, "label": "Actor" },
                      { "id": "data", "source_id": "data", "projection_id": "data", "x": 440, "y": 40, "width": 160, "height": 80, "label": "Data" },
                      { "id": "node", "source_id": "node", "projection_id": "node", "x": 640, "y": 40, "width": 160, "height": 80, "label": "Node" }
                    ]
                    """,
                    "[]",
                    """
                    {
                      "component": { "type": "ApplicationComponent", "source_id": "component" },
                      "actor": { "type": "BusinessActor", "source_id": "actor" },
                      "data": { "type": "DataObject", "source_id": "data" },
                      "node": { "type": "Node", "source_id": "node" }
                    }
                    """,
                    "{}");
            Document document = svgDocument(okContent(render(input)));

            Element component = childGroupWithAttribute(
                    groupWithAttribute(document, "data-dediren-node-id", "component"),
                    "data-dediren-node-decorator",
                    "archimate_application_component");
            assertThat(childElements(component, "rect")).hasSize(3);

            Element actor = childGroupWithAttribute(
                    groupWithAttribute(document, "data-dediren-node-id", "actor"),
                    "data-dediren-node-decorator",
                    "archimate_business_actor");
            assertThat(childElements(actor, "ellipse")).hasSize(1);
            assertThat(firstChildElement(actor, "path").getAttribute("stroke-linecap")).isEqualTo("round");

            Element data = childGroupWithAttribute(
                    groupWithAttribute(document, "data-dediren-node-id", "data"),
                    "data-dediren-node-decorator",
                    "archimate_data_object");
            assertThat(childElements(data, "path").stream().map(path -> path.getAttribute("data-dediren-icon-part")))
                    .contains("document-body", "document-header");

            Element node = childGroupWithAttribute(
                    groupWithAttribute(document, "data-dediren-node-id", "node"),
                    "data-dediren-node-decorator",
                    "archimate_technology_node");
            assertThat(firstElementWithAttribute(node, "data-dediren-icon-part").getAttribute("data-dediren-icon-part"))
                    .isEqualTo("node-3d-edges");
        }

        @Test
        void coversEachUmlNodeTypeFromPolicy() throws Exception {
            JsonNode policy = fixtureJson("fixtures/render-policy/uml-svg.json");
            ObjectNode nodeStyles = (ObjectNode) policy.at("/style/node_type_overrides");
            ArrayNode nodes = JsonSupport.objectMapper().createArrayNode();
            ObjectNode metadataNodes = JsonSupport.objectMapper().createObjectNode();
            int index = 0;
            for (var fields = nodeStyles.fields(); fields.hasNext(); ) {
                var field = fields.next();
                String nodeType = field.getKey();
                if (isSequenceNodeType(nodeType)) {
                    continue;
                }
                String id = "uml-node-" + index;
                nodes.add(JsonSupport.objectMapper().readTree("""
                        {
                          "id": "%s",
                          "source_id": "%s",
                          "projection_id": "%s",
                          "x": %d,
                          "y": %d,
                          "width": 180,
                          "height": 96,
                          "label": "%s"
                        }
                        """.formatted(id, id, id, 40 + (index % 4) * 220, 40 + (index / 4) * 140, nodeType)));
                metadataNodes.set(id, JsonSupport.objectMapper().readTree("""
                        {
                          "type": "%s",
                          "source_id": "%s"
                        }
                        """.formatted(nodeType, id)));
                index++;
            }

            String content = okContent(render(semanticRenderInput(
                    "uml",
                    nodes,
                    JsonSupport.objectMapper().createArrayNode(),
                    metadataNodes,
                    JsonSupport.objectMapper().createObjectNode(),
                    policy)));
            assertThat(writeRenderArtifact("svg_renderer_covers_each_uml_node_type", content)).exists();
            Document document = svgDocument(content);

            index = 0;
            for (var fields = nodeStyles.fields(); fields.hasNext(); ) {
                var field = fields.next();
                String id = "uml-node-" + index;
                if (isSequenceNodeType(field.getKey())) {
                    continue;
                }
                Element node = groupWithAttribute(document, "data-dediren-node-id", id);
                Element shape = firstElementWithAttribute(node, "data-dediren-node-shape");
                assertThat(shape.getAttribute("data-dediren-node-shape"))
                        .isEqualTo(EXPECTED_UML_NODE_SHAPES.get(field.getKey()));
                childGroupWithAttribute(node, "data-dediren-node-decorator", EXPECTED_UML_NODE_SHAPES.get(field.getKey()));
                index++;
            }
            assertThat(content).contains("&#171;interface&#187;", "&#171;dataType&#187;", "&#171;enumeration&#187;");
        }

        @Test
        void rendersUmlSequenceDiagramLayers() throws Exception {
            String content = okContent(render(umlSequenceStyleInput()));
            Document document = svgDocument(content);

            assertThat(content).contains(">Place Order<", ">Customer<", ">Order Service<", ">Receipt<");

            Element customerStem = elementWithAttribute(
                    document,
                    "line",
                    "data-dediren-sequence-lifeline-stem",
                    "customer");
            Element serviceStem = elementWithAttribute(
                    document,
                    "line",
                    "data-dediren-sequence-lifeline-stem",
                    "service");
            Element receiptStem = elementWithAttribute(
                    document,
                    "line",
                    "data-dediren-sequence-lifeline-stem",
                    "receipt");
            assertThat(customerStem.getAttribute("stroke-dasharray")).isEqualTo("8 5");
            assertThat(serviceStem.getAttribute("stroke-dasharray")).isEqualTo("8 5");
            assertThat(receiptStem.getAttribute("stroke-dasharray")).isEqualTo("8 5");

            assertThat(edgeLabelsInDomOrder(document))
                    .containsExactly("placeOrder", "accepted", "receiptReady", "createReceipt", "cancelOrder");

            Element reply = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "m2"), "path");
            assertThat(reply.getAttribute("stroke-dasharray")).isEqualTo("8 5");
            assertMarkerForStyle(document, reply, "m2", "end", "open_arrow");

            Element synchCall = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "m1"), "path");
            assertMarkerForStyle(document, synchCall, "m1", "end", "filled_arrow");

            Element asyncSignal = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "m3"), "path");
            assertMarkerForStyle(document, asyncSignal, "m3", "end", "open_arrow");

            Element createMessage = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "m4"), "path");
            assertThat(createMessage.hasAttribute("stroke-dasharray")).isFalse();
            assertThat(createMessage.getAttribute("d")).contains("L 600.0 96.0");
            assertMarkerForStyle(document, createMessage, "m4", "end", "open_arrow");

            Element deleteMarker = groupWithAttribute(
                    document,
                    "data-dediren-sequence-delete-marker",
                    "service-destroyed");
            assertThat(childElements(deleteMarker, "line")).hasSize(2);
        }

        @Test
        void rendersUmlSequenceCombinedFragments() throws Exception {
            ObjectNode input = (ObjectNode) renderInput(
                    "fixtures/layout-result/uml-sequence-fragments.json",
                    "fixtures/render-policy/uml-svg.json");
            input.set("render_metadata", fixtureJson("fixtures/render-metadata/uml-sequence-fragments.json"));

            Document document = svgDocument(okContent(render(input)));

            assertThat(combinedFragmentIds(document)).containsExactly(
                    "cf-availability",
                    "cf-coupon",
                    "cf-retry",
                    "cf-parallel-closeout");
            assertCombinedFragment(document, "cf-availability", "alt");
            assertCombinedFragment(document, "cf-coupon", "opt");
            assertCombinedFragment(document, "cf-retry", "loop");
            assertCombinedFragment(document, "cf-parallel-closeout", "par");
            assertOperandGuard(document, "cf-availability", "op-in-stock", "inStock");
            assertOperandGuard(document, "cf-availability", "op-backorder", "else");
            assertOperandSeparator(document, "cf-availability", "op-backorder");
            assertOperandGuard(document, "cf-coupon", "op-coupon", "couponPresent");
            assertOperandGuard(document, "cf-retry", "op-retry", "whilePaymentPending");
            assertOperandGuard(document, "cf-parallel-closeout", "op-charge", "charge");
            assertOperandGuard(document, "cf-parallel-closeout", "op-confirm", "confirm");
            assertOperandSeparator(document, "cf-parallel-closeout", "op-confirm");

            ObjectNode reorderedInput = reorderUmlSequenceFragmentMetadataNodes(umlSequenceFragmentsStyleInput());
            Document reorderedDocument = svgDocument(okContent(render(reorderedInput)));
            assertThat(combinedFragmentStructures(reorderedDocument))
                    .containsExactlyElementsOf(combinedFragmentStructures(document));
        }

        @Test
        void rendersNestedUmlSequenceCombinedFragmentsInsideOwnerFrame() throws Exception {
            ObjectNode input = (ObjectNode) umlSequenceFragmentsStyleInput();
            ((ArrayNode) input.at("/render_metadata/nodes/op-backorder/properties/fragments"))
                    .add("cf-coupon");

            Document document = svgDocument(okContent(render(input)));

            assertCombinedFragment(document, "cf-coupon", "opt");
            assertCombinedFragmentContainedWithin(document, "cf-coupon", "cf-availability");
        }

        @Test
        void defaultsOmittedUmlSequenceMessageSortToSynchronousCall() throws Exception {
            JsonNode input = umlSequenceStyleInput();
            ((ObjectNode) input.at("/render_metadata/edges/m1/properties")).remove("message_sort");

            Document document = svgDocument(okContent(render(input)));

            Element message = groupWithAttribute(document, "data-dediren-edge-id", "m1");
            assertThat(message.getAttribute("data-dediren-sequence-message-sort")).isEqualTo("synchCall");
            Element path = firstChildElement(message, "path");
            assertThat(path.hasAttribute("stroke-dasharray")).isFalse();
            assertMarkerForStyle(document, path, "m1", "end", "filled_arrow");
        }

        @Test
        void ordersUmlSequenceMessagesWithLargeIntegralSequence() throws Exception {
            JsonNode input = umlSequenceStyleInput();
            ((ObjectNode) input.at("/render_metadata/edges/m1/properties"))
                    .set("sequence", JsonSupport.objectMapper().getNodeFactory()
                            .numberNode(new java.math.BigInteger("9223372036854775808")));

            Document document = svgDocument(okContent(render(input)));

            assertThat(edgeLabelsInDomOrder(document))
                    .containsExactly("accepted", "receiptReady", "createReceipt", "cancelOrder", "placeOrder");
        }

        @Test
        void coversEachRelationshipTypeFromPolicies() throws Exception {
            assertRelationshipPolicyCoverage("archimate", "fixtures/render-policy/archimate-svg.json");
            assertRelationshipPolicyCoverage("uml", "fixtures/render-policy/uml-svg.json");
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
        void paintsEdgeLabelWithDefaultOutline() throws Exception {
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

            Element edge = groupWithAttribute(document, "data-dediren-edge-id", "labeled-edge");
            java.util.List<Element> labels = childElements(edge, "text");

            assertThat(labels).hasSize(2);
            Element outline = labels.get(0);
            Element label = labels.get(1);

            assertThat(childElements(edge, "rect")).isEmpty();
            assertThat(outline.getTextContent()).isEqualTo("clear label");
            assertThat(outline.getAttribute("fill")).isEqualTo("none");
            assertThat(outline.getAttribute("stroke")).isEqualTo("#f8fafc");
            assertThat(outline.getAttribute("stroke-width")).isEqualTo("2");
            assertThat(label.getTextContent()).isEqualTo("clear label");
            assertThat(label.getAttribute("fill")).isEqualTo("#374151");
            assertThat(label.getAttribute("paint-order")).isEmpty();
            assertThat(label.getAttribute("stroke")).isEmpty();
            assertThat(label.getAttribute("font-size")).isEqualTo("15.4");
            assertThat(label.getAttribute("font-weight")).isEqualTo("600");
        }

        @Test
        void paintsEdgeLabelOnReadableBackgroundWhenOptedIn() throws Exception {
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
                    "{ \"background\": { \"fill\": \"#f8fafc\" }, \"edge\": { \"label_presentation\": \"background\" } }");
            Document document = svgDocument(okContent(render(input)));

            Element edge = groupWithAttribute(document, "data-dediren-edge-id", "labeled-edge");
            Element background = firstElementWithAttribute(edge, "data-dediren-edge-label-background");
            Element label = firstChildElement(edge, "text");

            assertThat(background.getTagName()).isEqualTo("rect");
            assertThat(background.getAttribute("fill")).isEqualTo("#f8fafc");
            assertThat(background.getAttribute("rx")).isEqualTo("3");
            assertThat(Double.parseDouble(background.getAttribute("x")))
                    .isLessThan(Double.parseDouble(label.getAttribute("x")));
            assertThat(label.getAttribute("paint-order")).isEmpty();
            assertThat(label.getAttribute("stroke")).isEmpty();
            assertThat(label.getAttribute("font-size")).isEqualTo("15.4");
            assertThat(label.getAttribute("font-weight")).isEqualTo("600");
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

            // Contract: the earlier edge does not jump; the later crossing edge gains a
            // quadratic jump arc; the jump carries a white mask. Coordinate-free oracle.
            assertThat(firstPath.getAttribute("d")).doesNotContain(" Q ");
            assertThat(frontPath.getAttribute("d")).contains(" Q ");
            Element masks = childGroupWithAttribute(frontEdge, "data-dediren-line-jump-masks", "front-edge");
            assertThat(firstChildElement(masks, "path").getAttribute("stroke")).isEqualTo("#ffffff");
        }

        @Test
        void keepsRoundedRouteCornersWhenAddingLineJumps() throws Exception {
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
                          { "x": 0, "y": 40 },
                          { "x": 220, "y": 40 }
                        ],
                        "label": "first"
                      },
                      {
                        "id": "front-edge",
                        "source": "top",
                        "target": "side",
                        "source_id": "front-edge",
                        "projection_id": "front-edge",
                        "points": [
                          { "x": 100, "y": 0 },
                          { "x": 100, "y": 100 },
                          { "x": 180, "y": 100 }
                        ],
                        "label": "front"
                      }
                    ]
                    """,
                    "{}");
            Document document = svgDocument(okContent(render(input)));

            Element frontEdge = groupWithAttribute(document, "data-dediren-edge-id", "front-edge");
            Element frontPath = firstChildElement(frontEdge, "path");

            String frontD = frontPath.getAttribute("d");
            // The L-shaped route contributes one rounded corner (a Q segment) and the
            // crossing contributes at least one jump arc (another Q), so a route that lost
            // its rounding or its jump would fall below 2 quadratic segments.
            int quadraticSegments = frontD.split(" Q ", -1).length - 1;
            assertThat(quadraticSegments).isGreaterThanOrEqualTo(2);
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

        @Test
        void placesSharedSourceJunctionLabelOnUniqueBranch() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    "[]",
                    """
                    [
                      {
                        "id": "gateway-prices-cart",
                        "source": "api-gateway",
                        "target": "pricing-service",
                        "source_id": "gateway-prices-cart",
                        "projection_id": "gateway-prices-cart",
                        "routing_hints": ["shared_source_junction"],
                        "points": [
                          { "x": 0, "y": 120 },
                          { "x": 100, "y": 120 },
                          { "x": 100, "y": 40 },
                          { "x": 300, "y": 40 }
                        ],
                        "label": "prices cart"
                      }
                    ]
                    """,
                    "{ \"edge\": { \"label_horizontal_position\": \"center\" } }");
            Document document = svgDocument(okContent(render(input)));

            Element label = firstChildElement(
                    groupWithAttribute(document, "data-dediren-edge-id", "gateway-prices-cart"),
                    "text");

            assertThat(Double.parseDouble(label.getAttribute("x"))).isCloseTo(200.0, org.assertj.core.data.Offset.offset(1.0));
            assertThat(Double.parseDouble(label.getAttribute("y"))).isCloseTo(40.0, org.assertj.core.data.Offset.offset(18.0));
        }

        @Test
        void roundsOrthogonalRouteCorners() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    "[]",
                    """
                    [
                      {
                        "id": "stepped-edge",
                        "source": "left",
                        "target": "right",
                        "source_id": "stepped-edge",
                        "projection_id": "stepped-edge",
                        "points": [
                          { "x": 0, "y": 100 },
                          { "x": 80, "y": 100 },
                          { "x": 80, "y": 105 },
                          { "x": 160, "y": 105 }
                        ],
                        "label": "step"
                      }
                    ]
                    """,
                    "{}");
            Document document = svgDocument(okContent(render(input)));

            Element path = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "stepped-edge"), "path");

            assertThat(path.getAttribute("stroke-linecap")).isEqualTo("round");
            assertThat(path.getAttribute("stroke-linejoin")).isEqualTo("round");
            assertThat(path.getAttribute("d")).contains(" Q ");
            assertThat(path.getAttribute("d")).startsWith("M 0.0 100.0");
            assertThat(path.getAttribute("d")).endsWith("L 160.0 105.0");
        }

        @Test
        void suppressesLineJumpBetweenSharedJunctionEdges() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    "[]",
                    """
                    [
                      {
                        "id": "first-edge",
                        "source": "shared-source",
                        "target": "top-target",
                        "source_id": "first-edge",
                        "projection_id": "first-edge",
                        "points": [
                          { "x": 0, "y": 100 },
                          { "x": 80, "y": 100 },
                          { "x": 80, "y": 40 }
                        ],
                        "label": "first"
                      },
                      {
                        "id": "merged-edge",
                        "source": "shared-source",
                        "target": "bottom-target",
                        "source_id": "merged-edge",
                        "projection_id": "merged-edge",
                        "routing_hints": ["shared_source_junction"],
                        "points": [
                          { "x": 0, "y": 90 },
                          { "x": 120, "y": 90 }
                        ],
                        "label": "merged"
                      }
                    ]
                    """,
                    "{}");
            Document document = svgDocument(okContent(render(input)));

            Element path = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "merged-edge"), "path");

            assertThat(path.getAttribute("d")).doesNotContain(" Q ");
        }

        @Test
        void expandsViewboxToIncludeEdgeLabels() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    """
                    [
                      { "id": "left-node", "source_id": "left-node", "projection_id": "left-node", "x": 12, "y": 32, "width": 160, "height": 80, "label": "Left" },
                      { "id": "right-node", "source_id": "right-node", "projection_id": "right-node", "x": 212, "y": 32, "width": 160, "height": 80, "label": "Right" }
                    ]
                    """,
                    """
                    [
                      {
                        "id": "left-to-right",
                        "source": "left-node",
                        "target": "right-node",
                        "source_id": "left-to-right",
                        "projection_id": "left-to-right",
                        "points": [
                          { "x": 172, "y": 72 },
                          { "x": 212, "y": 72 }
                        ],
                        "label": "very long clipped edge label"
                      }
                    ]
                    """,
                    "{}");
            Document document = svgDocument(okContent(render(input)));

            String viewBox = document.getDocumentElement().getAttribute("viewBox");
            String[] parts = viewBox.split("\\s+");
            double minX = Double.parseDouble(parts[0]);
            double minY = Double.parseDouble(parts[1]);
            double width = Double.parseDouble(parts[2]);
            double height = Double.parseDouble(parts[3]);
            Element label = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "left-to-right"), "text");
            double labelY = Double.parseDouble(label.getAttribute("y"));

            assertThat(minX).isLessThan(0.0);
            assertThat(minY).isLessThanOrEqualTo(16.0);
            assertThat(width).isGreaterThanOrEqualTo(380.0);
            assertThat(height).isGreaterThanOrEqualTo(100.0);
            assertThat(minY + height).isGreaterThanOrEqualTo(labelY + 8.0);
        }

        @Test
        void wrapsNodeLabelsToFitInsideNodeWidth() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    """
                    [
                      {
                        "id": "payment-node",
                        "source_id": "payment-node",
                        "projection_id": "payment-node",
                        "x": 80,
                        "y": 64,
                        "width": 100,
                        "height": 68,
                        "label": "PaymentAuthorizationProcess"
                      }
                    ]
                    """,
                    "[]",
                    "{}");
            Document document = svgDocument(okContent(render(input)));

            Element label = firstChildElement(
                    groupWithAttribute(document, "data-dediren-node-id", "payment-node"),
                    "text");
            java.util.List<String> lines = textLinesFromSvg(label);
            double fontSize = svgFontSize(label);

            assertThat(lines).containsExactly("Payment", "Authorization", "Process");
            assertThat(lines)
                    .allSatisfy(line -> assertThat(estimatedSvgTextWidth(line, fontSize))
                            .isLessThanOrEqualTo(80.0));
        }

        @Test
        void positionsCompactUmlControlNodeLabelsOutsideTheShape() throws Exception {
            JsonNode policy = fixtureJson("fixtures/render-policy/uml-svg.json");
            ArrayNode nodes = JsonSupport.objectMapper().createArrayNode();
            nodes.add(JsonSupport.objectMapper().readTree("""
                    {
                      "id": "decision",
                      "source_id": "decision",
                      "projection_id": "decision",
                      "x": 120,
                      "y": 120,
                      "width": 32,
                      "height": 32,
                      "label": "StockAvailableDecision"
                    }
                    """));
            ObjectNode metadataNodes = JsonSupport.objectMapper().createObjectNode();
            metadataNodes.set("decision", JsonSupport.objectMapper().readTree("""
                    {
                      "type": "DecisionNode",
                      "source_id": "decision"
                    }
                    """));
            Document document = svgDocument(okContent(render(semanticRenderInput(
                    "uml",
                    nodes,
                    JsonSupport.objectMapper().createArrayNode(),
                    metadataNodes,
                    JsonSupport.objectMapper().createObjectNode(),
                    policy))));

            Element label = firstChildElement(
                    groupWithAttribute(document, "data-dediren-node-id", "decision"),
                    "text");

            assertThat(Double.parseDouble(label.getAttribute("x"))).isLessThan(120.0);
            assertThat(Double.parseDouble(label.getAttribute("y"))).isLessThan(120.0);
        }

        @Test
        void keepsLargeUmlControlNodeLabelsNearTheShape() throws Exception {
            JsonNode policy = fixtureJson("fixtures/render-policy/uml-svg.json");
            ArrayNode nodes = JsonSupport.objectMapper().createArrayNode();
            nodes.add(JsonSupport.objectMapper().readTree("""
                    {
                      "id": "decision",
                      "source_id": "decision",
                      "projection_id": "decision",
                      "x": 260,
                      "y": 320,
                      "width": 180,
                      "height": 96,
                      "label": "DecisionNode"
                    }
                    """));
            nodes.add(JsonSupport.objectMapper().readTree("""
                    {
                      "id": "activity-final",
                      "source_id": "activity-final",
                      "projection_id": "activity-final",
                      "x": 40,
                      "y": 320,
                      "width": 180,
                      "height": 96,
                      "label": "ActivityFinalNode"
                    }
                    """));
            ObjectNode metadataNodes = JsonSupport.objectMapper().createObjectNode();
            metadataNodes.set("decision", JsonSupport.objectMapper().readTree("""
                    {
                      "type": "DecisionNode",
                      "source_id": "decision"
                    }
                    """));
            metadataNodes.set("activity-final", JsonSupport.objectMapper().readTree("""
                    {
                      "type": "ActivityFinalNode",
                      "source_id": "activity-final"
                    }
                    """));
            Document document = svgDocument(okContent(render(semanticRenderInput(
                    "uml",
                    nodes,
                    JsonSupport.objectMapper().createArrayNode(),
                    metadataNodes,
                    JsonSupport.objectMapper().createObjectNode(),
                    policy))));

            Element decisionLabel = firstChildElement(
                    groupWithAttribute(document, "data-dediren-node-id", "decision"),
                    "text");
            Element finalLabel = firstChildElement(
                    groupWithAttribute(document, "data-dediren-node-id", "activity-final"),
                    "text");

            assertThat(Double.parseDouble(decisionLabel.getAttribute("x"))).isBetween(256.0, 350.0);
            assertThat(Double.parseDouble(decisionLabel.getAttribute("y"))).isBetween(296.0, 368.0);
            assertThat(Double.parseDouble(finalLabel.getAttribute("x"))).isBetween(36.0, 130.0);
            assertThat(Double.parseDouble(finalLabel.getAttribute("y"))).isBetween(296.0, 368.0);
        }

        @Test
        void separatesLabelsForParallelHorizontalEdges() throws Exception {
            JsonNode input = styledInlineInput(
                    "[]",
                    "[]",
                    """
                    [
                      {
                        "id": "upper-edge",
                        "source": "orders-api",
                        "target": "payments",
                        "source_id": "upper-edge",
                        "projection_id": "upper-edge",
                        "points": [
                          { "x": 100, "y": 160 },
                          { "x": 320, "y": 160 }
                        ],
                        "label": "writes orders"
                      },
                      {
                        "id": "lower-edge",
                        "source": "orders-api",
                        "target": "database",
                        "source_id": "lower-edge",
                        "projection_id": "lower-edge",
                        "points": [
                          { "x": 100, "y": 172 },
                          { "x": 320, "y": 172 }
                        ],
                        "label": "requests payment authorization"
                      }
                    ]
                    """,
                    "{}");
            Document document = svgDocument(okContent(render(input)));

            Element upper = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "upper-edge"), "text");
            Element lower = firstChildElement(groupWithAttribute(document, "data-dediren-edge-id", "lower-edge"), "text");

            assertThat(textBoxesOverlap(upper, lower)).isFalse();
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
        return okData(result).at("/artifacts/0/content").asText();
    }

    private static JsonNode error(PluginResult result, String expectedCode) throws Exception {
        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
        assertThat(result.exitCode()).describedAs(result.stderr()).isNotZero();
        assertThat(envelope.at("/status").asText()).describedAs(result.stdout()).isEqualTo("error");
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo(expectedCode);
        return envelope;
    }

    private static void assertInvalidUmlSequenceMessageMetadata(
            Consumer<ObjectNode> mutation,
            String expectedPath) throws Exception {
        JsonNode input = umlSequenceStyleInput();
        ObjectNode properties = (ObjectNode) input.at("/render_metadata/edges/m1/properties");
        mutation.accept(properties);

        JsonNode envelope = error(render(input), "DEDIREN_UML_MESSAGE_METADATA_INVALID");

        assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo(expectedPath);
    }

    private static void assertInvalidUmlCombinedFragmentMetadata(
            Consumer<ObjectNode> mutation,
            String expectedPath) throws Exception {
        JsonNode input = umlSequenceFragmentsStyleInput();
        ObjectNode properties = (ObjectNode) input.at("/render_metadata/nodes/cf-availability/properties");
        mutation.accept(properties);

        JsonNode envelope = error(render(input), "DEDIREN_UML_COMBINED_FRAGMENT_METADATA_INVALID");

        assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo(expectedPath);
    }

    private static void assertInvalidUmlInteractionOperandMetadata(
            Consumer<ObjectNode> mutation,
            String expectedPath) throws Exception {
        JsonNode input = umlSequenceFragmentsStyleInput();
        ObjectNode properties = (ObjectNode) input.at("/render_metadata/nodes/op-in-stock/properties");
        mutation.accept(properties);

        JsonNode envelope = error(render(input), "DEDIREN_UML_INTERACTION_OPERAND_METADATA_INVALID");

        assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo(expectedPath);
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

    private static JsonNode umlSequenceStyleInput() throws Exception {
        ObjectNode input = JsonSupport.objectMapper().createObjectNode();
        input.set("layout_result", fixtureJson("fixtures/layout-result/uml-sequence-basic.json"));
        input.set("render_metadata", fixtureJson("fixtures/render-metadata/uml-sequence-basic.json"));
        input.set("policy", fixtureJson("fixtures/render-policy/uml-svg.json"));
        return input;
    }

    private static JsonNode umlSequenceFragmentsStyleInput() throws Exception {
        ObjectNode input = JsonSupport.objectMapper().createObjectNode();
        input.set("layout_result", fixtureJson("fixtures/layout-result/uml-sequence-fragments.json"));
        input.set("render_metadata", fixtureJson("fixtures/render-metadata/uml-sequence-fragments.json"));
        input.set("policy", fixtureJson("fixtures/render-policy/uml-svg.json"));
        return input;
    }

    private static ObjectNode reorderUmlSequenceFragmentMetadataNodes(JsonNode input) {
        ObjectNode object = (ObjectNode) input;
        ObjectNode metadata = (ObjectNode) object.at("/render_metadata");
        ObjectNode nodes = (ObjectNode) metadata.get("nodes");
        var entries = new java.util.ArrayList<Map.Entry<String, JsonNode>>();
        nodes.fields().forEachRemaining(entries::add);
        java.util.Collections.reverse(entries);

        ObjectNode reordered = JsonSupport.objectMapper().createObjectNode();
        for (Map.Entry<String, JsonNode> entry : entries) {
            reordered.set(entry.getKey(), entry.getValue());
        }
        metadata.set("nodes", reordered);
        return object;
    }

    // Frozen once from fixtures/render-policy/uml-svg.json (node_type_overrides[*].decorator).
    // Pinning here (not re-reading the live file) is the contract: an intentional policy change updates this map.
    private static final java.util.Map<String, String> EXPECTED_UML_NODE_SHAPES = java.util.Map.ofEntries(
            java.util.Map.entry("Package", "uml_package"),
            java.util.Map.entry("Class", "uml_class"),
            java.util.Map.entry("Interface", "uml_interface"),
            java.util.Map.entry("DataType", "uml_data_type"),
            java.util.Map.entry("Enumeration", "uml_enumeration"),
            java.util.Map.entry("Activity", "uml_activity"),
            java.util.Map.entry("Action", "uml_action"),
            java.util.Map.entry("InitialNode", "uml_initial_node"),
            java.util.Map.entry("ActivityFinalNode", "uml_activity_final_node"),
            java.util.Map.entry("DecisionNode", "uml_decision_node"),
            java.util.Map.entry("MergeNode", "uml_merge_node"),
            java.util.Map.entry("ForkNode", "uml_fork_node"),
            java.util.Map.entry("JoinNode", "uml_join_node"),
            java.util.Map.entry("ObjectNode", "uml_object_node"),
            java.util.Map.entry("State", "uml_state"),
            java.util.Map.entry("FinalState", "uml_final_state"),
            java.util.Map.entry("Pseudostate", "uml_pseudostate"),
            java.util.Map.entry("Actor", "uml_actor"),
            java.util.Map.entry("UseCase", "uml_use_case"),
            java.util.Map.entry("ExtensionPoint", "uml_extension_point"),
            java.util.Map.entry("Component", "uml_component"),
            java.util.Map.entry("Port", "uml_port"),
            java.util.Map.entry("Node", "uml_node"),
            java.util.Map.entry("Device", "uml_device"),
            java.util.Map.entry("ExecutionEnvironment", "uml_execution_environment"),
            java.util.Map.entry("Artifact", "uml_artifact"),
            java.util.Map.entry("DeploymentSpecification", "uml_deployment_specification")
    );

    private static boolean isSequenceNodeType(String type) {
        return type.equals("Interaction")
                || type.equals("Lifeline")
                || type.equals("ExecutionSpecification")
                || type.equals("Gate")
                || type.equals("DestructionOccurrenceSpecification")
                || type.equals("CombinedFragment")
                || type.equals("InteractionOperand");
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

    private static JsonNode semanticRenderInput(
            String semanticProfile,
            ArrayNode nodes,
            ArrayNode edges,
            ObjectNode metadataNodes,
            ObjectNode metadataEdges,
            JsonNode policy) {
        ObjectNode layout = JsonSupport.objectMapper().createObjectNode();
        layout.put("layout_result_schema_version", "layout-result.schema.v1");
        layout.put("view_id", semanticProfile + "-coverage");
        layout.set("nodes", nodes);
        layout.set("edges", edges);
        layout.set("groups", JsonSupport.objectMapper().createArrayNode());
        layout.set("warnings", JsonSupport.objectMapper().createArrayNode());

        ObjectNode metadata = JsonSupport.objectMapper().createObjectNode();
        metadata.put("render_metadata_schema_version", "render-metadata.schema.v1");
        metadata.put("semantic_profile", semanticProfile);
        metadata.set("nodes", metadataNodes);
        metadata.set("edges", metadataEdges);

        ObjectNode input = JsonSupport.objectMapper().createObjectNode();
        input.set("layout_result", layout);
        input.set("render_metadata", metadata);
        input.set("policy", policy);
        return input;
    }

    private static void assertRelationshipPolicyCoverage(String semanticProfile, String policyPath) throws Exception {
        JsonNode policy = fixtureJson(policyPath);
        ObjectNode edgeStyles = (ObjectNode) policy.at("/style/edge_type_overrides");
        ArrayNode nodes = JsonSupport.objectMapper().createArrayNode();
        ArrayNode edges = JsonSupport.objectMapper().createArrayNode();
        ObjectNode metadataEdges = JsonSupport.objectMapper().createObjectNode();
        int index = 0;
        for (var fields = edgeStyles.fields(); fields.hasNext(); ) {
            String relationshipType = fields.next().getKey();
            if (isSequenceRelationshipType(semanticProfile, relationshipType)) {
                continue;
            }
            String id = semanticProfile + "-relationship-" + index;
            int y = 60 + index * 48;
            nodes.add(JsonSupport.objectMapper().readTree("""
                    {
                      "id": "source-%d",
                      "source_id": "source-%d",
                      "projection_id": "source-%d",
                      "x": 40,
                      "y": %d,
                      "width": 80,
                      "height": 32,
                      "label": "Source"
                    }
                    """.formatted(index, index, index, y - 16)));
            nodes.add(JsonSupport.objectMapper().readTree("""
                    {
                      "id": "target-%d",
                      "source_id": "target-%d",
                      "projection_id": "target-%d",
                      "x": 260,
                      "y": %d,
                      "width": 80,
                      "height": 32,
                      "label": "Target"
                    }
                    """.formatted(index, index, index, y - 16)));
            edges.add(JsonSupport.objectMapper().readTree("""
                    {
                      "id": "%s",
                      "source": "source-%d",
                      "target": "target-%d",
                      "source_id": "%s",
                      "projection_id": "%s",
                      "points": [
                        { "x": 120, "y": %d },
                        { "x": 260, "y": %d }
                      ],
                      "label": "%s"
                    }
                    """.formatted(id, index, index, id, id, y, y, relationshipType)));
            metadataEdges.set(id, JsonSupport.objectMapper().readTree("""
                    {
                      "type": "%s",
                      "source_id": "%s"
                    }
                    """.formatted(relationshipType, id)));
            index++;
        }

        String content = okContent(render(semanticRenderInput(
                semanticProfile,
                nodes,
                edges,
                JsonSupport.objectMapper().createObjectNode(),
                metadataEdges,
                policy)));
        assertThat(writeRenderArtifact(
                "svg_renderer_covers_each_" + semanticProfile + "_relationship_type",
                content)).exists();
        Document document = svgDocument(content);

        index = 0;
        for (var fields = edgeStyles.fields(); fields.hasNext(); ) {
            var field = fields.next();
            if (isSequenceRelationshipType(semanticProfile, field.getKey())) {
                continue;
            }
            String id = semanticProfile + "-relationship-" + index;
            JsonNode style = field.getValue();
            groupWithAttribute(document, "data-dediren-node-id", "source-" + index);
            groupWithAttribute(document, "data-dediren-node-id", "target-" + index);
            Element edge = groupWithAttribute(document, "data-dediren-edge-id", id);
            Element path = firstChildElement(edge, "path");
            assertMarkerForStyle(document, path, id, "start", style.at("/marker_start").asText("none"));
            assertMarkerForStyle(document, path, id, "end", style.at("/marker_end").asText("filled_arrow"));
            if ("dashed".equals(style.at("/line_style").asText())) {
                assertThat(path.getAttribute("stroke-dasharray")).isEqualTo("8 5");
            }
            index++;
        }
    }

    private static boolean isSequenceRelationshipType(String semanticProfile, String relationshipType) {
        return "uml".equals(semanticProfile) && "Message".equals(relationshipType);
    }

    private static void assertMarkerForStyle(
            Document document,
            Element path,
            String edgeId,
            String side,
            String markerName) {
        String attributeName = "marker-" + side;
        if ("none".equals(markerName)) {
            assertThat(path.hasAttribute(attributeName)).isFalse();
            return;
        }
        String markerId = "marker-" + side + "-" + edgeId;
        assertThat(path.getAttribute(attributeName)).isEqualTo("url(#" + markerId + ")");
        Element marker = marker(document, markerId, "data-dediren-edge-marker-" + side);
        assertThat(marker.getAttribute("data-dediren-edge-marker-" + side)).isEqualTo(markerName);
    }

    private static JsonNode renderInput(String layoutPath, String policyPath) throws Exception {
        ObjectNode input = JsonSupport.objectMapper().createObjectNode();
        input.set("layout_result", fixtureJson(layoutPath));
        input.set("policy", fixtureJson(policyPath));
        return input;
    }

    private static JsonNode renderInput(String layoutPath, String policyPath, String metadataPath) throws Exception {
        ObjectNode input = (ObjectNode) renderInput(layoutPath, policyPath);
        input.set("render_metadata", fixtureJson(metadataPath));
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

    private static void assertCombinedFragment(Document document, String id, String operator) {
        Element fragment = groupWithAttribute(document, "data-dediren-sequence-combined-fragment", id);
        assertThat(fragment.getAttribute("data-dediren-sequence-interaction-operator")).isEqualTo(operator);
        Element shape = childElementWithAttribute(
                fragment,
                "rect",
                "data-dediren-node-shape",
                "uml_combined_fragment");
        assertThat(shape.getAttribute("data-dediren-node-shape")).isEqualTo("uml_combined_fragment");
        childElementWithAttribute(fragment, "path", "data-dediren-sequence-fragment-operator-tab", "true");
        Element operatorText = childElementWithAttribute(
                fragment,
                "text",
                "data-dediren-sequence-fragment-operator",
                id);
        assertThat(operatorText.getTextContent()).isEqualTo(operator);
    }

    private static void assertOperandGuard(
            Document document,
            String fragmentId,
            String operandId,
            String guard) {
        Element fragment = groupWithAttribute(
                document,
                "data-dediren-sequence-combined-fragment",
                fragmentId);
        Element guardText = childElementWithAttribute(fragment, "text", "data-dediren-sequence-operand", operandId);
        assertThat(guardText.getAttribute("data-dediren-sequence-operand-guard")).isEqualTo(guard);
        assertThat(guardText.getTextContent()).isEqualTo("[" + guard + "]");
    }

    private static void assertOperandSeparator(Document document, String fragmentId, String operandId) {
        Element fragment = groupWithAttribute(
                document,
                "data-dediren-sequence-combined-fragment",
                fragmentId);
        childElementWithAttribute(fragment, "line", "data-dediren-sequence-operand-separator", operandId);
    }

    private static void assertCombinedFragmentContainedWithin(Document document, String innerId, String outerId) {
        Element inner = combinedFragmentShape(document, innerId);
        Element outer = combinedFragmentShape(document, outerId);

        double innerX = Double.parseDouble(inner.getAttribute("x"));
        double innerY = Double.parseDouble(inner.getAttribute("y"));
        double innerRight = innerX + Double.parseDouble(inner.getAttribute("width"));
        double innerBottom = innerY + Double.parseDouble(inner.getAttribute("height"));
        double outerX = Double.parseDouble(outer.getAttribute("x"));
        double outerY = Double.parseDouble(outer.getAttribute("y"));
        double outerRight = outerX + Double.parseDouble(outer.getAttribute("width"));
        double outerBottom = outerY + Double.parseDouble(outer.getAttribute("height"));

        assertThat(innerX).isGreaterThanOrEqualTo(outerX - 0.1);
        assertThat(innerY).isGreaterThanOrEqualTo(outerY - 0.1);
        assertThat(innerRight).isLessThanOrEqualTo(outerRight + 0.1);
        assertThat(innerBottom).isLessThanOrEqualTo(outerBottom + 0.1);
    }

    private static Element combinedFragmentShape(Document document, String id) {
        return childElementWithAttribute(
                groupWithAttribute(document, "data-dediren-sequence-combined-fragment", id),
                "rect",
                "data-dediren-node-shape",
                "uml_combined_fragment");
    }

    private static java.util.List<String> combinedFragmentIds(Document document) {
        return combinedFragmentGroups(document).stream()
                .map(group -> group.getAttribute("data-dediren-sequence-combined-fragment"))
                .toList();
    }

    private static java.util.List<String> combinedFragmentStructures(Document document) {
        var structures = new java.util.ArrayList<String>();
        for (Element group : combinedFragmentGroups(document)) {
            StringBuilder structure = new StringBuilder();
            structure.append(group.getAttribute("data-dediren-sequence-combined-fragment"))
                    .append("|")
                    .append(group.getAttribute("data-dediren-sequence-interaction-operator"));
            for (Element text : childElements(group, "text")) {
                if (text.hasAttribute("data-dediren-sequence-fragment-operator")) {
                    structure.append("|operator:")
                            .append(text.getAttribute("data-dediren-sequence-fragment-operator"))
                            .append("=")
                            .append(text.getTextContent());
                }
                if (text.hasAttribute("data-dediren-sequence-operand")) {
                    structure.append("|guard:")
                            .append(text.getAttribute("data-dediren-sequence-operand"))
                            .append("=")
                            .append(text.getAttribute("data-dediren-sequence-operand-guard"))
                            .append(":")
                            .append(text.getTextContent());
                }
            }
            for (Element line : childElements(group, "line")) {
                if (line.hasAttribute("data-dediren-sequence-operand-separator")) {
                    structure.append("|separator:")
                            .append(line.getAttribute("data-dediren-sequence-operand-separator"));
                }
            }
            structures.add(structure.toString());
        }
        return structures;
    }

    private static java.util.List<Element> combinedFragmentGroups(Document document) {
        var matches = new java.util.ArrayList<Element>();
        var groups = document.getElementsByTagName("g");
        for (int index = 0; index < groups.getLength(); index++) {
            Element group = (Element) groups.item(index);
            if (group.hasAttribute("data-dediren-sequence-combined-fragment")) {
                matches.add(group);
            }
        }
        return matches;
    }

    private static Element childElementWithAttribute(
            Element parent,
            String tagName,
            String name,
            String value) {
        for (Element element : childElements(parent, tagName)) {
            if (value.equals(element.getAttribute(name))) {
                return element;
            }
        }
        throw new AssertionError("expected child <" + tagName + "> with " + name + "=" + value);
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

    private static Element elementWithAttribute(Document document, String tagName, String name, String value) {
        var elements = document.getElementsByTagName(tagName);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (value.equals(element.getAttribute(name))) {
                return element;
            }
        }
        throw new AssertionError("expected <" + tagName + "> with " + name + "=" + value);
    }

    private static String expectedArchimateIconKind(String nodeType) {
        return switch (nodeType) {
            case "BusinessInterface", "ApplicationInterface", "TechnologyInterface" -> "interface";
            case "BusinessCollaboration", "ApplicationCollaboration", "TechnologyCollaboration" -> "collaboration";
            case "BusinessActor" -> "actor";
            case "BusinessRole" -> "role";
            case "BusinessService", "ApplicationService", "TechnologyService" -> "service";
            case "BusinessInteraction", "ApplicationInteraction", "TechnologyInteraction" -> "interaction";
            case "BusinessFunction", "ApplicationFunction", "TechnologyFunction" -> "function";
            case "BusinessProcess", "ApplicationProcess", "TechnologyProcess" -> "process";
            case "BusinessEvent", "ApplicationEvent", "TechnologyEvent", "ImplementationEvent" -> "event";
            case "BusinessObject", "DataObject" -> "object";
            case "ApplicationComponent" -> "component";
            case "Contract" -> "contract";
            case "Product" -> "product";
            case "Representation" -> "representation";
            case "Location" -> "location";
            case "Grouping" -> "grouping";
            case "Stakeholder" -> "stakeholder";
            case "Driver" -> "driver";
            case "Assessment" -> "assessment";
            case "Goal" -> "goal";
            case "Outcome" -> "outcome";
            case "Value" -> "value";
            case "Meaning" -> "meaning";
            case "Constraint" -> "constraint";
            case "Requirement" -> "requirement";
            case "Principle" -> "principle";
            case "CourseOfAction" -> "course_of_action";
            case "Resource" -> "resource";
            case "ValueStream" -> "value_stream";
            case "Capability" -> "capability";
            case "Plateau" -> "plateau";
            case "WorkPackage" -> "work_package";
            case "Deliverable" -> "deliverable";
            case "Gap" -> "gap";
            case "Artifact" -> "artifact";
            case "SystemSoftware" -> "system_software";
            case "Device" -> "device";
            case "Facility" -> "facility";
            case "Equipment" -> "equipment";
            case "Node" -> "node";
            case "Material" -> "material";
            case "CommunicationNetwork" -> "network";
            case "DistributionNetwork" -> "distribution_network";
            case "Path" -> "path";
            default -> throw new AssertionError("missing expected icon kind for " + nodeType);
        };
    }

    private static void assertDistinctArchimateIconMorphology(
            String nodeType,
            String expectedKind,
            Element decorator) {
        java.util.List<Element> paths = childElements(decorator, "path");
        assertThat(paths).as(nodeType + " should not use the generic triangular fallback")
                .noneMatch(path -> expectedKind.equals(path.getAttribute("data-dediren-icon-part"))
                        && paths.size() == 1
                        && childElements(decorator, "rect").isEmpty()
                        && childElements(decorator, "ellipse").isEmpty()
                        && childElements(decorator, "circle").isEmpty());
        java.util.List<String> primitiveParts = iconPrimitiveParts(decorator);
        for (String requiredPart : requiredArchimateIconParts(nodeType)) {
            assertThat(primitiveParts)
                    .as(nodeType + " should expose ArchiMate icon part " + requiredPart)
                    .contains(requiredPart);
        }
    }

    private static java.util.List<String> iconPrimitiveParts(Element decorator) {
        var parts = new java.util.ArrayList<String>();
        for (String tagName : java.util.List.of("path", "rect", "ellipse", "circle")) {
            for (Element element : childElements(decorator, tagName)) {
                if (element.hasAttribute("data-dediren-icon-part")) {
                    parts.add(element.getAttribute("data-dediren-icon-part"));
                }
            }
        }
        return parts;
    }

    private static java.util.List<String> requiredArchimateIconParts(String nodeType) {
        return switch (nodeType) {
            case "BusinessCollaboration", "ApplicationCollaboration", "TechnologyCollaboration" ->
                    java.util.List.of("collaboration-circles");
            case "BusinessRole", "Stakeholder" -> java.util.List.of("side-cylinder", "side-cylinder-end");
            case "BusinessInteraction", "ApplicationInteraction", "TechnologyInteraction" ->
                    java.util.List.of("interaction-half");
            case "BusinessFunction", "ApplicationFunction", "TechnologyFunction" ->
                    java.util.List.of("function-bookmark");
            case "BusinessProcess", "ApplicationProcess", "TechnologyProcess" ->
                    java.util.List.of("process-arrow");
            case "BusinessEvent", "ApplicationEvent", "TechnologyEvent", "ImplementationEvent" ->
                    java.util.List.of("event-pill");
            case "BusinessObject", "DataObject" -> java.util.List.of("document-body", "document-header");
            case "ApplicationComponent" -> java.util.List.of();
            case "Contract" -> java.util.List.of("contract-document-body", "contract-lines");
            case "Product" -> java.util.List.of("product-tab");
            case "Representation" -> java.util.List.of("wavy-representation");
            case "Driver" -> java.util.List.of("driver-spokes");
            case "Assessment" -> java.util.List.of("assessment-handle");
            case "Outcome" -> java.util.List.of("target-arrow");
            case "Constraint" -> java.util.List.of("constraint-parallelogram", "constraint-left-line");
            case "Requirement" -> java.util.List.of("requirement-parallelogram");
            case "CourseOfAction" -> java.util.List.of("course-of-action-handle");
            case "Resource" -> java.util.List.of("resource-capsule", "resource-tab", "resource-bars");
            case "ValueStream" -> java.util.List.of("value-stream-chevron");
            case "Capability" -> java.util.List.of("capability-step");
            case "WorkPackage" -> java.util.List.of("work-package-loop-arrow");
            case "Deliverable" -> java.util.List.of("wavy-document");
            case "Gap" -> java.util.List.of("gap-lines");
            case "Artifact" -> java.util.List.of("artifact-document");
            case "SystemSoftware" -> java.util.List.of("system-software-disks");
            case "Device" -> java.util.List.of("device-stand");
            case "Facility" -> java.util.List.of("factory-silhouette");
            case "Equipment" -> java.util.List.of("equipment-gear-large", "equipment-gear-small");
            case "Node" -> java.util.List.of("node-3d-edges");
            case "Material" -> java.util.List.of("material-hexagon", "material-lines");
            case "DistributionNetwork" -> java.util.List.of("distribution-network-arrows");
            case "Path" -> java.util.List.of("path-line", "path-arrowheads");
            default -> java.util.List.of();
        };
    }

    private static java.util.List<String> edgeLabelsInDomOrder(Document document) {
        var labels = new java.util.ArrayList<String>();
        var groups = document.getElementsByTagName("g");
        for (int index = 0; index < groups.getLength(); index++) {
            Element group = (Element) groups.item(index);
            if (!group.hasAttribute("data-dediren-edge-id")) {
                continue;
            }
            var texts = childElements(group, "text");
            if (!texts.isEmpty()) {
                labels.add(texts.get(0).getTextContent());
            }
        }
        return labels;
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

    private static java.util.List<String> textLinesFromSvg(Element label) {
        java.util.List<Element> tspans = childElements(label, "tspan");
        if (tspans.isEmpty()) {
            return java.util.List.of(label.getTextContent());
        }
        return tspans.stream().map(Element::getTextContent).toList();
    }

    private static double estimatedSvgTextWidth(String text, double fontSize) {
        return text.codePointCount(0, text.length()) * fontSize * 0.62;
    }

    private static double svgFontSize(Element label) {
        return label.hasAttribute("font-size") && !label.getAttribute("font-size").isEmpty()
                ? Double.parseDouble(label.getAttribute("font-size"))
                : 14.0;
    }

    private static boolean textBoxesOverlap(Element left, Element right) {
        double[] leftBox = textBox(left);
        double[] rightBox = textBox(right);
        return leftBox[0] < rightBox[2]
                && leftBox[2] > rightBox[0]
                && leftBox[1] < rightBox[3]
                && leftBox[3] > rightBox[1];
    }

    private static double[] textBox(Element label) {
        double fontSize = label.hasAttribute("font-size")
                ? Double.parseDouble(label.getAttribute("font-size"))
                : 14.0;
        double x = Double.parseDouble(label.getAttribute("x"));
        double y = Double.parseDouble(label.getAttribute("y"));
        String text = label.getTextContent() == null ? "" : label.getTextContent();
        double width = text.length() * fontSize * 0.56;
        double minX = switch (label.getAttribute("text-anchor")) {
            case "end" -> x - width;
            case "middle" -> x - width / 2.0;
            default -> x;
        };
        double minY = y - fontSize;
        return new double[]{minX, minY, minX + width, y + fontSize * 0.25};
    }

    private static JsonNode fixtureJson(String path) throws Exception {
        return JsonSupport.objectMapper().readTree(fixture(path));
    }

    private static String fixture(String path) throws Exception {
        return Files.readString(workspaceRoot().resolve(path));
    }

    private static Path writeRenderArtifact(String testName, String content) throws Exception {
        Path output = workspaceRoot()
                .resolve(".test-output/renders/svg-render-plugin")
                .resolve(testName + ".svg");
        Files.createDirectories(output.getParent());
        Files.writeString(output, content);
        return output;
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

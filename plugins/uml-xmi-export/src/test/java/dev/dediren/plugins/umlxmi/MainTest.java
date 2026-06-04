package dev.dediren.plugins.umlxmi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.contracts.json.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @TempDir
    Path tempDir;

    @Test
    void moduleLoads() {
        assertThat(Main.moduleName()).isEqualTo("uml-xmi-export");
    }

    @Test
    void reportsCapabilities() throws Exception {
        PluginResult result = Main.executeForTesting(new String[]{"capabilities"}, "", envWithXmiSchema());

        JsonNode capabilities = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(capabilities.at("/id").asText()).isEqualTo("uml-xmi");
        assertThat(capabilities.at("/runtime/artifact_kind").asText()).isEqualTo("uml-xmi+xml");
        assertThat(capabilities.at("/runtime/schema_validation/kind").asText()).isEqualTo("omg-xmi-xsd-partial");
        assertThat(capabilities.at("/capabilities").toString()).contains("export");
    }

    @Test
    void outputsXmi() throws Exception {
        String xml = exportXml(exportInput());

        assertThat(xml).isEqualTo(fixture("fixtures/export/uml-basic.xmi"));
        assertThat(xml).contains("uml:Class");
        assertThat(xml).doesNotContain("xmi:version", "uml:Activity");
    }

    @Test
    void scopesModelToLayoutView() throws Exception {
        String xml = exportXml(exportInput());

        assertThat(xml).contains("xmi:type=\"uml:Class\"");
        assertThat(xml).doesNotContain("activity-submit-order", "xmi:type=\"uml:Activity\"");
    }

    @Test
    void emitsInterfaceInScopedClassView() throws Exception {
        JsonNode input = exportInput(
                fixtureJson("fixtures/source/valid-uml-complex.json"),
                fixtureJson("fixtures/layout-result/uml-complex-class.json"));

        String xml = exportXml(input);

        assertThat(xml).contains("xmi:type=\"uml:Interface\"", "PaymentGateway");
        assertThat(xml).doesNotContain("Fulfill Order");
    }

    @Test
    void emitsActivityNodesAndFlowsForActivityView() throws Exception {
        JsonNode input = exportInput(
                fixtureJson("fixtures/source/valid-uml-basic.json"),
                fixtureJson("fixtures/layout-result/uml-activity.json"));

        String xml = exportXml(input);

        assertThat(xml).contains(
                "xmi:type=\"uml:Activity\"",
                "xmi:type=\"uml:InitialNode\"",
                "xmi:type=\"uml:OpaqueAction\"",
                "xmi:type=\"uml:ControlFlow\"");
        assertThat(xml).doesNotContain("xmi:type=\"uml:Class\"");
    }

    @Test
    void exportsSequenceInteractionLifelinesAndMessages() throws Exception {
        JsonNode input = exportSequenceInput();
        ((ObjectNode) input.at("/source/relationships/0/properties/uml")).remove("message_sort");

        String xml = exportXml(input);

        assertThat(xml).isEqualTo(fixture("fixtures/export/uml-sequence-basic.xmi"));
        assertThat(xml).contains(
                "xmi:type=\"uml:Interaction\"",
                "<lifeline xmi:id=\"id-customer\" name=\"Customer\"/>",
                "<lifeline xmi:id=\"id-service\" name=\"Order Service\"/>",
                "<message xmi:id=\"id-m1\" name=\"placeOrder\" messageSort=\"synchCall\""
                        + " sendEvent=\"id-m1-send-event\" receiveEvent=\"id-m1-receive-event\"/>");
    }

    @Test
    void exportsEndpointLifelineWhenOnlySelectedMessageOwnsInteraction() throws Exception {
        JsonNode input = exportSequenceInput();
        ((ObjectNode) input.at("/source/nodes/1/properties/uml")).remove("interaction");

        String xml = exportXml(input);

        assertThat(xml).contains(
                "<lifeline xmi:id=\"id-customer\" name=\"Customer\"/>",
                "<message xmi:id=\"id-m1\" name=\"placeOrder\" messageSort=\"synchCall\""
                        + " sendEvent=\"id-m1-send-event\" receiveEvent=\"id-m1-receive-event\"/>");
    }

    @Test
    void exportsSequenceMessagesInSequenceOrder() throws Exception {
        JsonNode input = exportSequenceInput();
        ((ObjectNode) input.at("/source/relationships/0/properties/uml")).put("sequence", 2);
        ((ObjectNode) input.at("/source/relationships/1/properties/uml")).put("sequence", 1);

        String xml = exportXml(input);

        assertThat(xml).containsSubsequence(
                "xmi:id=\"id-m2-send-event\"",
                "xmi:id=\"id-m2-receive-event\"",
                "xmi:id=\"id-m1-send-event\"",
                "xmi:id=\"id-m1-receive-event\"",
                "xmi:id=\"id-m3-send-event\"",
                "xmi:id=\"id-m3-receive-event\"");
        assertThat(xml).containsSubsequence(
                "<message xmi:id=\"id-m2\"",
                "<message xmi:id=\"id-m1\"",
                "<message xmi:id=\"id-m3\"");
    }

    @Test
    void rejectsSelectedSequenceDeleteMessageToDestructionOccurrence() throws Exception {
        JsonNode input = exportSequenceInput();
        ((com.fasterxml.jackson.databind.node.ArrayNode) input.at("/source/nodes")).add(
                JsonSupport.objectMapper().readTree("""
                {
                  "id": "service-destroyed",
                  "type": "DestructionOccurrenceSpecification",
                  "label": "",
                  "properties": {
                    "uml": {
                      "interaction": "interaction-place-order"
                    }
                  }
                }
                """));
        ((com.fasterxml.jackson.databind.node.ArrayNode) input.at("/source/relationships")).add(
                JsonSupport.objectMapper().readTree("""
                {
                  "id": "m5",
                  "type": "Message",
                  "source": "customer",
                  "target": "service-destroyed",
                  "label": "cancelOrder",
                  "properties": {
                    "uml": {
                      "interaction": "interaction-place-order",
                      "sequence": 4,
                      "message_sort": "deleteMessage"
                    }
                  }
                }
                """));

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithXmiSchema());

        assertThat(result.exitCode()).isEqualTo(3);
        assertError(result, "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_ENDPOINT_UNSUPPORTED", "$.relationships[3]");
    }

    @Test
    void rejectsSelectedSequenceMessageWithoutInteraction() throws Exception {
        JsonNode input = exportSequenceInput();
        ((ObjectNode) input.at("/source/relationships/0/properties/uml")).remove("interaction");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithXmiSchema());

        assertThat(result.exitCode()).isEqualTo(3);
        assertError(
                result,
                "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_MISSING",
                "$.relationships[0].properties.uml.interaction");
    }

    @Test
    void rejectsSelectedSequenceMessageWithUnknownInteraction() throws Exception {
        JsonNode input = exportSequenceInput();
        ((ObjectNode) input.at("/source/relationships/0/properties/uml")).put("interaction", "missing-interaction");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithXmiSchema());

        assertThat(result.exitCode()).isEqualTo(3);
        assertError(
                result,
                "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_UNSUPPORTED",
                "$.relationships[0].properties.uml.interaction");
    }

    @Test
    void rejectsSelectedSequenceMessageWithNonInteractionOwner() throws Exception {
        JsonNode input = exportSequenceInput();
        ((ObjectNode) input.at("/source/relationships/0/properties/uml")).put("interaction", "customer");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithXmiSchema());

        assertThat(result.exitCode()).isEqualTo(3);
        assertError(
                result,
                "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_UNSUPPORTED",
                "$.relationships[0].properties.uml.interaction");
    }

    @Test
    void rejectsSelectedUnsupportedSequenceNode() throws Exception {
        JsonNode input = exportSequenceInput();
        ((com.fasterxml.jackson.databind.node.ArrayNode) input.at("/source/nodes")).add(
                JsonSupport.objectMapper().readTree("""
                {
                  "id": "service-execution",
                  "type": "ExecutionSpecification",
                  "label": "",
                  "properties": {
                    "uml": {
                      "interaction": "interaction-place-order"
                    }
                  }
                }
                """));

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithXmiSchema());

        assertThat(result.exitCode()).isEqualTo(3);
        assertError(result, "DEDIREN_UML_XMI_SEQUENCE_NODE_UNSUPPORTED", "$.nodes[3]");
    }

    @Test
    void rejectsInvalidUmlRelationshipEndpoint() throws Exception {
        JsonNode input = exportInput();
        ((ObjectNode) input.at("/source/relationships/0")).put("source", "initial-submit");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithXmiSchema());

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
    }

    @Test
    void rejectsInvalidSequenceMessageEndpoint() throws Exception {
        JsonNode input = exportSequenceInput();
        ((ObjectNode) input.at("/source/nodes/2")).put("type", "Class");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithXmiSchema());

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
    }

    @Test
    void rejectsInvalidPolicySchema() throws Exception {
        JsonNode input = exportInput();
        ((ObjectNode) input.get("policy")).put("xmi_version", "3.0");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithXmiSchema());

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_UML_XMI_POLICY_INVALID");
    }

    @Test
    void rejectsGeneratedXmlWithInvalidXmiId() throws Exception {
        JsonNode input = exportInput();
        ((ObjectNode) input.get("policy")).put("model_identifier", "not a valid xml id");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithXmiSchema());

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_XMI_ID_INVALID");
    }

    @Test
    void deduplicatesCollidingGeneratedIds() throws Exception {
        JsonNode input = exportInput();
        ((ObjectNode) input.get("policy")).put("model_identifier", "id-class-order");
        ((com.fasterxml.jackson.databind.node.ArrayNode) input.at("/source/nodes")).add(
                JsonSupport.objectMapper().readTree("""
                {
                  "id": "class_order",
                  "type": "Class",
                  "label": "Order Copy",
                  "properties": {
                    "uml": {
                      "attributes": [
                        { "name": "id", "type": "OrderId", "visibility": "public", "multiplicity": "1" }
                      ],
                      "operations": []
                    }
                  }
                }
                """));
        ((com.fasterxml.jackson.databind.node.ArrayNode) input.at("/layout_result/nodes")).add(
                JsonSupport.objectMapper().readTree("""
                {
                  "id": "class_order",
                  "source_id": "class_order",
                  "projection_id": "class_order",
                  "x": 600,
                  "y": 72,
                  "width": 180,
                  "height": 104,
                  "label": "Order Copy"
                }
                """));

        String xml = exportXml(input);

        assertThat(xml).contains(
                "xmi:id=\"id-class-order-2\"",
                "xmi:id=\"id-class-order-3\"",
                "xmi:id=\"id-class-order-id-2\"");
        var seen = new HashSet<String>();
        for (String id : xml.split("xmi:id=\"")) {
            if (!id.contains("\"")) {
                continue;
            }
            String value = id.substring(0, id.indexOf('"'));
            assertThat(seen.add(value)).describedAs("duplicate xmi:id " + value).isTrue();
        }
    }

    private String exportXml(JsonNode input) throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithXmiSchema());
        return okData(result).at("/content").asText();
    }

    private JsonNode exportInput() throws Exception {
        return exportInput(
                fixtureJson("fixtures/source/valid-uml-basic.json"),
                fixtureJson("fixtures/layout-result/uml-basic.json"));
    }

    private JsonNode exportSequenceInput() throws Exception {
        return exportInput(
                fixtureJson("fixtures/source/valid-uml-sequence-basic.json"),
                fixtureJson("fixtures/layout-result/uml-sequence-basic.json"));
    }

    private JsonNode exportInput(JsonNode source, JsonNode layout) throws Exception {
        ObjectNode input = JsonSupport.objectMapper().createObjectNode();
        input.put("export_request_schema_version", "export-request.schema.v1");
        input.set("source", source);
        input.set("layout_result", layout);
        input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));
        return input;
    }

    private Map<String, String> envWithXmiSchema() throws Exception {
        Path schemaPath = tempDir.resolve("XMI.xsd");
        Files.writeString(schemaPath, """
                <?xml version="1.0" encoding="UTF-8"?>
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                            targetNamespace="http://www.omg.org/spec/XMI/20131001"
                            xmlns="http://www.omg.org/spec/XMI/20131001"
                            elementFormDefault="qualified">
                  <xsd:element name="XMI">
                    <xsd:complexType>
                      <xsd:choice minOccurs="0" maxOccurs="unbounded">
                        <xsd:any processContents="lax"/>
                      </xsd:choice>
                      <xsd:anyAttribute processContents="lax"/>
                    </xsd:complexType>
                  </xsd:element>
                </xsd:schema>
                """, StandardCharsets.UTF_8);
        return Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString());
    }

    private static JsonNode okData(PluginResult result) throws Exception {
        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
        assertThat(envelope.at("/status").asText()).describedAs(result.stdout()).isEqualTo("ok");
        return envelope.get("data");
    }

    private static void assertErrorCode(PluginResult result, String expectedCode) throws Exception {
        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo(expectedCode);
    }

    private static void assertError(PluginResult result, String expectedCode, String expectedPath) throws Exception {
        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo(expectedCode);
        assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo(expectedPath);
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

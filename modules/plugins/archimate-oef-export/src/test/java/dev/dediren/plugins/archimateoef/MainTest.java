package dev.dediren.plugins.archimateoef;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.contracts.json.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @TempDir
    Path tempDir;

    @Test
    void moduleLoads() {
        assertThat(Main.moduleName()).isEqualTo("archimate-oef-export");
    }

    @Test
    void reportsCapabilities() throws Exception {
        PluginResult result = Main.executeForTesting(new String[]{"capabilities"}, "", envWithOefSchemas());

        JsonNode capabilities = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(capabilities.at("/id").asText()).isEqualTo("archimate-oef");
        assertThat(capabilities.at("/runtime/artifact_kind").asText()).isEqualTo("archimate-oef+xml");
        assertThat(capabilities.at("/runtime/schema_validation/kind").asText()).isEqualTo("official-oef-xsd");
        assertThat(capabilities.at("/runtime/schema_validation/validator").asText()).isEqualTo("xmllint");
        assertThat(capabilities.at("/capabilities").toString()).contains("export");
    }

    @Test
    void outputsModelValidOefXml() throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                exportInput(fixtureJson("fixtures/source/valid-archimate-oef.json"),
                        fixtureJson("fixtures/layout-result/archimate-oef-basic.json")),
                envWithOefSchemas());

        JsonNode data = okData(result);

        assertThat(result.exitCode()).isZero();
        assertThat(data.at("/artifact_kind").asText()).isEqualTo("archimate-oef+xml");
        assertThat(data.at("/content").asText()).isEqualTo(fixture("fixtures/export/oef-basic.xml"));
    }

    @Test
    void emitsSemanticGroupingViewNodeAndIgnoresLayoutOnlyGroup() throws Exception {
        JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
        ((com.fasterxml.jackson.databind.node.ArrayNode) source.get("nodes")).addObject()
                .put("id", "customer-domain")
                .put("type", "Grouping")
                .put("label", "Customer Domain")
                .set("properties", JsonSupport.objectMapper().createObjectNode());
        JsonNode layout = fixtureJson("fixtures/layout-result/archimate-oef-basic.json");
        layoutWithGroups(layout, 10.0, 10.0, 520.0, 180.0);

        String xml = exportXml(source, layout);

        assertThat(xml).contains("id-el-customer-domain", "Customer Domain", "x=\"10\"", "w=\"520\"");
        assertThat(xml).doesNotContain("Visual Column");
    }

    @Test
    void roundsDecimalGeometryToIntegerOefCoordinates() throws Exception {
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
        ((ObjectNode) layout.at("/edges/0/points/0")).put("x", 220.2);
        ((ObjectNode) layout.at("/edges/0/points/0")).put("y", 80.8);
        layoutWithGroups(layout, 10.6, 11.5, 520.4, 180.6);

        String xml = exportXml(source, layout);

        assertThat(xml).contains("x=\"11\" y=\"12\" w=\"520\" h=\"181\"");
        assertThat(xml).contains("x=\"40\" y=\"41\" w=\"181\" h=\"80\"");
        assertThat(xml).contains("<bendpoint x=\"220\" y=\"81\"/>");
    }

    @Test
    void rejectsUnknownArchimateNodeTypeWithErrorEnvelope() throws Exception {
        JsonNode input = exportInputJson();
        ((ObjectNode) input.at("/source/nodes/0")).put("type", "TechnologyNode");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithOefSchemas());

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
        assertThat(result.stdout()).contains("TechnologyNode");
    }

    @Test
    void rejectsUnknownArchimateRelationshipTypeWithErrorEnvelope() throws Exception {
        JsonNode input = exportInputJson();
        ((ObjectNode) input.at("/source/relationships/0")).put("type", "ConnectsTo");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithOefSchemas());

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED");
        assertThat(result.stdout()).contains("ConnectsTo");
    }

    @Test
    void rejectsInvalidArchimateRelationshipEndpointWithErrorEnvelope() throws Exception {
        JsonNode input = exportInputJson();
        ((ObjectNode) input.at("/source/nodes/0")).put("type", "ApplicationService");
        ((ObjectNode) input.at("/source/nodes/1")).put("type", "ApplicationComponent");
        ((ObjectNode) input.at("/source/relationships/0")).put("type", "Realization");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithOefSchemas());

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
        assertThat(result.stdout()).contains("ApplicationService", "Realization", "ApplicationComponent");
    }

    @Test
    void emitsArchimateRelationshipConnectorJunctions() throws Exception {
        String xml = exportXml(
                JsonSupport.objectMapper().readTree("""
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "0.16.0" },
                    { "id": "archimate-oef", "version": "0.16.0" }
                  ],
                  "nodes": [
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
                    { "id": "flow-junction", "type": "AndJunction", "label": "All Targets", "properties": {} },
                    { "id": "orders", "type": "ApplicationService", "label": "Orders", "properties": {} },
                    { "id": "billing", "type": "ApplicationService", "label": "Billing", "properties": {} }
                  ],
                  "relationships": [
                    { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "flow-junction", "label": "", "properties": {} },
                    { "id": "junction-to-orders", "type": "Flow", "source": "flow-junction", "target": "orders", "label": "", "properties": {} },
                    { "id": "junction-to-billing", "type": "Flow", "source": "flow-junction", "target": "billing", "label": "", "properties": {} }
                  ],
                  "plugins": {}
                }
                """),
                JsonSupport.objectMapper().readTree("""
                {
                  "layout_result_schema_version": "layout-result.schema.v1",
                  "view_id": "main",
                  "nodes": [
                    { "id": "api", "source_id": "api", "projection_id": "api", "x": 20.0, "y": 80.0, "width": 160.0, "height": 80.0, "label": "API" },
                    { "id": "flow-junction", "source_id": "flow-junction", "projection_id": "flow-junction", "x": 240.0, "y": 106.0, "width": 28.0, "height": 28.0, "label": "" },
                    { "id": "orders", "source_id": "orders", "projection_id": "orders", "x": 340.0, "y": 40.0, "width": 160.0, "height": 80.0, "label": "Orders" },
                    { "id": "billing", "source_id": "billing", "projection_id": "billing", "x": 340.0, "y": 150.0, "width": 160.0, "height": 80.0, "label": "Billing" }
                  ],
                  "edges": [
                    { "id": "api-to-junction", "source": "api", "target": "flow-junction", "source_id": "api-to-junction", "projection_id": "api-to-junction", "points": [{ "x": 180.0, "y": 120.0 }, { "x": 240.0, "y": 120.0 }], "label": "" },
                    { "id": "junction-to-orders", "source": "flow-junction", "target": "orders", "source_id": "junction-to-orders", "projection_id": "junction-to-orders", "points": [{ "x": 268.0, "y": 120.0 }, { "x": 340.0, "y": 80.0 }], "label": "" },
                    { "id": "junction-to-billing", "source": "flow-junction", "target": "billing", "source_id": "junction-to-billing", "projection_id": "junction-to-billing", "points": [{ "x": 268.0, "y": 120.0 }, { "x": 340.0, "y": 190.0 }], "label": "" }
                  ],
                  "groups": [],
                  "warnings": []
                }
                """));

        assertThat(xml).contains(
                "id-el-flow-junction",
                "xsi:type=\"AndJunction\"",
                "id-vn-main-flow-junction",
                "relationshipRef=\"id-rel-api-to-junction\"",
                "target=\"id-vn-main-flow-junction\"",
                "source=\"id-vn-main-flow-junction\"");
    }

    @Test
    void allowsJunctionContainmentRelationship() throws Exception {
        String xml = exportXml(
                JsonSupport.objectMapper().readTree("""
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "0.16.0" },
                    { "id": "archimate-oef", "version": "0.16.0" }
                  ],
                  "nodes": [
                    { "id": "group", "type": "Grouping", "label": "Group", "properties": {} },
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} },
                    { "id": "junction", "type": "AndJunction", "label": "", "properties": {} },
                    { "id": "orders", "type": "ApplicationService", "label": "Orders", "properties": {} }
                  ],
                  "relationships": [
                    { "id": "group-contains-junction", "type": "Composition", "source": "group", "target": "junction", "label": "", "properties": {} },
                    { "id": "api-to-junction", "type": "Flow", "source": "api", "target": "junction", "label": "", "properties": {} },
                    { "id": "junction-to-orders", "type": "Flow", "source": "junction", "target": "orders", "label": "", "properties": {} }
                  ],
                  "plugins": {}
                }
                """),
                JsonSupport.objectMapper().readTree("""
                {
                  "layout_result_schema_version": "layout-result.schema.v1",
                  "view_id": "main",
                  "nodes": [
                    { "id": "group", "source_id": "group", "projection_id": "group", "x": 0.0, "y": 0.0, "width": 440.0, "height": 180.0, "label": "Group" },
                    { "id": "api", "source_id": "api", "projection_id": "api", "x": 20.0, "y": 60.0, "width": 160.0, "height": 80.0, "label": "API" },
                    { "id": "junction", "source_id": "junction", "projection_id": "junction", "x": 220.0, "y": 86.0, "width": 28.0, "height": 28.0, "label": "" },
                    { "id": "orders", "source_id": "orders", "projection_id": "orders", "x": 300.0, "y": 60.0, "width": 120.0, "height": 80.0, "label": "Orders" }
                  ],
                  "edges": [
                    { "id": "group-contains-junction", "source": "group", "target": "junction", "source_id": "group-contains-junction", "projection_id": "group-contains-junction", "points": [{ "x": 220.0, "y": 86.0 }, { "x": 220.0, "y": 86.0 }], "label": "" },
                    { "id": "api-to-junction", "source": "api", "target": "junction", "source_id": "api-to-junction", "projection_id": "api-to-junction", "points": [{ "x": 180.0, "y": 100.0 }, { "x": 220.0, "y": 100.0 }], "label": "" },
                    { "id": "junction-to-orders", "source": "junction", "target": "orders", "source_id": "junction-to-orders", "projection_id": "junction-to-orders", "points": [{ "x": 248.0, "y": 100.0 }, { "x": 300.0, "y": 100.0 }], "label": "" }
                  ],
                  "groups": [],
                  "warnings": []
                }
                """));

        assertThat(xml).contains("id-rel-group-contains-junction", "id-el-junction");
    }

    @Test
    void rejectsInvalidPolicyWithErrorEnvelope() throws Exception {
        JsonNode input = exportInputJson();
        ((ObjectNode) input.get("policy")).remove("model_identifier");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithOefSchemas());

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_OEF_POLICY_INVALID");
        assertThat(result.stdout()).contains("model_identifier");
    }

    @Test
    void rejectsGeneratedXmlThatFailsOfficialOefSchema() throws Exception {
        JsonNode input = exportInputJson();
        ((ObjectNode) input.get("policy")).put("model_identifier", "not a valid xml id");

        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                JsonSupport.objectMapper().writeValueAsString(input),
                envWithOefSchemas());

        assertThat(result.exitCode()).isEqualTo(3);
        assertErrorCode(result, "DEDIREN_OEF_SCHEMA_INVALID");
        assertThat(result.stdout()).contains("official OEF schema");
    }

    private String exportXml(JsonNode source, JsonNode layout) throws Exception {
        PluginResult result = Main.executeForTesting(
                new String[]{"export"},
                exportInput(source, layout),
                envWithOefSchemas());
        return okData(result).at("/content").asText();
    }

    private JsonNode exportInputJson() throws Exception {
        return JsonSupport.objectMapper().readTree(exportInput(
                fixtureJson("fixtures/source/valid-archimate-oef.json"),
                fixtureJson("fixtures/layout-result/archimate-oef-basic.json")));
    }

    private String exportInput(JsonNode source, JsonNode layout) throws Exception {
        return exportInput(source, layout, fixtureJson("fixtures/export-policy/default-oef.json"));
    }

    private String exportInput(JsonNode source, JsonNode layout, JsonNode policy) throws Exception {
        ObjectNode input = JsonSupport.objectMapper().createObjectNode();
        input.put("export_request_schema_version", "export-request.schema.v1");
        input.set("source", source);
        input.set("layout_result", layout);
        input.set("policy", policy);
        return JsonSupport.objectMapper().writeValueAsString(input);
    }

    private void layoutWithGroups(JsonNode layout, double x, double y, double width, double height) {
        var groups = JsonSupport.objectMapper().createArrayNode();
        groups.addObject()
                .put("id", "customer-domain-group")
                .put("source_id", "customer-domain")
                .put("projection_id", "customer-domain-group")
                .set("provenance", JsonSupport.objectMapper().createObjectNode()
                        .set("semantic_backed", JsonSupport.objectMapper().createObjectNode()
                                .put("source_id", "customer-domain")));
        ((ObjectNode) groups.get(0))
                .put("x", x)
                .put("y", y)
                .put("width", width)
                .put("height", height)
                .set("members", JsonSupport.objectMapper().createArrayNode()
                        .add("orders-component")
                        .add("orders-service"));
        ((ObjectNode) groups.get(0)).put("label", "Customer Domain");
        groups.addObject()
                .put("id", "visual-column")
                .put("source_id", "visual-column")
                .put("projection_id", "visual-column")
                .set("provenance", JsonSupport.objectMapper().createObjectNode().put("visual_only", true));
        ((ObjectNode) groups.get(1))
                .put("x", 40.0)
                .put("y", 40.0)
                .put("width", 200.0)
                .put("height", 120.0)
                .set("members", JsonSupport.objectMapper().createArrayNode().add("orders-component"));
        ((ObjectNode) groups.get(1)).put("label", "Visual Column");
        ((ObjectNode) layout).set("groups", groups);
    }

    private Map<String, String> envWithOefSchemas() throws Exception {
        Path schemaDir = tempDir.resolve("oef-schemas");
        Files.createDirectories(schemaDir);
        String schema = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                  targetNamespace="http://www.opengroup.org/xsd/archimate/3.0/"
                  xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
                  elementFormDefault="qualified"
                  attributeFormDefault="unqualified">
                  <xs:element name="model">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/>
                      </xs:sequence>
                      <xs:attribute name="identifier" type="xs:ID" use="required"/>
                      <xs:anyAttribute namespace="##any" processContents="lax"/>
                    </xs:complexType>
                  </xs:element>
                  <xs:complexType name="ApplicationComponent" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="ApplicationService" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Grouping" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="AndJunction" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Realization" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Flow" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Composition" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Element" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Relationship" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                  <xs:complexType name="Diagram" mixed="true">
                    <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
                    <xs:anyAttribute namespace="##any" processContents="lax"/>
                  </xs:complexType>
                </xs:schema>
                """;
        for (String fileName : new String[]{
                "archimate3_Model.xsd",
                "archimate3_View.xsd",
                "archimate3_Diagram.xsd"}) {
            Files.writeString(schemaDir.resolve(fileName), schema, StandardCharsets.UTF_8);
        }
        return Map.of("DEDIREN_OEF_SCHEMA_DIR", schemaDir.toString());
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

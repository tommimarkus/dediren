package dev.dediren.plugins.archimateoef;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.testsupport.CommandEnvelopeAssertions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class MainTest {
  @TempDir Path tempDir;

  @Test
  void moduleLoads() {
    assertThat(Main.moduleName()).isEqualTo("archimate-oef-export");
  }

  @Test
  void reportsCapabilities() throws Exception {
    PluginResult result =
        Main.executeForTesting(new String[] {"capabilities"}, "", envWithOefSchemas());

    JsonNode capabilities = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isZero();
    assertThat(capabilities.at("/id").asText()).isEqualTo("archimate-oef");
    assertThat(capabilities.at("/runtime/artifact_kind").asText()).isEqualTo("archimate-oef+xml");
    assertThat(capabilities.at("/runtime/schema_validation/kind").asText())
        .isEqualTo("official-oef-xsd");
    assertThat(capabilities.at("/runtime/schema_validation/validator").asText())
        .isEqualTo("xmllint");
    assertThat(capabilities.at("/capabilities").toString()).contains("export");
  }

  @Test
  void outputsModelValidOefXml() throws Exception {
    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            exportInput(
                fixtureJson("fixtures/source/valid-archimate-oef.json"),
                fixtureJson("fixtures/layout-result/archimate-oef-basic.json")),
            envWithOefSchemas());

    JsonNode data = okData(result);

    assertThat(result.exitCode()).isZero();
    assertThat(data.at("/artifact_kind").asText()).isEqualTo("archimate-oef+xml");
    // Regression backstop only; the spec-named assertions in the sibling tests are the primary
    // oracle. Update this golden via a reviewed baseline refresh when the OEF contract changes
    // intentionally.
    assertThat(data.at("/content").asText()).isEqualTo(fixture("fixtures/export/oef-basic.xml"));
  }

  @Test
  void missingOefSchemaValidatorIsStructured() throws Exception {
    Map<String, String> env = new java.util.HashMap<>(envWithOefSchemas());
    env.put("DEDIREN_OEF_SCHEMA_VALIDATOR", tempDir.resolve("no-such-validator").toString());

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            exportInput(
                fixtureJson("fixtures/source/valid-archimate-oef.json"),
                fixtureJson("fixtures/layout-result/archimate-oef-basic.json")),
            env);

    CommandEnvelopeAssertions.assertErrorCode(
        result.stdout(), DiagnosticCode.OEF_SCHEMA_VALIDATOR_UNAVAILABLE.code());
  }

  @Test
  void schemaDownloadFailureNamesProxyAndOfflineRemediation() throws Exception {
    // Issue #35: the OEF schema download runs curl in the plugin child. When it cannot fetch the
    // schema (proxied/sandboxed environment), DEDIREN_OEF_SCHEMA_UNAVAILABLE must name both
    // remediations agents can self-serve from stdout JSON alone: expose proxy env to the plugin,
    // or pre-fetch the XSDs and point DEDIREN_OEF_SCHEMA_DIR at them. Force the download path
    // (DEDIREN_OEF_SCHEMA_DIR unset) to fail deterministically without a network by pointing the
    // cache dir under a regular file so the cache directory cannot be created.
    Path blocker = tempDir.resolve("not-a-directory");
    Files.writeString(blocker, "x", StandardCharsets.UTF_8);
    Map<String, String> env =
        Map.of("DEDIREN_SCHEMA_CACHE_DIR", blocker.resolve("cache").toString());

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            exportInput(
                fixtureJson("fixtures/source/valid-archimate-oef.json"),
                fixtureJson("fixtures/layout-result/archimate-oef-basic.json")),
            env);

    JsonNode diagnostic = JsonSupport.objectMapper().readTree(result.stdout()).at("/diagnostics/0");
    assertThat(diagnostic.at("/code").asText()).isEqualTo("DEDIREN_OEF_SCHEMA_UNAVAILABLE");
    assertThat(diagnostic.at("/message").asText())
        .contains("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "DEDIREN_OEF_SCHEMA_DIR");
  }

  @Test
  void emitsSemanticGroupingViewNodeAndIgnoresLayoutOnlyGroup() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    ((ArrayNode) source.get("nodes"))
        .addObject()
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
  void emitsAttachmentsForRouteEndpointsAndBendpointsOnlyForIntermediatePoints() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    ((ArrayNode) source.get("nodes"))
        .addObject()
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
    assertThat(xml)
        .contains(
            "<sourceAttachment x=\"220\" y=\"81\"/><bendpoint x=\"261\" y=\"80\"/>"
                + "<targetAttachment x=\"300\" y=\"80\"/>");
    assertThat(xml)
        .doesNotContain("<bendpoint x=\"220\" y=\"81\"/>", "<bendpoint x=\"300\" y=\"80\"/>");
  }

  @Test
  void emitsSourceAndTargetAttachmentsForOnePointRoute() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    JsonNode layout = fixtureJson("fixtures/layout-result/archimate-oef-basic.json");
    ArrayNode points = (ArrayNode) layout.at("/edges/0/points");
    points.remove(1);
    ((ObjectNode) points.get(0)).put("x", 220.6);
    ((ObjectNode) points.get(0)).put("y", 80.2);

    String xml = exportXml(source, layout);

    assertThat(xml)
        .contains(
            "<sourceAttachment x=\"221\" y=\"80\"/>" + "<targetAttachment x=\"221\" y=\"80\"/>");
    assertThat(xml).doesNotContain("<bendpoint ");
  }

  @Test
  void emitsNoConnectionGeometryForEmptyRoute() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    JsonNode layout = fixtureJson("fixtures/layout-result/archimate-oef-basic.json");
    ((ArrayNode) layout.at("/edges/0/points")).removeAll();

    String connection = connectionXml(exportXml(source, layout));

    assertThat(connection).doesNotContain("sourceAttachment", "bendpoint", "targetAttachment");
  }

  @Test
  void rejectsLayoutNodeWhoseSourceIdDoesNotResolveToSourceNode() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    JsonNode layout = fixtureJson("fixtures/layout-result/archimate-oef-basic.json");
    ((ObjectNode) layout.at("/nodes/0")).put("id", "node-customer");
    ((ObjectNode) layout.at("/nodes/0")).put("source_id", "missing-source-node");

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"}, exportInput(source, layout), envWithOefSchemas());

    assertThat(result.exitCode()).isEqualTo(3);
    assertErrorCode(result, "DEDIREN_OEF_LAYOUT_REFERENCE_MISSING");
    assertThat(result.stdout())
        .contains(
            "Layout node 'node-customer' references missing source node 'missing-source-node'")
        .contains("while exporting ArchiMate OEF")
        .contains("$.layout_result.nodes[0].source_id");
  }

  @Test
  void rejectsLayoutEdgeWhoseRelationshipRefDoesNotResolveToSourceRelationship() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    JsonNode layout = fixtureJson("fixtures/layout-result/archimate-oef-basic.json");
    ((ObjectNode) layout.at("/edges/0")).put("id", "rel-serve");
    ((ObjectNode) layout.at("/edges/0")).put("source_id", "missing-relationship");

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"}, exportInput(source, layout), envWithOefSchemas());

    assertThat(result.exitCode()).isEqualTo(3);
    assertErrorCode(result, "DEDIREN_OEF_LAYOUT_REFERENCE_MISSING");
    assertThat(result.stdout())
        .contains(
            "Layout edge 'rel-serve' references missing source relationship 'missing-relationship'")
        .contains("while exporting ArchiMate OEF")
        .contains("$.layout_result.edges[0].source_id");
  }

  @Test
  void rejectsUnknownArchimateNodeTypeWithErrorEnvelope() throws Exception {
    JsonNode input = exportInputJson();
    ((ObjectNode) input.at("/source/nodes/0")).put("type", "TechnologyNode");

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
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

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
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

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            JsonSupport.objectMapper().writeValueAsString(input),
            envWithOefSchemas());

    assertThat(result.exitCode()).isEqualTo(3);
    assertErrorCode(result, "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
    assertThat(result.stdout())
        .contains("ApplicationService", "Realization", "ApplicationComponent");
  }

  @Test
  void emitsArchimateRelationshipConnectorJunctions() throws Exception {
    String xml =
        exportXml(
            JsonSupport.objectMapper()
                .readTree(
                    """
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "2026.07.2" },
                    { "id": "archimate-oef", "version": "2026.07.2" }
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
            JsonSupport.objectMapper()
                .readTree(
                    """
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

    assertThat(xml)
        .contains(
            "id-el-flow-junction",
            "xsi:type=\"AndJunction\"",
            "id-vn-main-flow-junction",
            "relationshipRef=\"id-rel-api-to-junction\"",
            "target=\"id-vn-main-flow-junction\"",
            "source=\"id-vn-main-flow-junction\"");
  }

  @Test
  void allowsJunctionContainmentRelationship() throws Exception {
    String xml =
        exportXml(
            JsonSupport.objectMapper()
                .readTree(
                    """
                {
                  "model_schema_version": "model.schema.v1",
                  "required_plugins": [
                    { "id": "generic-graph", "version": "2026.07.2" },
                    { "id": "archimate-oef", "version": "2026.07.2" }
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
            JsonSupport.objectMapper()
                .readTree(
                    """
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
  void rejectsGroupWhoseSourceNodeIsNotGroupingType() throws Exception {
    JsonNode input = exportInputJson();
    // Add an ApplicationComponent node as the semantic source of a layout group
    ((ArrayNode) input.at("/source/nodes"))
        .addObject()
        .put("id", "not-a-grouping")
        .put("type", "ApplicationComponent")
        .put("label", "Not A Grouping")
        .set("properties", JsonSupport.objectMapper().createObjectNode());
    JsonNode layout = input.get("layout_result");
    var groups = JsonSupport.objectMapper().createArrayNode();
    groups
        .addObject()
        .put("id", "semantic-group-bad-source")
        .put("source_id", "not-a-grouping")
        .put("projection_id", "semantic-group-bad-source")
        .set(
            "provenance",
            JsonSupport.objectMapper()
                .createObjectNode()
                .set(
                    "semantic_backed",
                    JsonSupport.objectMapper()
                        .createObjectNode()
                        .put("source_id", "not-a-grouping")));
    ((ObjectNode) groups.get(0))
        .put("x", 0.0)
        .put("y", 0.0)
        .put("width", 200.0)
        .put("height", 100.0)
        .put("label", "Not A Grouping")
        .set("members", JsonSupport.objectMapper().createArrayNode());
    ((ObjectNode) layout).set("groups", groups);

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            JsonSupport.objectMapper().writeValueAsString(input),
            envWithOefSchemas());

    assertThat(result.exitCode()).isEqualTo(3);
    assertErrorCode(result, "DEDIREN_ARCHIMATE_GROUP_SOURCE_NOT_GROUPING");
    assertThat(result.stdout()).contains("not-a-grouping", "ApplicationComponent");
  }

  @Test
  void rejectsInvalidPolicyWithErrorEnvelope() throws Exception {
    JsonNode input = exportInputJson();
    ((ObjectNode) input.get("policy")).remove("model_identifier");

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
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

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            JsonSupport.objectMapper().writeValueAsString(input),
            envWithOefSchemas());

    assertThat(result.exitCode()).isEqualTo(3);
    assertErrorCode(result, "DEDIREN_OEF_SCHEMA_INVALID");
    assertThat(result.stdout()).contains("official OEF schema");
  }

  @Test
  void declaresSchemaLocationAgainstDiagramSchemaNotModelSchema() throws Exception {
    String xml =
        exportXml(
            fixtureJson("fixtures/source/valid-archimate-oef.json"),
            fixtureJson("fixtures/layout-result/archimate-oef-basic.json"));

    // The document carries <views>/<diagrams>, which archimate3_Model.xsd rejects; it must declare
    // the diagram-bearing schema it actually validates against (issue #34, gap 3).
    assertThat(xml)
        .contains(
            "xsi:schemaLocation=\"http://www.opengroup.org/xsd/archimate/3.0/"
                + " http://www.opengroup.org/xsd/archimate/3.1/archimate3_Diagram.xsd\"");
    assertThat(xml).doesNotContain("archimate3_Model.xsd");
  }

  @Test
  void preservesNodeAndRelationshipPropertiesViaOefPropertyDefinitions() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    ObjectNode componentProperties = (ObjectNode) source.at("/nodes/0/properties");
    componentProperties.put("evidence-classification", "candidate-from-source");
    componentProperties.put("confidence", 0.4);
    ((ObjectNode) source.at("/relationships/0/properties")).put("source-path", "src/orders/mod.rs");

    String xml = exportXml(source, fixtureJson("fixtures/layout-result/archimate-oef-basic.json"));

    // Each distinct key becomes one model-level property definition (sorted for determinism).
    assertThat(xml)
        .contains(
            "<propertyDefinitions>"
                + "<propertyDefinition identifier=\"id-prop-confidence\" type=\"string\">"
                + "<name xml:lang=\"en\">confidence</name></propertyDefinition>"
                + "<propertyDefinition identifier=\"id-prop-evidence-classification\""
                + " type=\"string\">"
                + "<name xml:lang=\"en\">evidence-classification</name></propertyDefinition>"
                + "<propertyDefinition identifier=\"id-prop-source-path\" type=\"string\">"
                + "<name xml:lang=\"en\">source-path</name></propertyDefinition>"
                + "</propertyDefinitions>");
    // The element references its property definitions and carries the values (issue #34, gap 2).
    assertThat(xml)
        .contains(
            "<element identifier=\"id-el-orders-component\" xsi:type=\"ApplicationComponent\">"
                + "<name xml:lang=\"en\">Orders Component</name>"
                + "<properties>"
                + "<property propertyDefinitionRef=\"id-prop-confidence\">"
                + "<value xml:lang=\"en\">0.4</value></property>"
                + "<property propertyDefinitionRef=\"id-prop-evidence-classification\">"
                + "<value xml:lang=\"en\">candidate-from-source</value></property>"
                + "</properties></element>");
    assertThat(xml)
        .contains(
            "<properties><property propertyDefinitionRef=\"id-prop-source-path\">"
                + "<value xml:lang=\"en\">src/orders/mod.rs</value></property></properties>"
                + "</relationship>");
  }

  @Test
  void emitsNoPropertyDefinitionsWhenSourceHasNoProperties() throws Exception {
    String xml =
        exportXml(
            fixtureJson("fixtures/source/valid-archimate-oef.json"),
            fixtureJson("fixtures/layout-result/archimate-oef-basic.json"));

    assertThat(xml).doesNotContain("<propertyDefinitions>", "<properties>");
  }

  @Test
  void declaresOmittedViewsWithInfoDiagnosticWhileStatusStaysOk() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    ArrayNode views = (ArrayNode) source.at("/plugins/generic-graph/views");
    views
        .addObject()
        .put("id", "detail")
        .put("label", "Detail")
        .set("nodes", JsonSupport.objectMapper().createArrayNode().add("orders-service"));

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            exportInput(source, fixtureJson("fixtures/layout-result/archimate-oef-basic.json")),
            envWithOefSchemas());

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/content").asText()).contains("<view identifier=\"id-view-main\"");
    JsonNode diagnostic = envelope.at("/diagnostics/0");
    assertThat(diagnostic.at("/code").asText()).isEqualTo("DEDIREN_OEF_VIEWS_OMITTED");
    assertThat(diagnostic.at("/severity").asText()).isEqualTo("info");
    assertThat(diagnostic.at("/path").asText()).isEqualTo("source.plugins.generic-graph.views");
    assertThat(diagnostic.at("/message").asText())
        .contains("1 of 2 ArchiMate views", "omitted: detail", "single laid-out view 'main'");
  }

  @Test
  void singleDeclaredViewEmitsNoViewCoverageDiagnostic() throws Exception {
    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            exportInput(
                fixtureJson("fixtures/source/valid-archimate-oef.json"),
                fixtureJson("fixtures/layout-result/archimate-oef-basic.json")),
            envWithOefSchemas());

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/diagnostics")).isEmpty();
  }

  @Test
  void declaresOmissionWhenTheSingleDeclaredViewIsNotTheExportedView() throws Exception {
    // Exactly one declared view, but its id ('detail') is not the exported view ('main'): the
    // exported diagram is not the declared one, so the loss must still be declared, not suppressed
    // by a view-count short-circuit (issue #34).
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    ((ObjectNode) source.at("/plugins/generic-graph/views/0")).put("id", "detail");

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            exportInput(source, fixtureJson("fixtures/layout-result/archimate-oef-basic.json")),
            envWithOefSchemas());

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_OEF_VIEWS_OMITTED");
    assertThat(envelope.at("/diagnostics/0/message").asText())
        .contains("1 of 1 ArchiMate views", "omitted: detail", "single laid-out view 'main'");
  }

  @Test
  void listsEveryOmittedViewSortedAndCountedWhenSeveralAreOmitted() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    ArrayNode views = (ArrayNode) source.at("/plugins/generic-graph/views");
    views.addObject().put("id", "detail").put("label", "Detail");
    views.addObject().put("id", "alt").put("label", "Alt");

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            exportInput(source, fixtureJson("fixtures/layout-result/archimate-oef-basic.json")),
            envWithOefSchemas());

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_OEF_VIEWS_OMITTED");
    assertThat(envelope.at("/diagnostics/0/message").asText())
        .contains("2 of 3 ArchiMate views", "omitted: alt, detail");
  }

  @Test
  void toleratesMalformedGenericGraphPluginDataWithoutCrashingOrViewDiagnostic() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    // 'views' as a string cannot deserialize into a view list; the export must degrade to no view
    // diagnostic rather than crash the plugin process.
    ((ObjectNode) source.at("/plugins/generic-graph")).put("views", "not-a-view-array");

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            exportInput(source, fixtureJson("fixtures/layout-result/archimate-oef-basic.json")),
            envWithOefSchemas());

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/diagnostics")).isEmpty();
  }

  @Test
  void escapesXmlMetacharactersInPropertyKeysAndValues() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    ((ObjectNode) source.at("/nodes/0/properties")).put("note<x", "a&b<c");

    String xml = exportXml(source, fixtureJson("fixtures/layout-result/archimate-oef-basic.json"));

    assertThat(xml)
        .contains(
            "<name xml:lang=\"en\">note&lt;x</name>",
            "<value xml:lang=\"en\">a&amp;b&lt;c</value>");
    assertThat(xml).doesNotContain("note<x", "a&b<c");
  }

  @Test
  void emitsElementPropertiesInSortedKeyOrderIndependentOfSourceMapOrder() throws Exception {
    JsonNode source = fixtureJson("fixtures/source/valid-archimate-oef.json");
    ObjectNode properties = (ObjectNode) source.at("/nodes/0/properties");
    properties.put("owner", "team-x");
    properties.put("notes", "n");
    properties.put("source-path", "p");
    properties.put("confidence", "c");

    String xml = exportXml(source, fixtureJson("fixtures/layout-result/archimate-oef-basic.json"));

    assertThat(xml)
        .contains(
            "<properties>"
                + "<property propertyDefinitionRef=\"id-prop-confidence\">"
                + "<value xml:lang=\"en\">c</value></property>"
                + "<property propertyDefinitionRef=\"id-prop-notes\">"
                + "<value xml:lang=\"en\">n</value></property>"
                + "<property propertyDefinitionRef=\"id-prop-owner\">"
                + "<value xml:lang=\"en\">team-x</value></property>"
                + "<property propertyDefinitionRef=\"id-prop-source-path\">"
                + "<value xml:lang=\"en\">p</value></property>"
                + "</properties>");
  }

  private String exportXml(JsonNode source, JsonNode layout) throws Exception {
    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"}, exportInput(source, layout), envWithOefSchemas());
    return okData(result).at("/content").asText();
  }

  private JsonNode exportInputJson() throws Exception {
    return JsonSupport.objectMapper()
        .readTree(
            exportInput(
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
    groups
        .addObject()
        .put("id", "customer-domain-group")
        .put("source_id", "customer-domain")
        .put("projection_id", "customer-domain-group")
        .set(
            "provenance",
            JsonSupport.objectMapper()
                .createObjectNode()
                .set(
                    "semantic_backed",
                    JsonSupport.objectMapper()
                        .createObjectNode()
                        .put("source_id", "customer-domain")));
    ((ObjectNode) groups.get(0))
        .put("x", x)
        .put("y", y)
        .put("width", width)
        .put("height", height)
        .set(
            "members",
            JsonSupport.objectMapper()
                .createArrayNode()
                .add("orders-component")
                .add("orders-service"));
    ((ObjectNode) groups.get(0)).put("label", "Customer Domain");
    groups
        .addObject()
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
    String schema =
        """
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
    for (String fileName :
        new String[] {"archimate3_Model.xsd", "archimate3_View.xsd", "archimate3_Diagram.xsd"}) {
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

  private static String connectionXml(String xml) {
    int start = xml.indexOf("<connection ");
    int end = xml.indexOf("</connection>", start) + "</connection>".length();
    return xml.substring(start, end);
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

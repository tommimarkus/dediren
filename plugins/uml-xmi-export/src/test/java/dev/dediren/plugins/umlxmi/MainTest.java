package dev.dediren.plugins.umlxmi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.plugins.umlxmi.schema.SchemaValidation;
import dev.dediren.testsupport.CommandEnvelopeAssertions;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXParseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class MainTest {
  @TempDir Path tempDir;

  @Test
  void moduleLoads() {
    assertThat(Main.moduleName()).isEqualTo("uml-xmi-export");
  }

  @Test
  void outputsXmi() throws Exception {
    String xml = exportXml(exportInput());

    assertThat(xml)
        .containsSubsequence(
            "<uml:Model", "xmi:type=\"uml:Class\"", "<ownedAttribute", "<ownedOperation");
    // Regression backstop only; the spec-named assertions above are the primary oracle.
    // Update this golden via a reviewed baseline refresh when the XMI contract changes
    // intentionally.
    assertThat(xml).isEqualTo(fixture("fixtures/export/uml-basic.xmi"));
    assertThat(xml).doesNotContain("xmi:version", "uml:Activity");
  }

  @Test
  void resolvesAttributeTypesToInDocumentIdsWithNoDanglingStrings() throws Exception {
    String xml = exportXml(exportInput());

    // A domain type present in the view resolves to that element's xmi:id; primitives and domain
    // types absent from the view resolve to synthesized packagedElement ids (issue #33 defect 1).
    assertThat(xml)
        .contains(
            "type=\"id-enum-order-status\"", // OrderStatus -> the emitted Enumeration
            "type=\"id-datatype-orderid\"", // OrderId -> synthesized uml:DataType
            "type=\"id-primitive-string\"", // String -> synthesized uml:PrimitiveType
            "type=\"id-primitive-integer\"", // Integer -> synthesized uml:PrimitiveType
            "<packagedElement xmi:type=\"uml:DataType\" xmi:id=\"id-datatype-orderid\""
                + " name=\"OrderId\"/>",
            "<packagedElement xmi:type=\"uml:PrimitiveType\" xmi:id=\"id-primitive-string\""
                + " name=\"String\"/>",
            "<packagedElement xmi:type=\"uml:PrimitiveType\" xmi:id=\"id-primitive-integer\""
                + " name=\"Integer\"/>");
    // No attribute type survives as a dangling type-name string reference.
    assertThat(xml)
        .doesNotContain(
            "type=\"OrderId\"", "type=\"OrderStatus\"", "type=\"String\"", "type=\"Integer\"");
  }

  @Test
  void mapsPrimitiveAliasesToUmlStandardPrimitiveTypes() throws Exception {
    JsonNode input = exportInput();
    // OrderLine.sku/quantity are nodes[3].attributes[0]/[1]; retype to the Java-ish aliases.
    ((ObjectNode) input.at("/source/nodes/3/properties/uml/attributes/0")).put("type", "boolean");
    ((ObjectNode) input.at("/source/nodes/3/properties/uml/attributes/1")).put("type", "int");

    String xml = exportXml(input);

    assertThat(xml)
        .contains(
            "type=\"id-primitive-integer\"",
            "<packagedElement xmi:type=\"uml:PrimitiveType\" xmi:id=\"id-primitive-integer\""
                + " name=\"Integer\"/>",
            "type=\"id-primitive-boolean\"",
            "<packagedElement xmi:type=\"uml:PrimitiveType\" xmi:id=\"id-primitive-boolean\""
                + " name=\"Boolean\"/>");
    assertThat(xml)
        .doesNotContain("type=\"int\"", "name=\"int\"", "type=\"boolean\"", "name=\"boolean\"");
  }

  @Test
  void serializesAttributeMultiplicitiesAsOwnedValueSpecificationElements() throws Exception {
    String xml = exportXml(exportInput());

    // Multiplicities are owned LiteralInteger/LiteralUnlimitedNatural children, not XML attributes
    // on ownedAttribute (issue #33 defect 2).
    assertThat(xml)
        .contains(
            "<lowerValue xmi:type=\"uml:LiteralInteger\" xmi:id=\"id-class-order-id-lower\""
                + " value=\"1\"/>",
            "<upperValue xmi:type=\"uml:LiteralUnlimitedNatural\""
                + " xmi:id=\"id-class-order-id-upper\" value=\"1\"/>");
    assertThat(xml).doesNotContain("lowerValue=\"", "upperValue=\"");
  }

  @Test
  void serializesAssociationEndMultiplicitiesAsOwnedValueSpecificationsIncludingUnlimited()
      throws Exception {
    String xml = exportXml(exportInput());

    // The composition part end is 1..*, so its upperValue is LiteralUnlimitedNatural "*".
    assertThat(xml)
        .containsSubsequence(
            "<ownedEnd xmi:id=\"id-order-has-lines-target-end\"",
            "<lowerValue xmi:type=\"uml:LiteralInteger\""
                + " xmi:id=\"id-order-has-lines-target-end-lower\" value=\"1\"/>",
            "<upperValue xmi:type=\"uml:LiteralUnlimitedNatural\""
                + " xmi:id=\"id-order-has-lines-target-end-upper\" value=\"*\"/>",
            "</ownedEnd>");
  }

  @Test
  void validatesUmlContentAgainstSuppliedUmlDeclaringSchemaSet() throws Exception {
    // The plugin accepts DEDIREN_XMI_SCHEMA_PATH pointing at a schema (set). Supplying a schema
    // that also declares the UML namespace elements makes UML-content validation possible: xmllint
    // then resolves the emitted uml:* elements against real global declarations under a strict
    // wildcard instead of skipping them. This exercises the documented recipe end to end against
    // the canonical serialization (issue #33 defect 3).
    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"}, exportInput().toString(), envWithXmiAndUmlSchema());

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
  }

  @Test
  void missingXmiSchemaValidatorIsStructured() throws Exception {
    Map<String, String> env = new java.util.HashMap<>(envWithXmiSchema());
    env.put("DEDIREN_XMI_SCHEMA_VALIDATOR", tempDir.resolve("no-such-validator").toString());

    PluginResult result =
        Main.executeForTesting(new String[] {"export"}, exportInput().toString(), env);

    CommandEnvelopeAssertions.assertErrorCode(
        result.stdout(), DiagnosticCode.XMI_SCHEMA_VALIDATOR_UNAVAILABLE.code());
  }

  @Test
  void schemaDownloadFailureNamesProxyAndOfflineRemediation() throws Exception {
    // Issue #35: the XMI schema download runs curl in the plugin child. When it cannot fetch the
    // schema (proxied/sandboxed environment), DEDIREN_XMI_SCHEMA_UNAVAILABLE must name both
    // remediations agents can self-serve from stdout JSON alone: expose proxy env to the plugin,
    // or pre-fetch XMI.xsd and point DEDIREN_XMI_SCHEMA_PATH at it. Force the download path
    // (DEDIREN_XMI_SCHEMA_PATH unset) to fail deterministically without a network by pointing the
    // cache dir under a regular file so the cache directory cannot be created.
    Path blocker = tempDir.resolve("not-a-directory");
    Files.writeString(blocker, "x", StandardCharsets.UTF_8);
    Map<String, String> env =
        Map.of("DEDIREN_SCHEMA_CACHE_DIR", blocker.resolve("cache").toString());

    PluginResult result =
        Main.executeForTesting(new String[] {"export"}, exportInput().toString(), env);

    JsonNode diagnostic = JsonSupport.objectMapper().readTree(result.stdout()).at("/diagnostics/0");
    assertThat(diagnostic.at("/code").asText()).isEqualTo("DEDIREN_XMI_SCHEMA_UNAVAILABLE");
    assertThat(diagnostic.at("/message").asText())
        .contains("HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "DEDIREN_XMI_SCHEMA_PATH");
  }

  @Test
  void scopesModelToLayoutView() throws Exception {
    String xml = exportXml(exportInput());

    assertThat(xml).contains("xmi:type=\"uml:Class\"");
    assertThat(xml).doesNotContain("activity-submit-order", "xmi:type=\"uml:Activity\"");
  }

  @Test
  void emitsInterfaceInScopedClassView() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-complex.json"),
            fixtureJson("fixtures/layout-result/uml-complex-class.json"));

    String xml = exportXml(input);

    assertThat(xml).contains("xmi:type=\"uml:Interface\"", "PaymentGateway");
    assertThat(xml).doesNotContain("Fulfill Order");
  }

  @Test
  void deduplicatesSynthesizedTypesAndResolvesInScopeDomainTypesToTheirElements() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-complex.json"),
            fixtureJson("fixtures/layout-result/uml-complex-class.json"));

    String xml = exportXml(input);

    // A synthesized primitive is declared exactly once no matter how many attributes reference it
    // (String is used by nine attributes here), and an in-scope domain DataType resolves to its
    // emitted element rather than being re-synthesized as a duplicate (issue #33 defect 1). These
    // invariants would otherwise be guarded only by the golden fixture.
    assertThat(xml)
        .containsOnlyOnce(
            "<packagedElement xmi:type=\"uml:PrimitiveType\" xmi:id=\"id-primitive-string\""
                + " name=\"String\"/>");
    assertThat(xml)
        .contains("type=\"id-datatype-order-id\""); // OrderId -> its emitted uml:DataType
    assertThat(xml).doesNotContain("id-datatype-orderid"); // no synthesized OrderId duplicate
  }

  @Test
  void emitsClassRelationshipsAsAssociationsDependenciesAndRealizations() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-complex.json"),
            fixtureJson("fixtures/layout-result/uml-complex-class.json"));

    String xml = exportXml(input);

    assertThat(xml)
        .contains(
            // Composition order-has-lines: composite aggregation on the part (target) end.
            "xmi:type=\"uml:Association\"",
            "aggregation=\"composite\"",
            // Aggregation order-has-payment: shared aggregation.
            "aggregation=\"shared\"",
            // Association customer-places-order with roles and multiplicities.
            "<ownedEnd",
            "name=\"customer\"",
            "name=\"orders\"",
            "<upperValue xmi:type=\"uml:LiteralUnlimitedNatural\"",
            // Dependency and Realization between classifiers.
            "xmi:type=\"uml:Dependency\"",
            "xmi:type=\"uml:Realization\"",
            "client=\"id-class-card-payment\" supplier=\"id-interface-payment-gateway\"");
  }

  @Test
  void nestsClassifiersUnderTheirOwningPackage() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-complex.json"),
            fixtureJson("fixtures/layout-result/uml-complex-class.json"));

    String xml = exportXml(input);

    // The Commerce package is no longer an empty sibling; its classifiers nest inside it.
    assertThat(xml).doesNotContain("xmi:id=\"id-pkg-commerce\" name=\"Commerce\"/>");
    assertThat(xml)
        .containsSubsequence(
            "<packagedElement xmi:type=\"uml:Package\" xmi:id=\"id-pkg-commerce\" name=\"Commerce\">",
            "xmi:type=\"uml:Class\" xmi:id=\"id-class-customer\"",
            "</packagedElement>",
            "<packagedElement xmi:type=\"uml:Package\" xmi:id=\"id-pkg-fulfillment\""
                + " name=\"Fulfillment\">",
            "xmi:type=\"uml:Class\" xmi:id=\"id-class-shipment\"");
  }

  @Test
  void declaresOutOfViewContentAsInfoCoverageDiagnostics() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-complex.json"),
            fixtureJson("fixtures/layout-result/uml-complex-class.json"));

    PluginResult result =
        Main.executeForTesting(new String[] {"export"}, input.toString(), envWithXmiSchema());

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isZero();
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    JsonNode elements = diagnostic(envelope, "DEDIREN_XMI_ELEMENTS_OMITTED");
    assertThat(elements.at("/severity").asText()).isEqualTo("info");
    assertThat(elements.at("/path").asText()).isEqualTo("source.nodes");
    assertThat(elements.at("/message").asText()).contains("Activity=1", "Action=4");
    JsonNode relationships = diagnostic(envelope, "DEDIREN_XMI_RELATIONSHIPS_OMITTED");
    assertThat(relationships.at("/severity").asText()).isEqualTo("info");
    assertThat(relationships.at("/message").asText()).contains("ControlFlow=8", "ObjectFlow=2");
  }

  @Test
  void singleViewExportWithoutOmissionsHasNoCoverageDiagnostics() throws Exception {
    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"}, exportSequenceInput().toString(), envWithXmiSchema());

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/diagnostics").size()).isZero();
  }

  @Test
  void exportsComplexClassViewGolden() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-complex.json"),
            fixtureJson("fixtures/layout-result/uml-complex-class.json"));

    String xml = exportXml(input);

    // Regression backstop only; the spec-named assertions in the sibling tests are the primary
    // oracle. Update this golden via a reviewed baseline refresh when the XMI contract changes
    // intentionally.
    assertThat(xml).isEqualTo(fixture("fixtures/export/uml-complex-class.xmi"));
  }

  @Test
  void emitsActivityNodesAndFlowsForActivityView() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-basic.json"),
            fixtureJson("fixtures/layout-result/uml-activity.json"));

    String xml = exportXml(input);

    assertThat(xml)
        .contains(
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
    assertThat(xml)
        .contains(
            "xmi:type=\"uml:Interaction\"",
            "<lifeline xmi:id=\"id-customer\" name=\"Customer\"/>",
            "<lifeline xmi:id=\"id-service\" name=\"Order Service\"/>",
            "<message xmi:id=\"id-m1\" name=\"placeOrder\" messageSort=\"synchCall\""
                + " sendEvent=\"id-m1-send-event\" receiveEvent=\"id-m1-receive-event\"/>");
  }

  @Test
  void exportsUmlSequenceCombinedFragments() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-sequence-fragments.json"),
            fixtureJson("fixtures/layout-result/uml-sequence-fragments.json"));

    PluginResult result =
        Main.executeForTesting(new String[] {"export"}, input.toString(), envWithXmiSchema());

    String xml = okData(result).at("/content").asText();
    assertThat(xml)
        .containsSubsequence(
            "<uml:Model",
            "xmi:type=\"uml:CombinedFragment\"",
            "interactionOperator=\"alt\"",
            "<operand");
    // Regression backstop only; the spec-named assertions above are the primary oracle.
    // Update this golden via a reviewed baseline refresh when the XMI contract changes
    // intentionally.
    assertThat(xml).isEqualTo(fixture("fixtures/export/uml-sequence-fragments.xmi"));
  }

  @Test
  void exportsStateMachineRegionVerticesAndTransitions() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-state-machine-basic.json"),
            fixtureJson("fixtures/layout-result/uml-state-machine-basic.json"));

    String xml = exportXml(input);

    assertThat(xml).isEqualTo(fixture("fixtures/export/uml-state-machine-basic.xmi"));
    assertThat(xml)
        .contains(
            "xmi:type=\"uml:StateMachine\"",
            "<region xmi:id=\"id-main-region\" name=\"Main Region\"",
            "xmi:type=\"uml:Pseudostate\"",
            "kind=\"choice\"",
            "xmi:type=\"uml:FinalState\"",
            "<transition xmi:id=\"id-t-submit\"");
  }

  @Test
  void rejectsSelectedStateMachineTransitionWithoutRegion() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-state-machine-basic.json"),
            fixtureJson("fixtures/layout-result/uml-state-machine-basic.json"));
    ((ObjectNode) input.at("/source/relationships/0/properties/uml")).remove("region");

    exportExpectingError(
        input,
        "DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID",
        "$.relationships[0].properties.uml.region");
  }

  @Test
  void exportsStateMachineFrameNodesFromSemanticBackedGroups() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-state-machine-basic.json"),
            fixtureJson("fixtures/layout-result/uml-state-machine-basic.json"));

    String xml = exportXml(input);

    assertThat(xml).contains("xmi:id=\"id-order-lifecycle\"", "xmi:id=\"id-main-region\"");
  }

  @Test
  void exportsUseCaseActorsUseCasesAndRelationships() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-use-case-basic.json"),
            fixtureJson("fixtures/layout-result/uml-use-case-basic.json"));

    String xml = exportXml(input);

    assertThat(xml).isEqualTo(fixture("fixtures/export/uml-use-case-basic.xmi"));
    assertThat(xml)
        .contains(
            "xmi:type=\"uml:Actor\"",
            "xmi:type=\"uml:UseCase\"",
            "subject=\"id-order-service\"",
            "<extensionPoint xmi:id=\"id-payment-extension\" name=\"payment authorized\"/>",
            "<include xmi:id=\"id-include-authentication\" name=\"include\" addition=\"id-authenticate-customer\"/>",
            "<extend xmi:id=\"id-extend-discount\" name=\"extend\""
                + " extendedCase=\"id-place-order\" extensionLocation=\"id-payment-extension\"/>");
    assertThat(xml).doesNotContain("xmi:type=\"uml:ExtensionPoint\"");
  }

  @Test
  void rejectsUseCaseExtendWithExtensionPointOwnedByAnotherUseCase() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-use-case-basic.json"),
            fixtureJson("fixtures/layout-result/uml-use-case-basic.json"));
    ((ObjectNode) input.at("/source/nodes/8/properties/uml")).put("use_case", "track-order");

    exportExpectingError(
        input,
        "DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID",
        "$.relationships[4].properties.uml.extension_point");
  }

  @Test
  void exportsComponentPortsAndRelationships() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-component-basic.json"),
            fixtureJson("fixtures/layout-result/uml-component-basic.json"));

    String xml = exportXml(input);

    assertThat(xml).isEqualTo(fixture("fixtures/export/uml-component-basic.xmi"));
    assertThat(xml)
        .contains(
            "xmi:type=\"uml:Component\"",
            "xmi:type=\"uml:Port\"",
            "provided=\"id-interface-order-api\"",
            "required=\"id-interface-payment-gateway\"",
            "xmi:type=\"uml:Usage\"",
            "xmi:type=\"uml:Realization\"",
            "client=\"id-component-order-api\" supplier=\"id-interface-payment-gateway\"");
  }

  @Test
  void rejectsComponentPortOwnedByNonComponent() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-component-basic.json"),
            fixtureJson("fixtures/layout-result/uml-component-basic.json"));
    ((ObjectNode) input.at("/source/nodes/2/properties/uml"))
        .put("component", "interface-order-api");

    exportExpectingError(
        input, "DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED", "$.nodes[2].properties.uml.component");
  }

  @Test
  void exportsDeploymentTargetsArtifactsAndRelationships() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-deployment-basic.json"),
            fixtureJson("fixtures/layout-result/uml-deployment-basic.json"));

    String xml = exportXml(input);

    assertThat(xml).isEqualTo(fixture("fixtures/export/uml-deployment-basic.xmi"));
    assertThat(xml)
        .contains(
            "xmi:type=\"uml:Device\"",
            "xmi:type=\"uml:ExecutionEnvironment\"",
            "xmi:type=\"uml:Node\"",
            "xmi:type=\"uml:Artifact\"",
            "xmi:type=\"uml:DeploymentSpecification\"",
            "xmi:type=\"uml:Deployment\"",
            "deployedArtifact=\"id-artifact-orders-service\"",
            "location=\"id-ee-orders-runtime\"",
            "xmi:type=\"uml:Manifestation\"",
            "utilizedElement=\"id-component-order-api\"",
            "xmi:type=\"uml:CommunicationPath\"",
            "endType=\"id-ee-orders-runtime id-node-payment-network\"");
  }

  @Test
  void rejectsDeploymentWithNonArtifactSource() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-deployment-basic.json"),
            fixtureJson("fixtures/layout-result/uml-deployment-basic.json"));
    ((ObjectNode) input.at("/source/relationships/0")).put("source", "component-order-api");

    exportExpectingError(
        input, "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED", "$.relationships[0]");
  }

  @Test
  void keepsSequenceViewRelationshipsScopedToLayoutEdges() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-sequence-fragments.json"),
            fixtureJson("fixtures/layout-result/uml-sequence-fragments.json"));
    ((tools.jackson.databind.node.ArrayNode) input.at("/source/relationships"))
        .add(
            JsonSupport.objectMapper()
                .readTree(
                    """
                {
                  "id": "m-unlaid",
                  "type": "Message",
                  "source": "customer",
                  "target": "service",
                  "label": "notInLayout",
                  "properties": {
                    "uml": {
                      "interaction": "interaction-place-order",
                      "sequence": 13,
                      "message_sort": "synchCall"
                    }
                  }
                }
                """));
    ((tools.jackson.databind.node.ArrayNode)
            input.at("/source/plugins/generic-graph/views/0/relationships"))
        .add("m-unlaid");

    String xml = exportXml(input);

    assertThat(xml).doesNotContain("id-m-unlaid", "notInLayout");
  }

  @Test
  void interleavesTopLevelCombinedFragmentsAndStandaloneMessagesBySequence() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-sequence-fragments.json"),
            fixtureJson("fixtures/layout-result/uml-sequence-fragments.json"));
    var relationships = (tools.jackson.databind.node.ArrayNode) input.at("/source/relationships");
    for (int index = 4; index < relationships.size(); index++) {
      ObjectNode uml = (ObjectNode) relationships.get(index).get("properties").get("uml");
      uml.put("sequence", uml.get("sequence").intValue() + 1);
    }
    relationships.add(
        JsonSupport.objectMapper()
            .readTree(
                """
                {
                  "id": "m-between-fragments",
                  "type": "Message",
                  "source": "service",
                  "target": "payment",
                  "label": "authorizeStandalone",
                  "properties": {
                    "uml": {
                      "interaction": "interaction-place-order",
                      "sequence": 5,
                      "message_sort": "synchCall"
                    }
                  }
                }
                """));
    ((tools.jackson.databind.node.ArrayNode) input.at("/layout_result/edges"))
        .add(
            JsonSupport.objectMapper()
                .readTree(
                    """
                {
                  "id": "m-between-fragments",
                  "source": "service",
                  "target": "payment",
                  "source_id": "m-between-fragments",
                  "projection_id": "m-between-fragments",
                  "points": [
                    { "x": 394, "y": 360 },
                    { "x": 850, "y": 360 }
                  ],
                  "label": "authorizeStandalone"
                }
                """));

    String xml = exportXml(input);

    assertThat(xml)
        .containsSubsequence(
            "<fragment xmi:type=\"uml:CombinedFragment\" xmi:id=\"id-cf-availability\"",
            "xmi:id=\"id-m-between-fragments-send-event\"",
            "<fragment xmi:type=\"uml:CombinedFragment\" xmi:id=\"id-cf-coupon\"");
  }

  @Test
  void nestsCombinedFragmentsReferencedByOperandFragments() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-sequence-fragments.json"),
            fixtureJson("fixtures/layout-result/uml-sequence-fragments.json"));
    ((tools.jackson.databind.node.ArrayNode) input.at("/source/nodes/7/properties/uml/fragments"))
        .add("cf-coupon");

    String xml = exportXml(input);

    assertThat(xml).containsOnlyOnce("xmi:id=\"id-cf-coupon\"");
    assertThat(xml)
        .containsSubsequence(
            "<operand xmi:id=\"id-op-backorder\"",
            "<fragment xmi:type=\"uml:CombinedFragment\" xmi:id=\"id-cf-coupon\"",
            "</operand></fragment></operand></fragment><fragment xmi:type=\"uml:CombinedFragment\" xmi:id=\"id-cf-retry\"");
  }

  @Test
  void exportsEndpointLifelineWhenOnlySelectedMessageOwnsInteraction() throws Exception {
    JsonNode input = exportSequenceInput();
    ((ObjectNode) input.at("/source/nodes/1/properties/uml")).remove("interaction");

    String xml = exportXml(input);

    assertThat(xml)
        .contains(
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

    assertThat(xml)
        .containsSubsequence(
            "xmi:id=\"id-m2-send-event\"",
            "xmi:id=\"id-m2-receive-event\"",
            "xmi:id=\"id-m1-send-event\"",
            "xmi:id=\"id-m1-receive-event\"",
            "xmi:id=\"id-m3-send-event\"",
            "xmi:id=\"id-m3-receive-event\"");
    assertThat(xml)
        .containsSubsequence(
            "<message xmi:id=\"id-m2\"", "<message xmi:id=\"id-m1\"", "<message xmi:id=\"id-m3\"");
  }

  @Test
  void rejectsSelectedSequenceDeleteMessageToDestructionOccurrence() throws Exception {
    JsonNode input = exportSequenceInput();
    ((tools.jackson.databind.node.ArrayNode) input.at("/source/nodes"))
        .add(
            JsonSupport.objectMapper()
                .readTree(
                    """
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
    ((tools.jackson.databind.node.ArrayNode) input.at("/source/relationships"))
        .add(
            JsonSupport.objectMapper()
                .readTree(
                    """
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

    exportExpectingError(
        input, "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_ENDPOINT_UNSUPPORTED", "$.relationships[3]");
  }

  @Test
  void rejectsSelectedSequenceMessageWithoutInteraction() throws Exception {
    JsonNode input = exportSequenceInput();
    ((ObjectNode) input.at("/source/relationships/0/properties/uml")).remove("interaction");

    exportExpectingError(
        input,
        "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_MISSING",
        "$.relationships[0].properties.uml.interaction");
  }

  @Test
  void rejectsSelectedSequenceMessageWithUnknownInteraction() throws Exception {
    JsonNode input = exportSequenceInput();
    ((ObjectNode) input.at("/source/relationships/0/properties/uml"))
        .put("interaction", "missing-interaction");

    exportExpectingError(
        input,
        "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_UNSUPPORTED",
        "$.relationships[0].properties.uml.interaction");
  }

  @Test
  void rejectsSelectedSequenceMessageWithNonInteractionOwner() throws Exception {
    JsonNode input = exportSequenceInput();
    ((ObjectNode) input.at("/source/relationships/0/properties/uml"))
        .put("interaction", "customer");

    exportExpectingError(
        input,
        "DEDIREN_UML_XMI_SEQUENCE_MESSAGE_INTERACTION_UNSUPPORTED",
        "$.relationships[0].properties.uml.interaction");
  }

  @Test
  void rejectsSelectedUnsupportedSequenceNode() throws Exception {
    JsonNode input = exportSequenceInput();
    ((tools.jackson.databind.node.ArrayNode) input.at("/source/nodes"))
        .add(
            JsonSupport.objectMapper()
                .readTree(
                    """
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

    exportExpectingError(input, "DEDIREN_UML_XMI_SEQUENCE_NODE_UNSUPPORTED", "$.nodes[3]");
  }

  @Test
  void rejectsSelectedUnsupportedSequenceCombinedFragmentOperator() throws Exception {
    JsonNode input =
        exportInput(
            fixtureJson("fixtures/source/valid-uml-sequence-fragments.json"),
            fixtureJson("fixtures/layout-result/uml-sequence-fragments.json"));
    ((ObjectNode) input.at("/source/nodes/5/properties/uml")).put("operator", "ignore");

    exportExpectingError(
        input,
        "DEDIREN_UML_XMI_SEQUENCE_FRAGMENT_OPERATOR_UNSUPPORTED",
        "$.nodes[5].properties.uml.operator");
  }

  @Test
  void rejectsInvalidUmlRelationshipEndpoint() throws Exception {
    JsonNode input = exportInput();
    ((ObjectNode) input.at("/source/relationships/0")).put("source", "initial-submit");

    exportExpectingError(input, "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
  }

  @Test
  void rejectsInvalidSequenceMessageEndpoint() throws Exception {
    JsonNode input = exportSequenceInput();
    ((ObjectNode) input.at("/source/nodes/2")).put("type", "Class");

    exportExpectingError(input, "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
  }

  @Test
  void rejectsInvalidPolicySchema() throws Exception {
    JsonNode input = exportInput();
    ((ObjectNode) input.get("policy")).put("xmi_version", "3.0");

    exportExpectingError(input, "DEDIREN_UML_XMI_POLICY_INVALID");
  }

  @Test
  void rejectsGeneratedXmlWithInvalidXmiId() throws Exception {
    JsonNode input = exportInput();
    ((ObjectNode) input.get("policy")).put("model_identifier", "not a valid xml id");

    exportExpectingError(input, "DEDIREN_XMI_ID_INVALID");
  }

  @Test
  void deduplicatesCollidingGeneratedIds() throws Exception {
    JsonNode input = exportInput();
    ((ObjectNode) input.get("policy")).put("model_identifier", "id-class-order");
    ((tools.jackson.databind.node.ArrayNode) input.at("/source/nodes"))
        .add(
            JsonSupport.objectMapper()
                .readTree(
                    """
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
    ((tools.jackson.databind.node.ArrayNode) input.at("/layout_result/nodes"))
        .add(
            JsonSupport.objectMapper()
                .readTree(
                    """
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

    assertThat(xml)
        .contains(
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

  @Test
  void xmiValidationParserRejectsDoctype() throws Exception {
    var builder = SchemaValidation.secureXmiDocumentBuilderFactory().newDocumentBuilder();
    String hostile =
        "<!DOCTYPE XMI [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<XMI xmlns=\"http://www.omg.org/spec/XMI/20131001\">&xxe;</XMI>";

    assertThatThrownBy(
            () -> builder.parse(new ByteArrayInputStream(hostile.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(SAXParseException.class)
        .hasMessageContaining("DOCTYPE");
  }

  private PluginResult exportExpectingError(JsonNode input, String expectedCode) throws Exception {
    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            JsonSupport.objectMapper().writeValueAsString(input),
            envWithXmiSchema());
    assertThat(result.exitCode()).isEqualTo(3);
    assertErrorCode(result, expectedCode);
    return result;
  }

  private PluginResult exportExpectingError(
      JsonNode input, String expectedCode, String expectedPath) throws Exception {
    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            JsonSupport.objectMapper().writeValueAsString(input),
            envWithXmiSchema());
    assertThat(result.exitCode()).isEqualTo(3);
    assertError(result, expectedCode, expectedPath);
    return result;
  }

  private String exportXml(JsonNode input) throws Exception {
    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
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
    Files.writeString(
        schemaPath,
        """
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
                """,
        StandardCharsets.UTF_8);
    return Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString());
  }

  /**
   * Writes the documented UML-content validation schema set: an XMI driver schema whose {@code
   * xmi:XMI} demands a strict global element declaration for its non-XMI children, importing a UML
   * schema that declares the emitted {@code uml:} elements. Pointing {@code
   * DEDIREN_XMI_SCHEMA_PATH} at the driver lets {@code xmllint} validate UML content rather than
   * skipping it.
   */
  private Map<String, String> envWithXmiAndUmlSchema() throws Exception {
    Files.writeString(
        tempDir.resolve("uml.xsd"),
        """
                <?xml version="1.0" encoding="UTF-8"?>
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                            targetNamespace="http://www.omg.org/spec/UML/20161101"
                            xmlns="http://www.omg.org/spec/UML/20161101"
                            elementFormDefault="qualified">
                  <xsd:element name="Model">
                    <xsd:complexType>
                      <xsd:sequence>
                        <xsd:any namespace="##any" processContents="lax"
                                 minOccurs="0" maxOccurs="unbounded"/>
                      </xsd:sequence>
                      <xsd:anyAttribute processContents="lax"/>
                    </xsd:complexType>
                  </xsd:element>
                </xsd:schema>
                """,
        StandardCharsets.UTF_8);
    Path driverPath = tempDir.resolve("XMI.xsd");
    Files.writeString(
        driverPath,
        """
                <?xml version="1.0" encoding="UTF-8"?>
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                            targetNamespace="http://www.omg.org/spec/XMI/20131001"
                            xmlns="http://www.omg.org/spec/XMI/20131001"
                            elementFormDefault="qualified">
                  <xsd:import namespace="http://www.omg.org/spec/UML/20161101"
                              schemaLocation="uml.xsd"/>
                  <xsd:element name="XMI">
                    <xsd:complexType>
                      <xsd:choice minOccurs="0" maxOccurs="unbounded">
                        <xsd:any namespace="##other" processContents="strict"/>
                      </xsd:choice>
                      <xsd:anyAttribute processContents="lax"/>
                    </xsd:complexType>
                  </xsd:element>
                </xsd:schema>
                """,
        StandardCharsets.UTF_8);
    return Map.of("DEDIREN_XMI_SCHEMA_PATH", driverPath.toString());
  }

  private static JsonNode diagnostic(JsonNode envelope, String code) {
    for (JsonNode diagnostic : envelope.at("/diagnostics")) {
      if (diagnostic.at("/code").asText().equals(code)) {
        return diagnostic;
      }
    }
    throw new AssertionError("no diagnostic with code " + code + " in " + envelope);
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

  private static void assertError(PluginResult result, String expectedCode, String expectedPath)
      throws Exception {
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

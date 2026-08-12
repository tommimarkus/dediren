package dev.dediren.plugins.umlxmi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ModelExportRequest;
import dev.dediren.testsupport.SchemaAssertions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class XmiAssuranceTest {
  @TempDir Path tempDir;

  static Stream<Arguments> supportedKinds() {
    return Stream.of(
        Arguments.of(
            "uml-class", "valid-uml-basic", "uml-basic", "standard-uml-diagram-kind", true),
        Arguments.of(
            "uml-sequence",
            "valid-uml-sequence-basic",
            "uml-sequence-basic",
            "standard-uml-diagram-kind",
            false),
        Arguments.of(
            "uml-state-machine",
            "valid-uml-state-machine-basic",
            "uml-state-machine-basic",
            "standard-uml-diagram-kind",
            false),
        Arguments.of(
            "uml-use-case",
            "valid-uml-use-case-basic",
            "uml-use-case-basic",
            "standard-uml-diagram-kind",
            false),
        Arguments.of(
            "uml-component",
            "valid-uml-component-basic",
            "uml-component-basic",
            "standard-uml-diagram-kind",
            false),
        Arguments.of(
            "uml-deployment",
            "valid-uml-deployment-basic",
            "uml-deployment-basic",
            "standard-uml-diagram-kind",
            false),
        Arguments.of(
            "uml-activity", "valid-uml-basic", "uml-activity", "standard-uml-diagram-kind", false),
        Arguments.of(
            "uml-data", "valid-uml-basic", "uml-data", "dediren-local-classifier-view", true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("supportedKinds")
  void exportPublishesHonestAssuranceForEverySupportedKind(
      String kind,
      String source,
      String layout,
      String classification,
      boolean aggregateModelEligible)
      throws Exception {
    JsonNode assurance = exportAssurance(source, layout);

    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(), "schemas/uml-xmi-assurance.schema.json", assurance))
        .describedAs("emitted assurance for %s must satisfy its public schema", kind)
        .isEmpty();
    assertThat(assurance.at("/assurance_schema_version").asText())
        .isEqualTo("uml-xmi-assurance.schema.v1");
    JsonNode taxonomy = assurance.at("/kind_taxonomy");
    assertThat(taxonomy).hasSize(8);
    JsonNode kindEntry = findKind(taxonomy, kind);
    assertThat(kindEntry.at("/classification").asText()).isEqualTo(classification);
    assertThat(kindEntry.at("/scope/xmi_abstract_syntax").asText()).isEqualTo("selected-view");
    assertThat(kindEntry.at("/scope/aggregate_model").asText())
        .isEqualTo(aggregateModelEligible ? "included" : "not-included");
    assertThat(kindEntry.at("/scope/uml_di").asText())
        .isEqualTo(aggregateModelEligible ? "provisional-aggregate" : "none");
    if ("uml-data".equals(kind)) {
      assertThat(kindEntry.at("/maps_to_uml_diagram_kind").asText()).isEqualTo("class");
    }
    assertThat(assurance.at("/artifact_scope/scope").asText()).isEqualTo("view");
    assertThat(assurance.at("/artifact_scope/selected_view_kinds"))
        .singleElement()
        .satisfies(view -> assertThat(view.asText()).isEqualTo(kind));
    assertConservativeValidationEvidence(assurance);
  }

  @Test
  void modelExportPublishesAggregateScopeForItsSelectedViewKinds() throws Exception {
    SourceDocument source =
        JsonSupport.objectMapper()
            .readValue(
                Files.readString(workspaceRoot().resolve("fixtures/source/valid-uml-basic.json")),
                SourceDocument.class);
    ModelExportRequest request =
        new ModelExportRequest(
            source,
            List.of(
                new ModelExportRequest.ViewLayout(
                    "class-view",
                    fixture("fixtures/layout-result/uml-basic.json", LayoutResult.class)),
                new ModelExportRequest.ViewLayout(
                    "data-view",
                    fixture("fixtures/layout-result/uml-data.json", LayoutResult.class))),
            fixtureJson("fixtures/export-policy/default-uml-xmi.json"));

    Optional<EngineResult<ExportResult>> result =
        new XmiExportEngine()
            .exportModel(request, envWithStubXmiSchema(), Path.of("").toAbsolutePath());
    JsonNode assurance =
        JsonSupport.objectMapper().valueToTree(result.orElseThrow().value()).at("/assurance");

    assertThat(assurance.at("/artifact_scope/scope").asText()).isEqualTo("model-aggregate");
    assertThat(assurance.at("/artifact_scope/selected_view_kinds"))
        .extracting(JsonNode::asText)
        .containsExactly("uml-class", "uml-data");
    assertConservativeValidationEvidence(assurance);
  }

  @Test
  void assuranceCoverageReportsDynamicCategoryCountsAcrossEveryPartition() throws Exception {
    JsonNode assurance = exportAssurance("valid-uml-basic", "uml-basic");
    JsonNode coverage = assurance.at("/coverage");

    for (String partition : Stream.of("represented", "omitted", "unrepresented_in_view").toList()) {
      assertThat(coverage.at("/" + partition + "/elements").isObject()).isTrue();
      assertThat(coverage.at("/" + partition + "/relationships").isObject()).isTrue();
      assertThat(coverage.at("/" + partition + "/element_total").canConvertToInt()).isTrue();
      assertThat(coverage.at("/" + partition + "/relationship_total").canConvertToInt()).isTrue();
    }
    assertThat(coverage.at("/represented/elements/Class").asInt()).isPositive();
    assertThat(coverage.at("/represented/relationships/Composition").asInt()).isPositive();
  }

  private JsonNode exportAssurance(String sourceFixture, String layoutFixture) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", fixtureJson("fixtures/source/" + sourceFixture + ".json"));
    input.set("layout_result", fixtureJson("fixtures/layout-result/" + layoutFixture + ".json"));
    input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));

    PluginResult result =
        Main.executeForTesting(new String[] {"export"}, input.toString(), envWithStubXmiSchema());
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    return envelope.at("/data/assurance");
  }

  private static void assertConservativeValidationEvidence(JsonNode assurance) {
    JsonNode evidence = assurance.at("/validation_evidence");
    assertThat(evidence.at("/level").asText()).isEqualTo("xmi-envelope-only");
    assertThat(evidence.at("/xmi_schema_evidence/status").asText()).isEqualTo("validated");
    assertThat(evidence.at("/xmi_schema_evidence/source").asText())
        .isEqualTo("user-supplied-schema-path");
    assertThat(evidence.at("/xmi_schema_evidence/standard").isMissingNode()).isTrue();
    assertThat(evidence.at("/uml_metamodel_evidence")).isEmpty();
    assertThat(evidence.at("/importer_evidence")).isEmpty();
  }

  private static JsonNode findKind(JsonNode taxonomy, String kind) {
    for (JsonNode entry : taxonomy) {
      if (kind.equals(entry.at("/kind").asText())) {
        return entry;
      }
    }
    throw new AssertionError("Missing taxonomy entry for " + kind);
  }

  @org.junit.jupiter.api.Test
  void reportsNotValidatedWhenTheSchemaRejectedTheUmlContent() throws Exception {
    // UML-XMI-16 / DOC-25/-26: on the tolerated-gap path the schema REJECTS the uml: subtree and
    // the export proceeds anyway. status used to be an unconditional "validated" here, so a
    // consumer branching on it was reading a constant — and reading it wrong — while the schema's
    // "not-validated" value was unreachable. The stub above is lax, so it accepts everything; a
    // STRICT wildcard reproduces what the real pinned XMI.xsd does.
    Path schemaPath = tempDir.resolve("strict-XMI.xsd");
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
              <xsd:sequence>
                <xsd:any namespace="##other" processContents="strict"
                         minOccurs="0" maxOccurs="unbounded"/>
              </xsd:sequence>
              <xsd:anyAttribute processContents="lax"/>
            </xsd:complexType>
          </xsd:element>
        </xsd:schema>
        """,
        StandardCharsets.UTF_8);

    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", fixtureJson("fixtures/source/valid-uml-basic.json"));
    input.set("layout_result", fixtureJson("fixtures/layout-result/uml-basic.json"));
    input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));

    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"},
            input.toString(),
            Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString()));

    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    JsonNode evidence = envelope.at("/data/assurance/validation_evidence");
    assertThat(evidence.at("/xmi_schema_evidence/status").asText()).isEqualTo("not-validated");
    // The level is unchanged: the envelope was still checked, and no stronger evidence exists.
    assertThat(evidence.at("/level").asText()).isEqualTo("xmi-envelope-only");
  }

  private Map<String, String> envWithStubXmiSchema() throws Exception {
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

  private static JsonNode fixtureJson(String path) throws Exception {
    return JsonSupport.objectMapper().readTree(Files.readString(workspaceRoot().resolve(path)));
  }

  private static <T> T fixture(String path, Class<T> type) throws Exception {
    return JsonSupport.objectMapper()
        .readValue(Files.readString(workspaceRoot().resolve(path)), type);
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

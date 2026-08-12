package dev.dediren.plugins.umlxmi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
        Arguments.of("uml-class", "valid-uml-basic", "uml-basic", "standard-uml-diagram-kind", true),
        Arguments.of("uml-sequence", "valid-uml-sequence-basic", "uml-sequence-basic", "standard-uml-diagram-kind", false),
        Arguments.of("uml-state-machine", "valid-uml-state-machine-basic", "uml-state-machine-basic", "standard-uml-diagram-kind", false),
        Arguments.of("uml-use-case", "valid-uml-use-case-basic", "uml-use-case-basic", "standard-uml-diagram-kind", false),
        Arguments.of("uml-component", "valid-uml-component-basic", "uml-component-basic", "standard-uml-diagram-kind", false),
        Arguments.of("uml-deployment", "valid-uml-deployment-basic", "uml-deployment-basic", "standard-uml-diagram-kind", false),
        Arguments.of("uml-activity", "valid-uml-basic", "uml-activity", "standard-uml-diagram-kind", false),
        Arguments.of("uml-data", "valid-uml-basic", "uml-data", "dediren-local-classifier-view", true));
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

    assertThat(assurance.at("/assurance_schema_version").asText())
        .isEqualTo("uml-xmi-assurance.schema.v1");
    JsonNode taxonomy = assurance.at("/kind_taxonomy");
    assertThat(taxonomy).hasSize(8);
    JsonNode kindEntry = findKind(taxonomy, kind);
    assertThat(kindEntry.at("/classification").asText()).isEqualTo(classification);
    assertThat(kindEntry.at("/scope/xmi_abstract_syntax").asText()).isEqualTo("supported");
    assertThat(kindEntry.at("/scope/aggregate_model_eligible").asBoolean())
        .isEqualTo(aggregateModelEligible);
    assertThat(kindEntry.at("/scope/uml_di").asText())
        .isEqualTo(aggregateModelEligible ? "provisional-aggregate" : "none");
    if ("uml-data".equals(kind)) {
      assertThat(kindEntry.at("/maps_to_uml").asText()).isEqualTo("Class");
    }
    assertThat(assurance.at("/artifact_scope/scope").asText())
        .isEqualTo(aggregateModelEligible ? "model-aggregate" : "view");
    assertThat(assurance.at("/artifact_scope/selected_views"))
        .singleElement()
        .satisfies(view -> assertThat(view.asText()).isEqualTo(kind));
    assertConservativeValidationEvidence(assurance);
  }

  @Test
  void assuranceCoverageReportsDynamicCategoryCountsAcrossEveryPartition() throws Exception {
    JsonNode assurance = exportAssurance("valid-uml-basic", "uml-basic");
    JsonNode coverage = assurance.at("/coverage");

    for (String partition :
        Stream.of("represented", "omitted", "unrepresented_in_view").toList()) {
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

package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.testsupport.SchemaAssertions;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SchemaValidatorTest {
  private static final List<String> PUBLIC_SCHEMAS =
      List.of(
          "schemas/model.schema.json",
          "schemas/envelope.schema.json",
          "schemas/layout-request.schema.json",
          "schemas/layout-result.schema.json",
          "schemas/semantic-validation-result.schema.json",
          "schemas/render-policy.schema.json",
          "schemas/render-metadata.schema.json",
          "schemas/render-result.schema.json",
          "schemas/export-request.schema.json",
          "schemas/export-result.schema.json",
          "schemas/export-result.first-party.schema.json",
          "schemas/oef-export-policy.schema.json",
          "schemas/uml-xmi-export-policy.schema.json",
          "schemas/plugin-manifest.schema.json",
          "schemas/runtime-capability.schema.json",
          "schemas/bundle.schema.json");

  private static final List<String> PLUGIN_MANIFESTS =
      List.of(
          "fixtures/plugins/archimate-oef.manifest.json",
          "fixtures/plugins/elk-layout.manifest.json",
          "fixtures/plugins/generic-graph.manifest.json",
          "fixtures/plugins/render.manifest.json",
          "fixtures/plugins/uml-xmi.manifest.json");

  @Test
  void allPublicSchemasCompile() {
    for (String schema : PUBLIC_SCHEMAS) {
      assertThat(SchemaAssertions.compile(workspaceRoot(), schema)).describedAs(schema).isEmpty();
    }
  }

  @Test
  void validSourceMatchesModelSchemaAndAbsoluteGeometryIsRejected() {
    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(), "schemas/model.schema.json", "fixtures/source/valid-basic.json"))
        .isEmpty();
    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(),
                "schemas/model.schema.json",
                "fixtures/source/invalid-absolute-geometry.json"))
        .isNotEmpty();
  }

  @Test
  void layoutResultNodeRoleFieldIsOptional() {
    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(),
                "schemas/layout-result.schema.json",
                "fixtures/layout-result/uml-sequence-validatable.json"))
        .describedAs("role-bearing layout-result should validate")
        .isEmpty();
    assertThat(
            SchemaAssertions.validateFixture(
                workspaceRoot(),
                "schemas/layout-result.schema.json",
                "fixtures/layout-result/basic.json"))
        .describedAs("role-less layout-result should still validate")
        .isEmpty();
  }

  @Test
  void exportResultBaseSchemaAcceptsAnyHonestArtifactKind() {
    // The published export-result contract is the base any export plugin can satisfy honestly:
    // artifact_kind is a pattern, not the closed first-party enum.
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.schema.json",
                exportResult("ticket-stats+json")))
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.schema.json",
                exportResult("archimate-oef+xml")))
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(), "schemas/export-result.schema.json", exportResult("uml-xmi+xml")))
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.schema.json",
                exportResult("Not A Valid Kind")))
        .isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Archimate-OEF+xml", // uppercase leading/id characters
        "ticket-stats", // missing +suffix
        "", // empty string
        "ticket-stats+yaml", // unknown suffix
        "tïcket+json" // non-ASCII id character
      })
  void exportResultBaseSchemaRejectsArtifactKindOutsideThePattern(String artifactKind) {
    // Distinct rejection partitions for ^[a-z0-9][a-z0-9.-]*\+(xml|json|text)$: each is rejected
    // independently, not conflated into one bad string.
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(), "schemas/export-result.schema.json", exportResult(artifactKind)))
        .describedAs("artifact_kind=<%s>", artifactKind)
        .isNotEmpty();
  }

  @Test
  void exportResultFirstPartySchemaKeepsClosedArtifactKindEnum() {
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.first-party.schema.json",
                exportResult("archimate-oef+xml")))
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.first-party.schema.json",
                exportResult("uml-xmi+xml")))
        .isEmpty();
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-result.first-party.schema.json",
                exportResult("ticket-stats+json")))
        .isNotEmpty();
  }

  @Test
  void exportRequestPolicyIsPassThroughForAnyExportPlugin() throws Exception {
    // The CLI forwards the --policy document verbatim to the target export plugin, so the
    // published request schema must accept a third-party policy shape, not just the two
    // first-party ones.
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(),
                "schemas/export-request.schema.json",
                exportRequest(thirdPartyPolicy())))
        .isEmpty();
    for (String fixture :
        List.of(
            "fixtures/export-policy/default-oef.json",
            "fixtures/export-policy/default-uml-xmi.json")) {
      assertThat(
              SchemaAssertions.validate(
                  workspaceRoot(),
                  "schemas/export-request.schema.json",
                  exportRequest(readJson(fixture))))
          .describedAs(fixture)
          .isEmpty();
    }
  }

  @Test
  void exportRequestRejectsMissingOrNonObjectPolicy() {
    // export-request.schema.json requires `policy` and constrains it to an object. A request that
    // omits policy, or supplies a non-object (string/array/null), must be rejected.
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    var noPolicy = mapper.createObjectNode();
    noPolicy.put("export_request_schema_version", "export-request.schema.v1");
    noPolicy.putObject("source").put("model_schema_version", "model.schema.v1");
    noPolicy
        .putObject("layout_result")
        .put("layout_result_schema_version", "layout-result.schema.v1");
    assertThat(
            SchemaAssertions.validate(
                workspaceRoot(), "schemas/export-request.schema.json", noPolicy))
        .describedAs("policy omitted")
        .isNotEmpty();

    List<tools.jackson.databind.JsonNode> nonObjectPolicies =
        List.of(
            mapper.getNodeFactory().textNode("not-an-object"),
            mapper.createArrayNode(),
            mapper.getNodeFactory().nullNode());
    for (tools.jackson.databind.JsonNode badPolicy : nonObjectPolicies) {
      assertThat(
              SchemaAssertions.validate(
                  workspaceRoot(), "schemas/export-request.schema.json", exportRequest(badPolicy)))
          .describedAs("policy=%s", badPolicy.getNodeType())
          .isNotEmpty();
    }
  }

  private static tools.jackson.databind.JsonNode exportRequest(
      tools.jackson.databind.JsonNode policy) {
    var mapper = dev.dediren.contracts.json.JsonSupport.objectMapper();
    var request = mapper.createObjectNode();
    request.put("export_request_schema_version", "export-request.schema.v1");
    request.putObject("source").put("model_schema_version", "model.schema.v1");
    request
        .putObject("layout_result")
        .put("layout_result_schema_version", "layout-result.schema.v1");
    request.set("policy", policy);
    return request;
  }

  private static tools.jackson.databind.JsonNode thirdPartyPolicy() {
    var policy = dev.dediren.contracts.json.JsonSupport.objectMapper().createObjectNode();
    policy.put("ticket_stats_policy", "v1");
    return policy;
  }

  private static tools.jackson.databind.JsonNode readJson(String path) throws Exception {
    return dev.dediren.contracts.json.JsonSupport.objectMapper()
        .readTree(java.nio.file.Files.readString(workspaceRoot().resolve(path)));
  }

  private static tools.jackson.databind.JsonNode exportResult(String artifactKind) {
    var document = dev.dediren.contracts.json.JsonSupport.objectMapper().createObjectNode();
    document.put("export_result_schema_version", "export-result.schema.v1");
    document.put("artifact_kind", artifactKind);
    document.put("content", "{}");
    return document;
  }

  @Test
  void firstPartyPluginManifestsMatchSchema() {
    for (String manifest : PLUGIN_MANIFESTS) {
      assertThat(
              SchemaAssertions.validateFixture(
                  workspaceRoot(), "schemas/plugin-manifest.schema.json", manifest))
          .describedAs(manifest)
          .isEmpty();
    }
  }

  private static Path workspaceRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

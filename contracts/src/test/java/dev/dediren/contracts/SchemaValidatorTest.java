package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.testsupport.SchemaAssertions;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

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

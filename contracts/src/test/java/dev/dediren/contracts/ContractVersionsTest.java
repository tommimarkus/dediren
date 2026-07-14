package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractVersionsTest {
  @Test
  void schemaFilesEnforceTheMatchingVersionConstants() throws Exception {
    // Each public schema pins its version field with a JSON Schema `const`; a schema whose const
    // drifts from the ContractVersions constant must fail here directly, not only through the
    // transitive fixture-validation chains.
    Map<String, String[]> versionConstBySchema = new LinkedHashMap<>();
    versionConstBySchema.put(
        "schemas/model.schema.json",
        new String[] {"model_schema_version", ContractVersions.MODEL_SCHEMA_VERSION});
    versionConstBySchema.put(
        "schemas/envelope.schema.json",
        new String[] {"envelope_schema_version", ContractVersions.ENVELOPE_SCHEMA_VERSION});
    versionConstBySchema.put(
        "schemas/layout-request.schema.json",
        new String[] {
          "layout_request_schema_version", ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION
        });
    versionConstBySchema.put(
        "schemas/layout-result.schema.json",
        new String[] {
          "layout_result_schema_version", ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION
        });
    versionConstBySchema.put(
        "schemas/semantic-validation-result.schema.json",
        new String[] {
          "semantic_validation_result_schema_version",
          ContractVersions.SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION
        });
    versionConstBySchema.put(
        "schemas/render-policy.schema.json",
        new String[] {
          "render_policy_schema_version", ContractVersions.RENDER_POLICY_SCHEMA_VERSION
        });
    versionConstBySchema.put(
        "schemas/render-metadata.schema.json",
        new String[] {
          "render_metadata_schema_version", ContractVersions.RENDER_METADATA_SCHEMA_VERSION
        });
    versionConstBySchema.put(
        "schemas/render-result.schema.json",
        new String[] {
          "render_result_schema_version", ContractVersions.RENDER_RESULT_SCHEMA_VERSION
        });
    versionConstBySchema.put(
        "schemas/export-request.schema.json",
        new String[] {
          "export_request_schema_version", ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION
        });
    versionConstBySchema.put(
        "schemas/export-result.schema.json",
        new String[] {
          "export_result_schema_version", ContractVersions.EXPORT_RESULT_SCHEMA_VERSION
        });
    versionConstBySchema.put(
        "schemas/oef-export-policy.schema.json",
        new String[] {
          "oef_export_policy_schema_version", ContractVersions.OEF_EXPORT_POLICY_SCHEMA_VERSION
        });
    versionConstBySchema.put(
        "schemas/uml-xmi-export-policy.schema.json",
        new String[] {
          "uml_xmi_export_policy_schema_version",
          ContractVersions.UML_XMI_EXPORT_POLICY_SCHEMA_VERSION
        });
    versionConstBySchema.put(
        "schemas/build-result.schema.json",
        new String[] {"build_result_schema_version", ContractVersions.BUILD_RESULT_SCHEMA_VERSION});
    versionConstBySchema.put(
        "schemas/runtime-capability.schema.json",
        new String[] {"plugin_protocol_version", ContractVersions.PLUGIN_PROTOCOL_VERSION});

    for (var entry : versionConstBySchema.entrySet()) {
      String schemaPath = entry.getKey();
      String field = entry.getValue()[0];
      String expected = entry.getValue()[1];
      var schema =
          JsonSupport.readTree(
              Files.readString(
                  dev.dediren.testsupport.TestSupport.workspaceRoot().resolve(schemaPath)));

      assertThat(schema.at("/properties/" + field + "/const").asText())
          .describedAs("%s properties.%s.const", schemaPath, field)
          .isEqualTo(expected);
    }
  }

  @Test
  void schemaVersionConstantsMatchPublicSchemas() {
    assertThat(ContractVersions.MODEL_SCHEMA_VERSION).isEqualTo("model.schema.v1");
    assertThat(ContractVersions.ENVELOPE_SCHEMA_VERSION).isEqualTo("envelope.schema.v1");
    assertThat(ContractVersions.PLUGIN_PROTOCOL_VERSION).isEqualTo("plugin.protocol.v1");
    assertThat(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION)
        .isEqualTo("layout-request.schema.v2");
    assertThat(ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION).isEqualTo("layout-result.schema.v2");
    assertThat(ContractVersions.SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION)
        .isEqualTo("semantic-validation-result.schema.v1");
    assertThat(ContractVersions.RENDER_RESULT_SCHEMA_VERSION).isEqualTo("render-result.schema.v4");
    assertThat(ContractVersions.RENDER_POLICY_SCHEMA_VERSION).isEqualTo("render-policy.schema.v3");
    assertThat(ContractVersions.RENDER_METADATA_SCHEMA_VERSION)
        .isEqualTo("render-metadata.schema.v1");
    assertThat(ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION)
        .isEqualTo("export-request.schema.v1");
    assertThat(ContractVersions.EXPORT_RESULT_SCHEMA_VERSION).isEqualTo("export-result.schema.v1");
    assertThat(ContractVersions.OEF_EXPORT_POLICY_SCHEMA_VERSION)
        .isEqualTo("oef-export-policy.schema.v1");
    assertThat(ContractVersions.UML_XMI_EXPORT_POLICY_SCHEMA_VERSION)
        .isEqualTo("uml-xmi-export-policy.schema.v1");
    assertThat(ContractVersions.BUILD_RESULT_SCHEMA_VERSION).isEqualTo("build-result.schema.v1");
  }
}

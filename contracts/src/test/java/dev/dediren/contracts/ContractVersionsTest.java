package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractVersionsTest {
    @Test
    void schemaVersionConstantsMatchPublicSchemas() {
        assertThat(ContractVersions.MODEL_SCHEMA_VERSION).isEqualTo("model.schema.v1");
        assertThat(ContractVersions.ENVELOPE_SCHEMA_VERSION).isEqualTo("envelope.schema.v1");
        assertThat(ContractVersions.PLUGIN_PROTOCOL_VERSION).isEqualTo("plugin.protocol.v1");
        assertThat(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION).isEqualTo("layout-request.schema.v1");
        assertThat(ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION).isEqualTo("layout-result.schema.v1");
        assertThat(ContractVersions.SEMANTIC_VALIDATION_RESULT_SCHEMA_VERSION)
                .isEqualTo("semantic-validation-result.schema.v1");
        assertThat(ContractVersions.RENDER_RESULT_SCHEMA_VERSION).isEqualTo("render-result.schema.v2");
        assertThat(ContractVersions.SVG_RENDER_POLICY_SCHEMA_VERSION).isEqualTo("svg-render-policy.schema.v1");
        assertThat(ContractVersions.RENDER_METADATA_SCHEMA_VERSION).isEqualTo("render-metadata.schema.v1");
        assertThat(ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION).isEqualTo("export-request.schema.v1");
        assertThat(ContractVersions.EXPORT_RESULT_SCHEMA_VERSION).isEqualTo("export-result.schema.v1");
        assertThat(ContractVersions.OEF_EXPORT_POLICY_SCHEMA_VERSION).isEqualTo("oef-export-policy.schema.v1");
        assertThat(ContractVersions.UML_XMI_EXPORT_POLICY_SCHEMA_VERSION)
                .isEqualTo("uml-xmi-export-policy.schema.v1");
    }
}

package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnownSchemaVersionsTest {

  @Test
  void everyFamilysCurrentVersionIsItsContractVersionsConstant() {
    assertThat(KnownSchemaVersions.MODEL.currentVersion())
        .isEqualTo(ContractVersions.MODEL_SCHEMA_VERSION);
    assertThat(KnownSchemaVersions.RENDER_POLICY.currentVersion())
        .isEqualTo(ContractVersions.RENDER_POLICY_SCHEMA_VERSION);
    assertThat(KnownSchemaVersions.OEF_EXPORT_POLICY.currentVersion())
        .isEqualTo(ContractVersions.OEF_EXPORT_POLICY_SCHEMA_VERSION);
    assertThat(KnownSchemaVersions.UML_XMI_EXPORT_POLICY.currentVersion())
        .isEqualTo(ContractVersions.UML_XMI_EXPORT_POLICY_SCHEMA_VERSION);
  }

  @Test
  void renderPolicyCarriesItsShippedHistoryOldestFirst() {
    assertThat(KnownSchemaVersions.RENDER_POLICY.priorVersions())
        .containsExactly(
            "svg-render-policy.schema.v1", "render-policy.schema.v1", "render-policy.schema.v2");
  }

  @Test
  void renderPolicyKnowsTheFieldNameItUsedBeforeTheFamilyRename() {
    assertThat(KnownSchemaVersions.RENDER_POLICY.versionField())
        .isEqualTo("render_policy_schema_version");
    assertThat(KnownSchemaVersions.RENDER_POLICY.versionFields())
        .containsExactly("render_policy_schema_version", "svg_render_policy_schema_version");
  }

  @Test
  void familiesThatHaveNeverBeenBumpedHaveNoPriorVersions() {
    assertThat(KnownSchemaVersions.MODEL.priorVersions()).isEmpty();
    assertThat(KnownSchemaVersions.OEF_EXPORT_POLICY.priorVersions()).isEmpty();
    assertThat(KnownSchemaVersions.UML_XMI_EXPORT_POLICY.priorVersions()).isEmpty();
  }

  @Test
  void allListsEveryFamily() {
    assertThat(KnownSchemaVersions.ALL)
        .containsExactly(
            KnownSchemaVersions.MODEL,
            KnownSchemaVersions.RENDER_POLICY,
            KnownSchemaVersions.OEF_EXPORT_POLICY,
            KnownSchemaVersions.UML_XMI_EXPORT_POLICY,
            KnownSchemaVersions.LAYOUT_REQUEST);
  }

  @Test
  void layoutRequestCarriesItsShippedHistoryOldestFirst() {
    assertThat(KnownSchemaVersions.LAYOUT_REQUEST.priorVersions())
        .containsExactly("layout-request.schema.v1");
    assertThat(KnownSchemaVersions.LAYOUT_REQUEST.currentVersion())
        .isEqualTo(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);
    assertThat(KnownSchemaVersions.LAYOUT_REQUEST.versionField())
        .isEqualTo("layout_request_schema_version");
    assertThat(KnownSchemaVersions.LAYOUT_REQUEST.versionFields())
        .containsExactly("layout_request_schema_version");
  }
}

package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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

  @Test
  void familyWithoutAVersionFieldIsRejected() {
    assertThatThrownBy(
            () -> new KnownSchemaVersions.Family("fam", List.of(), List.of("v1"), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("family 'fam' must name its version field");
  }

  @Test
  void familyWithoutACurrentVersionIsRejected() {
    assertThatThrownBy(
            () ->
                new KnownSchemaVersions.Family("fam", List.of("fam_version"), List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("family 'fam' must have a current version");
  }

  @Test
  void familyWhoseStepDoesNotChainAdjacentVersionsIsRejected() {
    // Count guard passes (one step for two versions), so control reaches the chaining loop; the
    // step's from/to do not join v1 -> v2, so the chaining guard fires. The message is built from
    // the family's versions, not from the step's bad endpoints.
    MigrationPath misChainedStep =
        new MigrationPath("v0", "v9", List.of(MigrationOperation.regenerate()));
    assertThatThrownBy(
            () ->
                new KnownSchemaVersions.Family(
                    "fam", List.of("fam_version"), List.of("v1", "v2"), List.of(misChainedStep)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("family 'fam' step 0 must take v1 to v2");
  }

  @Test
  void familyWithoutOneMigrationStepPerSupersededVersionIsRejected() {
    // Two versions need exactly one migration step; supplying none trips the count guard before the
    // chaining loop. This is the enforcement that a version bump cannot ship without its steps.
    assertThatThrownBy(
            () ->
                new KnownSchemaVersions.Family(
                    "fam", List.of("fam_version"), List.of("v1", "v2"), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("family 'fam' must carry one migration step per superseded version");
  }

  @Test
  void migrationPathWithNoOperationsIsRejected() {
    assertThatThrownBy(() -> new MigrationPath("a", "b", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("migration path a -> b has no steps");
  }
}

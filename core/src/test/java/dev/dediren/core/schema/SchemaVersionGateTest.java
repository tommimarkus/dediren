package dev.dediren.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.KnownSchemaVersions;
import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;

class SchemaVersionGateTest {

  @Test
  void theCurrentVersionPasses() {
    var policy =
        JsonSupport.readTree(
            "{\"render_policy_schema_version\":\""
                + ContractVersions.RENDER_POLICY_SCHEMA_VERSION
                + "\"}");

    assertThat(SchemaVersionGate.check(KnownSchemaVersions.RENDER_POLICY, policy)).isEmpty();
  }

  @Test
  void aSupersededVersionIsOutdatedAndNamesBothVersionsAndTheGuide() {
    var policy =
        JsonSupport.readTree("{\"render_policy_schema_version\":\"render-policy.schema.v2\"}");

    Diagnostic diagnostic =
        SchemaVersionGate.check(KnownSchemaVersions.RENDER_POLICY, policy).orElseThrow();

    assertThat(diagnostic.code()).isEqualTo("DEDIREN_SCHEMA_VERSION_OUTDATED");
    assertThat(diagnostic.message())
        .contains("render-policy.schema.v2")
        .contains(ContractVersions.RENDER_POLICY_SCHEMA_VERSION)
        .contains("dediren_guide");
    assertThat(diagnostic.path()).isEqualTo("$.render_policy_schema_version");
  }

  @Test
  void aFileFromBeforeTheVersionFieldRenameIsOutdatedNotUnknown() {
    // The oldest render policies carry svg_render_policy_schema_version and no
    // render_policy_schema_version at all. A gate that only read the current field name would call
    // this "unknown" and strand precisely the file that most needs the upgrade steps.
    var policy =
        JsonSupport.readTree(
            "{\"svg_render_policy_schema_version\":\"svg-render-policy.schema.v1\"}");

    Diagnostic diagnostic =
        SchemaVersionGate.check(KnownSchemaVersions.RENDER_POLICY, policy).orElseThrow();

    assertThat(diagnostic.code()).isEqualTo("DEDIREN_SCHEMA_VERSION_OUTDATED");
    assertThat(diagnostic.message()).contains("svg-render-policy.schema.v1");
  }

  @Test
  void anUnrecognizedVersionIsUnknown() {
    var policy =
        JsonSupport.readTree("{\"render_policy_schema_version\":\"render-policy.schema.v99\"}");

    Diagnostic diagnostic =
        SchemaVersionGate.check(KnownSchemaVersions.RENDER_POLICY, policy).orElseThrow();

    assertThat(diagnostic.code()).isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
    assertThat(diagnostic.message()).contains("render-policy.schema.v99");
  }

  @Test
  void anAbsentVersionFieldIsUnknownAndNamesTheFieldItWanted() {
    var policy = JsonSupport.readTree("{\"page\":{}}");

    Diagnostic diagnostic =
        SchemaVersionGate.check(KnownSchemaVersions.RENDER_POLICY, policy).orElseThrow();

    assertThat(diagnostic.code()).isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
    assertThat(diagnostic.message()).contains("render_policy_schema_version");
  }

  @Test
  void theSourceModelIsGatedTheSameWay() {
    var model = JsonSupport.readTree("{\"model_schema_version\":\"model.schema.v0\"}");

    Diagnostic diagnostic = SchemaVersionGate.check(KnownSchemaVersions.MODEL, model).orElseThrow();

    // model.schema has never been bumped, so there is no history to recognize v0 against: unknown
    // is the honest answer, and it still names the version this build wants.
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
    assertThat(diagnostic.message()).contains(ContractVersions.MODEL_SCHEMA_VERSION);
    assertThat(diagnostic.path()).isEqualTo("$.model_schema_version");
  }
}

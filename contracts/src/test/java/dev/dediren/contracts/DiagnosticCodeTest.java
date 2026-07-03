package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DiagnosticCodeTest {
  @Test
  void eachConstantExposesItsCanonicalWireString() {
    assertThat(DiagnosticCode.PLUGIN_TIMEOUT.code()).isEqualTo("DEDIREN_PLUGIN_TIMEOUT");
    assertThat(DiagnosticCode.PLUGIN_PROCESS_FAILED.code())
        .isEqualTo("DEDIREN_PLUGIN_PROCESS_FAILED");
    assertThat(DiagnosticCode.OEF_SCHEMA_VALIDATOR_UNAVAILABLE.code())
        .isEqualTo("DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE");
  }

  @Test
  void pluginRuntimeFamilyExposesItsCanonicalWireStrings() {
    assertThat(DiagnosticCode.PLUGIN_MISSING_EXECUTABLE.code())
        .isEqualTo("DEDIREN_PLUGIN_MISSING_EXECUTABLE");
    assertThat(DiagnosticCode.PLUGIN_UNSUPPORTED_CAPABILITY.code())
        .isEqualTo("DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY");
    assertThat(DiagnosticCode.PLUGIN_ID_MISMATCH.code()).isEqualTo("DEDIREN_PLUGIN_ID_MISMATCH");
    assertThat(DiagnosticCode.PLUGIN_CAPABILITY_PROBE_FAILED.code())
        .isEqualTo("DEDIREN_PLUGIN_CAPABILITY_PROBE_FAILED");
    assertThat(DiagnosticCode.PLUGIN_CAPABILITY_INVALID_JSON.code())
        .isEqualTo("DEDIREN_PLUGIN_CAPABILITY_INVALID_JSON");
    assertThat(DiagnosticCode.PLUGIN_CAPABILITY_SCHEMA_INVALID.code())
        .isEqualTo("DEDIREN_PLUGIN_CAPABILITY_SCHEMA_INVALID");
    assertThat(DiagnosticCode.PLUGIN_OUTPUT_INVALID_JSON.code())
        .isEqualTo("DEDIREN_PLUGIN_OUTPUT_INVALID_JSON");
    assertThat(DiagnosticCode.PLUGIN_OUTPUT_INVALID_ENVELOPE.code())
        .isEqualTo("DEDIREN_PLUGIN_OUTPUT_INVALID_ENVELOPE");
    assertThat(DiagnosticCode.PLUGIN_OUTPUT_INVALID_DATA.code())
        .isEqualTo("DEDIREN_PLUGIN_OUTPUT_INVALID_DATA");
  }

  @Test
  void commandSourceAndLayoutFamiliesExposeTheirCanonicalWireStrings() {
    assertThat(DiagnosticCode.COMMAND_INPUT_INVALID.code())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(DiagnosticCode.VALIDATE_PROFILE_REQUIRED.code())
        .isEqualTo("DEDIREN_VALIDATE_PROFILE_REQUIRED");
    assertThat(DiagnosticCode.VALIDATE_PLUGIN_REQUIRED.code())
        .isEqualTo("DEDIREN_VALIDATE_PLUGIN_REQUIRED");
    assertThat(DiagnosticCode.SCHEMA_INVALID.code()).isEqualTo("DEDIREN_SCHEMA_INVALID");
    assertThat(DiagnosticCode.DUPLICATE_ID.code()).isEqualTo("DEDIREN_DUPLICATE_ID");
    assertThat(DiagnosticCode.DANGLING_ENDPOINT.code()).isEqualTo("DEDIREN_DANGLING_ENDPOINT");
    assertThat(DiagnosticCode.FRAGMENT_CONFLICT.code()).isEqualTo("DEDIREN_FRAGMENT_CONFLICT");
    assertThat(DiagnosticCode.LAYOUT_ROUTE_POINTS_EMPTY.code())
        .isEqualTo("DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY");
    assertThat(DiagnosticCode.LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE.code())
        .isEqualTo("DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE");
    assertThat(DiagnosticCode.LAYOUT_QUALITY_WARNING.code())
        .isEqualTo("DEDIREN_LAYOUT_QUALITY_WARNING");
  }

  @Test
  void wireStringsAreUniqueAndPrefixed() {
    var codes =
        Arrays.stream(DiagnosticCode.values())
            .map(DiagnosticCode::code)
            .collect(Collectors.toSet());
    assertThat(codes).hasSize(DiagnosticCode.values().length);
    assertThat(codes).allMatch(code -> code.startsWith("DEDIREN_"));
  }
}

package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DiagnosticCodeTest {
  @Test
  void engineRegistryFamilyExposesItsCanonicalWireStrings() {
    // PLUGIN_UNKNOWN and PLUGIN_UNSUPPORTED_CAPABILITY keep the wire strings published by the
    // retired process runtime; ENGINE_FAILED is the successor of the process-crash category.
    assertThat(DiagnosticCode.PLUGIN_UNKNOWN.code()).isEqualTo("DEDIREN_PLUGIN_UNKNOWN");
    assertThat(DiagnosticCode.PLUGIN_UNSUPPORTED_CAPABILITY.code())
        .isEqualTo("DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY");
    assertThat(DiagnosticCode.ENGINE_FAILED.code()).isEqualTo("DEDIREN_ENGINE_FAILED");
    assertThat(DiagnosticCode.XMI_SCHEMA_UNAVAILABLE.code())
        .isEqualTo("DEDIREN_XMI_SCHEMA_UNAVAILABLE");
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
    assertThat(DiagnosticCode.LAYOUT_SEQUENCE_INVARIANT_VIOLATED.code())
        .isEqualTo("DEDIREN_LAYOUT_SEQUENCE_INVARIANT_VIOLATED");
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

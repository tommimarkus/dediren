package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DiagnosticCodeTest {
    @Test
    void eachConstantExposesItsCanonicalWireString() {
        assertThat(DiagnosticCode.PLUGIN_TIMEOUT.code()).isEqualTo("DEDIREN_PLUGIN_TIMEOUT");
        assertThat(DiagnosticCode.PLUGIN_PROCESS_FAILED.code()).isEqualTo("DEDIREN_PLUGIN_PROCESS_FAILED");
        assertThat(DiagnosticCode.OEF_SCHEMA_VALIDATOR_UNAVAILABLE.code())
                .isEqualTo("DEDIREN_OEF_SCHEMA_VALIDATOR_UNAVAILABLE");
    }

    @Test
    void wireStringsAreUniqueAndPrefixed() {
        var codes = Arrays.stream(DiagnosticCode.values()).map(DiagnosticCode::code).collect(Collectors.toSet());
        assertThat(codes).hasSize(DiagnosticCode.values().length);
        assertThat(codes).allMatch(code -> code.startsWith("DEDIREN_"));
    }
}

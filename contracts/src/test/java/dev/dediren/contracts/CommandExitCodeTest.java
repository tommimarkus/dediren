package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CommandExitCodeTest {
    @Test
    void eachConstantExposesItsWireCode() {
        assertThat(CommandExitCode.OK.code()).isEqualTo(0);
        assertThat(CommandExitCode.INPUT_ERROR.code()).isEqualTo(2);
        assertThat(CommandExitCode.PLUGIN_ERROR.code()).isEqualTo(3);
    }

    @Test
    void wireCodesAreUnique() {
        var codes = Arrays.stream(CommandExitCode.values()).map(CommandExitCode::code).collect(Collectors.toSet());
        assertThat(codes).hasSize(CommandExitCode.values().length);
    }
}

package dev.dediren.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommandEnvelopeAssertionsTest {
    private static final String OK = "{\"status\":\"ok\",\"data\":{\"n\":1},\"diagnostics\":[]}";
    private static final String ERR =
            "{\"status\":\"error\",\"data\":null,\"diagnostics\":[{\"code\":\"DEDIREN_X\"}]}";

    @Test
    void okDataReturnsDataNodeOnSuccess() throws Exception {
        assertThat(CommandEnvelopeAssertions.okData(OK).at("/n").asInt()).isEqualTo(1);
    }

    @Test
    void okDataFailsWhenStatusIsError() {
        assertThatThrownBy(() -> CommandEnvelopeAssertions.okData(ERR))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void assertErrorCodeMatchesTheFirstDiagnostic() throws Exception {
        CommandEnvelopeAssertions.assertErrorCode(ERR, "DEDIREN_X");
    }

    @Test
    void assertErrorCodeFailsOnWrongCode() {
        assertThatThrownBy(() -> CommandEnvelopeAssertions.assertErrorCode(ERR, "DEDIREN_Y"))
                .isInstanceOf(AssertionError.class);
    }
}

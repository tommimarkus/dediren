package dev.dediren.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared assertions for the plugin/CLI command-envelope contract. Lives in {@code test-support} so
 * every module's tests can assert envelopes the same way. Uses a plain Jackson mapper deliberately:
 * {@code test-support} must not depend on {@code contracts} (which depends on {@code test-support} at
 * test scope), so it cannot use the contracts {@code JsonSupport} mapper.
 */
public final class CommandEnvelopeAssertions {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CommandEnvelopeAssertions() {
    }

    /** Asserts the envelope status is {@code ok} and returns its {@code data} node. */
    public static JsonNode okData(String stdout) throws Exception {
        JsonNode envelope = MAPPER.readTree(stdout);
        assertThat(envelope.at("/status").asText()).describedAs(stdout).isEqualTo("ok");
        return envelope.get("data");
    }

    /** Asserts the envelope status is {@code error} and its first diagnostic code equals {@code expectedCode}. */
    public static void assertErrorCode(String stdout, String expectedCode) throws Exception {
        JsonNode envelope = MAPPER.readTree(stdout);
        assertThat(envelope.at("/status").asText()).describedAs(stdout).isEqualTo("error");
        assertThat(envelope.at("/diagnostics/0/code").asText()).describedAs(stdout).isEqualTo(expectedCode);
    }
}

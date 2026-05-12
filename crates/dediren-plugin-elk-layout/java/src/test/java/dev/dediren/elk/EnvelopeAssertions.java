package dev.dediren.elk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

final class EnvelopeAssertions {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EnvelopeAssertions() {}

    static JsonNode parseJson(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    static JsonNode okData(String text) throws Exception {
        JsonNode envelope = parseJson(text);
        assertEquals("envelope.schema.v1", envelope.path("envelope_schema_version").asText());
        assertEquals("ok", envelope.path("status").asText());
        assertTrue(envelope.path("diagnostics").isArray());
        assertEquals(0, envelope.path("diagnostics").size());
        return envelope.path("data");
    }

    static JsonNode errorEnvelope(String text, String expectedCode) throws Exception {
        JsonNode envelope = parseJson(text);
        assertEquals("envelope.schema.v1", envelope.path("envelope_schema_version").asText());
        assertEquals("error", envelope.path("status").asText());
        assertTrue(envelope.path("diagnostics").isArray());
        assertFalse(envelope.path("diagnostics").isEmpty());
        List<String> codes = diagnosticCodes(envelope);
        assertTrue(
            codes.contains(expectedCode),
            "expected diagnostic code " + expectedCode + ", got " + codes
        );
        return envelope;
    }

    static List<String> diagnosticCodes(JsonNode envelope) {
        List<String> codes = new ArrayList<>();
        for (JsonNode diagnostic : envelope.path("diagnostics")) {
            codes.add(diagnostic.path("code").asText());
        }
        return codes;
    }
}

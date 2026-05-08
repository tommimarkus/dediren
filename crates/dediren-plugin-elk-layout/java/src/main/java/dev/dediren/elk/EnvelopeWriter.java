package dev.dediren.elk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

final class EnvelopeWriter {
    private EnvelopeWriter() {
    }

    static String ok(ObjectMapper mapper, JsonContracts.LayoutResult result)
        throws JsonProcessingException {
        return mapper.writeValueAsString(new JsonContracts.CommandEnvelope<>(
            "envelope.schema.v1",
            "ok",
            result,
            List.of()));
    }

    static String error(ObjectMapper mapper, String code, String message)
        throws JsonProcessingException {
        JsonContracts.Diagnostic diagnostic =
            new JsonContracts.Diagnostic(code, "error", message, null);
        return mapper.writeValueAsString(new JsonContracts.CommandEnvelope<>(
            "envelope.schema.v1",
            "error",
            null,
            List.of(diagnostic)));
    }
}

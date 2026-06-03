package dev.dediren.core.source;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.CommandEnvelope;

public record ValidationResult(int exitCode, CommandEnvelope<JsonNode> envelope) {
}

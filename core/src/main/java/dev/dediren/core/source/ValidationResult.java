package dev.dediren.core.source;

import dev.dediren.contracts.CommandEnvelope;
import tools.jackson.databind.JsonNode;

public record ValidationResult(int exitCode, CommandEnvelope<JsonNode> envelope) {}

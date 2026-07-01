package dev.dediren.contracts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

public record CommandEnvelope<T>(
    String envelopeSchemaVersion, EnvelopeStatus status, T data, List<Diagnostic> diagnostics) {
  public CommandEnvelope {
    Objects.requireNonNull(envelopeSchemaVersion, "envelopeSchemaVersion");
    Objects.requireNonNull(status, "status");
    diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
  }

  public static <T> CommandEnvelope<T> ok(T data) {
    return new CommandEnvelope<>(
        ContractVersions.ENVELOPE_SCHEMA_VERSION, EnvelopeStatus.OK, data, List.of());
  }

  public static CommandEnvelope<JsonNode> error(List<Diagnostic> diagnostics) {
    return new CommandEnvelope<>(
        ContractVersions.ENVELOPE_SCHEMA_VERSION, EnvelopeStatus.ERROR, null, diagnostics);
  }

  public static TypeReference<CommandEnvelope<JsonNode>> jsonNodeEnvelopeType() {
    return new TypeReference<>() {};
  }
}

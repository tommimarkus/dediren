package dev.dediren.contracts;

import java.util.List;
import java.util.Objects;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

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

  /**
   * A non-failing result whose payload still carries a quality verdict the envelope must surface.
   * Unlike {@link #ok(Object)} it keeps {@code data} and attaches {@code warning}-severity
   * diagnostics, so a consumer reading only the envelope {@code status}/{@code diagnostics} sees
   * the verdict without descending into {@code data}.
   */
  public static <T> CommandEnvelope<T> warning(T data, List<Diagnostic> diagnostics) {
    return new CommandEnvelope<>(
        ContractVersions.ENVELOPE_SCHEMA_VERSION, EnvelopeStatus.WARNING, data, diagnostics);
  }

  public static CommandEnvelope<JsonNode> error(List<Diagnostic> diagnostics) {
    return new CommandEnvelope<>(
        ContractVersions.ENVELOPE_SCHEMA_VERSION, EnvelopeStatus.ERROR, null, diagnostics);
  }

  public static TypeReference<CommandEnvelope<JsonNode>> jsonNodeEnvelopeType() {
    return new TypeReference<>() {};
  }
}

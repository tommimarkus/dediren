package dev.dediren.contracts.export;

import tools.jackson.databind.JsonNode;

public record ExportResult(
    String exportResultSchemaVersion, String artifactKind, String content, JsonNode assurance) {
  public ExportResult(String exportResultSchemaVersion, String artifactKind, String content) {
    this(exportResultSchemaVersion, artifactKind, content, null);
  }
}

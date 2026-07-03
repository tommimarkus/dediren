package dev.dediren.contracts.export;

import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.source.SourceDocument;
import tools.jackson.databind.JsonNode;

public record ExportRequest(
    String exportRequestSchemaVersion,
    SourceDocument source,
    LayoutResult layoutResult,
    JsonNode policy) {}

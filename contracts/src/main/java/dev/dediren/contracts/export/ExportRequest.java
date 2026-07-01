package dev.dediren.contracts.export;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.source.SourceDocument;

public record ExportRequest(
    String exportRequestSchemaVersion,
    SourceDocument source,
    LayoutResult layoutResult,
    JsonNode policy) {}

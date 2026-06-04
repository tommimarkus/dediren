package dev.dediren.contracts.export;

import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.source.SourceDocument;

public record OefExportInput(
        String exportRequestSchemaVersion,
        SourceDocument source,
        LayoutResult layoutResult,
        OefExportPolicy policy) {
}

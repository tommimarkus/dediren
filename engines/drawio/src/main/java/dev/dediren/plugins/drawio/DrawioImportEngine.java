package dev.dediren.plugins.drawio;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ImportEngine;
import dev.dediren.plugins.drawio.mx.MxFile;
import dev.dediren.plugins.drawio.mx.MxReader;
import dev.dediren.plugins.drawio.read.DrawioSourceMapper;

/**
 * Native importer for draw.io (mxGraph/{@code .drawio}) documents. Structured like {@code
 * DotImportEngine}: {@link MxReader} decodes the source into an {@link MxFile}, {@link
 * DrawioSourceMapper} maps that onto a {@link SourceDocument}, and this seam returns the mapper's
 * accumulated diagnostics on a successful result. Either step can fail atomically with a published
 * {@code DEDIREN_DRAWIO_*} {@link EngineException}, which is surfaced verbatim rather than caught
 * here.
 */
public final class DrawioImportEngine implements ImportEngine {
  @Override
  public String id() {
    return "drawio";
  }

  @Override
  public EngineResult<SourceDocument> importSource(String source) throws EngineException {
    if (source == null) {
      throw DrawioLimits.syntax("draw.io source is required", 1, 1);
    }
    // Defense in depth: core's BoundedReads already enforces this ceiling on the CLI and MCP
    // lanes before the engine is handed a byte (see DrawioLimits), but the engine itself does not
    // yet trust that upstream gate any more than DotLexer trusts its own caller.
    if (DrawioLimits.utf8Length(source) > DrawioLimits.MAX_INPUT_BYTES) {
      throw DrawioLimits.limit(
          DiagnosticCode.DRAWIO_INPUT_TOO_LARGE,
          "draw.io source exceeds the " + DrawioLimits.MAX_INPUT_BYTES + " byte ceiling");
    }
    MxFile file = MxReader.read(source);
    DrawioSourceMapper.MappingResult result = DrawioSourceMapper.map(file);
    return new EngineResult<>(result.document(), result.diagnostics());
  }
}

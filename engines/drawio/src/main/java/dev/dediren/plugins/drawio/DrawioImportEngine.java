package dev.dediren.plugins.drawio;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ImportEngine;

/**
 * Skeleton draw.io (mxGraph/`.drawio`) importer. {@link #importSource} is filled in by a later
 * step and until then surfaces a structural {@link EngineException} rather than an implementation
 * stub silently returning nothing.
 */
public final class DrawioImportEngine implements ImportEngine {
  @Override
  public String id() {
    return "drawio";
  }

  @Override
  public EngineResult<SourceDocument> importSource(String source) throws EngineException {
    throw EngineException.structuralFailure(
        DiagnosticCode.DRAWIO_NOT_YET_IMPLEMENTED.code(),
        "The drawio import engine is not yet implemented.",
        null);
  }
}

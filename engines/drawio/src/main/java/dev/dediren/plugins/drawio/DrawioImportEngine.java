package dev.dediren.plugins.drawio;

import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ImportEngine;

/**
 * Skeleton draw.io (mxGraph/`.drawio`) importer. {@link #importSource} is filled in by a later
 * step. Until then it refuses outright rather than returning an empty document — deliberately as
 * an {@link UnsupportedOperationException}, not a diagnostic code, because a scaffold must not add
 * a dead entry to the published {@code DEDIREN_DRAWIO_*} vocabulary.
 */
public final class DrawioImportEngine implements ImportEngine {
  @Override
  public String id() {
    return "drawio";
  }

  @Override
  public EngineResult<SourceDocument> importSource(String source) throws EngineException {
    throw new UnsupportedOperationException("The drawio import engine is not yet implemented.");
  }
}

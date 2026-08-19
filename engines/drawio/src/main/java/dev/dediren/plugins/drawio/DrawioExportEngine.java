package dev.dediren.plugins.drawio;

import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ExportEngine;
import java.nio.file.Path;
import java.util.Map;

/**
 * Skeleton draw.io (mxGraph/`.drawio`) exporter. The published {@link #ARTIFACT_KIND} is fixed now
 * so downstream wiring can settle on it; {@link #export} is filled in by a later step. Until
 * then it refuses outright rather than returning an empty artifact — deliberately as an
 * {@link UnsupportedOperationException}, not a diagnostic code, because a scaffold must not add a
 * dead entry to the published {@code DEDIREN_DRAWIO_*} vocabulary.
 */
public final class DrawioExportEngine implements ExportEngine {
  public static final String ARTIFACT_KIND = "drawio+xml";

  @Override
  public String id() {
    return "drawio";
  }

  @Override
  public EngineResult<ExportResult> export(
      ExportRequest request, Map<String, String> env, Path productRoot) throws EngineException {
    throw new UnsupportedOperationException("The drawio export engine is not yet implemented.");
  }
}

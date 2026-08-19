package dev.dediren.plugins.drawio;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ExportEngine;
import java.nio.file.Path;
import java.util.Map;

/**
 * Skeleton draw.io (mxGraph/`.drawio`) exporter. The published {@link #ARTIFACT_KIND} is fixed now
 * so downstream wiring can settle on it; {@link #export} is filled in by a later step and until
 * then surfaces a structural {@link EngineException} rather than an implementation stub silently
 * returning nothing.
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
    throw EngineException.structuralFailure(
        DiagnosticCode.DRAWIO_NOT_YET_IMPLEMENTED.code(),
        "The drawio export engine is not yet implemented.",
        null);
  }
}

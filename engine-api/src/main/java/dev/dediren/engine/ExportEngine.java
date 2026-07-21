package dev.dediren.engine;

import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import java.nio.file.Path;
import java.util.Map;

/** Notation export to an on-disk model artifact, for example ArchiMate/OEF or UML/XMI. */
public interface ExportEngine {
  /** Stable engine id, for example {@code "archimate-oef"} or {@code "uml-xmi"}. */
  String id();

  EngineResult<ExportResult> export(
      ExportRequest request, Map<String, String> env, Path productRoot) throws EngineException;

  /**
   * The whole-model export: one artifact carrying every supplied laid-out view. Empty when the
   * notation does not (yet) support model-scoped interchange — the build driver simply skips the
   * aggregate for that lane, so a notation opts in by overriding (whole-model XMI deliberately
   * trails; see the wave-3 plan).
   */
  default java.util.Optional<EngineResult<ExportResult>> exportModel(
      ModelExportRequest request, Map<String, String> env, Path productRoot)
      throws EngineException {
    return java.util.Optional.empty();
  }
}

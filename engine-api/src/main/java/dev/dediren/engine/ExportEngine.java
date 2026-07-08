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
}

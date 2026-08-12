package dev.dediren.engine;

import dev.dediren.contracts.source.SourceDocument;

/** Converts one external textual notation into a validated Dediren source document. */
public interface ImportEngine {
  String id();

  EngineResult<SourceDocument> importSource(String source) throws EngineException;
}

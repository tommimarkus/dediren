package dev.dediren.plugins.dotimport;

import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ImportEngine;

/** Native importer for Dediren's supported DOT (Graphviz) subset. */
public final class DotImportEngine implements ImportEngine {
  @Override
  public String id() {
    return "dot";
  }

  @Override
  public EngineResult<SourceDocument> importSource(String source) throws EngineException {
    DotDocument document = DotParser.parse(source);
    DotMapper.MappingResult result = new DotMapper().map(document);
    return new EngineResult<>(result.document(), result.diagnostics());
  }
}

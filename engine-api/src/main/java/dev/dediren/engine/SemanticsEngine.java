package dev.dediren.engine;

import dev.dediren.contracts.layout.SemanticValidationResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.ir.SceneGraph;

/** Notation-specific semantics: source validation and projection into layout/render inputs. */
public interface SemanticsEngine {
  /** Stable engine id, for example {@code "generic-graph"}. */
  String id();

  EngineResult<SemanticValidationResult> validate(SourceDocument source, String profile)
      throws EngineException;

  EngineResult<SceneGraph> projectScene(SourceDocument source, String view) throws EngineException;

  EngineResult<RenderMetadata> projectRenderMetadata(SourceDocument source, String view)
      throws EngineException;
}

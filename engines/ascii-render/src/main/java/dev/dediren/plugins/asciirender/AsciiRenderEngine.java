package dev.dediren.plugins.asciirender;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.RenderEngine;
import dev.dediren.ir.LaidOutScene;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * First-party {@link RenderEngine} that emits text artifacts from a laid-out view. A skeleton
 * placeholder that returns an empty text artifact; later implementation will render the scene to a
 * character grid.
 */
public final class AsciiRenderEngine implements RenderEngine {
  @Override
  public String id() {
    return "ascii";
  }

  @Override
  public EngineResult<RenderResult> render(
      LaidOutScene layout, JsonNode policy, RenderMetadata metadataOrNull)
      throws EngineException {
    List<RenderArtifact> artifacts = List.of(new RenderArtifact("text", ""));
    return new EngineResult<>(
        new RenderResult(ContractVersions.RENDER_RESULT_SCHEMA_VERSION, artifacts),
        List.of());
  }
}

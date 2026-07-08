package dev.dediren.engine;

import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderResult;
import tools.jackson.databind.JsonNode;

/**
 * SVG rendering from a laid-out view. {@code policy} stays a raw {@link JsonNode} because the
 * render plugin validates its own render-policy JSON ({@code RenderInputValidator}); parsing it
 * into a typed {@code RenderPolicy} does not belong in this shared engine-facing surface.
 */
public interface RenderEngine {
  /** Stable engine id, for example {@code "render"}. */
  String id();

  EngineResult<RenderResult> render(
      LayoutResult layout, JsonNode policy, RenderMetadata metadataOrNull) throws EngineException;
}

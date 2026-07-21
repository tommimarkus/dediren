package dev.dediren.engine;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.source.SourceDocument;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The whole-model export seam: the full source document plus every laid-out view, passed in memory
 * by the build driver (this is an engine-seam type like {@code SceneGraph}, not a wire contract —
 * no public schema). Views arrive in build order and the composed artifact must follow it
 * deterministically.
 */
public record ModelExportRequest(SourceDocument source, List<ViewLayout> views, JsonNode policy) {
  public ModelExportRequest {
    views = listOrEmpty(views);
  }

  public record ViewLayout(String viewId, LayoutResult layout) {}
}

package dev.dediren.contracts.render;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import java.util.Map;

public record RenderMetadata(
    String renderMetadataSchemaVersion,
    String semanticProfile,
    Map<String, RenderMetadataSelector> nodes,
    Map<String, RenderMetadataSelector> edges,
    Map<String, RenderMetadataSelector> groups) {
  public RenderMetadata {
    nodes = mapOrEmpty(nodes);
    edges = mapOrEmpty(edges);
    groups = mapOrEmpty(groups);
  }
}

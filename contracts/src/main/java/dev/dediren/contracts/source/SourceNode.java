package dev.dediren.contracts.source;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import dev.dediren.contracts.layout.LayoutLayerConstraint;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record SourceNode(
    String id,
    String type,
    String label,
    Map<String, JsonNode> properties,
    Integer partition,
    LayoutLayerConstraint layerConstraint) {
  public SourceNode {
    properties = mapOrEmpty(properties);
  }

  public SourceNode(String id, String type, String label, Map<String, JsonNode> properties) {
    this(id, type, label, properties, null, null);
  }
}

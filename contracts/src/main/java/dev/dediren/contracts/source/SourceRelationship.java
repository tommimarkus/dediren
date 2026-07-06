package dev.dediren.contracts.source;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import dev.dediren.contracts.layout.LayoutEdgePriority;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record SourceRelationship(
    String id,
    String type,
    String source,
    String target,
    String label,
    Map<String, JsonNode> properties,
    LayoutEdgePriority priority) {
  public SourceRelationship {
    properties = mapOrEmpty(properties);
  }

  public SourceRelationship(
      String id,
      String type,
      String source,
      String target,
      String label,
      Map<String, JsonNode> properties) {
    this(id, type, source, target, label, properties, null);
  }
}

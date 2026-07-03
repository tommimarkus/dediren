package dev.dediren.contracts.source;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import java.util.Map;
import tools.jackson.databind.JsonNode;

public record SourceRelationship(
    String id,
    String type,
    String source,
    String target,
    String label,
    Map<String, JsonNode> properties) {
  public SourceRelationship {
    properties = mapOrEmpty(properties);
  }
}

package dev.dediren.contracts.source;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import java.util.Map;
import tools.jackson.databind.JsonNode;

public record SourceNode(String id, String type, String label, Map<String, JsonNode> properties) {
  public SourceNode {
    properties = mapOrEmpty(properties);
  }
}

package dev.dediren.contracts.source;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;
import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public record SourceDocument(
    String modelSchemaVersion,
    List<String> fragments,
    List<PluginRequirement> requiredPlugins,
    List<SourceNode> nodes,
    List<SourceRelationship> relationships,
    Map<String, JsonNode> plugins) {
  public SourceDocument {
    fragments = listOrEmpty(fragments);
    requiredPlugins = listOrEmpty(requiredPlugins);
    nodes = listOrEmpty(nodes);
    relationships = listOrEmpty(relationships);
    plugins = mapOrEmpty(plugins);
  }
}

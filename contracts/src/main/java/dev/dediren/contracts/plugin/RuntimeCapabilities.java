package dev.dediren.contracts.plugin;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;
import tools.jackson.databind.JsonNode;

public record RuntimeCapabilities(
    String pluginProtocolVersion, String id, List<String> capabilities, JsonNode runtime) {
  public RuntimeCapabilities {
    capabilities = listOrEmpty(capabilities);
  }
}

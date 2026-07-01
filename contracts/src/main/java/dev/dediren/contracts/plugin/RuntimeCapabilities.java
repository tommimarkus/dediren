package dev.dediren.contracts.plugin;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record RuntimeCapabilities(
    String pluginProtocolVersion, String id, List<String> capabilities, JsonNode runtime) {
  public RuntimeCapabilities {
    capabilities = listOrEmpty(capabilities);
  }
}

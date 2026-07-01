package dev.dediren.contracts.source;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record GenericGraphPluginData(
    GenericGraphSemanticProfile semanticProfile, List<GenericGraphView> views) {
  public GenericGraphPluginData {
    views = listOrEmpty(views);
  }
}

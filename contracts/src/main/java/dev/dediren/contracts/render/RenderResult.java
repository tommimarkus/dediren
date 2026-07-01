package dev.dediren.contracts.render;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record RenderResult(String renderResultSchemaVersion, List<RenderArtifact> artifacts) {
  public RenderResult {
    artifacts = listOrEmpty(artifacts);
  }
}

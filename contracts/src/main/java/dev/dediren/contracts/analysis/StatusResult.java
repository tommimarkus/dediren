package dev.dediren.contracts.analysis;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

/**
 * The `dediren status` payload: a read-only freshness index of one workspace tree — every model
 * document with its canonical hash, and every recognized artifact classified {@code current} (stamp
 * matches a present model), {@code stale}, or {@code unstamped}. An index, not a gate: `verify` is
 * the exit-decidable check.
 */
public record StatusResult(
    String statusResultSchemaVersion, List<ModelEntry> models, List<ArtifactEntry> artifacts) {
  public StatusResult {
    models = listOrEmpty(models);
    artifacts = listOrEmpty(artifacts);
  }

  public record ModelEntry(String path, String sha256) {}

  /** {@code modelSha256} is the stamp's claim, present only on stamped artifacts. */
  public record ArtifactEntry(String path, String status, String modelSha256) {}
}

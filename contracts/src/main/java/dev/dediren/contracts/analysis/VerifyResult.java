package dev.dediren.contracts.analysis;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

/**
 * The `dediren verify` payload: every recognized artifact under the checked tree classified against
 * the model's recomputed canonical hash — {@code current}, {@code stale}, or {@code unstamped}. The
 * stale case is the CI drift gate; the envelope carrying this data is exit-decidable.
 */
public record VerifyResult(
    String verifyResultSchemaVersion, String modelSha256, List<ArtifactStatus> artifacts) {
  public VerifyResult {
    artifacts = listOrEmpty(artifacts);
  }

  public record ArtifactStatus(String path, String status) {}
}

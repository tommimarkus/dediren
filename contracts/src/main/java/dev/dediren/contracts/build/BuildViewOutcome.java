package dev.dediren.contracts.build;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.EnvelopeStatus;
import java.util.List;

/**
 * The per-view result of a build: its {@code viewId}, an aggregate {@code status} over the view's
 * stages, the {@code artifacts} written for it, and the {@code diagnostics} surfaced by any stage
 * (reusing the command-envelope {@link Diagnostic} shape, preserved unchanged from the stage that
 * emitted them). A view that failed carries {@link EnvelopeStatus#ERROR} and stops at the failing
 * stage, so its {@code artifacts} may be partial or empty.
 */
public record BuildViewOutcome(
    String viewId,
    EnvelopeStatus status,
    List<BuildArtifact> artifacts,
    List<Diagnostic> diagnostics) {
  public BuildViewOutcome {
    artifacts = listOrEmpty(artifacts);
    diagnostics = listOrEmpty(diagnostics);
  }
}

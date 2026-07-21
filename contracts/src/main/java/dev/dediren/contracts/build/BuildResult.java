package dev.dediren.contracts.build;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.EnvelopeStatus;
import java.util.List;

/**
 * The public aggregate of a {@code build} run: the {@code status} rolled up over every view ({@code
 * error} if any view failed, else {@code warning} if any view warned, else {@code ok}) and the
 * per-view {@link BuildViewOutcome} list in deterministic model order. {@code diagnostics} carries
 * build-level diagnostics that are not attributable to a single view (for example an invalid source
 * document or a missing output lane); it is omitted when empty so a normal run is exactly a status
 * plus its views. This document is the build command's top-level stdout, validated by {@code
 * schemas/build-result.schema.json}.
 */
public record BuildResult(
    String buildResultSchemaVersion,
    EnvelopeStatus status,
    List<BuildViewOutcome> views,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Diagnostic> diagnostics,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<BuildArtifact> modelArtifacts) {
  public BuildResult {
    views = listOrEmpty(views);
    diagnostics = listOrEmpty(diagnostics);
    modelArtifacts = listOrEmpty(modelArtifacts);
  }

  public BuildResult(
      String buildResultSchemaVersion,
      EnvelopeStatus status,
      List<BuildViewOutcome> views,
      List<Diagnostic> diagnostics) {
    this(buildResultSchemaVersion, status, views, diagnostics, List.of());
  }
}

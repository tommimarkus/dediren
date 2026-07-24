package dev.dediren.contracts.pkg;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.EnvelopeStatus;
import java.util.List;

/**
 * The normalized result of a package build (schema {@code package-build-result.schema.v1}): one
 * document naming every artifact at its declared path, with per-view and per-export status and
 * diagnostics rolled up into a single top-level {@code status}. Unlike legacy {@code build}'s bare
 * result, this rides inside a standard command envelope on the new surface.
 */
public record PackageBuildResult(
    String packageBuildResultSchemaVersion,
    EnvelopeStatus status,
    List<PackageViewOutcome> views,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PackageExportOutcome> exports,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Diagnostic> diagnostics) {
  public PackageBuildResult {
    views = listOrEmpty(views);
    exports = listOrEmpty(exports);
    diagnostics = listOrEmpty(diagnostics);
  }
}

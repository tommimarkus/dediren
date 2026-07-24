package dev.dediren.contracts.pkg;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;
import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.EnvelopeStatus;
import java.util.List;
import java.util.Map;

/**
 * The outcome of building one view in a package: its status, the produced artifacts keyed by
 * declared kind ({@code diagram} / {@code render_metadata} / {@code layout}) mapped to their final
 * paths, the echoed opaque {@code presentation}, and any diagnostics.
 */
public record PackageViewOutcome(
    String viewId,
    EnvelopeStatus status,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, String> artifacts,
    PackagePresentation presentation,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Diagnostic> diagnostics) {
  public PackageViewOutcome {
    artifacts = mapOrEmpty(artifacts);
    diagnostics = listOrEmpty(diagnostics);
  }
}

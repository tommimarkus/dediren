package dev.dediren.contracts.pkg;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.EnvelopeStatus;
import java.util.List;

/**
 * The outcome of one export in a package: its status, the echoed target ({@code view} or {@code
 * model}, whichever it targeted), the produced {@code artifact} path, and any diagnostics.
 */
public record PackageExportOutcome(
    String exportId,
    String view,
    String model,
    EnvelopeStatus status,
    String artifact,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Diagnostic> diagnostics) {
  public PackageExportOutcome {
    diagnostics = listOrEmpty(diagnostics);
  }
}

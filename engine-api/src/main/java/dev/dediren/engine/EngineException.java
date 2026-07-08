package dev.dediren.engine;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.Diagnostic;
import java.io.Serial;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Structured engine failure: the diagnostics become the error envelope; exitCode preserves the
 * engine's published non-zero process exit code.
 */
public final class EngineException extends Exception {
  @Serial private static final long serialVersionUID = 1L;

  private final List<Diagnostic> diagnostics;
  private final int exitCode;

  public EngineException(List<Diagnostic> diagnostics, int exitCode) {
    super(summarize(diagnostics));
    this.diagnostics = listOrEmpty(diagnostics);
    this.exitCode = exitCode;
  }

  public List<Diagnostic> diagnostics() {
    return diagnostics;
  }

  public int exitCode() {
    return exitCode;
  }

  private static String summarize(List<Diagnostic> diagnostics) {
    return listOrEmpty(diagnostics).stream()
        .map(Diagnostic::message)
        .collect(Collectors.joining("; "));
  }
}

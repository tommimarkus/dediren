package dev.dediren.engine;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
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

  /**
   * The semantics engines' failure shape: one ERROR diagnostic and exit 3.
   *
   * <p>Every notation front end and the profile router spelled this out identically in a private
   * {@code failure(code, message, path)} helper. The "one diagnostic, severity ERROR, exit 3"
   * policy is a property of the engine boundary, not of any one notation, so it belongs here — a
   * fourth notation would otherwise have copied it a fourth time, and a change to the exit code
   * would have had to find every copy.
   */
  public static EngineException semanticFailure(String code, String message, String path) {
    return new EngineException(
        List.of(new Diagnostic(code, DiagnosticSeverity.ERROR, message, path)), 3);
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

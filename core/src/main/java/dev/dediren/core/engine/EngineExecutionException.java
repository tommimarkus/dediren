package dev.dediren.core.engine;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;

public final class EngineExecutionException extends Exception {
  private final String code;
  private final String diagnosticPath;

  private EngineExecutionException(String code, String diagnosticPath, String message) {
    super(message);
    this.code = code;
    this.diagnosticPath = diagnosticPath;
  }

  public static EngineExecutionException plugin(String code, String pluginId, String message) {
    return new EngineExecutionException(code, "plugin:" + pluginId, message);
  }

  public static EngineExecutionException command(String code, String command, String message) {
    return new EngineExecutionException(code, "command:" + command, message);
  }

  public Diagnostic diagnostic() {
    return new Diagnostic(code, DiagnosticSeverity.ERROR, getMessage(), diagnosticPath);
  }
}

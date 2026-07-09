package dev.dediren.contracts;

import java.util.Objects;

public record Diagnostic(
    String code, DiagnosticSeverity severity, String message, String path, String sourcePointer) {
  public Diagnostic {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(message, "message");
  }

  public Diagnostic(String code, DiagnosticSeverity severity, String message, String path) {
    this(code, severity, message, path, null);
  }
}

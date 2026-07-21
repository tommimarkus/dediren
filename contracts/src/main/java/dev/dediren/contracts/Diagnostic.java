package dev.dediren.contracts;

import java.util.Objects;

public record Diagnostic(
    String code,
    DiagnosticSeverity severity,
    String message,
    String path,
    String sourcePointer,
    MigrationPath migration) {
  public Diagnostic {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(message, "message");
  }

  public Diagnostic(String code, DiagnosticSeverity severity, String message, String path) {
    this(code, severity, message, path, null, null);
  }

  public Diagnostic(
      String code, DiagnosticSeverity severity, String message, String path, String sourcePointer) {
    this(code, severity, message, path, sourcePointer, null);
  }

  /** This diagnostic with a machine-readable migration path attached (version-gate use). */
  public Diagnostic withMigration(MigrationPath migrationPath) {
    return new Diagnostic(code, severity, message, path, sourcePointer, migrationPath);
  }
}

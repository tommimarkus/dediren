package dev.dediren.plugins.dotimport;

/** A 1-based source location, formatted the way the published diagnostic {@code path} expects. */
record DotLocation(int line, int column) {
  String toDiagnosticPath() {
    return "line " + line + ", column " + column;
  }
}

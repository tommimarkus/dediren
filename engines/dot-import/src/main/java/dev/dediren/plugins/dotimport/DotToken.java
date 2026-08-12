package dev.dediren.plugins.dotimport;

/** One lexical token with its 1-based source location. */
record DotToken(DotTokenType type, String text, DotTokenKind kind, int line, int column) {
  DotLocation location() {
    return new DotLocation(line, column);
  }

  boolean isKeyword(String keyword) {
    return type == DotTokenType.ID && kind == DotTokenKind.PLAIN && text.equalsIgnoreCase(keyword);
  }
}

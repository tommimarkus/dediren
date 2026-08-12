package dev.dediren.plugins.dotimport;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.engine.EngineException;

/**
 * Bounded-input ceilings for the DOT lexer/parser, mirroring {@code MermaidParser}'s ceilings
 * exactly, plus the shared diagnostic-construction helpers both the lexer and parser use.
 */
final class DotLimits {
  static final long MAX_INPUT_BYTES = 64L * 1024 * 1024;
  static final int MAX_ELEMENTS = 100_000;
  static final int MAX_STATEMENTS = 200_000;
  static final int MAX_NESTING = 256;
  static final int MAX_TOKEN_BYTES = 64 * 1024;

  private DotLimits() {}

  static void checkTokenBytes(String token) throws EngineException {
    if (utf8Length(token) > MAX_TOKEN_BYTES) {
      throw limit(DiagnosticCode.DOT_TOKEN_LIMIT_EXCEEDED, "DOT token exceeds the 64 KiB ceiling");
    }
  }

  static long utf8Length(String value) {
    long bytes = 0;
    for (int index = 0; index < value.length(); ) {
      int codePoint = value.codePointAt(index);
      bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
      index += Character.charCount(codePoint);
    }
    return bytes;
  }

  static EngineException syntax(String message, int line, int column) {
    return failure(DiagnosticCode.DOT_SYNTAX_INVALID, message, line, column);
  }

  static EngineException unsupported(String message, int line, int column) {
    return failure(DiagnosticCode.DOT_UNSUPPORTED_CONSTRUCT, message, line, column);
  }

  static EngineException failure(DiagnosticCode code, String message, int line, int column) {
    return EngineException.structuralFailure(
        code.code(), message, "line " + line + ", column " + column);
  }

  static EngineException limit(DiagnosticCode code, String message) {
    return EngineException.structuralFailure(code.code(), message, "$");
  }
}

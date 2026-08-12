package dev.dediren.plugins.dotimport;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.engine.EngineException;
import java.util.ArrayList;
import java.util.List;

/** Hand-rolled tokenizer for Dediren's bounded DOT import subset. */
final class DotLexer {
  private final String source;
  private final List<DotToken> tokens = new ArrayList<>();
  private int position;
  private int line = 1;
  private int column = 1;
  private boolean atLineStart = true;

  private DotLexer(String source) {
    this.source = source;
  }

  static List<DotToken> tokenize(String source) throws EngineException {
    if (DotLimits.utf8Length(source) > DotLimits.MAX_INPUT_BYTES) {
      throw DotLimits.limit(
          DiagnosticCode.DOT_INPUT_TOO_LARGE, "DOT source exceeds the 64 MiB input ceiling");
    }
    DotLexer lexer = new DotLexer(source);
    lexer.run();
    return lexer.tokens;
  }

  private void run() throws EngineException {
    while (true) {
      skipInsignificant();
      if (position >= source.length()) {
        tokens.add(new DotToken(DotTokenType.EOF, "", DotTokenKind.PLAIN, line, column));
        return;
      }
      char current = source.charAt(position);
      int startLine = line;
      int startColumn = column;
      if (current == '{') {
        advance();
        tokens.add(
            new DotToken(DotTokenType.LBRACE, "{", DotTokenKind.PLAIN, startLine, startColumn));
      } else if (current == '}') {
        advance();
        tokens.add(
            new DotToken(DotTokenType.RBRACE, "}", DotTokenKind.PLAIN, startLine, startColumn));
      } else if (current == '[') {
        advance();
        tokens.add(
            new DotToken(DotTokenType.LBRACKET, "[", DotTokenKind.PLAIN, startLine, startColumn));
      } else if (current == ']') {
        advance();
        tokens.add(
            new DotToken(DotTokenType.RBRACKET, "]", DotTokenKind.PLAIN, startLine, startColumn));
      } else if (current == ';') {
        advance();
        tokens.add(
            new DotToken(DotTokenType.SEMI, ";", DotTokenKind.PLAIN, startLine, startColumn));
      } else if (current == ',') {
        advance();
        tokens.add(
            new DotToken(DotTokenType.COMMA, ",", DotTokenKind.PLAIN, startLine, startColumn));
      } else if (current == '=') {
        advance();
        tokens.add(
            new DotToken(DotTokenType.EQUALS, "=", DotTokenKind.PLAIN, startLine, startColumn));
      } else if (current == ':') {
        advance();
        tokens.add(
            new DotToken(DotTokenType.COLON, ":", DotTokenKind.PLAIN, startLine, startColumn));
      } else if (current == '-' && peekChar(1) == '>') {
        advance();
        advance();
        tokens.add(
            new DotToken(DotTokenType.EDGE_OP, "->", DotTokenKind.PLAIN, startLine, startColumn));
      } else if (current == '-' && peekChar(1) == '-') {
        advance();
        advance();
        tokens.add(
            new DotToken(DotTokenType.EDGE_OP, "--", DotTokenKind.PLAIN, startLine, startColumn));
      } else if (current == '"') {
        lexQuoted(startLine, startColumn);
      } else if (current == '<') {
        lexHtml(startLine, startColumn);
      } else if (current == '-' || Character.isDigit(current)) {
        lexNumeral(startLine, startColumn);
      } else if (isIdStart(current)) {
        lexPlainId(startLine, startColumn);
      } else {
        throw DotLimits.syntax("unexpected character '" + current + "'", startLine, startColumn);
      }
    }
  }

  private void lexQuoted(int startLine, int startColumn) throws EngineException {
    advance(); // opening quote
    StringBuilder text = new StringBuilder();
    while (true) {
      if (position >= source.length()) {
        throw DotLimits.syntax("unterminated quoted identifier", startLine, startColumn);
      }
      char current = source.charAt(position);
      if (current == '\\' && peekChar(1) == '"') {
        text.append('"');
        advance();
        advance();
      } else if (current == '"') {
        advance();
        break;
      } else {
        text.append(current);
        advance();
      }
    }
    String value = text.toString();
    DotLimits.checkTokenBytes(value);
    tokens.add(new DotToken(DotTokenType.ID, value, DotTokenKind.QUOTED, startLine, startColumn));
  }

  private void lexHtml(int startLine, int startColumn) throws EngineException {
    advance(); // opening '<'
    StringBuilder text = new StringBuilder();
    int depth = 1;
    while (depth > 0) {
      if (position >= source.length()) {
        throw DotLimits.syntax("unterminated HTML-like label", startLine, startColumn);
      }
      char current = source.charAt(position);
      if (current == '<') {
        depth++;
      } else if (current == '>') {
        depth--;
        if (depth == 0) {
          advance();
          break;
        }
      }
      text.append(current);
      advance();
    }
    String value = text.toString();
    DotLimits.checkTokenBytes(value);
    tokens.add(new DotToken(DotTokenType.ID, value, DotTokenKind.HTML, startLine, startColumn));
  }

  private void lexNumeral(int startLine, int startColumn) throws EngineException {
    StringBuilder text = new StringBuilder();
    if (source.charAt(position) == '-') {
      text.append('-');
      advance();
    }
    boolean sawDigitOrDot = false;
    while (position < source.length()
        && (Character.isDigit(source.charAt(position)) || source.charAt(position) == '.')) {
      sawDigitOrDot = true;
      text.append(source.charAt(position));
      advance();
    }
    if (!sawDigitOrDot) {
      throw DotLimits.syntax("expected a numeral after '-'", startLine, startColumn);
    }
    String value = text.toString();
    DotLimits.checkTokenBytes(value);
    tokens.add(new DotToken(DotTokenType.ID, value, DotTokenKind.NUMERAL, startLine, startColumn));
  }

  private void lexPlainId(int startLine, int startColumn) throws EngineException {
    StringBuilder text = new StringBuilder();
    while (position < source.length() && isIdPart(source.charAt(position))) {
      text.append(source.charAt(position));
      advance();
    }
    String value = text.toString();
    DotLimits.checkTokenBytes(value);
    tokens.add(new DotToken(DotTokenType.ID, value, DotTokenKind.PLAIN, startLine, startColumn));
  }

  private static boolean isIdStart(char value) {
    return Character.isLetter(value) || value == '_' || value >= 0x80;
  }

  private static boolean isIdPart(char value) {
    return Character.isLetterOrDigit(value) || value == '_' || value >= 0x80;
  }

  private void skipInsignificant() throws EngineException {
    while (position < source.length()) {
      char current = source.charAt(position);
      if (Character.isWhitespace(current)) {
        advance();
      } else if (current == '/' && peekChar(1) == '/') {
        while (position < source.length() && source.charAt(position) != '\n') {
          advance();
        }
      } else if (current == '/' && peekChar(1) == '*') {
        int startLine = line;
        int startColumn = column;
        advance();
        advance();
        boolean closed = false;
        while (position < source.length()) {
          if (source.charAt(position) == '*' && peekChar(1) == '/') {
            advance();
            advance();
            closed = true;
            break;
          }
          advance();
        }
        if (!closed) {
          throw DotLimits.syntax("unterminated /* comment", startLine, startColumn);
        }
      } else if (current == '#' && atLineStart) {
        while (position < source.length() && source.charAt(position) != '\n') {
          advance();
        }
      } else {
        return;
      }
    }
  }

  private char peekChar(int offset) {
    int at = position + offset;
    return at < source.length() ? source.charAt(at) : '\0';
  }

  private void advance() {
    char current = source.charAt(position);
    position++;
    if (current == '\n') {
      line++;
      column = 1;
      atLineStart = true;
    } else {
      column++;
      if (!Character.isWhitespace(current)) {
        atLineStart = false;
      }
    }
  }
}

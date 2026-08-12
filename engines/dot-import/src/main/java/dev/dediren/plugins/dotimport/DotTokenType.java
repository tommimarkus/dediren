package dev.dediren.plugins.dotimport;

/** Lexical token categories the DOT lexer produces. */
enum DotTokenType {
  ID,
  LBRACE,
  RBRACE,
  LBRACKET,
  RBRACKET,
  SEMI,
  COMMA,
  EQUALS,
  COLON,
  EDGE_OP,
  EOF
}

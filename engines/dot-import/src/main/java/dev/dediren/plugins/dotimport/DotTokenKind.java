package dev.dediren.plugins.dotimport;

/**
 * How an {@link DotTokenType#ID} token was spelled in the source. Only {@link #PLAIN} tokens are
 * eligible to be matched as the {@code strict}/{@code graph}/{@code digraph}/{@code node}/{@code
 * edge}/{@code subgraph} keywords, so a quoted string spelled {@code "node"} stays an ordinary
 * identifier.
 */
enum DotTokenKind {
  PLAIN,
  QUOTED,
  NUMERAL,
  HTML
}

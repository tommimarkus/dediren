package dev.dediren.plugins.dotimport;

/** A single resolved statement inside a DOT graph or subgraph body. */
sealed interface DotStatement permits DotNodeStatement, DotEdgeStatement, DotSubgraphStatement {}

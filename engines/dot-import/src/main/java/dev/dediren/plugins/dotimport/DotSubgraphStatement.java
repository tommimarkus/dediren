package dev.dediren.plugins.dotimport;

import java.util.List;
import java.util.Map;

/**
 * A {@code subgraph [ID] { ... }} statement. {@code id} is {@code null} for an anonymous named
 * subgraph body reached only through the {@code subgraph} keyword (this parser never accepts the
 * brace-only anonymous-subgraph shorthand as a statement; see {@code DotParser}). {@code cluster}
 * is {@code true} when {@code id} starts with {@code cluster_}. {@code attributes} are the
 * subgraph's own {@code graph}-scoped attributes (from {@code graph [...]} or bare {@code
 * key=value} statements inside its body); {@code statements} are this subgraph's direct children,
 * each already resolved against the defaults live at its own point of declaration.
 */
record DotSubgraphStatement(
    String id,
    boolean cluster,
    Map<String, String> attributes,
    List<DotStatement> statements,
    DotLocation location)
    implements DotStatement {}

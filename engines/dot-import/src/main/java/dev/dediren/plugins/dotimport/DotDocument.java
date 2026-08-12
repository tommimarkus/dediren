package dev.dediren.plugins.dotimport;

import java.util.List;
import java.util.Map;

/**
 * The parsed root of one DOT source: {@code [strict] (graph|digraph) [ID] { ... }}.
 *
 * <p>{@code attributes} are the root graph's own attributes (from {@code graph [...]} or bare
 * {@code key=value} statements at top level); {@code statements} are the top-level body, each
 * already resolved against the {@code node}/{@code edge} defaults live at its own point of
 * declaration.
 */
record DotDocument(
    boolean strict,
    boolean directed,
    String id,
    Map<String, String> attributes,
    List<DotStatement> statements) {}

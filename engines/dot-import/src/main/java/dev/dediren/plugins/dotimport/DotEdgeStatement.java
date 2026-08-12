package dev.dediren.plugins.dotimport;

import java.util.List;
import java.util.Map;

/**
 * An edge chain statement (for example {@code a -> b -> c}), with {@code attributes} already
 * resolved against the {@code edge} defaults live in the enclosing scope at this statement's
 * position. {@code endpoints} holds every node id in declaration order; adjacent pairs are the
 * individual edges the chain expands to, and every edge in the chain shares {@code attributes}.
 */
record DotEdgeStatement(
    List<String> endpoints, Map<String, String> attributes, DotLocation location)
    implements DotStatement {}

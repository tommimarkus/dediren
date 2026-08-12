package dev.dediren.plugins.dotimport;

import java.util.Map;

/**
 * A {@code node_id [attr_list]} statement, with {@code attributes} already resolved against the
 * {@code node} defaults live in the enclosing scope at this statement's position.
 */
record DotNodeStatement(String id, Map<String, String> attributes, DotLocation location)
    implements DotStatement {}

package dev.dediren.ir;

import dev.dediren.contracts.layout.LayoutEdgePriority;

/** A pre-layout scene edge. Carries a non-null {@link SourcePointer} origin. */
public record SceneEdge(
    String id,
    String source,
    String target,
    String label,
    SourcePointer origin,
    String relationshipType,
    LayoutEdgePriority priority) {}

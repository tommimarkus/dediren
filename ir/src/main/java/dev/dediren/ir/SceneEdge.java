package dev.dediren.ir;

import dev.dediren.contracts.layout.LayoutEdgePriority;

/**
 * A pre-layout scene edge; positionally mirrors {@link dev.dediren.contracts.layout.LayoutEdge}.
 */
public record SceneEdge(
    String id,
    String source,
    String target,
    String label,
    String sourceId,
    String relationshipType,
    LayoutEdgePriority priority,
    SourcePointer origin) {}

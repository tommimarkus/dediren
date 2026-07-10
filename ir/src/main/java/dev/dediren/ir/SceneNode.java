package dev.dediren.ir;

import dev.dediren.contracts.layout.LayoutLayerConstraint;

/**
 * A pre-layout scene node; positionally mirrors {@link dev.dediren.contracts.layout.LayoutNode}.
 */
public record SceneNode(
    String id,
    String label,
    String sourceId,
    Double widthHint,
    Double heightHint,
    String role,
    Integer partition,
    LayoutLayerConstraint layerConstraint,
    SourcePointer origin) {}

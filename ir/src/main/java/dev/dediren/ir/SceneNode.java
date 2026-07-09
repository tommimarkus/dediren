package dev.dediren.ir;

import dev.dediren.contracts.layout.LayoutLayerConstraint;

/** A pre-layout scene node. Carries a non-null {@link SourcePointer} origin. */
public record SceneNode(
    String id,
    String label,
    SourcePointer origin,
    Double widthHint,
    Double heightHint,
    String role,
    Integer partition,
    LayoutLayerConstraint layerConstraint) {}

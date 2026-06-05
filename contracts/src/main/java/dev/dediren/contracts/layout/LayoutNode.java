package dev.dediren.contracts.layout;

public record LayoutNode(
        String id,
        String label,
        String sourceId,
        Double widthHint,
        Double heightHint) {
}

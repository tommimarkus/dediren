package dev.dediren.contracts.layout;

public record LayoutPreferences(
        LayoutDirection direction,
        LayoutDensity density,
        LayoutWrapping wrapping,
        LayoutRoutingPreferences routing) {
}

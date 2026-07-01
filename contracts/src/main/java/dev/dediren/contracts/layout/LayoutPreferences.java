package dev.dediren.contracts.layout;

public record LayoutPreferences(
    LayoutMode mode,
    LayoutDirection direction,
    LayoutDensity density,
    LayoutWrapping wrapping,
    LayoutRoutingPreferences routing) {
  public LayoutPreferences(
      LayoutDirection direction,
      LayoutDensity density,
      LayoutWrapping wrapping,
      LayoutRoutingPreferences routing) {
    this(null, direction, density, wrapping, routing);
  }
}

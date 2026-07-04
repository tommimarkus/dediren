package dev.dediren.contracts.layout;

public record LayoutPreferences(
    LayoutMode mode,
    LayoutDirection direction,
    LayoutDensity density,
    LayoutWrapping wrapping,
    LayoutRoutingPreferences routing,
    LayoutCycleBreaking cycleBreaking,
    LayoutLayeringPreferences layering,
    LayoutCrossingPreferences crossing,
    LayoutPlacementPreferences placement) {

  public LayoutPreferences(
      LayoutDirection direction,
      LayoutDensity density,
      LayoutWrapping wrapping,
      LayoutRoutingPreferences routing) {
    this(null, direction, density, wrapping, routing, null, null, null, null);
  }

  public LayoutPreferences(
      LayoutMode mode,
      LayoutDirection direction,
      LayoutDensity density,
      LayoutWrapping wrapping,
      LayoutRoutingPreferences routing) {
    this(mode, direction, density, wrapping, routing, null, null, null, null);
  }
}

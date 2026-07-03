package dev.dediren.contracts.render;

/**
 * Accessible-name metadata for a rendered SVG. {@code title} names the graphic (the {@code <title>}
 * element, and the accessible name for {@code role="img"}); {@code description} is the optional
 * longer purpose text emitted as {@code <desc>}. Both are optional; when {@code title} is absent
 * the renderer falls back to the layout {@code view_id}, then to a generic default.
 */
public record SvgAccessibility(String title, String description) {}

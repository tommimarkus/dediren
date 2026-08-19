package dev.dediren.plugins.drawio.style;

/**
 * One resolved draw.io shape: the {@code style=} string mxGraph attaches to an exported cell,
 * paired with the boxed geometry that {@code style} implies. {@code width}/{@code height} are
 * separate from {@code style} because mxGraph carries geometry as sibling {@code mxGeometry}
 * attributes, not inside the style string itself.
 */
public record DrawioShape(String style, int width, int height) {}

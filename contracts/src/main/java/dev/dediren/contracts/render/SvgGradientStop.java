package dev.dediren.contracts.render;

/** One colour stop of a gradient fill: {@code offset} in [0,1], a colour, and optional opacity. */
public record SvgGradientStop(double offset, String color, Double opacity) {}

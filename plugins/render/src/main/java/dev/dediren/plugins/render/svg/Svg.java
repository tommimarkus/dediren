package dev.dediren.plugins.render.svg;

import java.util.Locale;

/**
 * Pure SVG text/number formatting primitives shared by the render entry point and the per-notation
 * decorators. No state and no rendering policy: just XML escaping, coordinate/number formatting,
 * and a cheap text-width estimate. Consumers static-import these so call sites stay unqualified.
 */
public final class Svg {
  private Svg() {}

  /** Coordinate/length formatter: drops the decimal for whole numbers, otherwise full precision. */
  public static String styleNumber(double value) {
    if (Math.rint(value) == value) {
      return Long.toString(Math.round(value));
    }
    return Double.toString(value);
  }

  /** Escapes a value for use inside an SVG/XML attribute (quotes included). */
  public static String attr(String value) {
    return (value == null ? "" : value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  /** Escapes a value for use as SVG/XML text content. */
  public static String text(String value) {
    return (value == null ? "" : value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  /** Floors to one decimal place and formats with a fixed locale (stable across environments). */
  public static String labelNumber(double value) {
    double floored = Math.floor(value * 10.0) / 10.0;
    return String.format(Locale.ROOT, "%.1f", floored);
  }

  /** Cheap monospace-ish width estimate used for label fitting. */
  public static double estimateTextWidth(String value, double fontSize) {
    return (value == null ? 0 : value.codePointCount(0, value.length())) * fontSize * 0.62;
  }
}

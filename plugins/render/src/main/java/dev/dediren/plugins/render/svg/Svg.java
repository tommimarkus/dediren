package dev.dediren.plugins.render.svg;

import java.util.Arrays;
import java.util.Locale;

/**
 * Pure SVG text/number formatting primitives shared by the render entry point and the per-notation
 * decorators. No state and no rendering policy: just XML escaping, coordinate/number formatting,
 * and a cheap text-width estimate. Consumers static-import these so call sites stay unqualified.
 */
public final class Svg {
  private Svg() {}

  // Per-glyph horizontal advances (fraction of an em) for the render font family
  // (Inter, Arial, sans-serif). Values are the Helvetica/Arial AFM advance widths
  // (originally 1/1000 em) for printable ASCII, which Inter and Arial both track
  // within a few percent. Summing these per string keeps estimateTextWidth within
  // a few percent of the natural rendered width — unlike the flat per-char constant
  // shipped for #25, which over-stated narrow-glyph labels (i/l/t/f/j/.) by ~40-50%
  // and under-stated wide ones (m/w/W/M). The estimate stays deterministic and
  // font-installation independent (the #25 goal): it is identical wherever the SVG
  // is produced, which is what lets textLength safely pin the rendered width.
  // Code points outside printable ASCII fall back to DEFAULT_ADVANCE_EM. See #39.
  private static final double DEFAULT_ADVANCE_EM = 0.600;
  private static final double[] ASCII_ADVANCE_EM = buildAsciiAdvanceTable();

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

  /**
   * Per-glyph width estimate used for label wrapping, font fitting, overlap boxes, and the {@code
   * textLength} width pin. Sums the em-advance of each code point (ASCII_ADVANCE_EM, else
   * DEFAULT_ADVANCE_EM) and scales by {@code fontSize}. Approximates the natural Inter/Arial width
   * within a few percent instead of a flat per-char constant, so the emitted textLength no longer
   * over-stretches narrow-glyph labels (see #39) while staying deterministic (#25).
   */
  public static double estimateTextWidth(String value, double fontSize) {
    if (value == null || value.isEmpty()) {
      return 0.0;
    }
    double emAdvance = 0.0;
    int index = 0;
    while (index < value.length()) {
      int codePoint = value.codePointAt(index);
      emAdvance += advanceEm(codePoint);
      index += Character.charCount(codePoint);
    }
    return emAdvance * fontSize;
  }

  private static double advanceEm(int codePoint) {
    if (codePoint >= 32 && codePoint < 127) {
      return ASCII_ADVANCE_EM[codePoint - 32];
    }
    return DEFAULT_ADVANCE_EM;
  }

  private static double[] buildAsciiAdvanceTable() {
    double[] em = new double[95]; // printable ASCII, code points 32..126
    Arrays.fill(em, DEFAULT_ADVANCE_EM);
    put(em, ' ', 0.278);
    put(em, '!', 0.278);
    put(em, '"', 0.355);
    put(em, '#', 0.556);
    put(em, '$', 0.556);
    put(em, '%', 0.889);
    put(em, '&', 0.667);
    put(em, '\'', 0.191);
    put(em, '(', 0.333);
    put(em, ')', 0.333);
    put(em, '*', 0.389);
    put(em, '+', 0.584);
    put(em, ',', 0.278);
    put(em, '-', 0.333);
    put(em, '.', 0.278);
    put(em, '/', 0.278);
    for (char digit = '0'; digit <= '9'; digit++) {
      put(em, digit, 0.556);
    }
    put(em, ':', 0.278);
    put(em, ';', 0.278);
    put(em, '<', 0.584);
    put(em, '=', 0.584);
    put(em, '>', 0.584);
    put(em, '?', 0.556);
    put(em, '@', 1.015);
    put(em, 'A', 0.667);
    put(em, 'B', 0.667);
    put(em, 'C', 0.722);
    put(em, 'D', 0.722);
    put(em, 'E', 0.667);
    put(em, 'F', 0.611);
    put(em, 'G', 0.778);
    put(em, 'H', 0.722);
    put(em, 'I', 0.278);
    put(em, 'J', 0.500);
    put(em, 'K', 0.667);
    put(em, 'L', 0.556);
    put(em, 'M', 0.833);
    put(em, 'N', 0.722);
    put(em, 'O', 0.778);
    put(em, 'P', 0.667);
    put(em, 'Q', 0.778);
    put(em, 'R', 0.722);
    put(em, 'S', 0.667);
    put(em, 'T', 0.611);
    put(em, 'U', 0.722);
    put(em, 'V', 0.667);
    put(em, 'W', 0.944);
    put(em, 'X', 0.667);
    put(em, 'Y', 0.667);
    put(em, 'Z', 0.611);
    put(em, '[', 0.278);
    put(em, '\\', 0.278);
    put(em, ']', 0.278);
    put(em, '^', 0.469);
    put(em, '_', 0.556);
    put(em, '`', 0.333);
    put(em, 'a', 0.556);
    put(em, 'b', 0.556);
    put(em, 'c', 0.500);
    put(em, 'd', 0.556);
    put(em, 'e', 0.556);
    put(em, 'f', 0.278);
    put(em, 'g', 0.556);
    put(em, 'h', 0.556);
    put(em, 'i', 0.222);
    put(em, 'j', 0.222);
    put(em, 'k', 0.500);
    put(em, 'l', 0.222);
    put(em, 'm', 0.833);
    put(em, 'n', 0.556);
    put(em, 'o', 0.556);
    put(em, 'p', 0.556);
    put(em, 'q', 0.556);
    put(em, 'r', 0.333);
    put(em, 's', 0.500);
    put(em, 't', 0.278);
    put(em, 'u', 0.556);
    put(em, 'v', 0.500);
    put(em, 'w', 0.722);
    put(em, 'x', 0.500);
    put(em, 'y', 0.500);
    put(em, 'z', 0.500);
    put(em, '{', 0.334);
    put(em, '|', 0.260);
    put(em, '}', 0.334);
    put(em, '~', 0.584);
    return em;
  }

  private static void put(double[] em, char character, double advanceEm) {
    em[character - 32] = advanceEm;
  }
}

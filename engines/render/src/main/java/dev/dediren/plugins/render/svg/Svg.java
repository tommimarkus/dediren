package dev.dediren.plugins.render.svg;

import dev.dediren.contracts.render.SvgEdgeLineStyle;
import java.util.Arrays;
import java.util.List;
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
  // Non-ASCII code points split two ways: East Asian Wide/Fullwidth glyphs (CJK, kana, Hangul,
  // fullwidth forms, emoji) occupy the full em square and use FULLWIDTH_ADVANCE_EM; every other
  // non-ASCII code point (accented Latin, currency, Greek, Cyrillic) is narrow and uses
  // DEFAULT_ADVANCE_EM. The earlier flat 0.600 em fallback under-measured CJK by ~40%, so the
  // emitted textLength then squeezed the rendered label — the #39 correction, extended to
  // non-ASCII. See #39.
  private static final double DEFAULT_ADVANCE_EM = 0.600;
  private static final double FULLWIDTH_ADVANCE_EM = 1.000;
  private static final double[] ASCII_ADVANCE_EM = buildAsciiAdvanceTable();

  /** Coordinate/length formatter: drops the decimal for whole numbers, otherwise full precision. */
  public static String styleNumber(double value) {
    if (Math.rint(value) == value) {
      return Long.toString(Math.round(value));
    }
    return Double.toString(value);
  }

  /**
   * A single optional presentation attribute (e.g. {@code fill-opacity}). Returns the empty string
   * when {@code value} is null so callers can unconditionally append it before an element's {@code
   * />}. The value is a validated number, so no escaping is needed.
   */
  public static String opacityAttr(String name, Double value) {
    return value == null ? "" : " " + name + "=\"" + styleNumber(value) + "\"";
  }

  /**
   * An optional attribute whose value is a single-word enum constant (e.g. {@code font-weight},
   * {@code font-style}). The constant name lowercased is the SVG/CSS keyword for every enum used
   * here (NORMAL→normal, BOLD→bold, ITALIC→italic, START/MIDDLE/END). Empty for null.
   */
  public static String enumAttr(String name, Enum<?> value) {
    return value == null ? "" : " " + name + "=\"" + value.name().toLowerCase(Locale.ROOT) + "\"";
  }

  /**
   * An optional string-valued attribute (e.g. {@code font-family}), XML-escaped. Empty for null.
   */
  public static String stringAttr(String name, String value) {
    return value == null ? "" : " " + name + "=\"" + attr(value) + "\"";
  }

  /**
   * The {@code stroke-dasharray} VALUE for a resolved line style: an explicit {@code dashPattern}
   * (space-joined) wins; otherwise the {@code dashedDefault} for {@code DASHED}, a fine dotted
   * pattern for {@code DOTTED}, and the empty string (solid) otherwise. Callers that own their own
   * dash default (e.g. the ArchiMate grouping border) use this raw value to reconcile.
   */
  public static String dashArrayValue(
      SvgEdgeLineStyle lineStyle, List<Double> dashPattern, String dashedDefault) {
    if (dashPattern != null && !dashPattern.isEmpty()) {
      StringBuilder joined = new StringBuilder();
      for (Double value : dashPattern) {
        if (joined.length() > 0) {
          joined.append(' ');
        }
        joined.append(styleNumber(value));
      }
      return joined.toString();
    }
    if (lineStyle == SvgEdgeLineStyle.DASHED) {
      return dashedDefault;
    }
    if (lineStyle == SvgEdgeLineStyle.DOTTED) {
      return "1 3";
    }
    return "";
  }

  /** The full {@code stroke-dasharray} attribute (with leading space), or empty for solid. */
  public static String dashArrayAttr(
      SvgEdgeLineStyle lineStyle, List<Double> dashPattern, String dashedDefault) {
    String value = dashArrayValue(lineStyle, dashPattern, dashedDefault);
    return value.isEmpty() ? "" : " stroke-dasharray=\"" + value + "\"";
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
    if (isFullWidth(codePoint)) {
      return FULLWIDTH_ADVANCE_EM;
    }
    return DEFAULT_ADVANCE_EM;
  }

  // Approximates Unicode East Asian Width "Wide" or "Fullwidth": the ranges whose glyphs occupy a
  // full em square in the fonts a viewer substitutes for them. Deterministic and font-installation
  // independent (the #25 goal), so the emitted textLength is identical wherever the SVG is
  // produced.
  private static boolean isFullWidth(int codePoint) {
    return (codePoint >= 0x1100 && codePoint <= 0x115F) // Hangul Jamo
        || (codePoint >= 0x2E80 && codePoint <= 0x303E) // CJK radicals, Kangxi, CJK symbols/punct
        || (codePoint >= 0x3041 && codePoint <= 0x33FF) // Hiragana, Katakana, CJK compatibility
        || (codePoint >= 0x3400 && codePoint <= 0x4DBF) // CJK Unified Ideographs Extension A
        || (codePoint >= 0x4E00 && codePoint <= 0x9FFF) // CJK Unified Ideographs
        || (codePoint >= 0xA000 && codePoint <= 0xA4CF) // Yi Syllables/Radicals
        || (codePoint >= 0xAC00 && codePoint <= 0xD7A3) // Hangul Syllables
        || (codePoint >= 0xF900 && codePoint <= 0xFAFF) // CJK Compatibility Ideographs
        || (codePoint >= 0xFE10 && codePoint <= 0xFE19) // Vertical forms
        || (codePoint >= 0xFE30 && codePoint <= 0xFE6F) // CJK compatibility & small form variants
        || (codePoint >= 0xFF00 && codePoint <= 0xFF60) // Fullwidth ASCII variants
        || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6) // Fullwidth signs
        || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF) // emoji & pictographs
        || (codePoint >= 0x20000 && codePoint <= 0x3FFFD); // CJK Extension B+ (supplementary)
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

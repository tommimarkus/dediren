package dev.dediren.plugins.render.svg;

import dev.dediren.contracts.render.SvgEdgeLineStyle;
import java.util.List;
import java.util.Locale;

/**
 * Pure SVG number/geometry formatting primitives shared by the render entry point and the
 * per-notation decorators. No state and no rendering policy: coordinate/number formatting,
 * stroke-dasharray assembly, and a cheap text-width estimate. XML escaping is {@link SvgWriter}'s
 * job. Consumers static-import these so call sites stay unqualified.
 */
public final class Svg {
  private Svg() {}

  /** One-decimal coordinate formatter. The geometry formatter every emitter uses. */
  public static String f1(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }

  /** Emits an opacity attribute value, or null when unset (so attrIf drops the attribute). */
  public static String opacity(Double value) {
    return value == null ? null : styleNumber(value);
  }

  /** Coordinate/length formatter: drops the decimal for whole numbers, otherwise full precision. */
  public static String styleNumber(double value) {
    if (Math.rint(value) == value) {
      return Long.toString(Math.round(value));
    }
    return Double.toString(value);
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

  /**
   * The one {@code stroke-dasharray} a shape writes on itself, given the dash its resolved style
   * asked for and the fallback its notation supplies — or {@code null} for "omit the attribute".
   *
   * <p>The precedence is SVG's, not this product's: a presentation attribute on the shape overrides
   * the same property inherited from a wrapper {@code <g>}. A shape that emitted only its notation
   * fallback would therefore silently outrank the user's dash riding that wrapper, and a shape that
   * emitted nothing would depend on whether a wrapper happened to be painted at all. So the shape
   * always states the winner: the user's dash when there is one, the notation's when there is not.
   * That rule used to be a comment above the one constant that depends on it, restated at each of
   * the sites that had to honour it.
   */
  public static String shapeDash(String resolvedDash, String notationFallback) {
    if (resolvedDash != null && !resolvedDash.isEmpty()) {
      return resolvedDash;
    }
    return notationFallback == null || notationFallback.isEmpty() ? null : notationFallback;
  }

  /** Floors to one decimal place and formats with a fixed locale (stable across environments). */
  public static String labelNumber(double value) {
    double floored = Math.floor(value * 10.0) / 10.0;
    return String.format(Locale.ROOT, "%.1f", floored);
  }

  public static double estimateTextWidth(String value, double fontSize) {
    return dev.dediren.ir.TextMetrics.estimateTextWidth(value, fontSize);
  }
}

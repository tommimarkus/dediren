package dev.dediren.plugins.render.svg;

import static dev.dediren.plugins.render.svg.Svg.styleNumber;

import dev.dediren.contracts.render.SvgEdgeMarkerEnd;
import java.util.Locale;

/**
 * Emits the {@code <marker>} adornment for one end of one edge.
 *
 * <p>The single home for marker geometry. The generic edge renderer and the UML sequence renderer
 * both draw arrowheads, and both used to carry their own copy of this — which is how the same
 * anchoring defect came to be fixed twice, once per copy (commits bc8936f and bec4fc8).
 */
public final class EdgeMarkers {

  private EdgeMarkers() {}

  // The marker viewport and reference point, in marker content units. markerUnits is deliberately
  // left unset below, so the SVG default of "strokeWidth" applies: every one of these is that many
  // *stroke widths* of user space, and the "10x10" in the class docs is 10px only on a 1px edge.
  // Anything measuring a marker has to scale by the edge's stroke width — see #inkBox.
  private static final double VIEWPORT = 10.0;
  private static final double REF_X_START = 1.0;
  private static final double REF_X_END = 9.0;
  private static final double REF_Y = 5.0;

  /**
   * Anchoring rule, stated once. A marker's box is 10x10 and a centred marker (refX=5) puts half
   * the adornment on the far side of the endpoint — where the node (which paints over edges) or the
   * lifeline stem hides or straddles it. So the marker is anchored at its endpoint-facing extent
   * instead: end markers point forward (tip at x=9), start markers trail back (base at x=1).
   */
  private static double refX(String side) {
    return "start".equals(side) ? REF_X_START : REF_X_END;
  }

  /**
   * The box the marker for {@code side} inks around the vertex it is anchored to, or {@code null}
   * for NONE, which draws nothing to measure.
   *
   * <p>Scaled by {@code strokeWidth} because {@code markerUnits} is unset (see the constants
   * above), and bounded by the viewport rather than by the path data because {@code overflow}
   * defaults to hidden — the viewport is the clip, and the arrowhead reaches it. Its tip is a 26.6°
   * miter join, which at the default miter limit of 4 is applied rather than beveled and so pushes
   * the outer tip past x=9 to about x=10.1; the clip, not the path, decides where that stops.
   *
   * <p>{@code orient="auto"} turns the viewport onto the route, so what is returned is the rotated
   * rectangle's axis-aligned bounding box.
   */
  public static LabelBox inkBox(
      String side,
      SvgEdgeMarkerEnd marker,
      double strokeWidth,
      double x,
      double y,
      double angleRadians) {
    if (marker == SvgEdgeMarkerEnd.NONE) {
      return null;
    }
    double anchorX = refX(side) * strokeWidth;
    double anchorY = REF_Y * strokeWidth;
    double viewport = VIEWPORT * strokeWidth;
    double cos = Math.cos(angleRadians);
    double sin = Math.sin(angleRadians);
    double minX = Double.POSITIVE_INFINITY;
    double minY = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY;
    double maxY = Double.NEGATIVE_INFINITY;
    for (double cornerX : new double[] {-anchorX, viewport - anchorX}) {
      for (double cornerY : new double[] {-anchorY, viewport - anchorY}) {
        double rotatedX = x + cornerX * cos - cornerY * sin;
        double rotatedY = y + cornerX * sin + cornerY * cos;
        minX = Math.min(minX, rotatedX);
        minY = Math.min(minY, rotatedY);
        maxX = Math.max(maxX, rotatedX);
        maxY = Math.max(maxY, rotatedY);
      }
    }
    return new LabelBox(minX, minY, maxX, maxY);
  }

  /** The fill a marker paints with, derived from its stroke colour: hollow forms sit on white. */
  public static String fill(SvgEdgeMarkerEnd marker, String stroke) {
    return switch (marker) {
      case HOLLOW_TRIANGLE, HOLLOW_DIAMOND, HOLLOW_CIRCLE -> "#ffffff";
      case OPEN_ARROW -> "none";
      default -> stroke;
    };
  }

  public static String markerName(SvgEdgeMarkerEnd marker) {
    return marker.name().toLowerCase(Locale.ROOT);
  }

  /**
   * Emits the marker for {@code side} ("start" or "end") of {@code edgeId}. NONE emits nothing.
   *
   * <p>Returns the minted element id, or {@code null} for NONE. The caller feeds that value back
   * through {@link SvgIds#reference} to build the referencing {@code url(#…)}: the edge id alone is
   * not enough, because {@link SvgIds} may have had to sanitize or suffix it.
   */
  public static String emit(
      SvgWriter w, SvgIds ids, String edgeId, String side, SvgEdgeMarkerEnd marker, String stroke) {
    if (marker == SvgEdgeMarkerEnd.NONE) {
      return null;
    }
    String id = ids.mint("marker-" + side + "-" + edgeId);
    String fill = fill(marker, stroke);
    w.start("marker")
        .attr("id", id)
        .attr("data-dediren-edge-marker-" + side, markerName(marker))
        .attr("markerWidth", styleNumber(VIEWPORT))
        .attr("markerHeight", styleNumber(VIEWPORT))
        .attr("refX", styleNumber(refX(side)))
        .attr("refY", styleNumber(REF_Y))
        .attr("orient", "auto");
    switch (marker) {
      case FILLED_DIAMOND, HOLLOW_DIAMOND ->
          w.empty("path")
              .attr("d", "M 1 5 L 5 1 L 9 5 L 5 9 Z")
              .attr("fill", fill)
              .attr("stroke", stroke)
              .attr("stroke-width", "1");
      case OPEN_ARROW ->
          w.empty("path")
              .attr("d", "M 1 1 L 9 5 L 1 9")
              .attr("fill", "none")
              .attr("stroke", stroke)
              .attr("stroke-width", "1.5");
      case FILLED_CIRCLE, HOLLOW_CIRCLE ->
          w.empty("circle")
              .attr("cx", "5")
              .attr("cy", "5")
              .attr("r", "3.5")
              .attr("fill", fill)
              .attr("stroke", stroke)
              .attr("stroke-width", "1");
      default ->
          w.empty("path")
              .attr("d", "M 1 1 L 9 5 L 1 9 Z")
              .attr("fill", fill)
              .attr("stroke", stroke)
              .attr("stroke-width", "1");
    }
    w.end();
    return id;
  }
}

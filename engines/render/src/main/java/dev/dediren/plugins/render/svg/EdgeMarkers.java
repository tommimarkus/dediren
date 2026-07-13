package dev.dediren.plugins.render.svg;

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

  /**
   * Anchoring rule, stated once. A marker's box is 10x10 and a centred marker (refX=5) puts half
   * the adornment on the far side of the endpoint — where the node (which paints over edges) or the
   * lifeline stem hides or straddles it. So the marker is anchored at its endpoint-facing extent
   * instead: end markers point forward (tip at x=9), start markers trail back (base at x=1).
   */
  private static String refX(String side) {
    return "start".equals(side) ? "1" : "9";
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

  /** Emits the marker for {@code side} ("start" or "end") of {@code edgeId}. NONE emits nothing. */
  public static void emit(
      SvgWriter w, String edgeId, String side, SvgEdgeMarkerEnd marker, String stroke) {
    if (marker == SvgEdgeMarkerEnd.NONE) {
      return;
    }
    String fill = fill(marker, stroke);
    w.start("marker")
        .attr("id", "marker-" + side + "-" + edgeId)
        .attr("data-dediren-edge-marker-" + side, markerName(marker))
        .attr("markerWidth", "10")
        .attr("markerHeight", "10")
        .attr("refX", refX(side))
        .attr("refY", "5")
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
  }
}

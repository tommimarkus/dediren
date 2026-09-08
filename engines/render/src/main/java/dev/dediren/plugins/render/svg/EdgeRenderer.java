package dev.dediren.plugins.render.svg;

import static dev.dediren.ir.RouteGeometry.flatten;
import static dev.dediren.plugins.render.svg.Geometry.labelBox;
import static dev.dediren.plugins.render.svg.Svg.dashArrayValue;
import static dev.dediren.plugins.render.svg.Svg.f1;
import static dev.dediren.plugins.render.svg.Svg.opacity;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;

import dev.dediren.contracts.layout.CubicBezierRoute;
import dev.dediren.contracts.layout.CubicBezierSegment;
import dev.dediren.contracts.layout.EdgeRoute;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.SvgEdgeLabelPresentation;
import dev.dediren.contracts.render.SvgEdgeLabelVerticalSide;
import dev.dediren.contracts.render.SvgEdgeMarkerEnd;
import dev.dediren.ir.RouteGeometry;
import dev.dediren.plugins.render.style.ResolvedEdgeStyle;
import dev.dediren.plugins.render.style.ResolvedStyle;
import dev.dediren.plugins.render.style.StyleResolver;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EdgeRenderer {

  private EdgeRenderer() {}

  private static final double EDGE_LABEL_BACKGROUND_PADDING_X = 5.0;
  private static final double EDGE_LABEL_BACKGROUND_PADDING_Y = 3.0;
  private static final double EDGE_LABEL_BACKGROUND_RX = 3.0;
  private static final double EDGE_LABEL_FONT_SIZE_SCALE = 1.1;
  private static final int EDGE_LABEL_FONT_WEIGHT = 600;
  private static final double EDGE_LABEL_OUTLINE_WIDTH = 2.0;

  /** Emits the edge's marker for {@code side} and returns its minted id ({@code null} for NONE). */
  public static String edgeMarker(
      SvgWriter w, SvgIds ids, LaidOutEdge edge, ResolvedEdgeStyle style, String side) {
    SvgEdgeMarkerEnd marker = side.equals("start") ? style.markerStart() : style.markerEnd();
    return EdgeMarkers.emit(w, ids, edge.id(), side, marker, style.stroke());
  }

  /**
   * The boxes this edge's end markers ink, in placement order.
   *
   * <p>Empty when the route has no points: {@link #edgePath} then draws no path, and a marker with
   * no path to sit on paints nothing however its {@code <marker>} element is defined. Ends set to
   * NONE contribute nothing for the same reason.
   *
   * <p>The angles handed to {@link EdgeMarkers#inkBox} are the ones {@code orient="auto"} resolves
   * to — the direction the route leaves its first vertex and arrives at its last. Corner rounding
   * and line jumps shorten those segments but never turn them, so the vertex-to-vertex direction is
   * the drawn one.
   */
  public static List<LabelBox> markerInkBoxes(LaidOutEdge edge, ResolvedEdgeStyle style) {
    if (!hasRenderableGeometry(edge)) {
      return List.of();
    }
    Point first = RouteGeometry.start(edge.route());
    Point last = RouteGeometry.end(edge.route());
    if (first == null || last == null) {
      return List.of();
    }
    List<LabelBox> boxes = new ArrayList<>();
    Point startTangent = RouteGeometry.startTangent(edge.route());
    Point endTangent = RouteGeometry.endTangent(edge.route());
    LabelBox start =
        EdgeMarkers.inkBox(
            "start",
            style.markerStart(),
            style.strokeWidth(),
            first.x(),
            first.y(),
            Math.atan2(startTangent.y(), startTangent.x()));
    if (start != null) {
      boxes.add(start);
    }
    LabelBox end =
        EdgeMarkers.inkBox(
            "end",
            style.markerEnd(),
            style.strokeWidth(),
            last.x(),
            last.y(),
            Math.atan2(endTangent.y(), endTangent.x()));
    if (end != null) {
      boxes.add(end);
    }
    return boxes;
  }

  /** Writes the backdrop strokes that clear each jump. The fills arrive already resolved. */
  public static void lineJumpMasks(SvgWriter w, String edgeId, List<MaskedLineJump> lineJumps) {
    if (lineJumps.isEmpty()) {
      return;
    }
    w.start("g").attr("data-dediren-line-jump-masks", edgeId);
    for (MaskedLineJump masked : lineJumps) {
      w.empty("path")
          .attr("d", masked.jump().maskPath())
          .attr("fill", "none")
          .attr("stroke", masked.maskFill())
          .attr("stroke-width", "6");
    }
    w.end();
  }

  public static String backdropFillAt(
      double x,
      double y,
      LayoutResult result,
      RenderMetadata metadata,
      RenderPolicy policy,
      ResolvedStyle base) {
    for (int index = result.groups().size() - 1; index >= 0; index--) {
      LaidOutGroup group = result.groups().get(index);
      if (pointInsideRect(x, y, group.x(), group.y(), group.width(), group.height())) {
        return StyleResolver.groupStyle(policy, metadata, group.id(), base).fill();
      }
    }
    return base.backgroundFill();
  }

  public static boolean pointInsideRect(
      double x, double y, double rectX, double rectY, double width, double height) {
    return x >= rectX && x <= rectX + width && y >= rectY && y <= rectY + height;
  }

  /**
   * Draws the route. The marker references are passed in rather than rebuilt from the edge id: they
   * are {@link SvgIds#reference} of exactly the ids {@link #edgeMarker} minted, so an attribute can
   * never point at an id that was sanitized or suffixed away. Each is {@code null} precisely when
   * that end's marker is NONE and nothing was emitted for it.
   */
  public static void edgePath(
      SvgWriter w,
      LaidOutEdge edge,
      ResolvedEdgeStyle style,
      List<LineJump> lineJumps,
      String markerStartReference,
      String markerEndReference) {
    if (!hasRenderableGeometry(edge)) {
      return;
    }
    String data = pathData(edge, lineJumps);
    String dash = dashArrayValue(style.lineStyle(), style.dashPattern(), "8 5");
    w.empty("path")
        .attr("d", data)
        .attr("fill", "none")
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()))
        .attr("stroke-linecap", "round")
        .attr("stroke-linejoin", "round")
        .attrIf("stroke-opacity", opacity(style.strokeOpacity()))
        .attrIf("stroke-dasharray", dash.isEmpty() ? null : dash)
        .attrIf("marker-start", markerStartReference)
        .attrIf("marker-end", markerEndReference);
  }

  private static void emitEdgeLabelBackground(SvgWriter w, EdgeLabel label, String backgroundFill) {
    LabelBox bounds = edgeLabelBackgroundBox(label);
    w.empty("rect")
        .attr("data-dediren-edge-label-background", "true")
        .attr("x", f1(bounds.minX()))
        .attr("y", f1(bounds.minY()))
        .attr("width", f1(bounds.width()))
        .attr("height", f1(bounds.height()))
        .attr("rx", styleNumber(EDGE_LABEL_BACKGROUND_RX))
        .attr("fill", backgroundFill);
  }

  public static void edgeLabel(
      SvgWriter w,
      EdgeLabel label,
      String text,
      ResolvedEdgeStyle style,
      String backgroundFill,
      double fontSize) {
    if (style.labelPresentation() == SvgEdgeLabelPresentation.BACKGROUND) {
      emitEdgeLabelBackground(w, label, backgroundFill);
      w.start("text")
          .attr("x", f1(label.x()))
          .attr("y", f1(label.y()))
          .attr("text-anchor", label.anchor())
          .attr("fill", style.labelFill())
          .attr("font-size", styleNumber(fontSize))
          .attr("font-weight", Integer.toString(EDGE_LABEL_FONT_WEIGHT))
          .attrIf("fill-opacity", opacity(style.labelOpacity()))
          .text(text)
          .end();
      return;
    }
    w.start("text")
        .attr("x", f1(label.x()))
        .attr("y", f1(label.y()))
        .attr("text-anchor", label.anchor())
        .attr("fill", "none")
        .attr("font-size", styleNumber(fontSize))
        .attr("font-weight", Integer.toString(EDGE_LABEL_FONT_WEIGHT))
        .attr("stroke", backgroundFill)
        .attr("stroke-width", styleNumber(EDGE_LABEL_OUTLINE_WIDTH))
        .text(text)
        .end();
    w.start("text")
        .attr("x", f1(label.x()))
        .attr("y", f1(label.y()))
        .attr("text-anchor", label.anchor())
        .attr("fill", style.labelFill())
        .attr("font-size", styleNumber(fontSize))
        .attr("font-weight", Integer.toString(EDGE_LABEL_FONT_WEIGHT))
        .attrIf("fill-opacity", opacity(style.labelOpacity()))
        .text(text)
        .end();
  }

  public static LabelBox edgeLabelBackgroundBox(EdgeLabel label) {
    return label
        .bounds()
        .expanded(EDGE_LABEL_BACKGROUND_PADDING_X, EDGE_LABEL_BACKGROUND_PADDING_Y);
  }

  public static LabelBox edgeLabelVisibleBox(
      EdgeLabel label, SvgEdgeLabelPresentation presentation) {
    if (presentation == SvgEdgeLabelPresentation.BACKGROUND) {
      return edgeLabelBackgroundBox(label);
    }
    return label.bounds().expanded(EDGE_LABEL_OUTLINE_WIDTH, EDGE_LABEL_OUTLINE_WIDTH);
  }

  public static double edgeLabelFontSize(double baseFontSize) {
    return Math.round(baseFontSize * EDGE_LABEL_FONT_SIZE_SCALE * 10.0) / 10.0;
  }

  public static String pathData(LaidOutEdge edge, List<LineJump> lineJumps) {
    if (edge.route() instanceof CubicBezierRoute cubic) {
      return cubicPathData(cubic);
    }
    if (lineJumps.isEmpty()) {
      return roundedPathData(RouteGeometry.flatten(edge.route()));
    }
    return roundedPathDataWithLineJumps(RouteGeometry.flatten(edge.route()), lineJumps);
  }

  public static String cubicPathData(CubicBezierRoute route) {
    if (!hasRenderableGeometry(route)) {
      return "";
    }
    StringBuilder data = new StringBuilder();
    data.append(String.format(Locale.ROOT, "M %.1f %.1f", route.start().x(), route.start().y()));
    for (CubicBezierSegment segment : route.segments()) {
      data.append(
          String.format(
              Locale.ROOT,
              " C %.1f %.1f %.1f %.1f %.1f %.1f",
              segment.control1().x(),
              segment.control1().y(),
              segment.control2().x(),
              segment.control2().y(),
              segment.end().x(),
              segment.end().y()));
    }
    return data.toString();
  }

  private static boolean hasRenderableGeometry(LaidOutEdge edge) {
    return hasRenderableGeometry(edge.route());
  }

  private static boolean hasRenderableGeometry(dev.dediren.contracts.layout.EdgeRoute route) {
    try {
      return RouteGeometry.flatten(route).size() >= 2;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  public static String roundedPathDataWithLineJumps(List<Point> points, List<LineJump> lineJumps) {
    if (points.isEmpty()) {
      return "";
    }
    if (points.size() == 1) {
      Point only = points.getFirst();
      return String.format(Locale.ROOT, "M %.1f %.1f", only.x(), only.y());
    }
    StringBuilder data = new StringBuilder();
    Point first = points.getFirst();
    data.append(String.format(Locale.ROOT, "M %.1f %.1f", first.x(), first.y()));
    Point penStart = first;
    for (int index = 0; index < points.size() - 1; index++) {
      int segmentIndex = index;
      Point start = points.get(index);
      Point end = points.get(index + 1);
      RoundedCorner rounded =
          index + 2 < points.size() ? roundedCorner(start, end, points.get(index + 2)) : null;
      Point segmentEnd = rounded == null ? end : rounded.before();
      double segmentStartProgress = segmentProgress(start, end, penStart.x(), penStart.y());
      double segmentEndProgress = segmentProgress(start, end, segmentEnd.x(), segmentEnd.y());
      List<LineJump> segmentJumps =
          lineJumps.stream()
              .filter(jump -> jump.segmentIndex() == segmentIndex)
              .filter(
                  jump -> {
                    double progress = segmentProgress(start, end, jump.x(), jump.y());
                    // Both bounds, symmetrically: the pen enters at the previous corner's
                    // rounded.after() and leaves at this corner's rounded.before(); a jump
                    // outside that window would make the path double back.
                    return progress >= segmentStartProgress - 0.001
                        && progress <= segmentEndProgress + 0.001;
                  })
              .sorted(
                  (left, right) ->
                      Double.compare(
                          segmentProgress(start, end, left.x(), left.y()),
                          segmentProgress(start, end, right.x(), right.y())))
              .toList();
      for (LineJump jump : segmentJumps) {
        data.append(" ").append(jump.pathPrefix(start, end));
      }
      data.append(String.format(Locale.ROOT, " L %.1f %.1f", segmentEnd.x(), segmentEnd.y()));
      if (rounded != null) {
        data.append(
            String.format(
                Locale.ROOT,
                " Q %.1f %.1f %.1f %.1f",
                end.x(),
                end.y(),
                rounded.after().x(),
                rounded.after().y()));
      }
      penStart = rounded == null ? end : rounded.after();
    }
    return data.toString();
  }

  public static String roundedPathData(List<Point> points) {
    if (points.isEmpty()) {
      return "";
    }
    if (points.size() == 1) {
      Point only = points.getFirst();
      return String.format(Locale.ROOT, "M %.1f %.1f", only.x(), only.y());
    }
    StringBuilder data = new StringBuilder();
    Point first = points.getFirst();
    data.append(String.format(Locale.ROOT, "M %.1f %.1f", first.x(), first.y()));
    for (int index = 1; index < points.size() - 1; index++) {
      Point previous = points.get(index - 1);
      Point corner = points.get(index);
      Point next = points.get(index + 1);
      RoundedCorner rounded = roundedCorner(previous, corner, next);
      if (rounded == null) {
        data.append(String.format(Locale.ROOT, " L %.1f %.1f", corner.x(), corner.y()));
      } else {
        data.append(
            String.format(
                Locale.ROOT,
                " L %.1f %.1f Q %.1f %.1f %.1f %.1f",
                rounded.before().x(),
                rounded.before().y(),
                corner.x(),
                corner.y(),
                rounded.after().x(),
                rounded.after().y()));
      }
    }
    Point last = points.getLast();
    data.append(String.format(Locale.ROOT, " L %.1f %.1f", last.x(), last.y()));
    return data.toString();
  }

  public static RoundedCorner roundedCorner(Point previous, Point corner, Point next) {
    boolean firstHorizontal = nearlyEqual(previous.y(), corner.y());
    boolean firstVertical = nearlyEqual(previous.x(), corner.x());
    boolean secondHorizontal = nearlyEqual(corner.y(), next.y());
    boolean secondVertical = nearlyEqual(corner.x(), next.x());
    if (!((firstHorizontal && secondVertical) || (firstVertical && secondHorizontal))) {
      return null;
    }
    double firstLength = distance(previous, corner);
    double secondLength = distance(corner, next);
    double radius = Math.min(8.0, Math.min(firstLength / 2.0, secondLength / 2.0));
    if (radius < 2.0) {
      return null;
    }
    return new RoundedCorner(
        shiftedToward(corner, previous, radius), shiftedToward(corner, next, radius));
  }

  public static Point shiftedToward(Point from, Point toward, double distance) {
    double length = distance(from, toward);
    if (length == 0.0) {
      return from;
    }
    double ratio = distance / length;
    return new Point(
        from.x() + (toward.x() - from.x()) * ratio, from.y() + (toward.y() - from.y()) * ratio);
  }

  public static double distance(Point left, Point right) {
    return Math.hypot(left.x() - right.x(), left.y() - right.y());
  }

  public static double segmentProgress(Point start, Point end, double x, double y) {
    double dx = Math.abs(end.x() - start.x());
    double dy = Math.abs(end.y() - start.y());
    if (dx >= dy) {
      double length = end.x() - start.x();
      return length == 0.0 ? 0.0 : (x - start.x()) / length;
    }
    double length = end.y() - start.y();
    return length == 0.0 ? 0.0 : (y - start.y()) / length;
  }

  public static List<LineJump> lineJumps(LaidOutEdge edge, List<LaidOutEdge> renderedEdges) {
    return lineJumps(edge, renderedEdges, List.of());
  }

  /** Accepts complete arcs only after accounting for the endpoint decoration paint. */
  public static List<LineJump> lineJumps(
      LaidOutEdge edge, List<LaidOutEdge> renderedEdges, List<LabelBox> endpointDecorations) {
    // Cubics emit their native C commands unchanged; inserting a polyline-style Q jump would
    // either be ignored by pathData or require altering the curve. Curves may still be crossed by
    // later orthogonal owners, but they never own a jump or its mask.
    if (edge.route() instanceof CubicBezierRoute) {
      return List.of();
    }
    List<LineJump> jumps = new ArrayList<>();
    List<Point> currentPoints = flatten(edge.route());
    List<List<Point>> previousRoutes =
        renderedEdges.stream().map(previous -> flatten(previous.route())).toList();
    for (int segmentIndex = 0; segmentIndex < currentPoints.size() - 1; segmentIndex++) {
      Point currentStart = currentPoints.get(segmentIndex);
      Point currentEnd = currentPoints.get(segmentIndex + 1);
      boolean currentVertical = nearlyEqual(currentStart.x(), currentEnd.x());
      boolean currentHorizontal = nearlyEqual(currentStart.y(), currentEnd.y());
      if (!currentVertical && !currentHorizontal) {
        continue;
      }
      for (int edgeIndex = 0; edgeIndex < renderedEdges.size(); edgeIndex++) {
        List<Point> previousPoints = previousRoutes.get(edgeIndex);
        for (int previousIndex = 0; previousIndex < previousPoints.size() - 1; previousIndex++) {
          Point previousStart = previousPoints.get(previousIndex);
          Point previousEnd = previousPoints.get(previousIndex + 1);
          boolean previousVertical = nearlyEqual(previousStart.x(), previousEnd.x());
          boolean previousHorizontal = nearlyEqual(previousStart.y(), previousEnd.y());
          if (currentVertical && previousHorizontal) {
            double x = currentStart.x();
            double y = previousStart.y();
            if (insideSegment(y, currentStart.y(), currentEnd.y())
                && insideSegment(x, previousStart.x(), previousEnd.x())) {
              jumps.add(new LineJump(segmentIndex, x, y, true));
            }
          } else if (currentHorizontal && previousVertical) {
            double x = previousStart.x();
            double y = currentStart.y();
            if (insideSegment(x, currentStart.x(), currentEnd.x())
                && insideSegment(y, previousStart.y(), previousEnd.y())) {
              jumps.add(new LineJump(segmentIndex, x, y, false));
            }
          }
        }
      }
    }
    List<LineJump> clearCandidates =
        dedupeJumps(jumps).stream()
            .filter(jump -> endpointDecorations.stream().noneMatch(jump.maskInkBox()::overlaps))
            .toList();
    return acceptedLineJumps(currentPoints, clearCandidates);
  }

  /**
   * Keeps only jumps whose whole six-unit reach fits in the straight portion that survives corner
   * rounding. The ordered accepted list is also the list used by the path, mask, and bounds lanes.
   */
  private static List<LineJump> acceptedLineJumps(List<Point> points, List<LineJump> candidates) {
    List<LineJump> accepted = new ArrayList<>();
    for (LineJump candidate : candidates) {
      if (!jumpFitsStraightRun(points, candidate)) {
        continue;
      }
      boolean overlapsAccepted =
          accepted.stream()
              .anyMatch(existing -> candidate.routeInkBox().overlaps(existing.routeInkBox()));
      if (!overlapsAccepted) {
        accepted.add(candidate);
      }
    }
    return accepted;
  }

  private static boolean jumpFitsStraightRun(List<Point> points, LineJump jump) {
    int index = jump.segmentIndex();
    Point start = points.get(index);
    Point end = points.get(index + 1);
    Point penStart = start;
    if (index > 0) {
      RoundedCorner prior = roundedCorner(points.get(index - 1), start, end);
      if (prior != null) {
        penStart = prior.after();
      }
    }
    Point penEnd = end;
    if (index + 2 < points.size()) {
      RoundedCorner next = roundedCorner(start, end, points.get(index + 2));
      if (next != null) {
        penEnd = next.before();
      }
    }
    double progress = segmentProgress(start, end, jump.x(), jump.y());
    double startProgress = segmentProgress(start, end, penStart.x(), penStart.y());
    double endProgress = segmentProgress(start, end, penEnd.x(), penEnd.y());
    double length = distance(start, end);
    double reachProgress = length == 0.0 ? Double.POSITIVE_INFINITY : 6.0 / length;
    return progress - startProgress >= reachProgress && endProgress - progress >= reachProgress;
  }

  public static List<LineJump> dedupeJumps(List<LineJump> jumps) {
    List<LineJump> deduped = new ArrayList<>();
    for (LineJump jump : jumps) {
      boolean exists =
          deduped.stream()
              .anyMatch(
                  existing ->
                      existing.segmentIndex() == jump.segmentIndex()
                          && Math.abs(existing.x() - jump.x()) < 0.1
                          && Math.abs(existing.y() - jump.y()) < 0.1
                          && existing.vertical() == jump.vertical());
      if (!exists) {
        deduped.add(jump);
      }
    }
    return deduped;
  }

  public static boolean nearlyEqual(double left, double right) {
    return Math.abs(left - right) < 0.001;
  }

  public static boolean insideSegment(double value, double start, double end) {
    double min = Math.min(start, end);
    double max = Math.max(start, end);
    return value > min && value < max;
  }

  public record LabelPlacement(EdgeLabel label, boolean constrained) {}

  public static EdgeLabel edgeLabel(
      LaidOutEdge edge, ResolvedEdgeStyle style, List<LabelBox> occupiedBoxes, double fontSize) {
    return placeLabel(edge, style, occupiedBoxes, List.of(), fontSize).label();
  }

  public static EdgeLabel edgeLabel(
      LaidOutEdge edge,
      ResolvedEdgeStyle style,
      List<LabelBox> occupiedBoxes,
      List<LaidOutEdge> competingRoutes,
      double fontSize) {
    return placeLabel(edge, style, occupiedBoxes, competingRoutes, fontSize).label();
  }

  /** One decision owns both the attached label and its unsatisfied-placement diagnostic. */
  public static LabelPlacement placeLabel(
      LaidOutEdge edge,
      ResolvedEdgeStyle style,
      List<LabelBox> occupiedBoxes,
      List<LaidOutEdge> competingRoutes,
      double fontSize) {
    List<Point> candidatePoints = candidateRuns(flatten(edge.route()));
    List<EdgeLabel> candidates = new ArrayList<>();
    if (edge.route() instanceof CubicBezierRoute && candidatePoints.size() > 2) {
      // A sampled curve vertex can carry an extremum whose tangent no individual chord retains.
      for (int offset : middleOutSegmentIndexes(candidatePoints.size() - 2)) {
        int index = offset + 1;
        Point before = candidatePoints.get(index - 1);
        Point after = candidatePoints.get(index + 1);
        double dx = after.x() - before.x();
        double dy = after.y() - before.y();
        double length = Math.hypot(dx, dy);
        if (length > 0.001) {
          for (double side :
              routeAttachmentSides(style, dx, dy, -dy / length, dx / length, false)) {
            candidates.add(
                routeAttachedCandidate(
                    candidatePoints.get(index),
                    -dy / length,
                    dx / length,
                    side,
                    edge.label(),
                    style,
                    fontSize));
          }
        }
      }
    }
    candidates.addAll(routeAttachedCandidates(candidatePoints, edge.label(), style, fontSize));
    RouteSample owner = sample(edge.route(), 0.0625);
    List<RouteSample> competitors =
        competingRoutes.stream().map(other -> sample(other.route(), 0.0625)).toList();
    EdgeLabel best = candidates.getFirst();
    double bestScore = Double.POSITIVE_INFINITY;
    for (EdgeLabel candidate : candidates) {
      LabelBox box = edgeLabelVisibleBox(candidate, style.labelPresentation());
      double ownerDistance = routeDistance(owner.points(), box);
      double competitorDistance = Double.POSITIVE_INFINITY;
      double uncertainty = owner.error();
      for (RouteSample other : competitors) {
        competitorDistance = Math.min(competitorDistance, routeDistance(other.points(), box));
        uncertainty = Math.max(uncertainty, owner.error() + other.error());
      }
      // Refine curves when their bounded approximation could change an attachment decision.
      if (uncertainty > 0.0
          && (Math.abs(ownerDistance - 2.0) <= uncertainty
              || Math.abs(ownerDistance - 6.0) <= uncertainty
              || Math.abs(competitorDistance - ownerDistance) <= uncertainty)) {
        ownerDistance = routeDistance(flatten(edge.route(), 0.00001), box);
        competitorDistance = Double.POSITIVE_INFINITY;
        for (LaidOutEdge other : competingRoutes) {
          competitorDistance =
              Math.min(competitorDistance, routeDistance(flatten(other.route(), 0.00001), box));
        }
        uncertainty = 0.00002;
      }
      double overlap = 0.0;
      for (LabelBox obstacle : occupiedBoxes) {
        overlap +=
            Math.max(
                    0.0,
                    Math.min(box.maxX(), obstacle.maxX()) - Math.max(box.minX(), obstacle.minX()))
                * Math.max(
                    0.0,
                    Math.min(box.maxY(), obstacle.maxY()) - Math.max(box.minY(), obstacle.minY()));
      }
      if (overlap == 0.0
          && ownerDistance >= 2.0 + uncertainty
          && ownerDistance <= 6.0 - uncertainty
          && ownerDistance + uncertainty < competitorDistance) {
        return new LabelPlacement(candidate, false);
      }
      double score =
          overlap
              + 1000.0
                  * (Math.max(0.0, 2.0 - ownerDistance)
                      + Math.max(0.0, ownerDistance - 6.0)
                      + Math.max(0.0, ownerDistance + uncertainty - competitorDistance));
      if (score < bestScore) {
        bestScore = score;
        best = candidate;
      }
    }
    return new LabelPlacement(best, true);
  }

  private record RouteSample(List<Point> points, double error) {}

  private static RouteSample sample(EdgeRoute route, double error) {
    return new RouteSample(flatten(route, error), route instanceof CubicBezierRoute ? error : 0.0);
  }

  /** Distance to actual line segments, not the empty corners of their bounding rectangles. */
  private static double routeDistance(List<Point> points, LabelBox box) {
    Rectangle2D rectangle =
        new Rectangle2D.Double(box.minX(), box.minY(), box.width(), box.height());
    double nearest = Double.POSITIVE_INFINITY;
    for (int index = 1; index < points.size(); index++) {
      Point a = points.get(index - 1);
      Point b = points.get(index);
      if (rectangle.intersectsLine(a.x(), a.y(), b.x(), b.y())) {
        return 0.0;
      }
      nearest = Math.min(nearest, pointBoxDistance(a, box));
      nearest = Math.min(nearest, pointBoxDistance(b, box));
      for (double x : new double[] {box.minX(), box.maxX()}) {
        for (double y : new double[] {box.minY(), box.maxY()}) {
          nearest = Math.min(nearest, Line2D.ptSegDist(a.x(), a.y(), b.x(), b.y(), x, y));
        }
      }
    }
    return nearest;
  }

  private static double pointBoxDistance(Point point, LabelBox box) {
    return Math.hypot(
        Math.max(0.0, Math.max(box.minX() - point.x(), point.x() - box.maxX())),
        Math.max(0.0, Math.max(box.minY() - point.y(), point.y() - box.maxY())));
  }

  private static List<EdgeLabel> routeAttachedCandidates(
      List<Point> points, String text, ResolvedEdgeStyle style, double fontSize) {
    if (points.size() < 2) {
      return List.of(edgeLabelCandidate(0.0, -6.0, "middle", text, fontSize));
    }
    List<EdgeLabel> fittingCandidates = new ArrayList<>();
    List<EdgeLabel> horizontalFittingCandidates = new ArrayList<>();
    List<EdgeLabel> fallbackCandidates = new ArrayList<>();
    for (int index : middleOutSegmentIndexes(points.size() - 1)) {
      Point start = points.get(index);
      Point end = points.get(index + 1);
      double dx = end.x() - start.x();
      double dy = end.y() - start.y();
      double length = Math.hypot(dx, dy);
      if (length < 0.001) {
        continue;
      }
      double normalX = -dy / length;
      double normalY = dx / length;
      for (double progress : routeAttachmentProgresses(style, length, Math.abs(dx) < 0.001)) {
        Point anchor = new Point(start.x() + dx * progress, start.y() + dy * progress);
        for (double side :
            routeAttachmentSides(
                style,
                dx,
                dy,
                normalX,
                normalY,
                index + 2 < points.size() && points.get(index + 2).y() < start.y())) {
          EdgeLabel candidate =
              routeAttachedCandidate(anchor, normalX, normalY, side, text, style, fontSize);
          LabelBox visible = edgeLabelVisibleBox(candidate, style.labelPresentation());
          double tangentHalfExtent =
              visible.width() / 2.0 * Math.abs(dx / length)
                  + visible.height() / 2.0 * Math.abs(dy / length);
          if (Math.min(progress, 1.0 - progress) * length >= tangentHalfExtent) {
            fittingCandidates.add(candidate);
            if (Math.abs(dy) < 0.001) {
              horizontalFittingCandidates.add(candidate);
            }
          } else {
            fallbackCandidates.add(candidate);
          }
        }
      }
    }
    // The render policy's horizontal label controls are the tie-breaker when an actual route has
    // both orientations. A long horizontal body therefore wins over a nearby vertical stub, while
    // vertical and diagonal routes remain fully usable when no horizontal body can carry the text.
    List<EdgeLabel> candidates = new ArrayList<>(horizontalFittingCandidates);
    for (EdgeLabel candidate : fittingCandidates) {
      if (!candidates.contains(candidate)) {
        candidates.add(candidate);
      }
    }
    candidates.addAll(fallbackCandidates);
    return candidates.isEmpty()
        ? List.of(
            edgeLabelCandidate(
                points.getFirst().x(), points.getFirst().y() - 6.0, "middle", text, fontSize))
        : candidates;
  }

  private static List<Point> candidateRuns(List<Point> points) {
    List<Point> runs = new ArrayList<>();
    for (Point point : points) {
      while (runs.size() >= 2) {
        Point a = runs.get(runs.size() - 2);
        Point b = runs.getLast();
        double cross =
            (b.x() - a.x()) * (point.y() - b.y()) - (b.y() - a.y()) * (point.x() - b.x());
        double dot = (b.x() - a.x()) * (point.x() - b.x()) + (b.y() - a.y()) * (point.y() - b.y());
        if (Math.abs(cross) > 0.000000001 || dot < 0.0) {
          break;
        }
        runs.removeLast();
      }
      runs.add(point);
    }
    return runs;
  }

  private static List<Double> routeAttachmentProgresses(
      ResolvedEdgeStyle style, double length, boolean vertical) {
    double near = Math.min(0.5, 18.0 / length);
    String position =
        vertical ? style.labelVerticalPosition().name() : style.labelHorizontalPosition().name();
    var positions =
        new java.util.LinkedHashSet<Double>(
            switch (position) {
              case "NEAR_START" -> List.of(near, 0.5, 1.0 - near);
              case "NEAR_END" -> List.of(1.0 - near, 0.5, near);
              default -> List.of(0.5, near, 1.0 - near);
            });
    positions.add(0.25);
    positions.add(0.75);
    for (double along = 24.0; along < length; along += 24.0) {
      positions.add(along / length);
    }
    return List.copyOf(positions);
  }

  private static List<Double> routeAttachmentSides(
      ResolvedEdgeStyle style,
      double dx,
      double dy,
      double normalX,
      double normalY,
      boolean autoAbove) {
    if (Math.abs(dy) < 0.001) {
      boolean above =
          switch (style.labelHorizontalSide()) {
            case ABOVE -> true;
            case BELOW -> false;
            case AUTO -> autoAbove;
          };
      double aboveSide = normalY < 0.0 ? 1.0 : -1.0;
      return above ? List.of(aboveSide, -aboveSide) : List.of(-aboveSide, aboveSide);
    }
    if (Math.abs(dx) < 0.001) {
      boolean left = style.labelVerticalSide() != SvgEdgeLabelVerticalSide.RIGHT;
      double leftSide = normalX < 0.0 ? 1.0 : -1.0;
      return left ? List.of(leftSide, -leftSide) : List.of(-leftSide, leftSide);
    }
    return List.of(1.0, -1.0);
  }

  private static EdgeLabel routeAttachedCandidate(
      Point anchor,
      double normalX,
      double normalY,
      double side,
      String text,
      ResolvedEdgeStyle style,
      double fontSize) {
    String textAnchor =
        Math.abs(normalY) < 0.001 ? (side * normalX > 0.0 ? "start" : "end") : "middle";
    EdgeLabel initial = edgeLabelCandidate(anchor.x(), anchor.y(), textAnchor, text, fontSize);
    LabelBox visible = edgeLabelVisibleBox(initial, style.labelPresentation());
    double centerX = (visible.minX() + visible.maxX()) / 2.0;
    double centerY = (visible.minY() + visible.maxY()) / 2.0;
    double halfProjection =
        visible.width() / 2.0 * Math.abs(normalX) + visible.height() / 2.0 * Math.abs(normalY);
    return edgeLabelCandidate(
        initial.x() + anchor.x() - centerX + normalX * side * (halfProjection + 4.0),
        initial.y() + anchor.y() - centerY + normalY * side * (halfProjection + 4.0),
        textAnchor,
        text,
        fontSize);
  }

  private static List<Integer> middleOutSegmentIndexes(int size) {
    List<Integer> indexes = new ArrayList<>(size);
    int middle = (size - 1) / 2;
    indexes.add(middle);
    for (int distance = 1; indexes.size() < size; distance++) {
      if (middle + distance < size) {
        indexes.add(middle + distance);
      }
      if (middle - distance >= 0) {
        indexes.add(middle - distance);
      }
    }
    return indexes;
  }

  public static EdgeLabel edgeLabelCandidate(
      double x, double y, String anchor, String text, double fontSize) {
    return new EdgeLabel(x, y, anchor, labelBox(x, y, anchor, text, fontSize));
  }
}

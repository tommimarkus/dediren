package dev.dediren.plugins.render.svg;

import static dev.dediren.plugins.render.svg.Geometry.labelBox;
import static dev.dediren.plugins.render.svg.Svg.attr;
import static dev.dediren.plugins.render.svg.Svg.dashArrayAttr;
import static dev.dediren.plugins.render.svg.Svg.opacityAttr;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;
import static dev.dediren.plugins.render.svg.Svg.text;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.SvgEdgeLabelPresentation;
import dev.dediren.contracts.render.SvgEdgeLabelVerticalSide;
import dev.dediren.contracts.render.SvgEdgeMarkerEnd;
import dev.dediren.plugins.render.style.ResolvedEdgeStyle;
import dev.dediren.plugins.render.style.ResolvedStyle;
import dev.dediren.plugins.render.style.StyleResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class EdgeRenderer {

  private EdgeRenderer() {}

  private static final double EDGE_LABEL_BACKGROUND_PADDING_X = 5.0;
  private static final double EDGE_LABEL_BACKGROUND_PADDING_Y = 3.0;
  private static final double EDGE_LABEL_BACKGROUND_RX = 3.0;
  private static final double EDGE_LABEL_FONT_SIZE_SCALE = 1.1;
  private static final int EDGE_LABEL_FONT_WEIGHT = 600;
  private static final double EDGE_LABEL_OUTLINE_WIDTH = 2.0;

  // Perpendicular reach (px from the segment) within which a horizontal-side label still counts as
  // "hugging" its own route. Offsets up to this are tried before falling back to an on-route
  // vertical-segment placement; larger offsets — which can walk the label clear off the diagram —
  // only run when no on-route placement is clear.
  private static final double MAX_HUG_OFFSET = 56.0;

  public static String edgeMarker(LaidOutEdge edge, ResolvedEdgeStyle style, String side) {
    SvgEdgeMarkerEnd marker = side.equals("start") ? style.markerStart() : style.markerEnd();
    if (marker == SvgEdgeMarkerEnd.NONE) {
      return "";
    }
    String markerName = markerName(marker);
    String id = "marker-" + side + "-" + edge.id();
    String attribute = "data-dediren-edge-marker-" + side;
    String fill = markerFill(marker, style);
    String stroke = markerStroke(marker, style);
    String body =
        switch (marker) {
          case FILLED_DIAMOND, HOLLOW_DIAMOND ->
              "<path d=\"M 1 5 L 5 1 L 9 5 L 5 9 Z\" fill=\""
                  + fill
                  + "\" stroke=\""
                  + stroke
                  + "\" stroke-width=\"1\"/>";
          case HOLLOW_TRIANGLE ->
              "<path d=\"M 1 1 L 9 5 L 1 9 Z\" fill=\""
                  + fill
                  + "\" stroke=\""
                  + stroke
                  + "\" stroke-width=\"1\"/>";
          case OPEN_ARROW ->
              "<path d=\"M 1 1 L 9 5 L 1 9\" fill=\"none\" stroke=\""
                  + stroke
                  + "\" stroke-width=\"1.5\"/>";
          case FILLED_CIRCLE, HOLLOW_CIRCLE ->
              "<circle cx=\"5\" cy=\"5\" r=\"3.5\" fill=\""
                  + fill
                  + "\" stroke=\""
                  + stroke
                  + "\" stroke-width=\"1\"/>";
          default ->
              "<path d=\"M 1 1 L 9 5 L 1 9 Z\" fill=\""
                  + fill
                  + "\" stroke=\""
                  + stroke
                  + "\" stroke-width=\"1\"/>";
        };
    // Anchor the marker at its endpoint-facing extent so the whole adornment stays on the
    // stroke side of the endpoint. Endpoints sit on node borders, and nodes paint over edges,
    // so a centred marker (refX=5) has its far half hidden by the node. End markers point
    // forward (tip at x=9); start markers trail back (base at x=1).
    String refX = "start".equals(side) ? "1" : "9";
    return "<marker id=\""
        + attr(id)
        + "\" "
        + attribute
        + "=\""
        + markerName
        + "\" markerWidth=\"10\" markerHeight=\"10\" refX=\""
        + refX
        + "\" refY=\"5\" orient=\"auto\">"
        + body
        + "</marker>";
  }

  public static String lineJumpMasks(
      LaidOutEdge edge,
      List<LineJump> lineJumps,
      LayoutResult result,
      RenderMetadata metadata,
      RenderPolicy policy,
      ResolvedStyle base) {
    if (lineJumps.isEmpty()) {
      return "";
    }
    StringBuilder masks = new StringBuilder();
    masks.append("<g data-dediren-line-jump-masks=\"").append(attr(edge.id())).append("\">");
    for (LineJump jump : lineJumps) {
      String maskFill = backdropFillAt(jump.x(), jump.y(), result, metadata, policy, base);
      masks
          .append("<path d=\"")
          .append(attr(jump.maskPath()))
          .append("\" fill=\"none\" stroke=\"")
          .append(attr(maskFill))
          .append("\" stroke-width=\"6\"/>");
    }
    masks.append("</g>");
    return masks.toString();
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

  public static String edgePath(
      LaidOutEdge edge, ResolvedEdgeStyle style, List<LineJump> lineJumps) {
    if (edge.points().isEmpty()) {
      return "";
    }
    String data = pathData(edge, lineJumps);
    String dash = dashArrayAttr(style.lineStyle(), style.dashPattern(), "8 5");
    String markerStart =
        style.markerStart() == SvgEdgeMarkerEnd.NONE
            ? ""
            : " marker-start=\"url(#marker-start-" + attr(edge.id()) + ")\"";
    String markerEnd =
        style.markerEnd() == SvgEdgeMarkerEnd.NONE
            ? ""
            : " marker-end=\"url(#marker-end-" + attr(edge.id()) + ")\"";
    return "<path d=\""
        + data
        + "\" fill=\"none\" stroke=\""
        + attr(style.stroke())
        + "\" stroke-width=\""
        + styleNumber(style.strokeWidth())
        + "\""
        + " stroke-linecap=\"round\" stroke-linejoin=\"round\""
        + opacityAttr("stroke-opacity", style.strokeOpacity())
        + dash
        + markerStart
        + markerEnd
        + "/>";
  }

  public static String edgeLabelBackground(EdgeLabel label, String backgroundFill) {
    LabelBox bounds = edgeLabelBackgroundBox(label);
    return String.format(
        Locale.ROOT,
        "<rect data-dediren-edge-label-background=\"true\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\"/>",
        bounds.minX(),
        bounds.minY(),
        bounds.width(),
        bounds.height(),
        styleNumber(EDGE_LABEL_BACKGROUND_RX),
        attr(backgroundFill));
  }

  public static String edgeLabel(
      EdgeLabel label,
      String text,
      ResolvedEdgeStyle style,
      String backgroundFill,
      double fontSize) {
    StringBuilder output = new StringBuilder();
    if (style.labelPresentation() == SvgEdgeLabelPresentation.BACKGROUND) {
      output.append(edgeLabelBackground(label, backgroundFill));
      output.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\" fill=\"%s\" font-size=\"%s\" font-weight=\"%d\"%s>%s</text>",
              label.x(),
              label.y(),
              attr(label.anchor()),
              attr(style.labelFill()),
              styleNumber(fontSize),
              EDGE_LABEL_FONT_WEIGHT,
              opacityAttr("fill-opacity", style.labelOpacity()),
              text(text)));
      return output.toString();
    }
    output.append(
        String.format(
            Locale.ROOT,
            "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\" fill=\"none\" font-size=\"%s\" font-weight=\"%d\" stroke=\"%s\" stroke-width=\"%s\">%s</text>",
            label.x(),
            label.y(),
            attr(label.anchor()),
            styleNumber(fontSize),
            EDGE_LABEL_FONT_WEIGHT,
            attr(backgroundFill),
            styleNumber(EDGE_LABEL_OUTLINE_WIDTH),
            text(text)));
    output.append(
        String.format(
            Locale.ROOT,
            "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\" fill=\"%s\" font-size=\"%s\" font-weight=\"%d\"%s>%s</text>",
            label.x(),
            label.y(),
            attr(label.anchor()),
            attr(style.labelFill()),
            styleNumber(fontSize),
            EDGE_LABEL_FONT_WEIGHT,
            opacityAttr("fill-opacity", style.labelOpacity()),
            text(text)));
    return output.toString();
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

  public static String markerName(SvgEdgeMarkerEnd marker) {
    return marker.name().toLowerCase(Locale.ROOT);
  }

  public static String markerFill(SvgEdgeMarkerEnd marker, ResolvedEdgeStyle style) {
    return switch (marker) {
      case HOLLOW_TRIANGLE, HOLLOW_DIAMOND, HOLLOW_CIRCLE -> "#ffffff";
      case OPEN_ARROW -> "none";
      default -> attr(style.stroke());
    };
  }

  public static String markerStroke(SvgEdgeMarkerEnd marker, ResolvedEdgeStyle style) {
    return switch (marker) {
      case FILLED_ARROW, FILLED_DIAMOND, FILLED_CIRCLE -> attr(style.stroke());
      default -> attr(style.stroke());
    };
  }

  public static String pathData(LaidOutEdge edge, List<LineJump> lineJumps) {
    if (lineJumps.isEmpty()) {
      return roundedPathData(edge.points());
    }
    return roundedPathDataWithLineJumps(edge.points(), lineJumps);
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
    for (int index = 0; index < points.size() - 1; index++) {
      int segmentIndex = index;
      Point start = points.get(index);
      Point end = points.get(index + 1);
      RoundedCorner rounded =
          index + 2 < points.size() ? roundedCorner(start, end, points.get(index + 2)) : null;
      Point segmentEnd = rounded == null ? end : rounded.before();
      double segmentEndProgress = segmentProgress(start, end, segmentEnd.x(), segmentEnd.y());
      List<LineJump> segmentJumps =
          lineJumps.stream()
              .filter(jump -> jump.segmentIndex() == segmentIndex)
              .filter(
                  jump ->
                      segmentProgress(start, end, jump.x(), jump.y()) <= segmentEndProgress + 0.001)
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
    List<LineJump> jumps = new ArrayList<>();
    for (int segmentIndex = 0; segmentIndex < edge.points().size() - 1; segmentIndex++) {
      Point currentStart = edge.points().get(segmentIndex);
      Point currentEnd = edge.points().get(segmentIndex + 1);
      boolean currentVertical = nearlyEqual(currentStart.x(), currentEnd.x());
      boolean currentHorizontal = nearlyEqual(currentStart.y(), currentEnd.y());
      if (!currentVertical && !currentHorizontal) {
        continue;
      }
      for (LaidOutEdge previousEdge : renderedEdges) {
        if (isSharedJunctionPair(edge, previousEdge)) {
          continue;
        }
        for (int previousIndex = 0;
            previousIndex < previousEdge.points().size() - 1;
            previousIndex++) {
          Point previousStart = previousEdge.points().get(previousIndex);
          Point previousEnd = previousEdge.points().get(previousIndex + 1);
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
    return dedupeJumps(jumps);
  }

  public static boolean isSharedJunctionPair(LaidOutEdge edge, LaidOutEdge previousEdge) {
    return (edge.routingHints().contains("shared_source_junction")
            && edge.source().equals(previousEdge.source()))
        || (edge.routingHints().contains("shared_target_junction")
            && edge.target().equals(previousEdge.target()));
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

  public static EdgeLabel edgeLabel(
      LaidOutEdge edge, ResolvedEdgeStyle style, List<LabelBox> occupiedBoxes, double fontSize) {
    Optional<Segment> horizontal = firstHorizontalSegment(edge);
    if (horizontal.isPresent()) {
      Segment segment = horizontal.get();
      double direction = Math.signum(segment.end().x() - segment.start().x());
      if (direction == 0.0) {
        direction = 1.0;
      }
      double preferredX =
          switch (style.labelHorizontalPosition()) {
            case CENTER -> (segment.start().x() + segment.end().x()) / 2.0;
            case NEAR_END -> segment.end().x() - direction * 18.0;
            case NEAR_START -> segment.start().x() + direction * 18.0;
          };
      double centerX = (segment.start().x() + segment.end().x()) / 2.0;
      double nearStartX = segment.start().x() + direction * 18.0;
      double nearEndX = segment.end().x() - direction * 18.0;
      double baseOffset =
          switch (style.labelHorizontalSide()) {
            case ABOVE -> -10.0;
            case BELOW -> 18.0;
            case AUTO -> autoHorizontalLabelOffset(edge, segment.index());
          };
      List<Double> xCandidates = orderedValues(preferredX, centerX, nearStartX, nearEndX);
      List<Double> hugOffsets = new ArrayList<>();
      List<Double> farOffsets = new ArrayList<>();
      for (double offset : labelOffsetCandidates(baseOffset)) {
        (Math.abs(offset) <= MAX_HUG_OFFSET ? hugOffsets : farOffsets).add(offset);
      }
      // 1. Hug the segment: try only the beside-the-route offsets first.
      Optional<EdgeLabel> besideRoute =
          firstClearHorizontalLabel(
              edge, style, occupiedBoxes, fontSize, segment, xCandidates, hugOffsets);
      if (besideRoute.isPresent()) {
        return besideRoute.get();
      }
      // 2. Prefer an on-route vertical-segment placement over displacing the label far from its own
      // segment. Escalating the horizontal offset outward can walk the label clear off the diagram
      // (the grouped fan-out regression), dissociating it from the relationship it names.
      Optional<EdgeLabel> vertical = firstClearVerticalLabel(edge, style, occupiedBoxes, fontSize);
      if (vertical.isPresent()) {
        return vertical.get();
      }
      // 3. Only with no clear on-route placement, escalate the horizontal offset outward.
      Optional<EdgeLabel> displaced =
          firstClearHorizontalLabel(
              edge, style, occupiedBoxes, fontSize, segment, xCandidates, farOffsets);
      if (displaced.isPresent()) {
        return displaced.get();
      }
      return edgeLabelCandidate(
          preferredX, segment.start().y() + baseOffset, "middle", edge.label(), fontSize);
    }
    List<EdgeLabel> candidates = verticalLabelCandidates(edge, style, fontSize);
    if (!candidates.isEmpty()) {
      for (EdgeLabel candidate : candidates) {
        LabelBox candidateBox = edgeLabelVisibleBox(candidate, style.labelPresentation());
        if (occupiedBoxes.stream().noneMatch(candidateBox::overlaps)) {
          return candidate;
        }
      }
      return candidates.get(0);
    }
    Point point =
        edge.points().isEmpty() ? new Point(0.0, 0.0) : edge.points().get(edge.points().size() / 2);
    return edgeLabelCandidate(point.x(), point.y() - 6.0, "middle", edge.label(), fontSize);
  }

  private static Optional<EdgeLabel> firstClearHorizontalLabel(
      LaidOutEdge edge,
      ResolvedEdgeStyle style,
      List<LabelBox> occupiedBoxes,
      double fontSize,
      Segment segment,
      List<Double> xCandidates,
      List<Double> offsets) {
    for (double offset : offsets) {
      for (double x : xCandidates) {
        EdgeLabel candidate =
            edgeLabelCandidate(x, segment.start().y() + offset, "middle", edge.label(), fontSize);
        LabelBox candidateBox = edgeLabelVisibleBox(candidate, style.labelPresentation());
        if (occupiedBoxes.stream().noneMatch(candidateBox::overlaps)) {
          return Optional.of(candidate);
        }
      }
    }
    return Optional.empty();
  }

  public static EdgeLabel edgeLabelCandidate(
      double x, double y, String anchor, String text, double fontSize) {
    return new EdgeLabel(x, y, anchor, labelBox(x, y, anchor, text, fontSize));
  }

  public static List<Double> orderedValues(double... values) {
    List<Double> ordered = new ArrayList<>();
    for (double value : values) {
      boolean exists = ordered.stream().anyMatch(existing -> Math.abs(existing - value) < 0.1);
      if (!exists) {
        ordered.add(value);
      }
    }
    return ordered;
  }

  public static List<Double> labelOffsetCandidates(double baseOffset) {
    double oppositeOffset = baseOffset < 0.0 ? 18.0 : -10.0;
    return orderedValues(
        baseOffset,
        oppositeOffset,
        baseOffset + 28.0,
        baseOffset - 28.0,
        baseOffset + 56.0,
        baseOffset - 56.0,
        baseOffset + 84.0,
        baseOffset - 84.0,
        baseOffset + 112.0,
        baseOffset - 112.0,
        baseOffset + 140.0,
        baseOffset - 140.0);
  }

  public static Optional<EdgeLabel> firstClearVerticalLabel(
      LaidOutEdge edge, ResolvedEdgeStyle style, List<LabelBox> occupiedBoxes, double fontSize) {
    for (EdgeLabel candidate : verticalLabelCandidates(edge, style, fontSize)) {
      LabelBox candidateBox = edgeLabelVisibleBox(candidate, style.labelPresentation());
      if (occupiedBoxes.stream().noneMatch(candidateBox::overlaps)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  public static List<EdgeLabel> verticalLabelCandidates(
      LaidOutEdge edge, ResolvedEdgeStyle style, double fontSize) {
    List<Segment> verticalSegments = verticalSegments(edge);
    if (verticalSegments.isEmpty()) {
      return List.of();
    }
    if (firstHorizontalSegment(edge).isEmpty()) {
      Segment segment = verticalSegments.get(0);
      double minY = edge.points().stream().mapToDouble(Point::y).min().orElse(segment.start().y());
      double maxY = edge.points().stream().mapToDouble(Point::y).max().orElse(segment.end().y());
      return verticalLabelCandidates(
          edge, style, segment.start().x(), (minY + maxY) / 2.0, fontSize);
    }
    List<EdgeLabel> candidates = new ArrayList<>();
    for (Segment segment : verticalSegments) {
      candidates.addAll(
          verticalLabelCandidates(
              edge,
              style,
              segment.start().x(),
              (segment.start().y() + segment.end().y()) / 2.0,
              fontSize));
    }
    return candidates;
  }

  public static List<EdgeLabel> verticalLabelCandidates(
      LaidOutEdge edge, ResolvedEdgeStyle style, double segmentX, double y, double fontSize) {
    List<EdgeLabel> candidates = new ArrayList<>();
    List<SvgEdgeLabelVerticalSide> sides =
        style.labelVerticalSide() == SvgEdgeLabelVerticalSide.RIGHT
            ? List.of(SvgEdgeLabelVerticalSide.RIGHT, SvgEdgeLabelVerticalSide.LEFT)
            : List.of(SvgEdgeLabelVerticalSide.LEFT, SvgEdgeLabelVerticalSide.RIGHT);
    for (double offset : List.of(6.0, 34.0, 62.0)) {
      for (SvgEdgeLabelVerticalSide side : sides) {
        double x = side == SvgEdgeLabelVerticalSide.RIGHT ? segmentX + offset : segmentX - offset;
        String anchor = side == SvgEdgeLabelVerticalSide.RIGHT ? "start" : "end";
        candidates.add(edgeLabelCandidate(x, y, anchor, edge.label(), fontSize));
      }
    }
    return candidates;
  }

  public static double autoHorizontalLabelOffset(LaidOutEdge edge, int segmentIndex) {
    if (segmentIndex + 2 < edge.points().size()) {
      Point segmentStart = edge.points().get(segmentIndex);
      Point next = edge.points().get(segmentIndex + 2);
      if (next.y() < segmentStart.y()) {
        return -10.0;
      }
    }
    return 18.0;
  }

  public static Optional<Segment> firstHorizontalSegment(LaidOutEdge edge) {
    if (edge.routingHints().contains("shared_source_junction")) {
      for (int index = edge.points().size() - 2; index >= 0; index--) {
        Point start = edge.points().get(index);
        Point end = edge.points().get(index + 1);
        if (nearlyEqual(start.y(), end.y()) && Math.abs(start.x() - end.x()) > 0.001) {
          return Optional.of(new Segment(index, start, end));
        }
      }
    }
    for (int index = 0; index < edge.points().size() - 1; index++) {
      Point start = edge.points().get(index);
      Point end = edge.points().get(index + 1);
      if (nearlyEqual(start.y(), end.y()) && Math.abs(start.x() - end.x()) > 0.001) {
        return Optional.of(new Segment(index, start, end));
      }
    }
    return Optional.empty();
  }

  public static List<Segment> verticalSegments(LaidOutEdge edge) {
    List<Segment> segments = new ArrayList<>();
    for (int index = 0; index < edge.points().size() - 1; index++) {
      Point start = edge.points().get(index);
      Point end = edge.points().get(index + 1);
      if (nearlyEqual(start.x(), end.x()) && Math.abs(start.y() - end.y()) > 0.001) {
        segments.add(new Segment(index, start, end));
      }
    }
    return segments;
  }
}

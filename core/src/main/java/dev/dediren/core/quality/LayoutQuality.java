package dev.dediren.core.quality;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LayoutQuality {
  private static final double ROUTE_DETOUR_RATIO = 1.5;
  private static final double ROUTE_DETOUR_EXCESS = 240.0;
  private static final double ROUTE_CLOSE_PARALLEL_DISTANCE = 20.0;
  private static final double ROUTE_CLOSE_PARALLEL_MIN_OVERLAP = 40.0;
  private static final double GEOMETRY_EPSILON = 0.001;
  private static final double ROUTE_ENDPOINT_TOLERANCE = 1.5;
  // Layout units reserved for the group title row; render draws the group label inside the
  // top of the group rect, so members inside this band collide with the label visually.
  private static final double GROUP_LABEL_BAND_HEIGHT = 24.0;
  // Conservative label-fit estimate: generous per-char width and line height, and only flag
  // labels needing more than LABEL_OVERFLOW_FACTOR times the estimated capacity, so renderer
  // font differences cannot produce false positives.
  private static final double LABEL_CHAR_WIDTH = 7.0;
  private static final double LABEL_LINE_HEIGHT = 16.0;
  private static final double LABEL_PADDING = 8.0;
  private static final int LABEL_OVERFLOW_FACTOR = 2;
  // Nodes below this dimension are icon-like (UML ports, gates, pseudostates, junction dots);
  // renderers draw their labels adjacent to the shape, not inside it, so box capacity does
  // not constrain the label.
  private static final double LABEL_SPACE_MIN_DIMENSION = 40.0;
  private static final double JUNCTION_ROUTE_TOLERANCE = 2.0;
  // Edge-label dissociation band (issue #31). LABEL_BAND_GAP spans ELK Layered's compact/readable
  // edge-edge spacing (40-48px) so genuinely adjacent parallel labeled runs qualify, while spacious
  // spacing (64px) and roomy layouts stay clear. LABEL_BAND_MIN_OVERLAP is the parallel run length
  // needed to actually host an edge label. LABEL_BAND_MIN_NEIGHBOURS makes a band three-plus edges,
  // so only a trapped edge (labeled neighbours on both sides) counts.
  private static final double LABEL_BAND_GAP = 52.0;
  private static final double LABEL_BAND_MIN_OVERLAP = 48.0;
  private static final int LABEL_BAND_MIN_NEIGHBOURS = 2;

  private LayoutQuality() {}

  public static LayoutQualityReport validateLayout(LayoutResult result) {
    int overlapCount = countOverlaps(result);
    int connectorThroughNodeCount = countConnectorThroughNodes(result);
    int invalidRouteCount =
        (int) result.edges().stream().filter(edge -> routeHasIntegrityIssue(edge, result)).count();
    int routeDetourCount =
        (int) result.edges().stream().filter(edge -> hasExcessiveDetour(edge.points())).count();
    int routeCloseParallelCount = countCloseParallelRoutes(result);
    int groupBoundaryIssueCount = countGroupBoundaryIssues(result);
    int groupLabelBandIssueCount = countGroupLabelBandIssues(result);
    int labelSpaceIssueCount = countLabelSpaceIssues(result);
    int edgeLabelDissociationCount = countEdgeLabelDissociations(result);
    int edgeCrossingCount = countEdgeCrossings(result);
    int warningCount = result.warnings().size();
    // edgeCrossingCount is informational: crossings can be unavoidable in non-planar graphs,
    // so it never degrades status. Per-fixture thresholds are asserted in tests instead.
    String status =
        overlapCount == 0
                && connectorThroughNodeCount == 0
                && invalidRouteCount == 0
                && routeDetourCount == 0
                && routeCloseParallelCount == 0
                && groupBoundaryIssueCount == 0
                && groupLabelBandIssueCount == 0
                && labelSpaceIssueCount == 0
                && edgeLabelDissociationCount == 0
                && warningCount == 0
            ? "ok"
            : "warning";
    return new LayoutQualityReport(
        status,
        "draft",
        overlapCount,
        connectorThroughNodeCount,
        invalidRouteCount,
        routeDetourCount,
        routeCloseParallelCount,
        groupBoundaryIssueCount,
        groupLabelBandIssueCount,
        labelSpaceIssueCount,
        edgeLabelDissociationCount,
        edgeCrossingCount,
        warningCount);
  }

  /**
   * Envelope-level restatement of a warning verdict: one {@code warning}-severity diagnostic per
   * nonzero non-informational quality count, each pointing at its {@code data.*} field. Returned
   * empty when the report is clean, so {@code non-empty} is exactly the {@code "warning"} verdict
   * (the informational {@code edge_crossing_count} never contributes). Consumers that read only the
   * command envelope's {@code status}/{@code diagnostics} then see the verdict without descending
   * into {@code data}.
   */
  public static List<Diagnostic> layoutQualityWarnings(LayoutQualityReport report) {
    var diagnostics = new ArrayList<Diagnostic>();
    addQualityWarning(diagnostics, "overlap_count", report.overlapCount());
    addQualityWarning(
        diagnostics, "connector_through_node_count", report.connectorThroughNodeCount());
    addQualityWarning(diagnostics, "invalid_route_count", report.invalidRouteCount());
    addQualityWarning(diagnostics, "route_detour_count", report.routeDetourCount());
    addQualityWarning(diagnostics, "route_close_parallel_count", report.routeCloseParallelCount());
    addQualityWarning(diagnostics, "group_boundary_issue_count", report.groupBoundaryIssueCount());
    addQualityWarning(
        diagnostics, "group_label_band_issue_count", report.groupLabelBandIssueCount());
    addQualityWarning(diagnostics, "label_space_issue_count", report.labelSpaceIssueCount());
    addQualityWarning(
        diagnostics, "edge_label_dissociation_count", report.edgeLabelDissociationCount());
    addQualityWarning(diagnostics, "warning_count", report.warningCount());
    return List.copyOf(diagnostics);
  }

  private static void addQualityWarning(List<Diagnostic> diagnostics, String field, int count) {
    if (count <= 0) {
      return;
    }
    diagnostics.add(
        new Diagnostic(
            DiagnosticCode.LAYOUT_QUALITY_WARNING.code(),
            DiagnosticSeverity.WARNING,
            "layout quality metric '" + field + "' is " + count,
            "$.data." + field));
  }

  public static List<Diagnostic> validateLayoutDiagnostics(LayoutResult result) {
    var diagnostics = new ArrayList<Diagnostic>();
    // Non-finite coordinates (a layout-plugin bug, or a JSON magnitude Jackson widens to Infinity)
    // make every downstream geometry check silently wrong, since NaN comparisons are always false.
    // Report them at the source before the route and junction checks consume the coordinates.
    for (int nodeIndex = 0; nodeIndex < result.nodes().size(); nodeIndex++) {
      LaidOutNode node = result.nodes().get(nodeIndex);
      if (!allFinite(node.x(), node.y(), node.width(), node.height())) {
        diagnostics.add(
            routeError(
                DiagnosticCode.LAYOUT_NON_FINITE_GEOMETRY,
                "node '" + node.id() + "' has non-finite geometry",
                "$.nodes[" + nodeIndex + "]"));
      }
    }
    for (int edgeIndex = 0; edgeIndex < result.edges().size(); edgeIndex++) {
      LaidOutEdge edge = result.edges().get(edgeIndex);
      for (int pointIndex = 0; pointIndex < edge.points().size(); pointIndex++) {
        Point point = edge.points().get(pointIndex);
        if (!allFinite(point.x(), point.y())) {
          diagnostics.add(
              routeError(
                  DiagnosticCode.LAYOUT_NON_FINITE_GEOMETRY,
                  "edge '" + edge.id() + "' has a non-finite route point",
                  "$.edges[" + edgeIndex + "].points[" + pointIndex + "]"));
          break;
        }
      }
    }
    for (int groupIndex = 0; groupIndex < result.groups().size(); groupIndex++) {
      LaidOutGroup group = result.groups().get(groupIndex);
      if (!allFinite(group.x(), group.y(), group.width(), group.height())) {
        diagnostics.add(
            routeError(
                DiagnosticCode.LAYOUT_NON_FINITE_GEOMETRY,
                "group '" + group.id() + "' has non-finite geometry",
                "$.groups[" + groupIndex + "]"));
      }
    }
    for (int edgeIndex = 0; edgeIndex < result.edges().size(); edgeIndex++) {
      LaidOutEdge edge = result.edges().get(edgeIndex);
      if (edge.points().isEmpty()) {
        diagnostics.add(
            routeError(
                DiagnosticCode.LAYOUT_ROUTE_POINTS_EMPTY,
                "edge '" + edge.id() + "' has no route points",
                "$.edges[" + edgeIndex + "].points"));
        continue;
      }
      if (edge.points().size() < 2) {
        diagnostics.add(
            routeError(
                DiagnosticCode.LAYOUT_ROUTE_POINTS_INSUFFICIENT,
                "edge '" + edge.id() + "' must have at least start and end route points",
                "$.edges[" + edgeIndex + "].points"));
        continue;
      }
      LaidOutNode source = findNode(result, edge.source());
      LaidOutNode target = findNode(result, edge.target());
      if (source == null || target == null) {
        continue;
      }
      if (!endpointAccepted(edge.points().getFirst(), source, ROUTE_ENDPOINT_TOLERANCE)) {
        diagnostics.add(
            routeError(
                DiagnosticCode.LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER,
                "edge '"
                    + edge.id()
                    + "' first route point is not on source node '"
                    + edge.source()
                    + "' perimeter",
                "$.edges[" + edgeIndex + "].points[0]"));
      }
      if (!endpointAccepted(edge.points().getLast(), target, ROUTE_ENDPOINT_TOLERANCE)) {
        diagnostics.add(
            routeError(
                DiagnosticCode.LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER,
                "edge '"
                    + edge.id()
                    + "' last route point is not on target node '"
                    + edge.target()
                    + "' perimeter",
                "$.edges[" + edgeIndex + "].points[-1]"));
      }
    }
    for (int nodeIndex = 0; nodeIndex < result.nodes().size(); nodeIndex++) {
      LaidOutNode node = result.nodes().get(nodeIndex);
      if (!"junction".equals(node.role())) {
        continue;
      }
      double centerX = node.x() + node.width() / 2.0;
      double centerY = node.y() + node.height() / 2.0;
      // The rendered junction dot radius tracks min(w,h)/2; routes must reach the dot,
      // not merely the bounding box, or the line shows a visible gap.
      double reach = Math.min(node.width(), node.height()) / 2.0 + JUNCTION_ROUTE_TOLERANCE;
      for (LaidOutEdge edge : result.edges()) {
        boolean incident = node.id().equals(edge.source()) || node.id().equals(edge.target());
        if (!incident || edge.points().size() < 2) {
          continue;
        }
        if (distanceToRoute(centerX, centerY, edge.points()) > reach) {
          diagnostics.add(
              routeError(
                  DiagnosticCode.LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE,
                  "junction '"
                      + node.id()
                      + "' is not on the route of incident edge '"
                      + edge.id()
                      + "'",
                  "$.nodes[" + nodeIndex + "]"));
        }
      }
    }
    return diagnostics;
  }

  private static Diagnostic routeError(DiagnosticCode code, String message, String path) {
    return new Diagnostic(code.code(), DiagnosticSeverity.ERROR, message, path);
  }

  private static boolean allFinite(double... values) {
    for (double value : values) {
      if (!Double.isFinite(value)) {
        return false;
      }
    }
    return true;
  }

  private static boolean routeHasIntegrityIssue(LaidOutEdge edge, LayoutResult result) {
    if (edge.points().size() < 2) {
      return true;
    }
    LaidOutNode source = findNode(result, edge.source());
    LaidOutNode target = findNode(result, edge.target());
    if (source == null || target == null) {
      return false;
    }
    return !endpointAccepted(edge.points().getFirst(), source, ROUTE_ENDPOINT_TOLERANCE)
        || !endpointAccepted(edge.points().getLast(), target, ROUTE_ENDPOINT_TOLERANCE);
  }

  private static LaidOutNode findNode(LayoutResult result, String id) {
    return result.nodes().stream().filter(node -> id.equals(node.id())).findFirst().orElse(null);
  }

  private static boolean pointOnNodePerimeter(Point point, LaidOutNode node, double tolerance) {
    double left = node.x();
    double right = node.x() + node.width();
    double top = node.y();
    double bottom = node.y() + node.height();
    return point.x() >= left - tolerance
        && point.x() <= right + tolerance
        && point.y() >= top - tolerance
        && point.y() <= bottom + tolerance
        && (sameWithin(point.x(), left, tolerance)
            || sameWithin(point.x(), right, tolerance)
            || sameWithin(point.y(), top, tolerance)
            || sameWithin(point.y(), bottom, tolerance));
  }

  private static boolean endpointAccepted(Point point, LaidOutNode node, double tolerance) {
    if (pointOnNodePerimeter(point, node, tolerance)) {
      return true;
    }
    return "lifeline".equals(node.role()) && onLifelineAxis(point, node, tolerance);
  }

  // Sequence Message endpoints anchor to the lifeline axis: the participant head's vertical
  // edge extended downward. ELK places them at the head's left/right edge x, below the head box.
  private static boolean onLifelineAxis(Point point, LaidOutNode node, double tolerance) {
    double left = node.x();
    double right = node.x() + node.width();
    return point.y() >= node.y() - tolerance
        && (sameWithin(point.x(), left, tolerance) || sameWithin(point.x(), right, tolerance));
  }

  private static boolean sameWithin(double left, double right, double tolerance) {
    return Math.abs(left - right) <= tolerance;
  }

  private static int countOverlaps(LayoutResult result) {
    int count = 0;
    for (int i = 0; i < result.nodes().size(); i++) {
      LaidOutNode left = result.nodes().get(i);
      for (int j = i + 1; j < result.nodes().size(); j++) {
        LaidOutNode right = result.nodes().get(j);
        if (rectanglesOverlap(
            left.x(),
            left.y(),
            left.width(),
            left.height(),
            right.x(),
            right.y(),
            right.width(),
            right.height())) {
          count++;
        }
      }
    }
    return count;
  }

  private static int countConnectorThroughNodes(LayoutResult result) {
    int count = 0;
    for (LaidOutEdge edge : result.edges()) {
      for (int i = 0; i + 1 < edge.points().size(); i++) {
        Point start = edge.points().get(i);
        Point end = edge.points().get(i + 1);
        for (LaidOutNode node : result.nodes()) {
          if (!node.id().equals(edge.source())
              && !node.id().equals(edge.target())
              && segmentIntersectsRect(
                  start, end, node.x(), node.y(), node.width(), node.height())) {
            count++;
            break;
          }
        }
      }
    }
    return count;
  }

  private static int countGroupBoundaryIssues(LayoutResult result) {
    int count = 0;
    for (var group : result.groups()) {
      for (String memberId : group.members()) {
        LaidOutNode node = findNode(result, memberId);
        if (node != null) {
          if (!rectangleContains(
              group.x(),
              group.y(),
              group.width(),
              group.height(),
              node.x(),
              node.y(),
              node.width(),
              node.height())) {
            count++;
          }
          continue;
        }
        LaidOutGroup childGroup = findGroup(result, memberId);
        if (childGroup == null) {
          continue;
        }
        if (!rectangleContains(
            group.x(),
            group.y(),
            group.width(),
            group.height(),
            childGroup.x(),
            childGroup.y(),
            childGroup.width(),
            childGroup.height())) {
          count++;
        }
      }
    }
    for (LaidOutEdge edge : result.edges()) {
      for (int i = 0; i + 1 < edge.points().size(); i++) {
        Point start = edge.points().get(i);
        Point end = edge.points().get(i + 1);
        for (var group : result.groups()) {
          if (groupContainsNode(result, group, edge.source(), new HashSet<>())
              || groupContainsNode(result, group, edge.target(), new HashSet<>())) {
            continue;
          }
          if (segmentIntersectsRect(
              start, end, group.x(), group.y(), group.width(), group.height())) {
            count++;
            break;
          }
        }
      }
    }
    return count;
  }

  private static int countGroupLabelBandIssues(LayoutResult result) {
    int count = 0;
    for (LaidOutGroup group : result.groups()) {
      if (group.label() == null || group.label().isBlank()) {
        continue;
      }
      for (String memberId : group.members()) {
        LaidOutNode node = findNode(result, memberId);
        if (node != null) {
          if (rectanglesOverlap(
              group.x(),
              group.y(),
              group.width(),
              GROUP_LABEL_BAND_HEIGHT,
              node.x(),
              node.y(),
              node.width(),
              node.height())) {
            count++;
          }
          continue;
        }
        LaidOutGroup childGroup = findGroup(result, memberId);
        if (childGroup != null
            && rectanglesOverlap(
                group.x(),
                group.y(),
                group.width(),
                GROUP_LABEL_BAND_HEIGHT,
                childGroup.x(),
                childGroup.y(),
                childGroup.width(),
                childGroup.height())) {
          count++;
        }
      }
    }
    return count;
  }

  private static int countLabelSpaceIssues(LayoutResult result) {
    int count = 0;
    for (LaidOutNode node : result.nodes()) {
      if (node.label() == null
          || node.label().isBlank()
          || "junction".equals(node.role())
          || Math.min(node.width(), node.height()) < LABEL_SPACE_MIN_DIMENSION) {
        continue;
      }
      int charsPerLine =
          (int) Math.max(1.0, Math.floor((node.width() - 2 * LABEL_PADDING) / LABEL_CHAR_WIDTH));
      int lines =
          (int) Math.max(1.0, Math.floor((node.height() - 2 * LABEL_PADDING) / LABEL_LINE_HEIGHT));
      if (node.label().length() > charsPerLine * lines * LABEL_OVERFLOW_FACTOR) {
        count++;
      }
    }
    return count;
  }

  // Detects the layout precondition behind issue #31: a labeled edge trapped between parallel
  // labeled neighbours so close that a centered edge label cannot sit on its own route without
  // landing on a neighbour's route. The renderer then displaces the label away from its edge
  // (often nearer a different edge), and the reader misattributes the relationship. Counting the
  // trapped edges turns that otherwise-invisible dissociation into a nonzero, envelope-visible
  // quality signal.
  //
  // An edge is trapped when it has at least LABEL_BAND_MIN_NEIGHBOURS unrelated labeled edges whose
  // same-orientation run sits within LABEL_BAND_GAP perpendicular and overlaps its own run by at
  // least LABEL_BAND_MIN_OVERLAP. The gap covers ELK Layered's compact/readable edge-edge spacing
  // (40-48px); a band therefore needs three-plus members, so the outer edges of a band (open space
  // on one side) and benign two-edge parallels are not counted. Edges sharing an endpoint node are
  // excluded, matching the crossing and close-parallel checks: a reader groups a fan by its shared
  // node.
  private static int countEdgeLabelDissociations(LayoutResult result) {
    List<LabeledEdgeRuns> labeled = new ArrayList<>();
    List<LaidOutEdge> edges = result.edges();
    for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
      LaidOutEdge edge = edges.get(edgeIndex);
      if (edge.label() == null || edge.label().isBlank()) {
        continue;
      }
      List<RouteSegment> segments = new ArrayList<>();
      for (int i = 0; i + 1 < edge.points().size(); i++) {
        RouteSegment segment =
            routeSegment(
                edgeIndex,
                edge.source(),
                edge.target(),
                edge.points().get(i),
                edge.points().get(i + 1));
        if (segment != null) {
          segments.add(segment);
        }
      }
      if (!segments.isEmpty()) {
        labeled.add(new LabeledEdgeRuns(edge, segments));
      }
    }
    int count = 0;
    for (LabeledEdgeRuns edge : labeled) {
      int neighbours = 0;
      for (LabeledEdgeRuns other : labeled) {
        if (other == edge || edgesShareEndpointNode(edge.edge(), other.edge())) {
          continue;
        }
        if (runsCompeteForLabelBand(edge.segments(), other.segments())) {
          neighbours++;
        }
      }
      if (neighbours >= LABEL_BAND_MIN_NEIGHBOURS) {
        count++;
      }
    }
    return count;
  }

  private static boolean runsCompeteForLabelBand(
      List<RouteSegment> left, List<RouteSegment> right) {
    for (RouteSegment leftSegment : left) {
      for (RouteSegment rightSegment : right) {
        if (leftSegment.orientation() == rightSegment.orientation()
            && Math.abs(leftSegment.fixed() - rightSegment.fixed()) <= LABEL_BAND_GAP
            && overlapLength(
                    leftSegment.min(), leftSegment.max(), rightSegment.min(), rightSegment.max())
                >= LABEL_BAND_MIN_OVERLAP) {
          return true;
        }
      }
    }
    return false;
  }

  private static LaidOutGroup findGroup(LayoutResult result, String id) {
    return result.groups().stream().filter(group -> id.equals(group.id())).findFirst().orElse(null);
  }

  private static boolean groupContainsNode(
      LayoutResult result, LaidOutGroup group, String nodeId, Set<String> visitedGroups) {
    if (!visitedGroups.add(group.id())) {
      return false;
    }
    for (String memberId : group.members()) {
      if (memberId.equals(nodeId) && findNode(result, memberId) != null) {
        return true;
      }
      LaidOutGroup childGroup = findGroup(result, memberId);
      if (childGroup != null && groupContainsNode(result, childGroup, nodeId, visitedGroups)) {
        return true;
      }
    }
    return false;
  }

  private static boolean rectangleContains(
      double outerX,
      double outerY,
      double outerWidth,
      double outerHeight,
      double innerX,
      double innerY,
      double innerWidth,
      double innerHeight) {
    return innerX >= outerX
        && innerY >= outerY
        && innerX + innerWidth <= outerX + outerWidth
        && innerY + innerHeight <= outerY + outerHeight;
  }

  private static int countCloseParallelRoutes(LayoutResult result) {
    var segments = new ArrayList<RouteSegment>();
    for (int edgeIndex = 0; edgeIndex < result.edges().size(); edgeIndex++) {
      LaidOutEdge edge = result.edges().get(edgeIndex);
      for (int i = 0; i + 1 < edge.points().size(); i++) {
        RouteSegment segment =
            routeSegment(
                edgeIndex,
                edge.source(),
                edge.target(),
                edge.points().get(i),
                edge.points().get(i + 1));
        if (segment != null) {
          segments.add(segment);
        }
      }
    }
    int count = 0;
    for (int i = 0; i < segments.size(); i++) {
      for (int j = i + 1; j < segments.size(); j++) {
        if (closeParallelRouteSegments(segments.get(i), segments.get(j))) {
          count++;
        }
      }
    }
    return count;
  }

  private static RouteSegment routeSegment(
      int edgeIndex, String source, String target, Point start, Point end) {
    if (sameCoordinate(start.y(), end.y()) && !sameCoordinate(start.x(), end.x())) {
      return new RouteSegment(
          edgeIndex,
          source,
          target,
          Orientation.HORIZONTAL,
          start.y(),
          Math.min(start.x(), end.x()),
          Math.max(start.x(), end.x()));
    }
    if (sameCoordinate(start.x(), end.x()) && !sameCoordinate(start.y(), end.y())) {
      return new RouteSegment(
          edgeIndex,
          source,
          target,
          Orientation.VERTICAL,
          start.x(),
          Math.min(start.y(), end.y()),
          Math.max(start.y(), end.y()));
    }
    return null;
  }

  private static boolean closeParallelRouteSegments(RouteSegment left, RouteSegment right) {
    return left.edgeIndex != right.edgeIndex
        && !shareEndpoint(left, right)
        && left.orientation == right.orientation
        && Math.abs(left.fixed - right.fixed) < ROUTE_CLOSE_PARALLEL_DISTANCE
        && overlapLength(left.min, left.max, right.min, right.max)
            >= ROUTE_CLOSE_PARALLEL_MIN_OVERLAP;
  }

  private static boolean shareEndpoint(RouteSegment left, RouteSegment right) {
    return left.source.equals(right.source)
        || left.source.equals(right.target)
        || left.target.equals(right.source)
        || left.target.equals(right.target);
  }

  private static double overlapLength(
      double leftMin, double leftMax, double rightMin, double rightMax) {
    return Math.max(0.0, Math.min(leftMax, rightMax) - Math.max(leftMin, rightMin));
  }

  private static boolean hasExcessiveDetour(List<Point> points) {
    if (points.size() < 2) {
      return false;
    }
    double routeLength = routeLength(points);
    Point start = points.getFirst();
    Point end = points.getLast();
    double directLength = Math.abs(start.x() - end.x()) + Math.abs(start.y() - end.y());
    return directLength > 0.0
        && routeLength > directLength * ROUTE_DETOUR_RATIO
        && routeLength - directLength > ROUTE_DETOUR_EXCESS;
  }

  private static double routeLength(List<Point> points) {
    double length = 0.0;
    for (int i = 0; i + 1 < points.size(); i++) {
      length +=
          Math.abs(points.get(i).x() - points.get(i + 1).x())
              + Math.abs(points.get(i).y() - points.get(i + 1).y());
    }
    return length;
  }

  private static boolean sameCoordinate(double left, double right) {
    return Math.abs(left - right) <= GEOMETRY_EPSILON;
  }

  private static boolean rectanglesOverlap(
      double leftX,
      double leftY,
      double leftWidth,
      double leftHeight,
      double rightX,
      double rightY,
      double rightWidth,
      double rightHeight) {
    return leftX < rightX + rightWidth
        && leftX + leftWidth > rightX
        && leftY < rightY + rightHeight
        && leftY + leftHeight > rightY;
  }

  private static boolean segmentIntersectsRect(
      Point start, Point end, double rectX, double rectY, double rectWidth, double rectHeight) {
    double minX = Math.min(start.x(), end.x());
    double maxX = Math.max(start.x(), end.x());
    double minY = Math.min(start.y(), end.y());
    double maxY = Math.max(start.y(), end.y());
    return rectanglesOverlap(
        minX,
        minY,
        Math.max(maxX - minX, 1.0),
        Math.max(maxY - minY, 1.0),
        rectX,
        rectY,
        rectWidth,
        rectHeight);
  }

  private static double distanceToRoute(double x, double y, List<Point> points) {
    double min = Double.MAX_VALUE;
    for (int i = 0; i + 1 < points.size(); i++) {
      min = Math.min(min, distanceToSegment(x, y, points.get(i), points.get(i + 1)));
    }
    return min;
  }

  private static double distanceToSegment(double x, double y, Point start, Point end) {
    double dx = end.x() - start.x();
    double dy = end.y() - start.y();
    double lengthSquared = dx * dx + dy * dy;
    double t =
        lengthSquared == 0.0
            ? 0.0
            : Math.clamp(((x - start.x()) * dx + (y - start.y()) * dy) / lengthSquared, 0.0, 1.0);
    return Math.hypot(x - (start.x() + t * dx), y - (start.y() + t * dy));
  }

  private static int countEdgeCrossings(LayoutResult result) {
    int count = 0;
    for (int i = 0; i < result.edges().size(); i++) {
      for (int j = i + 1; j < result.edges().size(); j++) {
        LaidOutEdge left = result.edges().get(i);
        LaidOutEdge right = result.edges().get(j);
        if (edgesShareEndpointNode(left, right)) {
          continue;
        }
        if (routesProperlyCross(left.points(), right.points())) {
          count++;
        }
      }
    }
    return count;
  }

  private static boolean edgesShareEndpointNode(LaidOutEdge left, LaidOutEdge right) {
    return left.source().equals(right.source())
        || left.source().equals(right.target())
        || left.target().equals(right.source())
        || left.target().equals(right.target());
  }

  private static boolean routesProperlyCross(List<Point> leftPoints, List<Point> rightPoints) {
    for (int i = 0; i + 1 < leftPoints.size(); i++) {
      for (int j = 0; j + 1 < rightPoints.size(); j++) {
        if (segmentsProperlyCross(
            leftPoints.get(i), leftPoints.get(i + 1),
            rightPoints.get(j), rightPoints.get(j + 1))) {
          return true;
        }
      }
    }
    return false;
  }

  // Proper crossing only (interiors intersect). Touches and collinear overlaps are excluded so
  // orthogonal routes that share a corner coordinate do not count as crossings.
  private static boolean segmentsProperlyCross(Point a, Point b, Point c, Point d) {
    double o1 = orientation(a, b, c);
    double o2 = orientation(a, b, d);
    double o3 = orientation(c, d, a);
    double o4 = orientation(c, d, b);
    return o1 * o2 < 0 && o3 * o4 < 0;
  }

  private static double orientation(Point a, Point b, Point c) {
    return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
  }

  private enum Orientation {
    HORIZONTAL,
    VERTICAL
  }

  private record RouteSegment(
      int edgeIndex,
      String source,
      String target,
      Orientation orientation,
      double fixed,
      double min,
      double max) {}

  private record LabeledEdgeRuns(LaidOutEdge edge, List<RouteSegment> segments) {}
}

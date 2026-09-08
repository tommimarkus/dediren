package dev.dediren.core.quality;

import static dev.dediren.ir.RouteGeometry.flatten;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.CubicBezierRoute;
import dev.dediren.contracts.layout.CubicBezierSegment;
import dev.dediren.contracts.layout.EdgeRoute;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutNodeRole;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.layout.PolylineRoute;
import dev.dediren.ir.RouteGeometry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LayoutQuality {
  private static final double ROUTE_DETOUR_RATIO = 1.5;
  private static final double SIMPLE_SIDE_RETURN_DETOUR_RATIO = 2.0;
  private static final double ROUTE_DETOUR_EXCESS = 240.0;
  private static final double ROUTE_CLOSE_PARALLEL_DISTANCE = 20.0;
  private static final double ROUTE_CLOSE_PARALLEL_MIN_OVERLAP = 40.0;
  private static final double ROUTE_NODE_CLEARANCE = 24.0;
  private static final double TERMINAL_CONTACT_ALLOWANCE = 48.0;
  private static final double QUALITY_FLATTENING_MAX_ERROR = 0.0625;
  private static final double EVENT_DEDUPLICATION_TOLERANCE = 0.25;
  private static final double NUMERIC_GEOMETRY_EPSILON = 1.0e-10;
  private static final double GEOMETRY_EPSILON = 0.001;
  private static final double ROUTE_ENDPOINT_TOLERANCE =
      dev.dediren.ir.quality.LayoutTolerances.ROUTE_ENDPOINT_TOLERANCE;
  // Layout units reserved for the group title row; render draws the group label inside the
  // top of the group rect, so members inside this band collide with the label visually.
  private static final double GROUP_LABEL_BAND_HEIGHT = 24.0;
  private static final double GROUP_BOUNDARY_INTERIOR_INSET = 1.0;
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
  // A self-loop must poke out beyond its node by at least this much to read as a visible loop;
  // a route staying within the box renders hidden behind the (later-painted, opaque) node.
  private static final double SELF_LOOP_MIN_ESCAPE = 4.0;
  // A route touching (or briefly overshooting) its own endpoint's perimeter is normal contact,
  // not a through-node defect; ELK places port anchors up to ~1px outside the outline, plus
  // rounding, so the inset must clear that band before a segment counts as entering the node's
  // interior rather than just meeting it.
  private static final double OWN_ENDPOINT_INTERIOR_INSET = 1.5;
  // Once inside the inset interior, the segment must still travel a non-trivial distance through
  // it along its own axis; a hairline graze at the inset boundary should not register.
  private static final double OWN_ENDPOINT_MIN_OVERLAP = 2.0;
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
    int routeNodeClearanceIssueCount = countRouteNodeClearanceIssues(result);
    int invalidRouteCount =
        (int) result.edges().stream().filter(edge -> routeHasIntegrityIssue(edge, result)).count();
    int routeDetourCount =
        (int) result.edges().stream().filter(edge -> hasExcessiveDetour(edge, result)).count();
    int routeOverlapCount = countRouteOverlaps(result);
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
                && routeNodeClearanceIssueCount == 0
                && invalidRouteCount == 0
                && routeDetourCount == 0
                && routeOverlapCount == 0
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
        routeNodeClearanceIssueCount,
        invalidRouteCount,
        routeDetourCount,
        routeOverlapCount,
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
    addQualityWarning(
        diagnostics, "route_node_clearance_issue_count", report.routeNodeClearanceIssueCount());
    addQualityWarning(diagnostics, "invalid_route_count", report.invalidRouteCount());
    addQualityWarning(diagnostics, "route_detour_count", report.routeDetourCount());
    addQualityWarning(diagnostics, "route_overlap_count", report.routeOverlapCount());
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

  /**
   * Structural hygiene of a layout result — duplicate ids and non-positive extents — as {@code
   * warning}-severity diagnostics.
   *
   * <p>These deliberately ride the warning lane rather than {@link #validateLayoutDiagnostics}'s
   * hard lane. Both conditions describe input that still renders, just not as its author meant: a
   * duplicate id makes every id lookup ambiguous (findNode/findGroup take the first match and the
   * rest silently vanish from checks and from any consumer resolving by id), and a non-positive
   * width or height collapses the shape to nothing. Nothing downstream is unable to proceed, so
   * promoting them to the hard lane would make {@code build} and {@code validate-layout} start
   * rejecting layouts they accept today — a tightening no lane asked for. On the warning lane every
   * lane gains the signal and no lane changes verdict on input it accepts.
   *
   * <p>Ids are compared within each id space (nodes, edges, groups) separately, because that is how
   * they are resolved: {@code findNode} searches nodes and {@code findGroup} searches groups, so a
   * node and a group sharing an id is unambiguous, while a repeat inside one space is not.
   */
  public static List<Diagnostic> layoutStructureWarnings(LayoutResult result) {
    var diagnostics = new ArrayList<Diagnostic>();
    addDuplicateIdWarnings(
        diagnostics, "node", "nodes", result.nodes().stream().map(LaidOutNode::id).toList());
    addDuplicateIdWarnings(
        diagnostics, "edge", "edges", result.edges().stream().map(LaidOutEdge::id).toList());
    addDuplicateIdWarnings(
        diagnostics, "group", "groups", result.groups().stream().map(LaidOutGroup::id).toList());
    for (int nodeIndex = 0; nodeIndex < result.nodes().size(); nodeIndex++) {
      LaidOutNode node = result.nodes().get(nodeIndex);
      if (isNonPositiveExtent(node.width(), node.height())) {
        diagnostics.add(
            structureWarning(
                DiagnosticCode.LAYOUT_NON_POSITIVE_EXTENT,
                extentMessage("node", node.id(), node.width(), node.height()),
                "$.nodes[" + nodeIndex + "]",
                node.sourcePointer()));
      }
    }
    for (int groupIndex = 0; groupIndex < result.groups().size(); groupIndex++) {
      LaidOutGroup group = result.groups().get(groupIndex);
      if (isNonPositiveExtent(group.width(), group.height())) {
        diagnostics.add(
            structureWarning(
                DiagnosticCode.LAYOUT_NON_POSITIVE_EXTENT,
                extentMessage("group", group.id(), group.width(), group.height()),
                "$.groups[" + groupIndex + "]",
                null));
      }
    }
    return List.copyOf(diagnostics);
  }

  /**
   * The warn-first verdict on a layout result that arrived from outside the pipeline: every {@link
   * #validateLayoutDiagnostics} finding restated at {@code warning} severity, plus {@link
   * #layoutStructureWarnings}.
   *
   * <p>Used by the render lane, which is the only lane that takes a caller-supplied layout result
   * and turns it straight into an artifact — until now with no geometry check at all. The severity
   * downgrade is the deliberate part: on {@code validate-layout} and {@code build} these findings
   * are input errors that stop the command, but render's decided posture is to attach the signal
   * and still produce the artifact, so a caller whose layout is subtly wrong sees why without
   * losing the output they asked for. Hard rejection on this lane is a separate decision.
   */
  public static List<Diagnostic> layoutInputWarnings(LayoutResult result) {
    var diagnostics = new ArrayList<Diagnostic>();
    for (Diagnostic diagnostic : validateLayoutDiagnostics(result)) {
      diagnostics.add(downgradedToWarning(diagnostic));
    }
    diagnostics.addAll(layoutStructureWarnings(result));
    return List.copyOf(diagnostics);
  }

  /** The same diagnostic — code, message, path, provenance — restated at {@code warning}. */
  private static Diagnostic downgradedToWarning(Diagnostic diagnostic) {
    return new Diagnostic(
        diagnostic.code(),
        DiagnosticSeverity.WARNING,
        diagnostic.message(),
        diagnostic.path(),
        diagnostic.sourcePointer(),
        diagnostic.migration());
  }

  private static void addDuplicateIdWarnings(
      List<Diagnostic> diagnostics, String element, String space, List<String> ids) {
    var seen = new HashSet<String>();
    for (String id : ids) {
      if (!seen.add(id)) {
        diagnostics.add(
            structureWarning(
                DiagnosticCode.LAYOUT_DUPLICATE_ID,
                "duplicate " + element + " id '" + id + "'",
                "$." + space + "[?(@.id=='" + id + "')]",
                null));
      }
    }
  }

  private static boolean isNonPositiveExtent(double width, double height) {
    // NaN fails every comparison, so a NaN extent is not reported here; it is already the hard
    // lane's DEDIREN_LAYOUT_NON_FINITE_GEOMETRY, and reporting it twice would just add noise.
    return width <= 0.0 || height <= 0.0;
  }

  private static String extentMessage(String element, String id, double width, double height) {
    return element
        + " '"
        + id
        + "' has non-positive extent "
        + width
        + "x"
        + height
        + " and renders as nothing";
  }

  private static Diagnostic structureWarning(
      DiagnosticCode code, String message, String path, String sourcePointer) {
    return new Diagnostic(code.code(), DiagnosticSeverity.WARNING, message, path, sourcePointer);
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
                "$.nodes[" + nodeIndex + "]",
                node.sourcePointer()));
      }
    }
    for (int edgeIndex = 0; edgeIndex < result.edges().size(); edgeIndex++) {
      LaidOutEdge edge = result.edges().get(edgeIndex);
      Diagnostic routeGeometryError = routeGeometryError(edge, edgeIndex);
      if (routeGeometryError != null) {
        diagnostics.add(routeGeometryError);
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
      if (routeGeometryError(edge, edgeIndex) != null) {
        continue;
      }
      List<Point> points = routePoints(edge);
      if (points.isEmpty()) {
        diagnostics.add(
            routeError(
                DiagnosticCode.LAYOUT_ROUTE_POINTS_EMPTY,
                "edge '" + edge.id() + "' has no route points",
                routePath(edge, edgeIndex),
                edge.sourcePointer()));
        continue;
      }
      if (points.size() < 2) {
        diagnostics.add(
            routeError(
                DiagnosticCode.LAYOUT_ROUTE_POINTS_INSUFFICIENT,
                "edge '" + edge.id() + "' must have at least start and end route points",
                routePath(edge, edgeIndex),
                edge.sourcePointer()));
        continue;
      }
      LaidOutNode source = findNode(result, edge.source());
      LaidOutNode target = findNode(result, edge.target());
      if (source == null || target == null) {
        continue;
      }
      if (!endpointAccepted(points.getFirst(), source, ROUTE_ENDPOINT_TOLERANCE)) {
        diagnostics.add(
            routeError(
                DiagnosticCode.LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER,
                "edge '"
                    + edge.id()
                    + "' first route point is not on source node '"
                    + edge.source()
                    + "' perimeter",
                "$.edges[" + edgeIndex + "].route",
                edge.sourcePointer()));
      }
      if (!endpointAccepted(points.getLast(), target, ROUTE_ENDPOINT_TOLERANCE)) {
        diagnostics.add(
            routeError(
                DiagnosticCode.LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER,
                "edge '"
                    + edge.id()
                    + "' last route point is not on target node '"
                    + edge.target()
                    + "' perimeter",
                "$.edges[" + edgeIndex + "].route",
                edge.sourcePointer()));
      }
    }
    for (int nodeIndex = 0; nodeIndex < result.nodes().size(); nodeIndex++) {
      LaidOutNode node = result.nodes().get(nodeIndex);
      if (!LayoutNodeRole.isJunction(node.role())) {
        continue;
      }
      double centerX = node.x() + node.width() / 2.0;
      double centerY = node.y() + node.height() / 2.0;
      // The rendered junction dot radius tracks min(w,h)/2; routes must reach the dot,
      // not merely the bounding box, or the line shows a visible gap.
      double reach = Math.min(node.width(), node.height()) / 2.0 + JUNCTION_ROUTE_TOLERANCE;
      for (LaidOutEdge edge : result.edges()) {
        boolean incident = node.id().equals(edge.source()) || node.id().equals(edge.target());
        List<Point> points = routePoints(edge);
        if (!incident || points.size() < 2) {
          continue;
        }
        if (distanceToRoute(centerX, centerY, points) > reach) {
          diagnostics.add(
              routeError(
                  DiagnosticCode.LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE,
                  "junction '"
                      + node.id()
                      + "' is not on the route of incident edge '"
                      + edge.id()
                      + "'",
                  "$.nodes[" + nodeIndex + "]",
                  node.sourcePointer()));
        }
      }
    }
    for (int edgeIndex = 0; edgeIndex < result.edges().size(); edgeIndex++) {
      LaidOutEdge edge = result.edges().get(edgeIndex);
      List<Point> points = routePoints(edge);
      if (!edge.source().equals(edge.target()) || points.size() < 2) {
        continue;
      }
      LaidOutNode node = findNode(result, edge.source());
      if (node != null && !selfLoopEscapesNode(points, node)) {
        diagnostics.add(
            routeError(
                DiagnosticCode.LAYOUT_SELF_LOOP_DEGENERATE,
                "self-loop '"
                    + edge.id()
                    + "' does not extend outside node '"
                    + node.id()
                    + "' and renders hidden behind it",
                "$.edges[" + edgeIndex + "]",
                edge.sourcePointer()));
      }
    }
    return diagnostics;
  }

  private static Diagnostic routeError(DiagnosticCode code, String message, String path) {
    return new Diagnostic(code.code(), DiagnosticSeverity.ERROR, message, path);
  }

  private static Diagnostic routeGeometryError(LaidOutEdge edge, int edgeIndex) {
    String base = "$.edges[" + edgeIndex + "].route";
    EdgeRoute route = edge.route();
    if (route == null) {
      return routeError(
          DiagnosticCode.LAYOUT_ROUTE_POINTS_EMPTY,
          "edge '" + edge.id() + "' has no route geometry",
          base,
          edge.sourcePointer());
    }
    if (route instanceof PolylineRoute polyline) {
      for (int pointIndex = 0; pointIndex < polyline.points().size(); pointIndex++) {
        Point point = polyline.points().get(pointIndex);
        if (!finite(point)) {
          return routeError(
              DiagnosticCode.LAYOUT_NON_FINITE_GEOMETRY,
              "edge '" + edge.id() + "' has a missing or non-finite route point",
              base + ".points[" + pointIndex + "]",
              edge.sourcePointer());
        }
      }
      return null;
    }
    CubicBezierRoute cubic = (CubicBezierRoute) route;
    if (!finite(cubic.start())) {
      return routeError(
          DiagnosticCode.LAYOUT_NON_FINITE_GEOMETRY,
          "edge '" + edge.id() + "' has a missing or non-finite cubic start",
          base + ".start",
          edge.sourcePointer());
    }
    for (int segmentIndex = 0; segmentIndex < cubic.segments().size(); segmentIndex++) {
      CubicBezierSegment segment = cubic.segments().get(segmentIndex);
      String segmentPath = base + ".segments[" + segmentIndex + "]";
      if (segment == null
          || !finite(segment.control1())
          || !finite(segment.control2())
          || !finite(segment.end())) {
        return routeError(
            DiagnosticCode.LAYOUT_NON_FINITE_GEOMETRY,
            "edge '" + edge.id() + "' has missing or non-finite cubic controls",
            segmentPath,
            edge.sourcePointer());
      }
    }
    return null;
  }

  private static String routePath(LaidOutEdge edge, int edgeIndex) {
    String suffix = edge.route() instanceof CubicBezierRoute ? ".segments" : ".points";
    return "$.edges[" + edgeIndex + "].route" + suffix;
  }

  private static boolean finite(Point point) {
    return point != null && allFinite(point.x(), point.y());
  }

  private static List<Point> routePoints(LaidOutEdge edge) {
    try {
      return flatten(edge.route());
    } catch (IllegalArgumentException exception) {
      return List.of();
    }
  }

  // Quality decisions close to a curve use a tighter approximation than the shared 0.25-unit
  // default. The route remains the sole authority; this only removes classification ambiguity.
  private static List<Point> qualityRoutePoints(LaidOutEdge edge) {
    try {
      return RouteGeometry.flatten(edge.route(), QUALITY_FLATTENING_MAX_ERROR);
    } catch (IllegalArgumentException exception) {
      return List.of();
    }
  }

  private static Diagnostic routeError(
      DiagnosticCode code, String message, String path, String sourcePointer) {
    return new Diagnostic(code.code(), DiagnosticSeverity.ERROR, message, path, sourcePointer);
  }

  private static boolean selfLoopEscapesNode(List<Point> points, LaidOutNode node) {
    double left = node.x();
    double right = node.x() + node.width();
    double top = node.y();
    double bottom = node.y() + node.height();
    for (Point point : points) {
      if (point.x() < left - SELF_LOOP_MIN_ESCAPE
          || point.x() > right + SELF_LOOP_MIN_ESCAPE
          || point.y() < top - SELF_LOOP_MIN_ESCAPE
          || point.y() > bottom + SELF_LOOP_MIN_ESCAPE) {
        return true;
      }
    }
    return false;
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
    List<Point> points = routePoints(edge);
    if (points.size() < 2) {
      return true;
    }
    LaidOutNode source = findNode(result, edge.source());
    LaidOutNode target = findNode(result, edge.target());
    if (source == null || target == null) {
      return false;
    }
    return !endpointAccepted(points.getFirst(), source, ROUTE_ENDPOINT_TOLERANCE)
        || !endpointAccepted(points.getLast(), target, ROUTE_ENDPOINT_TOLERANCE);
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
    return LayoutNodeRole.isLifeline(node.role()) && onLifelineAxis(point, node, tolerance);
  }

  // Sequence Message endpoints anchor to the lifeline axis: the participant head-box center,
  // extended downward (the render engine's own convention — see UmlSequenceRenderer's stem/
  // message-endpoint geometry, "the head-box center"). ELK places message endpoints at that
  // center x, below the head box.
  private static boolean onLifelineAxis(Point point, LaidOutNode node, double tolerance) {
    double centerX = node.x() + node.width() / 2.0;
    return point.y() >= node.y() - tolerance && sameWithin(point.x(), centerX, tolerance);
  }

  private static boolean sameWithin(double left, double right, double tolerance) {
    return Math.abs(left - right) <= tolerance;
  }

  private static boolean isSequenceChrome(LaidOutNode node) {
    // A UML sequence interaction frame legitimately encloses its lifelines, executions, and
    // messages; and an execution bar / destruction marker legitimately sits ON the lifeline stem a
    // message terminates on. None of these are overlaps or route-through-node defects. The
    // hard-error lane (validateLayoutDiagnostics) still guards them.
    return LayoutNodeRole.isInteraction(node.role())
        || LayoutNodeRole.isExecution(node.role())
        || LayoutNodeRole.isDestruction(node.role());
  }

  private static int countOverlaps(LayoutResult result) {
    int count = 0;
    for (int i = 0; i < result.nodes().size(); i++) {
      LaidOutNode left = result.nodes().get(i);
      if (isSequenceChrome(left)) {
        continue;
      }
      for (int j = i + 1; j < result.nodes().size(); j++) {
        LaidOutNode right = result.nodes().get(j);
        if (isSequenceChrome(right)) {
          continue;
        }
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
      // Degenerate loops have their own diagnostic. Escaping loops must also be checked for
      // unintended own-node interior crossings between their valid perimeter attachments.
      boolean selfLoop = edge.source().equals(edge.target());
      List<Point> points = routePoints(edge);
      for (int i = 0; i + 1 < points.size(); i++) {
        Point start = points.get(i);
        Point end = points.get(i + 1);
        for (LaidOutNode node : result.nodes()) {
          if (isSequenceChrome(node)) {
            continue;
          }
          boolean ownEndpoint = node.id().equals(edge.source()) || node.id().equals(edge.target());
          if (ownEndpoint) {
            if (selfLoop
                && selfLoopEscapesNode(points, node)
                && segmentPiercesOwnEndpointInterior(start, end, node)) {
              count++;
              break;
            }
            // A lifeline is exempt from the own-endpoint widening for the same reason
            // endpointAccepted/onLifelineAxis exists: a Message anchors to the lifeline *axis*
            // (the head-box centre x, extended downward), which lies inside the head-box
            // rectangle. Terminating there is correct sequence geometry, not a route driven
            // through a node body, so the interior test would report every destroying or
            // head-adjacent message. A message crossing some *other* lifeline is still counted
            // by the unrelated-node path below.
            if (!selfLoop
                && !LayoutNodeRole.isLifeline(node.role())
                && segmentPiercesOwnEndpointInterior(start, end, node)) {
              count++;
              break;
            }
            continue;
          }
          if (segmentIntersectsRect(start, end, node.x(), node.y(), node.width(), node.height())) {
            count++;
            break;
          }
        }
      }
    }
    return count;
  }

  /** Counts route/node pairs closer than the standalone 24-unit readability floor. */
  private static int countRouteNodeClearanceIssues(LayoutResult result) {
    int count = 0;
    for (int edgeIndex = 0; edgeIndex < result.edges().size(); edgeIndex++) {
      LaidOutEdge edge = result.edges().get(edgeIndex);
      if (routeGeometryError(edge, edgeIndex) != null) {
        continue;
      }
      List<Point> points = qualityRoutePoints(edge);
      if (points.size() < 2) {
        continue;
      }
      boolean selfLoop = edge.source().equals(edge.target());
      for (LaidOutNode node : result.nodes()) {
        if (isSequenceChrome(node)) {
          continue;
        }
        if (LayoutNodeRole.isLifeline(node.role())
            && (node.id().equals(edge.source()) || node.id().equals(edge.target()))) {
          continue;
        }
        double nearest = Double.POSITIVE_INFINITY;
        for (int segment = 0; segment + 1 < points.size(); segment++) {
          if (selfLoop && node.id().equals(edge.source())) {
            if (selfLoopEscapesNode(points, node)
                && segmentPiercesOwnEndpointInterior(
                    points.get(segment), points.get(segment + 1), node)) {
              count++;
              break;
            }
            continue;
          }
          ClearanceSegment clearanceSegment = terminalClearanceSegment(edge, node, points, segment);
          if (clearanceSegment == null) {
            continue;
          }
          nearest =
              Math.min(
                  nearest,
                  segmentRectangleDistance(clearanceSegment.start(), clearanceSegment.end(), node));
        }
        if (nearest <= ROUTE_NODE_CLEARANCE + QUALITY_FLATTENING_MAX_ERROR
            && refinedRouteViolatesClearance(edge, node)) {
          count++;
        }
      }
    }
    return count;
  }

  private static boolean refinedRouteViolatesClearance(LaidOutEdge edge, LaidOutNode node) {
    double error = QUALITY_FLATTENING_MAX_ERROR;
    while (error > NUMERIC_GEOMETRY_EPSILON) {
      List<Point> refined = flattenAtError(edge, error);
      double nearest = Double.POSITIVE_INFINITY;
      for (int segment = 0; segment + 1 < refined.size(); segment++) {
        ClearanceSegment clearanceSegment = terminalClearanceSegment(edge, node, refined, segment);
        if (clearanceSegment == null) {
          continue;
        }
        nearest =
            Math.min(
                nearest,
                segmentRectangleDistance(clearanceSegment.start(), clearanceSegment.end(), node));
      }
      if (Math.abs(nearest - ROUTE_NODE_CLEARANCE) > error) {
        return nearest < ROUTE_NODE_CLEARANCE;
      }
      error /= 4.0;
    }
    return false;
  }

  private static ClearanceSegment terminalClearanceSegment(
      LaidOutEdge edge, LaidOutNode node, List<Point> points, int segment) {
    if (node.id().equals(edge.source())) {
      double length = 0.0;
      for (int index = 0; index < segment; index++) {
        length += pointDistance(points.get(index), points.get(index + 1));
      }
      Point start = points.get(segment);
      Point end = points.get(segment + 1);
      double segmentLength = pointDistance(start, end);
      if (length + segmentLength <= TERMINAL_CONTACT_ALLOWANCE) {
        return null;
      }
      if (length < TERMINAL_CONTACT_ALLOWANCE) {
        start = interpolate(start, end, (TERMINAL_CONTACT_ALLOWANCE - length) / segmentLength);
      }
      return new ClearanceSegment(start, end);
    }
    if (node.id().equals(edge.target())) {
      double length = 0.0;
      for (int index = points.size() - 2; index > segment; index--) {
        length += pointDistance(points.get(index), points.get(index + 1));
      }
      Point start = points.get(segment);
      Point end = points.get(segment + 1);
      double segmentLength = pointDistance(start, end);
      if (length + segmentLength <= TERMINAL_CONTACT_ALLOWANCE) {
        return null;
      }
      if (length < TERMINAL_CONTACT_ALLOWANCE) {
        end = interpolate(end, start, (TERMINAL_CONTACT_ALLOWANCE - length) / segmentLength);
      }
      return new ClearanceSegment(start, end);
    }
    return new ClearanceSegment(points.get(segment), points.get(segment + 1));
  }

  private static double pointDistance(Point left, Point right) {
    return Math.hypot(left.x() - right.x(), left.y() - right.y());
  }

  private static Point interpolate(Point start, Point end, double fraction) {
    return new Point(
        start.x() + (end.x() - start.x()) * fraction, start.y() + (end.y() - start.y()) * fraction);
  }

  private static double segmentRectangleDistance(Point start, Point end, LaidOutNode node) {
    if (segmentIntersectsRect(start, end, node.x(), node.y(), node.width(), node.height())) {
      return 0.0;
    }
    Point topLeft = new Point(node.x(), node.y());
    Point topRight = new Point(node.x() + node.width(), node.y());
    Point bottomRight = new Point(node.x() + node.width(), node.y() + node.height());
    Point bottomLeft = new Point(node.x(), node.y() + node.height());
    return Math.min(
        Math.min(
            segmentDistance(start, end, topLeft, topRight),
            segmentDistance(start, end, topRight, bottomRight)),
        Math.min(
            segmentDistance(start, end, bottomRight, bottomLeft),
            segmentDistance(start, end, bottomLeft, topLeft)));
  }

  private static double segmentDistance(Point a, Point b, Point c, Point d) {
    if (segmentIntersection(a, b, c, d) != null) {
      return 0.0;
    }
    return Math.min(
        Math.min(pointSegmentDistance(a, c, d), pointSegmentDistance(b, c, d)),
        Math.min(pointSegmentDistance(c, a, b), pointSegmentDistance(d, a, b)));
  }

  private static double pointSegmentDistance(Point point, Point start, Point end) {
    return distanceToSegment(point.x(), point.y(), start, end);
  }

  // Widens the through-node check to an edge's own endpoint node: unlike an unrelated node
  // (bounding-box overlap is enough), legitimate contact at the endpoint's perimeter is normal,
  // so only a route that actually crosses the inset interior counts. Segments are orthogonal in
  // practice (see routeSegment), so orientation is used to separate "sits inside the perpendicular
  // band" from "travels far enough along its own axis"; a non-orthogonal segment falls back to
  // requiring the minimum overlap on both axes.
  private static boolean segmentPiercesOwnEndpointInterior(
      Point start, Point end, LaidOutNode node) {
    double left = node.x() + OWN_ENDPOINT_INTERIOR_INSET;
    double right = node.x() + node.width() - OWN_ENDPOINT_INTERIOR_INSET;
    double top = node.y() + OWN_ENDPOINT_INTERIOR_INSET;
    double bottom = node.y() + node.height() - OWN_ENDPOINT_INTERIOR_INSET;
    if (right <= left || bottom <= top) {
      // The node is too small for an inset interior to exist at all.
      return false;
    }
    double minX = Math.min(start.x(), end.x());
    double maxX = Math.max(start.x(), end.x());
    double minY = Math.min(start.y(), end.y());
    double maxY = Math.max(start.y(), end.y());
    if (sameCoordinate(start.y(), end.y())) {
      double y = start.y();
      return y > top
          && y < bottom
          && Math.min(maxX, right) - Math.max(minX, left) >= OWN_ENDPOINT_MIN_OVERLAP;
    }
    if (sameCoordinate(start.x(), end.x())) {
      double x = start.x();
      return x > left
          && x < right
          && Math.min(maxY, bottom) - Math.max(minY, top) >= OWN_ENDPOINT_MIN_OVERLAP;
    }
    double overlapX = Math.min(maxX, right) - Math.max(minX, left);
    double overlapY = Math.min(maxY, bottom) - Math.max(minY, top);
    return overlapX >= OWN_ENDPOINT_MIN_OVERLAP && overlapY >= OWN_ENDPOINT_MIN_OVERLAP;
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
      List<Point> points = routePoints(edge);
      for (LaidOutGroup group : result.groups()) {
        boolean sourceInGroup = groupContainsNode(result, group, edge.source(), new HashSet<>());
        boolean targetInGroup = groupContainsNode(result, group, edge.target(), new HashSet<>());
        if (sourceInGroup && targetInGroup) {
          if (routeLeavesGroup(points, group)) {
            count++;
          }
          continue;
        }
        if (sourceInGroup || targetInGroup) {
          continue;
        }
        for (int index = 0; index + 1 < points.size(); index++) {
          if (segmentIntersectsRect(
              points.get(index),
              points.get(index + 1),
              group.x() + GROUP_BOUNDARY_INTERIOR_INSET,
              group.y() + GROUP_BOUNDARY_INTERIOR_INSET,
              group.width() - 2 * GROUP_BOUNDARY_INTERIOR_INSET,
              group.height() - 2 * GROUP_BOUNDARY_INTERIOR_INSET)) {
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
      for (LaidOutEdge edge : result.edges()) {
        List<Point> points = qualityRoutePoints(edge);
        for (int index = 0; index + 1 < points.size(); index++) {
          if (segmentIntersectsRect(
              points.get(index),
              points.get(index + 1),
              group.x(),
              group.y(),
              group.width(),
              GROUP_LABEL_BAND_HEIGHT)) {
            count++;
            break;
          }
        }
      }
    }
    return count;
  }

  private static boolean routeLeavesGroup(List<Point> points, LaidOutGroup group) {
    for (Point point : points) {
      if (!pointInRectangle(point, group.x(), group.y(), group.width(), group.height())) {
        return true;
      }
    }
    return false;
  }

  private static int countLabelSpaceIssues(LayoutResult result) {
    int count = 0;
    for (LaidOutNode node : result.nodes()) {
      if (node.label() == null
          || node.label().isBlank()
          || LayoutNodeRole.isJunction(node.role())
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
      List<Point> points = routePoints(edge);
      for (int i = 0; i + 1 < points.size(); i++) {
        RouteSegment segment =
            routeSegment(edgeIndex, edge.source(), edge.target(), points.get(i), points.get(i + 1));
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
      List<Point> points = routePoints(edge);
      for (int i = 0; i + 1 < points.size(); i++) {
        RouteSegment segment =
            routeSegment(edgeIndex, edge.source(), edge.target(), points.get(i), points.get(i + 1));
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

  private static boolean hasExcessiveDetour(LaidOutEdge edge, LayoutResult result) {
    List<Point> points = routePoints(edge);
    if (points.size() < 2) {
      return false;
    }
    if (directRouteIsBlocked(edge, points, result)) {
      return false;
    }
    double routeLength = routeLength(points);
    Point start = points.getFirst();
    Point end = points.getLast();
    double directLength = Math.abs(start.x() - end.x()) + Math.abs(start.y() - end.y());
    double detourRatio =
        isSimpleSideReturn(points) ? SIMPLE_SIDE_RETURN_DETOUR_RATIO : ROUTE_DETOUR_RATIO;
    return directLength > 0.0
        && routeLength > directLength * detourRatio
        && routeLength - directLength > ROUTE_DETOUR_EXCESS;
  }

  // This is a witness check, not a router: when the direct candidate crosses an unrelated node,
  // the measured route may be longer for a necessary reason and remains advisory-clean.
  private static boolean directRouteIsBlocked(
      LaidOutEdge edge, List<Point> points, LayoutResult result) {
    Point start = points.getFirst();
    Point end = points.getLast();
    for (LaidOutNode node : result.nodes()) {
      if (node.id().equals(edge.source())
          || node.id().equals(edge.target())
          || isSequenceChrome(node)) {
        continue;
      }
      if (segmentIntersectsRect(start, end, node.x(), node.y(), node.width(), node.height())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isSimpleSideReturn(List<Point> points) {
    if (points.size() != 4) {
      return false;
    }
    Point start = points.get(0);
    Point firstCorner = points.get(1);
    Point secondCorner = points.get(2);
    Point end = points.get(3);
    boolean verticalReturn =
        Math.abs(start.x() - end.x()) <= GEOMETRY_EPSILON
            && Math.abs(start.y() - firstCorner.y()) <= GEOMETRY_EPSILON
            && Math.abs(firstCorner.x() - secondCorner.x()) <= GEOMETRY_EPSILON
            && Math.abs(secondCorner.y() - end.y()) <= GEOMETRY_EPSILON;
    boolean horizontalReturn =
        Math.abs(start.y() - end.y()) <= GEOMETRY_EPSILON
            && Math.abs(start.x() - firstCorner.x()) <= GEOMETRY_EPSILON
            && Math.abs(firstCorner.y() - secondCorner.y()) <= GEOMETRY_EPSILON
            && Math.abs(secondCorner.x() - end.x()) <= GEOMETRY_EPSILON;
    return verticalReturn || horizontalReturn;
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
    if (pointInRectangle(start, rectX, rectY, rectWidth, rectHeight)
        || pointInRectangle(end, rectX, rectY, rectWidth, rectHeight)) {
      return true;
    }
    Point topLeft = new Point(rectX, rectY);
    Point topRight = new Point(rectX + rectWidth, rectY);
    Point bottomRight = new Point(rectX + rectWidth, rectY + rectHeight);
    Point bottomLeft = new Point(rectX, rectY + rectHeight);
    return segmentIntersection(start, end, topLeft, topRight) != null
        || segmentIntersection(start, end, topRight, bottomRight) != null
        || segmentIntersection(start, end, bottomRight, bottomLeft) != null
        || segmentIntersection(start, end, bottomLeft, topLeft) != null;
  }

  private static boolean pointInRectangle(
      Point point, double rectX, double rectY, double rectWidth, double rectHeight) {
    return point.x() >= rectX
        && point.x() <= rectX + rectWidth
        && point.y() >= rectY
        && point.y() <= rectY + rectHeight;
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

  private static int countRouteOverlaps(LayoutResult result) {
    int count = 0;
    for (int leftIndex = 0; leftIndex < result.edges().size(); leftIndex++) {
      LaidOutEdge left = result.edges().get(leftIndex);
      if (routeGeometryError(left, leftIndex) != null) {
        continue;
      }
      List<Point> leftPoints = qualityRoutePoints(left);
      for (int rightIndex = leftIndex + 1; rightIndex < result.edges().size(); rightIndex++) {
        LaidOutEdge right = result.edges().get(rightIndex);
        if (routeGeometryError(right, rightIndex) != null) {
          continue;
        }
        List<Point> rightPoints = qualityRoutePoints(right);
        List<Point> events = new ArrayList<>();
        for (int i = 0; i + 1 < leftPoints.size(); i++) {
          for (int j = 0; j + 1 < rightPoints.size(); j++) {
            Point event =
                collinearOverlapMidpoint(
                    leftPoints.get(i),
                    leftPoints.get(i + 1),
                    rightPoints.get(j),
                    rightPoints.get(j + 1));
            if (event != null
                && !isIntentionalTerminalOverlap(left, right, event, leftPoints, rightPoints)) {
              addDistinctEvent(events, event);
            }
          }
        }
        count += events.size();
      }
    }
    return count;
  }

  private static boolean isIntentionalTerminalOverlap(
      LaidOutEdge left,
      LaidOutEdge right,
      Point event,
      List<Point> leftPoints,
      List<Point> rightPoints) {
    return left.routingHints().contains("shared_source_junction")
            && right.routingHints().contains("shared_source_junction")
            && left.source().equals(right.source())
            && sharedPrefixContains(leftPoints, rightPoints, event)
        || left.routingHints().contains("shared_target_junction")
            && right.routingHints().contains("shared_target_junction")
            && left.target().equals(right.target())
            && sharedSuffixContains(leftPoints, rightPoints, event);
  }

  private static boolean sharedPrefixContains(List<Point> left, List<Point> right, Point event) {
    return sharedTerminalContains(left, right, event, false);
  }

  private static boolean sharedSuffixContains(List<Point> left, List<Point> right, Point event) {
    return sharedTerminalContains(left, right, event, true);
  }

  private static boolean sharedTerminalContains(
      List<Point> left, List<Point> right, Point event, boolean reverse) {
    int step = reverse ? -1 : 1;
    int leftIndex = reverse ? left.size() - 1 : 0;
    int rightIndex = reverse ? right.size() - 1 : 0;
    Point leftCurrent = left.get(leftIndex);
    Point rightCurrent = right.get(rightIndex);
    while (leftIndex + step >= 0
        && leftIndex + step < left.size()
        && rightIndex + step >= 0
        && rightIndex + step < right.size()) {
      if (!near(leftCurrent, rightCurrent)) {
        return false;
      }
      Point leftEnd = left.get(leftIndex + step);
      Point rightEnd = right.get(rightIndex + step);
      double leftDx = leftEnd.x() - leftCurrent.x();
      double leftDy = leftEnd.y() - leftCurrent.y();
      double rightDx = rightEnd.x() - rightCurrent.x();
      double rightDy = rightEnd.y() - rightCurrent.y();
      double leftLength = Math.hypot(leftDx, leftDy);
      double rightLength = Math.hypot(rightDx, rightDy);
      if (leftLength <= NUMERIC_GEOMETRY_EPSILON || rightLength <= NUMERIC_GEOMETRY_EPSILON) {
        return false;
      }
      if (Math.abs(crossVector(leftDx, leftDy, rightDx, rightDy))
              > orientationTolerance(leftCurrent, leftEnd, rightEnd)
          || leftDx * rightDx + leftDy * rightDy <= 0.0) {
        return false;
      }
      double sharedLength = Math.min(leftLength, rightLength);
      Point sharedEnd =
          new Point(
              leftCurrent.x() + leftDx * sharedLength / leftLength,
              leftCurrent.y() + leftDy * sharedLength / leftLength);
      if (pointOnSegment(event, leftCurrent, sharedEnd)) {
        return true;
      }
      if (leftLength <= rightLength + NUMERIC_GEOMETRY_EPSILON) {
        leftIndex += step;
        leftCurrent = leftEnd;
      } else {
        leftCurrent = sharedEnd;
      }
      if (rightLength <= leftLength + NUMERIC_GEOMETRY_EPSILON) {
        rightIndex += step;
        rightCurrent = rightEnd;
      } else {
        rightCurrent = sharedEnd;
      }
    }
    return false;
  }

  private static int countEdgeCrossings(LayoutResult result) {
    int count = 0;
    for (int leftIndex = 0; leftIndex < result.edges().size(); leftIndex++) {
      LaidOutEdge left = result.edges().get(leftIndex);
      if (routeGeometryError(left, leftIndex) != null) {
        continue;
      }
      for (int rightIndex = leftIndex + 1; rightIndex < result.edges().size(); rightIndex++) {
        LaidOutEdge right = result.edges().get(rightIndex);
        if (routeGeometryError(right, rightIndex) != null) {
          continue;
        }
        List<Point> events = crossingEvents(left, right);
        count += events.size();
      }
    }
    return count;
  }

  private static List<Point> crossingEvents(LaidOutEdge left, LaidOutEdge right) {
    List<Point> leftPoints = qualityRoutePoints(left);
    List<Point> rightPoints = qualityRoutePoints(right);
    List<Point> coarse = crossingEvents(leftPoints, rightPoints);
    if (left.route() instanceof PolylineRoute && right.route() instanceof PolylineRoute) {
      return coarse;
    }
    double error = QUALITY_FLATTENING_MAX_ERROR;
    List<Point> events = coarse;
    while (hasUnresolvedCrossingAmbiguity(leftPoints, rightPoints, events, error)
        && error > EVENT_DEDUPLICATION_TOLERANCE * 1.0e-6) {
      error /= 4.0;
      leftPoints = flattenAtError(left, error);
      rightPoints = flattenAtError(right, error);
      events = crossingEvents(leftPoints, rightPoints);
    }
    return events;
  }

  private static List<Point> flattenAtError(LaidOutEdge edge, double error) {
    try {
      return RouteGeometry.flatten(edge.route(), error);
    } catch (IllegalArgumentException exception) {
      return List.of();
    }
  }

  private static boolean hasUnresolvedCrossingAmbiguity(
      List<Point> left, List<Point> right, List<Point> events, double error) {
    for (int i = 0; i + 1 < left.size(); i++) {
      for (int j = 0; j + 1 < right.size(); j++) {
        Point start = left.get(i);
        Point end = left.get(i + 1);
        Point otherStart = right.get(j);
        Point otherEnd = right.get(j + 1);
        if (!segmentBoundsWithin(start, end, otherStart, otherEnd, 2.0 * error)
            || segmentDistance(start, end, otherStart, otherEnd) > 2.0 * error) {
          continue;
        }
        if (events.stream()
            .noneMatch(
                event ->
                    pointOnSegment(event, start, end)
                        && pointOnSegment(event, otherStart, otherEnd))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean segmentBoundsWithin(Point a, Point b, Point c, Point d, double distance) {
    return Math.max(Math.min(a.x(), b.x()), Math.min(c.x(), d.x()))
            <= Math.min(Math.max(a.x(), b.x()), Math.max(c.x(), d.x())) + distance
        && Math.max(Math.min(a.y(), b.y()), Math.min(c.y(), d.y()))
            <= Math.min(Math.max(a.y(), b.y()), Math.max(c.y(), d.y())) + distance;
  }

  private static List<Point> crossingEvents(List<Point> left, List<Point> right) {
    List<Point> events = new ArrayList<>();
    for (int i = 0; i + 1 < left.size(); i++) {
      for (int j = 0; j + 1 < right.size(); j++) {
        Point event =
            properSegmentIntersection(left.get(i), left.get(i + 1), right.get(j), right.get(j + 1));
        if (event != null && !isGlobalEndpoint(event, left) && !isGlobalEndpoint(event, right)) {
          addDistinctEvent(events, event);
        }
      }
    }
    for (int i = 1; i + 1 < left.size(); i++) {
      if (vertexCrossesRoute(left, i, right) && !isGlobalEndpoint(left.get(i), right)) {
        addDistinctEvent(events, left.get(i));
      }
    }
    for (int i = 1; i + 1 < right.size(); i++) {
      if (vertexCrossesRoute(right, i, left) && !isGlobalEndpoint(right.get(i), left)) {
        addDistinctEvent(events, right.get(i));
      }
    }
    return events;
  }

  private static boolean vertexCrossesRoute(List<Point> route, int vertexIndex, List<Point> other) {
    Point vertex = route.get(vertexIndex);
    for (int segment = 0; segment + 1 < other.size(); segment++) {
      Point start = other.get(segment);
      Point end = other.get(segment + 1);
      if (!pointOnSegment(vertex, start, end)) {
        continue;
      }
      double before = cross(start, end, route.get(vertexIndex - 1));
      double after = cross(start, end, route.get(vertexIndex + 1));
      if (opposite(
          before,
          after,
          orientationTolerance(
              start, end, route.get(vertexIndex - 1), route.get(vertexIndex + 1)))) {
        return true;
      }
    }
    return false;
  }

  private static Point properSegmentIntersection(Point a, Point b, Point c, Point d) {
    double denominator = crossVector(b.x() - a.x(), b.y() - a.y(), d.x() - c.x(), d.y() - c.y());
    if (Math.abs(denominator) <= orientationTolerance(a, b, c, d)) {
      return null;
    }
    double t =
        crossVector(c.x() - a.x(), c.y() - a.y(), d.x() - c.x(), d.y() - c.y()) / denominator;
    double u =
        crossVector(c.x() - a.x(), c.y() - a.y(), b.x() - a.x(), b.y() - a.y()) / denominator;
    double tolerance =
        coordinateTolerance(a, b, c, d) / Math.max(1.0, Math.hypot(b.x() - a.x(), b.y() - a.y()));
    if (t <= tolerance || t >= 1.0 - tolerance || u <= tolerance || u >= 1.0 - tolerance) {
      return null;
    }
    return new Point(a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y()));
  }

  private static Point segmentIntersection(Point a, Point b, Point c, Point d) {
    Point proper = properSegmentIntersection(a, b, c, d);
    if (proper != null) {
      return proper;
    }
    for (Point candidate : List.of(a, b, c, d)) {
      if (pointOnSegment(candidate, a, b) && pointOnSegment(candidate, c, d)) {
        return candidate;
      }
    }
    return null;
  }

  private static Point collinearOverlapMidpoint(Point a, Point b, Point c, Point d) {
    double tolerance = orientationTolerance(a, b, c, d);
    if (Math.abs(cross(a, b, c)) > tolerance || Math.abs(cross(a, b, d)) > tolerance) {
      return null;
    }
    boolean xAxis = Math.abs(b.x() - a.x()) >= Math.abs(b.y() - a.y());
    double a0 = xAxis ? a.x() : a.y();
    double a1 = xAxis ? b.x() : b.y();
    double c0 = xAxis ? c.x() : c.y();
    double c1 = xAxis ? d.x() : d.y();
    double start = Math.max(Math.min(a0, a1), Math.min(c0, c1));
    double end = Math.min(Math.max(a0, a1), Math.max(c0, c1));
    if (end - start <= EVENT_DEDUPLICATION_TOLERANCE) {
      return null;
    }
    double factor = (start + end) / 2.0 - a0;
    double length = xAxis ? b.x() - a.x() : b.y() - a.y();
    double t = factor / length;
    return new Point(a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y()));
  }

  private static void addDistinctEvent(List<Point> events, Point candidate) {
    if (events.stream().noneMatch(existing -> near(existing, candidate))) {
      events.add(candidate);
    }
  }

  private static boolean isGlobalEndpoint(Point candidate, List<Point> points) {
    return !points.isEmpty()
        && (near(candidate, points.getFirst()) || near(candidate, points.getLast()));
  }

  private static boolean near(Point left, Point right) {
    return Math.hypot(left.x() - right.x(), left.y() - right.y()) <= EVENT_DEDUPLICATION_TOLERANCE;
  }

  private static boolean pointOnSegment(Point point, Point start, Point end) {
    double tolerance = orientationTolerance(point, start, end);
    return Math.abs(cross(start, end, point)) <= tolerance
        && point.x() >= Math.min(start.x(), end.x()) - coordinateTolerance(point, start, end)
        && point.x() <= Math.max(start.x(), end.x()) + coordinateTolerance(point, start, end)
        && point.y() >= Math.min(start.y(), end.y()) - coordinateTolerance(point, start, end)
        && point.y() <= Math.max(start.y(), end.y()) + coordinateTolerance(point, start, end);
  }

  private static boolean opposite(double left, double right, double tolerance) {
    return left < -tolerance && right > tolerance || left > tolerance && right < -tolerance;
  }

  private static double orientationTolerance(Point... points) {
    double minX = points[0].x();
    double minY = points[0].y();
    double maxX = minX;
    double maxY = minY;
    for (Point point : points) {
      minX = Math.min(minX, point.x());
      minY = Math.min(minY, point.y());
      maxX = Math.max(maxX, point.x());
      maxY = Math.max(maxY, point.y());
    }
    double scale = Math.max(1.0, Math.max(maxX - minX, maxY - minY));
    return NUMERIC_GEOMETRY_EPSILON * scale * scale;
  }

  private static double coordinateTolerance(Point... points) {
    double minX = points[0].x();
    double minY = points[0].y();
    double maxX = minX;
    double maxY = minY;
    for (Point point : points) {
      minX = Math.min(minX, point.x());
      minY = Math.min(minY, point.y());
      maxX = Math.max(maxX, point.x());
      maxY = Math.max(maxY, point.y());
    }
    return NUMERIC_GEOMETRY_EPSILON * Math.max(1.0, Math.max(maxX - minX, maxY - minY));
  }

  private static double cross(Point a, Point b, Point c) {
    return crossVector(b.x() - a.x(), b.y() - a.y(), c.x() - a.x(), c.y() - a.y());
  }

  private static double crossVector(double ax, double ay, double bx, double by) {
    return ax * by - ay * bx;
  }

  private static boolean edgesShareEndpointNode(LaidOutEdge left, LaidOutEdge right) {
    return left.source().equals(right.source())
        || left.source().equals(right.target())
        || left.target().equals(right.source())
        || left.target().equals(right.target());
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

  private record ClearanceSegment(Point start, Point end) {}

  private record LabeledEdgeRuns(LaidOutEdge edge, List<RouteSegment> segments) {}
}

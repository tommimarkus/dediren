package dev.dediren.ir.quality;

import dev.dediren.contracts.layout.Point;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.PlacedNode;
import dev.dediren.ir.RoutedEdge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Named geometric invariants over a UML-sequence {@link LaidOutScene}. Each method returns the
 * (possibly empty) list of {@link InvariantViolation}s: empty means the invariant holds. These
 * checks are pure geometry over the typed IR; they do not map to {@code Diagnostic}s or wire into
 * {@code core}'s layout-quality reporting (that live wiring is a later task).
 *
 * <p>A "message" is a {@link RoutedEdge} whose {@code source} and {@code target} both resolve (by
 * id) to {@code role=="lifeline"} {@link PlacedNode}s.
 */
public final class SequenceInvariants {

  // Sequence message endpoints anchor to the lifeline axis (the participant head-box centre-x, per
  // the render engine's stem/message-endpoint convention). core's perimeter check accepts the same
  // slack, so the value is declared once in LayoutTolerances and consumed by both.
  private static final double LIFELINE_AXIS_TOLERANCE = LayoutTolerances.ROUTE_ENDPOINT_TOLERANCE;

  private SequenceInvariants() {}

  /**
   * Each message edge's first route point must sit on its source lifeline's center-x, and its last
   * route point on its target lifeline's center-x, within {@link #LIFELINE_AXIS_TOLERANCE}.
   */
  public static List<InvariantViolation> messageEndpointsOnLifelineAxis(LaidOutScene scene) {
    List<InvariantViolation> violations = new ArrayList<>();
    Map<String, PlacedNode> nodesById = indexNodesById(scene);
    for (RoutedEdge edge : scene.edges()) {
      PlacedNode source = nodesById.get(edge.source());
      PlacedNode target = nodesById.get(edge.target());
      if (!isLifeline(source) || !isLifeline(target) || edge.points().isEmpty()) {
        continue;
      }
      Point first = edge.points().getFirst();
      Point last = edge.points().getLast();
      double sourceCenterX = centerX(source);
      double targetCenterX = centerX(target);
      if (!withinTolerance(first.x(), sourceCenterX, LIFELINE_AXIS_TOLERANCE)) {
        violations.add(
            new InvariantViolation(
                "message_endpoints_on_lifeline_axis",
                edge.id(),
                edge.origin(),
                "first route point x="
                    + first.x()
                    + " is not within "
                    + LIFELINE_AXIS_TOLERANCE
                    + " of source lifeline '"
                    + source.id()
                    + "' center-x="
                    + sourceCenterX));
      }
      if (!withinTolerance(last.x(), targetCenterX, LIFELINE_AXIS_TOLERANCE)) {
        violations.add(
            new InvariantViolation(
                "message_endpoints_on_lifeline_axis",
                edge.id(),
                edge.origin(),
                "last route point x="
                    + last.x()
                    + " is not within "
                    + LIFELINE_AXIS_TOLERANCE
                    + " of target lifeline '"
                    + target.id()
                    + "' center-x="
                    + targetCenterX));
      }
    }
    return List.copyOf(violations);
  }

  /**
   * Taking message edges in scene list order, each message's representative y (its first route
   * point's y) must be strictly greater than the previous message's, so the rendered sequence reads
   * top-to-bottom without messages overlapping or reordering.
   */
  public static List<InvariantViolation> messageYStrictlyIncreasing(LaidOutScene scene) {
    List<InvariantViolation> violations = new ArrayList<>();
    Map<String, PlacedNode> nodesById = indexNodesById(scene);
    Double previousY = null;
    String previousId = null;
    for (RoutedEdge edge : scene.edges()) {
      if (!isMessage(edge, nodesById) || edge.points().isEmpty()) {
        continue;
      }
      double y = edge.points().getFirst().y();
      if (previousY != null && y <= previousY) {
        violations.add(
            new InvariantViolation(
                "message_y_strictly_increasing",
                edge.id(),
                edge.origin(),
                "message y="
                    + y
                    + " is not strictly greater than previous message '"
                    + previousId
                    + "' y="
                    + previousY));
      }
      previousY = y;
      previousId = edge.id();
    }
    return List.copyOf(violations);
  }

  /**
   * If a {@code role=="interaction"} node (the frame) exists, its rect must contain every lifeline
   * node's rect and every message edge's route points. Only the first such frame node found is
   * checked; a scene without one trivially holds.
   */
  public static List<InvariantViolation> interactionFrameEnclosesLifelines(LaidOutScene scene) {
    PlacedNode frame =
        scene.nodes().stream()
            .filter(node -> "interaction".equals(node.role()))
            .findFirst()
            .orElse(null);
    if (frame == null) {
      return List.of();
    }
    List<InvariantViolation> violations = new ArrayList<>();
    double frameLeft = frame.x();
    double frameRight = frame.x() + frame.width();
    double frameTop = frame.y();
    double frameBottom = frame.y() + frame.height();

    for (PlacedNode node : scene.nodes()) {
      if (!isLifeline(node)) {
        continue;
      }
      double nodeRight = node.x() + node.width();
      double nodeBottom = node.y() + node.height();
      if (node.x() < frameLeft
          || nodeRight > frameRight
          || node.y() < frameTop
          || nodeBottom > frameBottom) {
        violations.add(
            new InvariantViolation(
                "interaction_frame_encloses_lifelines",
                node.id(),
                node.origin(),
                "lifeline rect ["
                    + node.x()
                    + ","
                    + node.y()
                    + ",w="
                    + node.width()
                    + ",h="
                    + node.height()
                    + "] is not contained within interaction frame '"
                    + frame.id()
                    + "'"));
      }
    }

    Map<String, PlacedNode> nodesById = indexNodesById(scene);
    for (RoutedEdge edge : scene.edges()) {
      if (!isMessage(edge, nodesById)) {
        continue;
      }
      for (Point point : edge.points()) {
        if (point.x() < frameLeft
            || point.x() > frameRight
            || point.y() < frameTop
            || point.y() > frameBottom) {
          violations.add(
              new InvariantViolation(
                  "interaction_frame_encloses_lifelines",
                  edge.id(),
                  edge.origin(),
                  "message point ("
                      + point.x()
                      + ","
                      + point.y()
                      + ") is outside interaction frame '"
                      + frame.id()
                      + "'"));
          break;
        }
      }
    }
    return List.copyOf(violations);
  }

  private static Map<String, PlacedNode> indexNodesById(LaidOutScene scene) {
    Map<String, PlacedNode> nodesById = new HashMap<>();
    for (PlacedNode node : scene.nodes()) {
      nodesById.putIfAbsent(node.id(), node);
    }
    return nodesById;
  }

  private static boolean isLifeline(PlacedNode node) {
    return node != null && "lifeline".equals(node.role());
  }

  private static boolean isMessage(RoutedEdge edge, Map<String, PlacedNode> nodesById) {
    return isLifeline(nodesById.get(edge.source())) && isLifeline(nodesById.get(edge.target()));
  }

  private static double centerX(PlacedNode node) {
    return node.x() + node.width() / 2.0;
  }

  private static boolean withinTolerance(double left, double right, double tolerance) {
    return Math.abs(left - right) <= tolerance;
  }
}

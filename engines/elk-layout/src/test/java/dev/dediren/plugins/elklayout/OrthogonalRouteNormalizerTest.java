package dev.dediren.plugins.elklayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.Point;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrthogonalRouteNormalizerTest {
  // A route segment counts as "riding" a node's own face once it lies within this distance of the
  // face AND overlaps it by at least this much. Matches the thresholds named in the task that
  // motivated this test: an 8px proximity band and a 2px minimum overlap, chosen to catch a visibly
  // doubled border without flagging sub-pixel endpoint contact.
  private static final double FACE_RIDE_PROXIMITY = 8.0;
  private static final double FACE_RIDE_MIN_OVERLAP = 2.0;

  /**
   * Regression for the "doubled border" defect: {@code collapseStairSteps} emitted a pivot leg that
   * lies ~1px off a node's OWN face for tens of pixels, because the 6-point {@code
   * startsAtSourceEndpoint} pivot in {@code stairReplacements} takes {@code pivot = start.x()} (or
   * {@code start.y()}) where {@code start} IS the port -- 1px outside the source's face by
   * construction, with no span guard at all. This asserts the normalizer never returns a route
   * whose segment overlaps its own source or target node's face within the proximity/overlap
   * thresholds above.
   */
  @Test
  void doesNotRideItsOwnSourceFaceViaTheStairPivot() {
    // Source spans x:[-100,0], y:[-20,20]; its east face sits at x=0. The route below starts 1px
    // outside that face (the port) and is a 6-point staircase whose startsAtSourceEndpoint pivot
    // collapses to a vertical leg running along x=0 for 26px -- squarely inside both thresholds.
    LaidOutNode source =
        new LaidOutNode("source", "source", "source", -100, -20, 100, 40, "Source");
    List<Point> staircase =
        List.of(
            new Point(0, 4),
            new Point(10, 4),
            new Point(10, 50),
            new Point(90, 50),
            new Point(90, 30),
            new Point(100, 30));

    List<Point> normalized =
        OrthogonalRouteNormalizer.collapseStairSteps(
            staircase, List.of(source), "source", "target");

    assertTrue(
        clearOfOwnFaceRide(normalized, source),
        () ->
            "normalized route rides its own source's face: "
                + normalized
                + "; source="
                + describe(source));
  }

  private static boolean clearOfOwnFaceRide(List<Point> route, LaidOutNode node) {
    for (int index = 0; index < route.size() - 1; index++) {
      if (ownFaceOverlap(route.get(index), route.get(index + 1), node) >= FACE_RIDE_MIN_OVERLAP) {
        return false;
      }
    }
    return true;
  }

  /**
   * Overlap length (in px) of an axis-aligned segment with one of the node's four faces, counted
   * only when the segment lies within {@link #FACE_RIDE_PROXIMITY} of that face's line.
   */
  private static double ownFaceOverlap(Point start, Point end, LaidOutNode node) {
    double left = node.x();
    double right = node.x() + node.width();
    double top = node.y();
    double bottom = node.y() + node.height();
    if (same(start.y(), end.y())
        && (Math.abs(start.y() - top) <= FACE_RIDE_PROXIMITY
            || Math.abs(start.y() - bottom) <= FACE_RIDE_PROXIMITY)) {
      return overlapLength(start.x(), end.x(), left, right);
    }
    if (same(start.x(), end.x())
        && (Math.abs(start.x() - left) <= FACE_RIDE_PROXIMITY
            || Math.abs(start.x() - right) <= FACE_RIDE_PROXIMITY)) {
      return overlapLength(start.y(), end.y(), top, bottom);
    }
    return 0.0;
  }

  private static double overlapLength(
      double firstStart, double firstEnd, double secondStart, double secondEnd) {
    double firstMin = Math.min(firstStart, firstEnd);
    double firstMax = Math.max(firstStart, firstEnd);
    return Math.max(0.0, Math.min(firstMax, secondEnd) - Math.max(firstMin, secondStart));
  }

  private static boolean same(double left, double right) {
    return Math.abs(left - right) <= 0.001;
  }

  private static String describe(LaidOutNode node) {
    return "[" + node.x() + "," + node.y() + " " + node.width() + "x" + node.height() + "]";
  }

  @Test
  void declinesADoglegCollapseThatWouldRideTheSourceBoundary() {
    // The naive collapse of this dogleg -- (0,0),(0,4),(100,4) -- would run its middle segment
    // along x=0, exactly the source's east face, for its whole 4px length: a face ride. The guard
    // added alongside doesNotRideItsOwnSourceFaceViaTheStairPivot above rejects it, so the route is
    // left as ELK produced it.
    List<Point> dogleg =
        List.of(new Point(0, 0), new Point(10, 0), new Point(10, 4), new Point(100, 4));
    LaidOutNode source =
        new LaidOutNode("source", "source", "source", -100, -20, 100, 40, "Source");

    List<Point> normalized =
        OrthogonalRouteNormalizer.collapseStairSteps(dogleg, List.of(source), "source", "target");

    assertEquals(dogleg, normalized);
  }

  @Test
  void prefersTheFewestTurnRouteFromTheSourceBoundary() {
    List<Point> staircase =
        List.of(
            new Point(0, 0),
            new Point(10, 0),
            new Point(10, 5),
            new Point(90, 5),
            new Point(90, 4),
            new Point(100, 4));

    List<Point> normalized =
        OrthogonalRouteNormalizer.collapseStairSteps(staircase, List.of(), "source", "target");

    assertEquals(List.of(new Point(0, 0), new Point(0, 4), new Point(100, 4)), normalized);
  }

  @Test
  void preservesTheSourceTrunkWhenTheEndpointIsMerged() {
    List<Point> staircase =
        List.of(
            new Point(0, 0),
            new Point(10, 0),
            new Point(10, 5),
            new Point(90, 5),
            new Point(90, 4),
            new Point(100, 4));

    List<Point> normalized =
        OrthogonalRouteNormalizer.collapseStairSteps(
            staircase, List.of(), "source", "target", false);

    assertEquals(
        List.of(new Point(0, 0), new Point(10, 0), new Point(10, 4), new Point(100, 4)),
        normalized);
  }

  @Test
  void doesNotExtendAnInternalHierarchyChannelBackToTheSourceBoundary() {
    List<Point> staircase =
        List.of(
            new Point(0, 100),
            new Point(20, 100),
            new Point(20, 30),
            new Point(40, 30),
            new Point(40, 25),
            new Point(60, 25),
            new Point(60, 20),
            new Point(80, 20));

    List<Point> normalized =
        OrthogonalRouteNormalizer.collapseStairSteps(staircase, List.of(), "source", "target");

    assertEquals(
        List.of(new Point(0, 100), new Point(20, 100), new Point(20, 20), new Point(80, 20)),
        normalized);
  }

  @Test
  void collapsesTheEquivalentVerticalStaircase() {
    List<Point> staircase =
        List.of(
            new Point(0, 0),
            new Point(0, 10),
            new Point(20, 10),
            new Point(20, 90),
            new Point(40, 90),
            new Point(40, 100));

    List<Point> normalized =
        OrthogonalRouteNormalizer.collapseStairSteps(staircase, List.of(), "source", "target");

    assertEquals(List.of(new Point(0, 0), new Point(40, 0), new Point(40, 100)), normalized);
  }

  @Test
  void usesTheOtherExistingChannelWhenTheFirstCollapsedRouteWouldCrossANode() {
    List<Point> staircase =
        List.of(
            new Point(0, 0),
            new Point(10, 0),
            new Point(10, 20),
            new Point(90, 20),
            new Point(90, 40),
            new Point(100, 40));
    List<LaidOutNode> obstacles =
        List.of(
            new LaidOutNode("near", "near", "near", 5, 25, 10, 10, "Near"),
            new LaidOutNode("channel", "channel", "channel", 45, 35, 10, 10, "Channel"));

    List<Point> normalized =
        OrthogonalRouteNormalizer.collapseStairSteps(staircase, obstacles, "source", "target");

    assertEquals(
        List.of(new Point(0, 0), new Point(90, 0), new Point(90, 40), new Point(100, 40)),
        normalized);
  }

  @Test
  void preservesTheElkRouteWhenNeitherCollapsedChannelIsClear() {
    List<Point> staircase =
        List.of(
            new Point(0, 0),
            new Point(10, 0),
            new Point(10, 20),
            new Point(90, 20),
            new Point(90, 40),
            new Point(100, 40));
    List<LaidOutNode> obstacles =
        List.of(
            new LaidOutNode("left", "left", "left", 5, 25, 10, 10, "Left"),
            new LaidOutNode("middle", "middle", "middle", 45, 35, 10, 10, "Middle"),
            new LaidOutNode("right", "right", "right", 85, 5, 10, 10, "Right"));

    List<Point> normalized =
        OrthogonalRouteNormalizer.collapseStairSteps(staircase, obstacles, "source", "target");

    assertEquals(staircase, normalized);
  }

  @Test
  void preservesNonOrthogonalPointsOutsideTheCollapsedStaircase() {
    List<Point> route =
        List.of(
            new Point(0, 0),
            new Point(10, 0),
            new Point(10, 20),
            new Point(90, 20),
            new Point(90, 40),
            new Point(100, 40),
            new Point(110, 50),
            new Point(120, 40));

    List<Point> normalized =
        OrthogonalRouteNormalizer.collapseStairSteps(route, List.of(), "source", "target");

    assertEquals(
        List.of(
            new Point(0, 0),
            new Point(10, 0),
            new Point(10, 40),
            new Point(100, 40),
            new Point(110, 50),
            new Point(120, 40)),
        normalized);
  }

  @Test
  void preservesAReversingLegAdjacentToTheCollapsedStaircase() {
    List<Point> route =
        List.of(
            new Point(20, 0),
            new Point(10, 0),
            new Point(30, 0),
            new Point(30, 20),
            new Point(90, 20),
            new Point(90, 40),
            new Point(100, 40));

    List<Point> normalized =
        OrthogonalRouteNormalizer.collapseStairSteps(route, List.of(), "source", "target");

    assertEquals(
        List.of(
            new Point(20, 0),
            new Point(10, 0),
            new Point(30, 0),
            new Point(30, 40),
            new Point(100, 40)),
        normalized);
  }
}

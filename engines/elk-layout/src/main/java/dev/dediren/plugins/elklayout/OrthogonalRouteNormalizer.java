package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.Point;
import java.util.ArrayList;
import java.util.List;

/** Removes redundant staircase turns introduced when ELK joins compound-edge route sections. */
final class OrthogonalRouteNormalizer {
  private static final double EPSILON = 0.001;

  // A source-boundary pivot replacement is rejected as "riding its own source's face" once it has
  // a segment within this proximity of, and overlapping by at least FACE_RIDE_MIN_OVERLAP, that
  // node's outline. Measured across this module's own test suite (100-case seeded fuzz sweep plus
  // the hand-written engine scenarios): of 60 total source-boundary-pivot firings (12 from the
  // 4-point dogleg replacement, 48 from the 6-point stairReplacements startsAtSourceEndpoint
  // pivot), 57 rode the source's own face and only 3 did not, so this is a guard, not a deletion --
  // the "ANY clean firings" branch of the pre-approved decision. The dogleg replacement's own
  // admission condition (adjustedCoordinate inside the source's span) rides by construction on
  // every one of its 12 firings, so this guard also suppresses it in practice without deleting the
  // rule outright.
  private static final double FACE_RIDE_PROXIMITY = 8.0;
  private static final double FACE_RIDE_MIN_OVERLAP = 2.0;

  private OrthogonalRouteNormalizer() {}

  static List<Point> collapseStairSteps(
      List<Point> input, List<LaidOutNode> nodes, String sourceId, String targetId) {
    return collapseStairSteps(input, nodes, sourceId, targetId, true);
  }

  static List<Point> collapseStairSteps(
      List<Point> input,
      List<LaidOutNode> nodes,
      String sourceId,
      String targetId,
      boolean allowSourceBoundaryPivot) {
    List<Point> route = new ArrayList<>(input);
    boolean allowWholeRouteSourcePivot = allowSourceBoundaryPivot && input.size() == 6;
    if (allowSourceBoundaryPivot && route.size() == 4) {
      List<Point> replacement = sourceBoundaryDoglegReplacement(route, nodes, sourceId);
      if (!replacement.isEmpty()
          && routeLength(replacement) <= routeLength(route) + EPSILON
          && clearOfUnrelatedNodes(replacement, nodes, sourceId, targetId)
          && !ridesOwnFace(replacement, nodes, sourceId)) {
        route = new ArrayList<>(replacement);
      }
    }
    boolean changed;
    do {
      changed = false;
      for (int index = 0; index <= route.size() - 6; index++) {
        boolean sourcePivotEligible = index == 0 && allowWholeRouteSourcePivot;
        List<List<Point>> candidates =
            stairReplacements(route.subList(index, index + 6), sourcePivotEligible);
        for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
          List<Point> replacement = candidates.get(candidateIndex);
          // The source-pivot candidate is always candidates.get(0) when sourcePivotEligible (see
          // stairReplacements): only that one can ride the source's own face by construction, so
          // the guard is scoped to it rather than every candidate.
          boolean isSourcePivotCandidate = sourcePivotEligible && candidateIndex == 0;
          if (routeLength(replacement) > routeLength(route.subList(index, index + 6)) + EPSILON
              || !clearOfUnrelatedNodes(replacement, nodes, sourceId, targetId)
              || (isSourcePivotCandidate && ridesOwnFace(replacement, nodes, sourceId))) {
            continue;
          }
          List<Point> collapsed = new ArrayList<>(route.size() - 2);
          collapsed.addAll(route.subList(0, index));
          collapsed.addAll(replacement);
          collapsed.addAll(route.subList(index + 6, route.size()));
          route = compact(collapsed);
          changed = true;
          break;
        }
        if (changed) {
          break;
        }
      }
    } while (changed);
    return List.copyOf(route);
  }

  /**
   * True when the replacement route has a segment that overlaps its own source node's outline by at
   * least {@link #FACE_RIDE_MIN_OVERLAP} within {@link #FACE_RIDE_PROXIMITY} of that face -- the
   * "doubled border" defect both source-boundary pivots can produce.
   */
  private static boolean ridesOwnFace(List<Point> route, List<LaidOutNode> nodes, String sourceId) {
    LaidOutNode source = findNode(nodes, sourceId);
    if (source == null) {
      return false;
    }
    for (int index = 0; index < route.size() - 1; index++) {
      if (ownFaceOverlap(route.get(index), route.get(index + 1), source) >= FACE_RIDE_MIN_OVERLAP) {
        return true;
      }
    }
    return false;
  }

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

  private static LaidOutNode findNode(List<LaidOutNode> nodes, String id) {
    for (LaidOutNode node : nodes) {
      if (node.id().equals(id)) {
        return node;
      }
    }
    return null;
  }

  private static List<Point> sourceBoundaryDoglegReplacement(
      List<Point> points, List<LaidOutNode> nodes, String sourceId) {
    Orientation first = orientation(points.get(0), points.get(1));
    Orientation cross = orientation(points.get(1), points.get(2));
    Orientation third = orientation(points.get(2), points.get(3));
    if (first == null || cross == null || first == cross || first != third) {
      return List.of();
    }

    LaidOutNode source = findNode(nodes, sourceId);
    if (source == null) {
      return List.of();
    }

    Point start = points.get(0);
    Point end = points.get(3);
    double adjustedCoordinate = first == Orientation.HORIZONTAL ? end.y() : end.x();
    double sourceMinimum = first == Orientation.HORIZONTAL ? source.y() : source.x();
    double sourceMaximum =
        first == Orientation.HORIZONTAL
            ? source.y() + source.height()
            : source.x() + source.width();
    if (adjustedCoordinate < sourceMinimum - EPSILON
        || adjustedCoordinate > sourceMaximum + EPSILON) {
      return List.of();
    }

    return first == Orientation.HORIZONTAL
        ? compact(List.of(start, new Point(start.x(), end.y()), end))
        : compact(List.of(start, new Point(end.x(), start.y()), end));
  }

  private static List<List<Point>> stairReplacements(
      List<Point> points, boolean startsAtSourceEndpoint) {
    Orientation first = orientation(points.get(0), points.get(1));
    Orientation second = orientation(points.get(1), points.get(2));
    if (first == null
        || second == null
        || first == second
        || first != orientation(points.get(2), points.get(3))
        || second != orientation(points.get(3), points.get(4))
        || first != orientation(points.get(4), points.get(5))) {
      return List.of();
    }

    Point start = points.get(0);
    Point end = points.get(5);
    List<Double> pivots = new ArrayList<>();
    if (startsAtSourceEndpoint) {
      // This is the only equivalent route with one visible corner rather than two: the small
      // cross-axis adjustment stays at the source boundary, while ELK's exact endpoints and the
      // target approach direction remain unchanged.
      pivots.add(first == Orientation.HORIZONTAL ? start.x() : start.y());
    }
    pivots.add(first == Orientation.HORIZONTAL ? points.get(1).x() : points.get(1).y());
    pivots.add(first == Orientation.HORIZONTAL ? points.get(4).x() : points.get(4).y());
    List<List<Point>> candidates = new ArrayList<>();
    for (double pivot : pivots) {
      List<Point> candidate =
          first == Orientation.HORIZONTAL
              ? compact(List.of(start, new Point(pivot, start.y()), new Point(pivot, end.y()), end))
              : compact(
                  List.of(start, new Point(start.x(), pivot), new Point(end.x(), pivot), end));
      if (candidate.size() >= 2) {
        candidates.add(candidate);
      }
    }
    return candidates;
  }

  private static boolean clearOfUnrelatedNodes(
      List<Point> route, List<LaidOutNode> nodes, String sourceId, String targetId) {
    for (LaidOutNode node : nodes) {
      if (node.id().equals(sourceId) || node.id().equals(targetId)) {
        continue;
      }
      for (int index = 0; index < route.size() - 1; index++) {
        if (intersectsInterior(route.get(index), route.get(index + 1), node)) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean intersectsInterior(Point start, Point end, LaidOutNode node) {
    double left = node.x();
    double right = node.x() + node.width();
    double top = node.y();
    double bottom = node.y() + node.height();
    if (same(start.y(), end.y())) {
      double y = start.y();
      double segmentLeft = Math.min(start.x(), end.x());
      double segmentRight = Math.max(start.x(), end.x());
      return y > top + EPSILON
          && y < bottom - EPSILON
          && segmentRight > left + EPSILON
          && segmentLeft < right - EPSILON;
    }
    if (same(start.x(), end.x())) {
      double x = start.x();
      double segmentTop = Math.min(start.y(), end.y());
      double segmentBottom = Math.max(start.y(), end.y());
      return x > left + EPSILON
          && x < right - EPSILON
          && segmentBottom > top + EPSILON
          && segmentTop < bottom - EPSILON;
    }
    return true;
  }

  private static List<Point> compact(List<Point> points) {
    List<Point> compacted = new ArrayList<>();
    for (Point point : points) {
      if (!compacted.isEmpty() && samePoint(compacted.get(compacted.size() - 1), point)) {
        continue;
      }
      Orientation incoming =
          compacted.size() < 2
              ? null
              : orientation(
                  compacted.get(compacted.size() - 2), compacted.get(compacted.size() - 1));
      while (incoming != null
          && incoming == orientation(compacted.get(compacted.size() - 1), point)
          && liesBetween(
              compacted.get(compacted.size() - 1),
              compacted.get(compacted.size() - 2),
              point,
              incoming)) {
        compacted.remove(compacted.size() - 1);
        incoming =
            compacted.size() < 2
                ? null
                : orientation(
                    compacted.get(compacted.size() - 2), compacted.get(compacted.size() - 1));
      }
      compacted.add(point);
    }
    return compacted;
  }

  private static boolean liesBetween(
      Point middle, Point first, Point last, Orientation orientation) {
    double middleCoordinate = orientation == Orientation.HORIZONTAL ? middle.x() : middle.y();
    double firstCoordinate = orientation == Orientation.HORIZONTAL ? first.x() : first.y();
    double lastCoordinate = orientation == Orientation.HORIZONTAL ? last.x() : last.y();
    return middleCoordinate >= Math.min(firstCoordinate, lastCoordinate) - EPSILON
        && middleCoordinate <= Math.max(firstCoordinate, lastCoordinate) + EPSILON;
  }

  private static double routeLength(List<Point> points) {
    double length = 0.0;
    for (int index = 0; index < points.size() - 1; index++) {
      Point start = points.get(index);
      Point end = points.get(index + 1);
      length += Math.abs(start.x() - end.x()) + Math.abs(start.y() - end.y());
    }
    return length;
  }

  private static Orientation orientation(Point start, Point end) {
    if (same(start.y(), end.y()) && !same(start.x(), end.x())) {
      return Orientation.HORIZONTAL;
    }
    if (same(start.x(), end.x()) && !same(start.y(), end.y())) {
      return Orientation.VERTICAL;
    }
    return null;
  }

  private static boolean samePoint(Point left, Point right) {
    return same(left.x(), right.x()) && same(left.y(), right.y());
  }

  private static boolean same(double left, double right) {
    return Math.abs(left - right) <= EPSILON;
  }

  private enum Orientation {
    HORIZONTAL,
    VERTICAL
  }
}

package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.layout.PolylineRoute;
import dev.dediren.ir.RouteGeometry;
import java.util.ArrayList;
import java.util.List;

/** Removes redundant staircase turns introduced when ELK joins compound-edge route sections. */
final class OrthogonalRouteNormalizer {
  private static final double EPSILON = 0.001;
  private static final double CLOSE_PARALLEL_DISTANCE = 20.0;
  private static final double CLOSE_PARALLEL_MIN_OVERLAP = 40.0;

  private OrthogonalRouteNormalizer() {}

  static List<Point> collapseStairSteps(
      List<Point> input, List<LaidOutNode> nodes, String sourceId, String targetId) {
    List<Point> route = new ArrayList<>(input);
    boolean changed;
    do {
      changed = false;
      for (int index = 0; index <= route.size() - 6; index++) {
        List<List<Point>> candidates = stairReplacements(route.subList(index, index + 6));
        for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
          List<Point> replacement = candidates.get(candidateIndex);
          if (routeLength(replacement) > routeLength(route.subList(index, index + 6)) + EPSILON
              || !clearOfUnrelatedNodes(replacement, nodes, sourceId, targetId)) {
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

  static List<Point> collapseStairSteps(
      List<Point> input,
      List<LaidOutNode> nodes,
      List<LaidOutGroup> groups,
      String sourceId,
      String targetId,
      boolean sharedJunction,
      List<List<Point>> siblingRoutes) {
    if (sharedJunction || !allSegmentsAxisAligned(input)) {
      return List.copyOf(input);
    }
    List<Point> candidate = collapseStairSteps(input, nodes, sourceId, targetId);
    if (candidate.equals(input)
        || !preservesEndpointApproaches(input, candidate)
        || addsObstacleIntrusion(input, candidate, nodes, groups)
        || reducesObstacleClearance(input, candidate, nodes, groups)
        || createsRouteConflict(candidate, siblingRoutes)) {
      return List.copyOf(input);
    }
    return candidate;
  }

  private static boolean allSegmentsAxisAligned(List<Point> route) {
    for (int index = 0; index < route.size() - 1; index++) {
      if (orientation(route.get(index), route.get(index + 1)) == null) {
        return false;
      }
    }
    return true;
  }

  private static double overlapLength(
      double firstStart, double firstEnd, double secondStart, double secondEnd) {
    double firstMin = Math.min(firstStart, firstEnd);
    double firstMax = Math.max(firstStart, firstEnd);
    return Math.max(0.0, Math.min(firstMax, secondEnd) - Math.max(firstMin, secondStart));
  }

  private static List<List<Point>> stairReplacements(List<Point> points) {
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

  private static boolean preservesEndpointApproaches(
      List<Point> nativeRoute, List<Point> candidate) {
    return nativeRoute.size() >= 2
        && candidate.size() >= 2
        && samePoint(nativeRoute.getFirst(), candidate.getFirst())
        && samePoint(nativeRoute.getLast(), candidate.getLast())
        && orientation(nativeRoute.get(0), nativeRoute.get(1))
            == orientation(candidate.get(0), candidate.get(1))
        && orientation(nativeRoute.get(nativeRoute.size() - 2), nativeRoute.getLast())
            == orientation(candidate.get(candidate.size() - 2), candidate.getLast());
  }

  private static boolean addsObstacleIntrusion(
      List<Point> nativeRoute,
      List<Point> candidate,
      List<LaidOutNode> nodes,
      List<LaidOutGroup> groups) {
    for (LaidOutNode node : nodes) {
      if (intrusionLength(candidate, node.x(), node.y(), node.width(), node.height())
          > intrusionLength(nativeRoute, node.x(), node.y(), node.width(), node.height())
              + EPSILON) {
        return true;
      }
    }
    for (LaidOutGroup group : groups) {
      if (intrusionLength(candidate, group.x(), group.y(), group.width(), group.height())
          > intrusionLength(nativeRoute, group.x(), group.y(), group.width(), group.height())
              + EPSILON) {
        return true;
      }
    }
    return false;
  }

  private static double intrusionLength(
      List<Point> route, double x, double y, double width, double height) {
    double total = 0.0;
    for (int index = 0; index < route.size() - 1; index++) {
      Point start = route.get(index);
      Point end = route.get(index + 1);
      if (same(start.y(), end.y()) && start.y() > y + EPSILON && start.y() < y + height - EPSILON) {
        total += overlapLength(start.x(), end.x(), x, x + width);
      } else if (same(start.x(), end.x())
          && start.x() > x + EPSILON
          && start.x() < x + width - EPSILON) {
        total += overlapLength(start.y(), end.y(), y, y + height);
      } else if (!same(start.x(), end.x()) && !same(start.y(), end.y())) {
        return Double.POSITIVE_INFINITY;
      }
    }
    return total;
  }

  private static boolean reducesObstacleClearance(
      List<Point> nativeRoute,
      List<Point> candidate,
      List<LaidOutNode> nodes,
      List<LaidOutGroup> groups) {
    for (LaidOutNode node : nodes) {
      if (clearance(candidate, node.x(), node.y(), node.width(), node.height()) + EPSILON
          < clearance(nativeRoute, node.x(), node.y(), node.width(), node.height())) {
        return true;
      }
    }
    for (LaidOutGroup group : groups) {
      if (clearance(candidate, group.x(), group.y(), group.width(), group.height()) + EPSILON
          < clearance(nativeRoute, group.x(), group.y(), group.width(), group.height())) {
        return true;
      }
    }
    return false;
  }

  private static double clearance(
      List<Point> route, double x, double y, double width, double height) {
    double minimum = Double.POSITIVE_INFINITY;
    for (int index = 0; index < route.size() - 1; index++) {
      minimum =
          Math.min(
              minimum,
              segmentRectangleDistance(
                  route.get(index), route.get(index + 1), x, y, width, height));
    }
    return minimum;
  }

  private static double segmentRectangleDistance(
      Point start, Point end, double x, double y, double width, double height) {
    Orientation segmentOrientation = orientation(start, end);
    if (segmentOrientation == Orientation.HORIZONTAL) {
      return Math.hypot(
          intervalDistance(start.x(), end.x(), x, x + width),
          intervalDistance(start.y(), start.y(), y, y + height));
    }
    if (segmentOrientation == Orientation.VERTICAL) {
      return Math.hypot(
          intervalDistance(start.x(), start.x(), x, x + width),
          intervalDistance(start.y(), end.y(), y, y + height));
    }
    if (samePoint(start, end)) {
      return Math.hypot(
          intervalDistance(start.x(), start.x(), x, x + width),
          intervalDistance(start.y(), start.y(), y, y + height));
    }
    // This normalizer changes only orthogonal staircases. A diagonal elsewhere in the native
    // route is outside its proof surface, so treat its clearance as unknown and keep the native
    // route whenever that uncertainty could matter.
    return 0.0;
  }

  private static double intervalDistance(
      double firstStart, double firstEnd, double secondStart, double secondEnd) {
    double firstMin = Math.min(firstStart, firstEnd);
    double firstMax = Math.max(firstStart, firstEnd);
    if (firstMax < secondStart) {
      return secondStart - firstMax;
    }
    if (secondEnd < firstMin) {
      return firstMin - secondEnd;
    }
    return 0.0;
  }

  private static boolean createsRouteConflict(List<Point> candidate, List<List<Point>> siblings) {
    PolylineRoute candidateRoute = new PolylineRoute(candidate);
    for (List<Point> sibling : siblings) {
      if (RouteGeometry.properlyIntersects(candidateRoute, new PolylineRoute(sibling))
          || closeParallelCount(candidate, sibling) > 0) {
        return true;
      }
    }
    return false;
  }

  private static int closeParallelCount(List<Point> left, List<Point> right) {
    int count = 0;
    for (int leftIndex = 0; leftIndex < left.size() - 1; leftIndex++) {
      Orientation leftOrientation = orientation(left.get(leftIndex), left.get(leftIndex + 1));
      if (leftOrientation == null) {
        continue;
      }
      for (int rightIndex = 0; rightIndex < right.size() - 1; rightIndex++) {
        Orientation rightOrientation =
            orientation(right.get(rightIndex), right.get(rightIndex + 1));
        if (leftOrientation != rightOrientation) {
          continue;
        }
        Point leftStart = left.get(leftIndex);
        Point leftEnd = left.get(leftIndex + 1);
        Point rightStart = right.get(rightIndex);
        Point rightEnd = right.get(rightIndex + 1);
        double distance =
            leftOrientation == Orientation.HORIZONTAL
                ? Math.abs(leftStart.y() - rightStart.y())
                : Math.abs(leftStart.x() - rightStart.x());
        double overlap =
            leftOrientation == Orientation.HORIZONTAL
                ? overlapLength(leftStart.x(), leftEnd.x(), rightStart.x(), rightEnd.x())
                : overlapLength(leftStart.y(), leftEnd.y(), rightStart.y(), rightEnd.y());
        if (distance < CLOSE_PARALLEL_DISTANCE && overlap >= CLOSE_PARALLEL_MIN_OVERLAP) {
          count++;
        }
      }
    }
    return count;
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

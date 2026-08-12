package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.Point;
import java.util.ArrayList;
import java.util.List;

/** Removes redundant staircase turns introduced when ELK joins compound-edge route sections. */
final class OrthogonalRouteNormalizer {
  private static final double EPSILON = 0.001;

  private OrthogonalRouteNormalizer() {}

  static List<Point> collapseStairSteps(
      List<Point> input, List<LaidOutNode> nodes, String sourceId, String targetId) {
    List<Point> route = new ArrayList<>(input);
    boolean changed;
    do {
      changed = false;
      for (int index = 0; index <= route.size() - 6; index++) {
        for (List<Point> replacement : stairReplacements(route.subList(index, index + 6))) {
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
    List<Double> pivots =
        first == Orientation.HORIZONTAL
            ? List.of(points.get(1).x(), points.get(4).x())
            : List.of(points.get(1).y(), points.get(4).y());
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

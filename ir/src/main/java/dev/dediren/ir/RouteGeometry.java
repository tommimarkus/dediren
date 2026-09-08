package dev.dediren.ir;

import dev.dediren.contracts.layout.CubicBezierRoute;
import dev.dediren.contracts.layout.CubicBezierSegment;
import dev.dediren.contracts.layout.EdgeRoute;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.layout.PolylineRoute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure geometry shared by layout quality checks and route consumers. */
public final class RouteGeometry {
  public static final double DEFAULT_FLATTENING_MAX_ERROR = 0.25;
  private static final int MAX_SUBDIVISION_DEPTH = 32;
  private static final double EPSILON = 1.0e-9;

  private RouteGeometry() {}

  public static List<Point> flatten(EdgeRoute route) {
    return flatten(route, DEFAULT_FLATTENING_MAX_ERROR);
  }

  public static List<Point> flatten(EdgeRoute route, double maxError) {
    if (!(maxError > 0.0) || !Double.isFinite(maxError)) {
      throw new IllegalArgumentException("maxError must be finite and positive");
    }
    if (route == null) {
      return List.of();
    }
    requireFiniteAndComplete(route);
    if (route instanceof PolylineRoute polyline) {
      return polyline.points();
    }
    CubicBezierRoute cubic = (CubicBezierRoute) route;
    if (cubic.start() == null) {
      return List.of();
    }
    List<Point> points = new ArrayList<>();
    points.add(cubic.start());
    Point start = cubic.start();
    for (CubicBezierSegment segment : cubic.segments()) {
      flattenCubic(
          start, segment.control1(), segment.control2(), segment.end(), maxError, 0, points);
      start = segment.end();
    }
    return List.copyOf(points);
  }

  public static Point start(EdgeRoute route) {
    if (route instanceof PolylineRoute polyline) {
      return polyline.points().isEmpty() ? null : polyline.points().getFirst();
    }
    return route instanceof CubicBezierRoute cubic ? cubic.start() : null;
  }

  public static Point end(EdgeRoute route) {
    if (route instanceof PolylineRoute polyline) {
      return polyline.points().isEmpty() ? null : polyline.points().getLast();
    }
    if (route instanceof CubicBezierRoute cubic) {
      return cubic.segments().isEmpty() ? cubic.start() : cubic.segments().getLast().end();
    }
    return null;
  }

  public static EdgeRoute reverse(EdgeRoute route) {
    if (route instanceof PolylineRoute polyline) {
      List<Point> points = new ArrayList<>(polyline.points());
      Collections.reverse(points);
      return new PolylineRoute(points);
    }
    CubicBezierRoute cubic = (CubicBezierRoute) route;
    List<CubicBezierSegment> reversed = new ArrayList<>();
    Point segmentEnd = end(cubic);
    for (int index = cubic.segments().size() - 1; index >= 0; index--) {
      CubicBezierSegment segment = cubic.segments().get(index);
      Point previousStart = index == 0 ? cubic.start() : cubic.segments().get(index - 1).end();
      reversed.add(new CubicBezierSegment(segment.control2(), segment.control1(), previousStart));
    }
    return new CubicBezierRoute(segmentEnd, reversed);
  }

  public static EdgeRoute translate(EdgeRoute route, double dx, double dy) {
    if (route instanceof PolylineRoute polyline) {
      return new PolylineRoute(polyline.points().stream().map(p -> translate(p, dx, dy)).toList());
    }
    CubicBezierRoute cubic = (CubicBezierRoute) route;
    return new CubicBezierRoute(
        translate(cubic.start(), dx, dy),
        cubic.segments().stream()
            .map(
                segment ->
                    new CubicBezierSegment(
                        translate(segment.control1(), dx, dy),
                        translate(segment.control2(), dx, dy),
                        translate(segment.end(), dx, dy)))
            .toList());
  }

  public static RouteBounds bounds(EdgeRoute route) {
    if (route == null) {
      return null;
    }
    requireFiniteAndComplete(route);
    Point first = start(route);
    if (first == null) {
      return null;
    }
    double minX = first.x();
    double minY = first.y();
    double maxX = minX;
    double maxY = minY;
    if (route instanceof CubicBezierRoute cubic) {
      Point start = cubic.start();
      for (CubicBezierSegment segment : cubic.segments()) {
        List<Double> candidates = new ArrayList<>(List.of(0.0, 1.0));
        candidates.addAll(
            extremaParameters(
                start.x(), segment.control1().x(), segment.control2().x(), segment.end().x()));
        candidates.addAll(
            extremaParameters(
                start.y(), segment.control1().y(), segment.control2().y(), segment.end().y()));
        for (double t : candidates) {
          Point point = cubicPoint(start, segment.control1(), segment.control2(), segment.end(), t);
          minX = Math.min(minX, point.x());
          minY = Math.min(minY, point.y());
          maxX = Math.max(maxX, point.x());
          maxY = Math.max(maxY, point.y());
        }
        start = segment.end();
      }
    } else {
      for (Point point : ((PolylineRoute) route).points()) {
        minX = Math.min(minX, point.x());
        minY = Math.min(minY, point.y());
        maxX = Math.max(maxX, point.x());
        maxY = Math.max(maxY, point.y());
      }
    }
    return new RouteBounds(minX, minY, maxX, maxY);
  }

  public static Point startTangent(EdgeRoute route) {
    if (route instanceof PolylineRoute polyline) {
      return tangent(polyline.points(), true);
    }
    CubicBezierRoute cubic = (CubicBezierRoute) route;
    Point vertex = cubic.start();
    for (CubicBezierSegment segment : cubic.segments()) {
      for (Point candidate : List.of(segment.control1(), segment.control2(), segment.end())) {
        Point tangent = difference(candidate, vertex);
        if (!zero(tangent)) {
          return tangent;
        }
      }
      vertex = segment.end();
    }
    return new Point(0.0, 0.0);
  }

  public static Point endTangent(EdgeRoute route) {
    if (route instanceof PolylineRoute polyline) {
      return tangent(polyline.points(), false);
    }
    CubicBezierRoute cubic = (CubicBezierRoute) route;
    Point vertex = end(cubic);
    for (int index = cubic.segments().size() - 1; index >= 0; index--) {
      CubicBezierSegment segment = cubic.segments().get(index);
      Point segmentStart = index == 0 ? cubic.start() : cubic.segments().get(index - 1).end();
      for (Point candidate : List.of(segment.control2(), segment.control1(), segmentStart)) {
        Point tangent = difference(vertex, candidate);
        if (!zero(tangent)) {
          return tangent;
        }
      }
      vertex = segmentStart;
    }
    return new Point(0.0, 0.0);
  }

  public static double length(EdgeRoute route) {
    return polylineLength(flatten(route));
  }

  public static double distanceTo(EdgeRoute route, Point point) {
    List<Point> points = flatten(route);
    if (points.isEmpty()) {
      return Double.POSITIVE_INFINITY;
    }
    if (points.size() == 1) {
      return distance(points.getFirst(), point);
    }
    double minimum = Double.POSITIVE_INFINITY;
    for (int index = 0; index + 1 < points.size(); index++) {
      minimum =
          Math.min(minimum, pointSegmentDistance(point, points.get(index), points.get(index + 1)));
    }
    return minimum;
  }

  public static boolean properlyIntersects(EdgeRoute left, EdgeRoute right) {
    List<Point> leftPoints = flatten(left);
    List<Point> rightPoints = flatten(right);
    for (int leftIndex = 0; leftIndex + 1 < leftPoints.size(); leftIndex++) {
      for (int rightIndex = 0; rightIndex + 1 < rightPoints.size(); rightIndex++) {
        if (segmentsIntersect(
                leftPoints.get(leftIndex),
                leftPoints.get(leftIndex + 1),
                rightPoints.get(rightIndex),
                rightPoints.get(rightIndex + 1))
            || interiorVertexCrossesSegment(leftPoints, leftIndex, rightPoints, rightIndex)
            || interiorVertexCrossesSegment(rightPoints, rightIndex, leftPoints, leftIndex)) {
          return true;
        }
      }
    }
    return false;
  }

  private static void flattenCubic(
      Point p0, Point p1, Point p2, Point p3, double maxError, int depth, List<Point> output) {
    double error = Math.max(pointSegmentDistance(p1, p0, p3), pointSegmentDistance(p2, p0, p3));
    if (error <= maxError) {
      output.add(p3);
      return;
    }
    if (depth >= MAX_SUBDIVISION_DEPTH) {
      throw new IllegalArgumentException("cubic route cannot be flattened to the requested error");
    }
    Point p01 = midpoint(p0, p1);
    Point p12 = midpoint(p1, p2);
    Point p23 = midpoint(p2, p3);
    Point p012 = midpoint(p01, p12);
    Point p123 = midpoint(p12, p23);
    Point split = midpoint(p012, p123);
    flattenCubic(p0, p01, p012, split, maxError, depth + 1, output);
    flattenCubic(split, p123, p23, p3, maxError, depth + 1, output);
  }

  private static List<Double> extremaParameters(double p0, double p1, double p2, double p3) {
    double a = -p0 + 3.0 * p1 - 3.0 * p2 + p3;
    double b = 2.0 * (p0 - 2.0 * p1 + p2);
    double c = p1 - p0;
    List<Double> result = new ArrayList<>(2);
    if (Math.abs(a) <= EPSILON) {
      if (Math.abs(b) > EPSILON) {
        addInterior(result, -c / b);
      }
      return result;
    }
    double discriminant = b * b - 4.0 * a * c;
    if (discriminant < 0.0) {
      return result;
    }
    double root = Math.sqrt(discriminant);
    addInterior(result, (-b + root) / (2.0 * a));
    addInterior(result, (-b - root) / (2.0 * a));
    return result;
  }

  private static void addInterior(List<Double> result, double value) {
    if (value > 0.0 && value < 1.0) {
      result.add(value);
    }
  }

  private static Point cubicPoint(Point p0, Point p1, Point p2, Point p3, double t) {
    double u = 1.0 - t;
    double a = u * u * u;
    double b = 3.0 * u * u * t;
    double c = 3.0 * u * t * t;
    double d = t * t * t;
    return new Point(
        a * p0.x() + b * p1.x() + c * p2.x() + d * p3.x(),
        a * p0.y() + b * p1.y() + c * p2.y() + d * p3.y());
  }

  private static Point tangent(List<Point> points, boolean start) {
    if (points.isEmpty()) {
      return new Point(0.0, 0.0);
    }
    Point vertex = start ? points.getFirst() : points.getLast();
    int step = start ? 1 : -1;
    for (int index = start ? 1 : points.size() - 2;
        index >= 0 && index < points.size();
        index += step) {
      Point value =
          start ? difference(points.get(index), vertex) : difference(vertex, points.get(index));
      if (!zero(value)) {
        return value;
      }
    }
    return new Point(0.0, 0.0);
  }

  private static boolean segmentsIntersect(Point a, Point b, Point c, Point d) {
    double abC = cross(a, b, c);
    double abD = cross(a, b, d);
    double cdA = cross(c, d, a);
    double cdB = cross(c, d, b);
    double tolerance = orientationTolerance(a, b, c, d);
    if (Math.abs(abC) <= tolerance
        && Math.abs(abD) <= tolerance
        && Math.abs(cdA) <= tolerance
        && Math.abs(cdB) <= tolerance) {
      return false;
    }
    return opposite(abC, abD, tolerance)
        && opposite(cdA, cdB, tolerance)
        && boxesOverlap(a, b, c, d, tolerance);
  }

  private static boolean opposite(double left, double right, double tolerance) {
    return (left < -tolerance && right > tolerance) || (left > tolerance && right < -tolerance);
  }

  private static double orientationTolerance(Point... points) {
    double scale = localScale(points);
    return EPSILON * scale * scale;
  }

  private static double coordinateTolerance(Point... points) {
    return EPSILON * localScale(points);
  }

  private static double localScale(Point... points) {
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
    return Math.max(1.0, Math.max(maxX - minX, maxY - minY));
  }

  private static boolean boxesOverlap(Point a, Point b, Point c, Point d, double tolerance) {
    return Math.max(Math.min(a.x(), b.x()), Math.min(c.x(), d.x()))
            <= Math.min(Math.max(a.x(), b.x()), Math.max(c.x(), d.x())) + tolerance
        && Math.max(Math.min(a.y(), b.y()), Math.min(c.y(), d.y()))
            <= Math.min(Math.max(a.y(), b.y()), Math.max(c.y(), d.y())) + tolerance;
  }

  private static boolean interiorVertexCrossesSegment(
      List<Point> route, int routeSegmentIndex, List<Point> other, int otherSegmentIndex) {
    Point otherStart = other.get(otherSegmentIndex);
    Point otherEnd = other.get(otherSegmentIndex + 1);
    int firstCandidate = Math.max(1, routeSegmentIndex);
    int lastCandidate = Math.min(route.size() - 2, routeSegmentIndex + 1);
    for (int vertexIndex = firstCandidate; vertexIndex <= lastCandidate; vertexIndex++) {
      Point vertex = route.get(vertexIndex);
      if (!pointOnSegment(vertex, otherStart, otherEnd)
          || same(vertex, other.getFirst())
          || same(vertex, other.getLast())) {
        continue;
      }
      double before = cross(otherStart, otherEnd, route.get(vertexIndex - 1));
      double after = cross(otherStart, otherEnd, route.get(vertexIndex + 1));
      double tolerance =
          orientationTolerance(
              otherStart, otherEnd, route.get(vertexIndex - 1), route.get(vertexIndex + 1));
      if (opposite(before, after, tolerance)) {
        return true;
      }
    }
    return false;
  }

  private static boolean pointOnSegment(Point point, Point start, Point end) {
    double orientationTolerance = orientationTolerance(point, start, end);
    double coordinateTolerance = coordinateTolerance(point, start, end);
    return Math.abs(cross(start, end, point)) <= orientationTolerance
        && point.x() >= Math.min(start.x(), end.x()) - coordinateTolerance
        && point.x() <= Math.max(start.x(), end.x()) + coordinateTolerance
        && point.y() >= Math.min(start.y(), end.y()) - coordinateTolerance
        && point.y() <= Math.max(start.y(), end.y()) + coordinateTolerance;
  }

  private static boolean same(Point left, Point right) {
    return Math.abs(left.x() - right.x()) <= EPSILON && Math.abs(left.y() - right.y()) <= EPSILON;
  }

  private static double cross(Point a, Point b, Point c) {
    return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
  }

  private static double polylineLength(List<Point> points) {
    double length = 0.0;
    for (int index = 0; index + 1 < points.size(); index++) {
      length += distance(points.get(index), points.get(index + 1));
    }
    return length;
  }

  private static double pointSegmentDistance(Point point, Point start, Point end) {
    double dx = end.x() - start.x();
    double dy = end.y() - start.y();
    double denominator = dx * dx + dy * dy;
    if (denominator <= EPSILON * EPSILON) {
      return distance(point, start);
    }
    double t = ((point.x() - start.x()) * dx + (point.y() - start.y()) * dy) / denominator;
    t = Math.max(0.0, Math.min(1.0, t));
    return Math.hypot(point.x() - (start.x() + t * dx), point.y() - (start.y() + t * dy));
  }

  private static double distance(Point left, Point right) {
    return Math.hypot(right.x() - left.x(), right.y() - left.y());
  }

  private static Point midpoint(Point left, Point right) {
    return new Point(left.x() / 2.0 + right.x() / 2.0, left.y() / 2.0 + right.y() / 2.0);
  }

  private static Point translate(Point point, double dx, double dy) {
    return point == null ? null : new Point(point.x() + dx, point.y() + dy);
  }

  private static Point difference(Point end, Point start) {
    return new Point(end.x() - start.x(), end.y() - start.y());
  }

  private static boolean zero(Point point) {
    return Math.abs(point.x()) <= EPSILON && Math.abs(point.y()) <= EPSILON;
  }

  private static void requireFiniteAndComplete(EdgeRoute route) {
    if (route instanceof PolylineRoute polyline) {
      for (Point point : polyline.points()) {
        requireFinite(point);
      }
      return;
    }
    CubicBezierRoute cubic = (CubicBezierRoute) route;
    requireFinite(cubic.start());
    for (CubicBezierSegment segment : cubic.segments()) {
      if (segment == null) {
        throw new IllegalArgumentException("cubic route contains a null segment");
      }
      requireFinite(segment.control1());
      requireFinite(segment.control2());
      requireFinite(segment.end());
    }
  }

  private static void requireFinite(Point point) {
    if (point == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y())) {
      throw new IllegalArgumentException("route contains missing or non-finite geometry");
    }
  }
}

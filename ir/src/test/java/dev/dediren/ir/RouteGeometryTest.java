package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.dediren.contracts.layout.CubicBezierRoute;
import dev.dediren.contracts.layout.CubicBezierSegment;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.layout.PolylineRoute;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteGeometryTest {

  @Test
  void cubicBoundsIncludeInteriorExtremaRatherThanOnlyControlPoints() {
    CubicBezierRoute route = cubic(point(0, 0), point(0, 100), point(100, 100), point(100, 0));

    RouteBounds bounds = RouteGeometry.bounds(route);

    assertThat(bounds.minX()).isEqualTo(0.0);
    assertThat(bounds.maxX()).isEqualTo(100.0);
    assertThat(bounds.minY()).isEqualTo(0.0);
    assertThat(bounds.maxY()).isCloseTo(75.0, within(1.0e-9));
  }

  @Test
  void reverseAndTranslatePreserveCubicGeometryAndDirection() {
    CubicBezierRoute route = cubic(point(0, 0), point(10, 20), point(20, 30), point(40, 50));

    CubicBezierRoute reversed = (CubicBezierRoute) RouteGeometry.reverse(route);
    CubicBezierRoute translated = (CubicBezierRoute) RouteGeometry.translate(reversed, 3, -2);

    assertThat(translated.start()).isEqualTo(point(43, 48));
    assertThat(translated.segments())
        .containsExactly(new CubicBezierSegment(point(23, 28), point(13, 18), point(3, -2)));
    assertThat(RouteGeometry.reverse(reversed)).isEqualTo(route);
  }

  @Test
  void endpointTangentsUseBezierControlsAndSkipDegenerateControls() {
    CubicBezierRoute route = cubic(point(0, 0), point(0, 0), point(10, 20), point(30, 20));

    assertThat(RouteGeometry.startTangent(route)).isEqualTo(point(10, 20));
    assertThat(RouteGeometry.endTangent(route)).isEqualTo(point(20, 0));
  }

  @Test
  void adaptiveFlatteningStaysWithinQuarterPixelAndSupportsLengthDistanceAndIntersection() {
    CubicBezierRoute curve = cubic(point(0, 0), point(0, 100), point(100, 100), point(100, 0));

    List<Point> flattened = RouteGeometry.flatten(curve, 0.25);

    assertThat(flattened).hasSizeGreaterThan(2);
    assertThat(denseMaximumError(curve, flattened)).isLessThanOrEqualTo(0.25 + 1.0e-9);
    assertThat(RouteGeometry.length(curve)).isGreaterThan(199.0).isLessThan(201.0);
    assertThat(RouteGeometry.distanceTo(curve, point(50, 75))).isLessThanOrEqualTo(0.25);
    assertThat(
            RouteGeometry.properlyIntersects(
                curve, new PolylineRoute(List.of(point(50, -10), point(50, 90)))))
        .isTrue();

    CubicBezierRoute inflection =
        cubic(point(0, 0), point(100, 100), point(-100, 100), point(0, 0));
    assertThat(denseMaximumError(inflection, RouteGeometry.flatten(inflection, 0.25)))
        .isLessThanOrEqualTo(0.25 + 1.0e-9);
    CubicBezierRoute degenerate = cubic(point(4, 5), point(4, 5), point(4, 5), point(4, 5));
    assertThat(denseMaximumError(degenerate, RouteGeometry.flatten(degenerate, 0.25)))
        .isLessThanOrEqualTo(0.25 + 1.0e-9);
  }

  @Test
  void malformedAndNonFiniteCurvesFailBeforeSubdivision() {
    CubicBezierRoute nonFinite =
        cubic(point(0, 0), point(Double.NaN, 10), point(20, 10), point(30, 0));
    CubicBezierRoute missingControl = cubic(point(0, 0), null, point(20, 10), point(30, 0));

    assertThatThrownBy(() -> RouteGeometry.flatten(nonFinite))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RouteGeometry.flatten(missingControl))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void intersectionClassificationIsTranslationInvariantAtLargeCoordinates() {
    PolylineRoute horizontal =
        new PolylineRoute(
            List.of(point(1_000_000_000, 1_000_000_005), point(1_000_000_010, 1_000_000_005)));
    PolylineRoute vertical =
        new PolylineRoute(
            List.of(point(1_000_000_005, 1_000_000_000), point(1_000_000_005, 1_000_000_010)));

    assertThat(RouteGeometry.properlyIntersects(horizontal, vertical)).isTrue();
  }

  private static CubicBezierRoute cubic(Point start, Point control1, Point control2, Point end) {
    return new CubicBezierRoute(start, List.of(new CubicBezierSegment(control1, control2, end)));
  }

  private static Point point(double x, double y) {
    return new Point(x, y);
  }

  private static double denseMaximumError(CubicBezierRoute route, List<Point> polyline) {
    CubicBezierSegment segment = route.segments().getFirst();
    double maximum = 0.0;
    for (int sample = 0; sample <= 10_000; sample++) {
      double t = sample / 10_000.0;
      Point point = cubicPoint(route.start(), segment, t);
      double distance = Double.POSITIVE_INFINITY;
      for (int index = 0; index + 1 < polyline.size(); index++) {
        distance =
            Math.min(
                distance,
                pointSegmentDistance(point, polyline.get(index), polyline.get(index + 1)));
      }
      maximum = Math.max(maximum, distance);
    }
    return maximum;
  }

  private static Point cubicPoint(Point start, CubicBezierSegment segment, double t) {
    double u = 1.0 - t;
    return point(
        u * u * u * start.x()
            + 3.0 * u * u * t * segment.control1().x()
            + 3.0 * u * t * t * segment.control2().x()
            + t * t * t * segment.end().x(),
        u * u * u * start.y()
            + 3.0 * u * u * t * segment.control1().y()
            + 3.0 * u * t * t * segment.control2().y()
            + t * t * t * segment.end().y());
  }

  private static double pointSegmentDistance(Point point, Point start, Point end) {
    double dx = end.x() - start.x();
    double dy = end.y() - start.y();
    double denominator = dx * dx + dy * dy;
    if (denominator == 0.0) {
      return Math.hypot(point.x() - start.x(), point.y() - start.y());
    }
    double progress = ((point.x() - start.x()) * dx + (point.y() - start.y()) * dy) / denominator;
    progress = Math.max(0.0, Math.min(1.0, progress));
    return Math.hypot(
        point.x() - (start.x() + progress * dx), point.y() - (start.y() + progress * dy));
  }
}

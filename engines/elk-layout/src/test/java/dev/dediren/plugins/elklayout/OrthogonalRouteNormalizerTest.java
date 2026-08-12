package dev.dediren.plugins.elklayout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.Point;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrthogonalRouteNormalizerTest {
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

    assertEquals(
        List.of(new Point(0, 0), new Point(0, 10), new Point(40, 10), new Point(40, 100)),
        normalized);
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
    LaidOutNode obstacle =
        new LaidOutNode("obstacle", "obstacle", "obstacle", 5, 25, 10, 10, "Obstacle");

    List<Point> normalized =
        OrthogonalRouteNormalizer.collapseStairSteps(
            staircase, List.of(obstacle), "source", "target");

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

package dev.dediren.plugins.asciirender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.PlacedGroup;
import dev.dediren.ir.PlacedNode;
import dev.dediren.ir.RoutedEdge;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CoordinateGrid}'s anchor quantization and content-widening. */
class CoordinateGridTest {

  private static PlacedNode node(String id, double x, double y, double w, double h, String label) {
    return new PlacedNode(id, id, id, x, y, w, h, label, "node", null);
  }

  private static LaidOutScene sceneOf(List<PlacedNode> nodes) {
    return new LaidOutScene("v", nodes, List.of(), List.of(), List.of());
  }

  @Test
  void distinctXValuesEvenOnePixelApartNeverShareAColumn() {
    LaidOutScene scene =
        sceneOf(List.of(node("a", 0, 0, 10, 10, "a"), node("b", 11, 0, 10, 10, "b")));
    CoordinateGrid grid = CoordinateGrid.of(scene);
    assertThat(grid.colOf(0)).isNotEqualTo(grid.colOf(11));
  }

  @Test
  void anchorOrderingIsPreserved() {
    LaidOutScene scene =
        sceneOf(List.of(node("a", 0, 0, 10, 10, "a"), node("b", 100, 0, 10, 10, "b")));
    CoordinateGrid grid = CoordinateGrid.of(scene);
    assertThat(grid.colOf(0)).isLessThan(grid.colOf(10));
    assertThat(grid.colOf(10)).isLessThan(grid.colOf(100));
    assertThat(grid.colOf(100)).isLessThan(grid.colOf(110));
  }

  @Test
  void longLabelWidensNodeBorderColumnsToFitLabelPlusBorders() {
    String label = "a".repeat(30);
    LaidOutScene scene = sceneOf(List.of(node("a", 0, 0, 5, 5, label)));
    CoordinateGrid grid = CoordinateGrid.of(scene);
    int span = grid.colOf(5) - grid.colOf(0);
    assertThat(span).isGreaterThanOrEqualTo(label.length() + 2);
  }

  @Test
  void wideningOneNodeDoesNotBreakAnEarlierSatisfiedNodesWidth() {
    String longLabel = "a".repeat(30);
    LaidOutScene scene =
        sceneOf(List.of(node("a", 0, 0, 400, 10, "short"), node("b", 500, 0, 5, 5, longLabel)));
    CoordinateGrid grid = CoordinateGrid.of(scene);
    int aSpan = grid.colOf(400) - grid.colOf(0);
    assertThat(aSpan).isGreaterThanOrEqualTo("short".length() + 2);
    int bSpan = grid.colOf(505) - grid.colOf(500);
    assertThat(bSpan).isGreaterThanOrEqualTo(longLabel.length() + 2);
  }

  @Test
  void colOfOnAnUnknownCoordinateThrows() {
    LaidOutScene scene = sceneOf(List.of(node("a", 0, 0, 10, 10, "a")));
    CoordinateGrid grid = CoordinateGrid.of(scene);
    assertThatThrownBy(() -> grid.colOf(9999)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rowOfOnAnUnknownCoordinateThrows() {
    LaidOutScene scene = sceneOf(List.of(node("a", 0, 0, 10, 10, "a")));
    CoordinateGrid grid = CoordinateGrid.of(scene);
    assertThatThrownBy(() -> grid.rowOf(9999)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anchorsWithinEpsilonAreMerged() {
    LaidOutScene scene =
        sceneOf(List.of(node("a", 0, 0, 10.2, 10, "a"), node("b", 10.3, 0, 10, 10, "b")));
    CoordinateGrid grid = CoordinateGrid.of(scene);
    assertThat(grid.colOf(10.2)).isEqualTo(grid.colOf(10.3));
  }

  @Test
  void edgePointsContributeAnchors() {
    RoutedEdge edge =
        new RoutedEdge(
            "e",
            "a",
            "b",
            "e",
            "e",
            List.of(),
            List.of(new dev.dediren.contracts.layout.Point(50, 50)),
            "",
            null);
    LaidOutScene scene =
        new LaidOutScene(
            "v",
            List.of(node("a", 0, 0, 10, 10, "a"), node("b", 100, 100, 10, 10, "b")),
            List.of(edge),
            List.of(),
            List.of());
    CoordinateGrid grid = CoordinateGrid.of(scene);
    assertThat(grid.colOf(50)).isBetween(grid.colOf(10), grid.colOf(100));
  }

  @Test
  void edgePointsWithinTwoUnitsOfABorderAdoptTheBorderAnchor() {
    // ELK routes edge endpoints a pixel or two off the node boundary; without border snapping
    // that offset becomes its own column and every edge detaches from its box by one cell.
    RoutedEdge edge =
        new RoutedEdge(
            "e",
            "a",
            "b",
            "e",
            "e",
            List.of(),
            List.of(
                new dev.dediren.contracts.layout.Point(81, 5),
                new dev.dediren.contracts.layout.Point(99, 5)),
            "",
            null);
    LaidOutScene scene =
        new LaidOutScene(
            "v",
            List.of(node("a", 0, 0, 80, 10, "a"), node("b", 100, 0, 80, 10, "b")),
            List.of(edge),
            List.of(),
            List.of());
    CoordinateGrid grid = CoordinateGrid.of(scene);
    assertThat(grid.colOf(81)).isEqualTo(grid.colOf(80));
    assertThat(grid.colOf(99)).isEqualTo(grid.colOf(100));
  }

  @Test
  void edgePointsBeyondTheSnapToleranceKeepTheirOwnAnchor() {
    RoutedEdge edge =
        new RoutedEdge(
            "e",
            "a",
            "b",
            "e",
            "e",
            List.of(),
            List.of(new dev.dediren.contracts.layout.Point(85, 5)),
            "",
            null);
    LaidOutScene scene =
        new LaidOutScene(
            "v",
            List.of(node("a", 0, 0, 80, 10, "a"), node("b", 100, 0, 80, 10, "b")),
            List.of(edge),
            List.of(),
            List.of());
    CoordinateGrid grid = CoordinateGrid.of(scene);
    assertThat(grid.colOf(85)).isNotEqualTo(grid.colOf(80));
    assertThat(grid.colOf(85)).isNotEqualTo(grid.colOf(100));
  }

  @Test
  void groupBordersContributeAnchorsWithoutContentWidening() {
    PlacedGroup group =
        new PlacedGroup("g", "g", "g", null, 0, 0, 3, 3, List.of("a"), "a-really-long-group-label");
    LaidOutScene scene =
        new LaidOutScene(
            "v", List.of(node("a", 0, 0, 3, 3, "a")), List.of(), List.of(group), List.of());
    CoordinateGrid grid = CoordinateGrid.of(scene);
    // Group label does not force widening: span reflects the small node/group extents only.
    int span = grid.colOf(3) - grid.colOf(0);
    assertThat(span).isLessThan("a-really-long-group-label".length());
  }

  @Test
  void widthAndHeightAreLastAnchorPlusOne() {
    LaidOutScene scene = sceneOf(List.of(node("a", 0, 0, 10, 10, "a")));
    CoordinateGrid grid = CoordinateGrid.of(scene);
    assertThat(grid.width()).isEqualTo(grid.colOf(10) + 1);
    assertThat(grid.height()).isEqualTo(grid.rowOf(10) + 1);
  }
}

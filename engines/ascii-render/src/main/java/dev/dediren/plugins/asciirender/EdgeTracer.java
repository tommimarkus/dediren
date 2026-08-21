package dev.dediren.plugins.asciirender;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.Point;
import dev.dediren.ir.RoutedEdge;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws one {@link RoutedEdge}'s route onto the grid: axis-aligned segments as straight lines, with
 * an L-route approximation ({@link DiagnosticCode#ASCII_EDGE_APPROXIMATED}) for any polyline whose
 * route is not axis-aligned in source coordinates, and an arrowhead at the final point.
 */
final class EdgeTracer {

  private static final int NORTH = 1;
  private static final int EAST = 2;
  private static final int SOUTH = 4;
  private static final int WEST = 8;

  private EdgeTracer() {}

  static List<Diagnostic> draw(
      CharCanvas canvas, CoordinateGrid grid, GlyphSet glyphs, RoutedEdge edge) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    List<int[]> cells = drawCells(grid, edge, diagnostics);
    if (cells.size() < 2) {
      return diagnostics;
    }

    for (int i = 0; i < cells.size() - 1; i++) {
      int[] a = cells.get(i);
      int[] b = cells.get(i + 1);
      if (a[0] == b[0]) {
        canvas.hline(a[0], a[1], b[1]);
      } else if (a[1] == b[1]) {
        canvas.vline(a[1], a[0], b[0]);
      }
    }

    int[] last = cells.get(cells.size() - 1);
    int[] secondLast = cells.get(cells.size() - 2);
    int dirBit;
    int arrowRow;
    int arrowCol;
    if (last[0] == secondLast[0]) {
      dirBit = last[1] > secondLast[1] ? EAST : WEST;
      arrowRow = last[0];
      arrowCol = last[1] + (dirBit == EAST ? -1 : 1);
    } else {
      dirBit = last[0] > secondLast[0] ? SOUTH : NORTH;
      arrowRow = last[0] + (dirBit == SOUTH ? -1 : 1);
      arrowCol = last[1];
    }
    if (arrowRow != secondLast[0] || arrowCol != secondLast[1]) {
      canvas.literal(arrowRow, arrowCol, glyphs.arrow(dirBit));
    }
    return diagnostics;
  }

  /**
   * The axis-aligned cell path this edge draws: the quantized route points when every consecutive
   * pair is already axis-aligned in source coordinates, or a 3-cell L-route (start, corner, end)
   * with {@link DiagnosticCode#ASCII_EDGE_APPROXIMATED} appended to {@code diagnostics} otherwise.
   * Shared with {@link EdgeLabelPlacer} so label placement reasons about the same drawn geometry.
   */
  static List<int[]> drawCells(CoordinateGrid grid, RoutedEdge edge, List<Diagnostic> diagnostics) {
    List<Point> points = edge.points();
    if (points.size() < 2) {
      return List.of();
    }
    List<int[]> cells = new ArrayList<>();
    for (Point p : points) {
      cells.add(new int[] {grid.rowOf(p.y()), grid.colOf(p.x())});
    }
    boolean diagonal = false;
    for (int i = 0; i < points.size() - 1; i++) {
      double dx = Math.abs(points.get(i + 1).x() - points.get(i).x());
      double dy = Math.abs(points.get(i + 1).y() - points.get(i).y());
      if (dx > 0.5 && dy > 0.5) {
        diagonal = true;
        break;
      }
    }
    if (!diagonal) {
      return cells;
    }
    int[] start = cells.get(0);
    int[] end = cells.get(cells.size() - 1);
    int[] corner = {start[0], end[1]};
    diagnostics.add(
        new Diagnostic(
            DiagnosticCode.ASCII_EDGE_APPROXIMATED.code(),
            DiagnosticSeverity.WARNING,
            "edge " + edge.id() + " is not axis-aligned and was approximated as an L-route",
            "edges[" + edge.id() + "]"));
    return List.of(start, corner, end);
  }
}

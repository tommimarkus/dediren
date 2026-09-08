package dev.dediren.plugins.asciirender;

import static dev.dediren.ir.RouteGeometry.flatten;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.Point;
import dev.dediren.ir.RoutedEdge;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws one {@link RoutedEdge}'s route onto the grid: axis-aligned segments as straight lines, with
 * a deterministic near-segment staircase ({@link DiagnosticCode#ASCII_EDGE_APPROXIMATED}) for each
 * diagonal segment, and an arrowhead at the final point.
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

    Map<String, Set<String>> permittedOwners = canvas.permittedSharedOwners(edge, cells);
    boolean collides = false;
    for (int i = 0; i < cells.size() - 1; i++) {
      int[] a = cells.get(i);
      int[] b = cells.get(i + 1);
      if (a[0] == b[0]) {
        collides |= canvas.hline(a[0], a[1], b[1], edge.id(), permittedOwners);
      } else if (a[1] == b[1]) {
        collides |= canvas.vline(a[1], a[0], b[0], edge.id(), permittedOwners);
      }
    }

    int[] last = cells.get(cells.size() - 1);
    int finalRunStart = cells.size() - 2;
    int rowStep = Integer.compare(last[0], cells.get(finalRunStart)[0]);
    int colStep = Integer.compare(last[1], cells.get(finalRunStart)[1]);
    while (finalRunStart > 0
        && rowStep == Integer.compare(cells.get(finalRunStart)[0], cells.get(finalRunStart - 1)[0])
        && colStep
            == Integer.compare(cells.get(finalRunStart)[1], cells.get(finalRunStart - 1)[1])) {
      finalRunStart--;
    }
    int[] finalRunOrigin = cells.get(finalRunStart);
    int dirBit;
    int arrowRow;
    int arrowCol;
    if (rowStep == 0) {
      dirBit = colStep > 0 ? EAST : WEST;
      arrowRow = last[0];
      arrowCol = last[1] + (dirBit == EAST ? -1 : 1);
    } else {
      dirBit = rowStep > 0 ? SOUTH : NORTH;
      arrowRow = last[0] + (dirBit == SOUTH ? -1 : 1);
      arrowCol = last[1];
    }
    if (arrowRow != finalRunOrigin[0] || arrowCol != finalRunOrigin[1]) {
      canvas.literal(arrowRow, arrowCol, glyphs.arrow(dirBit));
    }
    canvas.recordProjectedEdge(edge, cells);
    if (collides) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.ASCII_EDGE_APPROXIMATED.code(),
              DiagnosticSeverity.WARNING,
              "edge "
                  + edge.id()
                  + " collides with an earlier edge after character-grid projection",
              "edges[" + edge.id() + "]"));
    }
    return diagnostics;
  }

  /**
   * The axis-aligned cell path this edge draws, expanded to every projected cell. Diagonal source
   * segments become deterministic near-segment staircases, preserving every source turn rather than
   * replacing a multi-turn route with one end-to-end L. Shared with {@link EdgeLabelPlacer} so
   * label placement reasons about the same drawn geometry.
   */
  static List<int[]> drawCells(CoordinateGrid grid, RoutedEdge edge, List<Diagnostic> diagnostics) {
    List<Point> points = flatten(edge.route());
    if (points.size() < 2) {
      return List.of();
    }
    List<int[]> anchors = new ArrayList<>();
    for (Point p : points) {
      anchors.add(new int[] {grid.rowOf(p.y()), grid.colOf(p.x())});
    }
    boolean diagonal = false;
    List<int[]> cells = new ArrayList<>();
    cells.add(anchors.getFirst());
    for (int index = 0; index < anchors.size() - 1; index++) {
      int[] from = anchors.get(index);
      int[] to = anchors.get(index + 1);
      if (from[0] != to[0] && from[1] != to[1]) {
        diagonal = true;
        appendStaircase(cells, from, to);
      } else {
        appendAxisRun(cells, to);
      }
    }
    if (diagonal) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.ASCII_EDGE_APPROXIMATED.code(),
              DiagnosticSeverity.WARNING,
              "edge "
                  + edge.id()
                  + " has diagonal segment(s) rasterized as deterministic near-segment staircases",
              "edges[" + edge.id() + "]"));
    }
    return List.copyOf(cells);
  }

  private static void appendDistinct(List<int[]> cells, int[] candidate) {
    int[] previous = cells.getLast();
    if (previous[0] != candidate[0] || previous[1] != candidate[1]) {
      cells.add(candidate);
    }
  }

  /** Expands a straight raster run so collision intent is attached to every projected cell. */
  private static void appendAxisRun(List<int[]> cells, int[] target) {
    int[] previous = cells.getLast();
    int row = previous[0];
    int col = previous[1];
    int rowStep = Integer.compare(target[0], row);
    int colStep = Integer.compare(target[1], col);
    while (row != target[0] || col != target[1]) {
      row += rowStep;
      col += colStep;
      appendDistinct(cells, new int[] {row, col});
    }
  }

  /**
   * Adds an axis-connected digital line using the ordered row/column boundary crossings of the
   * source segment. Ties step row first, making the result stable while keeping every cell within
   * one grid unit of the continuous diagonal.
   */
  private static void appendStaircase(List<int[]> cells, int[] from, int[] to) {
    int row = from[0];
    int col = from[1];
    int rowDistance = Math.abs(to[0] - from[0]);
    int colDistance = Math.abs(to[1] - from[1]);
    int rowStep = Integer.compare(to[0], from[0]);
    int colStep = Integer.compare(to[1], from[1]);
    int rowsTaken = 0;
    int colsTaken = 0;
    while (row != to[0] || col != to[1]) {
      double nextRow =
          rowsTaken == rowDistance ? Double.POSITIVE_INFINITY : (rowsTaken + 1.0) / rowDistance;
      double nextCol =
          colsTaken == colDistance ? Double.POSITIVE_INFINITY : (colsTaken + 1.0) / colDistance;
      if (nextRow <= nextCol) {
        row += rowStep;
        rowsTaken++;
      } else {
        col += colStep;
        colsTaken++;
      }
      appendDistinct(cells, new int[] {row, col});
    }
  }
}

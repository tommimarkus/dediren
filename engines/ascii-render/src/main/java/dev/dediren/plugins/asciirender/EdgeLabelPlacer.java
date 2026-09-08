package dev.dediren.plugins.asciirender;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.ir.RoutedEdge;
import java.util.ArrayList;
import java.util.List;

/**
 * Places one {@link RoutedEdge}'s label onto its longest drawn segment, run after every node is
 * drawn so it can detect a collision with a node's literal cells. A horizontal segment tries the
 * row above, then the row below; a vertical segment tries one column right, then one column left of
 * the segment's midpoint. If both candidates collide (including clipping at the canvas edge), the
 * label is dropped and {@link DiagnosticCode#ASCII_EDGE_LABEL_DROPPED} is reported.
 */
final class EdgeLabelPlacer {

  private EdgeLabelPlacer() {}

  static List<Diagnostic> place(CharCanvas canvas, CoordinateGrid grid, RoutedEdge edge) {
    String label = edge.label();
    if (label == null || label.isBlank()) {
      return List.of();
    }
    List<Diagnostic> diagnostics = new ArrayList<>();
    List<int[]> cells = EdgeTracer.drawCells(grid, edge, new ArrayList<>());
    if (cells.size() < 2) {
      return List.of();
    }

    int bestLen = -1;
    int[] bestStart = null;
    int[] bestEnd = null;
    boolean bestHorizontal = false;
    int runStart = 0;
    int rowStep = Integer.compare(cells.get(1)[0], cells.get(0)[0]);
    int colStep = Integer.compare(cells.get(1)[1], cells.get(0)[1]);
    for (int i = 1; i < cells.size(); i++) {
      int nextRowStep =
          i == cells.size() - 1
              ? Integer.MIN_VALUE
              : Integer.compare(cells.get(i + 1)[0], cells.get(i)[0]);
      int nextColStep =
          i == cells.size() - 1
              ? Integer.MIN_VALUE
              : Integer.compare(cells.get(i + 1)[1], cells.get(i)[1]);
      if (rowStep == nextRowStep && colStep == nextColStep) {
        continue;
      }
      int[] a = cells.get(runStart);
      int[] b = cells.get(i);
      boolean horizontal = rowStep == 0;
      int len = horizontal ? Math.abs(b[1] - a[1]) : Math.abs(b[0] - a[0]);
      if (len > bestLen) {
        bestLen = len;
        bestStart = a;
        bestEnd = b;
        bestHorizontal = horizontal;
      }
      runStart = i;
      rowStep = nextRowStep;
      colStep = nextColStep;
    }

    boolean placed;
    if (bestHorizontal) {
      int row = bestStart[0];
      int midCol = (bestStart[1] + bestEnd[1]) / 2;
      int startCol = midCol - label.length() / 2;
      placed =
          tryWrite(canvas, row - 1, startCol, label) || tryWrite(canvas, row + 1, startCol, label);
    } else {
      int col = bestStart[1];
      int midRow = (bestStart[0] + bestEnd[0]) / 2;
      placed =
          tryWrite(canvas, midRow, col + 1, label)
              || tryWrite(canvas, midRow, col - label.length(), label);
    }

    if (placed) {
      return List.of();
    }
    diagnostics.add(
        new Diagnostic(
            DiagnosticCode.ASCII_EDGE_LABEL_DROPPED.code(),
            DiagnosticSeverity.WARNING,
            "edge "
                + edge.id()
                + "'s label \""
                + label
                + "\" had nowhere clear to land and was dropped",
            "edges[" + edge.id() + "].label"));
    return diagnostics;
  }

  private static boolean tryWrite(CharCanvas canvas, int row, int col, String label) {
    for (int i = 0; i < label.length(); i++) {
      int c = col + i;
      if (!canvas.isInBounds(row, c) || canvas.isLiteralAt(row, c)) {
        return false;
      }
    }
    canvas.text(row, col, label);
    return true;
  }
}

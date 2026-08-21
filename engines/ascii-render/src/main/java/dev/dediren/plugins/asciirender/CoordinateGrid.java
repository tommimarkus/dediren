package dev.dediren.plugins.asciirender;

import dev.dediren.contracts.layout.Point;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.PlacedGroup;
import dev.dediren.ir.PlacedNode;
import dev.dediren.ir.RoutedEdge;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Quantizes a {@link LaidOutScene}'s continuous geometry onto a character grid: every distinct
 * border or edge-point coordinate becomes a column/row anchor, gaps between anchors scale down to a
 * handful of cells, and node borders widen further where needed to fit a wrapped label.
 */
final class CoordinateGrid {

  private static final double EPSILON = 0.5;
  private static final double X_UNITS_PER_COL = 8.0;
  private static final double Y_UNITS_PER_ROW = 16.0;
  private static final int LABEL_WRAP_MAX_COLS = 32;
  private static final int MAX_WRAP_ROWS = 6;

  private final double[] xAnchors;
  private final int[] xColumns;
  private final double[] yAnchors;
  private final int[] yColumns;

  private CoordinateGrid(double[] xAnchors, int[] xColumns, double[] yAnchors, int[] yColumns) {
    this.xAnchors = xAnchors;
    this.xColumns = xColumns;
    this.yAnchors = yAnchors;
    this.yColumns = yColumns;
  }

  static CoordinateGrid of(LaidOutScene scene) {
    double[] xAnchors = mergeAnchors(collectAnchors(scene, true));
    double[] yAnchors = mergeAnchors(collectAnchors(scene, false));
    int[] xColumns = assignPositions(xAnchors, X_UNITS_PER_COL);
    int[] yColumns = assignPositions(yAnchors, Y_UNITS_PER_ROW);
    widenForNodeLabels(scene, xAnchors, xColumns, true);
    widenForNodeLabels(scene, yAnchors, yColumns, false);
    return new CoordinateGrid(xAnchors, xColumns, yAnchors, yColumns);
  }

  int colOf(double x) {
    return xColumns[nearestIndex(xAnchors, x)];
  }

  int rowOf(double y) {
    return yColumns[nearestIndex(yAnchors, y)];
  }

  int width() {
    return xColumns.length == 0 ? 0 : xColumns[xColumns.length - 1] + 1;
  }

  int height() {
    return yColumns.length == 0 ? 0 : yColumns[yColumns.length - 1] + 1;
  }

  private static List<Double> collectAnchors(LaidOutScene scene, boolean isX) {
    List<Double> raw = new ArrayList<>();
    for (PlacedNode n : scene.nodes()) {
      raw.add(isX ? n.x() : n.y());
      raw.add(isX ? n.x() + n.width() : n.y() + n.height());
    }
    for (PlacedGroup g : scene.groups()) {
      raw.add(isX ? g.x() : g.y());
      raw.add(isX ? g.x() + g.width() : g.y() + g.height());
    }
    for (RoutedEdge e : scene.edges()) {
      for (Point p : e.points()) {
        raw.add(isX ? p.x() : p.y());
      }
    }
    return raw;
  }

  private static double[] mergeAnchors(List<Double> raw) {
    List<Double> sorted = new ArrayList<>(raw);
    sorted.sort(Comparator.naturalOrder());
    List<Double> merged = new ArrayList<>();
    for (double v : sorted) {
      if (merged.isEmpty() || v - merged.get(merged.size() - 1) >= EPSILON) {
        merged.add(v);
      }
    }
    double[] result = new double[merged.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = merged.get(i);
    }
    return result;
  }

  private static int[] assignPositions(double[] anchors, double unitsPerCell) {
    int[] columns = new int[anchors.length];
    for (int i = 1; i < anchors.length; i++) {
      double delta = anchors[i] - anchors[i - 1];
      long gap = Math.max(1, Math.round(delta / unitsPerCell));
      columns[i] = columns[i - 1] + (int) gap;
    }
    return columns;
  }

  /**
   * Widens the gap that precedes each node's right-border anchor when its wrapped label does not
   * fit the anchor-derived span, shifting that anchor and every later one to make room. Nodes are
   * processed in ascending right-border order so a node satisfied by an earlier widening keeps its
   * span when a later node is widened.
   */
  private static void widenForNodeLabels(
      LaidOutScene scene, double[] anchors, int[] columns, boolean isX) {
    List<PlacedNode> nodes = new ArrayList<>(scene.nodes());
    nodes.sort(Comparator.comparingDouble(n -> isX ? n.x() + n.width() : n.y() + n.height()));
    for (PlacedNode n : nodes) {
      double startVal = isX ? n.x() : n.y();
      double endVal = isX ? n.x() + n.width() : n.y() + n.height();
      int startIdx = nearestIndex(anchors, startVal);
      int endIdx = nearestIndex(anchors, endVal);
      int required = isX ? requiredOuterCols(n.label()) : requiredOuterRows(n.label());
      int span = columns[endIdx] - columns[startIdx];
      if (span < required) {
        int deficit = required - span;
        for (int i = endIdx; i < columns.length; i++) {
          columns[i] += deficit;
        }
      }
    }
  }

  private static int requiredOuterCols(String label) {
    List<String> lines = LabelWrap.wrap(label, LABEL_WRAP_MAX_COLS);
    if (lines.isEmpty()) {
      return 3;
    }
    int longest = 0;
    for (String line : lines) {
      longest = Math.max(longest, line.length());
    }
    return longest + 2;
  }

  private static int requiredOuterRows(String label) {
    List<String> lines = LabelWrap.wrap(label, LABEL_WRAP_MAX_COLS);
    int rows = Math.min(lines.size(), MAX_WRAP_ROWS) + 2;
    return Math.max(3, rows);
  }

  private static int nearestIndex(double[] anchors, double value) {
    int best = -1;
    double bestDiff = Double.MAX_VALUE;
    for (int i = 0; i < anchors.length; i++) {
      double diff = Math.abs(anchors[i] - value);
      if (diff < bestDiff) {
        bestDiff = diff;
        best = i;
      }
    }
    if (best == -1 || bestDiff > EPSILON) {
      throw new IllegalArgumentException("No anchor near coordinate: " + value);
    }
    return best;
  }
}

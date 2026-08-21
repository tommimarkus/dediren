package dev.dediren.plugins.asciirender;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.ir.PlacedNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws one {@link PlacedNode} as a bordered box with its wrapped, centered label. Truncates a
 * label that outgrows the box (either its wrapped line count or an individual line's width),
 * marking the cut with {@link GlyphSet#truncationMarker()} and reporting {@link
 * DiagnosticCode#ASCII_LABEL_TRUNCATED}.
 */
final class NodeBox {

  private static final int LABEL_WRAP_MAX_COLS = 32;

  private NodeBox() {}

  static List<Diagnostic> draw(CharCanvas canvas, CoordinateGrid grid, GlyphSet glyphs, PlacedNode node) {
    int top = grid.rowOf(node.y());
    int bottom = grid.rowOf(node.y() + node.height());
    int left = grid.colOf(node.x());
    int right = grid.colOf(node.x() + node.width());

    canvas.hline(top, left, right);
    canvas.hline(bottom, left, right);
    canvas.vline(left, top, bottom);
    canvas.vline(right, top, bottom);

    int innerHeight = Math.max(0, bottom - top - 1);
    int innerWidth = Math.max(0, right - left - 1);
    if (innerHeight > 0 && innerWidth > 0) {
      canvas.clearRect(top + 1, left + 1, bottom - 1, right - 1);
    }

    List<String> lines = LabelWrap.wrap(node.label(), LABEL_WRAP_MAX_COLS);
    if (lines.isEmpty() || innerHeight == 0 || innerWidth == 0) {
      return List.of();
    }

    boolean rowsTruncated = lines.size() > innerHeight;
    List<String> shown = rowsTruncated ? lines.subList(0, innerHeight) : lines;
    List<String> finalLines = new ArrayList<>();
    boolean truncated = rowsTruncated;
    for (int i = 0; i < shown.size(); i++) {
      String line = shown.get(i);
      boolean forceMarker = rowsTruncated && i == shown.size() - 1;
      if (line.length() > innerWidth) {
        finalLines.add(truncate(line, innerWidth, glyphs));
        truncated = true;
      } else if (forceMarker) {
        finalLines.add(addMarker(line, innerWidth, glyphs));
      } else {
        finalLines.add(line);
      }
    }

    int topPad = (innerHeight - finalLines.size()) / 2;
    for (int i = 0; i < finalLines.size(); i++) {
      String line = finalLines.get(i);
      int leftPad = (innerWidth - line.length()) / 2;
      canvas.text(top + 1 + topPad + i, left + 1 + leftPad, line);
    }

    if (!truncated) {
      return List.of();
    }
    return List.of(
        new Diagnostic(
            DiagnosticCode.ASCII_LABEL_TRUNCATED.code(),
            DiagnosticSeverity.WARNING,
            "node " + node.id() + "'s label does not fit its box and was truncated",
            "nodes[" + node.id() + "].label"));
  }

  private static String truncate(String line, int maxWidth, GlyphSet glyphs) {
    if (maxWidth <= 0) {
      return "";
    }
    return line.substring(0, Math.max(0, maxWidth - 1)) + glyphs.truncationMarker();
  }

  private static String addMarker(String line, int maxWidth, GlyphSet glyphs) {
    if (maxWidth <= 0) {
      return "";
    }
    if (line.length() < maxWidth) {
      return line + glyphs.truncationMarker();
    }
    return truncate(line, maxWidth, glyphs);
  }
}

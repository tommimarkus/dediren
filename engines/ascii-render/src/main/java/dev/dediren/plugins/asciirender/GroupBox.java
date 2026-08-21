package dev.dediren.plugins.asciirender;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.ir.PlacedGroup;
import java.util.List;

/**
 * Draws one {@link PlacedGroup} as a border-only rect (no interior clear, unlike {@link NodeBox}),
 * with its label embedded in the top border two cells right of the top-left corner.
 */
final class GroupBox {

  private GroupBox() {}

  static List<Diagnostic> draw(
      CharCanvas canvas, CoordinateGrid grid, GlyphSet glyphs, PlacedGroup group) {
    int top = grid.rowOf(group.y());
    int bottom = grid.rowOf(group.y() + group.height());
    int left = grid.colOf(group.x());
    int right = grid.colOf(group.x() + group.width());

    canvas.hline(top, left, right);
    canvas.hline(bottom, left, right);
    canvas.vline(left, top, bottom);
    canvas.vline(right, top, bottom);

    String label = group.label();
    if (label == null || label.isBlank()) {
      return List.of();
    }
    String padded = " " + label.trim() + " ";
    int maxLabelWidth = right - left + 1 - 4;
    String toWrite = padded;
    boolean truncated = false;
    if (padded.length() > maxLabelWidth) {
      truncated = true;
      toWrite =
          maxLabelWidth <= 0
              ? ""
              : padded.substring(0, Math.max(0, maxLabelWidth - 1)) + glyphs.truncationMarker();
    }
    if (!toWrite.isEmpty()) {
      canvas.text(top, left + 2, toWrite);
    }
    if (!truncated) {
      return List.of();
    }
    return List.of(
        new Diagnostic(
            DiagnosticCode.ASCII_LABEL_TRUNCATED.code(),
            DiagnosticSeverity.WARNING,
            "group " + group.id() + "'s label does not fit its border and was truncated",
            "groups[" + group.id() + "].label"));
  }
}

package dev.dediren.plugins.archimateoef;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * Rounds view geometry to the integers the Open Group exchange schema demands, clamping whatever
 * falls outside its attribute ranges and recording every clamp as a warning.
 *
 * <p>The two contracts disagree: {@code LocationGroup} types {@code x}/{@code y} as {@code
 * xs:nonNegativeInteger} and {@code SizeGroup} types {@code w}/{@code h} as {@code
 * xs:positiveInteger}, while {@code layout-result.schema.json} types all four as plain numbers with
 * no lower bound. A negative coordinate or a sub-pixel size is therefore valid under dediren's own
 * published contract and fatal at the exchange schema — and dediren's own ELK layout can produce
 * both, so the input is not repairable by editing source JSON. Clamping keeps the export alive.
 *
 * <p>The clamp is always disclosed. Moving a node without saying so would contradict the export
 * documentation's promise that what an export cannot carry is declared rather than silently
 * dropped, and would leave a consumer comparing an SVG against an OEF that disagree about position.
 */
final class OefGeometry {

  private final List<Diagnostic> clamps = new ArrayList<>();

  /** Formats an {@code x}/{@code y} coordinate, clamping below zero. */
  String nonNegative(double value, String path) {
    return clamp(value, 0L, "xs:nonNegativeInteger", path);
  }

  /** Formats a {@code w}/{@code h} extent, clamping below one. */
  String positive(double value, String path) {
    return clamp(value, 1L, "xs:positiveInteger", path);
  }

  /** Every clamp applied so far, in emission order. */
  List<Diagnostic> diagnostics() {
    return List.copyOf(clamps);
  }

  boolean collapsedByRounding(Point left, Point right) {
    boolean distinct =
        Double.compare(left.x(), right.x()) != 0 || Double.compare(left.y(), right.y()) != 0;
    return distinct
        && Math.max(0L, Math.round(left.x())) == Math.max(0L, Math.round(right.x()))
        && Math.max(0L, Math.round(left.y())) == Math.max(0L, Math.round(right.y()));
  }

  void reportRoundedCollapse(String path) {
    clamps.add(
        new Diagnostic(
            DiagnosticCode.OEF_GEOMETRY_CLAMPED.code(),
            DiagnosticSeverity.WARNING,
            "route geometry collapsed onto the preceding exchange location after integer rounding;"
                + " the exported route loses a distinct sample",
            path));
  }

  void reportRoundedObstacle(String path, String obstacleId) {
    clamps.add(
        new Diagnostic(
            DiagnosticCode.OEF_GEOMETRY_CLAMPED.code(),
            DiagnosticSeverity.WARNING,
            "route geometry intersects obstacle '"
                + obstacleId
                + "' after OEF integer rounding or clamping; the exported route differs from the layout result",
            path));
  }

  Point roundedPoint(Point point) {
    return new Point(nonNegativeValue(point.x()), nonNegativeValue(point.y()));
  }

  long nonNegativeValue(double value) {
    return Math.max(0L, Math.round(value));
  }

  long positiveValue(double value) {
    return Math.max(1L, Math.round(value));
  }

  private String clamp(double value, long floor, String schemaType, String path) {
    long rounded = Math.round(value);
    if (value >= floor && rounded >= floor) {
      return Long.toString(rounded);
    }
    clamps.add(
        new Diagnostic(
            DiagnosticCode.OEF_GEOMETRY_CLAMPED.code(),
            DiagnosticSeverity.WARNING,
            "layout geometry "
                + value
                + " is outside the range the ArchiMate exchange schema accepts here ("
                + schemaType
                + ") and was clamped to "
                + floor
                + "; the exported position or size differs from the layout result",
            path));
    return Long.toString(floor);
  }
}

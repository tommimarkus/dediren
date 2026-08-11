package dev.dediren.plugins.render;

import dev.dediren.plugins.render.svg.LabelBox;
import dev.dediren.plugins.render.svg.SvgBounds;

/**
 * Something a document draws.
 *
 * <p>{@link #contributeBounds} grows the accumulator by the ink this element accounts for, using
 * the same {@link SvgBounds} calls with the same arguments the pass it replaced used — no rewritten
 * arithmetic, so no new rounding. It writes into the accumulator rather than returning boxes
 * because that type is already a mutable min/max fold, and because that fold is order-independent
 * for finite coordinates the measure walk may visit elements in emission order even though the old
 * passes visited them in another. (The fold is order-independent for infinities too; only the sign
 * bit of a NaN can depend on order, and every emitted format renders either NaN as {@code NaN}.)
 *
 * <p>Sealed across <em>both</em> document lanes: the generic one in {@link PlacedScene} and the UML
 * sequence one in {@link PlacedSequenceScene}. A new kind of drawable in either therefore cannot
 * compile without stating how the viewBox grows to hold it, and both lanes fold through the one
 * {@link SvgDocument#measure} rather than each deriving its own bounds. The guarantee is per
 * drawable kind rather than per attribute, so each kind is responsible for the <em>decoration</em>
 * of its own geometry too: the half of every stroke that lies outside the shape it outlines, the
 * marker viewport an edge's ends carry, the arc a line jump bulges into, and the text a frame
 * paints in its own header. Each of those was once left out, and each was a shape clipped flat at
 * the viewBox edge under a policy whose {@code margin} — schema minimum 0 — had no padding to
 * absorb it.
 *
 * <p>{@code sealed} permits only same-package implementations in an unnamed module, which is why
 * the sequence lane's renderer and its placed drawables live in this package rather than in {@code
 * node.uml} where the UML node shapes and decorators live.
 */
sealed interface PlacedElement
    permits PlacedScene.PlacedGroup,
        PlacedScene.PlacedEdge,
        PlacedScene.PlacedNode,
        PlacedSequenceScene.PlacedInteraction,
        PlacedSequenceScene.PlacedCombinedFragment,
        PlacedSequenceScene.PlacedLifelineHead,
        PlacedSequenceScene.PlacedLifelineStem,
        PlacedSequenceScene.PlacedExecution,
        PlacedSequenceScene.PlacedGate,
        PlacedSequenceScene.PlacedMessage,
        PlacedSequenceScene.PlacedDeleteMarker {

  void contributeBounds(SvgBounds bounds);

  static void includeBox(SvgBounds bounds, LabelBox box) {
    bounds.includeRect(box.minX(), box.minY(), box.width(), box.height());
  }

  /**
   * Includes a stroked shape's geometry <em>and</em> the outer half of the stroke that outlines it.
   * SVG centres a stroke on the path, so a shape measured at its geometry is measured half a stroke
   * short on every side — invisible under a generous margin, and a shaved edge at the {@code
   * margin: 0} the render-policy schema allows.
   */
  static void includeStroked(
      SvgBounds bounds, double x, double y, double width, double height, double strokeWidth) {
    double half = strokeWidth / 2.0;
    bounds.includeRect(x - half, y - half, width + strokeWidth, height + strokeWidth);
  }

  /**
   * Includes a stroked circle centred on a node's box. Two kinds draw one — the ArchiMate junction
   * and the UML sequence gate — and both floor the radius at a minimum that can exceed the node's
   * own half-extent, so the circle overhangs the box. Shared rather than written twice: the two
   * copies of this arithmetic were identical, and two identical derivations that must stay
   * identical is the drift this whole seam exists to remove.
   */
  static void includeCircleOnNode(
      SvgBounds bounds,
      double nodeX,
      double nodeY,
      double nodeWidth,
      double nodeHeight,
      double radius,
      double strokeWidth) {
    double reach = radius + strokeWidth / 2.0;
    double centerX = nodeX + nodeWidth / 2.0;
    double centerY = nodeY + nodeHeight / 2.0;
    bounds.includeRect(centerX - reach, centerY - reach, 2.0 * reach, 2.0 * reach);
  }
}

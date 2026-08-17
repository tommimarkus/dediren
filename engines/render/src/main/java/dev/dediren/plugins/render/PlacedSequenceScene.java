package dev.dediren.plugins.render;

import static dev.dediren.plugins.render.PlacedElement.includeBox;
import static dev.dediren.plugins.render.PlacedElement.includeCircleOnNode;
import static dev.dediren.plugins.render.PlacedElement.includeStroked;
import static dev.dediren.plugins.render.svg.EdgeRenderer.markerInkBoxes;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.plugins.render.node.NodeLabelPlacement;
import dev.dediren.plugins.render.node.NodeLabels;
import dev.dediren.plugins.render.style.ResolvedEdgeStyle;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.style.ResolvedStyle;
import dev.dediren.plugins.render.svg.LabelBox;
import dev.dediren.plugins.render.svg.SvgBounds;
import java.util.ArrayList;
import java.util.List;

/**
 * One UML sequence document after every placement decision is made and before a single byte is
 * written — the sequence lane's {@link PlacedScene}, and held to the same bargain: {@code
 * UmlSequenceRenderer.resolve} decides, {@link SvgDocument#measure} folds {@link #elements()} into
 * the viewBox, {@code UmlSequenceRenderer.emit} writes the very same objects.
 *
 * <p><strong>Why a second scene rather than the same one.</strong> A sequence view is not a graph
 * of nodes and edges with different paint. Its interaction frames and combined-fragment frames are
 * render-synthesized rectangles no layout produced; its lifelines draw a head <em>and</em> a stem
 * that must be painted in two separate passes so no head sits under another lifeline's stem; its
 * operands are separators and guards inside a frame rather than drawables of their own. Forcing any
 * of that into {@link PlacedScene.PlacedNode} would mean a node whose geometry is not its layout
 * box — the exact drift the placed-scene seam exists to stop. What is shared is the part that must
 * be: one bounds type, one fold, and one document skeleton.
 *
 * <p><strong>Emission order is paint order.</strong> {@link #elements()} lists the kinds in the
 * order they are written, back to front: frames, then fragments, then lifeline heads, then the
 * stems that must run behind everything the interaction contains, then executions, gates, messages
 * and finally the destruction markers that sit on top of the message that caused them. The measure
 * fold does not care — it is order-independent — but the emitter does, and one list keeps a new
 * kind from being added to only one of the two.
 */
record PlacedSequenceScene(
    RenderPolicy policy,
    String viewId,
    ResolvedStyle base,
    List<PlacedInteraction> interactions,
    List<PlacedCombinedFragment> combinedFragments,
    List<PlacedLifelineHead> lifelineHeads,
    List<PlacedLifelineStem> lifelineStems,
    List<PlacedExecution> executions,
    List<PlacedGate> gates,
    List<PlacedMessage> messages,
    List<PlacedDeleteMarker> deleteMarkers) {

  PlacedSequenceScene {
    interactions = List.copyOf(interactions);
    combinedFragments = List.copyOf(combinedFragments);
    lifelineHeads = List.copyOf(lifelineHeads);
    lifelineStems = List.copyOf(lifelineStems);
    executions = List.copyOf(executions);
    gates = List.copyOf(gates);
    messages = List.copyOf(messages);
    deleteMarkers = List.copyOf(deleteMarkers);
  }

  /** Every drawable, in emission order. One accessor for both the measure fold and the emitter. */
  List<PlacedElement> elements() {
    List<PlacedElement> elements = new ArrayList<>();
    elements.addAll(interactions);
    elements.addAll(combinedFragments);
    elements.addAll(lifelineHeads);
    elements.addAll(lifelineStems);
    elements.addAll(executions);
    elements.addAll(gates);
    elements.addAll(messages);
    elements.addAll(deleteMarkers);
    return elements;
  }

  /**
   * An interaction frame: the enclosing rectangle, the pentagonal name tab in its top-left corner,
   * and the box that tab's text inks.
   *
   * <p>The tab is measured rather than assumed to be inside the frame: its width floors at 96 while
   * the frame's does not, so a narrow interaction has a tab that overhangs its own right edge.
   */
  record PlacedInteraction(
      LaidOutNode node,
      ResolvedNodeStyle style,
      SequenceFrame frame,
      double titleWidth,
      double titleHeight,
      LabelBox titleBox)
      implements PlacedElement {

    @Override
    public void contributeBounds(SvgBounds bounds) {
      includeStroked(
          bounds, frame.x(), frame.y(), frame.width(), frame.height(), style.strokeWidth());
      includeStroked(bounds, frame.x(), frame.y(), titleWidth, titleHeight, style.strokeWidth());
      if (titleBox != null) {
        includeBox(bounds, titleBox);
      }
    }
  }

  /**
   * A combined fragment: its frame, the operator tab, and the operand chrome inside it.
   *
   * <p>The separators are not folded in on their own. Each spans exactly the frame's own width and
   * its y is clamped into the frame's interior, so the frame's stroked box already covers every one
   * of them — unlike the guards, whose text starts inside the frame but is not clipped by it.
   */
  record PlacedCombinedFragment(
      String id,
      String operator,
      ResolvedNodeStyle style,
      SequenceFrame frame,
      double tabWidth,
      double tabHeight,
      LabelBox operatorBox,
      List<PlacedOperandSeparator> separators,
      List<PlacedOperandGuard> guards)
      implements PlacedElement {

    PlacedCombinedFragment {
      separators = List.copyOf(separators);
      guards = List.copyOf(guards);
    }

    @Override
    public void contributeBounds(SvgBounds bounds) {
      includeStroked(
          bounds, frame.x(), frame.y(), frame.width(), frame.height(), style.strokeWidth());
      includeStroked(bounds, frame.x(), frame.y(), tabWidth, tabHeight, style.strokeWidth());
      if (operatorBox != null) {
        includeBox(bounds, operatorBox);
      }
      for (PlacedOperandGuard guard : guards) {
        includeBox(bounds, guard.box());
      }
    }
  }

  /** The horizontal rule that separates one interaction operand from the next. */
  record PlacedOperandSeparator(String operandId, double y) {}

  /** An operand's guard text, already bracketed, and the box its glyphs ink. */
  record PlacedOperandGuard(
      String operandId, String guard, String text, double x, double y, LabelBox box) {}

  /** A lifeline's head box and its wrapped, centred name. */
  record PlacedLifelineHead(
      LaidOutNode node, ResolvedNodeStyle style, double rx, NodeLabelPlacement label)
      implements PlacedElement {

    @Override
    public void contributeBounds(SvgBounds bounds) {
      includeStroked(bounds, node.x(), node.y(), node.width(), node.height(), style.strokeWidth());
      for (LabelBox box : NodeLabels.nodeLabelBoxes(label)) {
        includeBox(bounds, box);
      }
    }
  }

  /**
   * The dashed stem under a lifeline head, from the head's bottom edge down to the bottom of the
   * interaction that owns it.
   */
  record PlacedLifelineStem(
      String lifelineId, ResolvedNodeStyle style, double x, double top, double bottom)
      implements PlacedElement {

    @Override
    public void contributeBounds(SvgBounds bounds) {
      includeStroked(bounds, x, top, 0.0, bottom - top, style.strokeWidth());
    }
  }

  /** An execution specification's activation bar. */
  record PlacedExecution(LaidOutNode node, ResolvedNodeStyle style, double rx)
      implements PlacedElement {

    @Override
    public void contributeBounds(SvgBounds bounds) {
      includeStroked(bounds, node.x(), node.y(), node.width(), node.height(), style.strokeWidth());
    }
  }

  /**
   * A gate's circle. Its radius floors at 4, so on a small enough gate node the circle is wider
   * than the box layout gave it — both are folded in, for the same reason the ArchiMate junction
   * folds both.
   */
  record PlacedGate(LaidOutNode node, ResolvedNodeStyle style, double radius)
      implements PlacedElement {

    @Override
    public void contributeBounds(SvgBounds bounds) {
      includeStroked(bounds, node.x(), node.y(), node.width(), node.height(), style.strokeWidth());
      includeCircleOnNode(
          bounds, node.x(), node.y(), node.width(), node.height(), radius, style.strokeWidth());
    }
  }

  /**
   * A message: its route, the notation-resolved edge style the arrowhead and dash come from, and
   * its placed label.
   *
   * <p>{@code style} is the same {@link ResolvedEdgeStyle} the emitter writes from, so the marker
   * viewport measured here is the marker that is drawn.
   */
  record PlacedMessage(
      LaidOutEdge edge, ResolvedEdgeStyle style, String messageSort, PlacedMessageLabel label)
      implements PlacedElement {

    @Override
    public void contributeBounds(SvgBounds bounds) {
      // Half the stroke lies outside the route on either side, and stroke-linecap="round" puts the
      // same half beyond each end of it, so one square per vertex covers the whole ribbon.
      for (Point point : edge.points()) {
        includeStroked(bounds, point.x(), point.y(), 0.0, 0.0, style.strokeWidth());
      }
      for (LabelBox markerBox : markerInkBoxes(edge, style)) {
        includeBox(bounds, markerBox);
      }
      if (label != null) {
        includeBox(bounds, label.box());
      }
    }
  }

  /** A message label: where its {@code <text>} is anchored and the box its glyphs ink. */
  record PlacedMessageLabel(String text, double x, double y, LabelBox box) {}

  /**
   * The X drawn where a {@code deleteMessage} destroys a lifeline. Its stroke width floors at 1.5
   * independently of the message's, so it is carried rather than taken from the edge style.
   */
  record PlacedDeleteMarker(
      String id, ResolvedEdgeStyle style, double x, double y, double size, double strokeWidth)
      implements PlacedElement {

    @Override
    public void contributeBounds(SvgBounds bounds) {
      includeStroked(bounds, x - size, y - size, 2.0 * size, 2.0 * size, strokeWidth);
    }
  }
}

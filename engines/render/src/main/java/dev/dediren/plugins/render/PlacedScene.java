package dev.dediren.plugins.render;

import static dev.dediren.plugins.render.node.NodeLabels.nodeLabelBoxes;
import static dev.dediren.plugins.render.svg.EdgeRenderer.markerInkBoxes;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.plugins.render.node.NodeLabelPlacement;
import dev.dediren.plugins.render.style.ResolvedEdgeStyle;
import dev.dediren.plugins.render.style.ResolvedGroupStyle;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.style.ResolvedStyle;
import dev.dediren.plugins.render.svg.EdgeEndAdornments;
import dev.dediren.plugins.render.svg.EdgeLabel;
import dev.dediren.plugins.render.svg.LabelBox;
import dev.dediren.plugins.render.svg.LineJump;
import dev.dediren.plugins.render.svg.MaskedLineJump;
import dev.dediren.plugins.render.svg.SvgBounds;
import java.util.ArrayList;
import java.util.List;

/**
 * One SVG document after every placement decision is made and before a single byte is written:
 * resolved styles, placed labels, derived end adornments, line jumps and the backdrops that mask
 * them.
 *
 * <p><strong>Both {@code SvgDocument.measure} and {@code SvgDocument.emit} consume one scene, and
 * that is the point.</strong> The viewBox has to be known before the root start-tag closes, so the
 * bounds pass used to run first and independently: it re-resolved every style, re-ran edge-label
 * obstacle avoidance, and re-derived the adornments, all in the hope of reaching the same answers
 * the emission pass would reach a moment later. Every drift bug in this package descends from that
 * — bounds that centred a label the emitter left-aligned, adornments drawn outside a viewBox that
 * never heard of them. Re-splitting the two, or letting either recompute anything from the layout
 * result, reopens the whole bug class.
 *
 * <p>Every drawable is a {@link PlacedElement}, whose one method is its bounds contribution. A new
 * kind of drawable therefore cannot compile without stating how the viewBox grows to hold it. The
 * guarantee is per drawable kind rather than per attribute, so each kind is responsible for the
 * <em>decoration</em> of its own geometry too: the half of every stroke that lies outside the shape
 * it outlines, the marker viewport an edge's ends carry, the arc a line jump bulges into, and the
 * title a group paints above itself. Each of those was once left out, and each was a shape clipped
 * flat at the viewBox edge under a policy whose {@code margin} — schema minimum 0 — had no padding
 * to absorb it.
 *
 * <p>Lives one package up from {@code svg} because it is node-aware — it holds {@link
 * NodeLabelPlacement} and folds {@code NodeLabels.nodeLabelBoxes}. Putting it in {@code svg} would
 * drag {@code node.*} into that package, which is a leaf on purpose (see {@code svg.Geometry}).
 */
record PlacedScene(
    RenderPolicy policy,
    String viewId,
    ResolvedStyle base,
    List<PlacedGroup> groups,
    List<PlacedEdge> edges,
    List<PlacedNode> nodes) {

  PlacedScene {
    groups = List.copyOf(groups);
    edges = List.copyOf(edges);
    nodes = List.copyOf(nodes);
  }

  /**
   * Every drawable, in emission order. One accessor for both halves: the measure fold and the
   * emission walk see the same elements, so a fourth kind is added to both at once or to neither.
   */
  List<PlacedElement> elements() {
    List<PlacedElement> elements = new ArrayList<>(groups.size() + edges.size() + nodes.size());
    elements.addAll(groups);
    elements.addAll(edges);
    elements.addAll(nodes);
    return elements;
  }

  /**
   * Something the document draws.
   *
   * <p>{@link #contributeBounds} grows the accumulator by the ink this element accounts for, using
   * the same {@link SvgBounds} calls with the same arguments the pass it replaced used — no
   * rewritten arithmetic, so no new rounding. It writes into the accumulator rather than returning
   * boxes because that type is already a mutable min/max fold, and because that fold is
   * order-independent for finite coordinates the measure walk may visit elements in emission order
   * even though the old pass visited them in another. (The fold is order-independent for infinities
   * too; only the sign bit of a NaN can depend on order, and every emitted format renders either
   * NaN as {@code NaN}.)
   */
  sealed interface PlacedElement permits PlacedGroup, PlacedEdge, PlacedNode {
    void contributeBounds(SvgBounds bounds);
  }

  /**
   * A group rect with its resolved style, the metadata selector its data-attributes carry, and its
   * placed title.
   */
  record PlacedGroup(
      LaidOutGroup group,
      ResolvedGroupStyle style,
      RenderMetadataSelector selector,
      PlacedGroupTitle title)
      implements PlacedElement {

    @Override
    public void contributeBounds(SvgBounds bounds) {
      includeStroked(
          bounds, group.x(), group.y(), group.width(), group.height(), style.strokeWidth());
      if (title.visibleBox() != null) {
        includeBox(bounds, title.visibleBox());
      }
    }
  }

  /** An edge route with its resolved style, its masked jumps, and everything placed along it. */
  record PlacedEdge(
      LaidOutEdge edge,
      ResolvedEdgeStyle style,
      List<MaskedLineJump> lineJumps,
      PlacedEdgeLabel label,
      List<PlacedAdornment> adornments)
      implements PlacedElement {

    PlacedEdge {
      lineJumps = List.copyOf(lineJumps);
      adornments = List.copyOf(adornments);
    }

    /** The jumps alone: the route path bends around them, the mask fill is the mask's business. */
    List<LineJump> routeJumps() {
      return lineJumps.stream().map(MaskedLineJump::jump).toList();
    }

    /** The adornments alone, in placement order, for the markup writer. */
    List<EdgeEndAdornments.Adornment> routeAdornments() {
      return adornments.stream().map(PlacedAdornment::adornment).toList();
    }

    @Override
    public void contributeBounds(SvgBounds bounds) {
      // Half the stroke lies outside the route on either side, and stroke-linecap="round" puts the
      // same half beyond each end of it, so one square per vertex covers the whole ribbon.
      double half = style.strokeWidth() / 2.0;
      for (Point point : edge.points()) {
        includeStroked(bounds, point.x(), point.y(), 0.0, 0.0, style.strokeWidth());
      }
      for (MaskedLineJump masked : lineJumps) {
        // The jump's arc, not its mask. The mask is a backdrop-coloured stroke, so where a jump is
        // the outermost ink it is necessarily outside every group and painting the page's own
        // background colour onto the page background — clipping it changes nothing visible.
        includeBox(bounds, masked.jump().routeInkBox().expanded(half, half));
      }
      for (LabelBox markerBox : markerInkBoxes(edge, style)) {
        includeBox(bounds, markerBox);
      }
      if (label != null) {
        includeBox(bounds, label.visibleBox());
      }
      for (PlacedAdornment adornment : adornments) {
        includeBox(bounds, adornment.visibleBox());
      }
    }
  }

  /**
   * A node box with its resolved style, its metadata selector, its plain label placement — {@code
   * null} when this node draws no plain label (a glyph-only pseudostate, or a UML decorator that
   * supplies its own name) — and its junction radius, {@code null} unless this node draws the
   * ArchiMate junction circle in place of a box shape.
   */
  record PlacedNode(
      LaidOutNode node,
      ResolvedNodeStyle style,
      RenderMetadataSelector selector,
      NodeLabelPlacement label,
      Double junctionRadius)
      implements PlacedElement {

    /**
     * The boxes this node's plain label inks; empty when it draws none.
     *
     * <p>Derived rather than stored: unlike the edge boxes below, nothing in the placement pass
     * needed these, so there is no earlier result to reuse — and the emitter never computes them at
     * all, so there is no second copy to drift from. Exposed because the edge-label obstacle set
     * needs the same boxes: a node label placed outside its box (junctions, UML compact controls)
     * is ink an edge label must route around, and {@code Geometry.nodeObstacleBoxes} only knows
     * about node rects.
     */
    List<LabelBox> labelBoxes() {
      return label == null ? List.of() : nodeLabelBoxes(label);
    }

    @Override
    public void contributeBounds(SvgBounds bounds) {
      includeStroked(bounds, node.x(), node.y(), node.width(), node.height(), style.strokeWidth());
      if (junctionRadius != null) {
        // A junction draws a circle instead of the box, and its radius floors at 4.0, so on a small
        // enough node the circle is the wider of the two. The box stays in the fold anyway: it is
        // the rectangle every edge routes to, and dropping it would shrink bounds rather than close
        // the gap this contribution exists to close.
        double reach = junctionRadius + style.strokeWidth() / 2.0;
        double centerX = node.x() + node.width() / 2.0;
        double centerY = node.y() + node.height() / 2.0;
        bounds.includeRect(centerX - reach, centerY - reach, 2.0 * reach, 2.0 * reach);
      }
      for (LabelBox labelBox : labelBoxes()) {
        includeBox(bounds, labelBox);
      }
    }
  }

  /**
   * A placed group title: where its {@code <text>} is anchored and the box its glyphs ink.
   *
   * <p>{@code anchor} is the {@code text-anchor} attribute value the emitter writes, carried
   * verbatim including the {@code null} that means "leave the attribute off and take SVG's default
   * of start". Normalizing it here would put a second opinion about the anchor next to the one that
   * is emitted, and an anchor the bounds pass guessed at is what {@code label_align} drift was.
   *
   * <p>{@code visibleBox} is {@code null} when the title has no glyphs: the (empty) {@code <text>}
   * element is still emitted, but a title with nothing in it must not grow the viewBox by a line
   * box's worth of nothing.
   */
  record PlacedGroupTitle(double x, double y, String anchor, LabelBox visibleBox) {}

  /**
   * A placed edge label: where it sits, the text it carries, and the box it inks.
   *
   * <p>The box is carried rather than re-derived because the placement pass already computed it —
   * it is what the next edge's obstacle avoidance had to see.
   */
  record PlacedEdgeLabel(EdgeLabel label, String text, LabelBox visibleBox) {}

  /** A placed UML association-end adornment and the box it inks, carried for the same reason. */
  record PlacedAdornment(EdgeEndAdornments.Adornment adornment, LabelBox visibleBox) {}

  private static void includeBox(SvgBounds bounds, LabelBox box) {
    bounds.includeRect(box.minX(), box.minY(), box.width(), box.height());
  }

  /**
   * Includes a stroked shape's geometry <em>and</em> the outer half of the stroke that outlines it.
   * SVG centres a stroke on the path, so a shape measured at its geometry is measured half a stroke
   * short on every side — invisible under a generous margin, and a shaved edge at the {@code
   * margin: 0} the render-policy schema allows.
   */
  private static void includeStroked(
      SvgBounds bounds, double x, double y, double width, double height, double strokeWidth) {
    double half = strokeWidth / 2.0;
    bounds.includeRect(x - half, y - half, width + strokeWidth, height + strokeWidth);
  }
}

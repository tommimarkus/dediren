package dev.dediren.plugins.render;

import static dev.dediren.plugins.render.node.NodeLabels.nodeLabelBoxes;

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
 * guarantee is per drawable kind rather than per attribute: ink a drawable knowingly leaves out of
 * its own contribution (group titles, stroke half-widths, marker extents) is still its own
 * business, and today several do.
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

  /** A group rect with its resolved style and the metadata selector its data-attributes carry. */
  record PlacedGroup(LaidOutGroup group, ResolvedGroupStyle style, RenderMetadataSelector selector)
      implements PlacedElement {

    @Override
    public void contributeBounds(SvgBounds bounds) {
      bounds.includeRect(group.x(), group.y(), group.width(), group.height());
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
      for (Point point : edge.points()) {
        bounds.includePoint(point.x(), point.y());
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
   * A node box with its resolved style, its metadata selector, and its plain label placement —
   * {@code null} when this node draws no plain label (a glyph-only pseudostate, or a UML decorator
   * that supplies its own name).
   */
  record PlacedNode(
      LaidOutNode node,
      ResolvedNodeStyle style,
      RenderMetadataSelector selector,
      NodeLabelPlacement label)
      implements PlacedElement {

    @Override
    public void contributeBounds(SvgBounds bounds) {
      bounds.includeRect(node.x(), node.y(), node.width(), node.height());
      if (label == null) {
        return;
      }
      // Derived here rather than stored: unlike the edge boxes below, nothing in the placement
      // pass needed these, so there is no earlier result to reuse — and the emitter never
      // computes them at all, so there is no second copy to drift from.
      for (LabelBox labelBox : nodeLabelBoxes(label)) {
        includeBox(bounds, labelBox);
      }
    }
  }

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
}

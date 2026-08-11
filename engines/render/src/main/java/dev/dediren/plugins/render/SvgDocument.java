package dev.dediren.plugins.render;

import static dev.dediren.plugins.render.node.NodeLabels.nodeLabel;
import static dev.dediren.plugins.render.node.NodeLabels.placeNodeLabel;
import static dev.dediren.plugins.render.node.NodeShapeSupport.archimateJunctionRadius;
import static dev.dediren.plugins.render.node.NodeShapeSupport.decoratorName;
import static dev.dediren.plugins.render.node.NodeShapeSupport.isArchimateCutCornerRectangle;
import static dev.dediren.plugins.render.node.NodeShapeSupport.isArchimateJunction;
import static dev.dediren.plugins.render.node.NodeShapeSupport.isArchimateRoundedRectangle;
import static dev.dediren.plugins.render.node.NodeShapeSupport.isUmlDecorator;
import static dev.dediren.plugins.render.node.NodeShapeSupport.shouldRenderPlainNodeLabel;
import static dev.dediren.plugins.render.node.archimate.ArchimateIcons.archimateNodeDecorator;
import static dev.dediren.plugins.render.node.archimate.ArchimateShapes.archimateCutCornerShape;
import static dev.dediren.plugins.render.node.generic.GenericShapes.genericNodeShape;
import static dev.dediren.plugins.render.node.uml.UmlDecorators.umlNodeDecorator;
import static dev.dediren.plugins.render.node.uml.UmlShapes.umlNodeShape;
import static dev.dediren.plugins.render.svg.EdgeRenderer.backdropFillAt;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabel;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabelFontSize;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabelVisibleBox;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeMarker;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgePath;
import static dev.dediren.plugins.render.svg.EdgeRenderer.lineJumpMasks;
import static dev.dediren.plugins.render.svg.EdgeRenderer.lineJumps;
import static dev.dediren.plugins.render.svg.Geometry.labelBox;
import static dev.dediren.plugins.render.svg.Geometry.labelObstacleBoxesForEdge;
import static dev.dediren.plugins.render.svg.Svg.dashArrayValue;
import static dev.dediren.plugins.render.svg.Svg.f1;
import static dev.dediren.plugins.render.svg.Svg.opacity;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.SvgGradient;
import dev.dediren.contracts.render.SvgGradientStop;
import dev.dediren.contracts.render.SvgGradientType;
import dev.dediren.contracts.render.SvgLabelAlign;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.plugins.render.PlacedScene.PlacedAdornment;
import dev.dediren.plugins.render.PlacedScene.PlacedEdge;
import dev.dediren.plugins.render.PlacedScene.PlacedEdgeLabel;
import dev.dediren.plugins.render.PlacedScene.PlacedElement;
import dev.dediren.plugins.render.PlacedScene.PlacedGroup;
import dev.dediren.plugins.render.PlacedScene.PlacedGroupTitle;
import dev.dediren.plugins.render.PlacedScene.PlacedNode;
import dev.dediren.plugins.render.node.uml.UmlSequenceRenderer;
import dev.dediren.plugins.render.style.ResolvedEdgeStyle;
import dev.dediren.plugins.render.style.ResolvedGroupStyle;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.style.ResolvedStyle;
import dev.dediren.plugins.render.style.StyleResolver;
import dev.dediren.plugins.render.svg.EdgeEndAdornments;
import dev.dediren.plugins.render.svg.EdgeLabel;
import dev.dediren.plugins.render.svg.LabelBox;
import dev.dediren.plugins.render.svg.LineJump;
import dev.dediren.plugins.render.svg.MaskedLineJump;
import dev.dediren.plugins.render.svg.SvgAccessibleName;
import dev.dediren.plugins.render.svg.SvgBounds;
import dev.dediren.plugins.render.svg.SvgIds;
import dev.dediren.plugins.render.svg.SvgWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SvgDocument {

  // ArchiMate grouping borders fall back to this fine dash. A user dash (dash_pattern/line_style)
  // must win in both the group lane and the node lane: a shape's own stroke-dasharray attribute
  // beats the user dash riding the wrapper <g>, so this fallback may only be emitted when the
  // resolved style carries no dash of its own.
  private static final String ARCHIMATE_GROUPING_DASH = "3 2";

  // Group title placement: how far a start/end-aligned title is inset from the group's own edge,
  // and how far its baseline sits below the top edge once the label's own size is allowed for.
  private static final double GROUP_TITLE_INSET = 8.0;
  private static final double GROUP_TITLE_BASELINE_GAP = 4.0;

  private SvgDocument() {}

  /**
   * Renders one document in three passes: {@link #resolve} decides, {@link #measure} folds those
   * decisions into the viewBox, {@link #emit} writes them. The split exists because the viewBox
   * must be known before the root start-tag closes — see {@link PlacedScene} for why measure and
   * emit must keep consuming the one scene rather than each deriving its own.
   */
  public static String renderSvg(
      LayoutResult result, RenderMetadata metadata, RenderPolicy policy) {
    if (UmlSequenceRenderer.isSequence(metadata)) {
      return new UmlSequenceRenderer(result, metadata, policy).render();
    }
    PlacedScene scene = resolve(result, metadata, policy);
    return emit(new SvgWriter(), scene, measure(scene));
  }

  /**
   * Resolves styles and places everything the document draws, once.
   *
   * <p>The edge walk is order-dependent and must stay in layout order twice over: {@code lineJumps}
   * sees only the edges routed before it, and {@code placedLabelBoxes} is the running obstacle set
   * each edge's label avoids. Both used to be rebuilt identically by the bounds pass, and
   * "identically" was maintained by inspection.
   *
   * <p>Nodes are placed <em>before</em> edges even though they are emitted after them, because a
   * node label is an obstacle the edge labels have to see. Junction labels sit below the circle and
   * UML compact-control labels diagonally up-left, both outside the node rect that {@code
   * Geometry.nodeObstacleBoxes} contributes — so an edge label placed "clear" could be printed on
   * top of one. Emission order is unchanged: {@code PlacedScene.elements} owns that, not this walk.
   */
  static PlacedScene resolve(LayoutResult result, RenderMetadata metadata, RenderPolicy policy) {
    ResolvedStyle base = StyleResolver.baseStyle(policy);
    List<PlacedGroup> groups = new ArrayList<>();
    for (LaidOutGroup group : result.groups()) {
      ResolvedGroupStyle groupStyle = StyleResolver.groupStyle(policy, metadata, group.id(), base);
      groups.add(
          new PlacedGroup(
              group,
              groupStyle,
              metadata == null ? null : metadata.groups().get(group.id()),
              placeGroupTitle(group, groupStyle)));
    }
    List<PlacedNode> nodes = new ArrayList<>();
    List<LabelBox> placedLabelBoxes = new ArrayList<>();
    for (LaidOutNode node : result.nodes()) {
      ResolvedNodeStyle style = StyleResolver.nodeStyle(policy, metadata, node.id(), base);
      PlacedNode placed =
          new PlacedNode(
              node,
              style,
              metadata == null ? null : metadata.nodes().get(node.id()),
              shouldRenderPlainNodeLabel(node, style.decorator())
                  ? placeNodeLabel(node, style, base.fontSize())
                  : null,
              isArchimateJunction(style.decorator()) ? archimateJunctionRadius(node, style) : null);
      nodes.add(placed);
      placedLabelBoxes.addAll(placed.labelBoxes());
    }
    List<PlacedEdge> edges = new ArrayList<>();
    List<LaidOutEdge> routedEdges = new ArrayList<>();
    for (int edgeIndex = 0; edgeIndex < result.edges().size(); edgeIndex++) {
      LaidOutEdge edge = result.edges().get(edgeIndex);
      ResolvedEdgeStyle style = StyleResolver.edgeStyle(policy, metadata, edge.id(), base);
      List<MaskedLineJump> maskedJumps = new ArrayList<>();
      for (LineJump jump : lineJumps(edge, routedEdges)) {
        maskedJumps.add(
            new MaskedLineJump(
                jump, backdropFillAt(jump.x(), jump.y(), result, metadata, policy, base)));
      }
      PlacedEdgeLabel label = null;
      if (edge.label() != null && !edge.label().isEmpty()) {
        EdgeLabel placedLabel =
            edgeLabel(
                edge,
                style,
                labelObstacleBoxesForEdge(result, edgeIndex, placedLabelBoxes),
                edgeLabelFontSize(base.fontSize()));
        LabelBox visibleBox = edgeLabelVisibleBox(placedLabel, style.labelPresentation());
        label = new PlacedEdgeLabel(placedLabel, edge.label(), visibleBox);
        placedLabelBoxes.add(visibleBox);
      }
      // Same profile-gated call the emission pass used to make, so markup and bounds can never
      // disagree about whether an edge has end adornments — now because there is only one call.
      List<PlacedAdornment> adornments = new ArrayList<>();
      for (EdgeEndAdornments.Adornment adornment :
          EdgeEndAdornments.adornments(edge, metadata, base.fontSize())) {
        LabelBox visibleBox = EdgeEndAdornments.visibleBox(adornment, style);
        adornments.add(new PlacedAdornment(adornment, visibleBox));
        placedLabelBoxes.add(visibleBox);
      }
      edges.add(new PlacedEdge(edge, style, maskedJumps, label, adornments));
      routedEdges.add(edge);
    }
    return new PlacedScene(policy, result.viewId(), base, groups, edges, nodes);
  }

  /**
   * Places a group's title: the one decision about where that {@code <text>} goes and which anchor
   * it takes, so the box the viewBox grows to hold and the attributes written for it cannot part
   * company. A {@code label_size} may be anything up to 96, which is a title wide enough to run a
   * long way off the right of its own group.
   */
  private static PlacedGroupTitle placeGroupTitle(LaidOutGroup group, ResolvedGroupStyle style) {
    double x = group.x() + GROUP_TITLE_INSET;
    String anchor = null;
    if (style.labelAlign() == SvgLabelAlign.MIDDLE) {
      x = group.x() + group.width() / 2.0;
      anchor = "middle";
    } else if (style.labelAlign() == SvgLabelAlign.END) {
      x = group.x() + group.width() - GROUP_TITLE_INSET;
      anchor = "end";
    }
    double y = group.y() + style.labelSize() + GROUP_TITLE_BASELINE_GAP;
    String text = group.label();
    if (text == null || text.isEmpty()) {
      return new PlacedGroupTitle(x, y, anchor, null);
    }
    // Measured against the anchor that is emitted, resolving the null that means "attribute
    // omitted" to the SVG default it stands for — rather than assuming a centred title, which is
    // the shape the label_align drift in the node lane took.
    LabelBox visibleBox =
        labelBox(x, y, anchor == null ? "start" : anchor, text, style.labelSize());
    return new PlacedGroupTitle(x, y, anchor, visibleBox);
  }

  /**
   * The document bounds: every drawable's own contribution, then the empty-layout page fallback,
   * then the policy margins. Node-aware, which is why it and {@link PlacedScene} live here with
   * their caller rather than in the svg package's Geometry — that package stays a leaf with no
   * {@code node.*} imports.
   */
  static SvgBounds measure(PlacedScene scene) {
    SvgBounds bounds = SvgBounds.empty();
    for (PlacedElement element : scene.elements()) {
      element.contributeBounds(bounds);
    }
    if (bounds.isEmpty()) {
      bounds.includeRect(0.0, 0.0, scene.policy().page().width(), scene.policy().page().height());
    }
    return bounds.padded(scene.policy());
  }

  /** Writes the placed scene. No placement decision is left to make here — see {@link #resolve}. */
  static String emit(SvgWriter w, PlacedScene scene, SvgBounds bounds) {
    ResolvedStyle base = scene.base();
    // One minter per document: every id and every url(#…) in this SVG goes through it, so a
    // layout id that is duplicated or not a legal identifier can neither collide nor break its
    // own reference. See SvgIds for why the transform is a no-op on well-formed ids.
    SvgIds ids = new SvgIds();
    w.start("svg")
        .attr("xmlns", "http://www.w3.org/2000/svg")
        .attr("role", "img")
        .attr("width", String.format(Locale.ROOT, "%.0f", bounds.width()))
        .attr("height", String.format(Locale.ROOT, "%.0f", bounds.height()))
        .attr(
            "viewBox",
            String.format(
                Locale.ROOT,
                "%.1f %.1f %.1f %.1f",
                bounds.minX(),
                bounds.minY(),
                bounds.width(),
                bounds.height()));
    SvgAccessibleName.rootLanguage(w, scene.policy());
    SvgAccessibleName.markup(w, scene.policy(), scene.viewId());
    w.empty("rect")
        .attr("x", f1(bounds.minX()))
        .attr("y", f1(bounds.minY()))
        .attr("width", f1(bounds.width()))
        .attr("height", f1(bounds.height()))
        .attr("fill", base.backgroundFill())
        .attrIf("fill-opacity", opacity(base.backgroundFillOpacity()));
    w.start("g")
        .attr("font-family", base.fontFamily())
        .attr("font-size", styleNumber(base.fontSize()))
        .attrIf("font-weight", enumValue(base.fontWeight()))
        .attrIf("font-style", enumValue(base.fontStyle()));
    for (PlacedGroup placed : scene.groups()) {
      LaidOutGroup group = placed.group();
      ResolvedGroupStyle style = placed.style();
      w.start("g").attr("data-dediren-group-id", group.id());
      RenderMetadataSelector selector = placed.selector();
      if (selector != null) {
        w.attr("data-dediren-group-type", selector.type())
            .attr("data-dediren-group-source-id", selector.sourceId());
      }
      ResolvedGroupStyle rectStyle = style;
      if (style.fillGradient() != null) {
        String gradientId = ids.mint("group-fill-" + group.id());
        gradientElement(w, gradientId, style.fillGradient());
        rectStyle = style.withFill(ids.reference(gradientId));
      }
      String groupDashValue = dashArrayValue(style.lineStyle(), style.dashPattern(), "6 4");
      if (groupDashValue.isEmpty() && style.decorator() == SvgNodeDecorator.ARCHIMATE_GROUPING) {
        groupDashValue = ARCHIMATE_GROUPING_DASH;
      }
      w.empty("rect")
          .attr("x", f1(group.x()))
          .attr("y", f1(group.y()))
          .attr("width", f1(group.width()))
          .attr("height", f1(group.height()))
          .attr("rx", styleNumber(style.rx()))
          .attr("fill", rectStyle.fill())
          .attr("stroke", style.stroke())
          .attr("stroke-width", styleNumber(style.strokeWidth()))
          .attrIf("stroke-dasharray", groupDashValue.isEmpty() ? null : groupDashValue)
          .attrIf("fill-opacity", opacity(style.fillOpacity()))
          .attrIf("stroke-opacity", opacity(style.strokeOpacity()));
      groupDecorator(w, group, style);
      PlacedGroupTitle title = placed.title();
      w.start("text")
          .attr("x", f1(title.x()))
          .attr("y", f1(title.y()))
          .attrIf("text-anchor", title.anchor())
          .attr("fill", style.labelFill())
          .attr("font-size", styleNumber(style.labelSize()))
          .attrIf("font-family", style.fontFamily())
          .attrIf("font-weight", enumValue(style.fontWeight()))
          .attrIf("font-style", enumValue(style.fontStyle()))
          .attrIf("fill-opacity", opacity(style.labelOpacity()))
          .text(group.label())
          .end();
      w.end();
    }
    for (PlacedEdge placed : scene.edges()) {
      LaidOutEdge edge = placed.edge();
      ResolvedEdgeStyle style = placed.style();
      w.start("g").attr("data-dediren-edge-id", edge.id());
      String startMarkerId = edgeMarker(w, ids, edge, style, "start");
      String endMarkerId = edgeMarker(w, ids, edge, style, "end");
      lineJumpMasks(w, edge.id(), placed.lineJumps());
      edgePath(
          w,
          edge,
          style,
          placed.routeJumps(),
          ids.reference(startMarkerId),
          ids.reference(endMarkerId));
      PlacedEdgeLabel label = placed.label();
      if (label != null) {
        edgeLabel(
            w,
            label.label(),
            label.text(),
            style,
            base.backgroundFill(),
            edgeLabelFontSize(base.fontSize()));
      }
      if (!placed.adornments().isEmpty()) {
        EdgeEndAdornments.markup(
            w, placed.routeAdornments(), style, base.backgroundFill(), base.fontSize());
      }
      w.end();
    }
    for (PlacedNode placed : scene.nodes()) {
      LaidOutNode node = placed.node();
      ResolvedNodeStyle style = placed.style();
      RenderMetadataSelector selector = placed.selector();
      w.start("g").attr("data-dediren-node-id", node.id());
      ResolvedNodeStyle shapeStyle = style;
      if (style.fillGradient() != null) {
        String gradientId = ids.mint("node-fill-" + node.id());
        gradientElement(w, gradientId, style.fillGradient());
        shapeStyle = style.withFill(ids.reference(gradientId));
      }
      String wrapDash = dashArrayValue(shapeStyle.lineStyle(), shapeStyle.dashPattern(), "6 4");
      boolean wrap =
          shapeStyle.fillOpacity() != null
              || shapeStyle.strokeOpacity() != null
              || !wrapDash.isEmpty();
      if (wrap) {
        w.start("g")
            .attrIf("fill-opacity", opacity(shapeStyle.fillOpacity()))
            .attrIf("stroke-opacity", opacity(shapeStyle.strokeOpacity()))
            .attrIf("stroke-dasharray", wrapDash.isEmpty() ? null : wrapDash);
      }
      nodeShape(w, node, shapeStyle, selector, placed.junctionRadius());
      if (wrap) {
        w.end();
      }
      nodeDecorator(w, node, style, selector);
      if (placed.label() != null) {
        nodeLabel(w, placed.label(), style);
      }
      w.end();
    }
    w.end();
    w.end();
    return w.finish() + "\n";
  }

  private static void groupDecorator(SvgWriter w, LaidOutGroup group, ResolvedGroupStyle style) {
    if (style.decorator() != SvgNodeDecorator.ARCHIMATE_GROUPING) {
      return;
    }
    double size = 22.0;
    double x = group.x() + group.width() - size - 6.0;
    double y = group.y() + 9.0;
    w.start("g")
        .attr("data-dediren-group-decorator", "archimate_grouping")
        .attr("data-dediren-icon-kind", "grouping")
        .attr("data-dediren-icon-size", "22");
    w.empty("path")
        .attr("data-dediren-icon-part", "grouping")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
                x,
                y,
                x + size,
                y,
                x + size,
                y + size * 0.72,
                x,
                y + size * 0.72))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
    w.end();
  }

  // Inline gradient definition, referenced by fill="url(#id)". SVG gradient ids are
  // document-global,
  // so this can live inside the element's group. Linear coordinates run over the shape's bounding
  // box (objectBoundingBox), derived deterministically from the angle (0 = left→right, 90 = top→
  // bottom). Reuses the inline-id precedent from edge markers rather than a shared <defs>.
  private static void gradientElement(SvgWriter w, String id, SvgGradient gradient) {
    if (gradient.type() == SvgGradientType.RADIAL) {
      w.start("radialGradient").attr("id", id);
    } else {
      double radians = Math.toRadians(gradient.angle() == null ? 0.0 : gradient.angle());
      double cos = Math.cos(radians);
      double sin = Math.sin(radians);
      w.start("linearGradient")
          .attr("id", id)
          .attr("x1", String.format(Locale.ROOT, "%.4f", 0.5 - 0.5 * cos))
          .attr("y1", String.format(Locale.ROOT, "%.4f", 0.5 - 0.5 * sin))
          .attr("x2", String.format(Locale.ROOT, "%.4f", 0.5 + 0.5 * cos))
          .attr("y2", String.format(Locale.ROOT, "%.4f", 0.5 + 0.5 * sin));
    }
    for (SvgGradientStop stop : gradient.stops()) {
      w.empty("stop")
          .attr("offset", styleNumber(stop.offset()))
          .attr("stop-color", stop.color())
          .attrIf("stop-opacity", opacity(stop.opacity()));
    }
    w.end();
  }

  // The junction radius arrives from the placed scene rather than being recomputed here: the
  // viewBox has to grow around a circle that can be wider than the node box, and a radius derived
  // twice is a radius that can be measured at one size and drawn at another.
  private static void nodeShape(
      SvgWriter w,
      LaidOutNode node,
      ResolvedNodeStyle style,
      RenderMetadataSelector selector,
      Double junctionRadius) {
    SvgNodeDecorator decorator = style.decorator();
    if (junctionRadius != null) {
      double radius = junctionRadius;
      String fill =
          decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION ? style.stroke() : style.fill();
      w.empty("circle")
          .attr("data-dediren-node-shape", decoratorName(decorator))
          .attr("cx", f1(node.x() + node.width() / 2.0))
          .attr("cy", f1(node.y() + node.height() / 2.0))
          .attr("r", f1(radius))
          .attr("fill", fill)
          .attr("stroke", style.stroke())
          .attr("stroke-width", styleNumber(style.strokeWidth()));
      return;
    }
    if (decorator != null && isUmlDecorator(decorator)) {
      umlNodeShape(w, node, style, decorator, selector);
      return;
    }
    if (decorator == null && style.shape() != null) {
      genericNodeShape(w, node, style);
      return;
    }
    String shapeName = "archimate_rectangle";
    double rx = 0.0;
    String dashArray = null;
    if (decorator == null) {
      rx = style.rx();
    } else if (isArchimateCutCornerRectangle(decorator)) {
      archimateCutCornerShape(w, node, style);
      return;
    } else if (isArchimateRoundedRectangle(decorator)) {
      rx = Math.max(1.0, style.rx());
      shapeName = "archimate_rounded_rectangle";
    } else if (decorator == SvgNodeDecorator.ARCHIMATE_GROUPING) {
      String userDash = dashArrayValue(style.lineStyle(), style.dashPattern(), "6 4");
      dashArray = userDash.isEmpty() ? ARCHIMATE_GROUPING_DASH : userDash;
    }
    w.empty("rect")
        .attr("data-dediren-node-shape", shapeName)
        .attr("x", f1(node.x()))
        .attr("y", f1(node.y()))
        .attr("width", f1(node.width()))
        .attr("height", f1(node.height()))
        .attr("rx", styleNumber(rx))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()))
        .attrIf("stroke-dasharray", dashArray);
  }

  private static void nodeDecorator(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, RenderMetadataSelector selector) {
    SvgNodeDecorator decorator = style.decorator();
    if (decorator == null || isArchimateJunction(decorator)) {
      return;
    }
    if (isUmlDecorator(decorator)) {
      umlNodeDecorator(w, node, style, decorator, selector);
      return;
    }
    archimateNodeDecorator(w, node, style, decorator);
  }

  private static String enumValue(Enum<?> value) {
    return value == null ? null : value.name().toLowerCase(Locale.ROOT);
  }
}

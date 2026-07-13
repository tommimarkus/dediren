package dev.dediren.plugins.render.style;

import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.SvgBackgroundStyle;
import dev.dediren.contracts.render.SvgEdgeLabelHorizontalPosition;
import dev.dediren.contracts.render.SvgEdgeLabelHorizontalSide;
import dev.dediren.contracts.render.SvgEdgeLabelPresentation;
import dev.dediren.contracts.render.SvgEdgeLabelVerticalPosition;
import dev.dediren.contracts.render.SvgEdgeLabelVerticalSide;
import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgEdgeMarkerEnd;
import dev.dediren.contracts.render.SvgEdgeStyle;
import dev.dediren.contracts.render.SvgFontStyle;
import dev.dediren.contracts.render.SvgGroupStyle;
import dev.dediren.contracts.render.SvgNodeStyle;
import dev.dediren.contracts.render.SvgStylePolicy;
import java.util.Optional;

public final class StyleResolver {

  private StyleResolver() {}

  /** The product's house palette: every diagram except UML sequence starts here. */
  private static final ResolvedNodeStyle DEFAULT_NODE =
      new ResolvedNodeStyle(
          "#f8fafc", "#334155", 1.5, 6.0, "#0f172a", null, null, null, null, null, null, null, null,
          null, null, null, null);

  private static final ResolvedEdgeStyle DEFAULT_EDGE =
      new ResolvedEdgeStyle(
          "#64748b",
          1.5,
          "#374151",
          SvgEdgeLineStyle.SOLID,
          SvgEdgeMarkerEnd.NONE,
          SvgEdgeMarkerEnd.FILLED_ARROW,
          SvgEdgeLabelHorizontalPosition.NEAR_START,
          SvgEdgeLabelHorizontalSide.AUTO,
          SvgEdgeLabelVerticalPosition.CENTER,
          SvgEdgeLabelVerticalSide.LEFT,
          SvgEdgeLabelPresentation.OUTLINE,
          null,
          null,
          null);

  private static final ResolvedGroupStyle DEFAULT_GROUP =
      new ResolvedGroupStyle(
          "#eff6ff", "#93c5fd", 1.0, 8.0, "#1e3a8a", 12.0, null, null, null, null, null, null, null,
          null, null, null, null);

  // UML sequence diagrams start from a plain black-on-white base rather than the house palette:
  // square corners (rx 0), black strokes, 1.25px. This divergence is deliberate — square lifeline
  // heads and execution bars are what the notation draws — but it used to be spelled out inside
  // UmlSequenceRenderer's own private merge chain, so the two palettes could drift without anyone
  // noticing they were different things. Stated here, once, beside the palette it diverges from.
  private static final ResolvedNodeStyle SEQUENCE_DEFAULT_NODE =
      new ResolvedNodeStyle(
          "#ffffff", "#000000", 1.25, 0.0, "#000000", null, null, null, null, null, null, null,
          null, null, null, null, null);

  private static final ResolvedEdgeStyle SEQUENCE_DEFAULT_EDGE =
      new ResolvedEdgeStyle(
          "#000000",
          1.25,
          "#000000",
          SvgEdgeLineStyle.SOLID,
          SvgEdgeMarkerEnd.NONE,
          SvgEdgeMarkerEnd.FILLED_ARROW,
          SvgEdgeLabelHorizontalPosition.NEAR_START,
          SvgEdgeLabelHorizontalSide.AUTO,
          SvgEdgeLabelVerticalPosition.CENTER,
          SvgEdgeLabelVerticalSide.LEFT,
          SvgEdgeLabelPresentation.OUTLINE,
          null,
          null,
          null);

  public static ResolvedStyle baseStyle(RenderPolicy policy) {
    return baseStyle(policy, DEFAULT_NODE, DEFAULT_EDGE, DEFAULT_GROUP);
  }

  /** The base a UML sequence render resolves against. See {@link #SEQUENCE_DEFAULT_NODE}. */
  public static ResolvedStyle sequenceBaseStyle(RenderPolicy policy) {
    return baseStyle(policy, SEQUENCE_DEFAULT_NODE, SEQUENCE_DEFAULT_EDGE, DEFAULT_GROUP);
  }

  private static ResolvedStyle baseStyle(
      RenderPolicy policy,
      ResolvedNodeStyle defaultNode,
      ResolvedEdgeStyle defaultEdge,
      ResolvedGroupStyle defaultGroup) {
    SvgStylePolicy style = policy.style();
    return new ResolvedStyle(
        Optional.ofNullable(style)
            .map(SvgStylePolicy::background)
            .map(SvgBackgroundStyle::fill)
            .orElse("#ffffff"),
        Optional.ofNullable(style)
            .map(SvgStylePolicy::background)
            .map(SvgBackgroundStyle::fillOpacity)
            .orElse(null),
        Optional.ofNullable(style)
            .map(SvgStylePolicy::font)
            .map(SvgFontStyle::family)
            .orElse("Inter, Arial, sans-serif"),
        Optional.ofNullable(style).map(SvgStylePolicy::font).map(SvgFontStyle::size).orElse(14.0),
        Optional.ofNullable(style).map(SvgStylePolicy::font).map(SvgFontStyle::weight).orElse(null),
        Optional.ofNullable(style).map(SvgStylePolicy::font).map(SvgFontStyle::style).orElse(null),
        mergeNodeStyle(defaultNode, style == null ? null : style.node()),
        mergeEdgeStyle(defaultEdge, style == null ? null : style.edge()),
        mergeGroupStyle(defaultGroup, style == null ? null : style.group()));
  }

  public static ResolvedNodeStyle nodeStyle(
      RenderPolicy policy, RenderMetadata metadata, String nodeId, ResolvedStyle base) {
    SvgStylePolicy style = policy.style();
    SvgNodeStyle typeStyle = null;
    if (style != null && metadata != null && metadata.nodes().containsKey(nodeId)) {
      typeStyle = style.nodeTypeOverrides().get(metadata.nodes().get(nodeId).type());
    }
    ResolvedNodeStyle resolved = mergeNodeStyle(base.node(), typeStyle);
    return mergeNodeStyle(resolved, style == null ? null : style.nodeOverrides().get(nodeId));
  }

  public static ResolvedEdgeStyle edgeStyle(
      RenderPolicy policy, RenderMetadata metadata, String edgeId, ResolvedStyle base) {
    SvgStylePolicy style = policy.style();
    SvgEdgeStyle typeStyle = null;
    if (style != null && metadata != null && metadata.edges().containsKey(edgeId)) {
      typeStyle = style.edgeTypeOverrides().get(metadata.edges().get(edgeId).type());
    }
    ResolvedEdgeStyle resolved = mergeEdgeStyle(base.edge(), typeStyle);
    return mergeEdgeStyle(resolved, style == null ? null : style.edgeOverrides().get(edgeId));
  }

  public static ResolvedGroupStyle groupStyle(
      RenderPolicy policy, RenderMetadata metadata, String groupId, ResolvedStyle base) {
    SvgStylePolicy style = policy.style();
    SvgGroupStyle typeStyle = null;
    if (style != null && metadata != null && metadata.groups().containsKey(groupId)) {
      typeStyle = style.groupTypeOverrides().get(metadata.groups().get(groupId).type());
    }
    ResolvedGroupStyle resolved = mergeGroupStyle(base.group(), typeStyle);
    return mergeGroupStyle(resolved, style == null ? null : style.groupOverrides().get(groupId));
  }

  static ResolvedNodeStyle mergeNodeStyle(ResolvedNodeStyle base, SvgNodeStyle override) {
    if (override == null) {
      return base;
    }
    return new ResolvedNodeStyle(
        override.fill() == null ? base.fill() : override.fill(),
        override.stroke() == null ? base.stroke() : override.stroke(),
        override.strokeWidth() == null ? base.strokeWidth() : override.strokeWidth(),
        override.rx() == null ? base.rx() : override.rx(),
        override.labelFill() == null ? base.labelFill() : override.labelFill(),
        override.decorator() == null ? base.decorator() : override.decorator(),
        override.shape() == null ? base.shape() : override.shape(),
        override.fillOpacity() == null ? base.fillOpacity() : override.fillOpacity(),
        override.strokeOpacity() == null ? base.strokeOpacity() : override.strokeOpacity(),
        override.lineStyle() == null ? base.lineStyle() : override.lineStyle(),
        override.dashPattern() == null ? base.dashPattern() : override.dashPattern(),
        override.fontWeight() == null ? base.fontWeight() : override.fontWeight(),
        override.fontStyle() == null ? base.fontStyle() : override.fontStyle(),
        override.fontFamily() == null ? base.fontFamily() : override.fontFamily(),
        override.labelAlign() == null ? base.labelAlign() : override.labelAlign(),
        override.labelOpacity() == null ? base.labelOpacity() : override.labelOpacity(),
        override.fillGradient() == null ? base.fillGradient() : override.fillGradient());
  }

  static ResolvedEdgeStyle mergeEdgeStyle(ResolvedEdgeStyle base, SvgEdgeStyle override) {
    if (override == null) {
      return base;
    }
    return new ResolvedEdgeStyle(
        override.stroke() == null ? base.stroke() : override.stroke(),
        override.strokeWidth() == null ? base.strokeWidth() : override.strokeWidth(),
        override.labelFill() == null ? base.labelFill() : override.labelFill(),
        override.lineStyle() == null ? base.lineStyle() : override.lineStyle(),
        override.markerStart() == null ? base.markerStart() : override.markerStart(),
        override.markerEnd() == null ? base.markerEnd() : override.markerEnd(),
        override.labelHorizontalPosition() == null
            ? base.labelHorizontalPosition()
            : override.labelHorizontalPosition(),
        override.labelHorizontalSide() == null
            ? base.labelHorizontalSide()
            : override.labelHorizontalSide(),
        override.labelVerticalPosition() == null
            ? base.labelVerticalPosition()
            : override.labelVerticalPosition(),
        override.labelVerticalSide() == null
            ? base.labelVerticalSide()
            : override.labelVerticalSide(),
        override.labelPresentation() == null
            ? base.labelPresentation()
            : override.labelPresentation(),
        override.strokeOpacity() == null ? base.strokeOpacity() : override.strokeOpacity(),
        override.dashPattern() == null ? base.dashPattern() : override.dashPattern(),
        override.labelOpacity() == null ? base.labelOpacity() : override.labelOpacity());
  }

  static ResolvedGroupStyle mergeGroupStyle(ResolvedGroupStyle base, SvgGroupStyle override) {
    if (override == null) {
      return base;
    }
    return new ResolvedGroupStyle(
        override.fill() == null ? base.fill() : override.fill(),
        override.stroke() == null ? base.stroke() : override.stroke(),
        override.strokeWidth() == null ? base.strokeWidth() : override.strokeWidth(),
        override.rx() == null ? base.rx() : override.rx(),
        override.labelFill() == null ? base.labelFill() : override.labelFill(),
        override.labelSize() == null ? base.labelSize() : override.labelSize(),
        override.decorator() == null ? base.decorator() : override.decorator(),
        override.fillOpacity() == null ? base.fillOpacity() : override.fillOpacity(),
        override.strokeOpacity() == null ? base.strokeOpacity() : override.strokeOpacity(),
        override.lineStyle() == null ? base.lineStyle() : override.lineStyle(),
        override.dashPattern() == null ? base.dashPattern() : override.dashPattern(),
        override.fontWeight() == null ? base.fontWeight() : override.fontWeight(),
        override.fontStyle() == null ? base.fontStyle() : override.fontStyle(),
        override.fontFamily() == null ? base.fontFamily() : override.fontFamily(),
        override.labelAlign() == null ? base.labelAlign() : override.labelAlign(),
        override.labelOpacity() == null ? base.labelOpacity() : override.labelOpacity(),
        override.fillGradient() == null ? base.fillGradient() : override.fillGradient());
  }
}

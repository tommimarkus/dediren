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

  public static ResolvedStyle baseStyle(RenderPolicy policy) {
    SvgStylePolicy style = policy.style();
    var defaultNode =
        new ResolvedNodeStyle("#f8fafc", "#334155", 1.5, 6.0, "#0f172a", null, null, null, null);
    var defaultEdge =
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
            null);
    var defaultGroup =
        new ResolvedGroupStyle("#eff6ff", "#93c5fd", 1.0, 8.0, "#1e3a8a", 12.0, null, null, null);
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
        override.strokeOpacity() == null ? base.strokeOpacity() : override.strokeOpacity());
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
        override.strokeOpacity() == null ? base.strokeOpacity() : override.strokeOpacity());
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
        override.strokeOpacity() == null ? base.strokeOpacity() : override.strokeOpacity());
  }
}

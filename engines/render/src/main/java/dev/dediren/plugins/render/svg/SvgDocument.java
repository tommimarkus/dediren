package dev.dediren.plugins.render.svg;

import static dev.dediren.plugins.render.node.NodeLabels.nodeLabel;
import static dev.dediren.plugins.render.node.NodeShapeSupport.archimateJunctionRadius;
import static dev.dediren.plugins.render.node.NodeShapeSupport.decoratorName;
import static dev.dediren.plugins.render.node.NodeShapeSupport.isArchimateCutCornerRectangle;
import static dev.dediren.plugins.render.node.NodeShapeSupport.isArchimateRoundedRectangle;
import static dev.dediren.plugins.render.node.NodeShapeSupport.isUmlDecorator;
import static dev.dediren.plugins.render.node.NodeShapeSupport.shouldRenderPlainNodeLabel;
import static dev.dediren.plugins.render.node.archimate.ArchimateIcons.archimateNodeDecorator;
import static dev.dediren.plugins.render.node.archimate.ArchimateShapes.archimateCutCornerShape;
import static dev.dediren.plugins.render.node.generic.GenericShapes.genericNodeShape;
import static dev.dediren.plugins.render.node.uml.UmlDecorators.umlNodeDecorator;
import static dev.dediren.plugins.render.node.uml.UmlShapes.umlNodeShape;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabel;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabelFontSize;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabelVisibleBox;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeMarker;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgePath;
import static dev.dediren.plugins.render.svg.EdgeRenderer.lineJumpMasks;
import static dev.dediren.plugins.render.svg.EdgeRenderer.lineJumps;
import static dev.dediren.plugins.render.svg.Geometry.labelObstacleBoxesForEdge;
import static dev.dediren.plugins.render.svg.Geometry.svgBounds;
import static dev.dediren.plugins.render.svg.Svg.dashArrayValue;
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
import dev.dediren.plugins.render.node.uml.UmlSequenceRenderer;
import dev.dediren.plugins.render.style.ResolvedEdgeStyle;
import dev.dediren.plugins.render.style.ResolvedGroupStyle;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.style.ResolvedStyle;
import dev.dediren.plugins.render.style.StyleResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SvgDocument {

  private SvgDocument() {}

  public static String renderSvg(
      LayoutResult result, RenderMetadata metadata, RenderPolicy policy) {
    if (UmlSequenceRenderer.isSequence(metadata)) {
      return new UmlSequenceRenderer(result, metadata, policy).render();
    }
    ResolvedStyle base = StyleResolver.baseStyle(policy);
    SvgBounds bounds = svgBounds(result, metadata, policy, base);
    SvgWriter w = new SvgWriter();
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
    SvgAccessibleName.markup(w, policy, result.viewId());
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
    for (LaidOutGroup group : result.groups()) {
      ResolvedGroupStyle style = StyleResolver.groupStyle(policy, metadata, group.id(), base);
      w.start("g").attr("data-dediren-group-id", group.id());
      RenderMetadataSelector selector = metadata == null ? null : metadata.groups().get(group.id());
      if (selector != null) {
        w.attr("data-dediren-group-type", selector.type())
            .attr("data-dediren-group-source-id", selector.sourceId());
      }
      ResolvedGroupStyle rectStyle = style;
      if (style.fillGradient() != null) {
        String gradientId = "group-fill-" + group.id();
        gradientElement(w, gradientId, style.fillGradient());
        rectStyle = style.withFill("url(#" + gradientId + ")");
      }
      String groupDashValue = dashArrayValue(style.lineStyle(), style.dashPattern(), "6 4");
      if (groupDashValue.isEmpty() && style.decorator() == SvgNodeDecorator.ARCHIMATE_GROUPING) {
        groupDashValue = "3 2";
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
      double groupLabelX = group.x() + 8.0;
      String groupLabelAnchor = null;
      if (style.labelAlign() == SvgLabelAlign.MIDDLE) {
        groupLabelX = group.x() + group.width() / 2.0;
        groupLabelAnchor = "middle";
      } else if (style.labelAlign() == SvgLabelAlign.END) {
        groupLabelX = group.x() + group.width() - 8.0;
        groupLabelAnchor = "end";
      }
      w.start("text")
          .attr("x", f1(groupLabelX))
          .attr("y", f1(group.y() + style.labelSize() + 4.0))
          .attrIf("text-anchor", groupLabelAnchor)
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
    List<LaidOutEdge> renderedEdges = new ArrayList<>();
    List<LabelBox> placedLabelBoxes = new ArrayList<>();
    for (int edgeIndex = 0; edgeIndex < result.edges().size(); edgeIndex++) {
      LaidOutEdge edge = result.edges().get(edgeIndex);
      ResolvedEdgeStyle style = StyleResolver.edgeStyle(policy, metadata, edge.id(), base);
      List<LineJump> lineJumps = lineJumps(edge, renderedEdges);
      w.start("g").attr("data-dediren-edge-id", edge.id());
      edgeMarker(w, edge, style, "start");
      edgeMarker(w, edge, style, "end");
      lineJumpMasks(w, edge, lineJumps, result, metadata, policy, base);
      edgePath(w, edge, style, lineJumps);
      if (edge.label() != null && !edge.label().isEmpty()) {
        double edgeLabelFontSize = edgeLabelFontSize(base.fontSize());
        EdgeLabel label =
            edgeLabel(
                edge,
                style,
                labelObstacleBoxesForEdge(result, edgeIndex, placedLabelBoxes),
                edgeLabelFontSize);
        edgeLabel(w, label, edge.label(), style, base.backgroundFill(), edgeLabelFontSize);
        placedLabelBoxes.add(edgeLabelVisibleBox(label, style.labelPresentation()));
      }
      RenderMetadataSelector edgeSelector =
          metadata == null ? null : metadata.edges().get(edge.id());
      List<EdgeEndAdornments.Adornment> endAdornments =
          EdgeEndAdornments.adornments(edge, edgeSelector, base.fontSize());
      if (!endAdornments.isEmpty()) {
        EdgeEndAdornments.markup(w, endAdornments, style, base.backgroundFill(), base.fontSize());
        for (EdgeEndAdornments.Adornment adornment : endAdornments) {
          placedLabelBoxes.add(EdgeEndAdornments.visibleBox(adornment, style));
        }
      }
      w.end();
      renderedEdges.add(edge);
    }
    for (LaidOutNode node : result.nodes()) {
      ResolvedNodeStyle style = StyleResolver.nodeStyle(policy, metadata, node.id(), base);
      RenderMetadataSelector selector = metadata == null ? null : metadata.nodes().get(node.id());
      w.start("g").attr("data-dediren-node-id", node.id());
      ResolvedNodeStyle shapeStyle = style;
      if (style.fillGradient() != null) {
        String gradientId = "node-fill-" + node.id();
        gradientElement(w, gradientId, style.fillGradient());
        shapeStyle = style.withFill("url(#" + gradientId + ")");
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
      nodeShape(w, node, shapeStyle, selector);
      if (wrap) {
        w.end();
      }
      nodeDecorator(w, node, style, selector);
      if (shouldRenderPlainNodeLabel(node, style.decorator())) {
        nodeLabel(w, node, style, base.fontSize());
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

  private static void nodeShape(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, RenderMetadataSelector selector) {
    SvgNodeDecorator decorator = style.decorator();
    if (decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_OR_JUNCTION) {
      double radius = archimateJunctionRadius(node, style);
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
      dashArray = "3 2";
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
    if (decorator == null
        || decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_OR_JUNCTION) {
      return;
    }
    if (isUmlDecorator(decorator)) {
      umlNodeDecorator(w, node, style, decorator, selector);
      return;
    }
    archimateNodeDecorator(w, node, style, decorator);
  }

  private static String f1(double value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }

  private static String opacity(Double value) {
    return value == null ? null : styleNumber(value);
  }

  private static String enumValue(Enum<?> value) {
    return value == null ? null : value.name().toLowerCase(Locale.ROOT);
  }
}

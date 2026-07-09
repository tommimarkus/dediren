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
import static dev.dediren.plugins.render.svg.Svg.attr;
import static dev.dediren.plugins.render.svg.Svg.dashArrayAttr;
import static dev.dediren.plugins.render.svg.Svg.dashArrayValue;
import static dev.dediren.plugins.render.svg.Svg.opacityAttr;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;
import static dev.dediren.plugins.render.svg.Svg.text;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.SvgInteractionStyle;
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

  private static final String DEFAULT_HIGHLIGHT_STROKE = "#1f6feb";
  private static final double DEFAULT_HIGHLIGHT_STROKE_WIDTH = 3.0;

  public static String renderSvg(
      LayoutResult result, RenderMetadata metadata, RenderPolicy policy) {
    if (UmlSequenceRenderer.isSequence(metadata)) {
      return new UmlSequenceRenderer(result, metadata, policy).render();
    }
    boolean interactive = !"none".equals(interactiveMode(policy));
    ResolvedStyle base = StyleResolver.baseStyle(policy);
    SvgBounds bounds = svgBounds(result, metadata, policy, base);
    StringBuilder svg = new StringBuilder();
    svg.append(
        String.format(
            Locale.ROOT,
            "<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" width=\"%.0f\" height=\"%.0f\" viewBox=\"%.1f %.1f %.1f %.1f\">",
            bounds.width(),
            bounds.height(),
            bounds.minX(),
            bounds.minY(),
            bounds.width(),
            bounds.height()));
    svg.append(SvgAccessibleName.markup(policy, result.viewId()));
    svg.append(
        String.format(
            Locale.ROOT,
            "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\"%s/>",
            bounds.minX(),
            bounds.minY(),
            bounds.width(),
            bounds.height(),
            attr(base.backgroundFill()),
            opacityAttr("fill-opacity", base.backgroundFillOpacity())));
    if (interactive) {
      svg.append(interactionStyleBlock(policy));
    }
    svg.append("<g font-family=\"")
        .append(attr(base.fontFamily()))
        .append("\" font-size=\"")
        .append(styleNumber(base.fontSize()))
        .append("\">");
    for (LaidOutGroup group : result.groups()) {
      ResolvedGroupStyle style = StyleResolver.groupStyle(policy, metadata, group.id(), base);
      svg.append("<g data-dediren-group-id=\"").append(attr(group.id())).append("\"");
      RenderMetadataSelector selector = metadata == null ? null : metadata.groups().get(group.id());
      if (selector != null) {
        svg.append(" data-dediren-group-type=\"")
            .append(attr(selector.type()))
            .append("\" data-dediren-group-source-id=\"")
            .append(attr(selector.sourceId()))
            .append("\"");
      }
      svg.append(">");
      String groupDashValue = dashArrayValue(style.lineStyle(), style.dashPattern(), "6 4");
      if (groupDashValue.isEmpty() && style.decorator() == SvgNodeDecorator.ARCHIMATE_GROUPING) {
        groupDashValue = "3 2";
      }
      String groupDashArray =
          groupDashValue.isEmpty() ? "" : " stroke-dasharray=\"" + groupDashValue + "\"";
      String groupExtra =
          groupDashArray
              + opacityAttr("fill-opacity", style.fillOpacity())
              + opacityAttr("stroke-opacity", style.strokeOpacity());
      svg.append(
          String.format(
              Locale.ROOT,
              "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"%s/>",
              group.x(),
              group.y(),
              group.width(),
              group.height(),
              styleNumber(style.rx()),
              attr(style.fill()),
              attr(style.stroke()),
              styleNumber(style.strokeWidth()),
              groupExtra));
      svg.append(groupDecorator(group, style));
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"%s\">%s</text>",
              group.x() + 8.0,
              group.y() + style.labelSize() + 4.0,
              attr(style.labelFill()),
              styleNumber(style.labelSize()),
              text(group.label())));
      svg.append("</g>");
    }
    List<LaidOutEdge> renderedEdges = new ArrayList<>();
    List<LabelBox> placedLabelBoxes = new ArrayList<>();
    for (int edgeIndex = 0; edgeIndex < result.edges().size(); edgeIndex++) {
      LaidOutEdge edge = result.edges().get(edgeIndex);
      ResolvedEdgeStyle style = StyleResolver.edgeStyle(policy, metadata, edge.id(), base);
      List<LineJump> lineJumps = lineJumps(edge, renderedEdges);
      svg.append("<g data-dediren-edge-id=\"").append(attr(edge.id())).append("\"");
      if (interactive) {
        svg.append(" data-dediren-edge-source=\"")
            .append(attr(edge.source()))
            .append("\" data-dediren-edge-target=\"")
            .append(attr(edge.target()))
            .append("\"");
      }
      svg.append(">");
      svg.append(edgeMarker(edge, style, "start"));
      svg.append(edgeMarker(edge, style, "end"));
      svg.append(lineJumpMasks(edge, lineJumps, result, metadata, policy, base));
      svg.append(edgePath(edge, style, lineJumps));
      if (edge.label() != null && !edge.label().isEmpty()) {
        double edgeLabelFontSize = edgeLabelFontSize(base.fontSize());
        EdgeLabel label =
            edgeLabel(
                edge,
                style,
                labelObstacleBoxesForEdge(result, edgeIndex, placedLabelBoxes),
                edgeLabelFontSize);
        svg.append(edgeLabel(label, edge.label(), style, base.backgroundFill(), edgeLabelFontSize));
        placedLabelBoxes.add(edgeLabelVisibleBox(label, style.labelPresentation()));
      }
      RenderMetadataSelector edgeSelector =
          metadata == null ? null : metadata.edges().get(edge.id());
      List<EdgeEndAdornments.Adornment> endAdornments =
          EdgeEndAdornments.adornments(edge, edgeSelector, base.fontSize());
      if (!endAdornments.isEmpty()) {
        svg.append(
            EdgeEndAdornments.markup(endAdornments, style, base.backgroundFill(), base.fontSize()));
        for (EdgeEndAdornments.Adornment adornment : endAdornments) {
          placedLabelBoxes.add(EdgeEndAdornments.visibleBox(adornment, style));
        }
      }
      svg.append("</g>");
      renderedEdges.add(edge);
    }
    for (LaidOutNode node : result.nodes()) {
      ResolvedNodeStyle style = StyleResolver.nodeStyle(policy, metadata, node.id(), base);
      RenderMetadataSelector selector = metadata == null ? null : metadata.nodes().get(node.id());
      svg.append("<g data-dediren-node-id=\"").append(attr(node.id())).append("\">");
      svg.append(withNodeStrokeStyle(nodeShape(node, style, selector), style));
      svg.append(nodeDecorator(node, style, selector));
      if (shouldRenderPlainNodeLabel(node, style.decorator())) {
        svg.append(nodeLabel(node, style, base.fontSize()));
      }
      svg.append("</g>");
    }
    svg.append("</g>");
    if (interactive) {
      svg.append(interactionScriptBlock());
    }
    svg.append("</svg>\n");
    return svg.toString();
  }

  public static String interactiveMode(RenderPolicy policy) {
    String mode = policy.interactive();
    // The documented default is "none": omitting the field yields a static SVG with no
    // interaction script. Interactivity is opt-in via an explicit "svg", "html", or "both".
    // This rule is uniform across every view; the UML sequence renderer additionally never
    // emits the highlight script even when interactivity is requested (nothing to highlight).
    return mode == null ? "none" : mode;
  }

  private static String interactionStyleBlock(RenderPolicy policy) {
    String stroke = DEFAULT_HIGHLIGHT_STROKE;
    double width = DEFAULT_HIGHLIGHT_STROKE_WIDTH;
    SvgInteractionStyle interaction = policy.style() == null ? null : policy.style().interaction();
    if (interaction != null) {
      if (interaction.highlightStroke() != null) {
        stroke = interaction.highlightStroke();
      }
      if (interaction.highlightStrokeWidth() != null) {
        width = interaction.highlightStrokeWidth();
      }
    }
    return "<style>g.dediren-edge-highlighted &gt; path{stroke:"
        + attr(stroke)
        + ";stroke-width:"
        + styleNumber(width)
        + ";}</style>";
  }

  private static String interactionScriptBlock() {
    return "<script>//<![CDATA[\n"
        + "(function(){\n"
        + "var root=document.currentScript&&document.currentScript.closest?document.currentScript.closest('svg'):null;\n"
        + "if(!root){root=document.querySelector('svg');}\n"
        + "if(!root){return;}\n"
        + "var selected=null;\n"
        + "function clear(){var hl=root.querySelectorAll('.dediren-edge-highlighted');for(var i=0;i<hl.length;i++){hl[i].classList.remove('dediren-edge-highlighted');}selected=null;}\n"
        + "function select(id){clear();var edges=root.querySelectorAll('[data-dediren-edge-source]');for(var i=0;i<edges.length;i++){var e=edges[i];if(e.getAttribute('data-dediren-edge-source')===id||e.getAttribute('data-dediren-edge-target')===id){e.classList.add('dediren-edge-highlighted');}}selected=id;}\n"
        + "root.addEventListener('click',function(ev){var t=ev.target;var n=t.closest?t.closest('[data-dediren-node-id]'):null;if(n){var id=n.getAttribute('data-dediren-node-id');if(id===selected){clear();}else{select(id);}}else{clear();}});\n"
        + "document.addEventListener('keydown',function(ev){if(ev.key==='Escape'){clear();}});\n"
        + "})();\n"
        + "//]]></script>";
  }

  public static List<RenderArtifact> buildArtifacts(String mode, String svg) {
    return switch (mode) {
      case "html" -> List.of(new RenderArtifact("html", htmlWrap(svg)));
      case "both" ->
          List.of(new RenderArtifact("svg", svg), new RenderArtifact("html", htmlWrap(svg)));
      default -> List.of(new RenderArtifact("svg", svg));
    };
  }

  private static String htmlWrap(String svg) {
    return "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
        + "<title>dediren diagram</title></head>\n<body>\n"
        + svg
        + "</body>\n</html>\n";
  }

  private static String groupDecorator(LaidOutGroup group, ResolvedGroupStyle style) {
    if (style.decorator() != SvgNodeDecorator.ARCHIMATE_GROUPING) {
      return "";
    }
    double size = 22.0;
    double x = group.x() + group.width() - size - 6.0;
    double y = group.y() + 9.0;
    String body =
        String.format(
            Locale.ROOT,
            "<path data-dediren-icon-part=\"grouping\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
            x,
            y,
            x + size,
            y,
            x + size,
            y + size * 0.72,
            x,
            y + size * 0.72,
            attr(style.fill()),
            attr(style.stroke()),
            styleNumber(style.strokeWidth()));
    return "<g data-dediren-group-decorator=\"archimate_grouping\" data-dediren-icon-kind=\"grouping\""
        + " data-dediren-icon-size=\"22\">"
        + body
        + "</g>";
  }

  // Applies node fill/stroke opacity and dash by wrapping the shape output in a group that carries
  // the attributes, so they reach every notation's shape (generic, ArchiMate, UML, junction) by
  // inheritance without editing each shape builder. Emitted only when an attribute is set, so
  // otherwise-styled renders stay byte-identical. The wrapper holds only the shape — the decorator
  // and label keep their own fills; a shape's own inline dash (grouping "3 2") still wins.
  private static String withNodeStrokeStyle(String shape, ResolvedNodeStyle style) {
    String attrs =
        opacityAttr("fill-opacity", style.fillOpacity())
            + opacityAttr("stroke-opacity", style.strokeOpacity())
            + dashArrayAttr(style.lineStyle(), style.dashPattern(), "6 4");
    return attrs.isEmpty() ? shape : "<g" + attrs + ">" + shape + "</g>";
  }

  private static String nodeShape(
      LaidOutNode node, ResolvedNodeStyle style, RenderMetadataSelector selector) {
    SvgNodeDecorator decorator = style.decorator();
    if (decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_OR_JUNCTION) {
      double radius = archimateJunctionRadius(node, style);
      String fill =
          decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION ? style.stroke() : style.fill();
      return String.format(
          Locale.ROOT,
          "<circle data-dediren-node-shape=\"%s\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
          decoratorName(decorator),
          node.x() + node.width() / 2.0,
          node.y() + node.height() / 2.0,
          radius,
          attr(fill),
          attr(style.stroke()),
          styleNumber(style.strokeWidth()));
    }
    if (decorator != null && isUmlDecorator(decorator)) {
      return umlNodeShape(node, style, decorator, selector);
    }
    if (decorator == null && style.shape() != null) {
      return genericNodeShape(node, style);
    }
    String shapeName = "archimate_rectangle";
    double rx = 0.0;
    String dashArray = "";
    if (decorator == null) {
      rx = style.rx();
    } else if (isArchimateCutCornerRectangle(decorator)) {
      return archimateCutCornerShape(node, style);
    } else if (isArchimateRoundedRectangle(decorator)) {
      rx = Math.max(1.0, style.rx());
      shapeName = "archimate_rounded_rectangle";
    } else if (decorator == SvgNodeDecorator.ARCHIMATE_GROUPING) {
      dashArray = " stroke-dasharray=\"3 2\"";
    }
    return String.format(
        Locale.ROOT,
        "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"%s/>",
        shapeName,
        node.x(),
        node.y(),
        node.width(),
        node.height(),
        styleNumber(rx),
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()),
        dashArray);
  }

  private static String nodeDecorator(
      LaidOutNode node, ResolvedNodeStyle style, RenderMetadataSelector selector) {
    SvgNodeDecorator decorator = style.decorator();
    if (decorator == null
        || decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_OR_JUNCTION) {
      return "";
    }
    if (isUmlDecorator(decorator)) {
      return umlNodeDecorator(node, style, decorator, selector);
    }
    return archimateNodeDecorator(node, style, decorator);
  }
}

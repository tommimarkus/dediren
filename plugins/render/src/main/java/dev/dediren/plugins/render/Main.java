package dev.dediren.plugins.render;

import static dev.dediren.plugins.render.Svg.attr;
import static dev.dediren.plugins.render.Svg.estimateTextWidth;
import static dev.dediren.plugins.render.Svg.labelNumber;
import static dev.dediren.plugins.render.Svg.styleNumber;
import static dev.dediren.plugins.render.Svg.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.archimate.ArchimateTypeValidationException;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.RenderResult;
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
import dev.dediren.contracts.render.SvgInteractionStyle;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.contracts.render.SvgNodeStyle;
import dev.dediren.contracts.render.SvgStylePolicy;
import dev.dediren.uml.UmlValidationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class Main {
  private static final double EDGE_LABEL_BACKGROUND_PADDING_X = 5.0;
  private static final double EDGE_LABEL_BACKGROUND_PADDING_Y = 3.0;
  private static final double EDGE_LABEL_BACKGROUND_RX = 3.0;
  private static final double EDGE_LABEL_FONT_SIZE_SCALE = 1.1;
  private static final int EDGE_LABEL_FONT_WEIGHT = 600;
  private static final double EDGE_LABEL_OUTLINE_WIDTH = 2.0;
  private static final double EDGE_ROUTE_LABEL_OBSTACLE_PADDING = 6.0;
  private static final double GROUP_BORDER_LABEL_OBSTACLE_PADDING = 4.0;
  private static final double GROUP_TITLE_LABEL_OBSTACLE_HEIGHT = 24.0;
  private static final double ARCHIMATE_ICON_SIZE = 22.0;
  // Top inset of the corner type decorator from the node box top. Must match the
  // y origin used in archimateNodeDecorator; it feeds the label's vertical reserve.
  private static final double ARCHIMATE_ICON_TOP_INSET = 9.0;
  // Must equal ARCHIMATE_LABEL_ICON_RESERVE in plugins/generic-graph
  // GenericGraphLayoutSizing: per-side room reserved so a centered label clears
  // the upper-right type icon. Enforced by dist-tool ArchimateLabelReserveConsistencyTest.
  private static final double ARCHIMATE_LABEL_ICON_RESERVE = 34.0;
  private static final double NODE_LABEL_VERTICAL_PADDING = 8.0;
  private static final double NODE_LABEL_MIN_FONT_SIZE = 9.0;

  private Main() {}

  public static String moduleName() {
    return "render";
  }

  public static void main(String[] args) throws Exception {
    int exitCode = execute(args, System.in, System.out, System.err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  public static PluginResult executeForTesting(String[] args, String stdin) throws Exception {
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    int exitCode =
        execute(
            args,
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8));
    return new PluginResult(
        exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
  }

  private static int execute(
      String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr) throws Exception {
    if (args.length > 0 && args[0].equals("capabilities")) {
      stdout.println(capabilitiesJson());
      return 0;
    }
    if (args.length > 0 && args[0].equals("render")) {
      return renderFromStdin(stdin, stdout);
    }
    stderr.println("expected command: capabilities or render");
    return 2;
  }

  private static String capabilitiesJson() throws IOException {
    ObjectNode root = JsonSupport.objectMapper().createObjectNode();
    root.put("plugin_protocol_version", ContractVersions.PLUGIN_PROTOCOL_VERSION);
    root.put("id", "render");
    root.set("capabilities", JsonSupport.objectMapper().createArrayNode().add("render"));
    root.putObject("runtime").put("artifact_kind", "svg");
    return JsonSupport.objectMapper().writeValueAsString(root);
  }

  private static int renderFromStdin(InputStream stdin, PrintStream stdout) throws Exception {
    RenderInput input =
        JsonSupport.objectMapper().readValue(stdin.readAllBytes(), RenderInput.class);
    try {
      RenderInputValidator.validate(input.layoutResult(), input.renderMetadata(), input.policy());
    } catch (RenderInputValidator.PolicyValidationException error) {
      return exitWithDiagnostic(
          stdout, "DEDIREN_SVG_POLICY_INVALID", error.getMessage(), error.path());
    } catch (RenderInputValidator.RenderMetadataUsageException error) {
      return exitWithDiagnostic(stdout, error.code(), error.getMessage(), error.path());
    } catch (ArchimateTypeValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
    } catch (UmlValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
    }

    String svg = renderSvg(input.layoutResult(), input.renderMetadata(), input.policy());
    List<RenderArtifact> artifacts =
        new ArrayList<>(buildArtifacts(interactiveMode(input.policy()), svg));
    if (input.policy().raster() != null) {
      try {
        artifacts.add(
            new RenderArtifact(
                "png", SvgRasterizer.toPngBase64(svg, input.policy().raster()), "base64"));
      } catch (SvgRasterizer.RasterizationException error) {
        return exitWithDiagnostic(
            stdout, "DEDIREN_SVG_RASTERIZE_FAILED", error.getMessage(), "raster");
      }
    }
    var result = new RenderResult(ContractVersions.RENDER_RESULT_SCHEMA_VERSION, artifacts);
    stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(result)));
    return 0;
  }

  private static int exitWithDiagnostic(
      PrintStream stdout, String code, String message, String path) throws IOException {
    var diagnostic = new Diagnostic(code, DiagnosticSeverity.ERROR, message, path);
    stdout.println(
        JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
    return 3;
  }

  private static String renderSvg(
      LayoutResult result, RenderMetadata metadata, RenderPolicy policy) {
    if (UmlSequenceRenderer.isSequence(metadata)) {
      return new UmlSequenceRenderer(result, metadata, policy).render();
    }
    boolean interactive = !"none".equals(interactiveMode(policy));
    ResolvedStyle base = baseStyle(policy);
    SvgBounds bounds = svgBounds(result, metadata, policy, base);
    StringBuilder svg = new StringBuilder();
    svg.append(
        String.format(
            Locale.ROOT,
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.0f\" height=\"%.0f\" viewBox=\"%.1f %.1f %.1f %.1f\">",
            bounds.width(),
            bounds.height(),
            bounds.minX(),
            bounds.minY(),
            bounds.width(),
            bounds.height()));
    svg.append(
        String.format(
            Locale.ROOT,
            "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\"/>",
            bounds.minX(),
            bounds.minY(),
            bounds.width(),
            bounds.height(),
            attr(base.backgroundFill())));
    if (interactive) {
      svg.append(interactionStyleBlock(policy));
    }
    svg.append("<g font-family=\"")
        .append(attr(base.fontFamily()))
        .append("\" font-size=\"")
        .append(styleNumber(base.fontSize()))
        .append("\">");
    for (LaidOutGroup group : result.groups()) {
      ResolvedGroupStyle style = groupStyle(policy, metadata, group.id(), base);
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
      String groupDashArray =
          style.decorator() == SvgNodeDecorator.ARCHIMATE_GROUPING
              ? " stroke-dasharray=\"3 2\""
              : "";
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
              groupDashArray));
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
      ResolvedEdgeStyle style = edgeStyle(policy, metadata, edge.id(), base);
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
      svg.append("</g>");
      renderedEdges.add(edge);
    }
    for (LaidOutNode node : result.nodes()) {
      ResolvedNodeStyle style = nodeStyle(policy, metadata, node.id(), base);
      RenderMetadataSelector selector = metadata == null ? null : metadata.nodes().get(node.id());
      svg.append("<g data-dediren-node-id=\"").append(attr(node.id())).append("\">");
      svg.append(nodeShape(node, style, selector));
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

  private static final String DEFAULT_HIGHLIGHT_STROKE = "#1f6feb";
  private static final double DEFAULT_HIGHLIGHT_STROKE_WIDTH = 3.0;

  private static String interactiveMode(RenderPolicy policy) {
    String mode = policy.interactive();
    return mode == null ? "svg" : mode;
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

  private static List<RenderArtifact> buildArtifacts(String mode, String svg) {
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

  private static double archimateJunctionRadius(LaidOutNode node, ResolvedNodeStyle style) {
    return Math.max(4.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
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

  private static String archimateCutCornerShape(LaidOutNode node, ResolvedNodeStyle style) {
    double corner = Math.max(8.0, Math.min(14.0, Math.min(node.width(), node.height()) * 0.14));
    return String.format(
        Locale.ROOT,
        "<path data-dediren-node-shape=\"archimate_cut_corner_rectangle\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        node.x() + corner,
        node.y(),
        node.x() + node.width() - corner,
        node.y(),
        node.x() + node.width(),
        node.y() + corner,
        node.x() + node.width(),
        node.y() + node.height() - corner,
        node.x() + node.width() - corner,
        node.y() + node.height(),
        node.x() + corner,
        node.y() + node.height(),
        node.x(),
        node.y() + node.height() - corner,
        node.x(),
        node.y() + corner,
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
  }

  private static String umlNodeShape(
      LaidOutNode node,
      ResolvedNodeStyle style,
      SvgNodeDecorator decorator,
      RenderMetadataSelector selector) {
    String shapeName = decoratorName(decorator);
    return switch (decorator) {
      case UML_INITIAL_NODE -> {
        double radius =
            Math.max(4.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
        yield String.format(
            Locale.ROOT,
            "<circle data-dediren-node-shape=\"%s\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
            shapeName,
            node.x() + node.width() / 2.0,
            node.y() + node.height() / 2.0,
            radius,
            attr(style.fill()),
            attr(style.stroke()),
            styleNumber(style.strokeWidth()));
      }
      case UML_ACTIVITY_FINAL_NODE -> {
        double radius =
            Math.max(5.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
        double innerRadius = Math.max(3.0, radius * 0.48);
        yield String.format(
            Locale.ROOT,
            "<g data-dediren-node-shape=\"%s\"><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"#ffffff\" stroke=\"%s\" stroke-width=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/></g>",
            shapeName,
            node.x() + node.width() / 2.0,
            node.y() + node.height() / 2.0,
            radius,
            attr(style.stroke()),
            styleNumber(style.strokeWidth()),
            node.x() + node.width() / 2.0,
            node.y() + node.height() / 2.0,
            innerRadius,
            attr(style.stroke()));
      }
      case UML_STATE ->
          String.format(
              Locale.ROOT,
              "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              shapeName,
              node.x(),
              node.y(),
              node.width(),
              node.height(),
              styleNumber(Math.max(style.rx(), 14.0)),
              attr(style.fill()),
              attr(style.stroke()),
              styleNumber(style.strokeWidth()));
      case UML_FINAL_STATE -> umlFinalStateShape(node, style, shapeName);
      case UML_PSEUDOSTATE -> umlPseudostateShape(node, style, selector, shapeName);
      case UML_ACTOR -> umlActorShape(node, style, shapeName);
      case UML_USE_CASE -> umlUseCaseShape(node, style, shapeName);
      case UML_EXTENSION_POINT ->
          String.format(
              Locale.ROOT,
              "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              shapeName,
              node.x(),
              node.y(),
              node.width(),
              node.height(),
              styleNumber(Math.max(style.rx(), 2.0)),
              attr(style.fill()),
              attr(style.stroke()),
              styleNumber(style.strokeWidth()));
      case UML_COMPONENT, UML_PORT ->
          String.format(
              Locale.ROOT,
              "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              shapeName,
              node.x(),
              node.y(),
              node.width(),
              node.height(),
              styleNumber(style.rx()),
              attr(style.fill()),
              attr(style.stroke()),
              styleNumber(style.strokeWidth()));
      case UML_NODE, UML_DEVICE, UML_EXECUTION_ENVIRONMENT ->
          umlDeploymentTargetShape(node, style, shapeName);
      case UML_ARTIFACT, UML_DEPLOYMENT_SPECIFICATION -> umlArtifactShape(node, style, shapeName);
      case UML_DECISION_NODE, UML_MERGE_NODE -> {
        double centerX = node.x() + node.width() / 2.0;
        double centerY = node.y() + node.height() / 2.0;
        yield String.format(
            Locale.ROOT,
            "<path data-dediren-node-shape=\"%s\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
            shapeName,
            centerX,
            node.y(),
            node.x() + node.width(),
            centerY,
            centerX,
            node.y() + node.height(),
            node.x(),
            centerY,
            attr(style.fill()),
            attr(style.stroke()),
            styleNumber(style.strokeWidth()));
      }
      case UML_FORK_NODE, UML_JOIN_NODE -> {
        boolean horizontal = node.width() >= node.height();
        double width = horizontal ? node.width() : Math.min(node.width(), 14.0);
        double height = horizontal ? Math.min(node.height(), 14.0) : node.height();
        double x = node.x() + (node.width() - width) / 2.0;
        double y = node.y() + (node.height() - height) / 2.0;
        yield String.format(
            Locale.ROOT,
            "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"0\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
            shapeName,
            x,
            y,
            width,
            height,
            attr(style.fill()),
            attr(style.stroke()),
            styleNumber(style.strokeWidth()));
      }
      case UML_PACKAGE -> {
        double tabWidth = Math.max(40.0, Math.min(96.0, node.width() * 0.34));
        double tabHeight = Math.max(14.0, Math.min(24.0, node.height() * 0.18));
        yield String.format(
            Locale.ROOT,
            "<path data-dediren-node-shape=\"%s\" d=\"M %.1f %.1f H %.1f V %.1f H %.1f V %.1f H %.1f V %.1f H %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
            shapeName,
            node.x(),
            node.y(),
            node.x() + tabWidth,
            node.y() + tabHeight,
            node.x() + node.width(),
            node.y() + node.height(),
            node.x(),
            node.y() + tabHeight,
            node.x(),
            attr(style.fill()),
            attr(style.stroke()),
            styleNumber(style.strokeWidth()));
      }
      case UML_ACTION ->
          String.format(
              Locale.ROOT,
              "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              shapeName,
              node.x(),
              node.y(),
              node.width(),
              node.height(),
              styleNumber(Math.max(style.rx(), 10.0)),
              attr(style.fill()),
              attr(style.stroke()),
              styleNumber(style.strokeWidth()));
      default ->
          String.format(
              Locale.ROOT,
              "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              shapeName,
              node.x(),
              node.y(),
              node.width(),
              node.height(),
              styleNumber(style.rx()),
              attr(style.fill()),
              attr(style.stroke()),
              styleNumber(style.strokeWidth()));
    };
  }

  private static String umlDeploymentTargetShape(
      LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double depth = Math.max(10.0, Math.min(18.0, Math.min(node.width(), node.height()) * 0.13));
    double frontX = node.x();
    double frontY = node.y() + depth;
    double frontWidth = Math.max(20.0, node.width() - depth);
    double frontHeight = Math.max(20.0, node.height() - depth);
    String fill = attr(style.fill());
    String stroke = attr(style.stroke());
    String width = styleNumber(style.strokeWidth());
    return String.format(
        Locale.ROOT,
        "<g data-dediren-node-shape=\"%s\">"
            + "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>"
            + "<path data-dediren-deployment-target-part=\"top\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>"
            + "<path data-dediren-deployment-target-part=\"side\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>"
            + "</g>",
        shapeName,
        frontX,
        frontY,
        frontWidth,
        frontHeight,
        styleNumber(style.rx()),
        fill,
        stroke,
        width,
        frontX,
        frontY,
        frontX + depth,
        node.y(),
        frontX + frontWidth + depth,
        node.y(),
        frontX + frontWidth,
        frontY,
        fill,
        stroke,
        width,
        frontX + frontWidth,
        frontY,
        frontX + frontWidth + depth,
        node.y(),
        frontX + frontWidth + depth,
        node.y() + frontHeight,
        frontX + frontWidth,
        frontY + frontHeight,
        fill,
        stroke,
        width);
  }

  private static String umlArtifactShape(
      LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double fold = Math.max(12.0, Math.min(22.0, Math.min(node.width(), node.height()) * 0.24));
    String fill = attr(style.fill());
    String stroke = attr(style.stroke());
    String width = styleNumber(style.strokeWidth());
    return String.format(
        Locale.ROOT,
        "<g data-dediren-node-shape=\"%s\">"
            + "<path data-dediren-artifact-part=\"body\" d=\"M %.1f %.1f H %.1f L %.1f %.1f V %.1f H %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>"
            + "<path data-dediren-artifact-part=\"fold\" d=\"M %.1f %.1f V %.1f H %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>"
            + "</g>",
        shapeName,
        node.x(),
        node.y(),
        node.x() + node.width() - fold,
        node.x() + node.width(),
        node.y() + fold,
        node.y() + node.height(),
        node.x(),
        fill,
        stroke,
        width,
        node.x() + node.width() - fold,
        node.y(),
        node.y() + fold,
        node.x() + node.width(),
        stroke,
        width);
  }

  private static String umlActorShape(LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double centerX = node.x() + node.width() / 2.0;
    double headRadius = Math.max(7.0, Math.min(node.width(), node.height()) * 0.11);
    double headCenterY = node.y() + headRadius + 10.0;
    double bodyTopY = headCenterY + headRadius;
    double bodyBottomY = node.y() + node.height() * 0.58;
    double armY = node.y() + node.height() * 0.38;
    double armSpan = node.width() * 0.62;
    double legSpan = node.width() * 0.44;
    double legBottomY = node.y() + node.height() * 0.78;
    String body =
        String.format(
            Locale.ROOT,
            "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>"
                + "<path d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
            centerX,
            headCenterY,
            headRadius,
            attr(style.fill()),
            attr(style.stroke()),
            styleNumber(style.strokeWidth()),
            centerX,
            bodyTopY,
            centerX,
            bodyBottomY,
            centerX - armSpan / 2.0,
            armY,
            centerX + armSpan / 2.0,
            armY,
            centerX,
            bodyBottomY,
            centerX - legSpan / 2.0,
            legBottomY,
            centerX,
            bodyBottomY,
            centerX + legSpan / 2.0,
            legBottomY,
            attr(style.stroke()),
            styleNumber(style.strokeWidth()));
    return "<g data-dediren-node-shape=\"" + attr(shapeName) + "\">" + body + "</g>";
  }

  private static String umlUseCaseShape(
      LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    return String.format(
        Locale.ROOT,
        "<ellipse data-dediren-node-shape=\"%s\" cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        shapeName,
        node.x() + node.width() / 2.0,
        node.y() + node.height() / 2.0,
        Math.max(8.0, node.width() / 2.0),
        Math.max(8.0, node.height() / 2.0),
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
  }

  private static String umlFinalStateShape(
      LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double centerX = node.x() + node.width() / 2.0;
    double centerY = node.y() + node.height() / 2.0;
    double radius =
        Math.max(5.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
    double innerRadius = Math.max(3.0, radius * 0.48);
    return String.format(
        Locale.ROOT,
        "<g data-dediren-node-shape=\"%s\"><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"#ffffff\" stroke=\"%s\" stroke-width=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/></g>",
        shapeName,
        centerX,
        centerY,
        radius,
        attr(style.stroke()),
        styleNumber(style.strokeWidth()),
        centerX,
        centerY,
        innerRadius,
        attr(style.stroke()));
  }

  private static String umlPseudostateShape(
      LaidOutNode node,
      ResolvedNodeStyle style,
      RenderMetadataSelector selector,
      String shapeName) {
    String kind = textField(selector == null ? null : selector.properties(), "kind", "initial");
    return switch (kind) {
      case "choice", "junction" -> umlDiamondShape(node, style, shapeName);
      case "fork", "join" -> umlBarShape(node, style, shapeName);
      case "deepHistory" -> umlTextCircleShape(node, style, shapeName, "H*");
      case "shallowHistory" -> umlTextCircleShape(node, style, shapeName, "H");
      case "entryPoint" -> umlTextCircleShape(node, style, shapeName, "E");
      case "exitPoint", "terminate" -> umlTextCircleShape(node, style, shapeName, "X");
      default -> umlFilledCircleShape(node, style, shapeName);
    };
  }

  private static String umlFilledCircleShape(
      LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double radius =
        Math.max(4.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
    return String.format(
        Locale.ROOT,
        "<circle data-dediren-node-shape=\"%s\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        shapeName,
        node.x() + node.width() / 2.0,
        node.y() + node.height() / 2.0,
        radius,
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
  }

  private static String umlDiamondShape(
      LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double centerX = node.x() + node.width() / 2.0;
    double centerY = node.y() + node.height() / 2.0;
    return String.format(
        Locale.ROOT,
        "<path data-dediren-node-shape=\"%s\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        shapeName,
        centerX,
        node.y(),
        node.x() + node.width(),
        centerY,
        centerX,
        node.y() + node.height(),
        node.x(),
        centerY,
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
  }

  private static String umlBarShape(LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    boolean horizontal = node.width() >= node.height();
    double width = horizontal ? node.width() : Math.min(node.width(), 14.0);
    double height = horizontal ? Math.min(node.height(), 14.0) : node.height();
    double x = node.x() + (node.width() - width) / 2.0;
    double y = node.y() + (node.height() - height) / 2.0;
    return String.format(
        Locale.ROOT,
        "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"0\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        shapeName,
        x,
        y,
        width,
        height,
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
  }

  private static String umlTextCircleShape(
      LaidOutNode node, ResolvedNodeStyle style, String shapeName, String symbol) {
    double centerX = node.x() + node.width() / 2.0;
    double centerY = node.y() + node.height() / 2.0;
    double radius =
        Math.max(5.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
    double fontSize = Math.max(10.0, Math.min(14.0, radius * 0.9));
    return String.format(
        Locale.ROOT,
        "<circle data-dediren-node-shape=\"%s\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"#ffffff\" stroke=\"%s\" stroke-width=\"%s\"/><text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\" font-size=\"%s\">%s</text>",
        shapeName,
        centerX,
        centerY,
        radius,
        attr(style.stroke()),
        styleNumber(style.strokeWidth()),
        centerX,
        centerY + fontSize / 3.0,
        attr(style.labelFill()),
        styleNumber(fontSize),
        text(symbol));
  }

  private static String nodeLabel(LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
    NodeLabelLines label = nodeLabelLinesAndSize(node, style, fontSize);
    NodeLabelPosition position =
        nodeLabelPosition(node, style, label.fontSize(), label.lines().size());
    String baselineAttribute = position.centerBaseline() ? " dominant-baseline=\"middle\"" : "";
    if (label.lines().size() == 1) {
      return String.format(
          Locale.ROOT,
          "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\"%s fill=\"%s\" font-size=\"%s\"%s>%s</text>",
          position.x(),
          position.y(),
          baselineAttribute,
          attr(style.labelFill()),
          labelNumber(label.fontSize()),
          labelLengthAttributes(label.lines().get(0), label.fontSize()),
          text(label.lines().get(0)));
    }

    StringBuilder svg =
        new StringBuilder(
            String.format(
                Locale.ROOT,
                "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\"%s fill=\"%s\" font-size=\"%s\">",
                position.x(),
                position.y(),
                baselineAttribute,
                attr(style.labelFill()),
                labelNumber(label.fontSize())));
    for (int index = 0; index < label.lines().size(); index++) {
      String dy = index == 0 ? "0" : labelNumber(nodeLabelLineHeight(label.fontSize()));
      svg.append(
          String.format(
              Locale.ROOT,
              "<tspan x=\"%.1f\" dy=\"%s\"%s>%s</tspan>",
              position.x(),
              dy,
              labelLengthAttributes(label.lines().get(index), label.fontSize()),
              text(label.lines().get(index))));
    }
    svg.append("</text>");
    return svg.toString();
  }

  // Pin each rendered label line to the layout metric (estimateTextWidth) via
  // textLength + lengthAdjust, so the displayed width — and thus the label's
  // clearance from the corner decorator and node border — is independent of the
  // viewer's installed font rather than the layout's internal metric. See issue #25.
  private static String labelLengthAttributes(String line, double fontSize) {
    double width = estimateTextWidth(line, fontSize);
    if (width <= 0.0) {
      return "";
    }
    return " textLength=\"" + labelNumber(width) + "\" lengthAdjust=\"spacingAndGlyphs\"";
  }

  private static NodeLabelLines nodeLabelLinesAndSize(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
    List<String> lines = wrappedNodeLabelLines(node, style, fontSize);
    double maxWidth = nodeLabelMaxWidth(node, style, fontSize);
    double widestLine =
        lines.stream().mapToDouble(line -> estimateTextWidth(line, fontSize)).max().orElse(0.0);
    double widthFontSize = widestLine > maxWidth ? fontSize * maxWidth / widestLine : fontSize;
    double availableHeight =
        node.height() - NODE_LABEL_VERTICAL_PADDING - archimateLabelTopReserve(style);
    double blockHeight = lines.size() * nodeLabelLineHeight(fontSize);
    double heightFontSize =
        blockHeight > availableHeight ? fontSize * availableHeight / blockHeight : fontSize;
    double labelFontSize =
        Math.max(Math.min(widthFontSize, heightFontSize), NODE_LABEL_MIN_FONT_SIZE);
    return new NodeLabelLines(lines, labelFontSize);
  }

  private static List<String> wrappedNodeLabelLines(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
    // Reached only via shouldRenderPlainNodeLabel, which already requires a non-null, non-empty
    // label; the model schema also makes node label required. So node.label() is non-null here.
    String rawLabel = node.label();
    double maxWidth = nodeLabelMaxWidth(node, style, fontSize);
    List<String> tokens = labelWrapTokens(rawLabel);
    List<String> lines = new ArrayList<>();
    String current = "";
    for (String token : tokens) {
      String candidate = current.isEmpty() ? token : current + " " + token;
      if (estimateTextWidth(candidate, fontSize) <= maxWidth) {
        current = candidate;
        continue;
      }
      if (!current.isEmpty()) {
        lines.add(current);
      }
      current = token;
    }
    if (!current.isEmpty()) {
      lines.add(current);
    }
    return lines.isEmpty() ? List.of(rawLabel) : lines;
  }

  private static List<String> labelWrapTokens(String label) {
    List<String> tokens = new ArrayList<>();
    for (String token : label.split("\\s+")) {
      if (!token.isEmpty()) {
        tokens.addAll(splitCamelToken(token));
      }
    }
    return tokens;
  }

  private static List<String> splitCamelToken(String token) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int[] codePoints = token.codePoints().toArray();
    for (int index = 0; index < codePoints.length; index++) {
      boolean previousIsLowercase = index > 0 && Character.isLowerCase(codePoints[index - 1]);
      boolean nextIsLowercase =
          index + 1 < codePoints.length && Character.isLowerCase(codePoints[index + 1]);
      if (previousIsLowercase
          && Character.isUpperCase(codePoints[index])
          && nextIsLowercase
          && !current.isEmpty()) {
        parts.add(current.toString());
        current.setLength(0);
      }
      current.appendCodePoint(codePoints[index]);
    }
    if (!current.isEmpty()) {
      parts.add(current.toString());
    }
    return parts;
  }

  private static NodeLabelPosition nodeLabelPosition(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize, int lineCount) {
    if (archimateJunctionLabelOutside(style.decorator())) {
      // Top-aligned below the circle: the first line's baseline sits `gap` below the
      // circle's southmost point and any wrapped lines grow downward. Junctions carry
      // empty labels in practice, so block-centering (as in the UML branch below) is
      // unnecessary here.
      double radius = archimateJunctionRadius(node, style);
      double gap = 6.0;
      double firstLineY = node.y() + node.height() / 2.0 + radius + gap + fontSize;
      return new NodeLabelPosition(node.x() + node.width() / 2.0, firstLineY, false);
    }
    if (umlCompactControlNodeLabelOutside(style.decorator())) {
      double lineHeight = nodeLabelLineHeight(fontSize);
      double lineSpan = Math.max(0, lineCount - 1) * lineHeight;
      double diagonalGap = 8.0;
      double diagonalDelta = Math.min(node.width(), node.height()) / 2.0 + diagonalGap;
      double labelCenterY = node.y() + node.height() / 2.0 - diagonalDelta;
      double labelCenterX = node.x() + node.width() / 2.0 - diagonalDelta;
      double firstLineY = labelCenterY - (lineSpan - fontSize * 0.6) / 2.0;
      return new NodeLabelPosition(labelCenterX, firstLineY, false);
    }
    return new NodeLabelPosition(
        node.x() + node.width() / 2.0, nodeLabelFirstLineY(node, style, fontSize, lineCount), true);
  }

  // Reviewed (ARCH-V-002, won't-fix): these compact glyphs deliberately place their
  // label diagonally up-left (see nodeLabelPosition) to keep it clear of the in/out
  // flows that enter and leave initial/final/decision/merge nodes. This is intentional
  // and differs from ArchiMate junction labels, which center below the circle.
  private static boolean umlCompactControlNodeLabelOutside(SvgNodeDecorator decorator) {
    return decorator == SvgNodeDecorator.UML_INITIAL_NODE
        || decorator == SvgNodeDecorator.UML_ACTIVITY_FINAL_NODE
        || decorator == SvgNodeDecorator.UML_DECISION_NODE
        || decorator == SvgNodeDecorator.UML_MERGE_NODE;
  }

  private static boolean archimateJunctionLabelOutside(SvgNodeDecorator decorator) {
    return decorator == SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_OR_JUNCTION;
  }

  private static double nodeLabelMaxWidth(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
    double reserve =
        hasArchimateCornerIcon(style.decorator()) ? 2.0 * ARCHIMATE_LABEL_ICON_RESERVE : 20.0;
    return Math.max(node.width() - reserve, fontSize * 3.0);
  }

  private static double nodeLabelLineHeight(double fontSize) {
    return fontSize * 1.15;
  }

  // Center the label block in the box area below the corner-decorator's reserved band
  // (the icon's top inset + glyph box) so a multi-line label's top line never lands at
  // the decorator's vertical level — the vertical complement of the horizontal
  // textLength gutter shipped for #25. Nodes without a corner icon reserve nothing and
  // stay centered over the full box height. See issue #27.
  private static double nodeLabelFirstLineY(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize, int lineCount) {
    double topReserve = archimateLabelTopReserve(style);
    double areaCenterY = node.y() + topReserve + (node.height() - topReserve) / 2.0;
    return areaCenterY - (Math.max(0, lineCount - 1) * nodeLabelLineHeight(fontSize)) / 2.0;
  }

  private static double archimateLabelTopReserve(ResolvedNodeStyle style) {
    return hasArchimateCornerIcon(style.decorator())
        ? ARCHIMATE_ICON_TOP_INSET + ARCHIMATE_ICON_SIZE
        : 0.0;
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

  private static String archimateNodeDecorator(
      LaidOutNode node, ResolvedNodeStyle style, SvgNodeDecorator decorator) {
    String name = decoratorName(decorator);
    ArchimateIconKind kind = archimateIconKind(decorator);
    double size = ARCHIMATE_ICON_SIZE;
    double x = node.x() + node.width() - size - 6.0;
    double y = node.y() + ARCHIMATE_ICON_TOP_INSET;
    String body = archimateIconBody(decorator, kind, x, y, size, style);
    return "<g data-dediren-node-decorator=\""
        + attr(name)
        + "\" data-dediren-icon-kind=\""
        + kind.value()
        + "\" data-dediren-icon-size=\"22\">"
        + body
        + "</g>";
  }

  private static String archimateIconBody(
      SvgNodeDecorator decorator,
      ArchimateIconKind kind,
      double x,
      double y,
      double size,
      ResolvedNodeStyle style) {
    String fill = attr(style.fill());
    String stroke = attr(style.stroke());
    String width = styleNumber(style.strokeWidth());
    return switch (kind) {
      case ACTOR -> archimateActorIconBody(x, y - 3.0, size, fill, stroke, width);
      case INTERFACE ->
          String.format(
              Locale.ROOT,
              "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.72,
              y + size * 0.28,
              size * 0.18,
              size * 0.18,
              fill,
              stroke,
              width,
              x + size * 0.1,
              y + size * 0.28,
              x + size * 0.54,
              y + size * 0.28,
              stroke,
              width);
      case COLLABORATION ->
          String.format(
              Locale.ROOT,
              "<circle data-dediren-icon-part=\"collaboration-circles\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"none\"/><circle data-dediren-icon-part=\"collaboration-circles\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"none\"/><circle data-dediren-icon-part=\"collaboration-circles\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><circle data-dediren-icon-part=\"collaboration-circles\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.38,
              y + size * 0.38,
              size * 0.26,
              fill,
              x + size * 0.62,
              y + size * 0.38,
              size * 0.26,
              fill,
              x + size * 0.38,
              y + size * 0.38,
              size * 0.26,
              stroke,
              width,
              x + size * 0.62,
              y + size * 0.38,
              size * 0.26,
              stroke,
              width);
      case ROLE, STAKEHOLDER ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"side-cylinder\" d=\"M %.1f %.1f A %.1f %.1f 0 0 0 %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><ellipse data-dediren-icon-part=\"side-cylinder-end\" cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.1,
              y + size * 0.2,
              size * 0.16,
              size * 0.16,
              x + size * 0.1,
              y + size * 0.52,
              x + size * 0.1,
              y + size * 0.2,
              x + size * 0.7,
              y + size * 0.2,
              x + size * 0.1,
              y + size * 0.52,
              x + size * 0.7,
              y + size * 0.52,
              stroke,
              width,
              x + size * 0.7,
              y + size * 0.36,
              size * 0.16,
              size * 0.16,
              fill,
              stroke,
              width);
      case SERVICE -> archimateServiceIconBody(decorator, x, y, size, fill, stroke, width);
      case INTERACTION ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"interaction-half\" d=\"M %.1f %.1f A %.1f %.1f 0 0 0 %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"interaction-half\" d=\"M %.1f %.1f A %.1f %.1f 0 0 1 %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.42,
              y + size * 0.12,
              size * 0.24,
              size * 0.24,
              x + size * 0.42,
              y + size * 0.6,
              x + size * 0.42,
              y + size * 0.12,
              stroke,
              width,
              x + size * 0.58,
              y + size * 0.12,
              size * 0.24,
              size * 0.24,
              x + size * 0.58,
              y + size * 0.6,
              x + size * 0.58,
              y + size * 0.12,
              stroke,
              width);
      case FUNCTION ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"function-bookmark\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.18,
              y + size * 0.2,
              x + size * 0.5,
              y + size * 0.06,
              x + size * 0.82,
              y + size * 0.2,
              x + size * 0.82,
              y + size * 0.7,
              x + size * 0.5,
              y + size * 0.56,
              x + size * 0.18,
              y + size * 0.7,
              fill,
              stroke,
              width);
      case PROCESS ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"process-arrow\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x,
              y + size * 0.24,
              x + size * 0.62,
              y + size * 0.24,
              x + size * 0.62,
              y,
              x + size,
              y + size * 0.36,
              x + size * 0.62,
              y + size * 0.72,
              x + size * 0.62,
              y + size * 0.48,
              x,
              y + size * 0.48,
              fill,
              stroke,
              width);
      case COURSE_OF_ACTION ->
          archimateTargetIconBody(x, y, size, fill, stroke, width, TargetIconStyle.HANDLE);
      case EVENT ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"event-pill\" d=\"M %.1f %.1f L %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.18,
              y + size * 0.04,
              x + size * 0.74,
              y + size * 0.04,
              x + size * 0.94,
              y + size * 0.04,
              x + size,
              y + size * 0.2,
              x + size,
              y + size * 0.36,
              x + size,
              y + size * 0.52,
              x + size * 0.94,
              y + size * 0.68,
              x + size * 0.74,
              y + size * 0.68,
              x + size * 0.18,
              y + size * 0.68,
              x + size * 0.34,
              y + size * 0.36,
              x + size * 0.18,
              y + size * 0.04,
              fill,
              stroke,
              width);
      case OBJECT -> archimateDocumentIconBody(x, y, size, fill, stroke, width, false);
      case COMPONENT -> archimateApplicationComponentIconBody(x, y, size, fill, stroke, width);
      case CONTRACT -> archimateContractIconBody(x, y, size, fill, stroke, width);
      case PRODUCT ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"product-tab\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z M %.1f %.1f L %.1f %.1f L %.1f %.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\" stroke-linejoin=\"round\"/>",
              x,
              y,
              x + size,
              y,
              x + size,
              y + size * 0.72,
              x,
              y + size * 0.72,
              x,
              y,
              x,
              y + size * 0.24,
              x + size * 0.62,
              y + size * 0.24,
              x + size * 0.62,
              y,
              fill,
              stroke,
              width);
      case REPRESENTATION ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"wavy-representation\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f L %.1f %.1f Z M %.1f %.1f L %.1f %.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x,
              y,
              x + size * 0.92,
              y,
              x + size * 0.92,
              y + size * 0.58,
              x + size * 0.74,
              y + size * 0.5,
              x + size * 0.56,
              y + size * 0.5,
              x + size * 0.42,
              y + size * 0.58,
              x + size * 0.28,
              y + size * 0.66,
              x + size * 0.14,
              y + size * 0.66,
              x,
              y + size * 0.58,
              x,
              y,
              x,
              y + size * 0.22,
              x + size * 0.92,
              y + size * 0.22,
              fill,
              stroke,
              width);
      case LOCATION -> {
        double markerY = y - 3.0;
        yield String.format(
            Locale.ROOT,
            "<path d=\"M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
            x + size * 0.5,
            markerY + size,
            x + size * 0.04,
            markerY + size * 0.5,
            x + size * 0.14,
            markerY,
            x + size * 0.5,
            markerY,
            x + size * 0.86,
            markerY,
            x + size * 0.96,
            markerY + size * 0.5,
            x + size * 0.5,
            markerY + size,
            fill,
            stroke,
            width);
      }
      case GROUPING ->
          String.format(
              Locale.ROOT,
              "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.2\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-dasharray=\"3 2\"/>",
              x,
              y,
              size,
              size * 0.72,
              stroke,
              width);
      case DRIVER ->
          String.format(
              Locale.ROOT,
              "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"driver-spokes\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/><ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.5,
              y + size * 0.36,
              size * 0.28,
              size * 0.28,
              stroke,
              width,
              x + size * 0.5,
              y,
              x + size * 0.5,
              y + size * 0.72,
              x + size * 0.14,
              y + size * 0.36,
              x + size * 0.86,
              y + size * 0.36,
              x + size * 0.26,
              y + size * 0.12,
              x + size * 0.74,
              y + size * 0.6,
              x + size * 0.26,
              y + size * 0.6,
              x + size * 0.74,
              y + size * 0.12,
              stroke,
              width,
              x + size * 0.5,
              y + size * 0.36,
              size * 0.12,
              size * 0.08,
              stroke,
              stroke,
              width);
      case ASSESSMENT ->
          String.format(
              Locale.ROOT,
              "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"assessment-handle\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
              x + size * 0.5,
              y + size * 0.28,
              size * 0.22,
              size * 0.22,
              stroke,
              width,
              x + size * 0.36,
              y + size * 0.44,
              x + size * 0.16,
              y + size * 0.64,
              stroke,
              width);
      case GOAL ->
          archimateTargetIconBody(x, y, size, fill, stroke, width, TargetIconStyle.BULLSEYE);
      case OUTCOME ->
          archimateTargetIconBody(x, y, size, fill, stroke, width, TargetIconStyle.ARROW);
      case VALUE ->
          String.format(
              Locale.ROOT,
              "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.5,
              y + size * 0.36,
              size * 0.44,
              size * 0.24,
              fill,
              stroke,
              width);
      case MEANING ->
          String.format(
              Locale.ROOT,
              "<path d=\"M %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.18,
              y + size * 0.54,
              x + size * 0.04,
              y + size * 0.38,
              x + size * 0.18,
              y + size * 0.22,
              x + size * 0.36,
              y + size * 0.28,
              x + size * 0.46,
              y + size * 0.04,
              x + size * 0.68,
              y + size * 0.18,
              x + size * 0.66,
              y + size * 0.34,
              x + size * 0.92,
              y + size * 0.32,
              x + size * 0.94,
              y + size * 0.54,
              x + size * 0.72,
              y + size * 0.62,
              x + size * 0.42,
              y + size * 0.68,
              x + size * 0.26,
              y + size * 0.62,
              x + size * 0.18,
              y + size * 0.54,
              fill,
              stroke,
              width);
      case CONSTRAINT ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"constraint-parallelogram\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"constraint-left-line\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.22,
              y,
              x + size,
              y,
              x + size * 0.78,
              y + size * 0.72,
              x,
              y + size * 0.72,
              fill,
              stroke,
              width,
              x + size * 0.32,
              y,
              x + size * 0.1,
              y + size * 0.72,
              stroke,
              width);
      case REQUIREMENT ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"requirement-parallelogram\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.22,
              y,
              x + size,
              y,
              x + size * 0.78,
              y + size * 0.72,
              x,
              y + size * 0.72,
              fill,
              stroke,
              width);
      case PRINCIPLE ->
          String.format(
              Locale.ROOT,
              "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.2\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path d=\"M %.1f %.1f L %.1f %.1f\" stroke=\"%s\" stroke-width=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"1.2\" fill=\"%s\"/>",
              x,
              y,
              size,
              size * 0.72,
              fill,
              stroke,
              width,
              x + size * 0.5,
              y + size * 0.12,
              x + size * 0.5,
              y + size * 0.44,
              stroke,
              width,
              x + size * 0.5,
              y + size * 0.58,
              stroke);
      case RESOURCE ->
          String.format(
              Locale.ROOT,
              "<rect data-dediren-icon-part=\"resource-capsule\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><rect data-dediren-icon-part=\"resource-tab\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"0.8\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"resource-bars\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
              x + size * 0.04,
              y + size * 0.18,
              size * 0.78,
              size * 0.4,
              size * 0.12,
              fill,
              stroke,
              width,
              x + size * 0.82,
              y + size * 0.3,
              size * 0.08,
              size * 0.2,
              fill,
              stroke,
              width,
              x + size * 0.2,
              y + size * 0.28,
              x + size * 0.2,
              y + size * 0.52,
              x + size * 0.34,
              y + size * 0.28,
              x + size * 0.34,
              y + size * 0.52,
              x + size * 0.48,
              y + size * 0.28,
              x + size * 0.48,
              y + size * 0.52,
              stroke,
              width);
      case VALUE_STREAM ->
          String.format(
              Locale.ROOT,
              "<path data-dediren-icon-part=\"value-stream-chevron\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x,
              y + size * 0.08,
              x + size * 0.68,
              y + size * 0.08,
              x + size,
              y + size * 0.36,
              x + size * 0.68,
              y + size * 0.64,
              x,
              y + size * 0.64,
              x + size * 0.24,
              y + size * 0.36,
              fill,
              stroke,
              width);
      case CAPABILITY -> archimateCapabilityIconBody(x, y, size, fill, stroke, width);
      case PLATEAU ->
          String.format(
              Locale.ROOT,
              "<path d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"square\"/>",
              x + size * 0.26,
              y + size * 0.18,
              x + size * 0.88,
              y + size * 0.18,
              x + size * 0.12,
              y + size * 0.36,
              x + size * 0.74,
              y + size * 0.36,
              x,
              y + size * 0.54,
              x + size * 0.62,
              y + size * 0.54,
              stroke,
              width);
      case WORK_PACKAGE -> archimateWorkPackageIconBody(x, y, size, stroke, width);
      case DELIVERABLE ->
          archimateWavyDocumentIconBody("wavy-document", x, y, size, fill, stroke, width);
      case GAP ->
          String.format(
              Locale.ROOT,
              "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"gap-lines\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
              x + size * 0.52,
              y + size * 0.34,
              size * 0.22,
              size * 0.22,
              stroke,
              width,
              x + size * 0.1,
              y + size * 0.26,
              x + size * 0.94,
              y + size * 0.26,
              x + size * 0.1,
              y + size * 0.42,
              x + size * 0.94,
              y + size * 0.42,
              stroke,
              width);
      case ARTIFACT ->
          archimateFoldedDocumentIconBody(
              "artifact-document", x, y - 1.0, size, fill, stroke, width);
      case SYSTEM_SOFTWARE ->
          String.format(
              Locale.ROOT,
              "<ellipse data-dediren-icon-part=\"system-software-disks\" cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><ellipse data-dediren-icon-part=\"system-software-disks\" cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * 0.58,
              y + size * 0.36,
              size * 0.26,
              size * 0.26,
              stroke,
              width,
              x + size * 0.38,
              y + size * 0.5,
              size * 0.26,
              size * 0.26,
              fill,
              stroke,
              width);
      case DEVICE ->
          String.format(
              Locale.ROOT,
              "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"2.0\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"device-stand\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
              x + size * 0.08,
              y + size * 0.04,
              size * 0.84,
              size * 0.52,
              fill,
              stroke,
              width,
              x + size * 0.5,
              y + size * 0.56,
              x + size * 0.5,
              y + size * 0.72,
              x + size * 0.32,
              y + size * 0.72,
              x + size * 0.68,
              y + size * 0.72,
              stroke,
              width);
      case FACILITY -> archimateFacilityIconBody(x, y, size, fill, stroke, width);
      case EQUIPMENT -> archimateEquipmentIconBody(x, y, size, fill, stroke, width);
      case NODE -> archimateTechnologyNodeIconBody(x, y, size, fill, stroke, width);
      case MATERIAL -> archimateMaterialIconBody(x, y, size, fill, stroke, width);
      case NETWORK -> archimateNetworkIconBody(x, y, size, stroke, width);
      case DISTRIBUTION_NETWORK ->
          archimateDistributionNetworkIconBody(x, y, size, fill, stroke, width);
      case PATH -> archimatePathIconBody(x, y, size, stroke, width);
      case JUNCTION -> "";
    };
  }

  private static String archimateServiceIconBody(
      SvgNodeDecorator decorator,
      double x,
      double y,
      double size,
      String fill,
      String stroke,
      String width) {
    double serviceY =
        decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_SERVICE ? y : y + size * 0.12;
    double serviceHeight =
        decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_SERVICE ? size * 0.62 : size * 0.5;
    return String.format(
        Locale.ROOT,
        "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        x,
        serviceY,
        size,
        serviceHeight,
        size * 0.18,
        fill,
        stroke,
        width);
  }

  private static String archimateActorIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    double cx = x + size * 0.5;
    double headRx = size * 0.16;
    double headRy = size * 0.2;
    double headCy = y + headRy;
    double bodyTop = y + headRy * 2.0 + size * 0.08;
    double bodyBottom = y + size * 0.72;
    double armY = bodyTop + size * 0.12;
    return String.format(
        Locale.ROOT,
        "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>",
        cx,
        headCy,
        headRx,
        headRy,
        fill,
        stroke,
        width,
        cx,
        bodyTop,
        cx,
        bodyBottom,
        cx - size * 0.28,
        armY,
        cx,
        bodyTop + size * 0.1,
        cx + size * 0.28,
        armY,
        cx,
        bodyBottom,
        cx - size * 0.24,
        y + size,
        cx,
        bodyBottom,
        cx + size * 0.24,
        y + size,
        stroke,
        width);
  }

  private static String archimateApplicationComponentIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    double tabWidth = size * 0.36;
    double tabHeight = size * 0.26;
    double bodyX = x + tabWidth / 2.0;
    double bodyWidth = size - tabWidth / 2.0;
    return String.format(
        Locale.ROOT,
        "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.5\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.2\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.2\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        bodyX,
        y,
        bodyWidth,
        size * 0.72,
        fill,
        stroke,
        width,
        x,
        y + size * 0.12,
        tabWidth,
        tabHeight,
        fill,
        stroke,
        width,
        x,
        y + size * 0.44,
        tabWidth,
        tabHeight,
        fill,
        stroke,
        width);
  }

  private static String archimateTargetIconBody(
      double x,
      double y,
      double size,
      String fill,
      String stroke,
      String width,
      TargetIconStyle style) {
    double centerX = x + size * 0.5;
    double centerY = y + size * 0.36;
    double outer = size * 0.34;
    double inner = size * 0.16;
    String rings =
        String.format(
            Locale.ROOT,
            "<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/><ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
            centerX,
            centerY,
            outer,
            outer,
            fill,
            stroke,
            width,
            centerX,
            centerY,
            inner,
            inner,
            stroke,
            width,
            centerX,
            centerY,
            size * 0.05,
            size * 0.05,
            stroke,
            width);
    return switch (style) {
      case BULLSEYE -> rings;
      case ARROW ->
          rings
              + String.format(
                  Locale.ROOT,
                  "<path data-dediren-icon-part=\"target-arrow\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
                  centerX,
                  centerY,
                  x + size * 0.82,
                  y + size * 0.08,
                  x + size * 0.82,
                  y + size * 0.04,
                  x + size * 0.82,
                  y + size * 0.13,
                  x + size * 0.78,
                  y + size * 0.12,
                  x + size * 0.86,
                  y + size * 0.05,
                  x + size * 0.82,
                  y + size * 0.08,
                  x + size * 0.88,
                  y + size * 0.01,
                  stroke,
                  width);
      case HANDLE ->
          rings
              + String.format(
                  Locale.ROOT,
                  "<path data-dediren-icon-part=\"course-of-action-handle\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
                  centerX - size * 0.22,
                  centerY + size * 0.2,
                  x + size * 0.06,
                  y + size * 0.72,
                  stroke,
                  width);
    };
  }

  private static String archimateDocumentIconBody(
      double x, double y, double size, String fill, String stroke, String width, boolean folded) {
    if (folded) {
      return String.format(
          Locale.ROOT,
          "<path data-dediren-icon-part=\"document-fold\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z M %.1f %.1f L %.1f %.1f L %.1f %.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"document-header\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
          x,
          y,
          x + size * 0.72,
          y,
          x + size * 0.92,
          y + size * 0.2,
          x + size * 0.92,
          y + size * 0.72,
          x,
          y + size * 0.72,
          x + size * 0.72,
          y,
          x + size * 0.72,
          y + size * 0.2,
          x + size * 0.92,
          y + size * 0.2,
          fill,
          stroke,
          width,
          x,
          y + size * 0.22,
          x + size * 0.68,
          y + size * 0.22,
          stroke,
          width);
    }
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"document-body\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"document-header\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
        x,
        y,
        x + size * 0.92,
        y,
        x + size * 0.92,
        y + size * 0.72,
        x,
        y + size * 0.72,
        fill,
        stroke,
        width,
        x,
        y + size * 0.22,
        x + size * 0.92,
        y + size * 0.22,
        stroke,
        width);
  }

  private static String archimateContractIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"contract-document-body\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"contract-lines\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
        x,
        y,
        x + size * 0.92,
        y,
        x + size * 0.92,
        y + size * 0.72,
        x,
        y + size * 0.72,
        fill,
        stroke,
        width,
        x,
        y + size * 0.24,
        x + size * 0.92,
        y + size * 0.24,
        x,
        y + size * 0.48,
        x + size * 0.92,
        y + size * 0.48,
        stroke,
        width);
  }

  private static String archimateCapabilityIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    StringBuilder body = new StringBuilder();
    double[][] steps = {
      {0.2, 0.52},
      {0.4, 0.52},
      {0.4, 0.32},
      {0.6, 0.52},
      {0.6, 0.32},
      {0.6, 0.12}
    };
    for (double[] step : steps) {
      body.append(
          String.format(
              Locale.ROOT,
              "<rect data-dediren-icon-part=\"capability-step\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
              x + size * step[0],
              y + size * step[1],
              size * 0.2,
              size * 0.2,
              fill,
              stroke,
              width));
    }
    return body.toString();
  }

  private static String archimateWorkPackageIconBody(
      double x, double y, double size, String stroke, String width) {
    double loopX = x - size * 0.14;
    double loopY = y - size * 0.3;
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"work-package-loop-arrow\" d=\"M %.1f %.1f A %.1f %.1f 0 1 0 %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>",
        loopX + size * 0.58,
        loopY + size * 0.52,
        size * 0.26,
        size * 0.26,
        loopX + size * 0.54,
        loopY + size * 0.72,
        loopX + size * 0.86,
        loopY + size * 0.72,
        loopX + size * 0.86,
        loopY + size * 0.72,
        loopX + size * 0.74,
        loopY + size * 0.62,
        loopX + size * 0.86,
        loopY + size * 0.72,
        loopX + size * 0.74,
        loopY + size * 0.82,
        stroke,
        width);
  }

  private static String archimateWavyDocumentIconBody(
      String part, double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"%s\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f C %.1f %.1f, %.1f %.1f, %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\" stroke-linejoin=\"round\"/>",
        attr(part),
        x,
        y,
        x + size,
        y,
        x + size,
        y + size * 0.58,
        x + size * 0.82,
        y + size * 0.5,
        x + size * 0.66,
        y + size * 0.5,
        x + size * 0.5,
        y + size * 0.58,
        x + size * 0.34,
        y + size * 0.66,
        x + size * 0.18,
        y + size * 0.66,
        x,
        y + size * 0.58,
        x,
        y,
        fill,
        stroke,
        width);
  }

  private static String archimateFoldedDocumentIconBody(
      String part, double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"%s\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z M %.1f %.1f L %.1f %.1f L %.1f %.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        attr(part),
        x + size * 0.1,
        y,
        x + size * 0.58,
        y,
        x + size * 0.86,
        y + size * 0.28,
        x + size * 0.86,
        y + size * 0.9,
        x + size * 0.1,
        y + size * 0.9,
        x + size * 0.58,
        y,
        x + size * 0.58,
        y + size * 0.28,
        x + size * 0.86,
        y + size * 0.28,
        fill,
        stroke,
        width);
  }

  private static String archimateFacilityIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"factory-silhouette\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        x + size * 0.08,
        y + size * 0.72,
        x + size * 0.08,
        y + size * 0.08,
        x + size * 0.22,
        y + size * 0.08,
        x + size * 0.22,
        y + size * 0.48,
        x + size * 0.42,
        y + size * 0.34,
        x + size * 0.42,
        y + size * 0.48,
        x + size * 0.62,
        y + size * 0.34,
        x + size * 0.62,
        y + size * 0.48,
        x + size * 0.84,
        y + size * 0.34,
        x + size * 0.84,
        y + size * 0.72,
        x + size * 0.08,
        y + size * 0.72,
        fill,
        stroke,
        width);
  }

  private static String archimateEquipmentIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    return archimateGearPath(
            "equipment-gear-large",
            x + size * 0.34,
            y + size * 0.52,
            size * 0.24,
            fill,
            stroke,
            width)
        + String.format(
            Locale.ROOT,
            "<circle data-dediren-icon-part=\"equipment-gear-hole\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
            x + size * 0.34,
            y + size * 0.52,
            size * 0.06,
            stroke,
            width)
        + archimateGearPath(
            "equipment-gear-small",
            x + size * 0.68,
            y + size * 0.24,
            size * 0.16,
            fill,
            stroke,
            width)
        + String.format(
            Locale.ROOT,
            "<circle data-dediren-icon-part=\"equipment-gear-hole\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
            x + size * 0.68,
            y + size * 0.24,
            size * 0.04,
            stroke,
            width);
  }

  private static String archimateGearPath(
      String part, double cx, double cy, double radius, String fill, String stroke, String width) {
    double[][] points =
        new double[][] {
          {cx, cy - radius}, {cx + radius * 0.18, cy - radius * 0.72},
          {cx + radius * 0.42, cy - radius * 0.9}, {cx + radius * 0.52, cy - radius * 0.58},
          {cx + radius * 0.84, cy - radius * 0.54}, {cx + radius * 0.72, cy - radius * 0.18},
          {cx + radius, cy}, {cx + radius * 0.72, cy + radius * 0.18},
          {cx + radius * 0.84, cy + radius * 0.54}, {cx + radius * 0.52, cy + radius * 0.58},
          {cx + radius * 0.42, cy + radius * 0.9}, {cx + radius * 0.18, cy + radius * 0.72},
          {cx, cy + radius}, {cx - radius * 0.18, cy + radius * 0.72},
          {cx - radius * 0.42, cy + radius * 0.9}, {cx - radius * 0.52, cy + radius * 0.58},
          {cx - radius * 0.84, cy + radius * 0.54}, {cx - radius * 0.72, cy + radius * 0.18},
          {cx - radius, cy}, {cx - radius * 0.72, cy - radius * 0.18},
          {cx - radius * 0.84, cy - radius * 0.54}, {cx - radius * 0.52, cy - radius * 0.58},
          {cx - radius * 0.42, cy - radius * 0.9}, {cx - radius * 0.18, cy - radius * 0.72}
        };
    StringBuilder d = new StringBuilder();
    for (int index = 0; index < points.length; index++) {
      if (index > 0) {
        d.append(" ");
      }
      d.append(index == 0 ? "M" : "L")
          .append(" ")
          .append(styleNumber(points[index][0]))
          .append(" ")
          .append(styleNumber(points[index][1]));
    }
    return "<path data-dediren-icon-part=\""
        + attr(part)
        + "\" d=\""
        + d
        + " Z\" fill=\""
        + fill
        + "\" stroke=\""
        + stroke
        + "\" stroke-width=\""
        + width
        + "\"/>";
  }

  private static String archimateTechnologyNodeIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    double bodyY = y + size * 0.18;
    double depth = size * 0.18;
    return String.format(
        Locale.ROOT,
        "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"1.5\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"node-3d-edges\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linejoin=\"round\"/>",
        x,
        bodyY,
        size - depth,
        size * 0.58,
        fill,
        stroke,
        width,
        x,
        bodyY,
        x + depth,
        bodyY - depth,
        x + size,
        bodyY - depth,
        x + size - depth,
        bodyY,
        x + size - depth,
        bodyY,
        x + size,
        bodyY - depth,
        x + size - depth,
        bodyY + size * 0.58,
        x + size,
        bodyY + size * 0.58 - depth,
        x + size,
        bodyY - depth,
        x + size,
        bodyY + size * 0.58 - depth,
        stroke,
        width);
  }

  private static String archimateMaterialIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"material-hexagon\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/><path data-dediren-icon-part=\"material-lines\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
        x + size * 0.25,
        y,
        x + size * 0.75,
        y,
        x + size,
        y + size * 0.36,
        x + size * 0.75,
        y + size * 0.72,
        x + size * 0.25,
        y + size * 0.72,
        x,
        y + size * 0.36,
        fill,
        stroke,
        width,
        x + size * 0.36,
        y + size * 0.16,
        x + size * 0.64,
        y + size * 0.16,
        x + size * 0.78,
        y + size * 0.28,
        x + size * 0.62,
        y + size * 0.52,
        x + size * 0.22,
        y + size * 0.28,
        x + size * 0.38,
        y + size * 0.52,
        stroke,
        width);
  }

  private static String archimateNetworkIconBody(
      double x, double y, double size, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"2.3\" fill=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"2.3\" fill=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"2.3\" fill=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"2.3\" fill=\"%s\"/><path d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\"/>",
        x + size * 0.32,
        y + size * 0.22,
        stroke,
        x + size * 0.72,
        y + size * 0.22,
        stroke,
        x + size * 0.22,
        y + size * 0.58,
        stroke,
        x + size * 0.62,
        y + size * 0.58,
        stroke,
        x + size * 0.32,
        y + size * 0.22,
        x + size * 0.72,
        y + size * 0.22,
        x + size * 0.62,
        y + size * 0.58,
        x + size * 0.22,
        y + size * 0.58,
        stroke,
        width);
  }

  private static String archimateDistributionNetworkIconBody(
      double x, double y, double size, String fill, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"distribution-network-arrows\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\" stroke-linejoin=\"round\"/>",
        x + size * 0.12,
        y + size * 0.36,
        x + size * 0.3,
        y + size * 0.22,
        x + size * 0.3,
        y + size * 0.3,
        x + size * 0.7,
        y + size * 0.3,
        x + size * 0.7,
        y + size * 0.22,
        x + size * 0.88,
        y + size * 0.36,
        x + size * 0.7,
        y + size * 0.5,
        x + size * 0.7,
        y + size * 0.42,
        x + size * 0.3,
        y + size * 0.42,
        x + size * 0.3,
        y + size * 0.5,
        fill,
        stroke,
        width);
  }

  private static String archimatePathIconBody(
      double x, double y, double size, String stroke, String width) {
    return String.format(
        Locale.ROOT,
        "<path data-dediren-icon-part=\"path-line\" d=\"M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-dasharray=\"3 2\" stroke-linecap=\"round\"/><path data-dediren-icon-part=\"path-arrowheads\" d=\"M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f\" fill=\"none\" stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\"/>",
        x + size * 0.08,
        y + size * 0.36,
        x + size * 0.92,
        y + size * 0.36,
        stroke,
        width,
        x + size * 0.25,
        y + size * 0.16,
        x + size * 0.08,
        y + size * 0.36,
        x + size * 0.75,
        y + size * 0.16,
        x + size * 0.92,
        y + size * 0.36,
        x + size * 0.25,
        y + size * 0.56,
        x + size * 0.08,
        y + size * 0.36,
        x + size * 0.75,
        y + size * 0.56,
        x + size * 0.92,
        y + size * 0.36,
        stroke,
        width);
  }

  private static String umlNodeDecorator(
      LaidOutNode node,
      ResolvedNodeStyle style,
      SvgNodeDecorator decorator,
      RenderMetadataSelector selector) {
    String name = decoratorName(decorator);
    String body = "";
    // Actor is in umlDecoratorSuppliesNodeLabel (so the generic plain label is
    // suppressed) but supplies its own label below the figure, not classifier
    // notation — so it is excluded from this classifier branch and handled below.
    if (umlDecoratorSuppliesNodeLabel(decorator) && decorator != SvgNodeDecorator.UML_ACTOR) {
      body = umlClassifierNotation(node, style, decorator, selector);
    } else if (decorator == SvgNodeDecorator.UML_PACKAGE) {
      body =
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"12\">%s</text>",
              node.x() + 8.0,
              node.y() + 16.0,
              attr(style.labelFill()),
              text(node.label()));
    } else if (decorator == SvgNodeDecorator.UML_ACTOR) {
      body =
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\" font-size=\"12\">%s</text>",
              node.x() + node.width() / 2.0,
              node.y() + node.height() - 8.0,
              attr(style.labelFill()),
              text(node.label()));
    } else if (decorator == SvgNodeDecorator.UML_COMPONENT) {
      body = umlComponentGlyph(node, style);
    } else if (decorator == SvgNodeDecorator.UML_DEVICE
        || decorator == SvgNodeDecorator.UML_EXECUTION_ENVIRONMENT
        || decorator == SvgNodeDecorator.UML_DEPLOYMENT_SPECIFICATION) {
      body = umlStereotypeLabel(node, style, decorator);
    }
    return "<g data-dediren-node-decorator=\"" + attr(name) + "\">" + body + "</g>";
  }

  private static String umlStereotypeLabel(
      LaidOutNode node, ResolvedNodeStyle style, SvgNodeDecorator decorator) {
    String stereotype =
        switch (decorator) {
          case UML_DEVICE -> "&#171;device&#187;";
          case UML_EXECUTION_ENVIRONMENT -> "&#171;executionEnvironment&#187;";
          case UML_DEPLOYMENT_SPECIFICATION -> "&#171;deployment spec&#187;";
          default -> "";
        };
    return String.format(
        Locale.ROOT,
        "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\" font-size=\"11\">%s</text>",
        node.x() + node.width() / 2.0,
        node.y() + 17.0,
        attr(style.labelFill()),
        stereotype);
  }

  private static String umlComponentGlyph(LaidOutNode node, ResolvedNodeStyle style) {
    double glyphWidth = Math.min(28.0, Math.max(18.0, node.width() * 0.18));
    double glyphHeight = Math.min(24.0, Math.max(16.0, node.height() * 0.22));
    double x = node.x() + node.width() - glyphWidth - 10.0;
    double y = node.y() + 10.0;
    double tabWidth = glyphWidth * 0.32;
    double tabHeight = glyphHeight * 0.28;
    String fill = attr(style.fill());
    String stroke = attr(style.stroke());
    String width = styleNumber(style.strokeWidth());
    return String.format(
        Locale.ROOT,
        "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>"
            + "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>"
            + "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        x,
        y,
        glyphWidth,
        glyphHeight,
        fill,
        stroke,
        width,
        x - tabWidth * 0.45,
        y + glyphHeight * 0.22,
        tabWidth,
        tabHeight,
        fill,
        stroke,
        width,
        x - tabWidth * 0.45,
        y + glyphHeight * 0.58,
        tabWidth,
        tabHeight,
        fill,
        stroke,
        width);
  }

  private static String umlClassifierNotation(
      LaidOutNode node,
      ResolvedNodeStyle style,
      SvgNodeDecorator decorator,
      RenderMetadataSelector selector) {
    JsonNode properties = selector == null ? null : selector.properties();
    List<String> titleLines = new ArrayList<>();
    if (decorator == SvgNodeDecorator.UML_ENUMERATION) {
      titleLines.add("&#171;enumeration&#187;");
    } else if (decorator == SvgNodeDecorator.UML_INTERFACE) {
      titleLines.add("&#171;interface&#187;");
    } else if (decorator == SvgNodeDecorator.UML_DATA_TYPE) {
      titleLines.add("&#171;dataType&#187;");
    }
    titleLines.add(text(node.label()));

    List<String> attributeLines =
        decorator == SvgNodeDecorator.UML_ENUMERATION
            ? umlLiteralLines(properties)
            : umlAttributeLines(properties);
    List<String> operationLines =
        decorator == SvgNodeDecorator.UML_ENUMERATION ? List.of() : umlOperationLines(properties);
    double titleHeight = Math.max(28.0, titleLines.size() * 15.0 + 8.0);
    double attributeHeight = attributeLines.isEmpty() ? 0.0 : attributeLines.size() * 14.0 + 8.0;
    double firstSeparatorY = node.y() + titleHeight;
    double secondSeparatorY = firstSeparatorY + attributeHeight;

    StringBuilder svg = new StringBuilder();
    if (!attributeLines.isEmpty() || !operationLines.isEmpty()) {
      svg.append(
          String.format(
              Locale.ROOT,
              "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%s\"/>",
              node.x(),
              firstSeparatorY,
              node.x() + node.width(),
              firstSeparatorY,
              attr(style.stroke()),
              styleNumber(style.strokeWidth())));
    }
    if (!operationLines.isEmpty()) {
      svg.append(
          String.format(
              Locale.ROOT,
              "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%s\"/>",
              node.x(),
              secondSeparatorY,
              node.x() + node.width(),
              secondSeparatorY,
              attr(style.stroke()),
              styleNumber(style.strokeWidth())));
    }
    double y = node.y() + 15.0;
    for (String line : titleLines) {
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"middle\" fill=\"%s\" font-size=\"12\">%s</text>",
              node.x() + node.width() / 2.0,
              y,
              attr(style.labelFill()),
              line));
      y += 15.0;
    }
    y = firstSeparatorY + 15.0;
    for (String line : attributeLines) {
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"12\">%s</text>",
              node.x() + 8.0,
              y,
              attr(style.labelFill()),
              text(line)));
      y += 14.0;
    }
    y = secondSeparatorY + 15.0;
    for (String line : operationLines) {
      svg.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" fill=\"%s\" font-size=\"12\">%s</text>",
              node.x() + 8.0,
              y,
              attr(style.labelFill()),
              text(line)));
      y += 14.0;
    }
    return svg.toString();
  }

  private static List<String> umlAttributeLines(JsonNode properties) {
    JsonNode attributes = properties == null ? null : properties.get("attributes");
    if (attributes == null || !attributes.isArray()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (JsonNode attribute : attributes) {
      String visibility = umlVisibilitySymbol(textField(attribute, "visibility", "public"));
      String name = textField(attribute, "name", "");
      String type = textField(attribute, "type", "");
      lines.add(type.isEmpty() ? visibility + " " + name : visibility + " " + name + " : " + type);
    }
    return lines;
  }

  private static List<String> umlOperationLines(JsonNode properties) {
    JsonNode operations = properties == null ? null : properties.get("operations");
    if (operations == null || !operations.isArray()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (JsonNode operation : operations) {
      String visibility = umlVisibilitySymbol(textField(operation, "visibility", "public"));
      String name = textField(operation, "name", "");
      List<String> parameters = new ArrayList<>();
      JsonNode parameterValues = operation.get("parameters");
      if (parameterValues != null && parameterValues.isArray()) {
        for (JsonNode parameter : parameterValues) {
          String parameterName = textField(parameter, "name", "");
          String parameterType = textField(parameter, "type", "");
          if (parameterType.isEmpty()) {
            parameters.add(parameterName);
          } else if (parameterName.isEmpty()) {
            parameters.add(parameterType);
          } else {
            parameters.add(parameterName + " : " + parameterType);
          }
        }
      }
      String returnType = textField(operation, "return_type", "");
      String signature = visibility + " " + name + "(" + String.join(", ", parameters) + ")";
      lines.add(returnType.isEmpty() ? signature : signature + " : " + returnType);
    }
    return lines;
  }

  private static List<String> umlLiteralLines(JsonNode properties) {
    JsonNode literals = properties == null ? null : properties.get("literals");
    if (literals == null || !literals.isArray()) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (JsonNode literal : literals) {
      if (literal.isTextual()) {
        lines.add(literal.asText());
      }
    }
    return lines;
  }

  private static String umlVisibilitySymbol(String visibility) {
    return switch (visibility) {
      case "private" -> "-";
      case "protected" -> "#";
      case "package" -> "~";
      default -> "+";
    };
  }

  private static String textField(JsonNode value, String field, String fallback) {
    JsonNode fieldValue = value == null ? null : value.get(field);
    return fieldValue != null && fieldValue.isTextual() ? fieldValue.asText() : fallback;
  }

  // Reviewed (ARCH-L-004, won't-fix): final states and pseudostates are intentionally
  // unlabeled. Unnamed final/initial pseudostates are valid UML, so these glyph-only
  // shapes suppress the plain label rather than rendering an empty or placeholder name.
  private static boolean shouldRenderPlainNodeLabel(LaidOutNode node, SvgNodeDecorator decorator) {
    return node.label() != null
        && !node.label().isEmpty()
        && !umlDecoratorSuppliesNodeLabel(decorator)
        && decorator != SvgNodeDecorator.UML_FINAL_STATE
        && decorator != SvgNodeDecorator.UML_PSEUDOSTATE;
  }

  private static boolean umlDecoratorSuppliesNodeLabel(SvgNodeDecorator decorator) {
    return decorator == SvgNodeDecorator.UML_CLASS
        || decorator == SvgNodeDecorator.UML_INTERFACE
        || decorator == SvgNodeDecorator.UML_DATA_TYPE
        || decorator == SvgNodeDecorator.UML_ENUMERATION
        || decorator == SvgNodeDecorator.UML_ACTOR;
  }

  private static boolean isUmlDecorator(SvgNodeDecorator decorator) {
    return decorator != null && decorator.name().startsWith("UML_");
  }

  private static boolean hasArchimateCornerIcon(SvgNodeDecorator decorator) {
    return decorator != null
        && !isUmlDecorator(decorator)
        && decorator != SvgNodeDecorator.ARCHIMATE_AND_JUNCTION
        && decorator != SvgNodeDecorator.ARCHIMATE_OR_JUNCTION;
  }

  private static boolean isArchimateCutCornerRectangle(SvgNodeDecorator decorator) {
    return decorator == SvgNodeDecorator.ARCHIMATE_STAKEHOLDER
        || decorator == SvgNodeDecorator.ARCHIMATE_DRIVER
        || decorator == SvgNodeDecorator.ARCHIMATE_ASSESSMENT
        || decorator == SvgNodeDecorator.ARCHIMATE_GOAL
        || decorator == SvgNodeDecorator.ARCHIMATE_OUTCOME
        || decorator == SvgNodeDecorator.ARCHIMATE_VALUE
        || decorator == SvgNodeDecorator.ARCHIMATE_MEANING
        || decorator == SvgNodeDecorator.ARCHIMATE_CONSTRAINT
        || decorator == SvgNodeDecorator.ARCHIMATE_REQUIREMENT
        || decorator == SvgNodeDecorator.ARCHIMATE_PRINCIPLE;
  }

  private static boolean isArchimateRoundedRectangle(SvgNodeDecorator decorator) {
    return decorator == SvgNodeDecorator.ARCHIMATE_WORK_PACKAGE
        || decorator == SvgNodeDecorator.ARCHIMATE_IMPLEMENTATION_EVENT
        || decorator == SvgNodeDecorator.ARCHIMATE_COURSE_OF_ACTION
        || decorator == SvgNodeDecorator.ARCHIMATE_VALUE_STREAM
        || decorator == SvgNodeDecorator.ARCHIMATE_CAPABILITY
        || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_SERVICE
        || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_FUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_PROCESS
        || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_EVENT
        || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_SERVICE
        || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_FUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_PROCESS
        || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_EVENT
        || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_SERVICE
        || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_FUNCTION
        || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_PROCESS
        || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_EVENT
        || decorator == SvgNodeDecorator.ARCHIMATE_BUSINESS_INTERACTION
        || decorator == SvgNodeDecorator.ARCHIMATE_APPLICATION_INTERACTION
        || decorator == SvgNodeDecorator.ARCHIMATE_TECHNOLOGY_INTERACTION;
  }

  private static ArchimateIconKind archimateIconKind(SvgNodeDecorator decorator) {
    return switch (decorator) {
      case ARCHIMATE_BUSINESS_INTERFACE,
              ARCHIMATE_APPLICATION_INTERFACE,
              ARCHIMATE_TECHNOLOGY_INTERFACE ->
          ArchimateIconKind.INTERFACE;
      case ARCHIMATE_BUSINESS_COLLABORATION,
              ARCHIMATE_APPLICATION_COLLABORATION,
              ARCHIMATE_TECHNOLOGY_COLLABORATION ->
          ArchimateIconKind.COLLABORATION;
      case ARCHIMATE_BUSINESS_ACTOR -> ArchimateIconKind.ACTOR;
      case ARCHIMATE_BUSINESS_ROLE -> ArchimateIconKind.ROLE;
      case ARCHIMATE_BUSINESS_SERVICE,
              ARCHIMATE_APPLICATION_SERVICE,
              ARCHIMATE_TECHNOLOGY_SERVICE ->
          ArchimateIconKind.SERVICE;
      case ARCHIMATE_BUSINESS_INTERACTION,
              ARCHIMATE_APPLICATION_INTERACTION,
              ARCHIMATE_TECHNOLOGY_INTERACTION ->
          ArchimateIconKind.INTERACTION;
      case ARCHIMATE_BUSINESS_FUNCTION,
              ARCHIMATE_APPLICATION_FUNCTION,
              ARCHIMATE_TECHNOLOGY_FUNCTION ->
          ArchimateIconKind.FUNCTION;
      case ARCHIMATE_BUSINESS_PROCESS,
              ARCHIMATE_APPLICATION_PROCESS,
              ARCHIMATE_TECHNOLOGY_PROCESS ->
          ArchimateIconKind.PROCESS;
      case ARCHIMATE_BUSINESS_EVENT,
              ARCHIMATE_APPLICATION_EVENT,
              ARCHIMATE_TECHNOLOGY_EVENT,
              ARCHIMATE_IMPLEMENTATION_EVENT ->
          ArchimateIconKind.EVENT;
      case ARCHIMATE_BUSINESS_OBJECT, ARCHIMATE_DATA_OBJECT -> ArchimateIconKind.OBJECT;
      case ARCHIMATE_APPLICATION_COMPONENT -> ArchimateIconKind.COMPONENT;
      case ARCHIMATE_CONTRACT -> ArchimateIconKind.CONTRACT;
      case ARCHIMATE_PRODUCT -> ArchimateIconKind.PRODUCT;
      case ARCHIMATE_REPRESENTATION -> ArchimateIconKind.REPRESENTATION;
      case ARCHIMATE_LOCATION -> ArchimateIconKind.LOCATION;
      case ARCHIMATE_GROUPING -> ArchimateIconKind.GROUPING;
      case ARCHIMATE_AND_JUNCTION, ARCHIMATE_OR_JUNCTION -> ArchimateIconKind.JUNCTION;
      case ARCHIMATE_STAKEHOLDER -> ArchimateIconKind.STAKEHOLDER;
      case ARCHIMATE_DRIVER -> ArchimateIconKind.DRIVER;
      case ARCHIMATE_ASSESSMENT -> ArchimateIconKind.ASSESSMENT;
      case ARCHIMATE_GOAL -> ArchimateIconKind.GOAL;
      case ARCHIMATE_OUTCOME -> ArchimateIconKind.OUTCOME;
      case ARCHIMATE_VALUE -> ArchimateIconKind.VALUE;
      case ARCHIMATE_MEANING -> ArchimateIconKind.MEANING;
      case ARCHIMATE_CONSTRAINT -> ArchimateIconKind.CONSTRAINT;
      case ARCHIMATE_REQUIREMENT -> ArchimateIconKind.REQUIREMENT;
      case ARCHIMATE_PRINCIPLE -> ArchimateIconKind.PRINCIPLE;
      case ARCHIMATE_COURSE_OF_ACTION -> ArchimateIconKind.COURSE_OF_ACTION;
      case ARCHIMATE_RESOURCE -> ArchimateIconKind.RESOURCE;
      case ARCHIMATE_VALUE_STREAM -> ArchimateIconKind.VALUE_STREAM;
      case ARCHIMATE_CAPABILITY -> ArchimateIconKind.CAPABILITY;
      case ARCHIMATE_PLATEAU -> ArchimateIconKind.PLATEAU;
      case ARCHIMATE_WORK_PACKAGE -> ArchimateIconKind.WORK_PACKAGE;
      case ARCHIMATE_DELIVERABLE -> ArchimateIconKind.DELIVERABLE;
      case ARCHIMATE_GAP -> ArchimateIconKind.GAP;
      case ARCHIMATE_ARTIFACT -> ArchimateIconKind.ARTIFACT;
      case ARCHIMATE_SYSTEM_SOFTWARE -> ArchimateIconKind.SYSTEM_SOFTWARE;
      case ARCHIMATE_DEVICE -> ArchimateIconKind.DEVICE;
      case ARCHIMATE_FACILITY -> ArchimateIconKind.FACILITY;
      case ARCHIMATE_EQUIPMENT -> ArchimateIconKind.EQUIPMENT;
      case ARCHIMATE_TECHNOLOGY_NODE -> ArchimateIconKind.NODE;
      case ARCHIMATE_MATERIAL -> ArchimateIconKind.MATERIAL;
      case ARCHIMATE_COMMUNICATION_NETWORK -> ArchimateIconKind.NETWORK;
      case ARCHIMATE_DISTRIBUTION_NETWORK -> ArchimateIconKind.DISTRIBUTION_NETWORK;
      case ARCHIMATE_PATH -> ArchimateIconKind.PATH;
      default -> throw new IllegalArgumentException("not an ArchiMate decorator: " + decorator);
    };
  }

  private enum ArchimateIconKind {
    ACTOR("actor"),
    INTERFACE("interface"),
    COLLABORATION("collaboration"),
    ROLE("role"),
    SERVICE("service"),
    INTERACTION("interaction"),
    FUNCTION("function"),
    PROCESS("process"),
    EVENT("event"),
    OBJECT("object"),
    COMPONENT("component"),
    CONTRACT("contract"),
    PRODUCT("product"),
    REPRESENTATION("representation"),
    LOCATION("location"),
    GROUPING("grouping"),
    JUNCTION("junction"),
    STAKEHOLDER("stakeholder"),
    DRIVER("driver"),
    ASSESSMENT("assessment"),
    GOAL("goal"),
    OUTCOME("outcome"),
    VALUE("value"),
    MEANING("meaning"),
    CONSTRAINT("constraint"),
    REQUIREMENT("requirement"),
    PRINCIPLE("principle"),
    COURSE_OF_ACTION("course_of_action"),
    RESOURCE("resource"),
    VALUE_STREAM("value_stream"),
    CAPABILITY("capability"),
    PLATEAU("plateau"),
    WORK_PACKAGE("work_package"),
    DELIVERABLE("deliverable"),
    GAP("gap"),
    ARTIFACT("artifact"),
    SYSTEM_SOFTWARE("system_software"),
    DEVICE("device"),
    FACILITY("facility"),
    EQUIPMENT("equipment"),
    NODE("node"),
    MATERIAL("material"),
    NETWORK("network"),
    DISTRIBUTION_NETWORK("distribution_network"),
    PATH("path");

    private final String value;

    ArchimateIconKind(String value) {
      this.value = value;
    }

    private String value() {
      return value;
    }
  }

  private enum TargetIconStyle {
    BULLSEYE,
    ARROW,
    HANDLE
  }

  private static String decoratorName(SvgNodeDecorator decorator) {
    return decorator.name().toLowerCase(Locale.ROOT);
  }

  private static String edgeMarker(LaidOutEdge edge, ResolvedEdgeStyle style, String side) {
    SvgEdgeMarkerEnd marker = side.equals("start") ? style.markerStart() : style.markerEnd();
    if (marker == SvgEdgeMarkerEnd.NONE) {
      return "";
    }
    String markerName = markerName(marker);
    String id = "marker-" + side + "-" + edge.id();
    String attribute = "data-dediren-edge-marker-" + side;
    String fill = markerFill(marker, style);
    String stroke = markerStroke(marker, style);
    String body =
        switch (marker) {
          case FILLED_DIAMOND, HOLLOW_DIAMOND ->
              "<path d=\"M 1 5 L 5 1 L 9 5 L 5 9 Z\" fill=\""
                  + fill
                  + "\" stroke=\""
                  + stroke
                  + "\" stroke-width=\"1\"/>";
          case HOLLOW_TRIANGLE ->
              "<path d=\"M 1 1 L 9 5 L 1 9 Z\" fill=\""
                  + fill
                  + "\" stroke=\""
                  + stroke
                  + "\" stroke-width=\"1\"/>";
          case OPEN_ARROW ->
              "<path d=\"M 1 1 L 9 5 L 1 9\" fill=\"none\" stroke=\""
                  + stroke
                  + "\" stroke-width=\"1.5\"/>";
          case FILLED_CIRCLE, HOLLOW_CIRCLE ->
              "<circle cx=\"5\" cy=\"5\" r=\"3.5\" fill=\""
                  + fill
                  + "\" stroke=\""
                  + stroke
                  + "\" stroke-width=\"1\"/>";
          default ->
              "<path d=\"M 1 1 L 9 5 L 1 9 Z\" fill=\""
                  + fill
                  + "\" stroke=\""
                  + stroke
                  + "\" stroke-width=\"1\"/>";
        };
    return "<marker id=\""
        + attr(id)
        + "\" "
        + attribute
        + "=\""
        + markerName
        + "\" markerWidth=\"10\" markerHeight=\"10\" refX=\"5\" refY=\"5\" orient=\"auto\">"
        + body
        + "</marker>";
  }

  private static String lineJumpMasks(
      LaidOutEdge edge,
      List<LineJump> lineJumps,
      LayoutResult result,
      RenderMetadata metadata,
      RenderPolicy policy,
      ResolvedStyle base) {
    if (lineJumps.isEmpty()) {
      return "";
    }
    StringBuilder masks = new StringBuilder();
    masks.append("<g data-dediren-line-jump-masks=\"").append(attr(edge.id())).append("\">");
    for (LineJump jump : lineJumps) {
      String maskFill = backdropFillAt(jump.x(), jump.y(), result, metadata, policy, base);
      masks
          .append("<path d=\"")
          .append(attr(jump.maskPath()))
          .append("\" fill=\"none\" stroke=\"")
          .append(attr(maskFill))
          .append("\" stroke-width=\"6\"/>");
    }
    masks.append("</g>");
    return masks.toString();
  }

  private static String backdropFillAt(
      double x,
      double y,
      LayoutResult result,
      RenderMetadata metadata,
      RenderPolicy policy,
      ResolvedStyle base) {
    for (int index = result.groups().size() - 1; index >= 0; index--) {
      LaidOutGroup group = result.groups().get(index);
      if (pointInsideRect(x, y, group.x(), group.y(), group.width(), group.height())) {
        return groupStyle(policy, metadata, group.id(), base).fill();
      }
    }
    return base.backgroundFill();
  }

  private static boolean pointInsideRect(
      double x, double y, double rectX, double rectY, double width, double height) {
    return x >= rectX && x <= rectX + width && y >= rectY && y <= rectY + height;
  }

  private static String edgePath(
      LaidOutEdge edge, ResolvedEdgeStyle style, List<LineJump> lineJumps) {
    if (edge.points().isEmpty()) {
      return "";
    }
    String data = pathData(edge, lineJumps);
    String dash = style.lineStyle() == SvgEdgeLineStyle.DASHED ? " stroke-dasharray=\"8 5\"" : "";
    String markerStart =
        style.markerStart() == SvgEdgeMarkerEnd.NONE
            ? ""
            : " marker-start=\"url(#marker-start-" + attr(edge.id()) + ")\"";
    String markerEnd =
        style.markerEnd() == SvgEdgeMarkerEnd.NONE
            ? ""
            : " marker-end=\"url(#marker-end-" + attr(edge.id()) + ")\"";
    return "<path d=\""
        + data
        + "\" fill=\"none\" stroke=\""
        + attr(style.stroke())
        + "\" stroke-width=\""
        + styleNumber(style.strokeWidth())
        + "\""
        + " stroke-linecap=\"round\" stroke-linejoin=\"round\""
        + dash
        + markerStart
        + markerEnd
        + "/>";
  }

  private static String edgeLabelBackground(EdgeLabel label, String backgroundFill) {
    LabelBox bounds = edgeLabelBackgroundBox(label);
    return String.format(
        Locale.ROOT,
        "<rect data-dediren-edge-label-background=\"true\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\"/>",
        bounds.minX(),
        bounds.minY(),
        bounds.width(),
        bounds.height(),
        styleNumber(EDGE_LABEL_BACKGROUND_RX),
        attr(backgroundFill));
  }

  private static String edgeLabel(
      EdgeLabel label,
      String text,
      ResolvedEdgeStyle style,
      String backgroundFill,
      double fontSize) {
    StringBuilder output = new StringBuilder();
    if (style.labelPresentation() == SvgEdgeLabelPresentation.BACKGROUND) {
      output.append(edgeLabelBackground(label, backgroundFill));
      output.append(
          String.format(
              Locale.ROOT,
              "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\" fill=\"%s\" font-size=\"%s\" font-weight=\"%d\">%s</text>",
              label.x(),
              label.y(),
              attr(label.anchor()),
              attr(style.labelFill()),
              styleNumber(fontSize),
              EDGE_LABEL_FONT_WEIGHT,
              text(text)));
      return output.toString();
    }
    output.append(
        String.format(
            Locale.ROOT,
            "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\" fill=\"none\" font-size=\"%s\" font-weight=\"%d\" stroke=\"%s\" stroke-width=\"%s\">%s</text>",
            label.x(),
            label.y(),
            attr(label.anchor()),
            styleNumber(fontSize),
            EDGE_LABEL_FONT_WEIGHT,
            attr(backgroundFill),
            styleNumber(EDGE_LABEL_OUTLINE_WIDTH),
            text(text)));
    output.append(
        String.format(
            Locale.ROOT,
            "<text x=\"%.1f\" y=\"%.1f\" text-anchor=\"%s\" fill=\"%s\" font-size=\"%s\" font-weight=\"%d\">%s</text>",
            label.x(),
            label.y(),
            attr(label.anchor()),
            attr(style.labelFill()),
            styleNumber(fontSize),
            EDGE_LABEL_FONT_WEIGHT,
            text(text)));
    return output.toString();
  }

  private static LabelBox edgeLabelBackgroundBox(EdgeLabel label) {
    return label
        .bounds()
        .expanded(EDGE_LABEL_BACKGROUND_PADDING_X, EDGE_LABEL_BACKGROUND_PADDING_Y);
  }

  private static LabelBox edgeLabelVisibleBox(
      EdgeLabel label, SvgEdgeLabelPresentation presentation) {
    if (presentation == SvgEdgeLabelPresentation.BACKGROUND) {
      return edgeLabelBackgroundBox(label);
    }
    return label.bounds().expanded(EDGE_LABEL_OUTLINE_WIDTH, EDGE_LABEL_OUTLINE_WIDTH);
  }

  private static double edgeLabelFontSize(double baseFontSize) {
    return Math.round(baseFontSize * EDGE_LABEL_FONT_SIZE_SCALE * 10.0) / 10.0;
  }

  private static String markerName(SvgEdgeMarkerEnd marker) {
    return marker.name().toLowerCase(Locale.ROOT);
  }

  private static String markerFill(SvgEdgeMarkerEnd marker, ResolvedEdgeStyle style) {
    return switch (marker) {
      case HOLLOW_TRIANGLE, HOLLOW_DIAMOND, HOLLOW_CIRCLE -> "#ffffff";
      case OPEN_ARROW -> "none";
      default -> attr(style.stroke());
    };
  }

  private static String markerStroke(SvgEdgeMarkerEnd marker, ResolvedEdgeStyle style) {
    return switch (marker) {
      case FILLED_ARROW, FILLED_DIAMOND, FILLED_CIRCLE -> attr(style.stroke());
      default -> attr(style.stroke());
    };
  }

  private static String pathData(LaidOutEdge edge, List<LineJump> lineJumps) {
    if (lineJumps.isEmpty()) {
      return roundedPathData(edge.points());
    }
    return roundedPathDataWithLineJumps(edge.points(), lineJumps);
  }

  private static String roundedPathDataWithLineJumps(List<Point> points, List<LineJump> lineJumps) {
    if (points.isEmpty()) {
      return "";
    }
    if (points.size() == 1) {
      Point only = points.getFirst();
      return String.format(Locale.ROOT, "M %.1f %.1f", only.x(), only.y());
    }
    StringBuilder data = new StringBuilder();
    Point first = points.getFirst();
    data.append(String.format(Locale.ROOT, "M %.1f %.1f", first.x(), first.y()));
    for (int index = 0; index < points.size() - 1; index++) {
      int segmentIndex = index;
      Point start = points.get(index);
      Point end = points.get(index + 1);
      RoundedCorner rounded =
          index + 2 < points.size() ? roundedCorner(start, end, points.get(index + 2)) : null;
      Point segmentEnd = rounded == null ? end : rounded.before();
      double segmentEndProgress = segmentProgress(start, end, segmentEnd.x(), segmentEnd.y());
      List<LineJump> segmentJumps =
          lineJumps.stream()
              .filter(jump -> jump.segmentIndex() == segmentIndex)
              .filter(
                  jump ->
                      segmentProgress(start, end, jump.x(), jump.y()) <= segmentEndProgress + 0.001)
              .sorted(
                  (left, right) ->
                      Double.compare(
                          segmentProgress(start, end, left.x(), left.y()),
                          segmentProgress(start, end, right.x(), right.y())))
              .toList();
      for (LineJump jump : segmentJumps) {
        data.append(" ").append(jump.pathPrefix(start, end));
      }
      data.append(String.format(Locale.ROOT, " L %.1f %.1f", segmentEnd.x(), segmentEnd.y()));
      if (rounded != null) {
        data.append(
            String.format(
                Locale.ROOT,
                " Q %.1f %.1f %.1f %.1f",
                end.x(),
                end.y(),
                rounded.after().x(),
                rounded.after().y()));
      }
    }
    return data.toString();
  }

  private static String roundedPathData(List<Point> points) {
    if (points.isEmpty()) {
      return "";
    }
    if (points.size() == 1) {
      Point only = points.getFirst();
      return String.format(Locale.ROOT, "M %.1f %.1f", only.x(), only.y());
    }
    StringBuilder data = new StringBuilder();
    Point first = points.getFirst();
    data.append(String.format(Locale.ROOT, "M %.1f %.1f", first.x(), first.y()));
    for (int index = 1; index < points.size() - 1; index++) {
      Point previous = points.get(index - 1);
      Point corner = points.get(index);
      Point next = points.get(index + 1);
      RoundedCorner rounded = roundedCorner(previous, corner, next);
      if (rounded == null) {
        data.append(String.format(Locale.ROOT, " L %.1f %.1f", corner.x(), corner.y()));
      } else {
        data.append(
            String.format(
                Locale.ROOT,
                " L %.1f %.1f Q %.1f %.1f %.1f %.1f",
                rounded.before().x(),
                rounded.before().y(),
                corner.x(),
                corner.y(),
                rounded.after().x(),
                rounded.after().y()));
      }
    }
    Point last = points.getLast();
    data.append(String.format(Locale.ROOT, " L %.1f %.1f", last.x(), last.y()));
    return data.toString();
  }

  private static RoundedCorner roundedCorner(Point previous, Point corner, Point next) {
    boolean firstHorizontal = nearlyEqual(previous.y(), corner.y());
    boolean firstVertical = nearlyEqual(previous.x(), corner.x());
    boolean secondHorizontal = nearlyEqual(corner.y(), next.y());
    boolean secondVertical = nearlyEqual(corner.x(), next.x());
    if (!((firstHorizontal && secondVertical) || (firstVertical && secondHorizontal))) {
      return null;
    }
    double firstLength = distance(previous, corner);
    double secondLength = distance(corner, next);
    double radius = Math.min(8.0, Math.min(firstLength / 2.0, secondLength / 2.0));
    if (radius < 2.0) {
      return null;
    }
    return new RoundedCorner(
        shiftedToward(corner, previous, radius), shiftedToward(corner, next, radius));
  }

  private static Point shiftedToward(Point from, Point toward, double distance) {
    double length = distance(from, toward);
    if (length == 0.0) {
      return from;
    }
    double ratio = distance / length;
    return new Point(
        from.x() + (toward.x() - from.x()) * ratio, from.y() + (toward.y() - from.y()) * ratio);
  }

  private static double distance(Point left, Point right) {
    return Math.hypot(left.x() - right.x(), left.y() - right.y());
  }

  private static double segmentProgress(Point start, Point end, double x, double y) {
    double dx = Math.abs(end.x() - start.x());
    double dy = Math.abs(end.y() - start.y());
    if (dx >= dy) {
      double length = end.x() - start.x();
      return length == 0.0 ? 0.0 : (x - start.x()) / length;
    }
    double length = end.y() - start.y();
    return length == 0.0 ? 0.0 : (y - start.y()) / length;
  }

  private static List<LineJump> lineJumps(LaidOutEdge edge, List<LaidOutEdge> renderedEdges) {
    List<LineJump> jumps = new ArrayList<>();
    for (int segmentIndex = 0; segmentIndex < edge.points().size() - 1; segmentIndex++) {
      Point currentStart = edge.points().get(segmentIndex);
      Point currentEnd = edge.points().get(segmentIndex + 1);
      boolean currentVertical = nearlyEqual(currentStart.x(), currentEnd.x());
      boolean currentHorizontal = nearlyEqual(currentStart.y(), currentEnd.y());
      if (!currentVertical && !currentHorizontal) {
        continue;
      }
      for (LaidOutEdge previousEdge : renderedEdges) {
        if (isSharedJunctionPair(edge, previousEdge)) {
          continue;
        }
        for (int previousIndex = 0;
            previousIndex < previousEdge.points().size() - 1;
            previousIndex++) {
          Point previousStart = previousEdge.points().get(previousIndex);
          Point previousEnd = previousEdge.points().get(previousIndex + 1);
          boolean previousVertical = nearlyEqual(previousStart.x(), previousEnd.x());
          boolean previousHorizontal = nearlyEqual(previousStart.y(), previousEnd.y());
          if (currentVertical && previousHorizontal) {
            double x = currentStart.x();
            double y = previousStart.y();
            if (insideSegment(y, currentStart.y(), currentEnd.y())
                && insideSegment(x, previousStart.x(), previousEnd.x())) {
              jumps.add(new LineJump(segmentIndex, x, y, true));
            }
          } else if (currentHorizontal && previousVertical) {
            double x = previousStart.x();
            double y = currentStart.y();
            if (insideSegment(x, currentStart.x(), currentEnd.x())
                && insideSegment(y, previousStart.y(), previousEnd.y())) {
              jumps.add(new LineJump(segmentIndex, x, y, false));
            }
          }
        }
      }
    }
    return dedupeJumps(jumps);
  }

  private static boolean isSharedJunctionPair(LaidOutEdge edge, LaidOutEdge previousEdge) {
    return (edge.routingHints().contains("shared_source_junction")
            && edge.source().equals(previousEdge.source()))
        || (edge.routingHints().contains("shared_target_junction")
            && edge.target().equals(previousEdge.target()));
  }

  private static List<LineJump> dedupeJumps(List<LineJump> jumps) {
    List<LineJump> deduped = new ArrayList<>();
    for (LineJump jump : jumps) {
      boolean exists =
          deduped.stream()
              .anyMatch(
                  existing ->
                      existing.segmentIndex() == jump.segmentIndex()
                          && Math.abs(existing.x() - jump.x()) < 0.1
                          && Math.abs(existing.y() - jump.y()) < 0.1
                          && existing.vertical() == jump.vertical());
      if (!exists) {
        deduped.add(jump);
      }
    }
    return deduped;
  }

  private static boolean nearlyEqual(double left, double right) {
    return Math.abs(left - right) < 0.001;
  }

  private static boolean insideSegment(double value, double start, double end) {
    double min = Math.min(start, end);
    double max = Math.max(start, end);
    return value > min && value < max;
  }

  private static EdgeLabel edgeLabel(
      LaidOutEdge edge, ResolvedEdgeStyle style, List<LabelBox> occupiedBoxes, double fontSize) {
    Optional<Segment> horizontal = firstHorizontalSegment(edge);
    if (horizontal.isPresent()) {
      Segment segment = horizontal.get();
      double direction = Math.signum(segment.end().x() - segment.start().x());
      if (direction == 0.0) {
        direction = 1.0;
      }
      double preferredX =
          switch (style.labelHorizontalPosition()) {
            case CENTER -> (segment.start().x() + segment.end().x()) / 2.0;
            case NEAR_END -> segment.end().x() - direction * 18.0;
            case NEAR_START -> segment.start().x() + direction * 18.0;
          };
      double centerX = (segment.start().x() + segment.end().x()) / 2.0;
      double nearStartX = segment.start().x() + direction * 18.0;
      double nearEndX = segment.end().x() - direction * 18.0;
      double baseOffset =
          switch (style.labelHorizontalSide()) {
            case ABOVE -> -10.0;
            case BELOW -> 18.0;
            case AUTO -> autoHorizontalLabelOffset(edge, segment.index());
          };
      List<Double> xCandidates = orderedValues(preferredX, centerX, nearStartX, nearEndX);
      List<Double> offsets = labelOffsetCandidates(baseOffset);
      for (double offset : offsets) {
        for (double x : xCandidates) {
          EdgeLabel candidate =
              edgeLabelCandidate(x, segment.start().y() + offset, "middle", edge.label(), fontSize);
          LabelBox candidateBox = edgeLabelVisibleBox(candidate, style.labelPresentation());
          if (occupiedBoxes.stream().noneMatch(candidateBox::overlaps)) {
            return candidate;
          }
        }
      }
      Optional<EdgeLabel> vertical = firstClearVerticalLabel(edge, style, occupiedBoxes, fontSize);
      if (vertical.isPresent()) {
        return vertical.get();
      }
      return edgeLabelCandidate(
          preferredX, segment.start().y() + baseOffset, "middle", edge.label(), fontSize);
    }
    List<EdgeLabel> candidates = verticalLabelCandidates(edge, style, fontSize);
    if (!candidates.isEmpty()) {
      for (EdgeLabel candidate : candidates) {
        LabelBox candidateBox = edgeLabelVisibleBox(candidate, style.labelPresentation());
        if (occupiedBoxes.stream().noneMatch(candidateBox::overlaps)) {
          return candidate;
        }
      }
      return candidates.get(0);
    }
    Point point =
        edge.points().isEmpty() ? new Point(0.0, 0.0) : edge.points().get(edge.points().size() / 2);
    return edgeLabelCandidate(point.x(), point.y() - 6.0, "middle", edge.label(), fontSize);
  }

  private static EdgeLabel edgeLabelCandidate(
      double x, double y, String anchor, String text, double fontSize) {
    return new EdgeLabel(x, y, anchor, labelBox(x, y, anchor, text, fontSize));
  }

  private static List<Double> orderedValues(double... values) {
    List<Double> ordered = new ArrayList<>();
    for (double value : values) {
      boolean exists = ordered.stream().anyMatch(existing -> Math.abs(existing - value) < 0.1);
      if (!exists) {
        ordered.add(value);
      }
    }
    return ordered;
  }

  private static List<Double> labelOffsetCandidates(double baseOffset) {
    double oppositeOffset = baseOffset < 0.0 ? 18.0 : -10.0;
    return orderedValues(
        baseOffset,
        oppositeOffset,
        baseOffset + 28.0,
        baseOffset - 28.0,
        baseOffset + 56.0,
        baseOffset - 56.0,
        baseOffset + 84.0,
        baseOffset - 84.0,
        baseOffset + 112.0,
        baseOffset - 112.0,
        baseOffset + 140.0,
        baseOffset - 140.0);
  }

  private static Optional<EdgeLabel> firstClearVerticalLabel(
      LaidOutEdge edge, ResolvedEdgeStyle style, List<LabelBox> occupiedBoxes, double fontSize) {
    for (EdgeLabel candidate : verticalLabelCandidates(edge, style, fontSize)) {
      LabelBox candidateBox = edgeLabelVisibleBox(candidate, style.labelPresentation());
      if (occupiedBoxes.stream().noneMatch(candidateBox::overlaps)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private static List<EdgeLabel> verticalLabelCandidates(
      LaidOutEdge edge, ResolvedEdgeStyle style, double fontSize) {
    List<Segment> verticalSegments = verticalSegments(edge);
    if (verticalSegments.isEmpty()) {
      return List.of();
    }
    if (firstHorizontalSegment(edge).isEmpty()) {
      Segment segment = verticalSegments.get(0);
      double minY = edge.points().stream().mapToDouble(Point::y).min().orElse(segment.start().y());
      double maxY = edge.points().stream().mapToDouble(Point::y).max().orElse(segment.end().y());
      return verticalLabelCandidates(
          edge, style, segment.start().x(), (minY + maxY) / 2.0, fontSize);
    }
    List<EdgeLabel> candidates = new ArrayList<>();
    for (Segment segment : verticalSegments) {
      candidates.addAll(
          verticalLabelCandidates(
              edge,
              style,
              segment.start().x(),
              (segment.start().y() + segment.end().y()) / 2.0,
              fontSize));
    }
    return candidates;
  }

  private static List<EdgeLabel> verticalLabelCandidates(
      LaidOutEdge edge, ResolvedEdgeStyle style, double segmentX, double y, double fontSize) {
    List<EdgeLabel> candidates = new ArrayList<>();
    List<SvgEdgeLabelVerticalSide> sides =
        style.labelVerticalSide() == SvgEdgeLabelVerticalSide.RIGHT
            ? List.of(SvgEdgeLabelVerticalSide.RIGHT, SvgEdgeLabelVerticalSide.LEFT)
            : List.of(SvgEdgeLabelVerticalSide.LEFT, SvgEdgeLabelVerticalSide.RIGHT);
    for (double offset : List.of(6.0, 34.0, 62.0)) {
      for (SvgEdgeLabelVerticalSide side : sides) {
        double x = side == SvgEdgeLabelVerticalSide.RIGHT ? segmentX + offset : segmentX - offset;
        String anchor = side == SvgEdgeLabelVerticalSide.RIGHT ? "start" : "end";
        candidates.add(edgeLabelCandidate(x, y, anchor, edge.label(), fontSize));
      }
    }
    return candidates;
  }

  private static double autoHorizontalLabelOffset(LaidOutEdge edge, int segmentIndex) {
    if (segmentIndex + 2 < edge.points().size()) {
      Point segmentStart = edge.points().get(segmentIndex);
      Point next = edge.points().get(segmentIndex + 2);
      if (next.y() < segmentStart.y()) {
        return -10.0;
      }
    }
    return 18.0;
  }

  private static Optional<Segment> firstHorizontalSegment(LaidOutEdge edge) {
    if (edge.routingHints().contains("shared_source_junction")) {
      for (int index = edge.points().size() - 2; index >= 0; index--) {
        Point start = edge.points().get(index);
        Point end = edge.points().get(index + 1);
        if (nearlyEqual(start.y(), end.y()) && Math.abs(start.x() - end.x()) > 0.001) {
          return Optional.of(new Segment(index, start, end));
        }
      }
    }
    for (int index = 0; index < edge.points().size() - 1; index++) {
      Point start = edge.points().get(index);
      Point end = edge.points().get(index + 1);
      if (nearlyEqual(start.y(), end.y()) && Math.abs(start.x() - end.x()) > 0.001) {
        return Optional.of(new Segment(index, start, end));
      }
    }
    return Optional.empty();
  }

  private static List<Segment> verticalSegments(LaidOutEdge edge) {
    List<Segment> segments = new ArrayList<>();
    for (int index = 0; index < edge.points().size() - 1; index++) {
      Point start = edge.points().get(index);
      Point end = edge.points().get(index + 1);
      if (nearlyEqual(start.x(), end.x()) && Math.abs(start.y() - end.y()) > 0.001) {
        segments.add(new Segment(index, start, end));
      }
    }
    return segments;
  }

  private static SvgBounds svgBounds(
      LayoutResult result, RenderMetadata metadata, RenderPolicy policy, ResolvedStyle base) {
    var bounds = SvgBounds.empty();
    for (LaidOutGroup group : result.groups()) {
      bounds.includeRect(group.x(), group.y(), group.width(), group.height());
    }
    for (LaidOutEdge edge : result.edges()) {
      for (Point point : edge.points()) {
        bounds.includePoint(point.x(), point.y());
      }
    }
    for (LaidOutNode node : result.nodes()) {
      bounds.includeRect(node.x(), node.y(), node.width(), node.height());
      ResolvedNodeStyle style = nodeStyle(policy, metadata, node.id(), base);
      if (shouldRenderPlainNodeLabel(node, style.decorator())) {
        for (LabelBox labelBox : nodeLabelBoxes(node, style, base.fontSize())) {
          bounds.includeRect(labelBox.minX(), labelBox.minY(), labelBox.width(), labelBox.height());
        }
      }
    }
    List<LabelBox> placedLabelBoxes = new ArrayList<>();
    for (int edgeIndex = 0; edgeIndex < result.edges().size(); edgeIndex++) {
      LaidOutEdge edge = result.edges().get(edgeIndex);
      if (edge.label() == null || edge.label().isEmpty()) {
        continue;
      }
      ResolvedEdgeStyle style = edgeStyle(policy, metadata, edge.id(), base);
      EdgeLabel label =
          edgeLabel(
              edge,
              style,
              labelObstacleBoxesForEdge(result, edgeIndex, placedLabelBoxes),
              edgeLabelFontSize(base.fontSize()));
      LabelBox labelBox = edgeLabelVisibleBox(label, style.labelPresentation());
      bounds.includeRect(labelBox.minX(), labelBox.minY(), labelBox.width(), labelBox.height());
      placedLabelBoxes.add(labelBox);
    }
    if (bounds.isEmpty()) {
      bounds.includeRect(0.0, 0.0, policy.page().width(), policy.page().height());
    }
    return bounds.padded(policy);
  }

  private static List<LabelBox> nodeObstacleBoxes(LayoutResult result) {
    List<LabelBox> boxes = new ArrayList<>();
    for (LaidOutNode node : result.nodes()) {
      boxes.add(
          new LabelBox(node.x(), node.y(), node.x() + node.width(), node.y() + node.height()));
    }
    return boxes;
  }

  private static List<LabelBox> labelObstacleBoxesForEdge(
      LayoutResult result, int currentEdgeIndex, List<LabelBox> placedLabelBoxes) {
    List<LabelBox> boxes = nodeObstacleBoxes(result);
    boxes.addAll(groupObstacleBoxes(result));
    boxes.addAll(edgeRouteObstacleBoxesForOtherEdges(result.edges(), currentEdgeIndex));
    boxes.addAll(placedLabelBoxes);
    return boxes;
  }

  private static List<LabelBox> groupObstacleBoxes(LayoutResult result) {
    List<LabelBox> boxes = new ArrayList<>();
    for (LaidOutGroup group : result.groups()) {
      double minX = group.x();
      double minY = group.y();
      double maxX = group.x() + group.width();
      double maxY = group.y() + group.height();
      double padding = GROUP_BORDER_LABEL_OBSTACLE_PADDING;
      boxes.add(new LabelBox(minX, minY, maxX, minY + GROUP_TITLE_LABEL_OBSTACLE_HEIGHT));
      boxes.add(new LabelBox(minX - padding, minY - padding, maxX + padding, minY + padding));
      boxes.add(new LabelBox(minX - padding, maxY - padding, maxX + padding, maxY + padding));
      boxes.add(new LabelBox(minX - padding, minY - padding, minX + padding, maxY + padding));
      boxes.add(new LabelBox(maxX - padding, minY - padding, maxX + padding, maxY + padding));
    }
    return boxes;
  }

  private static List<LabelBox> edgeRouteObstacleBoxes(List<LaidOutEdge> edges) {
    List<LabelBox> boxes = new ArrayList<>();
    for (LaidOutEdge edge : edges) {
      for (int index = 0; index < edge.points().size() - 1; index++) {
        Point start = edge.points().get(index);
        Point end = edge.points().get(index + 1);
        if (nearlyEqual(start.x(), end.x()) && nearlyEqual(start.y(), end.y())) {
          continue;
        }
        double minX = Math.min(start.x(), end.x());
        double maxX = Math.max(start.x(), end.x());
        double minY = Math.min(start.y(), end.y());
        double maxY = Math.max(start.y(), end.y());
        double padding = EDGE_ROUTE_LABEL_OBSTACLE_PADDING;
        if (nearlyEqual(start.y(), end.y())) {
          boxes.add(new LabelBox(minX, start.y(), maxX, start.y()).expanded(padding, padding));
        } else if (nearlyEqual(start.x(), end.x())) {
          boxes.add(new LabelBox(start.x(), minY, start.x(), maxY).expanded(padding, padding));
        } else {
          boxes.add(new LabelBox(minX - padding, minY - padding, maxX + padding, maxY + padding));
        }
      }
    }
    return boxes;
  }

  private static List<LabelBox> edgeRouteObstacleBoxesForOtherEdges(
      List<LaidOutEdge> edges, int currentEdgeIndex) {
    List<LabelBox> boxes = new ArrayList<>();
    for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
      if (edgeIndex == currentEdgeIndex) {
        continue;
      }
      boxes.addAll(edgeRouteObstacleBoxes(List.of(edges.get(edgeIndex))));
    }
    return boxes;
  }

  private static LabelBox labelBox(
      double x, double y, String anchor, String text, double fontSize) {
    double width = (text == null ? 0 : text.length()) * fontSize * 0.56;
    double minX =
        switch (anchor) {
          case "end" -> x - width;
          case "middle" -> x - width / 2.0;
          default -> x;
        };
    double minY = y - fontSize;
    return new LabelBox(minX, minY, minX + width, y + fontSize * 0.25);
  }

  private static List<LabelBox> nodeLabelBoxes(
      LaidOutNode node, ResolvedNodeStyle style, double fontSize) {
    NodeLabelLines label = nodeLabelLinesAndSize(node, style, fontSize);
    NodeLabelPosition position =
        nodeLabelPosition(node, style, label.fontSize(), label.lines().size());
    List<LabelBox> boxes = new ArrayList<>();
    double lineHeight = nodeLabelLineHeight(label.fontSize());
    for (int index = 0; index < label.lines().size(); index++) {
      double y = position.y() + index * lineHeight;
      boxes.add(nodeLabelBox(position.x(), y, label.lines().get(index), label.fontSize()));
    }
    return boxes;
  }

  private static LabelBox nodeLabelBox(double x, double y, String text, double fontSize) {
    double width = estimateTextWidth(text, fontSize);
    double minX = x - width / 2.0;
    double minY = y - fontSize;
    return new LabelBox(minX, minY, minX + width, y + fontSize * 0.25);
  }

  private static ResolvedStyle baseStyle(RenderPolicy policy) {
    SvgStylePolicy style = policy.style();
    var defaultNode = new ResolvedNodeStyle("#f8fafc", "#334155", 1.5, 6.0, "#0f172a", null);
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
            SvgEdgeLabelPresentation.OUTLINE);
    var defaultGroup =
        new ResolvedGroupStyle("#eff6ff", "#93c5fd", 1.0, 8.0, "#1e3a8a", 12.0, null);
    return new ResolvedStyle(
        Optional.ofNullable(style)
            .map(SvgStylePolicy::background)
            .map(SvgBackgroundStyle::fill)
            .orElse("#ffffff"),
        Optional.ofNullable(style)
            .map(SvgStylePolicy::font)
            .map(SvgFontStyle::family)
            .orElse("Inter, Arial, sans-serif"),
        Optional.ofNullable(style).map(SvgStylePolicy::font).map(SvgFontStyle::size).orElse(14.0),
        mergeNodeStyle(defaultNode, style == null ? null : style.node()),
        mergeEdgeStyle(defaultEdge, style == null ? null : style.edge()),
        mergeGroupStyle(defaultGroup, style == null ? null : style.group()));
  }

  private static ResolvedNodeStyle nodeStyle(
      RenderPolicy policy, RenderMetadata metadata, String nodeId, ResolvedStyle base) {
    SvgStylePolicy style = policy.style();
    SvgNodeStyle typeStyle = null;
    if (style != null && metadata != null && metadata.nodes().containsKey(nodeId)) {
      typeStyle = style.nodeTypeOverrides().get(metadata.nodes().get(nodeId).type());
    }
    ResolvedNodeStyle resolved = mergeNodeStyle(base.node(), typeStyle);
    return mergeNodeStyle(resolved, style == null ? null : style.nodeOverrides().get(nodeId));
  }

  private static ResolvedEdgeStyle edgeStyle(
      RenderPolicy policy, RenderMetadata metadata, String edgeId, ResolvedStyle base) {
    SvgStylePolicy style = policy.style();
    SvgEdgeStyle typeStyle = null;
    if (style != null && metadata != null && metadata.edges().containsKey(edgeId)) {
      typeStyle = style.edgeTypeOverrides().get(metadata.edges().get(edgeId).type());
    }
    ResolvedEdgeStyle resolved = mergeEdgeStyle(base.edge(), typeStyle);
    return mergeEdgeStyle(resolved, style == null ? null : style.edgeOverrides().get(edgeId));
  }

  private static ResolvedGroupStyle groupStyle(
      RenderPolicy policy, RenderMetadata metadata, String groupId, ResolvedStyle base) {
    SvgStylePolicy style = policy.style();
    SvgGroupStyle typeStyle = null;
    if (style != null && metadata != null && metadata.groups().containsKey(groupId)) {
      typeStyle = style.groupTypeOverrides().get(metadata.groups().get(groupId).type());
    }
    ResolvedGroupStyle resolved = mergeGroupStyle(base.group(), typeStyle);
    return mergeGroupStyle(resolved, style == null ? null : style.groupOverrides().get(groupId));
  }

  private static ResolvedNodeStyle mergeNodeStyle(ResolvedNodeStyle base, SvgNodeStyle override) {
    if (override == null) {
      return base;
    }
    return new ResolvedNodeStyle(
        override.fill() == null ? base.fill() : override.fill(),
        override.stroke() == null ? base.stroke() : override.stroke(),
        override.strokeWidth() == null ? base.strokeWidth() : override.strokeWidth(),
        override.rx() == null ? base.rx() : override.rx(),
        override.labelFill() == null ? base.labelFill() : override.labelFill(),
        override.decorator() == null ? base.decorator() : override.decorator());
  }

  private static ResolvedEdgeStyle mergeEdgeStyle(ResolvedEdgeStyle base, SvgEdgeStyle override) {
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
            : override.labelPresentation());
  }

  private static ResolvedGroupStyle mergeGroupStyle(
      ResolvedGroupStyle base, SvgGroupStyle override) {
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
        override.decorator() == null ? base.decorator() : override.decorator());
  }

  private record RenderInput(
      LayoutResult layoutResult, RenderMetadata renderMetadata, RenderPolicy policy) {}

  private record Segment(int index, Point start, Point end) {}

  private record RoundedCorner(Point before, Point after) {}

  private record EdgeLabel(double x, double y, String anchor, LabelBox bounds) {}

  private record NodeLabelLines(List<String> lines, double fontSize) {}

  private record NodeLabelPosition(double x, double y, boolean centerBaseline) {}

  private record LabelBox(double minX, double minY, double maxX, double maxY) {
    boolean overlaps(LabelBox other) {
      return minX < other.maxX && maxX > other.minX && minY < other.maxY && maxY > other.minY;
    }

    LabelBox expanded(double horizontalPadding, double verticalPadding) {
      return new LabelBox(
          minX - horizontalPadding,
          minY - verticalPadding,
          maxX + horizontalPadding,
          maxY + verticalPadding);
    }

    double width() {
      return maxX - minX;
    }

    double height() {
      return maxY - minY;
    }
  }

  private record LineJump(int segmentIndex, double x, double y, boolean vertical) {
    String pathPrefix(Point start, Point end) {
      if (vertical) {
        double before = y + (start.y() < end.y() ? -6.0 : 6.0);
        double after = y + (start.y() < end.y() ? 6.0 : -6.0);
        double controlX = x + 6.0;
        return String.format(
            Locale.ROOT, "L %.1f %.1f Q %.1f %.1f %.1f %.1f", x, before, controlX, y, x, after);
      }
      double before = x + (start.x() < end.x() ? -6.0 : 6.0);
      double after = x + (start.x() < end.x() ? 6.0 : -6.0);
      double controlY = y - 6.0;
      return String.format(
          Locale.ROOT, "L %.1f %.1f Q %.1f %.1f %.1f %.1f", before, y, x, controlY, after, y);
    }

    String maskPath() {
      if (vertical) {
        return String.format(
            Locale.ROOT, "M %.1f %.1f Q %.1f %.1f %.1f %.1f", x, y - 6.0, x + 6.0, y, x, y + 6.0);
      }
      return String.format(
          Locale.ROOT, "M %.1f %.1f Q %.1f %.1f %.1f %.1f", x - 6.0, y, x, y - 6.0, x + 6.0, y);
    }
  }

  private static final class SvgBounds {
    private double minX;
    private double minY;
    private double maxX;
    private double maxY;

    private SvgBounds(double minX, double minY, double maxX, double maxY) {
      this.minX = minX;
      this.minY = minY;
      this.maxX = maxX;
      this.maxY = maxY;
    }

    static SvgBounds empty() {
      return new SvgBounds(
          Double.POSITIVE_INFINITY,
          Double.POSITIVE_INFINITY,
          Double.NEGATIVE_INFINITY,
          Double.NEGATIVE_INFINITY);
    }

    boolean isEmpty() {
      return !Double.isFinite(minX)
          || !Double.isFinite(minY)
          || !Double.isFinite(maxX)
          || !Double.isFinite(maxY);
    }

    void includeRect(double x, double y, double width, double height) {
      includePoint(x, y);
      includePoint(x + width, y + height);
    }

    void includePoint(double x, double y) {
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
    }

    SvgBounds padded(RenderPolicy policy) {
      return new SvgBounds(
          minX - policy.margin().left(),
          minY - policy.margin().top(),
          maxX + policy.margin().right(),
          maxY + policy.margin().bottom());
    }

    double width() {
      return maxX - minX;
    }

    double height() {
      return maxY - minY;
    }

    double minX() {
      return minX;
    }

    double minY() {
      return minY;
    }

    double maxX() {
      return maxX;
    }

    double maxY() {
      return maxY;
    }
  }

  private record ResolvedStyle(
      String backgroundFill,
      String fontFamily,
      double fontSize,
      ResolvedNodeStyle node,
      ResolvedEdgeStyle edge,
      ResolvedGroupStyle group) {}

  private record ResolvedNodeStyle(
      String fill,
      String stroke,
      double strokeWidth,
      double rx,
      String labelFill,
      SvgNodeDecorator decorator) {}

  private record ResolvedEdgeStyle(
      String stroke,
      double strokeWidth,
      String labelFill,
      SvgEdgeLineStyle lineStyle,
      SvgEdgeMarkerEnd markerStart,
      SvgEdgeMarkerEnd markerEnd,
      SvgEdgeLabelHorizontalPosition labelHorizontalPosition,
      SvgEdgeLabelHorizontalSide labelHorizontalSide,
      SvgEdgeLabelVerticalPosition labelVerticalPosition,
      SvgEdgeLabelVerticalSide labelVerticalSide,
      SvgEdgeLabelPresentation labelPresentation) {}

  private record ResolvedGroupStyle(
      String fill,
      String stroke,
      double strokeWidth,
      double rx,
      String labelFill,
      double labelSize,
      SvgNodeDecorator decorator) {}
}

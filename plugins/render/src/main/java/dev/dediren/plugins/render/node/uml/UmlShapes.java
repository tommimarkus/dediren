package dev.dediren.plugins.render.node.uml;

import static dev.dediren.plugins.render.node.NodeShapeSupport.decoratorName;
import static dev.dediren.plugins.render.node.uml.UmlDecorators.textField;
import static dev.dediren.plugins.render.svg.Svg.attr;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;
import static dev.dediren.plugins.render.svg.Svg.text;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import java.util.Locale;

// lean-audit:dup-intentional per-shape SVG path builders are deliberately parallel declarative code
public final class UmlShapes {

  private UmlShapes() {}

  public static String umlNodeShape(
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

  public static String umlDeploymentTargetShape(
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

  public static String umlArtifactShape(
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

  public static String umlActorShape(LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
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

  public static String umlUseCaseShape(
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

  public static String umlFinalStateShape(
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

  public static String umlPseudostateShape(
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

  public static String umlFilledCircleShape(
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

  public static String umlDiamondShape(
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

  public static String umlBarShape(LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
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

  public static String umlTextCircleShape(
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
}

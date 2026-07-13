package dev.dediren.plugins.render.node.uml;

import static dev.dediren.plugins.render.node.NodeShapeSupport.decoratorName;
import static dev.dediren.plugins.render.node.uml.UmlDecorators.textField;
import static dev.dediren.plugins.render.svg.Svg.f1;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.svg.SvgWriter;
import java.util.Locale;

// lean-audit:dup-intentional per-shape SVG path builders are deliberately parallel declarative code
public final class UmlShapes {

  private UmlShapes() {}

  public static void umlNodeShape(
      SvgWriter w,
      LaidOutNode node,
      ResolvedNodeStyle style,
      SvgNodeDecorator decorator,
      RenderMetadataSelector selector) {
    String shapeName = decoratorName(decorator);
    switch (decorator) {
      case UML_INITIAL_NODE -> umlFilledCircleShape(w, node, style, shapeName);
      case UML_ACTIVITY_FINAL_NODE -> umlFinalStateShape(w, node, style, shapeName);
      case UML_STATE -> umlNodeRect(w, node, style, shapeName, Math.max(style.rx(), 14.0));
      case UML_FINAL_STATE -> umlFinalStateShape(w, node, style, shapeName);
      case UML_PSEUDOSTATE -> umlPseudostateShape(w, node, style, selector, shapeName);
      case UML_ACTOR -> umlActorShape(w, node, style, shapeName);
      case UML_USE_CASE -> umlUseCaseShape(w, node, style, shapeName);
      case UML_EXTENSION_POINT -> umlNodeRect(w, node, style, shapeName, Math.max(style.rx(), 2.0));
      case UML_COMPONENT, UML_PORT -> umlNodeRect(w, node, style, shapeName, style.rx());
      case UML_NODE, UML_DEVICE, UML_EXECUTION_ENVIRONMENT ->
          umlDeploymentTargetShape(w, node, style, shapeName);
      case UML_ARTIFACT, UML_DEPLOYMENT_SPECIFICATION ->
          umlArtifactShape(w, node, style, shapeName);
      case UML_DECISION_NODE, UML_MERGE_NODE -> umlDiamondShape(w, node, style, shapeName);
      case UML_FORK_NODE, UML_JOIN_NODE -> umlBarShape(w, node, style, shapeName);
      case UML_PACKAGE -> umlPackageShape(w, node, style, shapeName);
      case UML_ACTION -> umlNodeRect(w, node, style, shapeName, Math.max(style.rx(), 10.0));
      default -> umlNodeRect(w, node, style, shapeName, style.rx());
    }
  }

  private static void umlNodeRect(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, String shapeName, double rx) {
    w.empty("rect")
        .attr("data-dediren-node-shape", shapeName)
        .attr("x", f1(node.x()))
        .attr("y", f1(node.y()))
        .attr("width", f1(node.width()))
        .attr("height", f1(node.height()))
        .attr("rx", styleNumber(rx))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }

  private static void umlPackageShape(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double tabWidth = Math.max(40.0, Math.min(96.0, node.width() * 0.34));
    double tabHeight = Math.max(14.0, Math.min(24.0, node.height() * 0.18));
    w.empty("path")
        .attr("data-dediren-node-shape", shapeName)
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f H %.1f V %.1f H %.1f V %.1f H %.1f V %.1f H %.1f Z",
                node.x(),
                node.y(),
                node.x() + tabWidth,
                node.y() + tabHeight,
                node.x() + node.width(),
                node.y() + node.height(),
                node.x(),
                node.y() + tabHeight,
                node.x()))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }

  public static void umlDeploymentTargetShape(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double depth = Math.max(10.0, Math.min(18.0, Math.min(node.width(), node.height()) * 0.13));
    double frontX = node.x();
    double frontY = node.y() + depth;
    double frontWidth = Math.max(20.0, node.width() - depth);
    double frontHeight = Math.max(20.0, node.height() - depth);
    w.start("g").attr("data-dediren-node-shape", shapeName);
    w.empty("rect")
        .attr("x", f1(frontX))
        .attr("y", f1(frontY))
        .attr("width", f1(frontWidth))
        .attr("height", f1(frontHeight))
        .attr("rx", styleNumber(style.rx()))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
    w.empty("path")
        .attr("data-dediren-deployment-target-part", "top")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
                frontX,
                frontY,
                frontX + depth,
                node.y(),
                frontX + frontWidth + depth,
                node.y(),
                frontX + frontWidth,
                frontY))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
    w.empty("path")
        .attr("data-dediren-deployment-target-part", "side")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
                frontX + frontWidth,
                frontY,
                frontX + frontWidth + depth,
                node.y(),
                frontX + frontWidth + depth,
                node.y() + frontHeight,
                frontX + frontWidth,
                frontY + frontHeight))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
    w.end();
  }

  public static void umlArtifactShape(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double fold = Math.max(12.0, Math.min(22.0, Math.min(node.width(), node.height()) * 0.24));
    w.start("g").attr("data-dediren-node-shape", shapeName);
    w.empty("path")
        .attr("data-dediren-artifact-part", "body")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f H %.1f L %.1f %.1f V %.1f H %.1f Z",
                node.x(),
                node.y(),
                node.x() + node.width() - fold,
                node.x() + node.width(),
                node.y() + fold,
                node.y() + node.height(),
                node.x()))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
    w.empty("path")
        .attr("data-dediren-artifact-part", "fold")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f V %.1f H %.1f",
                node.x() + node.width() - fold,
                node.y(),
                node.y() + fold,
                node.x() + node.width()))
        .attr("fill", "none")
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
    w.end();
  }

  public static void umlActorShape(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double centerX = node.x() + node.width() / 2.0;
    double headRadius = Math.max(7.0, Math.min(node.width(), node.height()) * 0.11);
    double headCenterY = node.y() + headRadius + 10.0;
    double bodyTopY = headCenterY + headRadius;
    double bodyBottomY = node.y() + node.height() * 0.58;
    double armY = node.y() + node.height() * 0.38;
    double armSpan = node.width() * 0.62;
    double legSpan = node.width() * 0.44;
    double legBottomY = node.y() + node.height() * 0.78;
    w.start("g").attr("data-dediren-node-shape", shapeName);
    w.empty("circle")
        .attr("cx", f1(centerX))
        .attr("cy", f1(headCenterY))
        .attr("r", f1(headRadius))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
    w.empty("path")
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f L %.1f %.1f M %.1f %.1f"
                    + " L %.1f %.1f",
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
                legBottomY))
        .attr("fill", "none")
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()))
        .attr("stroke-linecap", "round");
    w.end();
  }

  public static void umlUseCaseShape(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    w.empty("ellipse")
        .attr("data-dediren-node-shape", shapeName)
        .attr("cx", f1(node.x() + node.width() / 2.0))
        .attr("cy", f1(node.y() + node.height() / 2.0))
        .attr("rx", f1(Math.max(8.0, node.width() / 2.0)))
        .attr("ry", f1(Math.max(8.0, node.height() / 2.0)))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }

  public static void umlFinalStateShape(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double centerX = node.x() + node.width() / 2.0;
    double centerY = node.y() + node.height() / 2.0;
    double radius =
        Math.max(5.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
    double innerRadius = Math.max(3.0, radius * 0.48);
    w.start("g").attr("data-dediren-node-shape", shapeName);
    w.empty("circle")
        .attr("cx", f1(centerX))
        .attr("cy", f1(centerY))
        .attr("r", f1(radius))
        .attr("fill", "#ffffff")
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
    w.empty("circle")
        .attr("cx", f1(centerX))
        .attr("cy", f1(centerY))
        .attr("r", f1(innerRadius))
        .attr("fill", style.stroke());
    w.end();
  }

  public static void umlPseudostateShape(
      SvgWriter w,
      LaidOutNode node,
      ResolvedNodeStyle style,
      RenderMetadataSelector selector,
      String shapeName) {
    String kind = textField(selector == null ? null : selector.properties(), "kind", "initial");
    switch (kind) {
      case "choice", "junction" -> umlDiamondShape(w, node, style, shapeName);
      case "fork", "join" -> umlBarShape(w, node, style, shapeName);
      case "deepHistory" -> umlTextCircleShape(w, node, style, shapeName, "H*");
      case "shallowHistory" -> umlTextCircleShape(w, node, style, shapeName, "H");
      case "entryPoint" -> umlTextCircleShape(w, node, style, shapeName, "E");
      case "exitPoint", "terminate" -> umlTextCircleShape(w, node, style, shapeName, "X");
      default -> umlFilledCircleShape(w, node, style, shapeName);
    }
  }

  public static void umlFilledCircleShape(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double radius =
        Math.max(4.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
    w.empty("circle")
        .attr("data-dediren-node-shape", shapeName)
        .attr("cx", f1(node.x() + node.width() / 2.0))
        .attr("cy", f1(node.y() + node.height() / 2.0))
        .attr("r", f1(radius))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }

  public static void umlDiamondShape(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    double centerX = node.x() + node.width() / 2.0;
    double centerY = node.y() + node.height() / 2.0;
    w.empty("path")
        .attr("data-dediren-node-shape", shapeName)
        .attr(
            "d",
            String.format(
                Locale.ROOT,
                "M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z",
                centerX,
                node.y(),
                node.x() + node.width(),
                centerY,
                centerX,
                node.y() + node.height(),
                node.x(),
                centerY))
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }

  public static void umlBarShape(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, String shapeName) {
    boolean horizontal = node.width() >= node.height();
    double width = horizontal ? node.width() : Math.min(node.width(), 14.0);
    double height = horizontal ? Math.min(node.height(), 14.0) : node.height();
    double x = node.x() + (node.width() - width) / 2.0;
    double y = node.y() + (node.height() - height) / 2.0;
    w.empty("rect")
        .attr("data-dediren-node-shape", shapeName)
        .attr("x", f1(x))
        .attr("y", f1(y))
        .attr("width", f1(width))
        .attr("height", f1(height))
        .attr("rx", "0")
        .attr("fill", style.fill())
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
  }

  public static void umlTextCircleShape(
      SvgWriter w, LaidOutNode node, ResolvedNodeStyle style, String shapeName, String symbol) {
    double centerX = node.x() + node.width() / 2.0;
    double centerY = node.y() + node.height() / 2.0;
    double radius =
        Math.max(5.0, Math.min(node.width(), node.height()) / 2.0 - style.strokeWidth());
    double fontSize = Math.max(10.0, Math.min(14.0, radius * 0.9));
    w.empty("circle")
        .attr("data-dediren-node-shape", shapeName)
        .attr("cx", f1(centerX))
        .attr("cy", f1(centerY))
        .attr("r", f1(radius))
        .attr("fill", "#ffffff")
        .attr("stroke", style.stroke())
        .attr("stroke-width", styleNumber(style.strokeWidth()));
    w.start("text")
        .attr("x", f1(centerX))
        .attr("y", f1(centerY + fontSize / 3.0))
        .attr("text-anchor", "middle")
        .attr("fill", style.labelFill())
        .attr("font-size", styleNumber(fontSize))
        .text(symbol)
        .end();
  }
}

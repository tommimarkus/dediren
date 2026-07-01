package dev.dediren.plugins.render.svg;

import static dev.dediren.plugins.render.node.NodeLabels.nodeLabelBoxes;
import static dev.dediren.plugins.render.node.NodeShapeSupport.shouldRenderPlainNodeLabel;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabel;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabelFontSize;
import static dev.dediren.plugins.render.svg.EdgeRenderer.edgeLabelVisibleBox;
import static dev.dediren.plugins.render.svg.EdgeRenderer.nearlyEqual;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.plugins.render.style.ResolvedEdgeStyle;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import dev.dediren.plugins.render.style.ResolvedStyle;
import dev.dediren.plugins.render.style.StyleResolver;
import java.util.ArrayList;
import java.util.List;

public final class Geometry {

  private Geometry() {}

  private static final double EDGE_ROUTE_LABEL_OBSTACLE_PADDING = 6.0;
  private static final double GROUP_BORDER_LABEL_OBSTACLE_PADDING = 4.0;
  private static final double GROUP_TITLE_LABEL_OBSTACLE_HEIGHT = 24.0;

  public static SvgBounds svgBounds(
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
      ResolvedNodeStyle style = StyleResolver.nodeStyle(policy, metadata, node.id(), base);
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
      ResolvedEdgeStyle style = StyleResolver.edgeStyle(policy, metadata, edge.id(), base);
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

  public static List<LabelBox> nodeObstacleBoxes(LayoutResult result) {
    List<LabelBox> boxes = new ArrayList<>();
    for (LaidOutNode node : result.nodes()) {
      boxes.add(
          new LabelBox(node.x(), node.y(), node.x() + node.width(), node.y() + node.height()));
    }
    return boxes;
  }

  public static List<LabelBox> labelObstacleBoxesForEdge(
      LayoutResult result, int currentEdgeIndex, List<LabelBox> placedLabelBoxes) {
    List<LabelBox> boxes = nodeObstacleBoxes(result);
    boxes.addAll(groupObstacleBoxes(result));
    boxes.addAll(edgeRouteObstacleBoxesForOtherEdges(result.edges(), currentEdgeIndex));
    boxes.addAll(placedLabelBoxes);
    return boxes;
  }

  public static List<LabelBox> groupObstacleBoxes(LayoutResult result) {
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

  public static List<LabelBox> edgeRouteObstacleBoxes(List<LaidOutEdge> edges) {
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

  public static List<LabelBox> edgeRouteObstacleBoxesForOtherEdges(
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

  public static LabelBox labelBox(double x, double y, String anchor, String text, double fontSize) {
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
}

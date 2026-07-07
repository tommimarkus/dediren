package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.elk.core.options.PortSide;

final class SequenceLayoutConstraints {
  private static final String LIFELINE_ORDER_KIND = "uml.sequence.lifeline-order";
  private static final String MESSAGE_ORDER_KIND = "uml.sequence.message-order";
  private static final String FRAGMENT_OPEN_KIND = "uml.sequence.fragment-open";
  private static final String OPERAND_OPEN_KIND = "uml.sequence.operand-open";
  private static final double MINIMUM_MESSAGE_Y_STEP = 1.0;
  private static final double MESSAGE_HEAD_GAP = 24.0;
  private static final double MESSAGE_Y_STEP = 24.0;
  private static final double LIFELINE_COLUMN_GAP = 96.0;
  // Extra vertical room reserved before a message that opens a combined fragment (header band +
  // first-operand guard) or a non-first operand (separator line + guard). Coupled with the
  // renderer's FRAGMENT_VERTICAL_PADDING; kept in sync so the render chrome clears message labels.
  private static final double FRAGMENT_OPEN_GAP = 46.0;
  private static final double OPERAND_OPEN_GAP = 68.0;

  private final List<String> lifelineOrder;
  private final List<String> messageOrder;
  private final Map<String, Integer> lifelineIndexById;
  private final Map<String, Integer> messageIndexById;
  private final Set<String> fragmentOpenIds;
  private final Set<String> operandOpenIds;

  private SequenceLayoutConstraints(
      List<String> lifelineOrder,
      List<String> messageOrder,
      List<String> fragmentOpenIds,
      List<String> operandOpenIds) {
    this.lifelineOrder = List.copyOf(lifelineOrder);
    this.messageOrder = List.copyOf(messageOrder);
    this.lifelineIndexById = indexById(this.lifelineOrder);
    this.messageIndexById = indexById(this.messageOrder);
    this.fragmentOpenIds = Set.copyOf(fragmentOpenIds);
    this.operandOpenIds = Set.copyOf(operandOpenIds);
  }

  static SequenceLayoutConstraints from(LayoutRequest request) {
    List<String> lifelineOrder = List.of();
    List<String> messageOrder = List.of();
    List<String> fragmentOpenIds = List.of();
    List<String> operandOpenIds = List.of();
    for (LayoutConstraint constraint : request.constraints()) {
      if (LIFELINE_ORDER_KIND.equals(constraint.kind())) {
        lifelineOrder = constraint.subjects();
      } else if (MESSAGE_ORDER_KIND.equals(constraint.kind())) {
        messageOrder = constraint.subjects();
      } else if (FRAGMENT_OPEN_KIND.equals(constraint.kind())) {
        fragmentOpenIds = constraint.subjects();
      } else if (OPERAND_OPEN_KIND.equals(constraint.kind())) {
        operandOpenIds = constraint.subjects();
      }
    }
    return new SequenceLayoutConstraints(
        lifelineOrder, messageOrder, fragmentOpenIds, operandOpenIds);
  }

  boolean active() {
    return !lifelineOrder.isEmpty() && !messageOrder.isEmpty();
  }

  List<LayoutNode> orderedNodes(List<LayoutNode> nodes) {
    if (!active()) {
      return nodes;
    }
    List<LayoutNode> ordered = new ArrayList<>(nodes);
    ordered.sort(
        Comparator.comparingInt(
            node -> lifelineIndexById.getOrDefault(node.id(), Integer.MAX_VALUE)));
    return ordered;
  }

  List<LayoutEdge> orderedEdges(List<LayoutEdge> edges) {
    if (!active()) {
      return edges;
    }
    List<LayoutEdge> ordered = new ArrayList<>(edges);
    ordered.sort(
        Comparator.comparingInt(
            edge -> messageIndexById.getOrDefault(edge.id(), Integer.MAX_VALUE)));
    return ordered;
  }

  PortSide sourcePortSide(LayoutEdge edge, PortSide fallback) {
    Integer sourceIndex = lifelineIndexById.get(edge.source());
    Integer targetIndex = lifelineIndexById.get(edge.target());
    if (sourceIndex == null || targetIndex == null || sourceIndex.equals(targetIndex)) {
      return fallback;
    }
    return sourceIndex < targetIndex ? PortSide.EAST : PortSide.WEST;
  }

  PortSide targetPortSide(LayoutEdge edge, PortSide fallback) {
    Integer sourceIndex = lifelineIndexById.get(edge.source());
    Integer targetIndex = lifelineIndexById.get(edge.target());
    if (sourceIndex == null || targetIndex == null || sourceIndex.equals(targetIndex)) {
      return fallback;
    }
    return sourceIndex < targetIndex ? PortSide.WEST : PortSide.EAST;
  }

  LayoutResult normalize(LayoutResult result) {
    if (!active()) {
      return result;
    }
    List<LaidOutNode> normalizedNodes = normalizedLifelineNodes(result.nodes());
    Map<String, LaidOutNode> normalizedById = nodesById(normalizedNodes);
    List<LaidOutEdge> normalizedEdges = normalizedMessageEdges(result.edges(), normalizedById);
    List<LaidOutNode> wrappedNodes =
        normalizedInteractionNodes(normalizedNodes, normalizedById, normalizedEdges);
    return new LayoutResult(
        result.layoutResultSchemaVersion(),
        result.viewId(),
        wrappedNodes,
        normalizedEdges,
        result.groups(),
        result.warnings());
  }

  private List<LaidOutNode> normalizedInteractionNodes(
      List<LaidOutNode> nodes, Map<String, LaidOutNode> byId, List<LaidOutEdge> edges) {
    List<LaidOutNode> lifelines = new ArrayList<>();
    for (String id : lifelineOrder) {
      LaidOutNode node = byId.get(id);
      if (node != null) {
        lifelines.add(node);
      }
    }
    if (lifelines.isEmpty()) {
      return nodes;
    }

    double top = lifelines.stream().mapToDouble(LaidOutNode::y).min().orElse(0.0);
    double left = lifelines.stream().mapToDouble(LaidOutNode::x).min().orElse(0.0);
    double right =
        lifelines.stream().mapToDouble(node -> node.x() + node.width()).max().orElse(left);
    double bottom =
        lifelines.stream().mapToDouble(node -> node.y() + node.height()).max().orElse(top);
    for (LaidOutEdge edge : edges) {
      for (Point point : edge.points()) {
        bottom = Math.max(bottom, point.y());
      }
    }

    List<LaidOutNode> normalized = new ArrayList<>();
    for (LaidOutNode node : nodes) {
      if ("interaction".equals(node.role())) {
        normalized.add(
            new LaidOutNode(
                node.id(),
                node.sourceId(),
                node.projectionId(),
                left,
                top,
                right - left,
                bottom - top,
                node.label(),
                node.role()));
      } else {
        normalized.add(node);
      }
    }
    return normalized;
  }

  private List<LaidOutNode> normalizedLifelineNodes(List<LaidOutNode> nodes) {
    Map<String, LaidOutNode> nodesById = new HashMap<>();
    for (LaidOutNode node : nodes) {
      nodesById.put(node.id(), node);
    }

    List<LaidOutNode> orderedLifelines = new ArrayList<>();
    for (String id : lifelineOrder) {
      LaidOutNode node = nodesById.get(id);
      if (node != null) {
        orderedLifelines.add(node);
      }
    }
    if (orderedLifelines.isEmpty()) {
      return nodes;
    }

    List<Double> xSlots = distinctColumnXSlots(orderedLifelines);
    double bandY = orderedLifelines.stream().mapToDouble(LaidOutNode::y).min().orElse(0.0);
    Map<String, Double> normalizedXById = new HashMap<>();
    for (int index = 0; index < orderedLifelines.size(); index++) {
      normalizedXById.put(orderedLifelines.get(index).id(), xSlots.get(index));
    }

    List<LaidOutNode> normalized = new ArrayList<>();
    for (LaidOutNode node : nodes) {
      Double x = normalizedXById.get(node.id());
      if (x == null) {
        normalized.add(node);
        continue;
      }
      normalized.add(
          new LaidOutNode(
              node.id(),
              node.sourceId(),
              node.projectionId(),
              x,
              bandY,
              node.width(),
              node.height(),
              node.label(),
              node.role()));
    }
    return normalized;
  }

  // ELK's RIGHT-direction layered pass can place two lifelines in the same layer, giving them
  // an identical x. A sequence diagram requires one distinct column per participant in declared
  // order, so return one x-slot per lifeline in ascending order. When ELK already produced a
  // distinct column per lifeline, keep its exact (possibly uneven) spacing; only when it
  // collapsed lifelines into fewer columns do we rebuild evenly spaced, non-overlapping columns
  // anchored at the leftmost lifeline.
  private static List<Double> distinctColumnXSlots(List<LaidOutNode> orderedLifelines) {
    List<Double> sortedXs = orderedLifelines.stream().map(LaidOutNode::x).sorted().toList();
    if (sortedXs.stream().distinct().count() == orderedLifelines.size()) {
      return sortedXs;
    }
    double leftmostX = sortedXs.get(0);
    double maxWidth = orderedLifelines.stream().mapToDouble(LaidOutNode::width).max().orElse(0.0);
    double pitch = maxWidth + LIFELINE_COLUMN_GAP;
    List<Double> columns = new ArrayList<>();
    for (int index = 0; index < orderedLifelines.size(); index++) {
      columns.add(leftmostX + (pitch * index));
    }
    return columns;
  }

  private List<LaidOutEdge> normalizedMessageEdges(
      List<LaidOutEdge> edges, Map<String, LaidOutNode> normalizedNodesById) {
    Map<String, LaidOutEdge> edgesById = new HashMap<>();
    for (LaidOutEdge edge : edges) {
      edgesById.put(edge.id(), edge);
    }

    List<LaidOutEdge> orderedMessages = new ArrayList<>();
    for (String id : messageOrder) {
      LaidOutEdge edge = edgesById.get(id);
      if (edge != null && edge.points().size() >= 2) {
        orderedMessages.add(edge);
      }
    }
    if (orderedMessages.isEmpty()) {
      return edges;
    }

    List<Double> ySlots = normalizedMessageYSlots(orderedMessages, normalizedNodesById);
    Map<String, Double> normalizedYById = new HashMap<>();
    for (int index = 0; index < orderedMessages.size(); index++) {
      normalizedYById.put(orderedMessages.get(index).id(), ySlots.get(index));
    }

    List<LaidOutEdge> normalized = new ArrayList<>();
    for (LaidOutEdge edge : edges) {
      Double y = normalizedYById.get(edge.id());
      if (y == null) {
        normalized.add(edge);
        continue;
      }
      normalized.add(
          new LaidOutEdge(
              edge.id(),
              edge.source(),
              edge.target(),
              edge.sourceId(),
              edge.projectionId(),
              edge.routingHints(),
              normalizedMessagePoints(edge, normalizedNodesById, y),
              edge.label()));
    }
    return normalized;
  }

  private List<Point> normalizedMessagePoints(
      LaidOutEdge edge, Map<String, LaidOutNode> normalizedNodesById, double y) {
    LaidOutNode source = normalizedNodesById.get(edge.source());
    LaidOutNode target = normalizedNodesById.get(edge.target());
    Integer sourceIndex = lifelineIndexById.get(edge.source());
    Integer targetIndex = lifelineIndexById.get(edge.target());
    if (source == null
        || target == null
        || sourceIndex == null
        || targetIndex == null
        || sourceIndex.equals(targetIndex)) {
      return pointsAtY(edge.points(), y);
    }

    return List.of(
        new Point(sourceEndpointX(source, sourceIndex, targetIndex), y),
        new Point(targetEndpointX(target, sourceIndex, targetIndex), y));
  }

  private static double sourceEndpointX(LaidOutNode source, int sourceIndex, int targetIndex) {
    if (sourceIndex < targetIndex) {
      return source.x() + source.width();
    }
    return source.x();
  }

  private static double targetEndpointX(LaidOutNode target, int sourceIndex, int targetIndex) {
    if (sourceIndex < targetIndex) {
      return target.x();
    }
    return target.x() + target.width();
  }

  private List<Double> normalizedMessageYSlots(
      List<LaidOutEdge> orderedMessages, Map<String, LaidOutNode> normalizedNodesById) {
    List<LaidOutNode> lifelines = new ArrayList<>();
    for (String id : lifelineOrder) {
      LaidOutNode node = normalizedNodesById.get(id);
      if (node != null) {
        lifelines.add(node);
      }
    }

    double headBottom =
        lifelines.stream().mapToDouble(node -> node.y() + node.height()).max().orElse(Double.NaN);
    if (Double.isFinite(headBottom)) {
      List<Double> ySlots = new ArrayList<>();
      double y = headBottom + MESSAGE_HEAD_GAP;
      for (int index = 0; index < orderedMessages.size(); index++) {
        if (index > 0) {
          y += MESSAGE_Y_STEP;
        }
        String id = orderedMessages.get(index).id();
        if (fragmentOpenIds.contains(id)) {
          y += FRAGMENT_OPEN_GAP;
        } else if (operandOpenIds.contains(id)) {
          y += OPERAND_OPEN_GAP;
        }
        ySlots.add(y);
      }
      return ySlots;
    }

    return strictlyIncreasingYSlots(
        orderedMessages.stream().map(edge -> edge.points().get(0).y()).sorted().toList());
  }

  private static List<Double> strictlyIncreasingYSlots(List<Double> ySlots) {
    List<Double> normalized = new ArrayList<>();
    double previousY = Double.NEGATIVE_INFINITY;
    for (double slot : ySlots) {
      double y = slot;
      if (y <= previousY) {
        y = previousY + MINIMUM_MESSAGE_Y_STEP;
      }
      normalized.add(y);
      previousY = y;
    }
    return normalized;
  }

  private static List<Point> pointsAtY(List<Point> points, double y) {
    List<Point> normalized = new ArrayList<>();
    for (Point point : points) {
      normalized.add(new Point(point.x(), y));
    }
    return normalized;
  }

  private static Map<String, LaidOutNode> nodesById(List<LaidOutNode> nodes) {
    Map<String, LaidOutNode> byId = new HashMap<>();
    for (LaidOutNode node : nodes) {
      byId.put(node.id(), node);
    }
    return byId;
  }

  private static Map<String, Integer> indexById(List<String> ids) {
    Map<String, Integer> byId = new HashMap<>();
    for (int index = 0; index < ids.size(); index++) {
      byId.putIfAbsent(ids.get(index), index);
    }
    return byId;
  }
}

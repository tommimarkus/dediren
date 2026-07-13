package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.ir.BandMember;
import dev.dediren.ir.LayoutIntent;
import dev.dediren.ir.LayoutIntent.OrderedBand;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.elk.core.options.PortSide;

/**
 * The sequence-diagram geometry normalizer read from the neutral typed {@link LayoutIntent} seam:
 * column placement, non-overlap rebuild, message Y lattice, stem anchoring, message straightening,
 * interaction enclosure, and provenance re-threading, driven by {@code List<LayoutIntent>} decoded
 * from the {@code LayoutRequest} constraints rather than the former stringly {@code uml.sequence.*}
 * DTOs. Since the Plan B P5 cutover {@link ElkLayoutEngine} wires this class and the former {@code
 * SequenceLayoutConstraints} re-derivation is deleted.
 */
final class LayoutIntentNormalizer {
  private static final double MINIMUM_MESSAGE_Y_STEP = 1.0;
  private static final double MESSAGE_HEAD_GAP = 24.0;
  private static final double MESSAGE_Y_STEP = 24.0;
  private static final double LIFELINE_COLUMN_GAP = 96.0;

  // A self-message hooks off the lifeline stem and returns to it (the conventional UML self-call).
  // Neutral band geometry, owned here like LIFELINE_COLUMN_GAP / MESSAGE_Y_STEP.
  private static final double SELF_MESSAGE_LOOP_WIDTH = 40.0;
  private static final double SELF_MESSAGE_LOOP_HEIGHT = 24.0;

  private final List<String> lifelineOrder;
  private final List<String> messageOrder;
  private final Map<String, Integer> lifelineIndexById;
  private final Map<String, Integer> messageIndexById;
  private final Map<String, Double> messageLeadingGapById;
  private final Map<String, String> nodePointers;
  private final Map<String, String> edgePointers;

  private LayoutIntentNormalizer(
      List<BandMember> lifelineMembers,
      List<BandMember> messageMembers,
      Map<String, String> nodePointers,
      Map<String, String> edgePointers) {
    this.lifelineOrder = memberIds(lifelineMembers);
    this.messageOrder = memberIds(messageMembers);
    this.lifelineIndexById = indexById(this.lifelineOrder);
    this.messageIndexById = indexById(this.messageOrder);
    this.messageLeadingGapById = leadingGapById(messageMembers);
    // Map.copyOf rejects null values; a missing/optional source pointer is a legitimate value
    // here (pure copy-through), so keep a plain defensive copy instead.
    this.nodePointers = new HashMap<>(nodePointers);
    this.edgePointers = new HashMap<>(edgePointers);
  }

  static LayoutIntentNormalizer from(
      List<LayoutIntent> intents,
      Map<String, String> nodePointers,
      Map<String, String> edgePointers) {
    List<BandMember> lifelineMembers = List.of();
    List<BandMember> messageMembers = List.of();
    for (LayoutIntent intent : intents) {
      if (intent instanceof OrderedBand orderedBand) {
        switch (orderedBand.axis()) {
          case X -> lifelineMembers = orderedBand.members();
          case Y -> messageMembers = orderedBand.members();
        }
      }
    }
    return new LayoutIntentNormalizer(lifelineMembers, messageMembers, nodePointers, edgePointers);
  }

  // Mirrors the old SequenceLayoutConstraints#active(): the parsed lifeline-order AND
  // message-order lists must both be non-empty, not merely "a band was present". The lowering can
  // emit an OrderedBand(Axis.Y, []) for a sequence view with lifelines but zero messages, which
  // must stay inactive exactly like the old stringly constraints did.
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
                node.role(),
                nodePointers.get(node.id())));
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
              node.role(),
              nodePointers.get(node.id())));
    }
    return normalized;
  }

  // ELK's RIGHT-direction layered pass can place two lifelines in the same layer, giving them an
  // identical x, or in adjacent layers closer than their head-box width. A sequence diagram
  // requires one distinct, non-overlapping column per participant in declared order, so return one
  // x-slot per lifeline in ascending order. Keep ELK's exact (possibly uneven) spacing only when it
  // already produced distinct AND non-overlapping columns; when it collapsed lifelines into fewer
  // columns OR packed them closer than a head box is wide, rebuild evenly spaced, non-overlapping
  // columns anchored at the leftmost lifeline.
  private static List<Double> distinctColumnXSlots(List<LaidOutNode> orderedLifelines) {
    List<Double> sortedXs = orderedLifelines.stream().map(LaidOutNode::x).sorted().toList();
    if (sortedXs.stream().distinct().count() == orderedLifelines.size()
        && columnsAreNonOverlapping(orderedLifelines, sortedXs)) {
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

  // Given lifelines assigned to ascending x-slots (declared lifeline i -> ascendingXs[i]), each
  // head box occupies [x, x + width]. Boxes must not overlap: the next column starts at or after
  // the current box's right edge. Touching edges are allowed, matching core LayoutQuality's strict
  // rectanglesOverlap predicate (overlap iff x[i] + width[i] > x[i+1]).
  private static boolean columnsAreNonOverlapping(
      List<LaidOutNode> orderedLifelines, List<Double> ascendingXs) {
    for (int index = 0; index < orderedLifelines.size() - 1; index++) {
      double rightEdge = ascendingXs.get(index) + orderedLifelines.get(index).width();
      if (rightEdge > ascendingXs.get(index + 1)) {
        return false;
      }
    }
    return true;
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
              edge.label(),
              edgePointers.get(edge.id())));
    }
    return normalized;
  }

  private List<Point> normalizedMessagePoints(
      LaidOutEdge edge, Map<String, LaidOutNode> normalizedNodesById, double y) {
    LaidOutNode source = normalizedNodesById.get(edge.source());
    LaidOutNode target = normalizedNodesById.get(edge.target());
    Integer sourceIndex = lifelineIndexById.get(edge.source());
    Integer targetIndex = lifelineIndexById.get(edge.target());
    if (source == null || target == null || sourceIndex == null || targetIndex == null) {
      return pointsAtY(edge.points(), y);
    }
    if (sourceIndex.equals(targetIndex)) {
      double stem = stemX(source);
      return List.of(
          new Point(stem, y),
          new Point(stem + SELF_MESSAGE_LOOP_WIDTH, y),
          new Point(stem + SELF_MESSAGE_LOOP_WIDTH, y + SELF_MESSAGE_LOOP_HEIGHT),
          new Point(stem, y + SELF_MESSAGE_LOOP_HEIGHT));
    }
    return List.of(new Point(stemX(source), y), new Point(stemX(target), y));
  }

  // A self-message (source lifeline == target lifeline) is legal UML but has no meaningful
  // straight-line stem-to-stem geometry; it hooks off the stem instead (see
  // normalizedMessagePoints). A dangling/unknown endpoint (null node or unmapped lifeline index)
  // is a distinct case that still falls back to pointsAtY, handled separately above.
  private boolean isSelfMessage(LaidOutEdge edge) {
    Integer sourceIndex = lifelineIndexById.get(edge.source());
    Integer targetIndex = lifelineIndexById.get(edge.target());
    return sourceIndex != null && sourceIndex.equals(targetIndex);
  }

  // A message terminates on each participant's lifeline stem, which the renderer draws down the
  // head-box center (node.x() + node.width()/2). Anchoring the endpoint to the box's left/right
  // border instead leaves the arrow floating half a head-box width short of the stem.
  private static double stemX(LaidOutNode lifeline) {
    return lifeline.x() + lifeline.width() / 2.0;
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
          if (isSelfMessage(orderedMessages.get(index - 1))) {
            y += SELF_MESSAGE_LOOP_HEIGHT; // clear the previous hook's lower leg
          }
        }
        String id = orderedMessages.get(index).id();
        y += messageLeadingGapById.getOrDefault(id, 0.0);
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

  private static List<String> memberIds(List<BandMember> members) {
    return members.stream().map(BandMember::id).toList();
  }

  private static Map<String, Double> leadingGapById(List<BandMember> members) {
    Map<String, Double> byId = new HashMap<>();
    for (BandMember member : members) {
      byId.putIfAbsent(member.id(), member.leadingGap());
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

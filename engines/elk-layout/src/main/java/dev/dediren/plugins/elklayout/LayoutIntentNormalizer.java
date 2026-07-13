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
import dev.dediren.ir.LayoutIntent.StemSpan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.elk.core.options.PortSide;

/**
 * The sequence-diagram geometry normalizer read from the neutral typed {@link LayoutIntent} seam:
 * column placement, non-overlap rebuild, message Y lattice, stem anchoring, message straightening,
 * stem-span placement (a node anchored to a band member's axis over a span of rows), interaction
 * enclosure, and provenance re-threading, driven by {@code List<LayoutIntent>} decoded from the
 * {@code LayoutRequest} constraints rather than the former stringly {@code uml.sequence.*} DTOs.
 * Since the Plan B P5 cutover {@link ElkLayoutEngine} wires this class and the former {@code
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

  // A stem-span node whose two bounding rows land on the same slot (or in reverse order) would
  // otherwise collapse to a zero-height sliver; give it one readable band instead. Neutral row
  // geometry like MESSAGE_Y_STEP: it constrains a spanning node, not any notation's element.
  private static final double MINIMUM_EXECUTION_HEIGHT = 24.0;

  private final List<String> lifelineOrder;
  private final List<String> messageOrder;
  private final Map<String, Integer> lifelineIndexById;
  private final Map<String, Integer> messageIndexById;
  private final Map<String, Double> messageLeadingGapById;
  private final Map<String, StemSpan> stemSpanByNodeId;
  // Endpoint resolution: a stem-span node (an activation bar, a destruction marker) is not itself
  // a band member, but it lives on one. A message that starts or ends on such a node belongs to
  // the column of the band member it is anchored to, which is what gives it a port side and a
  // stem to route from.
  private final Map<String, String> anchorBandMemberByNodeId;
  private final Map<String, String> nodePointers;
  private final Map<String, String> edgePointers;

  private LayoutIntentNormalizer(
      List<BandMember> lifelineMembers,
      List<BandMember> messageMembers,
      List<StemSpan> stemSpans,
      Map<String, String> nodePointers,
      Map<String, String> edgePointers) {
    this.lifelineOrder = memberIds(lifelineMembers);
    this.messageOrder = memberIds(messageMembers);
    this.lifelineIndexById = indexById(this.lifelineOrder);
    this.messageIndexById = indexById(this.messageOrder);
    this.messageLeadingGapById = leadingGapById(messageMembers);
    this.stemSpanByNodeId = new LinkedHashMap<>();
    this.anchorBandMemberByNodeId = new HashMap<>();
    for (StemSpan span : stemSpans) {
      if (this.stemSpanByNodeId.putIfAbsent(span.nodeId(), span) == null) {
        this.anchorBandMemberByNodeId.put(span.nodeId(), span.bandMemberId());
      }
    }
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
    List<StemSpan> stemSpans = new ArrayList<>();
    for (LayoutIntent intent : intents) {
      switch (intent) {
        case OrderedBand orderedBand -> {
          switch (orderedBand.axis()) {
            case X -> lifelineMembers = orderedBand.members();
            case Y -> messageMembers = orderedBand.members();
          }
        }
        case StemSpan stemSpan -> stemSpans.add(stemSpan);
      }
    }
    return new LayoutIntentNormalizer(
        lifelineMembers, messageMembers, stemSpans, nodePointers, edgePointers);
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
    Integer sourceIndex = resolvedBandIndex(edge.source());
    Integer targetIndex = resolvedBandIndex(edge.target());
    if (sourceIndex == null || targetIndex == null || sourceIndex.equals(targetIndex)) {
      return fallback;
    }
    return sourceIndex < targetIndex ? PortSide.EAST : PortSide.WEST;
  }

  PortSide targetPortSide(LayoutEdge edge, PortSide fallback) {
    Integer sourceIndex = resolvedBandIndex(edge.source());
    Integer targetIndex = resolvedBandIndex(edge.target());
    if (sourceIndex == null || targetIndex == null || sourceIndex.equals(targetIndex)) {
      return fallback;
    }
    return sourceIndex < targetIndex ? PortSide.WEST : PortSide.EAST;
  }

  // An edge endpoint is either a band member itself (a lifeline) or a node anchored to one by a
  // StemSpan (an activation bar, a destruction marker). Resolve both to the owning band member, so
  // an edge that terminates on anchored chrome still has a column, a port side and a stem. Returns
  // null for an endpoint that belongs to no column at all (an unrelated node, a dangling id) --
  // the same "unknown endpoint" signal the callers already handle.
  private String resolvedBandMemberId(String endpointId) {
    if (lifelineIndexById.containsKey(endpointId)) {
      return endpointId;
    }
    String anchor = anchorBandMemberByNodeId.get(endpointId);
    return anchor != null && lifelineIndexById.containsKey(anchor) ? anchor : null;
  }

  private Integer resolvedBandIndex(String endpointId) {
    String bandMemberId = resolvedBandMemberId(endpointId);
    return bandMemberId == null ? null : lifelineIndexById.get(bandMemberId);
  }

  // Order matters. Lifeline columns come first: a stem-span node's x is derived from the
  // NORMALIZED stem of the band member it sits on. The message Y lattice comes next: a stem-span
  // node's y is a message row. Stem-span placement then precedes message routing: a message that
  // terminates on anchored chrome (a delete-message ending on a destruction marker) needs that
  // node's placed geometry to compute its endpoint. The interaction frame is last: it encloses
  // everything above.
  LayoutResult normalize(LayoutResult result) {
    if (!active()) {
      return result;
    }
    List<LaidOutNode> lifelineNodes = normalizedLifelineNodes(result.nodes());
    Map<String, LaidOutNode> lifelineById = nodesById(lifelineNodes);
    Map<String, Double> messageRows = messageRowsById(result.edges(), lifelineById);
    StemSpanPlacement placement = placeStemSpanNodes(lifelineNodes, lifelineById, messageRows);
    Map<String, LaidOutNode> normalizedById = nodesById(placement.nodes());
    List<LaidOutEdge> normalizedEdges =
        normalizedMessageEdges(
            result.edges(), normalizedById, messageRows, placement.pointAnchoredIds());
    List<LaidOutNode> wrappedNodes =
        normalizedInteractionNodes(
            placement.nodes(), normalizedById, normalizedEdges, placement.placed());
    return new LayoutResult(
        result.layoutResultSchemaVersion(),
        result.viewId(),
        wrappedNodes,
        normalizedEdges,
        result.groups(),
        result.warnings());
  }

  private List<LaidOutNode> normalizedInteractionNodes(
      List<LaidOutNode> nodes,
      Map<String, LaidOutNode> byId,
      List<LaidOutEdge> edges,
      List<LaidOutNode> placedStemSpanNodes) {
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
    // Anchored chrome (an activation bar, a destruction marker below the last message row) can
    // stick out past the lifelines and the routes; the frame must still enclose it.
    for (LaidOutNode node : placedStemSpanNodes) {
      top = Math.min(top, node.y());
      left = Math.min(left, node.x());
      right = Math.max(right, node.x() + node.width());
      bottom = Math.max(bottom, node.y() + node.height());
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

  // Place every node carried by a StemSpan intent: on the axis of the band member it is anchored
  // to, over the rows of the two messages that bound it. A span with two distinct, resolvable rows
  // is a bar (an activation); every other case is a point anchor (a marker centred on one row).
  private StemSpanPlacement placeStemSpanNodes(
      List<LaidOutNode> nodes, Map<String, LaidOutNode> byId, Map<String, Double> messageRows) {
    if (stemSpanByNodeId.isEmpty()) {
      return new StemSpanPlacement(nodes, List.of(), Set.of());
    }
    Double orphanRow = orphanRow(messageRows);
    List<LaidOutNode> normalized = new ArrayList<>();
    List<LaidOutNode> placed = new ArrayList<>();
    Set<String> pointAnchoredIds = new HashSet<>();
    for (LaidOutNode node : nodes) {
      StemSpan span = stemSpanByNodeId.get(node.id());
      // A StemSpan naming a lifeline as its own nodeId is unreachable through the current
      // lowering (a lifeline is a band member, never a spanned node), but guard against it
      // defensively: relocating a lifeline here would silently corrupt the column layout instead
      // of just being a no-op.
      boolean nodeIsLifeline = lifelineIndexById.containsKey(node.id());
      LaidOutNode anchor =
          span == null || nodeIsLifeline ? null : byId.get(resolvedBandMemberId(node.id()));
      if (span == null || nodeIsLifeline || anchor == null) {
        normalized.add(node);
        continue;
      }
      Double fromRow = messageRows.get(span.fromMemberId());
      Double toRow = messageRows.get(span.toMemberId());
      double top;
      double height = node.height();
      boolean pointAnchored;
      if (fromRow != null && toRow != null && !span.fromMemberId().equals(span.toMemberId())) {
        top = fromRow;
        height = Math.max(toRow - fromRow, MINIMUM_EXECUTION_HEIGHT);
        pointAnchored = false;
      } else {
        // A degenerate span (from == to) is a point anchor. So is a span whose rows do not resolve
        // at all -- the empty-member convention for a node with no bounding message -- which lands
        // one message step below the last row. With no messages at all there is no row to anchor
        // to, so leave the node exactly as the layout engine produced it.
        Double row = fromRow != null ? fromRow : toRow != null ? toRow : orphanRow;
        if (row == null) {
          normalized.add(node);
          continue;
        }
        top = row - node.height() / 2.0;
        pointAnchored = true;
      }
      LaidOutNode placedNode =
          new LaidOutNode(
              node.id(),
              node.sourceId(),
              node.projectionId(),
              stemX(anchor) - node.width() / 2.0,
              top,
              node.width(),
              height,
              node.label(),
              node.role(),
              nodePointers.get(node.id()));
      normalized.add(placedNode);
      placed.add(placedNode);
      if (pointAnchored) {
        pointAnchoredIds.add(placedNode.id());
      }
    }
    return new StemSpanPlacement(normalized, placed, pointAnchoredIds);
  }

  private static Double orphanRow(Map<String, Double> messageRows) {
    return messageRows.values().stream()
        .max(Comparator.naturalOrder())
        .map(lastRow -> lastRow + MESSAGE_Y_STEP)
        .orElse(null);
  }

  private Map<String, Double> messageRowsById(
      List<LaidOutEdge> edges, Map<String, LaidOutNode> normalizedNodesById) {
    List<LaidOutEdge> orderedMessages = orderedMessageEdges(edges);
    if (orderedMessages.isEmpty()) {
      return Map.of();
    }
    List<Double> ySlots = normalizedMessageYSlots(orderedMessages, normalizedNodesById);
    Map<String, Double> rows = new LinkedHashMap<>();
    for (int index = 0; index < orderedMessages.size(); index++) {
      rows.put(orderedMessages.get(index).id(), ySlots.get(index));
    }
    return rows;
  }

  private List<LaidOutEdge> orderedMessageEdges(List<LaidOutEdge> edges) {
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
    return orderedMessages;
  }

  private List<LaidOutEdge> normalizedMessageEdges(
      List<LaidOutEdge> edges,
      Map<String, LaidOutNode> normalizedNodesById,
      Map<String, Double> messageRows,
      Set<String> pointAnchoredIds) {
    if (messageRows.isEmpty()) {
      return edges;
    }
    List<LaidOutEdge> normalized = new ArrayList<>();
    for (LaidOutEdge edge : edges) {
      Double y = messageRows.get(edge.id());
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
              normalizedMessagePoints(edge, normalizedNodesById, y, pointAnchoredIds),
              edge.label(),
              edgePointers.get(edge.id())));
    }
    return normalized;
  }

  private List<Point> normalizedMessagePoints(
      LaidOutEdge edge,
      Map<String, LaidOutNode> normalizedNodesById,
      double y,
      Set<String> pointAnchoredIds) {
    LaidOutNode source = normalizedNodesById.get(edge.source());
    LaidOutNode target = normalizedNodesById.get(edge.target());
    Integer sourceIndex = resolvedBandIndex(edge.source());
    Integer targetIndex = resolvedBandIndex(edge.target());
    if (source == null || target == null || sourceIndex == null || targetIndex == null) {
      return pointsAtY(edge.points(), y);
    }
    LaidOutNode sourceStem = normalizedNodesById.get(resolvedBandMemberId(edge.source()));
    LaidOutNode targetStem = normalizedNodesById.get(resolvedBandMemberId(edge.target()));
    if (sourceStem == null || targetStem == null) {
      return pointsAtY(edge.points(), y);
    }
    if (pointAnchoredIds.contains(edge.target())) {
      // The route ends ON the point-anchored node, so it must land on that node's perimeter: the
      // stem runs through its centre, and a centre endpoint is inside the box, which core's
      // endpoint-on-perimeter check rejects. Either the left or the right edge sits on both the
      // perimeter and the row, so terminate on the NEAR edge relative to the source's column:
      // lifeline order is declaration order, so the source can legitimately be declared to the
      // right of the node it is destroying. Landing on the far edge would run the final segment
      // clear across the marker box, pointing the arrowhead away from the glyph.
      // Checked before the self-message hook: a marker anchored to the source's own band member
      // resolves to the same column, but it is a termination, not a self-call.
      double endX = sourceIndex > targetIndex ? target.x() + target.width() : target.x();
      return List.of(new Point(stemX(sourceStem), y), new Point(endX, y));
    }
    if (sourceIndex.equals(targetIndex)) {
      double stem = stemX(sourceStem);
      return List.of(
          new Point(stem, y),
          new Point(stem + SELF_MESSAGE_LOOP_WIDTH, y),
          new Point(stem + SELF_MESSAGE_LOOP_WIDTH, y + SELF_MESSAGE_LOOP_HEIGHT),
          new Point(stem, y + SELF_MESSAGE_LOOP_HEIGHT));
    }
    return List.of(new Point(stemX(sourceStem), y), new Point(stemX(targetStem), y));
  }

  // A self-message (source lifeline == target lifeline) is legal UML but has no meaningful
  // straight-line stem-to-stem geometry; it hooks off the stem instead (see
  // normalizedMessagePoints). A dangling/unknown endpoint (null node or unmapped lifeline index)
  // is a distinct case that still falls back to pointsAtY, handled separately above.
  //
  // This drives the Y lattice, which runs BEFORE placement, so it reads point-anchoredness off the
  // intent (from == to) rather than off the placed set. The two agree on every resolvable span;
  // where they can differ (a bar whose bounding rows do not resolve, so placement degrades it to a
  // point anchor) this over-reserves a hook's clearance for a route that turns out to be straight.
  // Slack, never a collision.
  private boolean isSelfMessage(LaidOutEdge edge) {
    Integer sourceIndex = resolvedBandIndex(edge.source());
    Integer targetIndex = resolvedBandIndex(edge.target());
    StemSpan targetSpan = stemSpanByNodeId.get(edge.target());
    boolean terminatesOnPointAnchor =
        targetSpan != null && targetSpan.fromMemberId().equals(targetSpan.toMemberId());
    return sourceIndex != null && sourceIndex.equals(targetIndex) && !terminatesOnPointAnchor;
  }

  // The node list with every stem-span node placed, the placed nodes alone (for the enclosing
  // frame), and the ids of those placed as a point anchor (for endpoint routing).
  private record StemSpanPlacement(
      List<LaidOutNode> nodes, List<LaidOutNode> placed, Set<String> pointAnchoredIds) {}

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

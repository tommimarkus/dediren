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
import org.eclipse.elk.core.options.PortSide;

final class SequenceLayoutConstraints {
    private static final String LIFELINE_ORDER_KIND = "uml.sequence.lifeline-order";
    private static final String MESSAGE_ORDER_KIND = "uml.sequence.message-order";
    private static final double MINIMUM_MESSAGE_Y_STEP = 1.0;

    private final List<String> lifelineOrder;
    private final List<String> messageOrder;
    private final Map<String, Integer> lifelineIndexById;
    private final Map<String, Integer> messageIndexById;

    private SequenceLayoutConstraints(
            List<String> lifelineOrder,
            List<String> messageOrder) {
        this.lifelineOrder = List.copyOf(lifelineOrder);
        this.messageOrder = List.copyOf(messageOrder);
        this.lifelineIndexById = indexById(this.lifelineOrder);
        this.messageIndexById = indexById(this.messageOrder);
    }

    static SequenceLayoutConstraints from(LayoutRequest request) {
        List<String> lifelineOrder = List.of();
        List<String> messageOrder = List.of();
        for (LayoutConstraint constraint : request.constraints()) {
            if (LIFELINE_ORDER_KIND.equals(constraint.kind())) {
                lifelineOrder = constraint.subjects();
            } else if (MESSAGE_ORDER_KIND.equals(constraint.kind())) {
                messageOrder = constraint.subjects();
            }
        }
        return new SequenceLayoutConstraints(lifelineOrder, messageOrder);
    }

    boolean active() {
        return !lifelineOrder.isEmpty() && !messageOrder.isEmpty();
    }

    List<LayoutNode> orderedNodes(List<LayoutNode> nodes) {
        if (!active()) {
            return nodes;
        }
        List<LayoutNode> ordered = new ArrayList<>(nodes);
        ordered.sort(Comparator.comparingInt(node ->
            lifelineIndexById.getOrDefault(node.id(), Integer.MAX_VALUE)));
        return ordered;
    }

    List<LayoutEdge> orderedEdges(List<LayoutEdge> edges) {
        if (!active()) {
            return edges;
        }
        List<LayoutEdge> ordered = new ArrayList<>(edges);
        ordered.sort(Comparator.comparingInt(edge ->
            messageIndexById.getOrDefault(edge.id(), Integer.MAX_VALUE)));
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
        return new LayoutResult(
            result.layoutResultSchemaVersion(),
            result.viewId(),
            normalizedLifelineNodes(result.nodes()),
            normalizedMessageEdges(result.edges()),
            result.groups(),
            result.warnings());
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

        List<Double> xSlots = orderedLifelines.stream()
            .map(LaidOutNode::x)
            .sorted()
            .toList();
        double bandY = orderedLifelines.stream()
            .mapToDouble(LaidOutNode::y)
            .min()
            .orElse(0.0);
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
            normalized.add(new LaidOutNode(
                node.id(),
                node.sourceId(),
                node.projectionId(),
                x,
                bandY,
                node.width(),
                node.height(),
                node.label()));
        }
        return normalized;
    }

    private List<LaidOutEdge> normalizedMessageEdges(List<LaidOutEdge> edges) {
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

        List<Double> ySlots = orderedMessages.stream()
            .map(edge -> edge.points().get(0).y())
            .sorted()
            .toList();
        Map<String, Double> normalizedYById = new HashMap<>();
        double previousY = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < orderedMessages.size(); index++) {
            double y = ySlots.get(index);
            if (y <= previousY) {
                y = previousY + MINIMUM_MESSAGE_Y_STEP;
            }
            normalizedYById.put(orderedMessages.get(index).id(), y);
            previousY = y;
        }

        List<LaidOutEdge> normalized = new ArrayList<>();
        for (LaidOutEdge edge : edges) {
            Double y = normalizedYById.get(edge.id());
            if (y == null) {
                normalized.add(edge);
                continue;
            }
            normalized.add(new LaidOutEdge(
                edge.id(),
                edge.source(),
                edge.target(),
                edge.sourceId(),
                edge.projectionId(),
                edge.routingHints(),
                pointsAtY(edge.points(), y),
                edge.label()));
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

    private static Map<String, Integer> indexById(List<String> ids) {
        Map<String, Integer> byId = new HashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            byId.putIfAbsent(ids.get(index), index);
        }
        return byId;
    }
}

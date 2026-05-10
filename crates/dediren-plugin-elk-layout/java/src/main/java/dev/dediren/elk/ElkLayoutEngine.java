package dev.dediren.elk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.util.ElkGraphUtil;

final class ElkLayoutEngine {
    private static final double DEFAULT_WIDTH = 160.0;
    private static final double DEFAULT_HEIGHT = 80.0;
    private static final double GROUP_PADDING = 24.0;
    private static final double PORT_MARGIN = 16.0;
    private static final double NODE_SPACING = 120.0;
    private static final double EDGE_NODE_SPACING = 32.0;
    private static final double EDGE_EDGE_SPACING = 20.0;

    JsonContracts.LayoutResult layout(JsonContracts.LayoutRequest request) {
        validate(request);
        if (!list(request.groups()).isEmpty()) {
            return layoutGrouped(request);
        }

        return layoutFlat(request);
    }

    private static JsonContracts.LayoutResult layoutFlat(JsonContracts.LayoutRequest request) {
        ElkNode root = ElkGraphUtil.createGraph();
        configureRoot(root, Direction.RIGHT);

        Map<String, ElkNode> elkNodes = new HashMap<>();
        for (JsonContracts.LayoutNode node : list(request.nodes())) {
            ElkNode elkNode = ElkGraphUtil.createNode(root);
            elkNode.setIdentifier(node.id());
            elkNode.setDimensions(
                positiveOrDefault(node.width_hint(), DEFAULT_WIDTH),
                positiveOrDefault(node.height_hint(), DEFAULT_HEIGHT));
            ElkGraphUtil.createLabel(elkNode).setText(node.label());
            elkNodes.put(node.id(), elkNode);
        }

        Map<String, ElkEdge> elkEdges = new HashMap<>();
        List<JsonContracts.Diagnostic> warnings = new ArrayList<>();
        List<JsonContracts.LayoutEdge> requestEdges = list(request.edges());
        for (int index = 0; index < requestEdges.size(); index++) {
            JsonContracts.LayoutEdge edge = requestEdges.get(index);
            ElkNode source = elkNodes.get(edge.source());
            ElkNode target = elkNodes.get(edge.target());
            if (source == null || target == null) {
                warnings.add(new JsonContracts.Diagnostic(
                    "DEDIREN_ELK_DANGLING_EDGE",
                    "warning",
                    "edge " + edge.id() + " references a missing endpoint",
                    "$.edges[" + index + "]"));
                continue;
            }
            ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(source, target);
            elkEdge.setIdentifier(edge.id());
            ElkGraphUtil.createLabel(elkEdge).setText(edge.label());
            elkEdges.put(edge.id(), elkEdge);
        }

        new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());

        List<JsonContracts.LaidOutNode> nodes = new ArrayList<>();
        for (JsonContracts.LayoutNode node : list(request.nodes())) {
            ElkNode elkNode = elkNodes.get(node.id());
            if (elkNode != null) {
                nodes.add(new JsonContracts.LaidOutNode(
                    node.id(),
                    node.source_id(),
                    node.id(),
                    elkNode.getX(),
                    elkNode.getY(),
                    elkNode.getWidth(),
                    elkNode.getHeight(),
                    node.label()));
            }
        }

        List<JsonContracts.LaidOutEdge> edges = new ArrayList<>();
        for (JsonContracts.LayoutEdge edge : list(request.edges())) {
            ElkEdge elkEdge = elkEdges.get(edge.id());
            if (elkEdge != null) {
                edges.add(new JsonContracts.LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.source_id(),
                    edge.id(),
                    points(elkEdge),
                    edge.label()));
            }
        }

        List<JsonContracts.LaidOutGroup> groups =
            groups(request, nodes, warnings);

        return new JsonContracts.LayoutResult(
            "layout-result.schema.v1",
            request.view_id(),
            nodes,
            edges,
            groups,
            warnings);
    }

    private static JsonContracts.LayoutResult layoutGrouped(JsonContracts.LayoutRequest request) {
        List<JsonContracts.Diagnostic> warnings = new ArrayList<>();
        Map<String, JsonContracts.LayoutNode> requestNodes = requestNodesById(request);
        Map<String, String> ownerByNode = ownerByNode(request);
        Map<String, InternalLayout> internalLayouts = new HashMap<>();
        List<JsonContracts.LayoutNode> macroNodes = new ArrayList<>();

        for (JsonContracts.LayoutGroup group : list(request.groups())) {
            List<JsonContracts.LayoutNode> members = list(group.members()).stream()
                .map(requestNodes::get)
                .filter(node -> node != null)
                .toList();
            if (members.isEmpty()) {
                continue;
            }
            List<JsonContracts.LayoutEdge> internalEdges = list(request.edges()).stream()
                .filter(edge -> group.id().equals(ownerByNode.get(edge.source()))
                    && group.id().equals(ownerByNode.get(edge.target())))
                .toList();
            InternalLayout internalLayout = internalLayout(members, internalEdges);
            internalLayouts.put(group.id(), internalLayout);
            macroNodes.add(new JsonContracts.LayoutNode(
                group.id(),
                group.label(),
                semanticBackedSourceId(group.provenance(), group.id()),
                internalLayout.width() + (GROUP_PADDING * 2.0),
                internalLayout.height() + (GROUP_PADDING * 2.0)));
        }

        for (JsonContracts.LayoutNode node : list(request.nodes())) {
            if (!ownerByNode.containsKey(node.id())) {
                macroNodes.add(node);
            }
        }

        List<JsonContracts.LayoutEdge> macroEdges = new ArrayList<>();
        for (JsonContracts.LayoutEdge edge : list(request.edges())) {
            String source = macroId(edge.source(), ownerByNode);
            String target = macroId(edge.target(), ownerByNode);
            if (source == null || target == null || source.equals(target)) {
                continue;
            }
            macroEdges.add(new JsonContracts.LayoutEdge(
                edge.id(),
                source,
                target,
                edge.label(),
                edge.source_id()));
        }

        GraphLayout macroLayout = graphLayout(macroNodes, macroEdges, Direction.RIGHT);
        Map<String, JsonContracts.LaidOutNode> finalNodes = new HashMap<>();
        List<JsonContracts.LaidOutNode> nodes = new ArrayList<>();
        for (JsonContracts.LayoutNode node : list(request.nodes())) {
            String owner = ownerByNode.get(node.id());
            JsonContracts.LaidOutNode laidOutNode;
            if (owner == null) {
                laidOutNode = macroLayout.nodes().get(node.id());
            } else {
                JsonContracts.LaidOutNode groupNode = macroLayout.nodes().get(owner);
                InternalLayout internalLayout = internalLayouts.get(owner);
                JsonContracts.LaidOutNode internalNode =
                    internalLayout == null ? null : internalLayout.nodes().get(node.id());
                laidOutNode = groupNode == null || internalNode == null ? null : offsetNode(
                    internalNode,
                    groupNode.x() + GROUP_PADDING,
                    groupNode.y() + GROUP_PADDING);
            }
            if (laidOutNode != null) {
                finalNodes.put(node.id(), laidOutNode);
                nodes.add(laidOutNode);
            }
        }

        List<JsonContracts.LaidOutEdge> edges = new ArrayList<>();
        Map<String, EdgePorts> edgePorts = edgePorts(list(request.edges()), finalNodes);
        for (JsonContracts.LayoutEdge edge : list(request.edges())) {
            String sourceOwner = ownerByNode.get(edge.source());
            String targetOwner = ownerByNode.get(edge.target());
            JsonContracts.LaidOutEdge laidOutEdge = null;
            if (sourceOwner != null && sourceOwner.equals(targetOwner)) {
                JsonContracts.LaidOutNode groupNode = macroLayout.nodes().get(sourceOwner);
                InternalLayout internalLayout = internalLayouts.get(sourceOwner);
                JsonContracts.LaidOutEdge internalEdge =
                    internalLayout == null ? null : internalLayout.edges().get(edge.id());
                if (groupNode != null && internalEdge != null) {
                    laidOutEdge = offsetEdge(
                        internalEdge,
                        groupNode.x() + GROUP_PADDING,
                        groupNode.y() + GROUP_PADDING);
                }
            }
            if (laidOutEdge == null) {
                JsonContracts.LaidOutNode source = finalNodes.get(edge.source());
                JsonContracts.LaidOutNode target = finalNodes.get(edge.target());
                if (source == null || target == null) {
                    warnings.add(new JsonContracts.Diagnostic(
                        "DEDIREN_ELK_DANGLING_EDGE",
                        "warning",
                        "edge " + edge.id() + " references a missing endpoint",
                        null));
                    continue;
                }
                laidOutEdge = new JsonContracts.LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.source_id(),
                    edge.id(),
                    routeBetween(source, target, edgePorts.get(edge.id())),
                    edge.label());
            }
            edges.add(laidOutEdge);
        }

        List<JsonContracts.LaidOutGroup> groups =
            groupedBounds(request, macroLayout.nodes(), internalLayouts, warnings);

        return new JsonContracts.LayoutResult(
            "layout-result.schema.v1",
            request.view_id(),
            nodes,
            edges,
            groups,
            warnings);
    }

    private record GraphLayout(
        Map<String, JsonContracts.LaidOutNode> nodes,
        Map<String, JsonContracts.LaidOutEdge> edges) {
    }

    private record InternalLayout(
        Map<String, JsonContracts.LaidOutNode> nodes,
        Map<String, JsonContracts.LaidOutEdge> edges,
        double width,
        double height) {
    }

    private enum PortSide {
        LEFT,
        RIGHT
    }

    private record PortKey(String nodeId, PortSide side) {
    }

    private record PortRef(PortSide side, int index, int count) {
    }

    private record EdgePorts(PortRef source, PortRef target) {
    }

    private record PortAssignment(String edgeId, double sortY, PortSide side, boolean source) {
    }

    private static InternalLayout internalLayout(
        List<JsonContracts.LayoutNode> nodes,
        List<JsonContracts.LayoutEdge> edges) {
        GraphLayout layout = graphLayout(nodes, edges, internalDirection(nodes, edges));
        double minX = layout.nodes().values().stream().mapToDouble(JsonContracts.LaidOutNode::x).min().orElse(0.0);
        double minY = layout.nodes().values().stream().mapToDouble(JsonContracts.LaidOutNode::y).min().orElse(0.0);
        double maxX = layout.nodes().values().stream().mapToDouble(node -> node.x() + node.width()).max().orElse(0.0);
        double maxY = layout.nodes().values().stream().mapToDouble(node -> node.y() + node.height()).max().orElse(0.0);

        Map<String, JsonContracts.LaidOutNode> normalizedNodes = new HashMap<>();
        for (Map.Entry<String, JsonContracts.LaidOutNode> entry : layout.nodes().entrySet()) {
            normalizedNodes.put(entry.getKey(), offsetNode(entry.getValue(), -minX, -minY));
        }

        Map<String, JsonContracts.LaidOutEdge> normalizedEdges = new HashMap<>();
        for (Map.Entry<String, JsonContracts.LaidOutEdge> entry : layout.edges().entrySet()) {
            normalizedEdges.put(entry.getKey(), offsetEdge(entry.getValue(), -minX, -minY));
        }

        return new InternalLayout(
            normalizedNodes,
            normalizedEdges,
            maxX - minX,
            maxY - minY);
    }

    private static GraphLayout graphLayout(
        List<JsonContracts.LayoutNode> requestNodes,
        List<JsonContracts.LayoutEdge> requestEdges,
        Direction direction) {
        ElkNode root = ElkGraphUtil.createGraph();
        configureRoot(root, direction);

        Map<String, ElkNode> elkNodes = new HashMap<>();
        for (JsonContracts.LayoutNode node : requestNodes) {
            ElkNode elkNode = ElkGraphUtil.createNode(root);
            elkNode.setIdentifier(node.id());
            elkNode.setDimensions(
                positiveOrDefault(node.width_hint(), DEFAULT_WIDTH),
                positiveOrDefault(node.height_hint(), DEFAULT_HEIGHT));
            ElkGraphUtil.createLabel(elkNode).setText(node.label());
            elkNodes.put(node.id(), elkNode);
        }

        Map<String, ElkEdge> elkEdges = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : requestEdges) {
            ElkNode source = elkNodes.get(edge.source());
            ElkNode target = elkNodes.get(edge.target());
            if (source == null || target == null) {
                continue;
            }
            ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(source, target);
            elkEdge.setIdentifier(edge.id());
            ElkGraphUtil.createLabel(elkEdge).setText(edge.label());
            elkEdges.put(edge.id(), elkEdge);
        }

        new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());

        Map<String, JsonContracts.LaidOutNode> nodes = new HashMap<>();
        for (JsonContracts.LayoutNode node : requestNodes) {
            ElkNode elkNode = elkNodes.get(node.id());
            if (elkNode != null) {
                nodes.put(node.id(), new JsonContracts.LaidOutNode(
                    node.id(),
                    node.source_id(),
                    node.id(),
                    elkNode.getX(),
                    elkNode.getY(),
                    elkNode.getWidth(),
                    elkNode.getHeight(),
                    node.label()));
            }
        }

        Map<String, JsonContracts.LaidOutEdge> edges = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : requestEdges) {
            ElkEdge elkEdge = elkEdges.get(edge.id());
            if (elkEdge != null) {
                edges.put(edge.id(), new JsonContracts.LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.source_id(),
                    edge.id(),
                    points(elkEdge),
                    edge.label()));
            }
        }

        return new GraphLayout(nodes, edges);
    }

    private static void configureRoot(ElkNode root, Direction direction) {
        root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
        root.setProperty(CoreOptions.DIRECTION, direction);
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
        root.setProperty(CoreOptions.SPACING_NODE_NODE, NODE_SPACING);
        root.setProperty(CoreOptions.SPACING_EDGE_NODE, EDGE_NODE_SPACING);
        root.setProperty(CoreOptions.SPACING_EDGE_EDGE, EDGE_EDGE_SPACING);
        root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, NODE_SPACING);
        root.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, EDGE_NODE_SPACING);
        root.setProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, EDGE_EDGE_SPACING);
    }

    private static Direction internalDirection(
        List<JsonContracts.LayoutNode> nodes,
        List<JsonContracts.LayoutEdge> edges) {
        if (nodes.size() < 3) {
            return Direction.RIGHT;
        }
        return Direction.DOWN;
    }

    private static JsonContracts.LaidOutNode offsetNode(
        JsonContracts.LaidOutNode node,
        double offsetX,
        double offsetY) {
        return new JsonContracts.LaidOutNode(
            node.id(),
            node.source_id(),
            node.projection_id(),
            node.x() + offsetX,
            node.y() + offsetY,
            node.width(),
            node.height(),
            node.label());
    }

    private static JsonContracts.LaidOutEdge offsetEdge(
        JsonContracts.LaidOutEdge edge,
        double offsetX,
        double offsetY) {
        return new JsonContracts.LaidOutEdge(
            edge.id(),
            edge.source(),
            edge.target(),
            edge.source_id(),
            edge.projection_id(),
            edge.points().stream()
                .map(point -> new JsonContracts.Point(point.x() + offsetX, point.y() + offsetY))
                .toList(),
            edge.label());
    }

    private static Map<String, JsonContracts.LayoutNode> requestNodesById(
        JsonContracts.LayoutRequest request) {
        Map<String, JsonContracts.LayoutNode> byId = new HashMap<>();
        for (JsonContracts.LayoutNode node : list(request.nodes())) {
            byId.put(node.id(), node);
        }
        return byId;
    }

    private static Map<String, String> ownerByNode(JsonContracts.LayoutRequest request) {
        Map<String, String> ownerByNode = new HashMap<>();
        for (JsonContracts.LayoutGroup group : list(request.groups())) {
            for (String member : list(group.members())) {
                ownerByNode.putIfAbsent(member, group.id());
            }
        }
        return ownerByNode;
    }

    private static String macroId(String nodeId, Map<String, String> ownerByNode) {
        return ownerByNode.getOrDefault(nodeId, nodeId);
    }

    private static List<JsonContracts.Point> routeBetween(
        JsonContracts.LaidOutNode source,
        JsonContracts.LaidOutNode target,
        EdgePorts ports) {
        double sourceCenterX = source.x() + source.width() / 2.0;
        double targetCenterX = target.x() + target.width() / 2.0;

        EdgePorts effectivePorts = ports == null
            ? defaultPorts(targetCenterX >= sourceCenterX)
            : ports;
        JsonContracts.Point start = portPoint(source, effectivePorts.source());
        JsonContracts.Point end = portPoint(target, effectivePorts.target());

        double midX = start.x() + (end.x() - start.x()) / 2.0;
        List<JsonContracts.Point> points = new ArrayList<>();
        addPoint(points, start.x(), start.y());
        addPoint(points, midX, start.y());
        addPoint(points, midX, end.y());
        addPoint(points, end.x(), end.y());
        return points;
    }

    private static Map<String, EdgePorts> edgePorts(
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LaidOutNode> nodes) {
        Map<PortKey, List<PortAssignment>> assignments = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            JsonContracts.LaidOutNode source = nodes.get(edge.source());
            JsonContracts.LaidOutNode target = nodes.get(edge.target());
            if (source == null || target == null) {
                continue;
            }
            PortSide sourceSide = sourceSide(source, target);
            PortSide targetSide = opposite(sourceSide);
            assignments
                .computeIfAbsent(new PortKey(source.id(), sourceSide), ignored -> new ArrayList<>())
                .add(new PortAssignment(edge.id(), nodeCenterY(target), sourceSide, true));
            assignments
                .computeIfAbsent(new PortKey(target.id(), targetSide), ignored -> new ArrayList<>())
                .add(new PortAssignment(edge.id(), nodeCenterY(source), targetSide, false));
        }

        Map<String, PortRef> sourcePorts = new HashMap<>();
        Map<String, PortRef> targetPorts = new HashMap<>();
        for (Map.Entry<PortKey, List<PortAssignment>> entry : assignments.entrySet()) {
            List<PortAssignment> portAssignments = entry.getValue();
            portAssignments.sort(Comparator
                .comparingDouble(PortAssignment::sortY)
                .thenComparing(PortAssignment::edgeId));
            for (int index = 0; index < portAssignments.size(); index++) {
                PortAssignment assignment = portAssignments.get(index);
                PortRef port = new PortRef(assignment.side(), index, portAssignments.size());
                if (assignment.source()) {
                    sourcePorts.put(assignment.edgeId(), port);
                } else {
                    targetPorts.put(assignment.edgeId(), port);
                }
            }
        }

        Map<String, EdgePorts> portsByEdge = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            PortRef sourcePort = sourcePorts.get(edge.id());
            PortRef targetPort = targetPorts.get(edge.id());
            if (sourcePort != null && targetPort != null) {
                portsByEdge.put(edge.id(), new EdgePorts(sourcePort, targetPort));
            }
        }
        return portsByEdge;
    }

    private static EdgePorts defaultPorts(boolean targetIsRightOfSource) {
        PortSide sourceSide = targetIsRightOfSource ? PortSide.RIGHT : PortSide.LEFT;
        return new EdgePorts(
            new PortRef(sourceSide, 0, 1),
            new PortRef(opposite(sourceSide), 0, 1));
    }

    private static PortSide sourceSide(
        JsonContracts.LaidOutNode source,
        JsonContracts.LaidOutNode target) {
        double sourceCenterX = source.x() + source.width() / 2.0;
        double targetCenterX = target.x() + target.width() / 2.0;
        return targetCenterX >= sourceCenterX ? PortSide.RIGHT : PortSide.LEFT;
    }

    private static double nodeCenterY(JsonContracts.LaidOutNode node) {
        return node.y() + node.height() / 2.0;
    }

    private static PortSide opposite(PortSide side) {
        return side == PortSide.RIGHT ? PortSide.LEFT : PortSide.RIGHT;
    }

    private static JsonContracts.Point portPoint(JsonContracts.LaidOutNode node, PortRef port) {
        double x = port.side() == PortSide.RIGHT ? node.x() + node.width() : node.x();
        return new JsonContracts.Point(x, portY(node, port.index(), port.count()));
    }

    private static double portY(JsonContracts.LaidOutNode node, int index, int count) {
        if (count <= 1) {
            return node.y() + node.height() / 2.0;
        }
        double margin = Math.min(PORT_MARGIN, node.height() / 4.0);
        double available = node.height() - (margin * 2.0);
        return node.y() + margin + (available * index / (count - 1.0));
    }

    private static void addPoint(List<JsonContracts.Point> points, double x, double y) {
        if (!points.isEmpty()) {
            JsonContracts.Point last = points.get(points.size() - 1);
            if (last.x() == x && last.y() == y) {
                return;
            }
        }
        points.add(new JsonContracts.Point(x, y));
    }

    private static List<JsonContracts.LaidOutGroup> groupedBounds(
        JsonContracts.LayoutRequest request,
        Map<String, JsonContracts.LaidOutNode> macroNodes,
        Map<String, InternalLayout> internalLayouts,
        List<JsonContracts.Diagnostic> warnings) {
        List<JsonContracts.LaidOutGroup> groups = new ArrayList<>();
        List<JsonContracts.LayoutGroup> requestGroups = list(request.groups());
        for (int groupIndex = 0; groupIndex < requestGroups.size(); groupIndex++) {
            JsonContracts.LayoutGroup group = requestGroups.get(groupIndex);
            JsonContracts.LaidOutNode groupNode = macroNodes.get(group.id());
            InternalLayout internalLayout = internalLayouts.get(group.id());
            List<String> memberIds = new ArrayList<>();
            List<String> requestedMembers = list(group.members());
            for (int memberIndex = 0; memberIndex < requestedMembers.size(); memberIndex++) {
                String memberId = requestedMembers.get(memberIndex);
                if (internalLayout == null || !internalLayout.nodes().containsKey(memberId)) {
                    warnings.add(new JsonContracts.Diagnostic(
                        "DEDIREN_ELK_MISSING_GROUP_MEMBER",
                        "warning",
                        "group " + group.id() + " references missing member " + memberId,
                        "$.groups[" + groupIndex + "].members[" + memberIndex + "]"));
                    continue;
                }
                memberIds.add(memberId);
            }
            if (groupNode == null || memberIds.isEmpty()) {
                warnings.add(new JsonContracts.Diagnostic(
                    "DEDIREN_ELK_EMPTY_GROUP",
                    "warning",
                    "group " + group.id() + " has no laid out members",
                    "$.groups[" + groupIndex + "]"));
                continue;
            }

            groups.add(new JsonContracts.LaidOutGroup(
                group.id(),
                semanticBackedSourceId(group.provenance(), group.id()),
                group.id(),
                groupNode.x(),
                groupNode.y(),
                groupNode.width(),
                groupNode.height(),
                memberIds,
                group.label()));
        }
        return groups;
    }

    private static List<JsonContracts.Point> points(ElkEdge edge) {
        List<JsonContracts.Point> points = new ArrayList<>();
        for (ElkEdgeSection section : edge.getSections()) {
            if (points.isEmpty()) {
                points.add(new JsonContracts.Point(section.getStartX(), section.getStartY()));
            }
            section.getBendPoints().forEach(bend ->
                points.add(new JsonContracts.Point(bend.getX(), bend.getY())));
            points.add(new JsonContracts.Point(section.getEndX(), section.getEndY()));
        }
        return points;
    }

    private static List<JsonContracts.LaidOutGroup> groups(
        JsonContracts.LayoutRequest request,
        List<JsonContracts.LaidOutNode> nodes,
        List<JsonContracts.Diagnostic> warnings) {
        Map<String, JsonContracts.LaidOutNode> byId = new HashMap<>();
        for (JsonContracts.LaidOutNode node : nodes) {
            byId.put(node.id(), node);
        }

        List<JsonContracts.LaidOutGroup> groups = new ArrayList<>();
        List<JsonContracts.LayoutGroup> requestGroups = list(request.groups());
        for (int groupIndex = 0; groupIndex < requestGroups.size(); groupIndex++) {
            JsonContracts.LayoutGroup group = requestGroups.get(groupIndex);
            List<JsonContracts.LaidOutNode> members = new ArrayList<>();
            List<String> memberIds = new ArrayList<>();
            List<String> requestedMembers = list(group.members());
            for (int memberIndex = 0; memberIndex < requestedMembers.size(); memberIndex++) {
                String memberId = requestedMembers.get(memberIndex);
                JsonContracts.LaidOutNode member = byId.get(memberId);
                if (member == null) {
                    warnings.add(new JsonContracts.Diagnostic(
                        "DEDIREN_ELK_MISSING_GROUP_MEMBER",
                        "warning",
                        "group " + group.id() + " references missing member " + memberId,
                        "$.groups[" + groupIndex + "].members[" + memberIndex + "]"));
                    continue;
                }
                members.add(member);
                memberIds.add(memberId);
            }
            if (members.isEmpty()) {
                warnings.add(new JsonContracts.Diagnostic(
                    "DEDIREN_ELK_EMPTY_GROUP",
                    "warning",
                    "group " + group.id() + " has no laid out members",
                    "$.groups[" + groupIndex + "]"));
                continue;
            }

            double minX = members.stream().mapToDouble(JsonContracts.LaidOutNode::x).min().orElse(0.0);
            double minY = members.stream().mapToDouble(JsonContracts.LaidOutNode::y).min().orElse(0.0);
            double maxX = members.stream().mapToDouble(node -> node.x() + node.width()).max().orElse(0.0);
            double maxY = members.stream().mapToDouble(node -> node.y() + node.height()).max().orElse(0.0);

            groups.add(new JsonContracts.LaidOutGroup(
                group.id(),
                semanticBackedSourceId(group.provenance(), group.id()),
                group.id(),
                minX - GROUP_PADDING,
                minY - GROUP_PADDING,
                (maxX - minX) + (GROUP_PADDING * 2.0),
                (maxY - minY) + (GROUP_PADDING * 2.0),
                memberIds,
                group.label()));
        }
        return groups;
    }

    private static String semanticBackedSourceId(
        JsonContracts.GroupProvenance provenance,
        String fallback) {
        if (provenance == null || provenance.semantic_backed() == null) {
            return fallback;
        }
        String sourceId = provenance.semantic_backed().source_id();
        return sourceId == null ? fallback : sourceId;
    }

    private static void validate(JsonContracts.LayoutRequest request) {
        requireNonNull(request, "$");
        requireNonNull(request.layout_request_schema_version(), "$.layout_request_schema_version");
        if (!request.layout_request_schema_version().equals("layout-request.schema.v1")) {
            throw new IllegalArgumentException(
                "$.layout_request_schema_version must be layout-request.schema.v1");
        }
        requireNonNull(request.view_id(), "$.view_id");
        requireNonNull(request.nodes(), "$.nodes");
        requireNonNull(request.edges(), "$.edges");
        requireNonNull(request.groups(), "$.groups");
        requireNonNull(request.labels(), "$.labels");
        requireNonNull(request.constraints(), "$.constraints");

        for (int index = 0; index < request.nodes().size(); index++) {
            JsonContracts.LayoutNode node = request.nodes().get(index);
            String path = "$.nodes[" + index + "]";
            requireNonNull(node, path);
            requireNonNull(node.id(), path + ".id");
            requireNonNull(node.label(), path + ".label");
            requireNonNull(node.source_id(), path + ".source_id");
            requirePositive(node.width_hint(), path + ".width_hint");
            requirePositive(node.height_hint(), path + ".height_hint");
        }

        for (int index = 0; index < request.edges().size(); index++) {
            JsonContracts.LayoutEdge edge = request.edges().get(index);
            String path = "$.edges[" + index + "]";
            requireNonNull(edge, path);
            requireNonNull(edge.id(), path + ".id");
            requireNonNull(edge.source(), path + ".source");
            requireNonNull(edge.target(), path + ".target");
            requireNonNull(edge.label(), path + ".label");
            requireNonNull(edge.source_id(), path + ".source_id");
        }

        for (int index = 0; index < request.groups().size(); index++) {
            JsonContracts.LayoutGroup group = request.groups().get(index);
            String path = "$.groups[" + index + "]";
            requireNonNull(group, path);
            requireNonNull(group.id(), path + ".id");
            requireNonNull(group.label(), path + ".label");
            requireNonNull(group.members(), path + ".members");
            requireNonNull(group.provenance(), path + ".provenance");
            validateProvenance(group.provenance(), path + ".provenance");
            for (int memberIndex = 0; memberIndex < group.members().size(); memberIndex++) {
                requireNonNull(
                    group.members().get(memberIndex),
                    path + ".members[" + memberIndex + "]");
            }
        }

        for (int index = 0; index < request.labels().size(); index++) {
            JsonContracts.LayoutLabel label = request.labels().get(index);
            String path = "$.labels[" + index + "]";
            requireNonNull(label, path);
            requireNonNull(label.owner_id(), path + ".owner_id");
            requireNonNull(label.text(), path + ".text");
        }

        for (int index = 0; index < request.constraints().size(); index++) {
            JsonContracts.LayoutConstraint constraint = request.constraints().get(index);
            String path = "$.constraints[" + index + "]";
            requireNonNull(constraint, path);
            requireNonNull(constraint.id(), path + ".id");
            requireNonNull(constraint.kind(), path + ".kind");
            requireNonNull(constraint.subjects(), path + ".subjects");
            for (int subjectIndex = 0; subjectIndex < constraint.subjects().size(); subjectIndex++) {
                requireNonNull(
                    constraint.subjects().get(subjectIndex),
                    path + ".subjects[" + subjectIndex + "]");
            }
        }
    }

    private static void validateProvenance(JsonContracts.GroupProvenance provenance, String path) {
        if (provenance.semantic_backed() == null) {
            return;
        }
        String semanticBackedPath = path + ".semantic_backed";
        if (provenance.semantic_backed().source_id() == null) {
            throw new IllegalArgumentException(
                "required string value is missing at " + semanticBackedPath + ".source_id");
        }
    }

    private static void requireNonNull(Object value, String path) {
        if (value == null) {
            throw new IllegalArgumentException("required value is missing at " + path);
        }
    }

    private static void requirePositive(Double value, String path) {
        if (value != null && (!Double.isFinite(value) || value <= 0.0)) {
            throw new IllegalArgumentException("value at " + path + " must be finite and positive");
        }
    }

    private static double positiveOrDefault(Double value, double fallback) {
        return value != null && value > 0.0 ? value : fallback;
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }
}

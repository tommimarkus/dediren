package dev.dediren.elk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.alg.layered.options.WrappingStrategy;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.options.HierarchyHandling;
import org.eclipse.elk.core.options.PortConstraints;
import org.eclipse.elk.core.options.PortSide;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.ElkPort;
import org.eclipse.elk.graph.util.ElkGraphUtil;

final class ElkLayoutEngine {
    private static final double DEFAULT_WIDTH = 160.0;
    private static final double DEFAULT_HEIGHT = 80.0;
    private static final double GROUP_PADDING = 24.0;
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
            ElkEdge elkEdge = createRoutedEdge(source, target, edge, Direction.RIGHT);
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
        ElkNode root = ElkGraphUtil.createGraph();
        configureRoot(root, Direction.DOWN);
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN);
        root.setProperty(CoreOptions.ASPECT_RATIO, 2.2);
        root.setProperty(LayeredOptions.WRAPPING_STRATEGY, WrappingStrategy.MULTI_EDGE);

        Map<String, ElkNode> elkGroups = new HashMap<>();
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

            ElkNode elkGroup = ElkGraphUtil.createNode(root);
            elkGroup.setIdentifier(group.id());
            ElkGraphUtil.createLabel(elkGroup).setText(group.label());
            configureRoot(elkGroup, internalDirection(members, internalEdges));
            elkGroup.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN);
            elkGroup.setProperty(CoreOptions.PADDING, new ElkPadding(GROUP_PADDING));
            elkGroups.put(group.id(), elkGroup);
        }

        Map<String, ElkNode> elkNodes = new HashMap<>();
        for (JsonContracts.LayoutNode node : list(request.nodes())) {
            ElkNode parent = ownerByNode.containsKey(node.id())
                ? elkGroups.get(ownerByNode.get(node.id()))
                : root;
            if (parent == null) {
                continue;
            }
            ElkNode elkNode = ElkGraphUtil.createNode(parent);
            elkNode.setIdentifier(node.id());
            elkNode.setDimensions(
                positiveOrDefault(node.width_hint(), DEFAULT_WIDTH),
                positiveOrDefault(node.height_hint(), DEFAULT_HEIGHT));
            ElkGraphUtil.createLabel(elkNode).setText(node.label());
            elkNodes.put(node.id(), elkNode);
        }

        Map<String, ElkEdge> elkEdges = new HashMap<>();
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
            ElkEdge elkEdge = createRoutedEdge(source, target, edge, Direction.DOWN);
            ElkGraphUtil.updateContainment(elkEdge);
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
                    absoluteX(elkNode),
                    absoluteY(elkNode),
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
            groupedBounds(request, elkGroups, elkNodes, warnings);

        return new JsonContracts.LayoutResult(
            "layout-result.schema.v1",
            request.view_id(),
            nodes,
            edges,
            groups,
            warnings);
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

    private static ElkEdge createRoutedEdge(
        ElkNode source,
        ElkNode target,
        JsonContracts.LayoutEdge edge,
        Direction direction) {
        source.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE);
        target.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE);
        ElkPort sourcePort = ElkGraphUtil.createPort(source);
        sourcePort.setIdentifier(edge.id() + "-source");
        sourcePort.setDimensions(0.0, 0.0);
        sourcePort.setProperty(CoreOptions.PORT_SIDE, sourcePortSide(direction));
        ElkPort targetPort = ElkGraphUtil.createPort(target);
        targetPort.setIdentifier(edge.id() + "-target");
        targetPort.setDimensions(0.0, 0.0);
        targetPort.setProperty(CoreOptions.PORT_SIDE, targetPortSide(direction));
        ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(sourcePort, targetPort);
        elkEdge.setIdentifier(edge.id());
        ElkGraphUtil.createLabel(elkEdge).setText(edge.label());
        return elkEdge;
    }

    private static PortSide sourcePortSide(Direction direction) {
        return direction == Direction.DOWN ? PortSide.SOUTH : PortSide.EAST;
    }

    private static PortSide targetPortSide(Direction direction) {
        return direction == Direction.DOWN ? PortSide.NORTH : PortSide.WEST;
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

    private static List<JsonContracts.LaidOutGroup> groupedBounds(
        JsonContracts.LayoutRequest request,
        Map<String, ElkNode> elkGroups,
        Map<String, ElkNode> elkNodes,
        List<JsonContracts.Diagnostic> warnings) {
        List<JsonContracts.LaidOutGroup> groups = new ArrayList<>();
        List<JsonContracts.LayoutGroup> requestGroups = list(request.groups());
        for (int groupIndex = 0; groupIndex < requestGroups.size(); groupIndex++) {
            JsonContracts.LayoutGroup group = requestGroups.get(groupIndex);
            ElkNode groupNode = elkGroups.get(group.id());
            List<String> memberIds = new ArrayList<>();
            List<String> requestedMembers = list(group.members());
            for (int memberIndex = 0; memberIndex < requestedMembers.size(); memberIndex++) {
                String memberId = requestedMembers.get(memberIndex);
                ElkNode memberNode = elkNodes.get(memberId);
                if (memberNode == null || memberNode.getParent() != groupNode) {
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
                absoluteX(groupNode),
                absoluteY(groupNode),
                groupNode.getWidth(),
                groupNode.getHeight(),
                memberIds,
                group.label()));
        }
        return groups;
    }

    private static double absoluteX(ElkNode node) {
        double x = node.getX();
        ElkNode parent = node.getParent();
        while (parent != null) {
            x += parent.getX();
            parent = parent.getParent();
        }
        return x;
    }

    private static double absoluteY(ElkNode node) {
        double y = node.getY();
        ElkNode parent = node.getParent();
        while (parent != null) {
            y += parent.getY();
            parent = parent.getParent();
        }
        return y;
    }

    private static List<JsonContracts.Point> points(ElkEdge edge) {
        List<JsonContracts.Point> points = new ArrayList<>();
        double offsetX = edge.getContainingNode() == null ? 0.0 : absoluteX(edge.getContainingNode());
        double offsetY = edge.getContainingNode() == null ? 0.0 : absoluteY(edge.getContainingNode());
        for (ElkEdgeSection section : edge.getSections()) {
            if (points.isEmpty()) {
                points.add(new JsonContracts.Point(
                    section.getStartX() + offsetX,
                    section.getStartY() + offsetY));
            }
            section.getBendPoints().forEach(bend ->
                points.add(new JsonContracts.Point(bend.getX() + offsetX, bend.getY() + offsetY)));
            points.add(new JsonContracts.Point(
                section.getEndX() + offsetX,
                section.getEndY() + offsetY));
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

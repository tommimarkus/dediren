package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutLabel;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.LayoutRoutingPreferences;
import dev.dediren.contracts.layout.Point;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.PortConstraints;
import org.eclipse.elk.core.options.PortSide;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkConnectableShape;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.ElkPort;
import org.eclipse.elk.graph.util.ElkGraphUtil;

final class ElkLayoutEngine {
    private static final double DEFAULT_WIDTH = 160.0;
    private static final double DEFAULT_HEIGHT = 80.0;
    private static final double CONNECTOR_SOURCE_MAX_WIDTH = 48.0;
    private static final double CONNECTOR_SOURCE_MAX_HEIGHT = 48.0;
    private static final int DEFAULT_SHORT_SIDE_PORT_CAPACITY = 3;
    private static final int MERGEABLE_ENDPOINT_EDGE_COUNT = 3;
    private static final String SHARED_SOURCE_JUNCTION_HINT = "shared_source_junction";
    private static final String SHARED_TARGET_JUNCTION_HINT = "shared_target_junction";
    private static final EdgeEndpointMerge NO_ENDPOINT_MERGE =
        new EdgeEndpointMerge(false, false);

    LayoutResult layout(LayoutRequest request) {
        validate(request);
        if (!list(request.groups()).isEmpty()) {
            return layoutGrouped(request);
        }

        return layoutFlat(request);
    }

    private static LayoutResult layoutFlat(LayoutRequest request) {
        LayoutPreferences preferences = request.layoutPreferences();
        Direction layoutDirection = ElkLayeredOptions.preferredDirection(preferences);
        ElkNode root = ElkGraphUtil.createGraph();
        ElkLayeredOptions.configureRoot(root, layoutDirection, preferences);

        Map<String, LayoutNode> requestNodes = requestNodesById(request);
        List<LayoutEdge> requestEdges = list(request.edges());
        Map<String, EdgeEndpointMerge> endpointMerges =
            flatEdgeEndpointMerges(requestEdges, requestNodes, preferences);
        Map<String, EdgeEndpointSides> endpointSides =
            flatEdgeEndpointSides(requestEdges, requestNodes, endpointMerges, layoutDirection);
        Map<String, EnumMap<PortSide, Integer>> portCounts =
            flatPortCounts(requestEdges, requestNodes, endpointMerges, endpointSides);
        Map<String, ElkNode> elkNodes = new HashMap<>();
        for (LayoutNode node : list(request.nodes())) {
            ElkNode elkNode = ElkGraphUtil.createNode(root);
            elkNode.setIdentifier(node.id());
            setGeneratedDimensions(elkNode, node, portCounts.get(node.id()), preferences);
            ElkGraphUtil.createLabel(elkNode).setText(node.label());
            elkNodes.put(node.id(), elkNode);
        }

        Map<String, ElkEdge> elkEdges = new HashMap<>();
        List<Diagnostic> warnings = new ArrayList<>();
        Map<String, EnumMap<PortSide, Integer>> portIndexes = new HashMap<>();
        for (int index = 0; index < requestEdges.size(); index++) {
            LayoutEdge edge = requestEdges.get(index);
            ElkNode source = elkNodes.get(edge.source());
            ElkNode target = elkNodes.get(edge.target());
            if (source == null || target == null) {
                warnings.add(new Diagnostic(
                    "DEDIREN_ELK_DANGLING_EDGE",
                    DiagnosticSeverity.WARNING,
                    "edge " + edge.id() + " references a missing endpoint",
                    "$.edges[" + index + "]"));
                continue;
            }
            EdgeEndpointMerge endpointMerge =
                endpointMerges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
            EdgeEndpointSides sides =
                endpointSides.getOrDefault(edge.id(), defaultEndpointSides(layoutDirection));
            ElkEdge elkEdge = createRoutedEdge(
                source,
                target,
                edge,
                sides.sourceSide(),
                sides.targetSide(),
                endpointMerge.sourceEndpoint()
                    ? 0
                    : nextPortIndex(portIndexes, edge.source(), sides.sourceSide()),
                endpointMerge.targetEndpoint()
                    ? 0
                    : nextPortIndex(portIndexes, edge.target(), sides.targetSide()),
                endpointMerge.sourceEndpoint(),
                endpointMerge.targetEndpoint());
            elkEdges.put(edge.id(), elkEdge);
        }

        new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());

        List<LaidOutNode> nodes = new ArrayList<>();
        for (LayoutNode node : list(request.nodes())) {
            ElkNode elkNode = elkNodes.get(node.id());
            if (elkNode != null) {
                nodes.add(new LaidOutNode(
                    node.id(),
                    node.sourceId(),
                    node.id(),
                    elkNode.getX(),
                    elkNode.getY(),
                    elkNode.getWidth(),
                    elkNode.getHeight(),
                    node.label()));
            }
        }

        List<LaidOutEdge> edges = new ArrayList<>();
        for (LayoutEdge edge : list(request.edges())) {
            ElkEdge elkEdge = elkEdges.get(edge.id());
            if (elkEdge != null) {
                edges.add(new LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.sourceId(),
                    edge.id(),
                    routingHints(edge.id(), endpointMerges),
                    points(elkEdge),
                    edge.label()));
            }
        }

        List<LaidOutGroup> groups =
            groups(request, nodes, warnings);

        return new LayoutResult(
            "layout-result.schema.v1",
            request.viewId(),
            nodes,
            edges,
            groups,
            warnings);
    }

    private static LayoutResult layoutGrouped(LayoutRequest request) {
        LayoutPreferences preferences = request.layoutPreferences();
        Direction rootDirection = ElkLayeredOptions.preferredDirection(preferences);
        List<Diagnostic> warnings = new ArrayList<>();
        Map<String, LayoutNode> requestNodes = requestNodesById(request);
        Map<String, String> ownerByNode = ownerByNode(request);
        List<LayoutEdge> requestEdges = list(request.edges());
        ElkNode root = ElkGraphUtil.createGraph();
        ElkLayeredOptions.configureGroupedRoot(root, rootDirection, preferences);

        Map<String, ElkNode> elkGroups = new HashMap<>();
        Map<String, Direction> groupDirectionById = new HashMap<>();
        Map<String, Integer> groupOrderById = new HashMap<>();
        List<LayoutGroup> requestGroups = list(request.groups());
        for (int groupIndex = 0; groupIndex < requestGroups.size(); groupIndex++) {
            LayoutGroup group = requestGroups.get(groupIndex);
            groupOrderById.put(group.id(), groupIndex);
            List<LayoutNode> members = list(group.members()).stream()
                .map(requestNodes::get)
                .filter(node -> node != null)
                .toList();
            if (members.isEmpty()) {
                continue;
            }
            List<LayoutEdge> internalEdges = list(request.edges()).stream()
                .filter(edge -> group.id().equals(ownerByNode.get(edge.source()))
                    && group.id().equals(ownerByNode.get(edge.target())))
                .toList();

            ElkNode elkGroup = ElkGraphUtil.createNode(root);
            elkGroup.setIdentifier(group.id());
            ElkGraphUtil.createLabel(elkGroup).setText(group.label());
            Direction groupDirection = internalDirection(members, internalEdges);
            ElkLayeredOptions.configureGroup(elkGroup, groupDirection, preferences);
            elkGroups.put(group.id(), elkGroup);
            groupDirectionById.put(group.id(), groupDirection);
        }

        Map<String, EdgeEndpointMerge> endpointMerges = groupedEdgeEndpointMerges(
            requestEdges,
            requestNodes,
            ownerByNode,
            groupDirectionById,
            groupOrderById,
            rootDirection,
            preferences);
        Map<String, EnumMap<PortSide, Integer>> portCounts = groupedPortCounts(
            requestEdges,
            requestNodes,
            ownerByNode,
            groupDirectionById,
            groupOrderById,
            rootDirection,
            endpointMerges);
        Map<String, ElkNode> elkNodes = new HashMap<>();
        for (LayoutNode node : list(request.nodes())) {
            ElkNode parent = ownerByNode.containsKey(node.id())
                ? elkGroups.get(ownerByNode.get(node.id()))
                : root;
            if (parent == null) {
                continue;
            }
            ElkNode elkNode = ElkGraphUtil.createNode(parent);
            elkNode.setIdentifier(node.id());
            setGeneratedDimensions(elkNode, node, portCounts.get(node.id()), preferences);
            ElkGraphUtil.createLabel(elkNode).setText(node.label());
            elkNodes.put(node.id(), elkNode);
        }

        Map<String, ElkEdge> elkEdges = new HashMap<>();
        Map<String, EdgePortIndexes> edgePortIndexes = groupedEdgePortIndexes(
            request,
            requestNodes,
            ownerByNode,
            groupDirectionById,
            groupOrderById,
            rootDirection);
        for (int index = 0; index < requestEdges.size(); index++) {
            LayoutEdge edge = requestEdges.get(index);
            ElkNode source = elkNodes.get(edge.source());
            ElkNode target = elkNodes.get(edge.target());
            if (source == null || target == null) {
                warnings.add(new Diagnostic(
                    "DEDIREN_ELK_DANGLING_EDGE",
                    DiagnosticSeverity.WARNING,
                    "edge " + edge.id() + " references a missing endpoint",
                    "$.edges[" + index + "]"));
                continue;
            }
            Direction edgeDirection =
                edgeDirection(
                    edge,
                    requestNodes,
                    ownerByNode,
                    groupDirectionById,
                    groupOrderById,
                    rootDirection);
            EdgeEndpointMerge endpointMerge =
                endpointMerges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
            EdgePortIndexes portIndexes = edgePortIndexes.get(edge.id());
            ElkEdge elkEdge = createRoutedEdge(
                source,
                target,
                edge,
                edgeDirection,
                endpointMerge.sourceEndpoint()
                    ? 0
                    : portIndexes == null ? 0 : portIndexes.sourceIndex(),
                endpointMerge.targetEndpoint()
                    ? 0
                    : portIndexes == null ? 0 : portIndexes.targetIndex(),
                endpointMerge.sourceEndpoint(),
                endpointMerge.targetEndpoint());
            ElkGraphUtil.updateContainment(elkEdge);
            elkEdges.put(edge.id(), elkEdge);
        }

        new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());

        List<LaidOutNode> nodes = new ArrayList<>();
        for (LayoutNode node : list(request.nodes())) {
            ElkNode elkNode = elkNodes.get(node.id());
            if (elkNode != null) {
                nodes.add(new LaidOutNode(
                    node.id(),
                    node.sourceId(),
                    node.id(),
                    absoluteX(elkNode),
                    absoluteY(elkNode),
                    elkNode.getWidth(),
                    elkNode.getHeight(),
                    node.label()));
            }
        }

        List<LaidOutEdge> edges = new ArrayList<>();
        List<LaidOutGroup> groups =
            groupedBounds(request, elkGroups, elkNodes, warnings);
        for (LayoutEdge edge : list(request.edges())) {
            ElkEdge elkEdge = elkEdges.get(edge.id());
            if (elkEdge != null) {
                edges.add(new LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.sourceId(),
                    edge.id(),
                    routingHints(edge.id(), endpointMerges),
                    points(elkEdge),
                    edge.label()));
            }
        }
        // Route geometry belongs to ELK Layered. Keep route-quality concerns in
        // ELK graph construction, ELK options, and validation diagnostics.

        return new LayoutResult(
            "layout-result.schema.v1",
            request.viewId(),
            nodes,
            edges,
            groups,
            warnings);
    }

    private static Map<String, EdgeEndpointMerge> emptyEndpointMerges(
        List<LayoutEdge> edges) {
        Map<String, EdgeEndpointMerge> endpointMerges = new HashMap<>();
        for (LayoutEdge edge : edges) {
            endpointMerges.put(edge.id(), NO_ENDPOINT_MERGE);
        }
        return endpointMerges;
    }

    private static Direction internalDirection(
        List<LayoutNode> nodes,
        List<LayoutEdge> edges) {
        if (nodes.size() < 3) {
            return Direction.RIGHT;
        }
        if (nodes.stream().anyMatch(ElkLayoutEngine::isConnectorSizedRequestNode)) {
            return Direction.DOWN;
        }
        // A same-source service fan-out reads as a left-to-right call flow.
        // Express that as ELK direction intent instead of correcting routes
        // after ELK has produced them.
        if (hasInternalFanOut(edges)) {
            return Direction.RIGHT;
        }
        return Direction.DOWN;
    }

    private static boolean hasInternalFanOut(List<LayoutEdge> edges) {
        Map<String, Integer> outgoingCounts = new HashMap<>();
        for (LayoutEdge edge : edges) {
            int count = outgoingCounts.merge(edge.source(), 1, Integer::sum);
            if (count >= MERGEABLE_ENDPOINT_EDGE_COUNT) {
                return true;
            }
        }
        return false;
    }

    private static Direction edgeDirection(
        LayoutEdge edge,
        Map<String, LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById,
        Direction rootDirection) {
        String sourceOwner = ownerByNode.get(edge.source());
        String targetOwner = ownerByNode.get(edge.target());
        if (sourceOwner != null && sourceOwner.equals(targetOwner)) {
            if (isConnectorSizedRequestNode(nodes.get(edge.source()))
                || isConnectorSizedRequestNode(nodes.get(edge.target()))) {
                return Direction.RIGHT;
            }
            return groupDirectionById.getOrDefault(sourceOwner, Direction.RIGHT);
        }
        if (sourceOwner != null && targetOwner != null) {
            int sourceOrder = groupOrderById.getOrDefault(sourceOwner, 0);
            int targetOrder = groupOrderById.getOrDefault(targetOwner, 0);
            if (sourceOrder > targetOrder) {
                return oppositeDirection(rootDirection);
            }
        }
        return rootDirection;
    }

    private static Direction oppositeDirection(Direction direction) {
        return switch (direction) {
            case LEFT -> Direction.RIGHT;
            case UP -> Direction.DOWN;
            case DOWN -> Direction.UP;
            default -> Direction.LEFT;
        };
    }

    private static boolean isConnectorSizedRequestNode(LayoutNode node) {
        if (node == null) {
            return false;
        }
        double width = node.widthHint() == null ? DEFAULT_WIDTH : node.widthHint();
        double height = node.heightHint() == null ? DEFAULT_HEIGHT : node.heightHint();
        return width <= CONNECTOR_SOURCE_MAX_WIDTH && height <= CONNECTOR_SOURCE_MAX_HEIGHT;
    }

    private static Map<String, EdgeEndpointSides> flatEdgeEndpointSides(
        List<LayoutEdge> edges,
        Map<String, LayoutNode> nodes,
        Map<String, EdgeEndpointMerge> endpointMerges,
        Direction direction) {
        Map<String, Integer> outgoingCounts = new HashMap<>();
        Map<String, Integer> incomingCounts = new HashMap<>();
        for (LayoutEdge edge : edges) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            outgoingCounts.merge(edge.source(), 1, Integer::sum);
            incomingCounts.merge(edge.target(), 1, Integer::sum);
        }

        Map<String, Integer> outgoingIndexes = new HashMap<>();
        Map<String, Integer> incomingIndexes = new HashMap<>();
        Map<String, EdgeEndpointSides> sidesByEdge = new HashMap<>();
        EdgeEndpointSides defaultSides = defaultEndpointSides(direction);
        for (LayoutEdge edge : edges) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            EdgeEndpointMerge endpointMerge =
                endpointMerges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
            int outgoingIndex = nextEndpointIndex(outgoingIndexes, edge.source());
            int incomingIndex = nextEndpointIndex(incomingIndexes, edge.target());
            PortSide sourceSide = defaultSides.sourceSide();
            PortSide targetSide = defaultSides.targetSide();
            if (!endpointMerge.sourceEndpoint()
                && outgoingCounts.getOrDefault(edge.source(), 0) > 1
                && isConnectorSizedRequestNode(nodes.get(edge.source()))) {
                sourceSide = connectorBranchSide(direction, true, outgoingIndex);
            }
            if (!endpointMerge.targetEndpoint()
                && incomingCounts.getOrDefault(edge.target(), 0) > 1
                && isConnectorSizedRequestNode(nodes.get(edge.target()))) {
                targetSide = connectorBranchSide(direction, false, incomingIndex);
            }
            sidesByEdge.put(edge.id(), new EdgeEndpointSides(sourceSide, targetSide));
        }
        return sidesByEdge;
    }

    private static int nextEndpointIndex(Map<String, Integer> indexes, String nodeId) {
        int index = indexes.getOrDefault(nodeId, 0);
        indexes.put(nodeId, index + 1);
        return index;
    }

    private static EdgeEndpointSides defaultEndpointSides(Direction direction) {
        return new EdgeEndpointSides(sourcePortSide(direction), targetPortSide(direction));
    }

    private static PortSide connectorBranchSide(
        Direction direction,
        boolean sourceEndpoint,
        int index) {
        PortSide primary = sourceEndpoint
            ? sourcePortSide(direction)
            : targetPortSide(direction);
        PortSide[] alternates = switch (primary) {
            case EAST, WEST -> new PortSide[] { primary, PortSide.NORTH, PortSide.SOUTH };
            case NORTH, SOUTH -> new PortSide[] { primary, PortSide.EAST, PortSide.WEST };
            default -> new PortSide[] { primary };
        };
        return alternates[Math.min(index, alternates.length - 1)];
    }

    private static ElkEdge createRoutedEdge(
        ElkNode source,
        ElkNode target,
        LayoutEdge edge,
        Direction direction,
        int sourcePortIndex,
        int targetPortIndex,
        boolean mergeSourceEndpoint,
        boolean mergeTargetEndpoint) {
        return createRoutedEdge(
            source,
            target,
            edge,
            sourcePortSide(direction),
            targetPortSide(direction),
            sourcePortIndex,
            targetPortIndex,
            mergeSourceEndpoint,
            mergeTargetEndpoint);
    }

    private static ElkEdge createRoutedEdge(
        ElkNode source,
        ElkNode target,
        LayoutEdge edge,
        PortSide sourceSide,
        PortSide targetSide,
        int sourcePortIndex,
        int targetPortIndex,
        boolean mergeSourceEndpoint,
        boolean mergeTargetEndpoint) {
        String relationshipType = relationshipType(edge);
        ElkConnectableShape sourceShape = mergeSourceEndpoint
            ? sharedMergePort(source, sourceSide, true, relationshipType)
            : createEdgePort(source, edge.id() + "-source", sourceSide, sourcePortIndex);
        ElkConnectableShape targetShape = mergeTargetEndpoint
            ? sharedMergePort(target, targetSide, false, relationshipType)
            : createEdgePort(target, edge.id() + "-target", targetSide, targetPortIndex);
        ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(sourceShape, targetShape);
        elkEdge.setIdentifier(edge.id());
        ElkGraphUtil.createLabel(elkEdge).setText(edge.label());
        return elkEdge;
    }

    private static ElkPort createEdgePort(
        ElkNode node,
        String id,
        PortSide side,
        int index) {
        node.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_ORDER);
        ElkPort port = ElkGraphUtil.createPort(node);
        port.setIdentifier(id);
        port.setDimensions(1.0, 1.0);
        port.setProperty(CoreOptions.PORT_SIDE, side);
        port.setProperty(CoreOptions.PORT_INDEX, index);
        return port;
    }

    private static ElkPort sharedMergePort(
        ElkNode node,
        PortSide side,
        boolean sourceEndpoint,
        String relationshipType) {
        String id = "__dediren_merge_"
            + (sourceEndpoint ? "source_" : "target_")
            + side.name()
            + "_"
            + relationshipTypePortSuffix(relationshipType);
        for (ElkPort port : node.getPorts()) {
            if (id.equals(port.getIdentifier())) {
                return port;
            }
        }
        return createEdgePort(node, id, side, nextExistingPortIndex(node, side));
    }

    private static String relationshipTypePortSuffix(String relationshipType) {
        return Integer.toHexString(relationshipType.hashCode());
    }

    private static String relationshipType(LayoutEdge edge) {
        String relationshipType = edge.relationshipType();
        if (relationshipType == null || relationshipType.isBlank()) {
            return null;
        }
        return relationshipType;
    }

    private static int nextExistingPortIndex(ElkNode node, PortSide side) {
        int maxIndex = -1;
        for (ElkPort port : node.getPorts()) {
            if (port.getProperty(CoreOptions.PORT_SIDE) == side) {
                maxIndex = Math.max(maxIndex, port.getProperty(CoreOptions.PORT_INDEX));
            }
        }
        return maxIndex + 1;
    }

    // Endpoint merging is Dediren graph shaping, not route geometry. ELK's
    // MERGE_EDGES and MERGE_HIERARCHY_EDGES are broad booleans; Dediren keeps
    // relationship-type scoped shared ports so intentional fan-in/fan-out
    // junctions remain readable without globally merging unrelated edges.
    private static Map<String, EdgeEndpointMerge> flatEdgeEndpointMerges(
        List<LayoutEdge> edges,
        Map<String, LayoutNode> nodes,
        LayoutPreferences preferences) {
        if (!ElkLayeredOptions.endpointMergingEnabled(preferences)) {
            return emptyEndpointMerges(edges);
        }

        Direction direction = ElkLayeredOptions.preferredDirection(preferences);
        Map<EdgeEndpointKey, Integer> endpointCounts = new HashMap<>();
        for (LayoutEdge edge : edges) {
            String relationshipType = relationshipType(edge);
            if (!nodes.containsKey(edge.source())
                || !nodes.containsKey(edge.target())
                || edge.source().equals(edge.target())
                || relationshipType == null) {
                continue;
            }
            endpointCounts.merge(
                new EdgeEndpointKey(edge.source(), sourcePortSide(direction), true, relationshipType),
                1,
                Integer::sum);
            endpointCounts.merge(
                new EdgeEndpointKey(edge.target(), targetPortSide(direction), false, relationshipType),
                1,
                Integer::sum);
        }

        Map<String, EdgeEndpointMerge> endpointMerges = new HashMap<>();
        for (LayoutEdge edge : edges) {
            String relationshipType = relationshipType(edge);
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            endpointMerges.put(edge.id(), new EdgeEndpointMerge(
                relationshipType != null
                    && endpointCounts.getOrDefault(
                        new EdgeEndpointKey(edge.source(), sourcePortSide(direction), true, relationshipType),
                        0) >= MERGEABLE_ENDPOINT_EDGE_COUNT,
                relationshipType != null
                    && endpointCounts.getOrDefault(
                        new EdgeEndpointKey(edge.target(), targetPortSide(direction), false, relationshipType),
                        0) >= MERGEABLE_ENDPOINT_EDGE_COUNT));
        }
        return endpointMerges;
    }

    private static Map<String, EdgeEndpointMerge> groupedEdgeEndpointMerges(
        List<LayoutEdge> edges,
        Map<String, LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById,
        Direction rootDirection,
        LayoutPreferences preferences) {
        if (!ElkLayeredOptions.endpointMergingEnabled(preferences)) {
            return emptyEndpointMerges(edges);
        }

        Set<String> sourceOnlyGroups = sourceOnlyGroups(edges, ownerByNode);
        Map<EdgeEndpointKey, Integer> endpointCounts = new HashMap<>();
        for (LayoutEdge edge : edges) {
            String relationshipType = relationshipType(edge);
            if (!nodes.containsKey(edge.source())
                || !nodes.containsKey(edge.target())
                || edge.source().equals(edge.target())
                || sameOwnerInternalEdge(edge, ownerByNode)
                || relationshipType == null) {
                continue;
            }
            Direction direction =
                edgeDirection(
                    edge,
                    nodes,
                    ownerByNode,
                    groupDirectionById,
                    groupOrderById,
                    rootDirection);
            endpointCounts.merge(
                new EdgeEndpointKey(edge.source(), sourcePortSide(direction), true, relationshipType),
                1,
                Integer::sum);
            endpointCounts.merge(
                new EdgeEndpointKey(edge.target(), targetPortSide(direction), false, relationshipType),
                1,
                Integer::sum);
        }

        Map<String, EdgeEndpointMerge> endpointMerges = new HashMap<>();
        for (LayoutEdge edge : edges) {
            String relationshipType = relationshipType(edge);
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            boolean mergeableEndpoint =
                relationshipType != null && !sameOwnerInternalEdge(edge, ownerByNode);
            // Actor/source-only groups often have only two equivalent entry
            // edges into a platform node. Merge that target pair so ELK owns a
            // single shared endpoint instead of forcing an arbitrary port order.
            boolean sourceOnlyGroupTargetEndpoint =
                sourceOnlyGroups.contains(ownerByNode.get(edge.source()));
            Direction direction =
                edgeDirection(
                    edge,
                    nodes,
                    ownerByNode,
                    groupDirectionById,
                    groupOrderById,
                    rootDirection);
            endpointMerges.put(edge.id(), new EdgeEndpointMerge(
                mergeableEndpoint
                    && endpointCounts.getOrDefault(
                        new EdgeEndpointKey(edge.source(), sourcePortSide(direction), true, relationshipType),
                        0) >= MERGEABLE_ENDPOINT_EDGE_COUNT,
                mergeableEndpoint
                    && endpointCounts.getOrDefault(
                        new EdgeEndpointKey(edge.target(), targetPortSide(direction), false, relationshipType),
                        0) >= (sourceOnlyGroupTargetEndpoint ? 2 : MERGEABLE_ENDPOINT_EDGE_COUNT)));
        }
        return endpointMerges;
    }

    private static Set<String> sourceOnlyGroups(
        List<LayoutEdge> edges,
        Map<String, String> ownerByNode) {
        Set<String> groups = new HashSet<>(ownerByNode.values());
        Set<String> nonSourceOnlyGroups = new HashSet<>();
        Set<String> groupsWithOutgoingEdges = new HashSet<>();
        for (LayoutEdge edge : edges) {
            String sourceOwner = ownerByNode.get(edge.source());
            String targetOwner = ownerByNode.get(edge.target());
            if (sourceOwner != null && sourceOwner.equals(targetOwner)) {
                nonSourceOnlyGroups.add(sourceOwner);
                continue;
            }
            if (sourceOwner != null) {
                groupsWithOutgoingEdges.add(sourceOwner);
            }
            if (targetOwner != null) {
                nonSourceOnlyGroups.add(targetOwner);
            }
        }
        groups.retainAll(groupsWithOutgoingEdges);
        groups.removeAll(nonSourceOnlyGroups);
        return groups;
    }

    private static boolean sameOwnerInternalEdge(
        LayoutEdge edge,
        Map<String, String> ownerByNode) {
        String sourceOwner = ownerByNode.get(edge.source());
        return sourceOwner != null && sourceOwner.equals(ownerByNode.get(edge.target()));
    }

    private static List<String> routingHints(
        String edgeId,
        Map<String, EdgeEndpointMerge> endpointMerges) {
        EdgeEndpointMerge endpointMerge =
            endpointMerges.getOrDefault(edgeId, NO_ENDPOINT_MERGE);
        List<String> hints = new ArrayList<>();
        if (endpointMerge.sourceEndpoint()) {
            hints.add(SHARED_SOURCE_JUNCTION_HINT);
        }
        if (endpointMerge.targetEndpoint()) {
            hints.add(SHARED_TARGET_JUNCTION_HINT);
        }
        return hints;
    }

    private static Map<String, EnumMap<PortSide, Integer>> flatPortCounts(
        List<LayoutEdge> edges,
        Map<String, LayoutNode> nodes,
        Map<String, EdgeEndpointMerge> endpointMerges,
        Map<String, EdgeEndpointSides> endpointSides) {
        Map<String, EnumMap<PortSide, Integer>> portCounts = new HashMap<>();
        Set<EdgeEndpointKey> countedMergePorts = new HashSet<>();
        for (LayoutEdge edge : edges) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            EdgeEndpointMerge endpointMerge =
                endpointMerges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
            EdgeEndpointSides sides = endpointSides.get(edge.id());
            countPort(
                portCounts,
                countedMergePorts,
                edge.source(),
                sides.sourceSide(),
                true,
                relationshipType(edge),
                endpointMerge.sourceEndpoint());
            countPort(
                portCounts,
                countedMergePorts,
                edge.target(),
                sides.targetSide(),
                false,
                relationshipType(edge),
                endpointMerge.targetEndpoint());
        }
        return portCounts;
    }

    private static Map<String, EnumMap<PortSide, Integer>> groupedPortCounts(
        List<LayoutEdge> edges,
        Map<String, LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById,
        Direction rootDirection,
        Map<String, EdgeEndpointMerge> endpointMerges) {
        Map<String, EnumMap<PortSide, Integer>> portCounts = new HashMap<>();
        Set<EdgeEndpointKey> countedMergePorts = new HashSet<>();
        for (LayoutEdge edge : edges) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            Direction direction =
                edgeDirection(
                    edge,
                    nodes,
                    ownerByNode,
                    groupDirectionById,
                    groupOrderById,
                    rootDirection);
            EdgeEndpointMerge endpointMerge =
                endpointMerges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
            countPort(
                portCounts,
                countedMergePorts,
                edge.source(),
                sourcePortSide(direction),
                true,
                relationshipType(edge),
                endpointMerge.sourceEndpoint());
            countPort(
                portCounts,
                countedMergePorts,
                edge.target(),
                targetPortSide(direction),
                false,
                relationshipType(edge),
                endpointMerge.targetEndpoint());
        }
        return portCounts;
    }

    private static Map<String, EdgePortIndexes> groupedEdgePortIndexes(
        LayoutRequest request,
        Map<String, LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById,
        Direction rootDirection) {
        Map<NodeSideKey, Integer> nextIndexByNodeSide = new HashMap<>();
        Map<String, EdgePortIndexes> portIndexesByEdge = new HashMap<>();
        for (LayoutEdge edge : list(request.edges())) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            Direction direction =
                edgeDirection(
                    edge,
                    nodes,
                    ownerByNode,
                    groupDirectionById,
                    groupOrderById,
                    rootDirection);
            PortSide sourceSide = sourcePortSide(direction);
            PortSide targetSide = targetPortSide(direction);
            int sourceIndex = nextGroupedPortIndex(
                nextIndexByNodeSide,
                edge.source(),
                sourceSide,
                reverseGroupedPortIndex(sourceSide));
            int targetIndex = nextGroupedPortIndex(
                nextIndexByNodeSide,
                edge.target(),
                targetSide,
                reverseGroupedPortIndex(targetSide));
            portIndexesByEdge.put(edge.id(), new EdgePortIndexes(sourceIndex, targetIndex));
        }
        return portIndexesByEdge;
    }

    private static boolean reverseGroupedPortIndex(PortSide side) {
        return side == PortSide.WEST;
    }

    private static int nextGroupedPortIndex(
        Map<NodeSideKey, Integer> nextIndexByNodeSide,
        String nodeId,
        PortSide side,
        boolean reverseIndex) {
        int index = nextIndexByNodeSide.merge(new NodeSideKey(nodeId, side), 1, Integer::sum) - 1;
        // ELK's west-side port indexes render in the opposite vertical
        // direction, so use descending indices there to preserve request order
        // as top-to-bottom visual graph intent.
        return reverseIndex ? -index : index;
    }

    // ELK accounts for the generated ports, but Dediren still increases the
    // minimum node side length when many ports would otherwise be packed onto
    // the same side. This is size intent, not route geometry.
    private static void setGeneratedDimensions(
        ElkNode elkNode,
        LayoutNode node,
        Map<PortSide, Integer> portCounts,
        LayoutPreferences preferences) {
        double width = positiveOrDefault(node.widthHint(), DEFAULT_WIDTH);
        double height = positiveOrDefault(node.heightHint(), DEFAULT_HEIGHT);
        if (portCounts != null) {
            double portSpacing = ElkLayeredOptions.portPortSpacing(preferences);
            width = Math.max(width, requiredPortSideLength(width, maxPortCount(
                portCounts,
                PortSide.NORTH,
                PortSide.SOUTH),
                portSpacing));
            height = Math.max(height, requiredPortSideLength(height, maxPortCount(
                portCounts,
                PortSide.WEST,
                PortSide.EAST),
                portSpacing));
        }
        elkNode.setDimensions(width, height);
    }

    private static double requiredPortSideLength(
        double currentLength,
        int portCount,
        double portSpacing) {
        if (portCount <= DEFAULT_SHORT_SIDE_PORT_CAPACITY) {
            return currentLength;
        }
        return currentLength
            + ((portCount - DEFAULT_SHORT_SIDE_PORT_CAPACITY) * portSpacing);
    }

    private static int maxPortCount(
        Map<PortSide, Integer> portCounts,
        PortSide first,
        PortSide second) {
        return Math.max(
            portCounts.getOrDefault(first, 0),
            portCounts.getOrDefault(second, 0));
    }

    private static void countPort(
        Map<String, EnumMap<PortSide, Integer>> portCounts,
        String nodeId,
        PortSide side) {
        EnumMap<PortSide, Integer> bySide =
            portCounts.computeIfAbsent(nodeId, ignored -> new EnumMap<>(PortSide.class));
        bySide.merge(side, 1, Integer::sum);
    }

    private static void countPort(
        Map<String, EnumMap<PortSide, Integer>> portCounts,
        Set<EdgeEndpointKey> countedMergePorts,
        String nodeId,
        PortSide side,
        boolean sourceEndpoint,
        String relationshipType,
        boolean mergeEndpoint) {
        if (!mergeEndpoint) {
            countPort(portCounts, nodeId, side);
            return;
        }
        if (countedMergePorts.add(new EdgeEndpointKey(nodeId, side, sourceEndpoint, relationshipType))) {
            countPort(portCounts, nodeId, side);
        }
    }

    private static int nextPortIndex(
        Map<String, EnumMap<PortSide, Integer>> portIndexes,
        String nodeId,
        PortSide side) {
        EnumMap<PortSide, Integer> bySide =
            portIndexes.computeIfAbsent(nodeId, ignored -> new EnumMap<>(PortSide.class));
        int index = bySide.getOrDefault(side, 0);
        bySide.put(side, index + 1);
        return index;
    }

    private static PortSide sourcePortSide(Direction direction) {
        return switch (direction) {
            case DOWN -> PortSide.SOUTH;
            case LEFT -> PortSide.WEST;
            case UP -> PortSide.NORTH;
            default -> PortSide.EAST;
        };
    }

    private static PortSide targetPortSide(Direction direction) {
        return switch (direction) {
            case DOWN -> PortSide.NORTH;
            case LEFT -> PortSide.EAST;
            case UP -> PortSide.SOUTH;
            default -> PortSide.WEST;
        };
    }

    private record EdgePortIndexes(int sourceIndex, int targetIndex) {}

    private record EdgeEndpointSides(PortSide sourceSide, PortSide targetSide) {}

    private record NodeSideKey(String nodeId, PortSide side) {}

    private record EdgeEndpointMerge(boolean sourceEndpoint, boolean targetEndpoint) {}

    private record EdgeEndpointKey(
        String nodeId,
        PortSide side,
        boolean sourceEndpoint,
        String relationshipType) {}

    private static Map<String, LayoutNode> requestNodesById(
        LayoutRequest request) {
        Map<String, LayoutNode> byId = new HashMap<>();
        for (LayoutNode node : list(request.nodes())) {
            byId.put(node.id(), node);
        }
        return byId;
    }

    private static Map<String, String> ownerByNode(LayoutRequest request) {
        Map<String, String> ownerByNode = new HashMap<>();
        for (LayoutGroup group : list(request.groups())) {
            for (String member : list(group.members())) {
                ownerByNode.putIfAbsent(member, group.id());
            }
        }
        return ownerByNode;
    }

    private static List<LaidOutGroup> groupedBounds(
        LayoutRequest request,
        Map<String, ElkNode> elkGroups,
        Map<String, ElkNode> elkNodes,
        List<Diagnostic> warnings) {
        List<LaidOutGroup> groups = new ArrayList<>();
        List<LayoutGroup> requestGroups = list(request.groups());
        for (int groupIndex = 0; groupIndex < requestGroups.size(); groupIndex++) {
            LayoutGroup group = requestGroups.get(groupIndex);
            ElkNode groupNode = elkGroups.get(group.id());
            List<String> memberIds = new ArrayList<>();
            List<String> requestedMembers = list(group.members());
            for (int memberIndex = 0; memberIndex < requestedMembers.size(); memberIndex++) {
                String memberId = requestedMembers.get(memberIndex);
                ElkNode memberNode = elkNodes.get(memberId);
                if (memberNode == null || memberNode.getParent() != groupNode) {
                    warnings.add(new Diagnostic(
                        "DEDIREN_ELK_MISSING_GROUP_MEMBER",
                        DiagnosticSeverity.WARNING,
                        "group " + group.id() + " references missing member " + memberId,
                        "$.groups[" + groupIndex + "].members[" + memberIndex + "]"));
                    continue;
                }
                memberIds.add(memberId);
            }
            if (groupNode == null || memberIds.isEmpty()) {
                warnings.add(new Diagnostic(
                    "DEDIREN_ELK_EMPTY_GROUP",
                    DiagnosticSeverity.WARNING,
                    "group " + group.id() + " has no laid out members",
                    "$.groups[" + groupIndex + "]"));
                continue;
            }

            groups.add(new LaidOutGroup(
                group.id(),
                semanticBackedSourceId(group.provenance(), group.id()),
                group.id(),
                group.provenance(),
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

    private static List<Point> points(ElkEdge edge) {
        List<Point> points = new ArrayList<>();
        double offsetX = edge.getContainingNode() == null ? 0.0 : absoluteX(edge.getContainingNode());
        double offsetY = edge.getContainingNode() == null ? 0.0 : absoluteY(edge.getContainingNode());
        for (ElkEdgeSection section : edge.getSections()) {
            if (points.isEmpty()) {
                points.add(new Point(
                    section.getStartX() + offsetX,
                    section.getStartY() + offsetY));
            }
            section.getBendPoints().forEach(bend ->
                points.add(new Point(bend.getX() + offsetX, bend.getY() + offsetY)));
            points.add(new Point(
                section.getEndX() + offsetX,
                section.getEndY() + offsetY));
        }
        return points;
    }

    private static List<LaidOutGroup> groups(
        LayoutRequest request,
        List<LaidOutNode> nodes,
        List<Diagnostic> warnings) {
        Map<String, LaidOutNode> byId = new HashMap<>();
        for (LaidOutNode node : nodes) {
            byId.put(node.id(), node);
        }

        List<LaidOutGroup> groups = new ArrayList<>();
        List<LayoutGroup> requestGroups = list(request.groups());
        for (int groupIndex = 0; groupIndex < requestGroups.size(); groupIndex++) {
            LayoutGroup group = requestGroups.get(groupIndex);
            List<LaidOutNode> members = new ArrayList<>();
            List<String> memberIds = new ArrayList<>();
            List<String> requestedMembers = list(group.members());
            for (int memberIndex = 0; memberIndex < requestedMembers.size(); memberIndex++) {
                String memberId = requestedMembers.get(memberIndex);
                LaidOutNode member = byId.get(memberId);
                if (member == null) {
                    warnings.add(new Diagnostic(
                        "DEDIREN_ELK_MISSING_GROUP_MEMBER",
                        DiagnosticSeverity.WARNING,
                        "group " + group.id() + " references missing member " + memberId,
                        "$.groups[" + groupIndex + "].members[" + memberIndex + "]"));
                    continue;
                }
                members.add(member);
                memberIds.add(memberId);
            }
            if (members.isEmpty()) {
                warnings.add(new Diagnostic(
                    "DEDIREN_ELK_EMPTY_GROUP",
                    DiagnosticSeverity.WARNING,
                    "group " + group.id() + " has no laid out members",
                    "$.groups[" + groupIndex + "]"));
                continue;
            }

            double minX = members.stream().mapToDouble(LaidOutNode::x).min().orElse(0.0);
            double minY = members.stream().mapToDouble(LaidOutNode::y).min().orElse(0.0);
            double maxX = members.stream().mapToDouble(node -> node.x() + node.width()).max().orElse(0.0);
            double maxY = members.stream().mapToDouble(node -> node.y() + node.height()).max().orElse(0.0);

            groups.add(new LaidOutGroup(
                group.id(),
                semanticBackedSourceId(group.provenance(), group.id()),
                group.id(),
                group.provenance(),
                minX - ElkLayeredOptions.DEFAULT_GROUP_PADDING,
                minY - ElkLayeredOptions.DEFAULT_GROUP_PADDING,
                (maxX - minX) + (ElkLayeredOptions.DEFAULT_GROUP_PADDING * 2.0),
                (maxY - minY) + (ElkLayeredOptions.DEFAULT_GROUP_PADDING * 2.0),
                memberIds,
                group.label()));
        }
        return groups;
    }

    private static String semanticBackedSourceId(
        GroupProvenance provenance,
        String fallback) {
        if (provenance == null || provenance.semanticBacked() == null) {
            return fallback;
        }
        String sourceId = provenance.semanticBacked().sourceId();
        return sourceId == null ? fallback : sourceId;
    }

    private static void validate(LayoutRequest request) {
        requireNonNull(request, "$");
        requireNonNull(request.layoutRequestSchemaVersion(), "$.layout_request_schema_version");
        if (!request.layoutRequestSchemaVersion().equals("layout-request.schema.v1")) {
            throw new IllegalArgumentException(
                "$.layout_request_schema_version must be layout-request.schema.v1");
        }
        requireNonNull(request.viewId(), "$.view_id");
        requireNonNull(request.nodes(), "$.nodes");
        requireNonNull(request.edges(), "$.edges");
        requireNonNull(request.groups(), "$.groups");
        requireNonNull(request.labels(), "$.labels");
        requireNonNull(request.constraints(), "$.constraints");
        validateLayoutPreferences(request.layoutPreferences(), "$.layout_preferences");

        for (int index = 0; index < request.nodes().size(); index++) {
            LayoutNode node = request.nodes().get(index);
            String path = "$.nodes[" + index + "]";
            requireNonNull(node, path);
            requireNonNull(node.id(), path + ".id");
            requireNonNull(node.label(), path + ".label");
            requireNonNull(node.sourceId(), path + ".source_id");
            requirePositive(node.widthHint(), path + ".width_hint");
            requirePositive(node.heightHint(), path + ".height_hint");
        }

        for (int index = 0; index < request.edges().size(); index++) {
            LayoutEdge edge = request.edges().get(index);
            String path = "$.edges[" + index + "]";
            requireNonNull(edge, path);
            requireNonNull(edge.id(), path + ".id");
            requireNonNull(edge.source(), path + ".source");
            requireNonNull(edge.target(), path + ".target");
            requireNonNull(edge.label(), path + ".label");
            requireNonNull(edge.sourceId(), path + ".source_id");
        }

        for (int index = 0; index < request.groups().size(); index++) {
            LayoutGroup group = request.groups().get(index);
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
            LayoutLabel label = request.labels().get(index);
            String path = "$.labels[" + index + "]";
            requireNonNull(label, path);
            requireNonNull(label.ownerId(), path + ".owner_id");
            requireNonNull(label.text(), path + ".text");
        }

        for (int index = 0; index < request.constraints().size(); index++) {
            LayoutConstraint constraint = request.constraints().get(index);
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

    private static void validateLayoutPreferences(
        LayoutPreferences preferences,
        String path) {
        if (preferences == null) {
            return;
        }
        validateRoutingPreferences(preferences.routing(), path + ".routing");
    }

    private static void validateRoutingPreferences(
        LayoutRoutingPreferences routing,
        String path) {
        if (routing == null) {
            return;
        }
    }

    private static void validateProvenance(GroupProvenance provenance, String path) {
        boolean visualOnly = Boolean.TRUE.equals(provenance.visualOnly());
        boolean semanticBacked = provenance.semanticBacked() != null;
        if (visualOnly == semanticBacked) {
            throw new IllegalArgumentException(
                "group provenance must contain exactly one of visual_only or semantic_backed at " + path);
        }
        if (semanticBacked && provenance.semanticBacked().sourceId() == null) {
            throw new IllegalArgumentException(
                "required string value is missing at " + path + ".semantic_backed.source_id");
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

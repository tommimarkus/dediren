package dev.dediren.elk;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.elk.alg.libavoid.options.LibavoidOptions;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.alg.layered.options.WrappingStrategy;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.math.ElkMargin;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.options.HierarchyHandling;
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
    private static final String LAYERED_ALGORITHM = "org.eclipse.elk.layered";
    private static final String LIBAVOID_ALGORITHM = "org.eclipse.elk.alg.libavoid";
    private static final double DEFAULT_WIDTH = 160.0;
    private static final double DEFAULT_HEIGHT = 80.0;
    private static final double CONNECTOR_SOURCE_MAX_WIDTH = 48.0;
    private static final double CONNECTOR_SOURCE_MAX_HEIGHT = 48.0;
    private static final double GROUP_PADDING = 24.0;
    private static final double NODE_SPACING = 60.0;
    private static final double EDGE_NODE_SPACING = 32.0;
    private static final double EDGE_EDGE_SPACING = 40.0;
    private static final double PORT_PORT_SPACING = 32.0;
    private static final int DEFAULT_SHORT_SIDE_PORT_CAPACITY = 3;
    private static final double PORT_SURROUNDING_SPACING = 16.0;
    private static final double ROUTE_DETOUR_RATIO = 1.5;
    private static final double ROUTE_DETOUR_EXCESS = 240.0;
    private static final double ROUTE_CHANNEL_PADDING = 32.0;
    private static final double ROUTE_FALLBACK_LANE_SPACING = 48.0;
    private static final int ROUTE_FALLBACK_LANE_COUNT = 4;
    private static final double ROUTE_CLOSE_PARALLEL_DISTANCE = 20.0;
    private static final double ROUTE_CLOSE_PARALLEL_MIN_OVERLAP = 40.0;
    private static final double ROUTE_ENDPOINT_CLEARANCE = 32.0;
    private static final double CONNECTOR_ENDPOINT_DOGLEG_SNAP = 24.0;
    private static final double LIBAVOID_SEGMENT_PENALTY = 50.0;
    private static final double LIBAVOID_IDEAL_NUDGING_DISTANCE = 16.0;
    private static final double LIBAVOID_SHAPE_BUFFER_DISTANCE = 16.0;
    private static final double GEOMETRY_EPSILON = 0.001;
    private static final int MERGEABLE_ENDPOINT_EDGE_COUNT = 3;
    private static final String SHARED_SOURCE_JUNCTION_HINT = "shared_source_junction";
    private static final String SHARED_TARGET_JUNCTION_HINT = "shared_target_junction";
    private static final EdgeEndpointMerge NO_ENDPOINT_MERGE =
        new EdgeEndpointMerge(false, false);

    JsonContracts.LayoutResult layout(JsonContracts.LayoutRequest request) {
        validate(request);
        if (!list(request.groups()).isEmpty()) {
            return layoutGrouped(request);
        }

        return layoutFlat(request);
    }

    private static JsonContracts.LayoutResult layoutFlat(JsonContracts.LayoutRequest request) {
        ElkNode root = ElkGraphUtil.createGraph();
        configureLayeredRoot(root, Direction.RIGHT);

        Map<String, JsonContracts.LayoutNode> requestNodes = requestNodesById(request);
        List<JsonContracts.LayoutEdge> requestEdges = list(request.edges());
        Map<String, EdgeEndpointMerge> endpointMerges =
            flatEdgeEndpointMerges(requestEdges, requestNodes);
        Map<String, EnumMap<PortSide, Integer>> portCounts =
            flatPortCounts(requestEdges, requestNodes, endpointMerges);
        Map<String, ElkNode> elkNodes = new HashMap<>();
        for (JsonContracts.LayoutNode node : list(request.nodes())) {
            ElkNode elkNode = ElkGraphUtil.createNode(root);
            elkNode.setIdentifier(node.id());
            setGeneratedDimensions(elkNode, node, portCounts.get(node.id()));
            ElkGraphUtil.createLabel(elkNode).setText(node.label());
            elkNodes.put(node.id(), elkNode);
        }

        Map<String, ElkEdge> elkEdges = new HashMap<>();
        List<JsonContracts.Diagnostic> warnings = new ArrayList<>();
        Map<String, EnumMap<PortSide, Integer>> portIndexes = new HashMap<>();
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
            EdgeEndpointMerge endpointMerge =
                endpointMerges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
            ElkEdge elkEdge = createRoutedEdge(
                source,
                target,
                edge,
                Direction.RIGHT,
                endpointMerge.sourceEndpoint()
                    ? 0
                    : nextPortIndex(portIndexes, edge.source(), sourcePortSide(Direction.RIGHT)),
                endpointMerge.targetEndpoint()
                    ? 0
                    : nextPortIndex(portIndexes, edge.target(), targetPortSide(Direction.RIGHT)),
                endpointMerge.sourceEndpoint(),
                endpointMerge.targetEndpoint());
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
        Map<String, List<JsonContracts.Point>> libavoidRoutes =
            routeWithLibavoid(
                requestEdges,
                nodes,
                flatEdgeDirections(requestEdges),
                Map.of(),
                endpointMerges,
                Map.of());
        for (JsonContracts.LayoutEdge edge : list(request.edges())) {
            ElkEdge elkEdge = elkEdges.get(edge.id());
            if (elkEdge != null) {
                List<JsonContracts.Point> route =
                    routeOrFallback(libavoidRoutes.get(edge.id()), points(elkEdge));
                edges.add(new JsonContracts.LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.source_id(),
                    edge.id(),
                    routingHints(edge.id(), endpointMerges),
                    route,
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
        configureLayeredRoot(root, Direction.RIGHT);
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN);
        root.setProperty(CoreOptions.ASPECT_RATIO, 2.2);
        root.setProperty(LayeredOptions.WRAPPING_STRATEGY, WrappingStrategy.MULTI_EDGE);
        root.setProperty(LayeredOptions.FEEDBACK_EDGES, true);

        Map<String, ElkNode> elkGroups = new HashMap<>();
        Map<String, Direction> groupDirectionById = new HashMap<>();
        Map<String, Integer> groupOrderById = new HashMap<>();
        List<JsonContracts.LayoutGroup> requestGroups = list(request.groups());
        for (int groupIndex = 0; groupIndex < requestGroups.size(); groupIndex++) {
            JsonContracts.LayoutGroup group = requestGroups.get(groupIndex);
            groupOrderById.put(group.id(), groupIndex);
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
            Direction groupDirection = internalDirection(members, internalEdges);
            configureLayeredRoot(elkGroup, groupDirection);
            elkGroup.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN);
            elkGroup.setProperty(CoreOptions.PADDING, new ElkPadding(GROUP_PADDING));
            elkGroups.put(group.id(), elkGroup);
            groupDirectionById.put(group.id(), groupDirection);
        }

        List<JsonContracts.LayoutEdge> requestEdges = list(request.edges());
        Map<String, EdgeEndpointMerge> endpointMerges = groupedEdgeEndpointMerges(
            requestEdges,
            requestNodes,
            ownerByNode,
            groupDirectionById,
            groupOrderById);
        Map<String, EnumMap<PortSide, Integer>> portCounts = groupedPortCounts(
            requestEdges,
            requestNodes,
            ownerByNode,
            groupDirectionById,
            groupOrderById,
            endpointMerges);
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
            setGeneratedDimensions(elkNode, node, portCounts.get(node.id()));
            ElkGraphUtil.createLabel(elkNode).setText(node.label());
            elkNodes.put(node.id(), elkNode);
        }

        Map<String, ElkEdge> elkEdges = new HashMap<>();
        Map<String, EdgePortIndexes> edgePortIndexes = groupedEdgePortIndexes(
            request,
            requestEdges,
            requestNodes,
            ownerByNode,
            groupDirectionById,
            groupOrderById,
            endpointMerges);
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
            Direction edgeDirection =
                edgeDirection(edge, requestNodes, ownerByNode, groupDirectionById, groupOrderById);
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
        Map<String, Direction> libavoidDirections = groupedEdgeDirections(
            requestEdges,
            requestNodes,
            ownerByNode,
            groupDirectionById,
            groupOrderById);
        Map<String, PortSide> libavoidSourcePortSides = geometryAwareSourcePortSides(
            requestEdges,
            nodes,
            libavoidDirections,
            endpointMerges);
        Map<String, EdgePortIndexes> libavoidEdgePortIndexes = geometryAwareEdgePortIndexes(
            requestEdges,
            nodes,
            libavoidDirections,
            libavoidSourcePortSides,
            endpointMerges,
            edgePortIndexes);
        List<JsonContracts.LaidOutGroup> groups =
            groupedBounds(request, elkGroups, elkNodes, warnings);
        Map<String, List<JsonContracts.Point>> libavoidRoutes =
            routeWithLibavoid(
                requestEdges,
                nodes,
                libavoidDirections,
                libavoidSourcePortSides,
                endpointMerges,
                libavoidEdgePortIndexes);
        for (JsonContracts.LayoutEdge edge : list(request.edges())) {
            ElkEdge elkEdge = elkEdges.get(edge.id());
            if (elkEdge != null) {
                List<JsonContracts.Point> route =
                    routeOrFallback(libavoidRoutes.get(edge.id()), points(elkEdge));
                edges.add(new JsonContracts.LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.source_id(),
                    edge.id(),
                    routingHints(edge.id(), endpointMerges),
                    route,
                    edge.label()));
            }
        }
        edges = straightenConnectorEndpointDoglegs(edges, nodes);
        edges = normalizeExcessiveRoutes(edges, nodes, groups);

        return new JsonContracts.LayoutResult(
            "layout-result.schema.v1",
            request.view_id(),
            nodes,
            edges,
            groups,
            warnings);
    }

    private static void configureLayeredRoot(ElkNode root, Direction direction) {
        root.setProperty(CoreOptions.ALGORITHM, LAYERED_ALGORITHM);
        root.setProperty(CoreOptions.DIRECTION, direction);
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
        root.setProperty(CoreOptions.SPACING_NODE_NODE, NODE_SPACING);
        root.setProperty(CoreOptions.SPACING_EDGE_NODE, EDGE_NODE_SPACING);
        root.setProperty(CoreOptions.SPACING_EDGE_EDGE, EDGE_EDGE_SPACING);
        root.setProperty(CoreOptions.SPACING_PORT_PORT, PORT_PORT_SPACING);
        root.setProperty(CoreOptions.SPACING_PORTS_SURROUNDING, new ElkMargin(PORT_SURROUNDING_SPACING));
        root.setProperty(LayeredOptions.SPACING_EDGE_EDGE, EDGE_EDGE_SPACING);
        root.setProperty(LayeredOptions.SPACING_EDGE_NODE, EDGE_NODE_SPACING);
        root.setProperty(LayeredOptions.SPACING_PORT_PORT, PORT_PORT_SPACING);
        root.setProperty(LayeredOptions.SPACING_PORTS_SURROUNDING, new ElkMargin(PORT_SURROUNDING_SPACING));
        root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, NODE_SPACING);
        root.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, EDGE_NODE_SPACING);
        root.setProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, EDGE_EDGE_SPACING);
        root.setProperty(LayeredOptions.MERGE_EDGES, true);
        root.setProperty(LayeredOptions.MERGE_HIERARCHY_EDGES, true);
    }

    static ElkNode configuredLibavoidRoot() {
        ElkNode root = ElkGraphUtil.createGraph();
        configureLibavoidRoot(root);
        return root;
    }

    private static void configureLibavoidRoot(ElkNode root) {
        root.setProperty(CoreOptions.ALGORITHM, LIBAVOID_ALGORITHM);
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT);
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
        root.setProperty(LibavoidOptions.SEGMENT_PENALTY, LIBAVOID_SEGMENT_PENALTY);
        root.setProperty(LibavoidOptions.IDEAL_NUDGING_DISTANCE, LIBAVOID_IDEAL_NUDGING_DISTANCE);
        root.setProperty(LibavoidOptions.SHAPE_BUFFER_DISTANCE, LIBAVOID_SHAPE_BUFFER_DISTANCE);
        root.setProperty(LibavoidOptions.NUDGE_ORTHOGONAL_SEGMENTS_CONNECTED_TO_SHAPES, true);
        root.setProperty(LibavoidOptions.PENALISE_ORTHOGONAL_SHARED_PATHS_AT_CONN_ENDS, false);
    }

    static Map<String, List<JsonContracts.Point>> routeWithLibavoid(
        List<JsonContracts.LayoutEdge> edges,
        List<JsonContracts.LaidOutNode> nodes) {
        return routeWithLibavoid(
            edges,
            nodes,
            flatEdgeDirections(edges),
            Map.of(),
            emptyEndpointMerges(edges),
            Map.of());
    }

    private static Map<String, List<JsonContracts.Point>> routeWithLibavoid(
        List<JsonContracts.LayoutEdge> edges,
        List<JsonContracts.LaidOutNode> nodes,
        Map<String, Direction> edgeDirections,
        Map<String, PortSide> sourcePortSides,
        Map<String, EdgeEndpointMerge> endpointMerges,
        Map<String, EdgePortIndexes> edgePortIndexes) {
        ElkNode root = configuredLibavoidRoot();

        Map<String, ElkNode> elkNodes = new HashMap<>();
        for (JsonContracts.LaidOutNode node : nodes) {
            ElkNode elkNode = ElkGraphUtil.createNode(root);
            elkNode.setIdentifier(node.id());
            elkNode.setDimensions(node.width(), node.height());
            elkNode.setLocation(node.x(), node.y());
            ElkGraphUtil.createLabel(elkNode).setText(node.label());
            elkNodes.put(node.id(), elkNode);
        }

        Map<String, ElkEdge> elkEdges = new HashMap<>();
        Map<String, EnumMap<PortSide, Integer>> portIndexes = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            ElkNode source = elkNodes.get(edge.source());
            ElkNode target = elkNodes.get(edge.target());
            if (source == null || target == null) {
                continue;
            }
            Direction direction = edgeDirections.getOrDefault(edge.id(), Direction.RIGHT);
            PortSide sourceSide = sourcePortSides.getOrDefault(
                edge.id(),
                sourcePortSide(direction));
            PortSide targetSide = targetPortSide(direction);
            EdgeEndpointMerge endpointMerge =
                endpointMerges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
            EdgePortIndexes portIndexesForEdge = edgePortIndexes.get(edge.id());
            ElkEdge elkEdge = createRoutedEdge(
                source,
                target,
                edge,
                sourceSide,
                targetSide,
                endpointMerge.sourceEndpoint()
                    ? 0
                    : portIndexesForEdge == null
                        ? nextPortIndex(portIndexes, edge.source(), sourceSide)
                        : portIndexesForEdge.sourceIndex(),
                endpointMerge.targetEndpoint()
                    ? 0
                    : portIndexesForEdge == null
                        ? nextPortIndex(portIndexes, edge.target(), targetSide)
                        : portIndexesForEdge.targetIndex(),
                endpointMerge.sourceEndpoint(),
                endpointMerge.targetEndpoint());
            elkEdges.put(edge.id(), elkEdge);
        }

        new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());

        Map<String, List<JsonContracts.Point>> routes = new HashMap<>();
        for (Map.Entry<String, ElkEdge> entry : elkEdges.entrySet()) {
            routes.put(entry.getKey(), points(entry.getValue()));
        }
        return routes;
    }

    private static Map<String, Direction> flatEdgeDirections(
        List<JsonContracts.LayoutEdge> edges) {
        Map<String, Direction> directions = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            directions.put(edge.id(), Direction.RIGHT);
        }
        return directions;
    }

    private static Map<String, Direction> groupedEdgeDirections(
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById) {
        Map<String, Direction> directions = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            directions.put(
                edge.id(),
                edgeDirection(edge, nodes, ownerByNode, groupDirectionById, groupOrderById));
        }
        return directions;
    }

    private static Map<String, EdgeEndpointMerge> emptyEndpointMerges(
        List<JsonContracts.LayoutEdge> edges) {
        Map<String, EdgeEndpointMerge> endpointMerges = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            endpointMerges.put(edge.id(), NO_ENDPOINT_MERGE);
        }
        return endpointMerges;
    }

    private static List<JsonContracts.Point> routeOrFallback(
        List<JsonContracts.Point> libavoidRoute,
        List<JsonContracts.Point> layeredRoute) {
        return libavoidRoute == null || libavoidRoute.isEmpty()
            ? layeredRoute
            : libavoidRoute;
    }

    private static Direction internalDirection(
        List<JsonContracts.LayoutNode> nodes,
        List<JsonContracts.LayoutEdge> edges) {
        if (nodes.size() < 3) {
            return Direction.RIGHT;
        }
        return Direction.DOWN;
    }

    private static Direction edgeDirection(
        JsonContracts.LayoutEdge edge,
        Map<String, JsonContracts.LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById) {
        String sourceOwner = ownerByNode.get(edge.source());
        String targetOwner = ownerByNode.get(edge.target());
        if (sourceOwner != null && sourceOwner.equals(targetOwner)) {
            if (isConnectorSizedSource(nodes.get(edge.source()))) {
                return Direction.RIGHT;
            }
            return groupDirectionById.getOrDefault(sourceOwner, Direction.RIGHT);
        }
        if (sourceOwner != null && targetOwner != null) {
            int sourceOrder = groupOrderById.getOrDefault(sourceOwner, 0);
            int targetOrder = groupOrderById.getOrDefault(targetOwner, 0);
            if (sourceOrder > targetOrder) {
                return Direction.LEFT;
            }
        }
        return Direction.RIGHT;
    }

    private static boolean isConnectorSizedSource(JsonContracts.LayoutNode node) {
        if (node == null) {
            return false;
        }
        double width = node.width_hint() == null ? DEFAULT_WIDTH : node.width_hint();
        double height = node.height_hint() == null ? DEFAULT_HEIGHT : node.height_hint();
        return width <= CONNECTOR_SOURCE_MAX_WIDTH && height <= CONNECTOR_SOURCE_MAX_HEIGHT;
    }

    private static ElkEdge createRoutedEdge(
        ElkNode source,
        ElkNode target,
        JsonContracts.LayoutEdge edge,
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
        JsonContracts.LayoutEdge edge,
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
        port.setDimensions(0.0, 0.0);
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

    private static String relationshipType(JsonContracts.LayoutEdge edge) {
        String relationshipType = edge.relationship_type();
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

    private static Map<String, EdgeEndpointMerge> flatEdgeEndpointMerges(
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LayoutNode> nodes) {
        Map<EdgeEndpointKey, Integer> endpointCounts = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            String relationshipType = relationshipType(edge);
            if (!nodes.containsKey(edge.source())
                || !nodes.containsKey(edge.target())
                || edge.source().equals(edge.target())
                || relationshipType == null) {
                continue;
            }
            endpointCounts.merge(
                new EdgeEndpointKey(edge.source(), sourcePortSide(Direction.RIGHT), true, relationshipType),
                1,
                Integer::sum);
            endpointCounts.merge(
                new EdgeEndpointKey(edge.target(), targetPortSide(Direction.RIGHT), false, relationshipType),
                1,
                Integer::sum);
        }

        Map<String, EdgeEndpointMerge> endpointMerges = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            String relationshipType = relationshipType(edge);
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            endpointMerges.put(edge.id(), new EdgeEndpointMerge(
                relationshipType != null
                    && endpointCounts.getOrDefault(
                        new EdgeEndpointKey(edge.source(), sourcePortSide(Direction.RIGHT), true, relationshipType),
                        0) >= MERGEABLE_ENDPOINT_EDGE_COUNT,
                relationshipType != null
                    && endpointCounts.getOrDefault(
                        new EdgeEndpointKey(edge.target(), targetPortSide(Direction.RIGHT), false, relationshipType),
                        0) >= MERGEABLE_ENDPOINT_EDGE_COUNT));
        }
        return endpointMerges;
    }

    private static Map<String, EdgeEndpointMerge> groupedEdgeEndpointMerges(
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById) {
        Map<EdgeEndpointKey, Integer> endpointCounts = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            String relationshipType = relationshipType(edge);
            if (!nodes.containsKey(edge.source())
                || !nodes.containsKey(edge.target())
                || edge.source().equals(edge.target())
                || relationshipType == null) {
                continue;
            }
            Direction direction =
                edgeDirection(edge, nodes, ownerByNode, groupDirectionById, groupOrderById);
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
        for (JsonContracts.LayoutEdge edge : edges) {
            String relationshipType = relationshipType(edge);
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            Direction direction =
                edgeDirection(edge, nodes, ownerByNode, groupDirectionById, groupOrderById);
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
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LayoutNode> nodes,
        Map<String, EdgeEndpointMerge> endpointMerges) {
        Map<String, EnumMap<PortSide, Integer>> portCounts = new HashMap<>();
        Set<EdgeEndpointKey> countedMergePorts = new HashSet<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            EdgeEndpointMerge endpointMerge =
                endpointMerges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
            countPort(
                portCounts,
                countedMergePorts,
                edge.source(),
                sourcePortSide(Direction.RIGHT),
                true,
                relationshipType(edge),
                endpointMerge.sourceEndpoint());
            countPort(
                portCounts,
                countedMergePorts,
                edge.target(),
                targetPortSide(Direction.RIGHT),
                false,
                relationshipType(edge),
                endpointMerge.targetEndpoint());
        }
        return portCounts;
    }

    private static Map<String, EnumMap<PortSide, Integer>> groupedPortCounts(
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById,
        Map<String, EdgeEndpointMerge> endpointMerges) {
        Map<String, EnumMap<PortSide, Integer>> portCounts = new HashMap<>();
        Set<EdgeEndpointKey> countedMergePorts = new HashSet<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            Direction direction =
                edgeDirection(edge, nodes, ownerByNode, groupDirectionById, groupOrderById);
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
        JsonContracts.LayoutRequest request,
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById,
        Map<String, EdgeEndpointMerge> endpointMerges) {
        Map<String, Integer> nodeOrderById = groupedNodeOrderById(request, groupOrderById);
        Map<PortGroupKey, List<PortCandidate>> candidatesByPortGroup = new HashMap<>();
        for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
            JsonContracts.LayoutEdge edge = edges.get(edgeIndex);
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            Direction direction =
                edgeDirection(edge, nodes, ownerByNode, groupDirectionById, groupOrderById);
            PortSide sourceSide = sourcePortSide(direction);
            PortSide targetSide = targetPortSide(direction);
            EdgeEndpointMerge endpointMerge =
                endpointMerges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
            if (!endpointMerge.sourceEndpoint()) {
                candidatesByPortGroup
                    .computeIfAbsent(new PortGroupKey(edge.source(), sourceSide), ignored -> new ArrayList<>())
                    .add(new PortCandidate(
                        edge.id(),
                        true,
                        nodeOrderById.getOrDefault(edge.target(), Integer.MAX_VALUE),
                        edgeIndex));
            }
            if (!endpointMerge.targetEndpoint()) {
                candidatesByPortGroup
                    .computeIfAbsent(new PortGroupKey(edge.target(), targetSide), ignored -> new ArrayList<>())
                    .add(new PortCandidate(
                        edge.id(),
                        false,
                        nodeOrderById.getOrDefault(edge.source(), Integer.MAX_VALUE),
                        edgeIndex));
            }
        }

        Map<String, Integer> sourcePortIndexByEdge = new HashMap<>();
        Map<String, Integer> targetPortIndexByEdge = new HashMap<>();
        for (List<PortCandidate> candidates : candidatesByPortGroup.values()) {
            candidates.sort((left, right) -> {
                // Source-side order follows the diverging route channel order; target-side order
                // still follows the remote endpoint order to keep arrivals stable.
                int remoteOrder = left.sourceEndpoint() && right.sourceEndpoint()
                    ? Integer.compare(right.remoteNodeOrder(), left.remoteNodeOrder())
                    : Integer.compare(left.remoteNodeOrder(), right.remoteNodeOrder());
                if (remoteOrder != 0) {
                    return remoteOrder;
                }
                int requestOrder = Integer.compare(left.requestEdgeIndex(), right.requestEdgeIndex());
                if (requestOrder != 0) {
                    return requestOrder;
                }
                return left.edgeId().compareTo(right.edgeId());
            });
            for (int portIndex = 0; portIndex < candidates.size(); portIndex++) {
                PortCandidate candidate = candidates.get(portIndex);
                if (candidate.sourceEndpoint()) {
                    sourcePortIndexByEdge.put(candidate.edgeId(), portIndex);
                } else {
                    targetPortIndexByEdge.put(candidate.edgeId(), portIndex);
                }
            }
        }

        Map<String, EdgePortIndexes> portIndexesByEdge = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            portIndexesByEdge.put(edge.id(), new EdgePortIndexes(
                sourcePortIndexByEdge.getOrDefault(edge.id(), 0),
                targetPortIndexByEdge.getOrDefault(edge.id(), 0)));
        }
        return portIndexesByEdge;
    }

    private static Map<String, PortSide> geometryAwareSourcePortSides(
        List<JsonContracts.LayoutEdge> edges,
        List<JsonContracts.LaidOutNode> laidOutNodes,
        Map<String, Direction> edgeDirections,
        Map<String, EdgeEndpointMerge> endpointMerges) {
        Map<String, JsonContracts.LaidOutNode> nodes = laidOutNodesById(laidOutNodes);
        Map<String, Set<PortSide>> incomingTargetSides = incomingTargetPortSidesByNode(
            edges,
            nodes,
            edgeDirections);
        Map<String, PortSide> sourcePortSides = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            Direction direction = edgeDirections.getOrDefault(edge.id(), Direction.RIGHT);
            PortSide defaultSide = sourcePortSide(direction);
            PortSide sourceSide = defaultSide;
            EdgeEndpointMerge endpointMerge =
                endpointMerges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
            JsonContracts.LaidOutNode source = nodes.get(edge.source());
            JsonContracts.LaidOutNode target = nodes.get(edge.target());
            if (source != null
                && target != null
                && !endpointMerge.sourceEndpoint()) {
                sourceSide = geometryAwareSourcePortSide(
                    source,
                    target,
                    defaultSide,
                    incomingTargetSides.getOrDefault(edge.source(), Set.of()),
                    isConnectorSizedNode(source));
            }
            sourcePortSides.put(edge.id(), sourceSide);
        }
        return sourcePortSides;
    }

    private static Map<String, Set<PortSide>> incomingTargetPortSidesByNode(
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LaidOutNode> nodes,
        Map<String, Direction> edgeDirections) {
        Map<String, Set<PortSide>> incomingTargetSides = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                continue;
            }
            Direction direction = edgeDirections.getOrDefault(edge.id(), Direction.RIGHT);
            incomingTargetSides
                .computeIfAbsent(edge.target(), ignored -> new HashSet<>())
                .add(targetPortSide(direction));
        }
        return incomingTargetSides;
    }

    private static Map<String, EdgePortIndexes> geometryAwareEdgePortIndexes(
        List<JsonContracts.LayoutEdge> edges,
        List<JsonContracts.LaidOutNode> laidOutNodes,
        Map<String, Direction> edgeDirections,
        Map<String, PortSide> sourcePortSides,
        Map<String, EdgeEndpointMerge> endpointMerges,
        Map<String, EdgePortIndexes> fallbackPortIndexes) {
        Map<String, JsonContracts.LaidOutNode> nodes = laidOutNodesById(laidOutNodes);
        Map<PortGroupKey, List<GeometryPortCandidate>> candidatesByPortGroup = new HashMap<>();
        for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
            JsonContracts.LayoutEdge edge = edges.get(edgeIndex);
            EdgeEndpointMerge endpointMerge =
                endpointMerges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
            JsonContracts.LaidOutNode source = nodes.get(edge.source());
            JsonContracts.LaidOutNode target = nodes.get(edge.target());
            if (source == null || target == null) {
                continue;
            }
            Direction direction = edgeDirections.getOrDefault(edge.id(), Direction.RIGHT);
            if (!endpointMerge.sourceEndpoint()) {
                PortSide sourceSide = sourcePortSides.getOrDefault(
                    edge.id(),
                    sourcePortSide(direction));
                PortOrder order = portOrder(source, target, sourceSide);
                candidatesByPortGroup
                    .computeIfAbsent(
                        new PortGroupKey(edge.source(), sourceSide),
                        ignored -> new ArrayList<>())
                    .add(new GeometryPortCandidate(
                        edge.id(),
                        true,
                        order.band(),
                        order.order(),
                        order.secondary(),
                        edgeIndex));
            }
            if (!endpointMerge.targetEndpoint()) {
                PortSide targetSide = targetPortSide(direction);
                PortOrder order = portOrder(target, source, targetSide);
                candidatesByPortGroup
                    .computeIfAbsent(
                        new PortGroupKey(edge.target(), targetSide),
                        ignored -> new ArrayList<>())
                    .add(new GeometryPortCandidate(
                        edge.id(),
                        false,
                        order.band(),
                        order.order(),
                        order.secondary(),
                        edgeIndex));
            }
        }

        Map<String, Integer> sourcePortIndexByEdge = new HashMap<>();
        Map<String, Integer> targetPortIndexByEdge = new HashMap<>();
        for (List<GeometryPortCandidate> candidates : candidatesByPortGroup.values()) {
            candidates.sort((left, right) -> {
                int bandOrder = Integer.compare(left.band(), right.band());
                if (bandOrder != 0) {
                    return bandOrder;
                }
                int geometryOrder = Double.compare(left.order(), right.order());
                if (geometryOrder != 0) {
                    return geometryOrder;
                }
                int secondaryOrder = Double.compare(left.secondary(), right.secondary());
                if (secondaryOrder != 0) {
                    return secondaryOrder;
                }
                int requestOrder = Integer.compare(left.requestEdgeIndex(), right.requestEdgeIndex());
                if (requestOrder != 0) {
                    return requestOrder;
                }
                return left.edgeId().compareTo(right.edgeId());
            });
            for (int portIndex = 0; portIndex < candidates.size(); portIndex++) {
                GeometryPortCandidate candidate = candidates.get(portIndex);
                if (candidate.sourceEndpoint()) {
                    sourcePortIndexByEdge.put(candidate.edgeId(), portIndex);
                } else {
                    targetPortIndexByEdge.put(candidate.edgeId(), portIndex);
                }
            }
        }

        Map<String, EdgePortIndexes> portIndexesByEdge = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            EdgePortIndexes fallback = fallbackPortIndexes.getOrDefault(
                edge.id(),
                new EdgePortIndexes(0, 0));
            portIndexesByEdge.put(edge.id(), new EdgePortIndexes(
                sourcePortIndexByEdge.getOrDefault(edge.id(), fallback.sourceIndex()),
                targetPortIndexByEdge.getOrDefault(edge.id(), fallback.targetIndex())));
        }
        return portIndexesByEdge;
    }

    private record PortOrder(int band, double order, double secondary) {}

    private static PortSide geometryAwareSourcePortSide(
        JsonContracts.LaidOutNode source,
        JsonContracts.LaidOutNode target,
        PortSide defaultSide,
        Set<PortSide> incomingTargetSides,
        boolean connectorSized) {
        double sourceX = centerX(source);
        double sourceY = centerY(source);
        double targetX = centerX(target);
        double targetY = centerY(target);
        double verticalDelta = targetY - sourceY;
        double horizontalDelta = targetX - sourceX;
        if (incomingTargetSides.contains(defaultSide)
            && verticalDelta < -ROUTE_ENDPOINT_CLEARANCE
            && !incomingTargetSides.contains(PortSide.NORTH)) {
            return PortSide.NORTH;
        }
        if (!connectorSized) {
            return defaultSide;
        }
        if (Math.abs(verticalDelta) <= GEOMETRY_EPSILON
            || Math.abs(horizontalDelta) > Math.abs(verticalDelta)) {
            return defaultSide;
        }

        PortSide verticalSide = verticalDelta > 0.0 ? PortSide.SOUTH : PortSide.NORTH;
        if (!incomingTargetSides.contains(verticalSide)) {
            return verticalSide;
        }
        if (targetX < sourceX - GEOMETRY_EPSILON) {
            return PortSide.WEST;
        }
        if (targetX > sourceX + GEOMETRY_EPSILON) {
            return PortSide.EAST;
        }
        return defaultSide;
    }

    private static PortOrder portOrder(
        JsonContracts.LaidOutNode node,
        JsonContracts.LaidOutNode remote,
        PortSide side) {
        double nodeY = centerY(node);
        double remoteY = centerY(remote);
        if (side == PortSide.NORTH) {
            return new PortOrder(0, centerX(remote), -remoteY);
        }
        if (side == PortSide.SOUTH) {
            return new PortOrder(0, -centerX(remote), remoteY);
        }
        if (side == PortSide.WEST) {
            if (remoteY > nodeY + GEOMETRY_EPSILON) {
                return new PortOrder(0, -remoteY, centerX(remote));
            }
            if (remoteY < nodeY - GEOMETRY_EPSILON) {
                return new PortOrder(1, -remoteY, centerX(remote));
            }
            return new PortOrder(0, -remoteY, centerX(remote));
        }
        if (side != PortSide.EAST) {
            return new PortOrder(0, centerX(remote), remoteY);
        }
        if (remoteY < nodeY - GEOMETRY_EPSILON) {
            return new PortOrder(0, remoteY, centerX(remote));
        }
        if (remoteY > nodeY + GEOMETRY_EPSILON) {
            return new PortOrder(1, -remoteY, centerX(remote));
        }
        return new PortOrder(0, remoteY, centerX(remote));
    }

    private static Map<String, JsonContracts.LaidOutNode> laidOutNodesById(
        List<JsonContracts.LaidOutNode> nodes) {
        Map<String, JsonContracts.LaidOutNode> byId = new HashMap<>();
        for (JsonContracts.LaidOutNode node : nodes) {
            byId.put(node.id(), node);
        }
        return byId;
    }

    private static boolean isConnectorSizedNode(JsonContracts.LaidOutNode node) {
        return node.width() <= CONNECTOR_SOURCE_MAX_WIDTH
            && node.height() <= CONNECTOR_SOURCE_MAX_HEIGHT;
    }

    private static double centerX(JsonContracts.LaidOutNode node) {
        return node.x() + node.width() / 2.0;
    }

    private static double centerY(JsonContracts.LaidOutNode node) {
        return node.y() + node.height() / 2.0;
    }

    private static Map<String, Integer> groupedNodeOrderById(
        JsonContracts.LayoutRequest request,
        Map<String, Integer> groupOrderById) {
        Map<String, Integer> nodeOrderById = new HashMap<>();
        int stride = Math.max(list(request.nodes()).size() + 1, 1);
        List<JsonContracts.LayoutGroup> groups = list(request.groups());
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            JsonContracts.LayoutGroup group = groups.get(groupIndex);
            int groupOrder = groupOrderById.getOrDefault(group.id(), groupIndex);
            List<String> members = list(group.members());
            for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
                nodeOrderById.putIfAbsent(
                    members.get(memberIndex),
                    groupOrder * stride + memberIndex);
            }
        }

        int ungroupedBase = groups.size() * stride;
        List<JsonContracts.LayoutNode> nodes = list(request.nodes());
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            JsonContracts.LayoutNode node = nodes.get(nodeIndex);
            nodeOrderById.putIfAbsent(node.id(), ungroupedBase + nodeIndex);
        }
        return nodeOrderById;
    }

    private static void setGeneratedDimensions(
        ElkNode elkNode,
        JsonContracts.LayoutNode node,
        Map<PortSide, Integer> portCounts) {
        double width = positiveOrDefault(node.width_hint(), DEFAULT_WIDTH);
        double height = positiveOrDefault(node.height_hint(), DEFAULT_HEIGHT);
        if (portCounts != null) {
            width = Math.max(width, requiredPortSideLength(width, maxPortCount(
                portCounts,
                PortSide.NORTH,
                PortSide.SOUTH)));
            height = Math.max(height, requiredPortSideLength(height, maxPortCount(
                portCounts,
                PortSide.WEST,
                PortSide.EAST)));
        }
        elkNode.setDimensions(width, height);
    }

    private static double requiredPortSideLength(double currentLength, int portCount) {
        if (portCount <= DEFAULT_SHORT_SIDE_PORT_CAPACITY) {
            return currentLength;
        }
        return currentLength
            + ((portCount - DEFAULT_SHORT_SIDE_PORT_CAPACITY) * PORT_PORT_SPACING);
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
            default -> PortSide.EAST;
        };
    }

    private static PortSide targetPortSide(Direction direction) {
        return switch (direction) {
            case DOWN -> PortSide.NORTH;
            case LEFT -> PortSide.EAST;
            default -> PortSide.WEST;
        };
    }

    private record EdgePortIndexes(int sourceIndex, int targetIndex) {}

    private record EdgeEndpointMerge(boolean sourceEndpoint, boolean targetEndpoint) {}

    private record EdgeEndpointKey(
        String nodeId,
        PortSide side,
        boolean sourceEndpoint,
        String relationshipType) {}

    private record PortGroupKey(String nodeId, PortSide side) {}

    private record PortCandidate(
        String edgeId,
        boolean sourceEndpoint,
        int remoteNodeOrder,
        int requestEdgeIndex) {}

    private record GeometryPortCandidate(
        String edgeId,
        boolean sourceEndpoint,
        int band,
        double order,
        double secondary,
        int requestEdgeIndex) {}

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

    private static List<JsonContracts.LaidOutEdge> normalizeExcessiveRoutes(
        List<JsonContracts.LaidOutEdge> edges,
        List<JsonContracts.LaidOutNode> nodes,
        List<JsonContracts.LaidOutGroup> groups) {
        List<JsonContracts.LaidOutEdge> current = edges;
        for (int pass = 0; pass < 3; pass++) {
            List<JsonContracts.LaidOutEdge> normalized =
                normalizeExcessiveRoutesOnce(current, nodes, groups);
            if (normalized.equals(current)) {
                return normalized;
            }
            current = normalized;
        }
        return current;
    }

    private static List<JsonContracts.LaidOutEdge> straightenConnectorEndpointDoglegs(
        List<JsonContracts.LaidOutEdge> edges,
        List<JsonContracts.LaidOutNode> nodes) {
        List<JsonContracts.LaidOutEdge> straightened = new ArrayList<>();
        for (JsonContracts.LaidOutEdge edge : edges) {
            List<JsonContracts.Point> points = straightenConnectorEndpointDogleg(edge, nodes);
            if (points.equals(edge.points())) {
                straightened.add(edge);
            } else {
                straightened.add(new JsonContracts.LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.source_id(),
                    edge.projection_id(),
                    edge.routing_hints(),
                    points,
                    edge.label()));
            }
        }
        return straightened;
    }

    private static List<JsonContracts.Point> straightenConnectorEndpointDogleg(
        JsonContracts.LaidOutEdge edge,
        List<JsonContracts.LaidOutNode> nodes) {
        JsonContracts.LaidOutNode source = nodeById(nodes, edge.source());
        JsonContracts.LaidOutNode target = nodeById(nodes, edge.target());
        if (source == null
            || target == null
            || !isConnectorSizedNode(source)
            || edge.points().size() < 4) {
            return edge.points();
        }
        return snapSmallTerminalDogleg(edge.points(), target);
    }

    private static List<JsonContracts.Point> snapSmallTerminalDogleg(
        List<JsonContracts.Point> points,
        JsonContracts.LaidOutNode target) {
        int endIndex = points.size() - 1;
        JsonContracts.Point beforeEntry = points.get(endIndex - 3);
        JsonContracts.Point doglegStart = points.get(endIndex - 2);
        JsonContracts.Point doglegEnd = points.get(endIndex - 1);
        JsonContracts.Point end = points.get(endIndex);

        if (horizontal(beforeEntry, doglegStart)
            && vertical(doglegStart, doglegEnd)
            && horizontal(doglegEnd, end)
            && Math.abs(doglegStart.y() - doglegEnd.y()) <= CONNECTOR_ENDPOINT_DOGLEG_SNAP
            && pointOnVerticalBoundary(end, target)
            && withinNodeY(target, doglegStart.y())) {
            return replaceTerminalPoint(points, new JsonContracts.Point(end.x(), doglegStart.y()));
        }

        if (vertical(beforeEntry, doglegStart)
            && horizontal(doglegStart, doglegEnd)
            && vertical(doglegEnd, end)
            && Math.abs(doglegStart.x() - doglegEnd.x()) <= CONNECTOR_ENDPOINT_DOGLEG_SNAP
            && pointOnHorizontalBoundary(end, target)
            && withinNodeX(target, doglegStart.x())) {
            return replaceTerminalPoint(points, new JsonContracts.Point(doglegStart.x(), end.y()));
        }

        return points;
    }

    private static List<JsonContracts.Point> replaceTerminalPoint(
        List<JsonContracts.Point> points,
        JsonContracts.Point end) {
        List<JsonContracts.Point> adjusted = new ArrayList<>(points.subList(0, points.size() - 2));
        adjusted.add(end);
        return compactPoints(adjusted);
    }

    private static List<JsonContracts.LaidOutEdge> normalizeExcessiveRoutesOnce(
        List<JsonContracts.LaidOutEdge> edges,
        List<JsonContracts.LaidOutNode> nodes,
        List<JsonContracts.LaidOutGroup> groups) {
        List<JsonContracts.LaidOutEdge> normalized = new ArrayList<>();
        for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
            JsonContracts.LaidOutEdge edge = edges.get(edgeIndex);
            List<JsonContracts.LaidOutEdge> comparisonEdges = new ArrayList<>(normalized);
            comparisonEdges.addAll(edges.subList(edgeIndex + 1, edges.size()));
            int originalCloseParallelCount =
                closeParallelRouteCount(edge, edge.points(), comparisonEdges);
            boolean originalIntersectsUnrelatedGroup =
                routeIntersectsUnrelatedGroup(edge, edge.points(), groups);
            if (!hasExcessiveDetour(edge.points())
                && cornerCount(edge.points()) <= 2
                && originalCloseParallelCount == 0
                && !originalIntersectsUnrelatedGroup) {
                normalized.add(edge);
                continue;
            }
            List<JsonContracts.Point> replacement =
                shortestCleanOrthogonalRoute(edge, nodes, groups, comparisonEdges);
            if (replacement.isEmpty()) {
                normalized.add(edge);
            } else {
                int replacementCloseParallelCount =
                    closeParallelRouteCount(edge, replacement, comparisonEdges);
                int originalCornerCount = cornerCount(edge.points());
                int replacementCornerCount = cornerCount(replacement);
                boolean replacementDoesNotAddParallelRoutes =
                    replacementCloseParallelCount <= originalCloseParallelCount;
                boolean replacementImprovesReadability =
                    replacementCloseParallelCount < originalCloseParallelCount
                        || replacementCornerCount < originalCornerCount;
                boolean replacementIsShorter =
                    routeLength(replacement) < routeLength(edge.points());
                if (originalIntersectsUnrelatedGroup && replacementDoesNotAddParallelRoutes) {
                    normalized.add(new JsonContracts.LaidOutEdge(
                        edge.id(),
                        edge.source(),
                        edge.target(),
                        edge.source_id(),
                        edge.projection_id(),
                        edge.routing_hints(),
                        replacement,
                        edge.label()));
                    continue;
                }
                if (!replacementDoesNotAddParallelRoutes
                    || (!replacementImprovesReadability && !replacementIsShorter)) {
                    normalized.add(edge);
                    continue;
                }
                normalized.add(new JsonContracts.LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.source_id(),
                    edge.projection_id(),
                    edge.routing_hints(),
                    replacement,
                    edge.label()));
            }
        }
        return normalized;
    }

    private static boolean hasExcessiveDetour(List<JsonContracts.Point> points) {
        if (points.size() < 2) {
            return false;
        }
        double directLength = directLength(points);
        double routeLength = routeLength(points);
        return directLength > 0.0
            && routeLength > directLength * ROUTE_DETOUR_RATIO
            && routeLength - directLength > ROUTE_DETOUR_EXCESS;
    }

    private static List<JsonContracts.Point> shortestCleanOrthogonalRoute(
        JsonContracts.LaidOutEdge edge,
        List<JsonContracts.LaidOutNode> nodes,
        List<JsonContracts.LaidOutGroup> groups,
        List<JsonContracts.LaidOutEdge> existingEdges) {
        JsonContracts.Point start = edge.points().get(0);
        JsonContracts.Point end = edge.points().get(edge.points().size() - 1);
        JsonContracts.Point clearStart = clearancePoint(start, nodeById(nodes, edge.source()));
        JsonContracts.Point clearEnd = clearancePoint(end, nodeById(nodes, edge.target()));
        List<List<JsonContracts.Point>> candidates = new ArrayList<>();
        candidates.add(routeViaX(start, clearStart, clearEnd, end, (start.x() + end.x()) / 2.0));
        candidates.add(routeViaY(start, clearStart, clearEnd, end, (start.y() + end.y()) / 2.0));
        candidates.add(routeViaX(start, clearStart, clearEnd, end, clearStart.x()));
        candidates.add(routeViaX(start, clearStart, clearEnd, end, clearEnd.x()));
        candidates.add(routeViaY(start, clearStart, clearEnd, end, clearStart.y()));
        candidates.add(routeViaY(start, clearStart, clearEnd, end, clearEnd.y()));
        addOriginalRouteLaneCandidates(
            candidates,
            edge.points(),
            start,
            clearStart,
            clearEnd,
            end);
        addExistingRouteLaneCandidates(
            candidates,
            existingEdges,
            start,
            clearStart,
            clearEnd,
            end);
        double minNodeX = minObstacleX(nodes, groups);
        double maxNodeX = maxObstacleX(nodes, groups);
        double minNodeY = minObstacleY(nodes, groups);
        double maxNodeY = maxObstacleY(nodes, groups);
        for (int lane = 0; lane < ROUTE_FALLBACK_LANE_COUNT; lane++) {
            double offset = ROUTE_CHANNEL_PADDING + (lane * ROUTE_FALLBACK_LANE_SPACING);
            candidates.add(routeViaX(start, clearStart, clearEnd, end, minNodeX - offset));
            candidates.add(routeViaX(start, clearStart, clearEnd, end, maxNodeX + offset));
            candidates.add(routeViaY(start, clearStart, clearEnd, end, minNodeY - offset));
            candidates.add(routeViaY(start, clearStart, clearEnd, end, maxNodeY + offset));
        }

        List<JsonContracts.Point> best = List.of();
        int bestCloseParallelCount = Integer.MAX_VALUE;
        int bestCornerCount = Integer.MAX_VALUE;
        double bestLength = Double.POSITIVE_INFINITY;
        for (List<JsonContracts.Point> candidate : candidates) {
            if (routeIntersectsUnrelatedNode(edge, candidate, nodes)
                || routeIntersectsUnrelatedGroup(edge, candidate, groups)) {
                continue;
            }
            int closeParallelCount = closeParallelRouteCount(edge, candidate, existingEdges);
            int candidateCornerCount = cornerCount(candidate);
            double candidateLength = routeLength(candidate);
            if (closeParallelCount < bestCloseParallelCount
                || (closeParallelCount == bestCloseParallelCount
                    && candidateCornerCount < bestCornerCount)
                || (closeParallelCount == bestCloseParallelCount
                    && candidateCornerCount == bestCornerCount
                    && candidateLength < bestLength)) {
                best = candidate;
                bestCloseParallelCount = closeParallelCount;
                bestCornerCount = candidateCornerCount;
                bestLength = candidateLength;
            }
        }
        return best;
    }

    private static void addOriginalRouteLaneCandidates(
        List<List<JsonContracts.Point>> candidates,
        List<JsonContracts.Point> originalPoints,
        JsonContracts.Point start,
        JsonContracts.Point clearStart,
        JsonContracts.Point clearEnd,
        JsonContracts.Point end) {
        for (int index = 0; index < originalPoints.size() - 1; index++) {
            JsonContracts.Point segmentStart = originalPoints.get(index);
            JsonContracts.Point segmentEnd = originalPoints.get(index + 1);
            if (vertical(segmentStart, segmentEnd)) {
                candidates.add(routeViaX(start, clearStart, clearEnd, end, segmentStart.x()));
            } else if (horizontal(segmentStart, segmentEnd)) {
                candidates.add(routeViaY(start, clearStart, clearEnd, end, segmentStart.y()));
            }
        }
    }

    private static void addExistingRouteLaneCandidates(
        List<List<JsonContracts.Point>> candidates,
        List<JsonContracts.LaidOutEdge> existingEdges,
        JsonContracts.Point start,
        JsonContracts.Point clearStart,
        JsonContracts.Point clearEnd,
        JsonContracts.Point end) {
        for (JsonContracts.LaidOutEdge existingEdge : existingEdges) {
            for (int index = 0; index < existingEdge.points().size() - 1; index++) {
                JsonContracts.Point segmentStart = existingEdge.points().get(index);
                JsonContracts.Point segmentEnd = existingEdge.points().get(index + 1);
                if (vertical(segmentStart, segmentEnd)) {
                    candidates.add(routeViaX(
                        start,
                        clearStart,
                        clearEnd,
                        end,
                        segmentStart.x() - ROUTE_FALLBACK_LANE_SPACING));
                    candidates.add(routeViaX(
                        start,
                        clearStart,
                        clearEnd,
                        end,
                        segmentStart.x() + ROUTE_FALLBACK_LANE_SPACING));
                } else if (horizontal(segmentStart, segmentEnd)) {
                    candidates.add(routeViaY(
                        start,
                        clearStart,
                        clearEnd,
                        end,
                        segmentStart.y() - ROUTE_FALLBACK_LANE_SPACING));
                    candidates.add(routeViaY(
                        start,
                        clearStart,
                        clearEnd,
                        end,
                        segmentStart.y() + ROUTE_FALLBACK_LANE_SPACING));
                }
            }
        }
    }

    private static JsonContracts.Point clearancePoint(
        JsonContracts.Point point,
        JsonContracts.LaidOutNode node) {
        if (node == null) {
            return point;
        }
        double right = node.x() + node.width();
        double bottom = node.y() + node.height();
        if (sameCoordinate(point.x(), node.x())) {
            return new JsonContracts.Point(node.x() - ROUTE_ENDPOINT_CLEARANCE, point.y());
        }
        if (sameCoordinate(point.x(), right)) {
            return new JsonContracts.Point(right + ROUTE_ENDPOINT_CLEARANCE, point.y());
        }
        if (sameCoordinate(point.y(), node.y())) {
            return new JsonContracts.Point(point.x(), node.y() - ROUTE_ENDPOINT_CLEARANCE);
        }
        if (sameCoordinate(point.y(), bottom)) {
            return new JsonContracts.Point(point.x(), bottom + ROUTE_ENDPOINT_CLEARANCE);
        }
        return point;
    }

    private static List<JsonContracts.Point> routeViaX(
        JsonContracts.Point start,
        JsonContracts.Point clearStart,
        JsonContracts.Point clearEnd,
        JsonContracts.Point end,
        double viaX) {
        return compactPoints(List.of(
            start,
            clearStart,
            new JsonContracts.Point(viaX, clearStart.y()),
            new JsonContracts.Point(viaX, clearEnd.y()),
            clearEnd,
            end));
    }

    private static List<JsonContracts.Point> routeViaY(
        JsonContracts.Point start,
        JsonContracts.Point clearStart,
        JsonContracts.Point clearEnd,
        JsonContracts.Point end,
        double viaY) {
        return compactPoints(List.of(
            start,
            clearStart,
            new JsonContracts.Point(clearStart.x(), viaY),
            new JsonContracts.Point(clearEnd.x(), viaY),
            clearEnd,
            end));
    }

    private static List<JsonContracts.Point> compactPoints(List<JsonContracts.Point> points) {
        List<JsonContracts.Point> compacted = new ArrayList<>();
        for (JsonContracts.Point point : points) {
            if (!compacted.isEmpty() && samePoint(compacted.get(compacted.size() - 1), point)) {
                continue;
            }
            if (compacted.size() >= 2
                && collinear(
                    compacted.get(compacted.size() - 2),
                    compacted.get(compacted.size() - 1),
                    point)) {
                compacted.set(compacted.size() - 1, point);
            } else {
                compacted.add(point);
            }
        }
        return compacted;
    }

    private static boolean collinear(
        JsonContracts.Point first,
        JsonContracts.Point second,
        JsonContracts.Point third) {
        return sameCoordinate(first.x(), second.x())
            && sameCoordinate(second.x(), third.x())
            || sameCoordinate(first.y(), second.y())
                && sameCoordinate(second.y(), third.y());
    }

    private static boolean samePoint(JsonContracts.Point left, JsonContracts.Point right) {
        return sameCoordinate(left.x(), right.x())
            && sameCoordinate(left.y(), right.y());
    }

    private static boolean sameCoordinate(double left, double right) {
        return Math.abs(left - right) <= GEOMETRY_EPSILON;
    }

    private static boolean routeIntersectsUnrelatedNode(
        JsonContracts.LaidOutEdge edge,
        List<JsonContracts.Point> points,
        List<JsonContracts.LaidOutNode> nodes) {
        for (int index = 0; index < points.size() - 1; index++) {
            JsonContracts.Point start = points.get(index);
            JsonContracts.Point end = points.get(index + 1);
            for (JsonContracts.LaidOutNode node : nodes) {
                if (!node.id().equals(edge.source())
                    && !node.id().equals(edge.target())
                    && segmentIntersectsRect(start, end, node)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean routeIntersectsUnrelatedGroup(
        JsonContracts.LaidOutEdge edge,
        List<JsonContracts.Point> points,
        List<JsonContracts.LaidOutGroup> groups) {
        for (int index = 0; index < points.size() - 1; index++) {
            JsonContracts.Point start = points.get(index);
            JsonContracts.Point end = points.get(index + 1);
            for (JsonContracts.LaidOutGroup group : groups) {
                if (groupContainsEndpoint(edge, group)) {
                    continue;
                }
                if (segmentIntersectsRect(start, end, group)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean groupContainsEndpoint(
        JsonContracts.LaidOutEdge edge,
        JsonContracts.LaidOutGroup group) {
        return group.members().contains(edge.source())
            || group.members().contains(edge.target());
    }

    private static boolean segmentIntersectsRect(
        JsonContracts.Point start,
        JsonContracts.Point end,
        JsonContracts.LaidOutNode node) {
        double minX = Math.min(start.x(), end.x());
        double maxX = Math.max(start.x(), end.x());
        double minY = Math.min(start.y(), end.y());
        double maxY = Math.max(start.y(), end.y());
        return rectanglesOverlap(
            minX,
            minY,
            Math.max(maxX - minX, 1.0),
            Math.max(maxY - minY, 1.0),
            node.x(),
            node.y(),
            node.width(),
            node.height());
    }

    private static boolean pointOnVerticalBoundary(
        JsonContracts.Point point,
        JsonContracts.LaidOutNode node) {
        double right = node.x() + node.width();
        return sameCoordinate(point.x(), node.x())
            || sameCoordinate(point.x(), right);
    }

    private static boolean pointOnHorizontalBoundary(
        JsonContracts.Point point,
        JsonContracts.LaidOutNode node) {
        double bottom = node.y() + node.height();
        return sameCoordinate(point.y(), node.y())
            || sameCoordinate(point.y(), bottom);
    }

    private static boolean withinNodeX(JsonContracts.LaidOutNode node, double x) {
        return x >= node.x() - GEOMETRY_EPSILON
            && x <= node.x() + node.width() + GEOMETRY_EPSILON;
    }

    private static boolean withinNodeY(JsonContracts.LaidOutNode node, double y) {
        return y >= node.y() - GEOMETRY_EPSILON
            && y <= node.y() + node.height() + GEOMETRY_EPSILON;
    }

    private static boolean segmentIntersectsRect(
        JsonContracts.Point start,
        JsonContracts.Point end,
        JsonContracts.LaidOutGroup group) {
        double minX = Math.min(start.x(), end.x());
        double maxX = Math.max(start.x(), end.x());
        double minY = Math.min(start.y(), end.y());
        double maxY = Math.max(start.y(), end.y());
        return rectanglesOverlap(
            minX,
            minY,
            Math.max(maxX - minX, 1.0),
            Math.max(maxY - minY, 1.0),
            group.x(),
            group.y(),
            group.width(),
            group.height());
    }

    private static boolean rectanglesOverlap(
        double leftX,
        double leftY,
        double leftWidth,
        double leftHeight,
        double rightX,
        double rightY,
        double rightWidth,
        double rightHeight) {
        return leftX < rightX + rightWidth
            && leftX + leftWidth > rightX
            && leftY < rightY + rightHeight
            && leftY + leftHeight > rightY;
    }

    private static double routeLength(List<JsonContracts.Point> points) {
        double length = 0.0;
        for (int index = 0; index < points.size() - 1; index++) {
            JsonContracts.Point start = points.get(index);
            JsonContracts.Point end = points.get(index + 1);
            length += Math.abs(start.x() - end.x()) + Math.abs(start.y() - end.y());
        }
        return length;
    }

    private static int cornerCount(List<JsonContracts.Point> points) {
        int corners = 0;
        RouteOrientation previous = null;
        for (int index = 0; index < points.size() - 1; index++) {
            RouteOrientation current = routeOrientation(points.get(index), points.get(index + 1));
            if (current == null) {
                continue;
            }
            if (previous != null && previous != current) {
                corners++;
            }
            previous = current;
        }
        return corners;
    }

    private enum RouteOrientation {
        HORIZONTAL,
        VERTICAL
    }

    private static RouteOrientation routeOrientation(
        JsonContracts.Point start,
        JsonContracts.Point end) {
        if (horizontal(start, end)) {
            return RouteOrientation.HORIZONTAL;
        }
        if (vertical(start, end)) {
            return RouteOrientation.VERTICAL;
        }
        return null;
    }

    private static double directLength(List<JsonContracts.Point> points) {
        JsonContracts.Point start = points.get(0);
        JsonContracts.Point end = points.get(points.size() - 1);
        return Math.abs(start.x() - end.x()) + Math.abs(start.y() - end.y());
    }

    private static int closeParallelRouteCount(
        JsonContracts.LaidOutEdge candidateEdge,
        List<JsonContracts.Point> candidate,
        List<JsonContracts.LaidOutEdge> existingEdges) {
        int count = 0;
        for (int candidateIndex = 0; candidateIndex < candidate.size() - 1; candidateIndex++) {
            JsonContracts.Point candidateStart = candidate.get(candidateIndex);
            JsonContracts.Point candidateEnd = candidate.get(candidateIndex + 1);
            for (JsonContracts.LaidOutEdge edge : existingEdges) {
                if (shareEndpoint(candidateEdge, edge)) {
                    continue;
                }
                for (int edgeIndex = 0; edgeIndex < edge.points().size() - 1; edgeIndex++) {
                    if (closeParallelSegments(
                        candidateStart,
                        candidateEnd,
                        edge.points().get(edgeIndex),
                        edge.points().get(edgeIndex + 1))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static boolean shareEndpoint(
        JsonContracts.LaidOutEdge left,
        JsonContracts.LaidOutEdge right) {
        return left.source().equals(right.source())
            || left.source().equals(right.target())
            || left.target().equals(right.source())
            || left.target().equals(right.target());
    }

    private static boolean closeParallelSegments(
        JsonContracts.Point leftStart,
        JsonContracts.Point leftEnd,
        JsonContracts.Point rightStart,
        JsonContracts.Point rightEnd) {
        if (horizontal(leftStart, leftEnd) && horizontal(rightStart, rightEnd)) {
            return Math.abs(leftStart.y() - rightStart.y()) < ROUTE_CLOSE_PARALLEL_DISTANCE
                && overlapLength(leftStart.x(), leftEnd.x(), rightStart.x(), rightEnd.x())
                    >= ROUTE_CLOSE_PARALLEL_MIN_OVERLAP;
        }
        if (vertical(leftStart, leftEnd) && vertical(rightStart, rightEnd)) {
            return Math.abs(leftStart.x() - rightStart.x()) < ROUTE_CLOSE_PARALLEL_DISTANCE
                && overlapLength(leftStart.y(), leftEnd.y(), rightStart.y(), rightEnd.y())
                    >= ROUTE_CLOSE_PARALLEL_MIN_OVERLAP;
        }
        return false;
    }

    private static boolean horizontal(JsonContracts.Point start, JsonContracts.Point end) {
        return sameCoordinate(start.y(), end.y()) && !sameCoordinate(start.x(), end.x());
    }

    private static boolean vertical(JsonContracts.Point start, JsonContracts.Point end) {
        return sameCoordinate(start.x(), end.x()) && !sameCoordinate(start.y(), end.y());
    }

    private static double overlapLength(
        double firstStart,
        double firstEnd,
        double secondStart,
        double secondEnd) {
        double firstMin = Math.min(firstStart, firstEnd);
        double firstMax = Math.max(firstStart, firstEnd);
        double secondMin = Math.min(secondStart, secondEnd);
        double secondMax = Math.max(secondStart, secondEnd);
        return Math.max(0.0, Math.min(firstMax, secondMax) - Math.max(firstMin, secondMin));
    }

    private static double minNodeX(List<JsonContracts.LaidOutNode> nodes) {
        return nodes.stream()
            .mapToDouble(JsonContracts.LaidOutNode::x)
            .min()
            .orElse(0.0);
    }

    private static double maxNodeX(List<JsonContracts.LaidOutNode> nodes) {
        return nodes.stream()
            .mapToDouble(node -> node.x() + node.width())
            .max()
            .orElse(0.0);
    }

    private static double minNodeY(List<JsonContracts.LaidOutNode> nodes) {
        return nodes.stream()
            .mapToDouble(JsonContracts.LaidOutNode::y)
            .min()
            .orElse(0.0);
    }

    private static double maxNodeY(List<JsonContracts.LaidOutNode> nodes) {
        return nodes.stream()
            .mapToDouble(node -> node.y() + node.height())
            .max()
            .orElse(0.0);
    }

    private static double minObstacleX(
        List<JsonContracts.LaidOutNode> nodes,
        List<JsonContracts.LaidOutGroup> groups) {
        return Math.min(
            minNodeX(nodes),
            groups.stream()
                .mapToDouble(JsonContracts.LaidOutGroup::x)
                .min()
                .orElse(minNodeX(nodes)));
    }

    private static double maxObstacleX(
        List<JsonContracts.LaidOutNode> nodes,
        List<JsonContracts.LaidOutGroup> groups) {
        return Math.max(
            maxNodeX(nodes),
            groups.stream()
                .mapToDouble(group -> group.x() + group.width())
                .max()
                .orElse(maxNodeX(nodes)));
    }

    private static double minObstacleY(
        List<JsonContracts.LaidOutNode> nodes,
        List<JsonContracts.LaidOutGroup> groups) {
        return Math.min(
            minNodeY(nodes),
            groups.stream()
                .mapToDouble(JsonContracts.LaidOutGroup::y)
                .min()
                .orElse(minNodeY(nodes)));
    }

    private static double maxObstacleY(
        List<JsonContracts.LaidOutNode> nodes,
        List<JsonContracts.LaidOutGroup> groups) {
        return Math.max(
            maxNodeY(nodes),
            groups.stream()
                .mapToDouble(group -> group.y() + group.height())
                .max()
                .orElse(maxNodeY(nodes)));
    }

    private static JsonContracts.LaidOutNode nodeById(
        List<JsonContracts.LaidOutNode> nodes,
        String id) {
        return nodes.stream()
            .filter(node -> node.id().equals(id))
            .findFirst()
            .orElse(null);
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
                group.provenance(),
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
        boolean visualOnly = Boolean.TRUE.equals(provenance.visual_only());
        boolean semanticBacked = provenance.semantic_backed() != null;
        if (visualOnly == semanticBacked) {
            throw new IllegalArgumentException(
                "group provenance must contain exactly one of visual_only or semantic_backed at " + path);
        }
        if (semanticBacked && provenance.semantic_backed().source_id() == null) {
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

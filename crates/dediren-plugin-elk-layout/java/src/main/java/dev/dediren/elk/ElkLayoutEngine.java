package dev.dediren.elk;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.elk.alg.layered.options.EdgeStraighteningStrategy;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy;
import org.eclipse.elk.alg.layered.options.OrderingStrategy;
import org.eclipse.elk.alg.layered.options.PortSortingStrategy;
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
    private static final double DEFAULT_WIDTH = 160.0;
    private static final double DEFAULT_HEIGHT = 80.0;
    private static final double CONNECTOR_SOURCE_MAX_WIDTH = 48.0;
    private static final double CONNECTOR_SOURCE_MAX_HEIGHT = 48.0;
    private static final double GROUP_PADDING = 24.0;
    private static final double NODE_SPACING = 60.0;
    private static final double EDGE_NODE_SPACING = 32.0;
    private static final double EDGE_EDGE_SPACING = 40.0;
    private static final double PORT_PORT_SPACING = 32.0;
    private static final double READABLE_NODE_SPACING = 72.0;
    private static final double READABLE_EDGE_NODE_SPACING = 48.0;
    private static final double READABLE_EDGE_EDGE_SPACING = 48.0;
    private static final double READABLE_PORT_PORT_SPACING = 40.0;
    private static final double READABLE_GROUP_PADDING = 32.0;
    private static final double SPACIOUS_NODE_SPACING = 96.0;
    private static final double SPACIOUS_EDGE_NODE_SPACING = 64.0;
    private static final double SPACIOUS_EDGE_EDGE_SPACING = 64.0;
    private static final double SPACIOUS_PORT_PORT_SPACING = 48.0;
    private static final double SPACIOUS_GROUP_PADDING = 40.0;
    private static final int DEFAULT_SHORT_SIDE_PORT_CAPACITY = 3;
    private static final double PORT_SURROUNDING_SPACING = 16.0;
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
        JsonContracts.LayoutPreferences preferences = request.layout_preferences();
        Direction layoutDirection = preferredDirection(preferences);
        ElkNode root = ElkGraphUtil.createGraph();
        configureLayeredRoot(root, layoutDirection, preferences);

        Map<String, JsonContracts.LayoutNode> requestNodes = requestNodesById(request);
        List<JsonContracts.LayoutEdge> requestEdges = list(request.edges());
        Map<String, EdgeEndpointMerge> endpointMerges =
            flatEdgeEndpointMerges(requestEdges, requestNodes, preferences);
        Map<String, EnumMap<PortSide, Integer>> portCounts =
            flatPortCounts(requestEdges, requestNodes, endpointMerges, layoutDirection);
        Map<String, ElkNode> elkNodes = new HashMap<>();
        for (JsonContracts.LayoutNode node : list(request.nodes())) {
            ElkNode elkNode = ElkGraphUtil.createNode(root);
            elkNode.setIdentifier(node.id());
            setGeneratedDimensions(elkNode, node, portCounts.get(node.id()), preferences);
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
                layoutDirection,
                endpointMerge.sourceEndpoint()
                    ? 0
                    : nextPortIndex(portIndexes, edge.source(), sourcePortSide(layoutDirection)),
                endpointMerge.targetEndpoint()
                    ? 0
                    : nextPortIndex(portIndexes, edge.target(), targetPortSide(layoutDirection)),
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
        for (JsonContracts.LayoutEdge edge : list(request.edges())) {
            ElkEdge elkEdge = elkEdges.get(edge.id());
            if (elkEdge != null) {
                edges.add(new JsonContracts.LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.source_id(),
                    edge.id(),
                    routingHints(edge.id(), endpointMerges),
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
        JsonContracts.LayoutPreferences preferences = request.layout_preferences();
        Direction rootDirection = preferredDirection(preferences);
        List<JsonContracts.Diagnostic> warnings = new ArrayList<>();
        Map<String, JsonContracts.LayoutNode> requestNodes = requestNodesById(request);
        Map<String, String> ownerByNode = ownerByNode(request);
        ElkNode root = ElkGraphUtil.createGraph();
        configureLayeredRoot(root, rootDirection, preferences, false);
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN);
        root.setProperty(CoreOptions.ASPECT_RATIO, 2.2);
        if (groupedWrappingEnabled(preferences)) {
            root.setProperty(LayeredOptions.WRAPPING_STRATEGY, WrappingStrategy.MULTI_EDGE);
        }
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
            configureLayeredRoot(elkGroup, groupDirection, preferences, false);
            elkGroup.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN);
            elkGroup.setProperty(CoreOptions.PADDING, new ElkPadding(groupPadding(preferences)));
            elkGroups.put(group.id(), elkGroup);
            groupDirectionById.put(group.id(), groupDirection);
        }

        List<JsonContracts.LayoutEdge> requestEdges = list(request.edges());
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
        for (JsonContracts.LayoutNode node : list(request.nodes())) {
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
        List<JsonContracts.LaidOutGroup> groups =
            groupedBounds(request, elkGroups, elkNodes, warnings);
        for (JsonContracts.LayoutEdge edge : list(request.edges())) {
            ElkEdge elkEdge = elkEdges.get(edge.id());
            if (elkEdge != null) {
                edges.add(new JsonContracts.LaidOutEdge(
                    edge.id(),
                    edge.source(),
                    edge.target(),
                    edge.source_id(),
                    edge.id(),
                    routingHints(edge.id(), endpointMerges),
                    points(elkEdge),
                    edge.label()));
            }
        }
        // Route geometry belongs to ELK Layered. Keep route-quality concerns in
        // ELK graph construction, ELK options, and validation diagnostics.

        return new JsonContracts.LayoutResult(
            "layout-result.schema.v1",
            request.view_id(),
            nodes,
            edges,
            groups,
            warnings);
    }

    private static void configureLayeredRoot(
        ElkNode root,
        Direction direction,
        JsonContracts.LayoutPreferences preferences) {
        configureLayeredRoot(root, direction, preferences, true);
    }

    private static void configureLayeredRoot(
        ElkNode root,
        Direction direction,
        JsonContracts.LayoutPreferences preferences,
        boolean preserveModelOrder) {
        double nodeSpacing = switch (density(preferences)) {
            case "readable" -> READABLE_NODE_SPACING;
            case "spacious" -> SPACIOUS_NODE_SPACING;
            default -> NODE_SPACING;
        };
        double edgeNodeSpacing = switch (density(preferences)) {
            case "readable" -> READABLE_EDGE_NODE_SPACING;
            case "spacious" -> SPACIOUS_EDGE_NODE_SPACING;
            default -> EDGE_NODE_SPACING;
        };
        double edgeEdgeSpacing = switch (density(preferences)) {
            case "readable" -> READABLE_EDGE_EDGE_SPACING;
            case "spacious" -> SPACIOUS_EDGE_EDGE_SPACING;
            default -> EDGE_EDGE_SPACING;
        };
        double portPortSpacing = portPortSpacing(preferences);

        root.setProperty(CoreOptions.ALGORITHM, LAYERED_ALGORITHM);
        root.setProperty(CoreOptions.DIRECTION, direction);
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
        root.setProperty(CoreOptions.SPACING_NODE_NODE, nodeSpacing);
        root.setProperty(CoreOptions.SPACING_EDGE_NODE, edgeNodeSpacing);
        root.setProperty(CoreOptions.SPACING_EDGE_EDGE, edgeEdgeSpacing);
        root.setProperty(CoreOptions.SPACING_PORT_PORT, portPortSpacing);
        root.setProperty(CoreOptions.SPACING_PORTS_SURROUNDING, new ElkMargin(PORT_SURROUNDING_SPACING));
        root.setProperty(LayeredOptions.SPACING_EDGE_EDGE, edgeEdgeSpacing);
        root.setProperty(LayeredOptions.SPACING_EDGE_NODE, edgeNodeSpacing);
        root.setProperty(LayeredOptions.SPACING_PORT_PORT, portPortSpacing);
        root.setProperty(LayeredOptions.SPACING_PORTS_SURROUNDING, new ElkMargin(PORT_SURROUNDING_SPACING));
        root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, nodeSpacing);
        root.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, edgeNodeSpacing);
        root.setProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, edgeEdgeSpacing);
        root.setProperty(LayeredOptions.PORT_SORTING_STRATEGY, PortSortingStrategy.INPUT_ORDER);
        if (preserveModelOrder) {
            root.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.PREFER_EDGES);
            root.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_PORT_MODEL_ORDER, true);
        }
        root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.BRANDES_KOEPF);
        root.setProperty(
            LayeredOptions.NODE_PLACEMENT_BK_EDGE_STRAIGHTENING,
            EdgeStraighteningStrategy.IMPROVE_STRAIGHTNESS);
        root.setProperty(LayeredOptions.UNNECESSARY_BENDPOINTS, true);
        boolean mergeEdges = endpointMergingEnabled(preferences);
        root.setProperty(LayeredOptions.MERGE_EDGES, mergeEdges);
        root.setProperty(LayeredOptions.MERGE_HIERARCHY_EDGES, mergeEdges);
    }

    static ElkNode configuredLayeredRoot(
        Direction direction,
        JsonContracts.LayoutPreferences preferences) {
        ElkNode root = ElkGraphUtil.createGraph();
        configureLayeredRoot(root, direction, preferences);
        return root;
    }

    private static String density(JsonContracts.LayoutPreferences preferences) {
        return preferences == null || preferences.density() == null
            ? "compact"
            : preferences.density();
    }

    private static double portPortSpacing(JsonContracts.LayoutPreferences preferences) {
        return switch (density(preferences)) {
            case "readable" -> READABLE_PORT_PORT_SPACING;
            case "spacious" -> SPACIOUS_PORT_PORT_SPACING;
            default -> PORT_PORT_SPACING;
        };
    }

    private static double groupPadding(JsonContracts.LayoutPreferences preferences) {
        return switch (density(preferences)) {
            case "readable" -> READABLE_GROUP_PADDING;
            case "spacious" -> SPACIOUS_GROUP_PADDING;
            default -> GROUP_PADDING;
        };
    }

    private static boolean groupedWrappingEnabled(JsonContracts.LayoutPreferences preferences) {
        return preferences == null
            || preferences.wrapping() == null
            || "auto".equals(preferences.wrapping())
            || "multi-edge".equals(preferences.wrapping());
    }

    private static String endpointMerging(JsonContracts.LayoutPreferences preferences) {
        if (preferences == null
            || preferences.routing() == null
            || preferences.routing().endpoint_merging() == null) {
            return "auto";
        }
        return preferences.routing().endpoint_merging();
    }

    private static boolean endpointMergingEnabled(JsonContracts.LayoutPreferences preferences) {
        return !"off".equals(endpointMerging(preferences));
    }

    private static Direction preferredDirection(JsonContracts.LayoutPreferences preferences) {
        if (preferences == null || preferences.direction() == null) {
            return Direction.RIGHT;
        }
        return switch (preferences.direction()) {
            case "left" -> Direction.LEFT;
            case "down" -> Direction.DOWN;
            case "up" -> Direction.UP;
            default -> Direction.RIGHT;
        };
    }

    private static Map<String, EdgeEndpointMerge> emptyEndpointMerges(
        List<JsonContracts.LayoutEdge> edges) {
        Map<String, EdgeEndpointMerge> endpointMerges = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
            endpointMerges.put(edge.id(), NO_ENDPOINT_MERGE);
        }
        return endpointMerges;
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

    private static boolean isConnectorSizedRequestNode(JsonContracts.LayoutNode node) {
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

    // Endpoint merging is Dediren graph shaping, not route geometry. ELK's
    // MERGE_EDGES and MERGE_HIERARCHY_EDGES are broad booleans; Dediren keeps
    // relationship-type scoped shared ports so intentional fan-in/fan-out
    // junctions remain readable without globally merging unrelated edges.
    private static Map<String, EdgeEndpointMerge> flatEdgeEndpointMerges(
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LayoutNode> nodes,
        JsonContracts.LayoutPreferences preferences) {
        if (!endpointMergingEnabled(preferences)) {
            return emptyEndpointMerges(edges);
        }

        Direction direction = preferredDirection(preferences);
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
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById,
        Direction rootDirection,
        JsonContracts.LayoutPreferences preferences) {
        if (!endpointMergingEnabled(preferences)) {
            return emptyEndpointMerges(edges);
        }

        Set<String> sourceOnlyGroups = sourceOnlyGroups(edges, ownerByNode);
        Map<EdgeEndpointKey, Integer> endpointCounts = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : edges) {
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
        for (JsonContracts.LayoutEdge edge : edges) {
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
        List<JsonContracts.LayoutEdge> edges,
        Map<String, String> ownerByNode) {
        Set<String> groups = new HashSet<>(ownerByNode.values());
        Set<String> nonSourceOnlyGroups = new HashSet<>();
        Set<String> groupsWithOutgoingEdges = new HashSet<>();
        for (JsonContracts.LayoutEdge edge : edges) {
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
        JsonContracts.LayoutEdge edge,
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
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LayoutNode> nodes,
        Map<String, EdgeEndpointMerge> endpointMerges,
        Direction direction) {
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

    private static Map<String, EnumMap<PortSide, Integer>> groupedPortCounts(
        List<JsonContracts.LayoutEdge> edges,
        Map<String, JsonContracts.LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById,
        Direction rootDirection,
        Map<String, EdgeEndpointMerge> endpointMerges) {
        Map<String, EnumMap<PortSide, Integer>> portCounts = new HashMap<>();
        Set<EdgeEndpointKey> countedMergePorts = new HashSet<>();
        for (JsonContracts.LayoutEdge edge : edges) {
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
        JsonContracts.LayoutRequest request,
        Map<String, JsonContracts.LayoutNode> nodes,
        Map<String, String> ownerByNode,
        Map<String, Direction> groupDirectionById,
        Map<String, Integer> groupOrderById,
        Direction rootDirection) {
        Map<NodeSideKey, Integer> nextIndexByNodeSide = new HashMap<>();
        Map<String, EdgePortIndexes> portIndexesByEdge = new HashMap<>();
        for (JsonContracts.LayoutEdge edge : list(request.edges())) {
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
            int sourceIndex = nextGroupedPortIndex(nextIndexByNodeSide, edge.source(), sourceSide);
            int targetIndex = nextGroupedPortIndex(nextIndexByNodeSide, edge.target(), targetSide);
            portIndexesByEdge.put(edge.id(), new EdgePortIndexes(sourceIndex, targetIndex));
        }
        return portIndexesByEdge;
    }

    private static int nextGroupedPortIndex(
        Map<NodeSideKey, Integer> nextIndexByNodeSide,
        String nodeId,
        PortSide side) {
        int index = nextIndexByNodeSide.merge(new NodeSideKey(nodeId, side), 1, Integer::sum) - 1;
        // ELK's west-side port indexes render in the opposite vertical
        // direction, so use descending indices there to preserve request order
        // as top-to-bottom visual graph intent.
        return side == PortSide.WEST ? -index : index;
    }

    // ELK accounts for the generated ports, but Dediren still increases the
    // minimum node side length when many ports would otherwise be packed onto
    // the same side. This is size intent, not route geometry.
    private static void setGeneratedDimensions(
        ElkNode elkNode,
        JsonContracts.LayoutNode node,
        Map<PortSide, Integer> portCounts,
        JsonContracts.LayoutPreferences preferences) {
        double width = positiveOrDefault(node.width_hint(), DEFAULT_WIDTH);
        double height = positiveOrDefault(node.height_hint(), DEFAULT_HEIGHT);
        if (portCounts != null) {
            double portSpacing = portPortSpacing(preferences);
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

    private record NodeSideKey(String nodeId, PortSide side) {}

    private record EdgeEndpointMerge(boolean sourceEndpoint, boolean targetEndpoint) {}

    private record EdgeEndpointKey(
        String nodeId,
        PortSide side,
        boolean sourceEndpoint,
        String relationshipType) {}

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
        validateLayoutPreferences(request.layout_preferences(), "$.layout_preferences");

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

    private static void validateLayoutPreferences(
        JsonContracts.LayoutPreferences preferences,
        String path) {
        if (preferences == null) {
            return;
        }
        requireOneOf(
            preferences.direction(),
            path + ".direction",
            "right",
            "left",
            "down",
            "up");
        requireOneOf(
            preferences.density(),
            path + ".density",
            "compact",
            "readable",
            "spacious");
        requireOneOf(
            preferences.wrapping(),
            path + ".wrapping",
            "auto",
            "off",
            "multi-edge");
        validateRoutingPreferences(preferences.routing(), path + ".routing");
    }

    private static void validateRoutingPreferences(
        JsonContracts.LayoutRoutingPreferences routing,
        String path) {
        if (routing == null) {
            return;
        }
        requireOneOf(routing.style(), path + ".style", "orthogonal");
        requireOneOf(
            routing.profile(),
            path + ".profile",
            "compact",
            "readable",
            "spacious");
        requireOneOf(
            routing.endpoint_merging(),
            path + ".endpoint_merging",
            "off",
            "local",
            "auto");
    }

    private static void requireOneOf(String value, String path, String... accepted) {
        if (value == null) {
            return;
        }
        for (String candidate : accepted) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException(path + " has unsupported value: " + value);
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

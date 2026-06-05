package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.layout.LayoutDensity;
import dev.dediren.contracts.layout.LayoutDirection;
import dev.dediren.contracts.layout.LayoutEndpointMerging;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRoutingPreferences;
import dev.dediren.contracts.layout.LayoutWrapping;
import org.eclipse.elk.alg.layered.options.EdgeStraighteningStrategy;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy;
import org.eclipse.elk.alg.layered.options.OrderingStrategy;
import org.eclipse.elk.alg.layered.options.PortSortingStrategy;
import org.eclipse.elk.alg.layered.options.WrappingStrategy;
import org.eclipse.elk.core.math.ElkMargin;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.options.HierarchyHandling;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.util.ElkGraphUtil;

final class ElkLayeredOptions {
    static final double DEFAULT_GROUP_PADDING = 24.0;

    private static final String LAYERED_ALGORITHM = "org.eclipse.elk.layered";
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
    private static final double PORT_SURROUNDING_SPACING = 16.0;

    private ElkLayeredOptions() {
    }

    static void configureRoot(ElkNode root, Direction direction, LayoutPreferences preferences) {
        configureRoot(root, direction, preferences, true);
    }

    static void configureRoot(
            ElkNode root,
            Direction direction,
            LayoutPreferences preferences,
            boolean preserveModelOrder) {
        double nodeSpacing = switch (density(preferences)) {
            case READABLE -> READABLE_NODE_SPACING;
            case SPACIOUS -> SPACIOUS_NODE_SPACING;
            default -> NODE_SPACING;
        };
        double edgeNodeSpacing = switch (density(preferences)) {
            case READABLE -> READABLE_EDGE_NODE_SPACING;
            case SPACIOUS -> SPACIOUS_EDGE_NODE_SPACING;
            default -> EDGE_NODE_SPACING;
        };
        double edgeEdgeSpacing = switch (density(preferences)) {
            case READABLE -> READABLE_EDGE_EDGE_SPACING;
            case SPACIOUS -> SPACIOUS_EDGE_EDGE_SPACING;
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

    static void configureGroupedRoot(ElkNode root, Direction direction, LayoutPreferences preferences) {
        configureRoot(root, direction, preferences, false);
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN);
        root.setProperty(CoreOptions.ASPECT_RATIO, 2.2);
        if (groupedWrappingEnabled(preferences)) {
            root.setProperty(LayeredOptions.WRAPPING_STRATEGY, WrappingStrategy.MULTI_EDGE);
        }
        root.setProperty(LayeredOptions.FEEDBACK_EDGES, true);
    }

    static void configureGroup(ElkNode group, Direction direction, LayoutPreferences preferences) {
        configureRoot(group, direction, preferences, false);
        group.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN);
        group.setProperty(CoreOptions.PADDING, new ElkPadding(groupPadding(preferences)));
    }

    static ElkNode configuredRoot(Direction direction, LayoutPreferences preferences) {
        ElkNode root = ElkGraphUtil.createGraph();
        configureRoot(root, direction, preferences);
        return root;
    }

    static double portPortSpacing(LayoutPreferences preferences) {
        return switch (density(preferences)) {
            case READABLE -> READABLE_PORT_PORT_SPACING;
            case SPACIOUS -> SPACIOUS_PORT_PORT_SPACING;
            default -> PORT_PORT_SPACING;
        };
    }

    static boolean endpointMergingEnabled(LayoutPreferences preferences) {
        return endpointMerging(preferences) != LayoutEndpointMerging.OFF;
    }

    static Direction preferredDirection(LayoutPreferences preferences) {
        if (preferences == null || preferences.direction() == null) {
            return Direction.RIGHT;
        }
        return switch (preferences.direction()) {
            case LEFT -> Direction.LEFT;
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            default -> Direction.RIGHT;
        };
    }

    private static LayoutDensity density(LayoutPreferences preferences) {
        return preferences == null || preferences.density() == null
                ? LayoutDensity.COMPACT
                : preferences.density();
    }

    private static double groupPadding(LayoutPreferences preferences) {
        return switch (density(preferences)) {
            case READABLE -> READABLE_GROUP_PADDING;
            case SPACIOUS -> SPACIOUS_GROUP_PADDING;
            default -> DEFAULT_GROUP_PADDING;
        };
    }

    private static boolean groupedWrappingEnabled(LayoutPreferences preferences) {
        return preferences != null && preferences.wrapping() == LayoutWrapping.MULTI_EDGE;
    }

    private static LayoutEndpointMerging endpointMerging(LayoutPreferences preferences) {
        LayoutRoutingPreferences routing = preferences == null ? null : preferences.routing();
        if (routing == null || routing.endpointMerging() == null) {
            return LayoutEndpointMerging.AUTO;
        }
        return routing.endpointMerging();
    }
}

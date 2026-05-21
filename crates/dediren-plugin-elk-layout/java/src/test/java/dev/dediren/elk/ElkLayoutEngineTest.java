package dev.dediren.elk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.elk.alg.layered.options.EdgeStraighteningStrategy;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy;
import org.eclipse.elk.alg.layered.options.OrderingStrategy;
import org.eclipse.elk.alg.layered.options.PortSortingStrategy;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.PortSide;
import org.eclipse.elk.graph.ElkNode;
import org.junit.jupiter.api.Test;

class ElkLayoutEngineTest {
    private static final double GEOMETRY_EPSILON = 0.001;
    private static final double PORT_SIDE_EPSILON = 1.0;

    @Test
    void elkHelperBuildUsesLayeredOnly() throws IOException {
        assertFalse(
            Files.readString(Path.of("build.gradle.kts")).contains("org.eclipse.elk.alg.libavoid"),
            "ELK helper must not declare the Libavoid backend");
        assertFalse(
            Files.readString(Path.of("gradle.lockfile")).contains("org.eclipse.elk.alg.libavoid"),
            "ELK helper lockfile must not include the Libavoid backend");
    }

    @Test
    void elkHelperDoesNotOwnPostElkRouteGeometry() throws IOException {
        String source = Files.readString(Path.of(
            "src/main/java/dev/dediren/elk/ElkLayoutEngine.java"));

        assertFalse(
            source.contains("straightenConnectorEndpointDoglegs("),
            "ELK helper must not snap connector doglegs after ELK has routed edges");
        assertFalse(
            source.contains("normalizeExcessiveRoutes("),
            "ELK helper must not replace ELK routes with a custom route normalizer");
        assertFalse(
            source.contains("shortestCleanOrthogonalRoute("),
            "ELK helper must not contain a fallback orthogonal router");
        assertFalse(
            source.contains("routeIntersectsUnrelatedNode("),
            "route intersection checks belong in validation diagnostics, not route replacement");
    }

    @Test
    void layeredRootDisablesElkMergeOptionsWhenEndpointMergingIsOff() {
        JsonContracts.LayoutPreferences preferences = new JsonContracts.LayoutPreferences(
            null,
            null,
            null,
            new JsonContracts.LayoutRoutingPreferences("orthogonal", null, "off"));

        ElkNode root = ElkLayoutEngine.configuredLayeredRoot(Direction.RIGHT, preferences);

        assertEquals(false, root.getProperty(LayeredOptions.MERGE_EDGES));
        assertEquals(false, root.getProperty(LayeredOptions.MERGE_HIERARCHY_EDGES));
    }

    @Test
    void layeredRootUsesElkFirstOrderingAndStraighteningOptions() {
        ElkNode root = ElkLayoutEngine.configuredLayeredRoot(Direction.RIGHT, null);

        assertEquals(PortSortingStrategy.INPUT_ORDER, root.getProperty(LayeredOptions.PORT_SORTING_STRATEGY));
        assertEquals(OrderingStrategy.PREFER_EDGES, root.getProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY));
        assertEquals(true, root.getProperty(LayeredOptions.CONSIDER_MODEL_ORDER_PORT_MODEL_ORDER));
        assertEquals(NodePlacementStrategy.BRANDES_KOEPF, root.getProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY));
        assertEquals(
            EdgeStraighteningStrategy.IMPROVE_STRAIGHTNESS,
            root.getProperty(LayeredOptions.NODE_PLACEMENT_BK_EDGE_STRAIGHTENING));
        assertEquals(true, root.getProperty(LayeredOptions.UNNECESSARY_BENDPOINTS));
    }

    @Test
    void layeredLayoutPlacesTargetToTheRightAndRoutesTheEdge() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("client", "Client", "client", 160.0, 80.0),
                new JsonContracts.LayoutNode("api", "API", "api", 160.0, 80.0)),
            List.of(new JsonContracts.LayoutEdge(
                "client-calls-api", "client", "api", "calls", "client-calls-api")),
            List.of(),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        JsonContracts.LaidOutNode client = result.nodes().stream()
            .filter(node -> node.id().equals("client"))
            .findFirst()
            .orElseThrow();
        JsonContracts.LaidOutNode api = result.nodes().stream()
            .filter(node -> node.id().equals("api"))
            .findFirst()
            .orElseThrow();
        JsonContracts.LaidOutEdge edge = result.edges().get(0);

        assertEquals("layout-result.schema.v1", result.layout_result_schema_version());
        assertEquals("main", result.view_id());
        assertEquals("client", client.source_id());
        assertEquals("api", api.projection_id());
        assertEquals("client-calls-api", edge.source_id());
        assertEquals("client-calls-api", edge.projection_id());
        assertTrue(api.x() > client.x(), "layered layout should place target after source");
        assertTrue(edge.points().size() >= 2, "layout must include start and end points");
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void directionUpUsesNorthToSouthPorts() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("worker", "Worker", "worker", 160.0, 80.0),
                new JsonContracts.LayoutNode("queue", "Queue", "queue", 160.0, 80.0)),
            List.of(new JsonContracts.LayoutEdge(
                "worker-polls-queue", "worker", "queue", "polls", "worker-polls-queue")),
            List.of(),
            List.of(),
            List.of(),
            new JsonContracts.LayoutPreferences("up", null, null, null));

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        assertRouteEndpointOnSide(result, "worker-polls-queue", "worker", true, PortSide.NORTH);
        assertRouteEndpointOnSide(result, "worker-polls-queue", "queue", false, PortSide.SOUTH);
    }

    @Test
    void compactDecisionFanOutUsesSeparateSourceCorners() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "activity",
            List.of(
                new JsonContracts.LayoutNode("check-cache", "Cached?", "check-cache", 32.0, 32.0),
                new JsonContracts.LayoutNode("cached", "Use Cache", "cached", 160.0, 80.0),
                new JsonContracts.LayoutNode("stale", "Refresh", "stale", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge(
                    "check-cache-cached", "check-cache", "cached", "cached", "check-cache-cached", "ControlFlow"),
                new JsonContracts.LayoutEdge(
                    "check-cache-stale", "check-cache", "stale", "stale", "check-cache-stale", "ControlFlow")),
            List.of(),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutEdge cachedEdge = edgeById(result, "check-cache-cached");
        JsonContracts.LaidOutEdge staleEdge = edgeById(result, "check-cache-stale");

        assertFalse(
            samePoint(cachedEdge.points().get(0), staleEdge.points().get(0)),
            "decision fan-out branches should not leave the same visual corner, cached="
                + cachedEdge.points()
                + ", stale="
                + staleEdge.points());
        assertTrue(
            usesDifferentSourceSides(result, "check-cache-cached", "check-cache-stale", "check-cache"),
            "decision fan-out branches should use separate source corners, cached="
                + cachedEdge.points()
                + ", stale="
                + staleEdge.points());
    }

    @Test
    void partialGroupUsesLaidOutMembersAndSemanticSourceId() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(new JsonContracts.LayoutNode("client", "Client", "client", 160.0, 80.0)),
            List.of(),
            List.of(new JsonContracts.LayoutGroup(
                "group-1",
                "Group",
                List.of("client", "missing"),
                new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("system-group")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        JsonContracts.LaidOutGroup group = result.groups().get(0);

        assertEquals("system-group", group.source_id());
        assertEquals(List.of("client"), group.members());
        assertTrue(result.warnings().stream()
            .anyMatch(warning ->
                warning.code().equals("DEDIREN_ELK_MISSING_GROUP_MEMBER")
                    && warning.path().equals("$.groups[0].members[1]")));
    }

    @Test
    void layoutPreservesVisualOnlyGroupProvenance() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(new JsonContracts.LayoutNode("client", "Client", "client", 160.0, 80.0)),
            List.of(),
            List.of(new JsonContracts.LayoutGroup(
                "visual-column",
                "Visual Column",
                List.of("client"),
                new JsonContracts.GroupProvenance(true, null))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        JsonContracts.LaidOutGroup group = result.groups().get(0);

        assertEquals("visual-column", group.source_id());
        assertTrue(group.provenance().visual_only());
        assertEquals(null, group.provenance().semantic_backed());
    }

    @Test
    void groupedMembersProduceGroupBoundsAroundGeneratedNodeGeometry() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new JsonContracts.LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
                new JsonContracts.LayoutNode("payments", "Payment Authorization Service", "payments", 160.0, 80.0),
                new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge(
                    "web-app-calls-api", "web-app", "orders-api", "calls API", "web-app-calls-api"),
                new JsonContracts.LayoutEdge(
                    "api-authorizes-payment", "orders-api", "payments", "requests payment authorization", "api-authorizes-payment"),
                new JsonContracts.LayoutEdge(
                    "api-writes-database", "orders-api", "database", "writes orders", "api-writes-database"),
                new JsonContracts.LayoutEdge(
                    "api-publishes-job", "orders-api", "worker", "publishes fulfillment", "api-publishes-job"),
                new JsonContracts.LayoutEdge(
                    "worker-reads-database", "worker", "database", "loads order", "worker-reads-database")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("web-app", "orders-api", "worker"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("application-services"))),
                new JsonContracts.LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("external-dependencies")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        assertEquals(2, result.groups().size());
        JsonContracts.LaidOutGroup application = result.groups().stream()
            .filter(group -> group.id().equals("application-services"))
            .findFirst()
            .orElseThrow();
        JsonContracts.LaidOutGroup external = result.groups().stream()
            .filter(group -> group.id().equals("external-dependencies"))
            .findFirst()
            .orElseThrow();

        assertEquals(List.of("web-app", "orders-api", "worker"), application.members());
        assertEquals(List.of("payments", "database"), external.members());
        assertGroupContainsMembers(result, application);
        assertGroupContainsMembers(result, external);
        assertTrue(
            !rectanglesOverlap(
                application.x(),
                application.y(),
                application.width(),
                application.height(),
                external.x(),
                external.y(),
                external.width(),
                external.height()),
            "ELK-generated group bounds should not overlap");
    }

    @Test
    void groupedPipelineKeepsReadableLeftToRightFlow() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("client", "Client", "client", 160.0, 80.0),
                new JsonContracts.LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new JsonContracts.LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
                new JsonContracts.LayoutNode("payments", "Payment Authorization Service", "payments", 160.0, 80.0),
                new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("client-submits-order", "client", "web-app", "submits order", "client-submits-order"),
                new JsonContracts.LayoutEdge("web-app-calls-api", "web-app", "orders-api", "calls API", "web-app-calls-api"),
                new JsonContracts.LayoutEdge("api-authorizes-payment", "orders-api", "payments", "requests payment authorization", "api-authorizes-payment"),
                new JsonContracts.LayoutEdge("api-writes-database", "orders-api", "database", "writes orders", "api-writes-database"),
                new JsonContracts.LayoutEdge("api-publishes-job", "orders-api", "worker", "publishes fulfillment", "api-publishes-job"),
                new JsonContracts.LayoutEdge("worker-reads-database", "worker", "database", "loads order", "worker-reads-database")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("web-app", "orders-api", "worker"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("application-services"))),
                new JsonContracts.LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("external-dependencies")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutNode client = nodeById(result, "client");
        JsonContracts.LaidOutNode webApp = nodeById(result, "web-app");
        JsonContracts.LaidOutGroup application = groupById(result, "application-services");
        JsonContracts.LaidOutEdge submitsOrder = edgeById(result, "client-submits-order");
        JsonContracts.Point submitStart = submitsOrder.points().get(0);
        JsonContracts.Point submitEnd = submitsOrder.points().get(submitsOrder.points().size() - 1);
        double minX = result.nodes().stream().mapToDouble(JsonContracts.LaidOutNode::x).min().orElse(0.0);
        double maxX = result.nodes().stream().mapToDouble(node -> node.x() + node.width()).max().orElse(0.0);
        double minY = result.nodes().stream().mapToDouble(JsonContracts.LaidOutNode::y).min().orElse(0.0);
        double maxY = result.nodes().stream().mapToDouble(node -> node.y() + node.height()).max().orElse(0.0);
        double aspect = (maxX - minX) / (maxY - minY);

        assertTrue(
            Math.abs(centerY(client) - centerY(webApp)) < 4.0,
            "client-to-web cross-boundary flow should stay horizontally aligned");
        assertTrue(
            Math.abs(submitStart.y() - submitEnd.y()) < 4.0,
            "client-to-web route should not loop around the group");
        assertTrue(
            application.width() > application.height(),
            "application group should stay horizontal enough for cross-boundary routing");
        assertTrue(
            aspect < 4.2,
            "grouped rich pipeline should keep a bounded readable aspect ratio, aspect=" + aspect);
    }

    @Test
    void groupedInternalFanOutUsesRightwardServiceFlow() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("order-service", "Order Service", "order-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("catalog-service", "Catalog Service", "catalog-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("payment-service", "Payment Service", "payment-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("fulfillment-service", "Fulfillment Service", "fulfillment-service", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("order-checks-catalog", "order-service", "catalog-service", "checks catalog", "order-checks-catalog"),
                new JsonContracts.LayoutEdge("order-requests-payment", "order-service", "payment-service", "requests payment", "order-requests-payment"),
                new JsonContracts.LayoutEdge("order-reserves-stock", "order-service", "fulfillment-service", "reserves stock", "order-reserves-stock")),
            List.of(new JsonContracts.LayoutGroup(
                "core-services",
                "Core Services",
                List.of("order-service", "catalog-service", "payment-service", "fulfillment-service"),
                new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("core-services")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutNode orderService = nodeById(result, "order-service");
        JsonContracts.LaidOutEdge catalog = edgeById(result, "order-checks-catalog");
        JsonContracts.LaidOutEdge payment = edgeById(result, "order-requests-payment");
        JsonContracts.LaidOutEdge fulfillment = edgeById(result, "order-reserves-stock");

        for (String edgeId : List.of(
            "order-checks-catalog",
            "order-requests-payment",
            "order-reserves-stock")) {
            assertRouteEndpointOnSide(result, edgeId, "order-service", true, PortSide.EAST);
        }
        assertEquals(0, routeCrossingCountNearSource(catalog, payment, orderService));
        assertEquals(0, routeCrossingCountNearSource(catalog, fulfillment, orderService));
        assertEquals(0, routeCrossingCountNearSource(payment, fulfillment, orderService));
    }

    @Test
    void groupedPipelineProducesValidRoutesForMultipleOutgoingEdges() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new JsonContracts.LayoutNode("payments", "Payment Authorization Service", "payments", 160.0, 80.0),
                new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("api-writes-database", "orders-api", "database", "writes orders", "api-writes-database"),
                new JsonContracts.LayoutEdge("api-authorizes-payment", "orders-api", "payments", "requests payment authorization", "api-authorizes-payment")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("orders-api"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("application-services"))),
                new JsonContracts.LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("external-dependencies")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutEdge paymentEdge = result.edges().stream()
            .filter(edge -> edge.id().equals("api-authorizes-payment"))
            .findFirst()
            .orElseThrow();
        JsonContracts.LaidOutEdge databaseEdge = result.edges().stream()
            .filter(edge -> edge.id().equals("api-writes-database"))
            .findFirst()
            .orElseThrow();

        assertRouted(paymentEdge);
        assertRouted(databaseEdge);
        assertEquals(
            0,
            connectorThroughNodeCount(result),
            "multiple outgoing routes should avoid unrelated nodes");
    }

    @Test
    void threeShortSidePortsKeepTheDefaultNodeSize() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("gateway", "API Gateway", "gateway", 160.0, 80.0),
                new JsonContracts.LayoutNode("catalog", "Catalog", "catalog", 160.0, 80.0),
                new JsonContracts.LayoutNode("pricing", "Pricing", "pricing", 160.0, 80.0),
                new JsonContracts.LayoutNode("orders", "Orders", "orders", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge(
                    "gateway-catalog", "gateway", "catalog", "queries", "gateway-catalog"),
                new JsonContracts.LayoutEdge(
                    "gateway-pricing", "gateway", "pricing", "prices", "gateway-pricing"),
                new JsonContracts.LayoutEdge(
                    "gateway-orders", "gateway", "orders", "orders", "gateway-orders")),
            List.of(),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutNode gateway = nodeById(result, "gateway");

        assertEquals(
            80.0,
            gateway.height(),
            "typical nodes should fit three short-side ports before generated resizing");
    }

    @Test
    void groupedGatewayFanOutKeepsBoundedElkRoutes() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("web-frontend", "Web Frontend", "web-frontend", 160.0, 80.0),
                new JsonContracts.LayoutNode("api-gateway", "API Gateway", "api-gateway", 160.0, 112.0),
                new JsonContracts.LayoutNode("identity-service", "Identity Service", "identity-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("pricing-service", "Pricing Service", "pricing-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("order-service", "Order Service", "order-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("catalog-service", "Catalog Service", "catalog-service", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("web-calls-gateway", "web-frontend", "api-gateway", "calls", "web-calls-gateway"),
                new JsonContracts.LayoutEdge("gateway-authenticates", "api-gateway", "identity-service", "authenticates", "gateway-authenticates", "Serving"),
                new JsonContracts.LayoutEdge("gateway-prices-cart", "api-gateway", "pricing-service", "prices cart", "gateway-prices-cart", "Serving"),
                new JsonContracts.LayoutEdge("gateway-places-order", "api-gateway", "order-service", "places order", "gateway-places-order", "Serving"),
                new JsonContracts.LayoutEdge("gateway-queries-catalog", "api-gateway", "catalog-service", "queries catalog", "gateway-queries-catalog", "Serving")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "edge-platform",
                    "Edge Platform",
                    List.of("web-frontend", "api-gateway"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("edge-platform"))),
                new JsonContracts.LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of("identity-service", "pricing-service", "order-service", "catalog-service"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("core-services")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        assertEquals(
            0,
            connectorThroughNodeCount(result),
            "gateway fan-out routes should avoid unrelated service nodes");
        for (String edgeId : List.of(
            "gateway-authenticates",
            "gateway-prices-cart",
            "gateway-places-order",
            "gateway-queries-catalog")) {
            JsonContracts.LaidOutEdge edge = edgeById(result, edgeId);
            assertRouted(edge);
            int corners = cornerCount(edge.points());
            assertTrue(
                corners <= 4,
                edgeId + " should keep a bounded ELK-routed corner count, got "
                    + corners
                    + " corners, points="
                    + edge.points());
        }
    }

    @Test
    void groupedGatewayFanOutUsesMergedJunctionRoute() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("web-frontend", "Web Frontend", "web-frontend", 160.0, 80.0),
                new JsonContracts.LayoutNode("api-gateway", "API Gateway", "api-gateway", 160.0, 112.0),
                new JsonContracts.LayoutNode("identity-service", "Identity Service", "identity-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("pricing-service", "Pricing Service", "pricing-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("order-service", "Order Service", "order-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("catalog-service", "Catalog Service", "catalog-service", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("web-calls-gateway", "web-frontend", "api-gateway", "calls", "web-calls-gateway"),
                new JsonContracts.LayoutEdge("gateway-authenticates", "api-gateway", "identity-service", "authenticates", "gateway-authenticates", "Serving"),
                new JsonContracts.LayoutEdge("gateway-prices-cart", "api-gateway", "pricing-service", "prices cart", "gateway-prices-cart", "Serving"),
                new JsonContracts.LayoutEdge("gateway-places-order", "api-gateway", "order-service", "places order", "gateway-places-order", "Serving"),
                new JsonContracts.LayoutEdge("gateway-queries-catalog", "api-gateway", "catalog-service", "queries catalog", "gateway-queries-catalog", "Serving")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "edge-platform",
                    "Edge Platform",
                    List.of("web-frontend", "api-gateway"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("edge-platform"))),
                new JsonContracts.LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of("identity-service", "pricing-service", "order-service", "catalog-service"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("core-services")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        List<JsonContracts.LaidOutEdge> fanOutEdges = List.of(
            edgeById(result, "gateway-authenticates"),
            edgeById(result, "gateway-prices-cart"),
            edgeById(result, "gateway-places-order"),
            edgeById(result, "gateway-queries-catalog"));

        assertTrue(
            hasSharedInteriorRoutePoint(fanOutEdges),
            "same-source fan-out should use an ELK-merged junction route, edges=" + fanOutEdges);
        for (JsonContracts.LaidOutEdge edge : fanOutEdges) {
            assertEquals(
                List.of("shared_source_junction"),
                edge.routing_hints(),
                "same-source fan-out should advise renderers about the merged source junction");
        }
    }

    @Test
    void endpointMergingOffSuppressesSharedSourceHints() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("source", "Source", "source", 160.0, 80.0),
                new JsonContracts.LayoutNode("target-a", "Target A", "target-a", 160.0, 80.0),
                new JsonContracts.LayoutNode("target-b", "Target B", "target-b", 160.0, 80.0),
                new JsonContracts.LayoutNode("target-c", "Target C", "target-c", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("edge-a", "source", "target-a", "realizes", "edge-a", "Realization"),
                new JsonContracts.LayoutEdge("edge-b", "source", "target-b", "realizes", "edge-b", "Realization"),
                new JsonContracts.LayoutEdge("edge-c", "source", "target-c", "realizes", "edge-c", "Realization")),
            List.of(),
            List.of(),
            List.of(),
            new JsonContracts.LayoutPreferences(
                null,
                null,
                null,
                new JsonContracts.LayoutRoutingPreferences("orthogonal", null, "off")));

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        List<JsonContracts.LaidOutEdge> fanOutEdges = List.of(
            edgeById(result, "edge-a"),
            edgeById(result, "edge-b"),
            edgeById(result, "edge-c"));

        assertFalse(
            hasSharedInteriorRoutePoint(fanOutEdges),
            "endpoint_merging=off must avoid no-hint routes that still share an interior route point");
        for (JsonContracts.LaidOutEdge edge : fanOutEdges) {
            assertEquals(
                List.of(),
                edge.routing_hints(),
                "endpoint_merging=off must suppress shared source hints");
        }
    }

    @Test
    void spaciousDensityExpandsGeneratedNodeForExtraPorts() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("source", "Source", "source", 160.0, 80.0),
                new JsonContracts.LayoutNode("target-a", "Target A", "target-a", 160.0, 80.0),
                new JsonContracts.LayoutNode("target-b", "Target B", "target-b", 160.0, 80.0),
                new JsonContracts.LayoutNode("target-c", "Target C", "target-c", 160.0, 80.0),
                new JsonContracts.LayoutNode("target-d", "Target D", "target-d", 160.0, 80.0),
                new JsonContracts.LayoutNode("target-e", "Target E", "target-e", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("edge-a", "source", "target-a", "calls", "edge-a"),
                new JsonContracts.LayoutEdge("edge-b", "source", "target-b", "calls", "edge-b"),
                new JsonContracts.LayoutEdge("edge-c", "source", "target-c", "calls", "edge-c"),
                new JsonContracts.LayoutEdge("edge-d", "source", "target-d", "calls", "edge-d"),
                new JsonContracts.LayoutEdge("edge-e", "source", "target-e", "calls", "edge-e")),
            List.of(),
            List.of(),
            List.of(),
            new JsonContracts.LayoutPreferences(
                null,
                "spacious",
                null,
                new JsonContracts.LayoutRoutingPreferences("orthogonal", null, "off")));

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        JsonContracts.LaidOutNode source = nodeById(result, "source");
        assertTrue(
            source.height() >= 176.0,
            "spacious density should size five same-side ports using spacious spacing, height="
                + source.height());
    }

    @Test
    void groupedFanOutDoesNotMergeDifferentRelationshipTypes() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("api-gateway", "API Gateway", "api-gateway", 160.0, 112.0),
                new JsonContracts.LayoutNode("identity-service", "Identity Service", "identity-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("pricing-service", "Pricing Service", "pricing-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("order-service", "Order Service", "order-service", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("gateway-authenticates", "api-gateway", "identity-service", "authenticates", "gateway-authenticates", "Serving"),
                new JsonContracts.LayoutEdge("gateway-prices-cart", "api-gateway", "pricing-service", "prices cart", "gateway-prices-cart", "Serving"),
                new JsonContracts.LayoutEdge("gateway-places-order", "api-gateway", "order-service", "places order", "gateway-places-order", "Triggering")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "edge-platform",
                    "Edge Platform",
                    List.of("api-gateway"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("edge-platform"))),
                new JsonContracts.LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of("identity-service", "pricing-service", "order-service"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("core-services")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        for (String edgeId : List.of(
            "gateway-authenticates",
            "gateway-prices-cart",
            "gateway-places-order")) {
            JsonContracts.LaidOutEdge edge = edgeById(result, edgeId);
            assertEquals(
                List.of(),
                edge.routing_hints(),
                "mixed relationship types must not be counted together for endpoint merging");
        }
    }

    @Test
    void sameGroupInternalEdgesDoNotUseSharedEndpointMerge() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("api-gateway", "API Gateway", "api-gateway", 160.0, 112.0),
                new JsonContracts.LayoutNode("identity-service", "Identity Service", "identity-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("pricing-service", "Pricing Service", "pricing-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("order-service", "Order Service", "order-service", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("gateway-authenticates", "api-gateway", "identity-service", "authenticates", "gateway-authenticates", "Serving"),
                new JsonContracts.LayoutEdge("gateway-prices-cart", "api-gateway", "pricing-service", "prices cart", "gateway-prices-cart", "Serving"),
                new JsonContracts.LayoutEdge("gateway-places-order", "api-gateway", "order-service", "places order", "gateway-places-order", "Serving")),
            List.of(new JsonContracts.LayoutGroup(
                "core-services",
                "Core Services",
                List.of("api-gateway", "identity-service", "pricing-service", "order-service"),
                new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("core-services")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        for (String edgeId : List.of(
            "gateway-authenticates",
            "gateway-prices-cart",
            "gateway-places-order")) {
            JsonContracts.LaidOutEdge edge = edgeById(result, edgeId);
            assertEquals(
                List.of(),
                edge.routing_hints(),
                "same-group internal edges should not be converted into shared endpoint junctions");
        }
    }

    @Test
    void groupedSourcePortsFollowDivergingRouteChannelOrder() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("identity-service", "Identity Service", "identity-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("session-cache", "Session Cache", "session-cache", 160.0, 80.0),
                new JsonContracts.LayoutNode("identity-provider", "Identity Provider", "identity-provider", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("identity-federates", "identity-service", "identity-provider", "federates", "identity-federates"),
                new JsonContracts.LayoutEdge("identity-caches-session", "identity-service", "session-cache", "caches session", "identity-caches-session")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of("identity-service"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("core-services"))),
                new JsonContracts.LayoutGroup(
                    "data-platform",
                    "Data Platform",
                    List.of("session-cache"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("data-platform"))),
                new JsonContracts.LayoutGroup(
                    "external-systems",
                    "External Systems",
                    List.of("identity-provider"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("external-systems")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutNode source = nodeById(result, "identity-service");
        JsonContracts.LaidOutEdge cacheEdge = edgeById(result, "identity-caches-session");
        JsonContracts.LaidOutEdge federatesEdge = edgeById(result, "identity-federates");

        assertTrue(
            sourcePortY(federatesEdge) < sourcePortY(cacheEdge),
            "source ports should keep diverging rightward channels from crossing, cache="
                + cacheEdge.points()
                + ", federates="
                + federatesEdge.points());
        assertEquals(
            0,
            routeCrossingCountNearSource(federatesEdge, cacheEdge, source),
            "same-source routes should not cross immediately after leaving their source, cache="
                + cacheEdge.points()
                + ", federates="
                + federatesEdge.points());
    }

    @Test
    void groupedSameSourceDataRoutesDoNotCrossNearTheSource() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("pricing-service", "Pricing Service", "pricing-service", 160.0, 80.0),
                new JsonContracts.LayoutNode("session-cache", "Session Cache", "session-cache", 160.0, 80.0),
                new JsonContracts.LayoutNode("product-db", "Product DB", "product-db", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("pricing-reads-products", "pricing-service", "product-db", "reads products", "pricing-reads-products"),
                new JsonContracts.LayoutEdge("pricing-caches-quotes", "pricing-service", "session-cache", "caches quotes", "pricing-caches-quotes")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of("pricing-service"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("core-services"))),
                new JsonContracts.LayoutGroup(
                    "data-platform",
                    "Data Platform",
                    List.of("session-cache", "product-db"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("data-platform")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutNode source = nodeById(result, "pricing-service");
        JsonContracts.LaidOutEdge readsEdge = edgeById(result, "pricing-reads-products");
        JsonContracts.LaidOutEdge cacheEdge = edgeById(result, "pricing-caches-quotes");

        assertTrue(
            sourcePortY(readsEdge) < sourcePortY(cacheEdge),
            "source ports should keep lower data-channel routes below upper source channels, reads="
                + readsEdge.points()
                + ", cache="
                + cacheEdge.points());
        assertEquals(
            0,
            routeCrossingCountNearSource(readsEdge, cacheEdge, source),
            "same-source data routes should not cross immediately after leaving their source, reads="
                + readsEdge.points()
                + ", cache="
                + cacheEdge.points());
    }

    @Test
    void groupedTargetPortsFollowIncomingSourceOrder() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("customer-mobile", "Mobile App", "customer-mobile", 160.0, 80.0),
                new JsonContracts.LayoutNode("customer-web", "Web Customer", "customer-web", 160.0, 80.0),
                new JsonContracts.LayoutNode("support-agent", "Support Agent", "support-agent", 160.0, 80.0),
                new JsonContracts.LayoutNode("cdn", "CDN", "cdn", 160.0, 80.0),
                new JsonContracts.LayoutNode("web-frontend", "Web Frontend", "web-frontend", 160.0, 80.0),
                new JsonContracts.LayoutNode("admin-portal", "Admin Portal", "admin-portal", 160.0, 80.0),
                new JsonContracts.LayoutNode("api-gateway", "API Gateway", "api-gateway", 160.0, 80.0),
                new JsonContracts.LayoutNode("gateway-and-junction", "", "gateway-and-junction", 28.0, 28.0)),
            List.of(
                new JsonContracts.LayoutEdge("mobile-enters-cdn", "customer-mobile", "cdn", "uses", "mobile-enters-cdn", "Association"),
                new JsonContracts.LayoutEdge("web-enters-cdn", "customer-web", "cdn", "uses", "web-enters-cdn", "Association"),
                new JsonContracts.LayoutEdge("support-opens-admin", "support-agent", "admin-portal", "manages", "support-opens-admin"),
                new JsonContracts.LayoutEdge("cdn-serves-web", "cdn", "web-frontend", "serves", "cdn-serves-web"),
                new JsonContracts.LayoutEdge("web-calls-gateway", "web-frontend", "api-gateway", "calls", "web-calls-gateway"),
                new JsonContracts.LayoutEdge("admin-calls-gateway", "admin-portal", "api-gateway", "calls", "admin-calls-gateway"),
                new JsonContracts.LayoutEdge("gateway-to-and-junction", "api-gateway", "gateway-and-junction", "routes", "gateway-to-and-junction")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "users",
                    "Users",
                    List.of("customer-mobile", "customer-web", "support-agent"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("users"))),
                new JsonContracts.LayoutGroup(
                    "edge-platform",
                    "Edge Platform",
                    List.of("cdn", "web-frontend", "admin-portal", "api-gateway", "gateway-and-junction"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("edge-platform")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutNode mobile = nodeById(result, "customer-mobile");
        JsonContracts.LaidOutNode web = nodeById(result, "customer-web");
        JsonContracts.LaidOutEdge mobileEdge = edgeById(result, "mobile-enters-cdn");
        JsonContracts.LaidOutEdge webEdge = edgeById(result, "web-enters-cdn");

        assertRouteEndpointOnSide(result, "mobile-enters-cdn", "cdn", false, PortSide.WEST);
        assertRouteEndpointOnSide(result, "web-enters-cdn", "cdn", false, PortSide.WEST);
        assertEquals(List.of("shared_target_junction"), mobileEdge.routing_hints());
        assertEquals(List.of("shared_target_junction"), webEdge.routing_hints());
        assertTrue(
            sameVerticalOrder(centerY(web), centerY(mobile), targetPortY(webEdge), targetPortY(mobileEdge)),
            "same-target west ports should follow incoming source order to avoid CDN-side crossings, web="
                + webEdge.points()
                + ", mobile="
                + mobileEdge.points()
                + ", webNode="
                + web
                + ", mobileNode="
                + mobile);
    }

    @Test
    void groupedPipelineProducesValidRoutesForMultipleIncomingEdges() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new JsonContracts.LayoutNode("batch-worker", "Batch Worker", "batch-worker", 160.0, 80.0),
                new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("web-calls-api", "web-app", "orders-api", "calls API", "web-calls-api"),
                new JsonContracts.LayoutEdge("worker-updates-api", "batch-worker", "orders-api", "updates orders", "worker-updates-api")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "callers",
                    "Callers",
                    List.of("web-app", "batch-worker"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("callers"))),
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("orders-api"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("application-services")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutEdge webEdge = result.edges().stream()
            .filter(edge -> edge.id().equals("web-calls-api"))
            .findFirst()
            .orElseThrow();
        JsonContracts.LaidOutEdge workerEdge = result.edges().stream()
            .filter(edge -> edge.id().equals("worker-updates-api"))
            .findFirst()
            .orElseThrow();

        assertRouted(webEdge);
        assertRouted(workerEdge);
        assertEquals(
            0,
            connectorThroughNodeCount(result),
            "multiple incoming routes should avoid unrelated nodes");
    }

    @Test
    void groupedFanInUsesMergedJunctionRoute() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new JsonContracts.LayoutNode("batch-worker", "Batch Worker", "batch-worker", 160.0, 80.0),
                new JsonContracts.LayoutNode("support-console", "Support Console", "support-console", 160.0, 80.0),
                new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 112.0)),
            List.of(
                new JsonContracts.LayoutEdge("web-calls-api", "web-app", "orders-api", "calls API", "web-calls-api", "Serving"),
                new JsonContracts.LayoutEdge("worker-updates-api", "batch-worker", "orders-api", "updates orders", "worker-updates-api", "Serving"),
                new JsonContracts.LayoutEdge("console-reads-api", "support-console", "orders-api", "reads orders", "console-reads-api", "Serving")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "callers",
                    "Callers",
                    List.of("web-app", "batch-worker", "support-console"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("callers"))),
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("orders-api"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("application-services")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        List<JsonContracts.LaidOutEdge> fanInEdges = List.of(
            edgeById(result, "web-calls-api"),
            edgeById(result, "worker-updates-api"),
            edgeById(result, "console-reads-api"));

        assertTrue(
            hasSharedInteriorRoutePoint(fanInEdges),
            "same-target fan-in should use an ELK-merged junction route, edges=" + fanInEdges);
        for (JsonContracts.LaidOutEdge edge : fanInEdges) {
            assertEquals(
                List.of("shared_target_junction"),
                edge.routing_hints(),
                "same-target fan-in should advise renderers about the merged target junction");
        }
    }

    @Test
    void groupedPipelineAvoidsExcessiveCrossGroupRouteDetours() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("client", "Client", "client", 160.0, 80.0),
                new JsonContracts.LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new JsonContracts.LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
                new JsonContracts.LayoutNode("payments", "Payment Authorization Service", "payments", 160.0, 80.0),
                new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("client-submits-order", "client", "web-app", "submits order", "client-submits-order"),
                new JsonContracts.LayoutEdge("web-app-calls-api", "web-app", "orders-api", "calls API", "web-app-calls-api"),
                new JsonContracts.LayoutEdge("api-authorizes-payment", "orders-api", "payments", "requests payment authorization", "api-authorizes-payment"),
                new JsonContracts.LayoutEdge("api-writes-database", "orders-api", "database", "writes orders", "api-writes-database"),
                new JsonContracts.LayoutEdge("api-publishes-job", "orders-api", "worker", "publishes fulfillment", "api-publishes-job"),
                new JsonContracts.LayoutEdge("worker-reads-database", "worker", "database", "loads order", "worker-reads-database")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("web-app", "orders-api", "worker"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("application-services"))),
                new JsonContracts.LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("external-dependencies")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        assertEquals(
            0,
            excessiveRouteDetourCount(result),
            "grouped cross-boundary routes should not loop around the whole diagram");
    }

    @Test
    void groupedReverseCrossGroupEdgeKeepsBoundedElkDetour() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new JsonContracts.LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
                new JsonContracts.LayoutNode("payments", "Payment Authorization Service", "payments", 160.0, 80.0),
                new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("api-writes-database", "orders-api", "database", "writes orders", "api-writes-database"),
                new JsonContracts.LayoutEdge("payments-serves-api", "payments", "orders-api", "serves payment authorization", "payments-serves-api"),
                new JsonContracts.LayoutEdge("api-publishes-job", "orders-api", "worker", "publishes fulfillment", "api-publishes-job"),
                new JsonContracts.LayoutEdge("worker-reads-database", "worker", "database", "loads order", "worker-reads-database")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("orders-api", "worker"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("application-services"))),
                new JsonContracts.LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("external-dependencies")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutEdge paymentEdge = edgeById(result, "payments-serves-api");
        int detourCount = excessiveRouteDetourCount(result);

        assertRouted(paymentEdge);
        assertTrue(
            detourCount <= 1,
            "reverse cross-group routes should keep ELK detours bounded, got " + detourCount);
        assertEquals(
            0,
            connectorThroughNodeCount(result),
            "reverse cross-group routes should avoid unrelated member nodes");
        assertEquals(
            0,
            endpointBoundaryOverlapCount(result, paymentEdge),
            "ELK-routed reverse route should leave endpoint nodes before turning");
    }

    @Test
    void groupedCrossGroupEdgesFollowPreferredRootDirection() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("workflow-entry", "Workflow Entry", "workflow-entry", 160.0, 80.0),
                new JsonContracts.LayoutNode("workflow-return", "Workflow Return", "workflow-return", 160.0, 80.0),
                new JsonContracts.LayoutNode("worker-entry", "Worker Entry", "worker-entry", 160.0, 80.0),
                new JsonContracts.LayoutNode("worker-return", "Worker Return", "worker-return", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge(
                    "workflow-to-worker", "workflow-entry", "worker-entry", "dispatches", "workflow-to-worker"),
                new JsonContracts.LayoutEdge(
                    "worker-to-workflow", "worker-return", "workflow-return", "reports", "worker-to-workflow")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "workflow",
                    "Workflow",
                    List.of("workflow-entry", "workflow-return"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("workflow"))),
                new JsonContracts.LayoutGroup(
                    "worker",
                    "Worker",
                    List.of("worker-entry", "worker-return"),
                    new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("worker")))),
            List.of(),
            List.of(),
            new JsonContracts.LayoutPreferences("down", null, null, null));

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        assertRouteEndpointOnSide(result, "workflow-to-worker", "workflow-entry", true, PortSide.SOUTH);
        assertRouteEndpointOnSide(result, "workflow-to-worker", "worker-entry", false, PortSide.NORTH);
        assertRouteEndpointOnSide(result, "worker-to-workflow", "worker-return", true, PortSide.NORTH);
        assertRouteEndpointOnSide(result, "worker-to-workflow", "workflow-return", false, PortSide.SOUTH);
    }

    @Test
    void groupedConnectorEdgesKeepHorizontalFlowInsideVerticalGroups() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("event-bus", "Event Bus", "event-bus", 160.0, 80.0),
                new JsonContracts.LayoutNode(
                    "event-dispatch-or-junction",
                    "Event Dispatch",
                    "event-dispatch-or-junction",
                    28.0,
                    28.0),
                new JsonContracts.LayoutNode("order-worker", "Order Worker", "order-worker", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge(
                    "event-bus-to-or-junction",
                    "event-bus",
                    "event-dispatch-or-junction",
                    "dispatches",
                    "event-bus-to-or-junction"),
                new JsonContracts.LayoutEdge(
                    "event-bus-drives-order-worker",
                    "event-bus",
                    "order-worker",
                    "order event",
                    "event-bus-drives-order-worker")),
            List.of(new JsonContracts.LayoutGroup(
                "async-processing",
                "Async Processing",
                List.of("event-bus", "event-dispatch-or-junction", "order-worker"),
                new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("async-processing")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutEdge dispatchEdge = edgeById(result, "event-bus-to-or-junction");
        JsonContracts.LaidOutEdge orderEdge = edgeById(result, "event-bus-drives-order-worker");

        assertRouteEndpointOnSide(result, "event-bus-to-or-junction", "event-bus", true, PortSide.EAST);
        assertRouteEndpointOnSide(
            result,
            "event-bus-to-or-junction",
            "event-dispatch-or-junction",
            false,
            PortSide.WEST);
        assertRouteEndpointOnSide(result, "event-bus-drives-order-worker", "event-bus", true, PortSide.SOUTH);
        assertRouteEndpointOnSide(result, "event-bus-drives-order-worker", "order-worker", false, PortSide.NORTH);
        assertTrue(
            sourcePortY(dispatchEdge) < sourcePortY(orderEdge),
            "junction dispatch source port should be above the direct order branch, dispatch="
                + dispatchEdge.points()
                + ", order="
                + orderEdge.points());
    }

    private static void assertRouted(JsonContracts.LaidOutEdge edge) {
        assertTrue(
            edge.points().size() >= 2,
            "edge " + edge.id() + " should include ELK-generated route points");
    }

    private static JsonContracts.LaidOutNode nodeById(
        JsonContracts.LayoutResult result,
        String id) {
        return result.nodes().stream()
            .filter(node -> node.id().equals(id))
            .findFirst()
            .orElseThrow();
    }

    private static JsonContracts.LaidOutGroup groupById(
        JsonContracts.LayoutResult result,
        String id) {
        return result.groups().stream()
            .filter(group -> group.id().equals(id))
            .findFirst()
            .orElseThrow();
    }

    private static JsonContracts.LaidOutEdge edgeById(
        JsonContracts.LayoutResult result,
        String id) {
        return result.edges().stream()
            .filter(edge -> edge.id().equals(id))
            .findFirst()
            .orElseThrow();
    }

    private static void assertRouteEndpointOnSide(
        JsonContracts.LayoutResult result,
        String edgeId,
        String nodeId,
        boolean start,
        PortSide side) {
        JsonContracts.LaidOutEdge edge = edgeById(result, edgeId);
        JsonContracts.LaidOutNode node = nodeById(result, nodeId);
        assertTrue(edge.points().size() >= 2, "edge " + edgeId + " should have route endpoints");
        JsonContracts.Point point = start
            ? edge.points().get(0)
            : edge.points().get(edge.points().size() - 1);
        assertPointOnSide(point, node, side, edgeId + " endpoint for " + nodeId);
    }

    private static void assertPointOnSide(
        JsonContracts.Point point,
        JsonContracts.LaidOutNode node,
        PortSide side,
        String message) {
        switch (side) {
            case NORTH -> {
                assertEquals(node.y(), point.y(), PORT_SIDE_EPSILON, message);
                assertWithinHorizontalBounds(point, node, message);
            }
            case SOUTH -> {
                assertEquals(node.y() + node.height(), point.y(), PORT_SIDE_EPSILON, message);
                assertWithinHorizontalBounds(point, node, message);
            }
            case WEST -> {
                assertEquals(node.x(), point.x(), PORT_SIDE_EPSILON, message);
                assertWithinVerticalBounds(point, node, message);
            }
            case EAST -> {
                assertEquals(node.x() + node.width(), point.x(), PORT_SIDE_EPSILON, message);
                assertWithinVerticalBounds(point, node, message);
            }
            default -> throw new IllegalArgumentException("unsupported side " + side);
        }
    }

    private static boolean usesDifferentSourceSides(
        JsonContracts.LayoutResult result,
        String firstEdgeId,
        String secondEdgeId,
        String nodeId) {
        JsonContracts.LaidOutNode node = nodeById(result, nodeId);
        PortSide firstSide = routeEndpointSide(edgeById(result, firstEdgeId).points().get(0), node);
        PortSide secondSide = routeEndpointSide(edgeById(result, secondEdgeId).points().get(0), node);
        return firstSide != null && secondSide != null && firstSide != secondSide;
    }

    private static PortSide routeEndpointSide(
        JsonContracts.Point point,
        JsonContracts.LaidOutNode node) {
        if (Math.abs(node.y() - point.y()) <= PORT_SIDE_EPSILON
            && point.x() >= node.x() - GEOMETRY_EPSILON
            && point.x() <= node.x() + node.width() + GEOMETRY_EPSILON) {
            return PortSide.NORTH;
        }
        if (Math.abs(node.y() + node.height() - point.y()) <= PORT_SIDE_EPSILON
            && point.x() >= node.x() - GEOMETRY_EPSILON
            && point.x() <= node.x() + node.width() + GEOMETRY_EPSILON) {
            return PortSide.SOUTH;
        }
        if (Math.abs(node.x() - point.x()) <= PORT_SIDE_EPSILON
            && point.y() >= node.y() - GEOMETRY_EPSILON
            && point.y() <= node.y() + node.height() + GEOMETRY_EPSILON) {
            return PortSide.WEST;
        }
        if (Math.abs(node.x() + node.width() - point.x()) <= PORT_SIDE_EPSILON
            && point.y() >= node.y() - GEOMETRY_EPSILON
            && point.y() <= node.y() + node.height() + GEOMETRY_EPSILON) {
            return PortSide.EAST;
        }
        return null;
    }

    private static void assertWithinHorizontalBounds(
        JsonContracts.Point point,
        JsonContracts.LaidOutNode node,
        String message) {
        assertTrue(
            point.x() >= node.x() - GEOMETRY_EPSILON
                && point.x() <= node.x() + node.width() + GEOMETRY_EPSILON,
            message + " should be within horizontal node bounds, point=" + point + ", node=" + node);
    }

    private static void assertWithinVerticalBounds(
        JsonContracts.Point point,
        JsonContracts.LaidOutNode node,
        String message) {
        assertTrue(
            point.y() >= node.y() - GEOMETRY_EPSILON
                && point.y() <= node.y() + node.height() + GEOMETRY_EPSILON,
            message + " should be within vertical node bounds, point=" + point + ", node=" + node);
    }

    private static double sourcePortY(JsonContracts.LaidOutEdge edge) {
        return edge.points().get(0).y();
    }

    private static double targetPortY(JsonContracts.LaidOutEdge edge) {
        return edge.points().get(edge.points().size() - 1).y();
    }

    private static boolean sameVerticalOrder(
        double firstSourceY,
        double secondSourceY,
        double firstTargetY,
        double secondTargetY) {
        if (sameCoordinate(firstSourceY, secondSourceY)
            || sameCoordinate(firstTargetY, secondTargetY)) {
            return true;
        }
        return Double.compare(firstSourceY, secondSourceY)
            == Double.compare(firstTargetY, secondTargetY);
    }

    private static boolean sameCoordinate(double left, double right) {
        return Math.abs(left - right) <= GEOMETRY_EPSILON;
    }

    private static double centerY(JsonContracts.LaidOutNode node) {
        return node.y() + node.height() / 2.0;
    }

    private static void assertGroupContainsMembers(
        JsonContracts.LayoutResult result,
        JsonContracts.LaidOutGroup group) {
        for (String memberId : group.members()) {
            JsonContracts.LaidOutNode member = result.nodes().stream()
                .filter(node -> node.id().equals(memberId))
                .findFirst()
                .orElseThrow();
            assertTrue(
                member.x() >= group.x()
                    && member.y() >= group.y()
                    && member.x() + member.width() <= group.x() + group.width()
                    && member.y() + member.height() <= group.y() + group.height(),
                "group " + group.id() + " should contain member " + memberId);
        }
    }

    @Test
    void groupedCrossGroupEdgeDoesNotRouteThroughUnrelatedGroupMember() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("a", "A", "a", 160.0, 80.0),
                new JsonContracts.LayoutNode("b", "B", "b", 160.0, 80.0),
                new JsonContracts.LayoutNode("c", "C", "c", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("a-to-b", "a", "b", "internal", "a-to-b"),
                new JsonContracts.LayoutEdge("a-to-c", "a", "c", "connects", "a-to-c")),
            List.of(new JsonContracts.LayoutGroup(
                "group",
                "Group",
                List.of("a", "b"),
                new JsonContracts.GroupProvenance(null, new JsonContracts.SemanticBacked("group")))),
            List.of(),
            List.of(),
            null);

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        assertEquals(
            0,
            connectorThroughNodeCount(result),
            "cross-group routes should avoid unrelated member nodes");
    }

    private static int connectorThroughNodeCount(JsonContracts.LayoutResult result) {
        int count = 0;
        for (JsonContracts.LaidOutEdge edge : result.edges()) {
            for (int index = 0; index < edge.points().size() - 1; index++) {
                JsonContracts.Point start = edge.points().get(index);
                JsonContracts.Point end = edge.points().get(index + 1);
                for (JsonContracts.LaidOutNode node : result.nodes()) {
                    if (!node.id().equals(edge.source())
                        && !node.id().equals(edge.target())
                        && segmentIntersectsRect(start, end, node)) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }

    private static int excessiveRouteDetourCount(JsonContracts.LayoutResult result) {
        int count = 0;
        for (JsonContracts.LaidOutEdge edge : result.edges()) {
            if (edge.points().size() < 2) {
                continue;
            }
            double routeLength = routeLength(edge.points());
            JsonContracts.Point start = edge.points().get(0);
            JsonContracts.Point end = edge.points().get(edge.points().size() - 1);
            double directLength = Math.abs(start.x() - end.x()) + Math.abs(start.y() - end.y());
            if (directLength > 0.0 && routeLength > directLength * 1.5 && routeLength - directLength > 240.0) {
                count++;
            }
        }
        return count;
    }

    private static int routeCrossingCountNearSource(
        JsonContracts.LaidOutEdge first,
        JsonContracts.LaidOutEdge second,
        JsonContracts.LaidOutNode source) {
        int count = 0;
        double nearSourceRight = source.x() + source.width() + 160.0;
        for (int firstIndex = 0; firstIndex < first.points().size() - 1; firstIndex++) {
            JsonContracts.Point firstStart = first.points().get(firstIndex);
            JsonContracts.Point firstEnd = first.points().get(firstIndex + 1);
            if (Math.min(firstStart.x(), firstEnd.x()) > nearSourceRight) {
                continue;
            }
            for (int secondIndex = 0; secondIndex < second.points().size() - 1; secondIndex++) {
                JsonContracts.Point secondStart = second.points().get(secondIndex);
                JsonContracts.Point secondEnd = second.points().get(secondIndex + 1);
                if (Math.min(secondStart.x(), secondEnd.x()) > nearSourceRight) {
                    continue;
                }
                if (segmentsCross(firstStart, firstEnd, secondStart, secondEnd)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean segmentsCross(
        JsonContracts.Point firstStart,
        JsonContracts.Point firstEnd,
        JsonContracts.Point secondStart,
        JsonContracts.Point secondEnd) {
        RouteOrientation firstOrientation = routeOrientation(firstStart, firstEnd);
        RouteOrientation secondOrientation = routeOrientation(secondStart, secondEnd);
        if (firstOrientation == null
            || secondOrientation == null
            || firstOrientation == secondOrientation) {
            return false;
        }
        JsonContracts.Point horizontalStart =
            firstOrientation == RouteOrientation.HORIZONTAL ? firstStart : secondStart;
        JsonContracts.Point horizontalEnd =
            firstOrientation == RouteOrientation.HORIZONTAL ? firstEnd : secondEnd;
        JsonContracts.Point verticalStart =
            firstOrientation == RouteOrientation.VERTICAL ? firstStart : secondStart;
        JsonContracts.Point verticalEnd =
            firstOrientation == RouteOrientation.VERTICAL ? firstEnd : secondEnd;
        double minHorizontalX = Math.min(horizontalStart.x(), horizontalEnd.x());
        double maxHorizontalX = Math.max(horizontalStart.x(), horizontalEnd.x());
        double minVerticalY = Math.min(verticalStart.y(), verticalEnd.y());
        double maxVerticalY = Math.max(verticalStart.y(), verticalEnd.y());
        double crossingX = verticalStart.x();
        double crossingY = horizontalStart.y();
        return crossingX > minHorizontalX + GEOMETRY_EPSILON
            && crossingX < maxHorizontalX - GEOMETRY_EPSILON
            && crossingY > minVerticalY + GEOMETRY_EPSILON
            && crossingY < maxVerticalY - GEOMETRY_EPSILON;
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
            JsonContracts.Point start = points.get(index);
            JsonContracts.Point end = points.get(index + 1);
            RouteOrientation current = routeOrientation(start, end);
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

    private static boolean hasSharedInteriorRoutePoint(List<JsonContracts.LaidOutEdge> edges) {
        for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
            JsonContracts.LaidOutEdge edge = edges.get(edgeIndex);
            for (int pointIndex = 1; pointIndex < edge.points().size() - 1; pointIndex++) {
                JsonContracts.Point point = edge.points().get(pointIndex);
                for (int otherIndex = edgeIndex + 1; otherIndex < edges.size(); otherIndex++) {
                    if (containsInteriorPoint(edges.get(otherIndex), point)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsInteriorPoint(
        JsonContracts.LaidOutEdge edge,
        JsonContracts.Point candidate) {
        for (int index = 1; index < edge.points().size() - 1; index++) {
            if (samePoint(edge.points().get(index), candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean samePoint(
        JsonContracts.Point left,
        JsonContracts.Point right) {
        return Math.abs(left.x() - right.x()) <= 0.001
            && Math.abs(left.y() - right.y()) <= 0.001;
    }

    private enum RouteOrientation {
        HORIZONTAL,
        VERTICAL
    }

    private static RouteOrientation routeOrientation(
        JsonContracts.Point start,
        JsonContracts.Point end) {
        if (Double.compare(start.y(), end.y()) == 0
            && Double.compare(start.x(), end.x()) != 0) {
            return RouteOrientation.HORIZONTAL;
        }
        if (Double.compare(start.x(), end.x()) == 0
            && Double.compare(start.y(), end.y()) != 0) {
            return RouteOrientation.VERTICAL;
        }
        return null;
    }

    private static int endpointBoundaryOverlapCount(
        JsonContracts.LayoutResult result,
        JsonContracts.LaidOutEdge edge) {
        return boundaryOverlapCount(nodeById(result, edge.source()), edge.points())
            + boundaryOverlapCount(nodeById(result, edge.target()), edge.points());
    }

    private static int boundaryOverlapCount(
        JsonContracts.LaidOutNode node,
        List<JsonContracts.Point> points) {
        int count = 0;
        for (int index = 0; index < points.size() - 1; index++) {
            JsonContracts.Point start = points.get(index);
            JsonContracts.Point end = points.get(index + 1);
            if (segmentOverlapsBoundary(start, end, node)) {
                count++;
            }
        }
        return count;
    }

    private static boolean segmentOverlapsBoundary(
        JsonContracts.Point start,
        JsonContracts.Point end,
        JsonContracts.LaidOutNode node) {
        double right = node.x() + node.width();
        double bottom = node.y() + node.height();
        if (Double.compare(start.x(), end.x()) == 0
            && (Double.compare(start.x(), node.x()) == 0 || Double.compare(start.x(), right) == 0)) {
            return overlapLength(start.y(), end.y(), node.y(), bottom) > 1.0;
        }
        if (Double.compare(start.y(), end.y()) == 0
            && (Double.compare(start.y(), node.y()) == 0 || Double.compare(start.y(), bottom) == 0)) {
            return overlapLength(start.x(), end.x(), node.x(), right) > 1.0;
        }
        return false;
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
}

package dev.dediren.elk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ElkLayoutEngineTest {
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
            List.of());

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
                new JsonContracts.GroupProvenance(
                    new JsonContracts.SemanticBacked("system-group")))),
            List.of(),
            List.of());

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
    void groupedMembersProduceGroupBoundsAroundGeneratedNodeGeometry() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new JsonContracts.LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
                new JsonContracts.LayoutNode("payments", "Payments Provider", "payments", 160.0, 80.0),
                new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge(
                    "web-app-calls-api", "web-app", "orders-api", "calls API", "web-app-calls-api"),
                new JsonContracts.LayoutEdge(
                    "api-authorizes-payment", "orders-api", "payments", "authorizes payment", "api-authorizes-payment"),
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
                    new JsonContracts.GroupProvenance(
                        new JsonContracts.SemanticBacked("application-services"))),
                new JsonContracts.LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    new JsonContracts.GroupProvenance(
                        new JsonContracts.SemanticBacked("external-dependencies")))),
            List.of(),
            List.of());

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
                new JsonContracts.LayoutNode("payments", "Payments Provider", "payments", 160.0, 80.0),
                new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("client-submits-order", "client", "web-app", "submits order", "client-submits-order"),
                new JsonContracts.LayoutEdge("web-app-calls-api", "web-app", "orders-api", "calls API", "web-app-calls-api"),
                new JsonContracts.LayoutEdge("api-authorizes-payment", "orders-api", "payments", "authorizes payment", "api-authorizes-payment"),
                new JsonContracts.LayoutEdge("api-writes-database", "orders-api", "database", "writes orders", "api-writes-database"),
                new JsonContracts.LayoutEdge("api-publishes-job", "orders-api", "worker", "publishes fulfillment", "api-publishes-job"),
                new JsonContracts.LayoutEdge("worker-reads-database", "worker", "database", "loads order", "worker-reads-database")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("web-app", "orders-api", "worker"),
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("application-services"))),
                new JsonContracts.LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("external-dependencies")))),
            List.of(),
            List.of());

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
    void groupedPipelineProducesValidRoutesForMultipleOutgoingEdges() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new JsonContracts.LayoutNode("payments", "Payments Provider", "payments", 160.0, 80.0),
                new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("api-writes-database", "orders-api", "database", "writes orders", "api-writes-database"),
                new JsonContracts.LayoutEdge("api-authorizes-payment", "orders-api", "payments", "authorizes payment", "api-authorizes-payment")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("orders-api"),
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("application-services"))),
                new JsonContracts.LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("external-dependencies")))),
            List.of(),
            List.of());

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
            List.of());

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutNode gateway = nodeById(result, "gateway");

        assertEquals(
            80.0,
            gateway.height(),
            "typical nodes should fit three short-side ports before generated resizing");
    }

    @Test
    void groupedGatewayFanOutKeepsCompactDoglegs() {
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
                new JsonContracts.LayoutEdge("gateway-authenticates", "api-gateway", "identity-service", "authenticates", "gateway-authenticates"),
                new JsonContracts.LayoutEdge("gateway-prices-cart", "api-gateway", "pricing-service", "prices cart", "gateway-prices-cart"),
                new JsonContracts.LayoutEdge("gateway-places-order", "api-gateway", "order-service", "places order", "gateway-places-order"),
                new JsonContracts.LayoutEdge("gateway-queries-catalog", "api-gateway", "catalog-service", "queries catalog", "gateway-queries-catalog")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "edge-platform",
                    "Edge Platform",
                    List.of("web-frontend", "api-gateway"),
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("edge-platform"))),
                new JsonContracts.LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of("identity-service", "pricing-service", "order-service", "catalog-service"),
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("core-services")))),
            List.of(),
            List.of());

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        assertEquals(
            0,
            connectorThroughNodeCount(result),
            "compact gateway fan-out routes should still avoid unrelated service nodes");
        for (String edgeId : List.of(
            "gateway-authenticates",
            "gateway-prices-cart",
            "gateway-places-order",
            "gateway-queries-catalog")) {
            JsonContracts.LaidOutEdge edge = edgeById(result, edgeId);
            assertTrue(
                cornerCount(edge.points()) <= 2,
                edgeId + " should use a compact dogleg, points=" + edge.points());
        }
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
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("callers"))),
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("orders-api"),
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("application-services")))),
            List.of(),
            List.of());

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
    void groupedPipelineAvoidsExcessiveCrossGroupRouteDetours() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("client", "Client", "client", 160.0, 80.0),
                new JsonContracts.LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new JsonContracts.LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
                new JsonContracts.LayoutNode("payments", "Payments Provider", "payments", 160.0, 80.0),
                new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("client-submits-order", "client", "web-app", "submits order", "client-submits-order"),
                new JsonContracts.LayoutEdge("web-app-calls-api", "web-app", "orders-api", "calls API", "web-app-calls-api"),
                new JsonContracts.LayoutEdge("api-authorizes-payment", "orders-api", "payments", "authorizes payment", "api-authorizes-payment"),
                new JsonContracts.LayoutEdge("api-writes-database", "orders-api", "database", "writes orders", "api-writes-database"),
                new JsonContracts.LayoutEdge("api-publishes-job", "orders-api", "worker", "publishes fulfillment", "api-publishes-job"),
                new JsonContracts.LayoutEdge("worker-reads-database", "worker", "database", "loads order", "worker-reads-database")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("web-app", "orders-api", "worker"),
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("application-services"))),
                new JsonContracts.LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("external-dependencies")))),
            List.of(),
            List.of());

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        assertEquals(
            0,
            excessiveRouteDetourCount(result),
            "grouped cross-boundary routes should not loop around the whole diagram");
    }

    @Test
    void groupedReverseCrossGroupEdgeAvoidsExcessiveDetour() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new JsonContracts.LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
                new JsonContracts.LayoutNode("payments", "Payments Provider", "payments", 160.0, 80.0),
                new JsonContracts.LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new JsonContracts.LayoutEdge("api-writes-database", "orders-api", "database", "writes orders", "api-writes-database"),
                new JsonContracts.LayoutEdge("payments-serves-api", "payments", "orders-api", "authorizes payment", "payments-serves-api"),
                new JsonContracts.LayoutEdge("api-publishes-job", "orders-api", "worker", "publishes fulfillment", "api-publishes-job"),
                new JsonContracts.LayoutEdge("worker-reads-database", "worker", "database", "loads order", "worker-reads-database")),
            List.of(
                new JsonContracts.LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("orders-api", "worker"),
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("application-services"))),
                new JsonContracts.LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("external-dependencies")))),
            List.of(),
            List.of());

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
        JsonContracts.LaidOutEdge paymentEdge = edgeById(result, "payments-serves-api");

        assertEquals(
            0,
            excessiveRouteDetourCount(result),
            "reverse cross-group route should be normalized when ELK routes it around the diagram");
        assertEquals(
            0,
            endpointBoundaryOverlapCount(result, paymentEdge),
            "normalized reverse route should leave endpoint nodes before turning");
        assertTrue(paymentEdge.points().size() <= 6, "normalized reverse route should stay compact");
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
                new JsonContracts.GroupProvenance(new JsonContracts.SemanticBacked("group")))),
            List.of(),
            List.of());

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

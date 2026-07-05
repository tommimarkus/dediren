package dev.dediren.plugins.elklayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dediren.contracts.layout.*;
import dev.dediren.contracts.layout.LayoutAlgorithm;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.elk.alg.layered.options.CrossingMinimizationStrategy;
import org.eclipse.elk.alg.layered.options.CycleBreakingStrategy;
import org.eclipse.elk.alg.layered.options.EdgeStraighteningStrategy;
import org.eclipse.elk.alg.layered.options.GraphCompactionStrategy;
import org.eclipse.elk.alg.layered.options.GreedySwitchType;
import org.eclipse.elk.alg.layered.options.LayerConstraint;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.alg.layered.options.LayeringStrategy;
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy;
import org.eclipse.elk.alg.layered.options.OrderingStrategy;
import org.eclipse.elk.alg.layered.options.PortSortingStrategy;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.options.PortSide;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.junit.jupiter.api.Test;

class ElkLayoutEngineTest {
  private static final double GEOMETRY_EPSILON = 0.001;
  private static final double PORT_SIDE_EPSILON = 1.0;

  // Tolerance for calling a route segment axis-aligned. These fixtures use the default ORTHOGONAL
  // routing style (polyline and spline exist but are not requested here), so every segment must
  // move
  // along exactly one axis. A
  // real diagonal jog is tens of pixels off both axes; this tolerance only absorbs sub-pixel float
  // drift and never lets a genuine diagonal through.
  private static final double ORTHOGONAL_TOLERANCE = 0.5;

  // Architectural fitness function for the ELK-first rule (CLAUDE.md): the helper must not
  // re-implement post-ELK route geometry. Intentionally a source-text guard, not behavioral —
  // accepted per the 2026-06-07 test-quality audit. The geometry-outcome tests in this class
  // (route-through-node count, corner counts) prove the rendered result; this guard prevents
  // the prohibited code from being added in the first place.
  @Test
  void elkHelperDoesNotOwnPostElkRouteGeometry() throws IOException {
    String source =
        Files.readString(
            Path.of("src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java"));

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
    LayoutPreferences preferences =
        new LayoutPreferences(
            null,
            null,
            null,
            new LayoutRoutingPreferences(
                LayoutRoutingStyle.ORTHOGONAL, null, LayoutEndpointMerging.OFF));

    ElkNode root = ElkLayeredOptions.configuredRoot(Direction.RIGHT, preferences);

    assertEquals(false, root.getProperty(LayeredOptions.MERGE_EDGES));
    assertEquals(false, root.getProperty(LayeredOptions.MERGE_HIERARCHY_EDGES));
  }

  @Test
  void layeredRootUsesElkFirstOrderingAndStraighteningOptions() {
    ElkNode root = ElkLayeredOptions.configuredRoot(Direction.RIGHT, null);

    assertEquals(
        PortSortingStrategy.INPUT_ORDER, root.getProperty(LayeredOptions.PORT_SORTING_STRATEGY));
    assertEquals(
        OrderingStrategy.PREFER_EDGES,
        root.getProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY));
    assertEquals(true, root.getProperty(LayeredOptions.CONSIDER_MODEL_ORDER_PORT_MODEL_ORDER));
    assertEquals(
        NodePlacementStrategy.BRANDES_KOEPF,
        root.getProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY));
    assertEquals(
        EdgeStraighteningStrategy.IMPROVE_STRAIGHTNESS,
        root.getProperty(LayeredOptions.NODE_PLACEMENT_BK_EDGE_STRAIGHTENING));
    assertEquals(true, root.getProperty(LayeredOptions.UNNECESSARY_BENDPOINTS));
  }

  @Test
  void layeredLayoutPlacesTargetToTheRightAndRoutesTheEdge() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("client", "Client", "client", 160.0, 80.0),
                new LayoutNode("api", "API", "api", 160.0, 80.0)),
            List.of(
                new LayoutEdge("client-calls-api", "client", "api", "calls", "client-calls-api")),
            List.of(),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    LaidOutNode client =
        result.nodes().stream()
            .filter(node -> node.id().equals("client"))
            .findFirst()
            .orElseThrow();
    LaidOutNode api =
        result.nodes().stream().filter(node -> node.id().equals("api")).findFirst().orElseThrow();
    LaidOutEdge edge = result.edges().get(0);

    assertEquals("layout-result.schema.v1", result.layoutResultSchemaVersion());
    assertEquals("main", result.viewId());
    assertEquals("client", client.sourceId());
    assertEquals("api", api.projectionId());
    assertEquals("client-calls-api", edge.sourceId());
    assertEquals("client-calls-api", edge.projectionId());
    assertTrue(api.x() > client.x(), "layered layout should place target after source");
    assertTrue(edge.points().size() >= 2, "layout must include start and end points");
    assertEquals(List.of(), result.warnings());
  }

  @Test
  void laysOutSequenceLifelinesInConstraintOrder() {
    LayoutResult result = new ElkLayoutEngine().layout(sequenceLayoutRequest());
    ElkLayoutRenderArtifacts.write(result);
    LaidOutNode customer = nodeById(result, "customer");
    LaidOutNode service = nodeById(result, "service");

    assertTrue(
        customer.x() < service.x(),
        "sequence lifelines should follow constraint order, customer="
            + customer
            + ", service="
            + service);
    assertEquals(
        customer.y(),
        service.y(),
        PORT_SIDE_EPSILON,
        "sequence lifeline heads should stay in one horizontal band");
  }

  @Test
  void sequenceLayoutPreservesLifelineRoleOnNodes() {
    LayoutResult result = new ElkLayoutEngine().layout(sequenceLayoutRequest());

    assertEquals("lifeline", nodeById(result, "customer").role(), "customer lifeline role");
    assertEquals("lifeline", nodeById(result, "service").role(), "service lifeline role");
    assertNull(
        nodeById(result, "interaction-place-order").role(),
        "interaction frame should stay role-less");
  }

  @Test
  void laysOutSequenceMessagesInConstraintOrder() {
    LayoutResult result = new ElkLayoutEngine().layout(sequenceLayoutRequest());
    ElkLayoutRenderArtifacts.write(result);
    double firstMessageY = firstSegmentY(edgeById(result, "m1"));
    double secondMessageY = firstSegmentY(edgeById(result, "m2"));
    double thirdMessageY = firstSegmentY(edgeById(result, "m3"));

    assertTrue(
        firstMessageY < secondMessageY && secondMessageY < thirdMessageY,
        "sequence message routes should follow constraint order, m1="
            + edgeById(result, "m1").points()
            + ", m2="
            + edgeById(result, "m2").points()
            + ", m3="
            + edgeById(result, "m3").points());
    assertSequenceRouteEndpointOnLifelineSide(result, "m1", "customer", true, PortSide.EAST);
    assertSequenceRouteEndpointOnLifelineSide(result, "m1", "service", false, PortSide.WEST);
    assertSequenceRouteEndpointOnLifelineSide(result, "m2", "service", true, PortSide.WEST);
    assertSequenceRouteEndpointOnLifelineSide(result, "m2", "customer", false, PortSide.EAST);
    assertSequenceRouteEndpointOnLifelineSide(result, "m3", "service", true, PortSide.WEST);
    assertSequenceRouteEndpointOnLifelineSide(result, "m3", "customer", false, PortSide.EAST);
  }

  @Test
  void laysOutSequenceMessagesBelowLifelineHeads() {
    LayoutResult result = new ElkLayoutEngine().layout(sequenceLayoutRequest());
    ElkLayoutRenderArtifacts.write(result);
    List<LaidOutNode> lifelines =
        List.of(nodeById(result, "customer"), nodeById(result, "service"));

    for (String edgeId : List.of("m1", "m2", "m3")) {
      double messageY = firstSegmentY(edgeById(result, edgeId));
      for (LaidOutNode lifeline : lifelines) {
        assertTrue(
            messageY > lifeline.y() + lifeline.height(),
            edgeId
                + " should be routed below lifeline heads, y="
                + messageY
                + ", lifeline="
                + lifeline);
      }
    }
  }

  @Test
  void normalizesSequenceMessagesToCleanHorizontalSegments() {
    LayoutResult normalized =
        SequenceLayoutConstraints.from(sequenceLayoutRequest())
            .normalize(sequenceLayoutResultWithMessageBendPoints());
    LaidOutEdge edge = edgeById(normalized, "m1");
    double messageY = firstSegmentY(edge);

    assertEquals(
        2,
        edge.points().size(),
        "lifeline-to-lifeline sequence messages should render as direct horizontal segments");
    assertEquals(240.0, edge.points().getFirst().x(), GEOMETRY_EPSILON);
    assertEquals(520.0, edge.points().getLast().x(), GEOMETRY_EPSILON);
    for (Point point : edge.points()) {
      assertEquals(
          messageY, point.y(), GEOMETRY_EPSILON, "all message route points should share y");
    }
    assertSequenceRouteEndpointOnLifelineSide(normalized, "m1", "customer", true, PortSide.EAST);
    assertSequenceRouteEndpointOnLifelineSide(normalized, "m1", "service", false, PortSide.WEST);
  }

  @Test
  void ignoresSequenceConstraintsForNonSequenceGraphs() {
    LayoutRequest unconstrained = genericTwoNodeRequest(List.of());
    LayoutRequest constrained =
        genericTwoNodeRequest(
            List.of(
                new LayoutConstraint(
                    "main.uml.sequence.lifeline-order",
                    "uml.sequence.lifeline-order",
                    List.of("service", "customer"))));

    LayoutResult baseline = new ElkLayoutEngine().layout(unconstrained);
    LayoutResult constrainedResult = new ElkLayoutEngine().layout(constrained);
    ElkLayoutRenderArtifacts.write(constrainedResult);

    assertEquals(
        nodeById(baseline, "customer").x(),
        nodeById(constrainedResult, "customer").x(),
        GEOMETRY_EPSILON,
        "partial sequence constraints must not affect ordinary graph layout");
    assertEquals(
        nodeById(baseline, "service").x(),
        nodeById(constrainedResult, "service").x(),
        GEOMETRY_EPSILON,
        "partial sequence constraints must not affect ordinary graph layout");
  }

  @Test
  void ignoresMessageOnlySequenceConstraintsForNonSequenceGraphs() {
    LayoutRequest unconstrained = genericTwoNodeRequest(List.of());
    LayoutRequest constrained =
        genericTwoNodeRequest(
            List.of(
                new LayoutConstraint(
                    "main.uml.sequence.message-order",
                    "uml.sequence.message-order",
                    List.of("customer-calls-service"))));

    LayoutResult baseline = new ElkLayoutEngine().layout(unconstrained);
    LayoutResult constrainedResult = new ElkLayoutEngine().layout(constrained);
    ElkLayoutRenderArtifacts.write(constrainedResult);

    assertEquals(
        nodeById(baseline, "customer").x(),
        nodeById(constrainedResult, "customer").x(),
        GEOMETRY_EPSILON,
        "message-only sequence constraints must not affect ordinary graph layout");
    assertEquals(
        nodeById(baseline, "service").x(),
        nodeById(constrainedResult, "service").x(),
        GEOMETRY_EPSILON,
        "message-only sequence constraints must not affect ordinary graph layout");
  }

  @Test
  void sequenceDanglingEdgeWarningUsesOriginalRequestEdgeIndex() {
    LayoutResult result = new ElkLayoutEngine().layout(sequenceLayoutRequestWithDanglingMessage());

    assertTrue(
        result.warnings().stream()
            .anyMatch(
                warning ->
                    warning.code().equals("DEDIREN_ELK_DANGLING_EDGE")
                        && warning.path().equals("$.edges[1]")),
        "sequence edge ordering must not change warning paths, warnings=" + result.warnings());
  }

  @Test
  void sequenceLifelinesEachGetADistinctColumnWhenElkCollapsesLayers() {
    // Regression for #29: ELK's RIGHT-direction layered pass can assign two lifelines to the
    // same layer, giving them an identical x. Every lifeline must still occupy its own column
    // in declared order, and messages must span their two columns left-to-right.
    List<String> order = List.of("user", "storefront", "orderservice", "payment", "inventory");
    LayoutResult result = new ElkLayoutEngine().layout(fiveLifelineSequenceRequest(order));
    ElkLayoutRenderArtifacts.write(result);

    List<LaidOutNode> lifelines = order.stream().map(id -> nodeById(result, id)).toList();
    double bandY = lifelines.getFirst().y();
    for (int leftIndex = 0; leftIndex < lifelines.size(); leftIndex++) {
      LaidOutNode left = lifelines.get(leftIndex);
      assertEquals(
          bandY,
          left.y(),
          PORT_SIDE_EPSILON,
          "sequence lifeline heads should stay in one horizontal band, " + left);
      for (int rightIndex = leftIndex + 1; rightIndex < lifelines.size(); rightIndex++) {
        LaidOutNode right = lifelines.get(rightIndex);
        assertTrue(
            left.x() < right.x(),
            "each sequence lifeline should occupy a distinct column in declared order, "
                + order.get(leftIndex)
                + "="
                + left
                + ", "
                + order.get(rightIndex)
                + "="
                + right);
        assertFalse(
            rectanglesOverlap(
                left.x(),
                left.y(),
                left.width(),
                left.height(),
                right.x(),
                right.y(),
                right.width(),
                right.height()),
            "sequence lifeline heads should not overlap, "
                + order.get(leftIndex)
                + "="
                + left
                + ", "
                + order.get(rightIndex)
                + "="
                + right);
      }
    }

    // m2 (storefront -> orderservice) was the message that degenerated into a backwards stub
    // between the merged box's own edges; it must now read left-to-right.
    LaidOutEdge m2 = edgeById(result, "m2");
    assertEquals(2, m2.points().size(), "lifeline-to-lifeline message should be a direct segment");
    assertTrue(
        m2.points().getFirst().x() < m2.points().getLast().x(),
        "forward sequence message should span its columns left-to-right, m2=" + m2.points());
  }

  @Test
  void packedLayoutPlacesDisconnectedNodesWithoutEdgesOrOverlaps() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "map",
            List.of(
                new LayoutNode("crm", "CRM", "crm", 160.0, 80.0),
                new LayoutNode("billing", "Billing", "billing", 160.0, 80.0),
                new LayoutNode("warehouse", "Warehouse", "warehouse", 160.0, 80.0),
                new LayoutNode("portal", "Portal", "portal", 160.0, 80.0)),
            List.of(),
            List.of(),
            List.of(),
            new LayoutPreferences(LayoutMode.PACKED, null, LayoutDensity.READABLE, null, null));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    assertEquals(4, result.nodes().size());
    assertEquals(List.of(), result.edges());
    assertEquals(List.of(), result.warnings());
    for (int leftIndex = 0; leftIndex < result.nodes().size(); leftIndex++) {
      LaidOutNode left = result.nodes().get(leftIndex);
      for (int rightIndex = leftIndex + 1; rightIndex < result.nodes().size(); rightIndex++) {
        LaidOutNode right = result.nodes().get(rightIndex);
        assertFalse(
            rectanglesOverlap(
                left.x(),
                left.y(),
                left.width(),
                left.height(),
                right.x(),
                right.y(),
                right.width(),
                right.height()),
            "packed layout should not overlap disconnected nodes");
      }
    }
  }

  @Test
  void groupWithNoLaidOutMembersYieldsEmptyGroupWarningAndNoGroupBounds() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(new LayoutNode("api", "API", "api", 160.0, 80.0)),
            List.of(),
            List.of(
                new LayoutGroup(
                    "empty-group", "Empty", List.of(), GroupProvenance.visualOnlyGroup())),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);

    assertEquals(List.of(), result.groups());
    assertEquals(1, result.warnings().size());
    assertEquals("DEDIREN_ELK_EMPTY_GROUP", result.warnings().getFirst().code());
    assertEquals("$.groups[0]", result.warnings().getFirst().path());
  }

  @Test
  void packedLayoutPlacesGroupedDisconnectedNodesInsidePackedGroupBounds() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "map",
            List.of(
                new LayoutNode("crm", "CRM", "crm", 160.0, 80.0),
                new LayoutNode("billing", "Billing", "billing", 160.0, 80.0),
                new LayoutNode("warehouse", "Warehouse", "warehouse", 160.0, 80.0),
                new LayoutNode("portal", "Portal", "portal", 160.0, 80.0)),
            List.of(),
            List.of(
                new LayoutGroup(
                    "customer-systems",
                    "Customer Systems",
                    List.of("crm", "billing"),
                    GroupProvenance.visualOnlyGroup()),
                new LayoutGroup(
                    "operations",
                    "Operations",
                    List.of("warehouse", "portal"),
                    GroupProvenance.visualOnlyGroup())),
            List.of(),
            new LayoutPreferences(LayoutMode.PACKED, null, LayoutDensity.READABLE, null, null));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    LaidOutGroup customerSystems = groupById(result, "customer-systems");
    LaidOutGroup operations = groupById(result, "operations");
    assertEquals(List.of("crm", "billing"), customerSystems.members());
    assertEquals(List.of("warehouse", "portal"), operations.members());
    assertEquals(List.of(), result.edges());
    assertEquals(List.of(), result.warnings());
    assertGroupContainsNode(customerSystems, nodeById(result, "crm"));
    assertGroupContainsNode(customerSystems, nodeById(result, "billing"));
    assertGroupContainsNode(operations, nodeById(result, "warehouse"));
    assertGroupContainsNode(operations, nodeById(result, "portal"));
    assertFalse(
        rectanglesOverlap(
            customerSystems.x(),
            customerSystems.y(),
            customerSystems.width(),
            customerSystems.height(),
            operations.x(),
            operations.y(),
            operations.width(),
            operations.height()),
        "packed layout should not overlap group bounds");
  }

  @Test
  void packedLayoutSupportsNestedGroupMembers() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "map",
            List.of(
                new LayoutNode("initial", "", "initial", 36.0, 36.0),
                new LayoutNode("draft", "Draft", "draft", 150.0, 72.0)),
            List.of(),
            List.of(
                new LayoutGroup(
                    "outer-group",
                    "Outer Group",
                    List.of("inner-group"),
                    GroupProvenance.visualOnlyGroup()),
                new LayoutGroup(
                    "inner-group",
                    "Inner Group",
                    List.of("initial", "draft"),
                    GroupProvenance.visualOnlyGroup())),
            List.of(),
            new LayoutPreferences(LayoutMode.PACKED, null, LayoutDensity.READABLE, null, null));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    LaidOutGroup outer = groupById(result, "outer-group");
    LaidOutGroup inner = groupById(result, "inner-group");
    assertEquals(List.of(), result.warnings());
    assertEquals(List.of("inner-group"), outer.members());
    assertEquals(List.of("initial", "draft"), inner.members());
    assertEquals(List.of(), result.edges());
    assertGroupContainsGroup(outer, inner);
    assertGroupContainsNode(inner, nodeById(result, "initial"));
    assertGroupContainsNode(inner, nodeById(result, "draft"));
  }

  @Test
  void directionUpUsesNorthToSouthPorts() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("worker", "Worker", "worker", 160.0, 80.0),
                new LayoutNode("queue", "Queue", "queue", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "worker-polls-queue", "worker", "queue", "polls", "worker-polls-queue")),
            List.of(),
            List.of(),
            new LayoutPreferences(LayoutDirection.UP, null, null, null));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    assertRouteEndpointOnSide(result, "worker-polls-queue", "worker", true, PortSide.NORTH);
    assertRouteEndpointOnSide(result, "worker-polls-queue", "queue", false, PortSide.SOUTH);
  }

  @Test
  void compactDecisionFanOutUsesSeparateSourceCorners() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "activity",
            List.of(
                new LayoutNode("check-cache", "Cached?", "check-cache", 32.0, 32.0),
                new LayoutNode("cached", "Use Cache", "cached", 160.0, 80.0),
                new LayoutNode("stale", "Refresh", "stale", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "check-cache-cached",
                    "check-cache",
                    "cached",
                    "cached",
                    "check-cache-cached",
                    "ControlFlow"),
                new LayoutEdge(
                    "check-cache-stale",
                    "check-cache",
                    "stale",
                    "stale",
                    "check-cache-stale",
                    "ControlFlow")),
            List.of(),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    LaidOutEdge cachedEdge = edgeById(result, "check-cache-cached");
    LaidOutEdge staleEdge = edgeById(result, "check-cache-stale");

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
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(new LayoutNode("client", "Client", "client", 160.0, 80.0)),
            List.of(),
            List.of(
                new LayoutGroup(
                    "group-1",
                    "Group",
                    List.of("client", "missing"),
                    GroupProvenance.semanticBacked("system-group"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    LaidOutGroup group = result.groups().get(0);

    assertEquals("system-group", group.sourceId());
    assertEquals(List.of("client"), group.members());
    assertTrue(
        result.warnings().stream()
            .anyMatch(
                warning ->
                    warning.code().equals("DEDIREN_ELK_MISSING_GROUP_MEMBER")
                        && warning.path().equals("$.groups[0].members[1]")));
  }

  @Test
  void layoutPreservesVisualOnlyGroupProvenance() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(new LayoutNode("client", "Client", "client", 160.0, 80.0)),
            List.of(),
            List.of(
                new LayoutGroup(
                    "visual-column",
                    "Visual Column",
                    List.of("client"),
                    new GroupProvenance(true, null))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    LaidOutGroup group = result.groups().get(0);

    assertEquals("visual-column", group.sourceId());
    assertTrue(group.provenance().visualOnly());
    assertEquals(null, group.provenance().semanticBacked());
  }

  @Test
  void groupedMembersProduceGroupBoundsAroundGeneratedNodeGeometry() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
                new LayoutNode(
                    "payments", "Payment Authorization Service", "payments", 160.0, 80.0),
                new LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "web-app-calls-api", "web-app", "orders-api", "calls API", "web-app-calls-api"),
                new LayoutEdge(
                    "api-authorizes-payment",
                    "orders-api",
                    "payments",
                    "requests payment authorization",
                    "api-authorizes-payment"),
                new LayoutEdge(
                    "api-writes-database",
                    "orders-api",
                    "database",
                    "writes orders",
                    "api-writes-database"),
                new LayoutEdge(
                    "api-publishes-job",
                    "orders-api",
                    "worker",
                    "publishes fulfillment",
                    "api-publishes-job"),
                new LayoutEdge(
                    "worker-reads-database",
                    "worker",
                    "database",
                    "loads order",
                    "worker-reads-database")),
            List.of(
                new LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("web-app", "orders-api", "worker"),
                    GroupProvenance.semanticBacked("application-services")),
                new LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    GroupProvenance.semanticBacked("external-dependencies"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    assertEquals(2, result.groups().size());
    LaidOutGroup application =
        result.groups().stream()
            .filter(group -> group.id().equals("application-services"))
            .findFirst()
            .orElseThrow();
    LaidOutGroup external =
        result.groups().stream()
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
  void groupedLayoutSupportsNestedGroupMembers() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "state-machine",
            List.of(
                new LayoutNode("initial", "", "initial", 36.0, 36.0),
                new LayoutNode("draft", "Draft", "draft", 150.0, 72.0)),
            List.of(new LayoutEdge("create", "initial", "draft", "create", "create")),
            List.of(
                new LayoutGroup(
                    "state-machine-frame",
                    "State Machine",
                    List.of("region-frame"),
                    GroupProvenance.semanticBacked("state-machine")),
                new LayoutGroup(
                    "region-frame",
                    "Region",
                    List.of("initial", "draft"),
                    GroupProvenance.semanticBacked("region"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    LaidOutGroup outer = groupById(result, "state-machine-frame");
    LaidOutGroup inner = groupById(result, "region-frame");
    assertEquals(List.of(), result.warnings());
    assertEquals(List.of("region-frame"), outer.members());
    assertEquals(List.of("initial", "draft"), inner.members());
    assertGroupContainsGroup(outer, inner);
    assertGroupContainsNode(inner, nodeById(result, "initial"));
    assertGroupContainsNode(inner, nodeById(result, "draft"));
  }

  @Test
  void groupedPipelineKeepsReadableLeftToRightFlow() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("client", "Client", "client", 160.0, 80.0),
                new LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
                new LayoutNode(
                    "payments", "Payment Authorization Service", "payments", 160.0, 80.0),
                new LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "client-submits-order",
                    "client",
                    "web-app",
                    "submits order",
                    "client-submits-order"),
                new LayoutEdge(
                    "web-app-calls-api", "web-app", "orders-api", "calls API", "web-app-calls-api"),
                new LayoutEdge(
                    "api-authorizes-payment",
                    "orders-api",
                    "payments",
                    "requests payment authorization",
                    "api-authorizes-payment"),
                new LayoutEdge(
                    "api-writes-database",
                    "orders-api",
                    "database",
                    "writes orders",
                    "api-writes-database"),
                new LayoutEdge(
                    "api-publishes-job",
                    "orders-api",
                    "worker",
                    "publishes fulfillment",
                    "api-publishes-job"),
                new LayoutEdge(
                    "worker-reads-database",
                    "worker",
                    "database",
                    "loads order",
                    "worker-reads-database")),
            List.of(
                new LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("web-app", "orders-api", "worker"),
                    GroupProvenance.semanticBacked("application-services")),
                new LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    GroupProvenance.semanticBacked("external-dependencies"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    LaidOutNode client = nodeById(result, "client");
    LaidOutNode webApp = nodeById(result, "web-app");
    LaidOutGroup application = groupById(result, "application-services");
    LaidOutEdge submitsOrder = edgeById(result, "client-submits-order");
    Point submitStart = submitsOrder.points().get(0);
    Point submitEnd = submitsOrder.points().get(submitsOrder.points().size() - 1);
    double minX = result.nodes().stream().mapToDouble(LaidOutNode::x).min().orElse(0.0);
    double maxX =
        result.nodes().stream().mapToDouble(node -> node.x() + node.width()).max().orElse(0.0);
    double minY = result.nodes().stream().mapToDouble(LaidOutNode::y).min().orElse(0.0);
    double maxY =
        result.nodes().stream().mapToDouble(node -> node.y() + node.height()).max().orElse(0.0);
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
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("order-service", "Order Service", "order-service", 160.0, 80.0),
                new LayoutNode(
                    "catalog-service", "Catalog Service", "catalog-service", 160.0, 80.0),
                new LayoutNode(
                    "payment-service", "Payment Service", "payment-service", 160.0, 80.0),
                new LayoutNode(
                    "fulfillment-service",
                    "Fulfillment Service",
                    "fulfillment-service",
                    160.0,
                    80.0)),
            List.of(
                new LayoutEdge(
                    "order-checks-catalog",
                    "order-service",
                    "catalog-service",
                    "checks catalog",
                    "order-checks-catalog"),
                new LayoutEdge(
                    "order-requests-payment",
                    "order-service",
                    "payment-service",
                    "requests payment",
                    "order-requests-payment"),
                new LayoutEdge(
                    "order-reserves-stock",
                    "order-service",
                    "fulfillment-service",
                    "reserves stock",
                    "order-reserves-stock")),
            List.of(
                new LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of(
                        "order-service",
                        "catalog-service",
                        "payment-service",
                        "fulfillment-service"),
                    GroupProvenance.semanticBacked("core-services"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    LaidOutNode orderService = nodeById(result, "order-service");
    LaidOutEdge catalog = edgeById(result, "order-checks-catalog");
    LaidOutEdge payment = edgeById(result, "order-requests-payment");
    LaidOutEdge fulfillment = edgeById(result, "order-reserves-stock");

    for (String edgeId :
        List.of("order-checks-catalog", "order-requests-payment", "order-reserves-stock")) {
      assertRouteEndpointOnSide(result, edgeId, "order-service", true, PortSide.EAST);
    }
    assertEquals(0, routeCrossingCountNearSource(catalog, payment, orderService));
    assertEquals(0, routeCrossingCountNearSource(catalog, fulfillment, orderService));
    assertEquals(0, routeCrossingCountNearSource(payment, fulfillment, orderService));
  }

  @Test
  void groupedPipelineProducesValidRoutesForMultipleOutgoingEdges() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new LayoutNode(
                    "payments", "Payment Authorization Service", "payments", 160.0, 80.0),
                new LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "api-writes-database",
                    "orders-api",
                    "database",
                    "writes orders",
                    "api-writes-database"),
                new LayoutEdge(
                    "api-authorizes-payment",
                    "orders-api",
                    "payments",
                    "requests payment authorization",
                    "api-authorizes-payment")),
            List.of(
                new LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("orders-api"),
                    GroupProvenance.semanticBacked("application-services")),
                new LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    GroupProvenance.semanticBacked("external-dependencies"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    LaidOutEdge paymentEdge =
        result.edges().stream()
            .filter(edge -> edge.id().equals("api-authorizes-payment"))
            .findFirst()
            .orElseThrow();
    LaidOutEdge databaseEdge =
        result.edges().stream()
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
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("gateway", "API Gateway", "gateway", 160.0, 80.0),
                new LayoutNode("catalog", "Catalog", "catalog", 160.0, 80.0),
                new LayoutNode("pricing", "Pricing", "pricing", 160.0, 80.0),
                new LayoutNode("orders", "Orders", "orders", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "gateway-catalog", "gateway", "catalog", "queries", "gateway-catalog"),
                new LayoutEdge(
                    "gateway-pricing", "gateway", "pricing", "prices", "gateway-pricing"),
                new LayoutEdge("gateway-orders", "gateway", "orders", "orders", "gateway-orders")),
            List.of(),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    LaidOutNode gateway = nodeById(result, "gateway");

    assertEquals(
        80.0,
        gateway.height(),
        "typical nodes should fit three short-side ports before generated resizing");
  }

  @Test
  void groupedGatewayFanOutKeepsBoundedElkRoutes() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("web-frontend", "Web Frontend", "web-frontend", 160.0, 80.0),
                new LayoutNode("api-gateway", "API Gateway", "api-gateway", 160.0, 112.0),
                new LayoutNode(
                    "identity-service", "Identity Service", "identity-service", 160.0, 80.0),
                new LayoutNode(
                    "pricing-service", "Pricing Service", "pricing-service", 160.0, 80.0),
                new LayoutNode("order-service", "Order Service", "order-service", 160.0, 80.0),
                new LayoutNode(
                    "catalog-service", "Catalog Service", "catalog-service", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "web-calls-gateway",
                    "web-frontend",
                    "api-gateway",
                    "calls",
                    "web-calls-gateway"),
                new LayoutEdge(
                    "gateway-authenticates",
                    "api-gateway",
                    "identity-service",
                    "authenticates",
                    "gateway-authenticates",
                    "Serving"),
                new LayoutEdge(
                    "gateway-prices-cart",
                    "api-gateway",
                    "pricing-service",
                    "prices cart",
                    "gateway-prices-cart",
                    "Serving"),
                new LayoutEdge(
                    "gateway-places-order",
                    "api-gateway",
                    "order-service",
                    "places order",
                    "gateway-places-order",
                    "Serving"),
                new LayoutEdge(
                    "gateway-queries-catalog",
                    "api-gateway",
                    "catalog-service",
                    "queries catalog",
                    "gateway-queries-catalog",
                    "Serving")),
            List.of(
                new LayoutGroup(
                    "edge-platform",
                    "Edge Platform",
                    List.of("web-frontend", "api-gateway"),
                    GroupProvenance.semanticBacked("edge-platform")),
                new LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of(
                        "identity-service", "pricing-service", "order-service", "catalog-service"),
                    GroupProvenance.semanticBacked("core-services"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    assertEquals(
        0,
        connectorThroughNodeCount(result),
        "gateway fan-out routes should avoid unrelated service nodes");
    for (String edgeId :
        List.of(
            "gateway-authenticates",
            "gateway-prices-cart",
            "gateway-places-order",
            "gateway-queries-catalog")) {
      LaidOutEdge edge = edgeById(result, edgeId);
      assertRouted(edge);
      int corners = cornerCount(edge.points());
      assertTrue(
          corners <= 4,
          edgeId
              + " should keep a bounded ELK-routed corner count, got "
              + corners
              + " corners, points="
              + edge.points());
    }
  }

  @Test
  void groupedGatewayFanOutUsesMergedJunctionRoute() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("web-frontend", "Web Frontend", "web-frontend", 160.0, 80.0),
                new LayoutNode("api-gateway", "API Gateway", "api-gateway", 160.0, 112.0),
                new LayoutNode(
                    "identity-service", "Identity Service", "identity-service", 160.0, 80.0),
                new LayoutNode(
                    "pricing-service", "Pricing Service", "pricing-service", 160.0, 80.0),
                new LayoutNode("order-service", "Order Service", "order-service", 160.0, 80.0),
                new LayoutNode(
                    "catalog-service", "Catalog Service", "catalog-service", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "web-calls-gateway",
                    "web-frontend",
                    "api-gateway",
                    "calls",
                    "web-calls-gateway"),
                new LayoutEdge(
                    "gateway-authenticates",
                    "api-gateway",
                    "identity-service",
                    "authenticates",
                    "gateway-authenticates",
                    "Serving"),
                new LayoutEdge(
                    "gateway-prices-cart",
                    "api-gateway",
                    "pricing-service",
                    "prices cart",
                    "gateway-prices-cart",
                    "Serving"),
                new LayoutEdge(
                    "gateway-places-order",
                    "api-gateway",
                    "order-service",
                    "places order",
                    "gateway-places-order",
                    "Serving"),
                new LayoutEdge(
                    "gateway-queries-catalog",
                    "api-gateway",
                    "catalog-service",
                    "queries catalog",
                    "gateway-queries-catalog",
                    "Serving")),
            List.of(
                new LayoutGroup(
                    "edge-platform",
                    "Edge Platform",
                    List.of("web-frontend", "api-gateway"),
                    GroupProvenance.semanticBacked("edge-platform")),
                new LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of(
                        "identity-service", "pricing-service", "order-service", "catalog-service"),
                    GroupProvenance.semanticBacked("core-services"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    List<LaidOutEdge> fanOutEdges =
        List.of(
            edgeById(result, "gateway-authenticates"),
            edgeById(result, "gateway-prices-cart"),
            edgeById(result, "gateway-places-order"),
            edgeById(result, "gateway-queries-catalog"));

    assertTrue(
        hasSharedInteriorRoutePoint(fanOutEdges),
        "same-source fan-out should use an ELK-merged junction route, edges=" + fanOutEdges);
    for (LaidOutEdge edge : fanOutEdges) {
      assertEquals(
          List.of("shared_source_junction"),
          edge.routingHints(),
          "same-source fan-out should advise renderers about the merged source junction");
    }
  }

  @Test
  void endpointMergingOffSuppressesSharedSourceHints() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("source", "Source", "source", 160.0, 80.0),
                new LayoutNode("target-a", "Target A", "target-a", 160.0, 80.0),
                new LayoutNode("target-b", "Target B", "target-b", 160.0, 80.0),
                new LayoutNode("target-c", "Target C", "target-c", 160.0, 80.0)),
            List.of(
                new LayoutEdge("edge-a", "source", "target-a", "realizes", "edge-a", "Realization"),
                new LayoutEdge("edge-b", "source", "target-b", "realizes", "edge-b", "Realization"),
                new LayoutEdge(
                    "edge-c", "source", "target-c", "realizes", "edge-c", "Realization")),
            List.of(),
            List.of(),
            new LayoutPreferences(
                null,
                null,
                null,
                new LayoutRoutingPreferences(
                    LayoutRoutingStyle.ORTHOGONAL, null, LayoutEndpointMerging.OFF)));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    List<LaidOutEdge> fanOutEdges =
        List.of(edgeById(result, "edge-a"), edgeById(result, "edge-b"), edgeById(result, "edge-c"));

    assertFalse(
        hasSharedInteriorRoutePoint(fanOutEdges),
        "endpoint_merging=off must avoid no-hint routes that still share an interior route point");
    for (LaidOutEdge edge : fanOutEdges) {
      assertEquals(
          List.of(), edge.routingHints(), "endpoint_merging=off must suppress shared source hints");
    }
  }

  @Test
  void spaciousDensityExpandsGeneratedNodeForExtraPorts() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("source", "Source", "source", 160.0, 80.0),
                new LayoutNode("target-a", "Target A", "target-a", 160.0, 80.0),
                new LayoutNode("target-b", "Target B", "target-b", 160.0, 80.0),
                new LayoutNode("target-c", "Target C", "target-c", 160.0, 80.0),
                new LayoutNode("target-d", "Target D", "target-d", 160.0, 80.0),
                new LayoutNode("target-e", "Target E", "target-e", 160.0, 80.0)),
            List.of(
                new LayoutEdge("edge-a", "source", "target-a", "calls", "edge-a"),
                new LayoutEdge("edge-b", "source", "target-b", "calls", "edge-b"),
                new LayoutEdge("edge-c", "source", "target-c", "calls", "edge-c"),
                new LayoutEdge("edge-d", "source", "target-d", "calls", "edge-d"),
                new LayoutEdge("edge-e", "source", "target-e", "calls", "edge-e")),
            List.of(),
            List.of(),
            new LayoutPreferences(
                null,
                LayoutDensity.SPACIOUS,
                null,
                new LayoutRoutingPreferences(
                    LayoutRoutingStyle.ORTHOGONAL, null, LayoutEndpointMerging.OFF)));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    LaidOutNode source = nodeById(result, "source");
    assertTrue(
        source.height() >= 176.0,
        "spacious density should size five same-side ports using spacious spacing, height="
            + source.height());
  }

  @Test
  void groupedFanOutDoesNotMergeDifferentRelationshipTypes() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("api-gateway", "API Gateway", "api-gateway", 160.0, 112.0),
                new LayoutNode(
                    "identity-service", "Identity Service", "identity-service", 160.0, 80.0),
                new LayoutNode(
                    "pricing-service", "Pricing Service", "pricing-service", 160.0, 80.0),
                new LayoutNode("order-service", "Order Service", "order-service", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "gateway-authenticates",
                    "api-gateway",
                    "identity-service",
                    "authenticates",
                    "gateway-authenticates",
                    "Serving"),
                new LayoutEdge(
                    "gateway-prices-cart",
                    "api-gateway",
                    "pricing-service",
                    "prices cart",
                    "gateway-prices-cart",
                    "Serving"),
                new LayoutEdge(
                    "gateway-places-order",
                    "api-gateway",
                    "order-service",
                    "places order",
                    "gateway-places-order",
                    "Triggering")),
            List.of(
                new LayoutGroup(
                    "edge-platform",
                    "Edge Platform",
                    List.of("api-gateway"),
                    GroupProvenance.semanticBacked("edge-platform")),
                new LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of("identity-service", "pricing-service", "order-service"),
                    GroupProvenance.semanticBacked("core-services"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    for (String edgeId :
        List.of("gateway-authenticates", "gateway-prices-cart", "gateway-places-order")) {
      LaidOutEdge edge = edgeById(result, edgeId);
      assertEquals(
          List.of(),
          edge.routingHints(),
          "mixed relationship types must not be counted together for endpoint merging");
    }
  }

  @Test
  void sameGroupInternalEdgesDoNotUseSharedEndpointMerge() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("api-gateway", "API Gateway", "api-gateway", 160.0, 112.0),
                new LayoutNode(
                    "identity-service", "Identity Service", "identity-service", 160.0, 80.0),
                new LayoutNode(
                    "pricing-service", "Pricing Service", "pricing-service", 160.0, 80.0),
                new LayoutNode("order-service", "Order Service", "order-service", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "gateway-authenticates",
                    "api-gateway",
                    "identity-service",
                    "authenticates",
                    "gateway-authenticates",
                    "Serving"),
                new LayoutEdge(
                    "gateway-prices-cart",
                    "api-gateway",
                    "pricing-service",
                    "prices cart",
                    "gateway-prices-cart",
                    "Serving"),
                new LayoutEdge(
                    "gateway-places-order",
                    "api-gateway",
                    "order-service",
                    "places order",
                    "gateway-places-order",
                    "Serving")),
            List.of(
                new LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of("api-gateway", "identity-service", "pricing-service", "order-service"),
                    GroupProvenance.semanticBacked("core-services"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    for (String edgeId :
        List.of("gateway-authenticates", "gateway-prices-cart", "gateway-places-order")) {
      LaidOutEdge edge = edgeById(result, edgeId);
      assertEquals(
          List.of(),
          edge.routingHints(),
          "same-group internal edges should not be converted into shared endpoint junctions");
    }
  }

  @Test
  void groupedSourcePortsFollowDivergingRouteChannelOrder() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode(
                    "identity-service", "Identity Service", "identity-service", 160.0, 80.0),
                new LayoutNode("session-cache", "Session Cache", "session-cache", 160.0, 80.0),
                new LayoutNode(
                    "identity-provider", "Identity Provider", "identity-provider", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "identity-federates",
                    "identity-service",
                    "identity-provider",
                    "federates",
                    "identity-federates"),
                new LayoutEdge(
                    "identity-caches-session",
                    "identity-service",
                    "session-cache",
                    "caches session",
                    "identity-caches-session")),
            List.of(
                new LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of("identity-service"),
                    GroupProvenance.semanticBacked("core-services")),
                new LayoutGroup(
                    "data-platform",
                    "Data Platform",
                    List.of("session-cache"),
                    GroupProvenance.semanticBacked("data-platform")),
                new LayoutGroup(
                    "external-systems",
                    "External Systems",
                    List.of("identity-provider"),
                    GroupProvenance.semanticBacked("external-systems"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    LaidOutNode source = nodeById(result, "identity-service");
    LaidOutEdge cacheEdge = edgeById(result, "identity-caches-session");
    LaidOutEdge federatesEdge = edgeById(result, "identity-federates");

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
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode(
                    "pricing-service", "Pricing Service", "pricing-service", 160.0, 80.0),
                new LayoutNode("session-cache", "Session Cache", "session-cache", 160.0, 80.0),
                new LayoutNode("product-db", "Product DB", "product-db", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "pricing-reads-products",
                    "pricing-service",
                    "product-db",
                    "reads products",
                    "pricing-reads-products"),
                new LayoutEdge(
                    "pricing-caches-quotes",
                    "pricing-service",
                    "session-cache",
                    "caches quotes",
                    "pricing-caches-quotes")),
            List.of(
                new LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of("pricing-service"),
                    GroupProvenance.semanticBacked("core-services")),
                new LayoutGroup(
                    "data-platform",
                    "Data Platform",
                    List.of("session-cache", "product-db"),
                    GroupProvenance.semanticBacked("data-platform"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    LaidOutNode source = nodeById(result, "pricing-service");
    LaidOutEdge readsEdge = edgeById(result, "pricing-reads-products");
    LaidOutEdge cacheEdge = edgeById(result, "pricing-caches-quotes");

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
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("customer-mobile", "Mobile App", "customer-mobile", 160.0, 80.0),
                new LayoutNode("customer-web", "Web Customer", "customer-web", 160.0, 80.0),
                new LayoutNode("support-agent", "Support Agent", "support-agent", 160.0, 80.0),
                new LayoutNode("cdn", "CDN", "cdn", 160.0, 80.0),
                new LayoutNode("web-frontend", "Web Frontend", "web-frontend", 160.0, 80.0),
                new LayoutNode("admin-portal", "Admin Portal", "admin-portal", 160.0, 80.0),
                new LayoutNode("api-gateway", "API Gateway", "api-gateway", 160.0, 80.0),
                new LayoutNode("gateway-and-junction", "", "gateway-and-junction", 28.0, 28.0)),
            List.of(
                new LayoutEdge(
                    "mobile-enters-cdn",
                    "customer-mobile",
                    "cdn",
                    "uses",
                    "mobile-enters-cdn",
                    "Association"),
                new LayoutEdge(
                    "web-enters-cdn",
                    "customer-web",
                    "cdn",
                    "uses",
                    "web-enters-cdn",
                    "Association"),
                new LayoutEdge(
                    "support-opens-admin",
                    "support-agent",
                    "admin-portal",
                    "manages",
                    "support-opens-admin"),
                new LayoutEdge("cdn-serves-web", "cdn", "web-frontend", "serves", "cdn-serves-web"),
                new LayoutEdge(
                    "web-calls-gateway",
                    "web-frontend",
                    "api-gateway",
                    "calls",
                    "web-calls-gateway"),
                new LayoutEdge(
                    "admin-calls-gateway",
                    "admin-portal",
                    "api-gateway",
                    "calls",
                    "admin-calls-gateway"),
                new LayoutEdge(
                    "gateway-to-and-junction",
                    "api-gateway",
                    "gateway-and-junction",
                    "routes",
                    "gateway-to-and-junction")),
            List.of(
                new LayoutGroup(
                    "users",
                    "Users",
                    List.of("customer-mobile", "customer-web", "support-agent"),
                    GroupProvenance.semanticBacked("users")),
                new LayoutGroup(
                    "edge-platform",
                    "Edge Platform",
                    List.of(
                        "cdn",
                        "web-frontend",
                        "admin-portal",
                        "api-gateway",
                        "gateway-and-junction"),
                    GroupProvenance.semanticBacked("edge-platform"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    LaidOutNode mobile = nodeById(result, "customer-mobile");
    LaidOutNode web = nodeById(result, "customer-web");
    LaidOutEdge mobileEdge = edgeById(result, "mobile-enters-cdn");
    LaidOutEdge webEdge = edgeById(result, "web-enters-cdn");

    assertRouteEndpointOnSide(result, "mobile-enters-cdn", "cdn", false, PortSide.WEST);
    assertRouteEndpointOnSide(result, "web-enters-cdn", "cdn", false, PortSide.WEST);
    assertEquals(List.of("shared_target_junction"), mobileEdge.routingHints());
    assertEquals(List.of("shared_target_junction"), webEdge.routingHints());
    assertTrue(
        sameVerticalOrder(
            centerY(web), centerY(mobile), targetPortY(webEdge), targetPortY(mobileEdge)),
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
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new LayoutNode("batch-worker", "Batch Worker", "batch-worker", 160.0, 80.0),
                new LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "web-calls-api", "web-app", "orders-api", "calls API", "web-calls-api"),
                new LayoutEdge(
                    "worker-updates-api",
                    "batch-worker",
                    "orders-api",
                    "updates orders",
                    "worker-updates-api")),
            List.of(
                new LayoutGroup(
                    "callers",
                    "Callers",
                    List.of("web-app", "batch-worker"),
                    GroupProvenance.semanticBacked("callers")),
                new LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("orders-api"),
                    GroupProvenance.semanticBacked("application-services"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    LaidOutEdge webEdge =
        result.edges().stream()
            .filter(edge -> edge.id().equals("web-calls-api"))
            .findFirst()
            .orElseThrow();
    LaidOutEdge workerEdge =
        result.edges().stream()
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
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new LayoutNode("batch-worker", "Batch Worker", "batch-worker", 160.0, 80.0),
                new LayoutNode(
                    "support-console", "Support Console", "support-console", 160.0, 80.0),
                new LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 112.0)),
            List.of(
                new LayoutEdge(
                    "web-calls-api",
                    "web-app",
                    "orders-api",
                    "calls API",
                    "web-calls-api",
                    "Serving"),
                new LayoutEdge(
                    "worker-updates-api",
                    "batch-worker",
                    "orders-api",
                    "updates orders",
                    "worker-updates-api",
                    "Serving"),
                new LayoutEdge(
                    "console-reads-api",
                    "support-console",
                    "orders-api",
                    "reads orders",
                    "console-reads-api",
                    "Serving")),
            List.of(
                new LayoutGroup(
                    "callers",
                    "Callers",
                    List.of("web-app", "batch-worker", "support-console"),
                    GroupProvenance.semanticBacked("callers")),
                new LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("orders-api"),
                    GroupProvenance.semanticBacked("application-services"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    List<LaidOutEdge> fanInEdges =
        List.of(
            edgeById(result, "web-calls-api"),
            edgeById(result, "worker-updates-api"),
            edgeById(result, "console-reads-api"));

    assertTrue(
        hasSharedInteriorRoutePoint(fanInEdges),
        "same-target fan-in should use an ELK-merged junction route, edges=" + fanInEdges);
    for (LaidOutEdge edge : fanInEdges) {
      assertEquals(
          List.of("shared_target_junction"),
          edge.routingHints(),
          "same-target fan-in should advise renderers about the merged target junction");
    }
  }

  @Test
  void groupedPipelineAvoidsExcessiveCrossGroupRouteDetours() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("client", "Client", "client", 160.0, 80.0),
                new LayoutNode("web-app", "Web App", "web-app", 160.0, 80.0),
                new LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
                new LayoutNode(
                    "payments", "Payment Authorization Service", "payments", 160.0, 80.0),
                new LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "client-submits-order",
                    "client",
                    "web-app",
                    "submits order",
                    "client-submits-order"),
                new LayoutEdge(
                    "web-app-calls-api", "web-app", "orders-api", "calls API", "web-app-calls-api"),
                new LayoutEdge(
                    "api-authorizes-payment",
                    "orders-api",
                    "payments",
                    "requests payment authorization",
                    "api-authorizes-payment"),
                new LayoutEdge(
                    "api-writes-database",
                    "orders-api",
                    "database",
                    "writes orders",
                    "api-writes-database"),
                new LayoutEdge(
                    "api-publishes-job",
                    "orders-api",
                    "worker",
                    "publishes fulfillment",
                    "api-publishes-job"),
                new LayoutEdge(
                    "worker-reads-database",
                    "worker",
                    "database",
                    "loads order",
                    "worker-reads-database")),
            List.of(
                new LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("web-app", "orders-api", "worker"),
                    GroupProvenance.semanticBacked("application-services")),
                new LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    GroupProvenance.semanticBacked("external-dependencies"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    assertEquals(
        0,
        excessiveRouteDetourCount(result),
        "grouped cross-boundary routes should not loop around the whole diagram");
  }

  @Test
  void groupedReverseCrossGroupEdgeKeepsBoundedElkDetour() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("orders-api", "Orders API", "orders-api", 160.0, 80.0),
                new LayoutNode("worker", "Fulfillment Worker", "worker", 160.0, 80.0),
                new LayoutNode(
                    "payments", "Payment Authorization Service", "payments", 160.0, 80.0),
                new LayoutNode("database", "PostgreSQL", "database", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "api-writes-database",
                    "orders-api",
                    "database",
                    "writes orders",
                    "api-writes-database"),
                new LayoutEdge(
                    "payments-serves-api",
                    "payments",
                    "orders-api",
                    "serves payment authorization",
                    "payments-serves-api"),
                new LayoutEdge(
                    "api-publishes-job",
                    "orders-api",
                    "worker",
                    "publishes fulfillment",
                    "api-publishes-job"),
                new LayoutEdge(
                    "worker-reads-database",
                    "worker",
                    "database",
                    "loads order",
                    "worker-reads-database")),
            List.of(
                new LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("orders-api", "worker"),
                    GroupProvenance.semanticBacked("application-services")),
                new LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("payments", "database"),
                    GroupProvenance.semanticBacked("external-dependencies"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    LaidOutEdge paymentEdge = edgeById(result, "payments-serves-api");
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
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("workflow-entry", "Workflow Entry", "workflow-entry", 160.0, 80.0),
                new LayoutNode(
                    "workflow-return", "Workflow Return", "workflow-return", 160.0, 80.0),
                new LayoutNode("worker-entry", "Worker Entry", "worker-entry", 160.0, 80.0),
                new LayoutNode("worker-return", "Worker Return", "worker-return", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "workflow-to-worker",
                    "workflow-entry",
                    "worker-entry",
                    "dispatches",
                    "workflow-to-worker"),
                new LayoutEdge(
                    "worker-to-workflow",
                    "worker-return",
                    "workflow-return",
                    "reports",
                    "worker-to-workflow")),
            List.of(
                new LayoutGroup(
                    "workflow",
                    "Workflow",
                    List.of("workflow-entry", "workflow-return"),
                    GroupProvenance.semanticBacked("workflow")),
                new LayoutGroup(
                    "worker",
                    "Worker",
                    List.of("worker-entry", "worker-return"),
                    GroupProvenance.semanticBacked("worker"))),
            List.of(),
            new LayoutPreferences(LayoutDirection.DOWN, null, null, null));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    assertRouteEndpointOnSide(result, "workflow-to-worker", "workflow-entry", true, PortSide.SOUTH);
    assertRouteEndpointOnSide(result, "workflow-to-worker", "worker-entry", false, PortSide.NORTH);
    assertRouteEndpointOnSide(result, "worker-to-workflow", "worker-return", true, PortSide.NORTH);
    assertRouteEndpointOnSide(
        result, "worker-to-workflow", "workflow-return", false, PortSide.SOUTH);

    // The two edges form a group cycle. The reverse edge must route straight back through the
    // return channel, not wrap the whole diagram as ELK feedback routing would otherwise do.
    assertEquals(
        List.of(),
        excessiveRouteDetourIds(result),
        "cyclic cross-group edges must route straight through, not detour around the diagram");
  }

  @Test
  void businessProcessCooperationRoutesCrossHierarchyFeedbackEdgesToDeclaredTargets() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "business-process-cooperation",
            List.of(
                new LayoutNode(
                    "actor-electronic-applicant",
                    "Electronic Applicant",
                    "actor-electronic-applicant",
                    160.0,
                    80.0),
                new LayoutNode(
                    "actor-ulosottolaitos",
                    "Ulosottolaitos / ORK",
                    "actor-ulosottolaitos",
                    160.0,
                    80.0),
                new LayoutNode(
                    "proc-uljas-submit-batch",
                    "Submit Batch",
                    "proc-uljas-submit-batch",
                    160.0,
                    80.0),
                new LayoutNode(
                    "proc-uljas-process-batch",
                    "Process Batch",
                    "proc-uljas-process-batch",
                    160.0,
                    80.0),
                new LayoutNode(
                    "proc-uljas-feedback-cycle",
                    "Deliver Feedback",
                    "proc-uljas-feedback-cycle",
                    160.0,
                    80.0),
                new LayoutNode("evt-batch-landed", "Batch Landed", "evt-batch-landed", 160.0, 80.0),
                new LayoutNode(
                    "svc-send-uljas-message",
                    "Send Uljas Message",
                    "svc-send-uljas-message",
                    160.0,
                    80.0),
                new LayoutNode(
                    "svc-receive-uljas-message",
                    "Receive Uljas Message",
                    "svc-receive-uljas-message",
                    160.0,
                    80.0)),
            List.of(
                new LayoutEdge(
                    "actor-applicant-assigns-submit",
                    "actor-electronic-applicant",
                    "proc-uljas-submit-batch",
                    "performs",
                    "actor-applicant-assigns-submit",
                    "Assignment"),
                new LayoutEdge(
                    "actor-ulosottolaitos-assigns-process",
                    "actor-ulosottolaitos",
                    "proc-uljas-process-batch",
                    "performs",
                    "actor-ulosottolaitos-assigns-process",
                    "Assignment"),
                new LayoutEdge(
                    "actor-ulosottolaitos-assigns-feedback",
                    "actor-ulosottolaitos",
                    "proc-uljas-feedback-cycle",
                    "performs",
                    "actor-ulosottolaitos-assigns-feedback",
                    "Assignment"),
                new LayoutEdge(
                    "proc-submit-triggers-evt-landed",
                    "proc-uljas-submit-batch",
                    "evt-batch-landed",
                    "lands",
                    "proc-submit-triggers-evt-landed",
                    "Triggering"),
                new LayoutEdge(
                    "evt-landed-triggers-process",
                    "evt-batch-landed",
                    "proc-uljas-process-batch",
                    "forwards",
                    "evt-landed-triggers-process",
                    "Triggering"),
                new LayoutEdge(
                    "proc-process-triggers-feedback",
                    "proc-uljas-process-batch",
                    "proc-uljas-feedback-cycle",
                    "outputs",
                    "proc-process-triggers-feedback",
                    "Triggering"),
                new LayoutEdge(
                    "proc-feedback-flow-applicant",
                    "proc-uljas-feedback-cycle",
                    "actor-electronic-applicant",
                    "delivers",
                    "proc-feedback-flow-applicant",
                    "Flow"),
                new LayoutEdge(
                    "svc-send-serves-proc-submit",
                    "svc-send-uljas-message",
                    "proc-uljas-submit-batch",
                    "serves",
                    "svc-send-serves-proc-submit",
                    "Serving"),
                new LayoutEdge(
                    "svc-receive-serves-proc-feedback",
                    "svc-receive-uljas-message",
                    "proc-uljas-feedback-cycle",
                    "serves",
                    "svc-receive-serves-proc-feedback",
                    "Serving")),
            List.of(
                new LayoutGroup(
                    "grp-stakeholders-view-bpc",
                    "Uljas Business Stakeholders",
                    List.of("actor-electronic-applicant", "actor-ulosottolaitos"),
                    GroupProvenance.semanticBacked("grp-uljas-business-stakeholders"))),
            List.of(),
            new LayoutPreferences(
                LayoutDirection.RIGHT,
                LayoutDensity.SPACIOUS,
                null,
                new LayoutRoutingPreferences(
                    LayoutRoutingStyle.ORTHOGONAL,
                    LayoutRoutingProfile.SPACIOUS,
                    LayoutEndpointMerging.OFF)));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    assertRouteEndpointsOnNodePerimeters(result, "actor-ulosottolaitos-assigns-feedback");
    assertRouteEndpointsOnNodePerimeters(result, "proc-feedback-flow-applicant");
    // Every edge in this cross-hierarchy feedback graph must render pristinely, not just the two
    // feedback edges checked above: axis-aligned segments, no zero-length bends, and no route
    // driven through an unrelated node.
    for (LaidOutEdge edge : result.edges()) {
      assertOrthogonalRoute(edge);
      assertNoDegenerateRouteSegments(edge);
    }
    assertEquals(
        0,
        connectorThroughNodeCount(result),
        "cross-hierarchy feedback routes should avoid unrelated nodes");
  }

  @Test
  void groupedConnectorEdgesKeepHorizontalFlowInsideVerticalGroups() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("event-bus", "Event Bus", "event-bus", 160.0, 80.0),
                new LayoutNode(
                    "event-dispatch-or-junction",
                    "Event Dispatch",
                    "event-dispatch-or-junction",
                    28.0,
                    28.0),
                new LayoutNode("order-worker", "Order Worker", "order-worker", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "event-bus-to-or-junction",
                    "event-bus",
                    "event-dispatch-or-junction",
                    "dispatches",
                    "event-bus-to-or-junction"),
                new LayoutEdge(
                    "event-bus-drives-order-worker",
                    "event-bus",
                    "order-worker",
                    "order event",
                    "event-bus-drives-order-worker")),
            List.of(
                new LayoutGroup(
                    "async-processing",
                    "Async Processing",
                    List.of("event-bus", "event-dispatch-or-junction", "order-worker"),
                    GroupProvenance.semanticBacked("async-processing"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);
    LaidOutEdge dispatchEdge = edgeById(result, "event-bus-to-or-junction");
    LaidOutEdge orderEdge = edgeById(result, "event-bus-drives-order-worker");

    assertRouteEndpointOnSide(result, "event-bus-to-or-junction", "event-bus", true, PortSide.EAST);
    assertRouteEndpointOnSide(
        result, "event-bus-to-or-junction", "event-dispatch-or-junction", false, PortSide.WEST);
    assertRouteEndpointOnSide(
        result, "event-bus-drives-order-worker", "event-bus", true, PortSide.SOUTH);
    assertRouteEndpointOnSide(
        result, "event-bus-drives-order-worker", "order-worker", false, PortSide.NORTH);
    assertTrue(
        sourcePortY(dispatchEdge) < sourcePortY(orderEdge),
        "junction dispatch source port should be above the direct order branch, dispatch="
            + dispatchEdge.points()
            + ", order="
            + orderEdge.points());
  }

  @Test
  void complexGroupedServiceMeshKeepsRoutesValidAndBounded() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "service-mesh",
            List.of(
                new LayoutNode("customer-mobile", "Mobile App", "customer-mobile", 160.0, 80.0),
                new LayoutNode("customer-web", "Web Customer", "customer-web", 160.0, 80.0),
                new LayoutNode("support-agent", "Support Agent", "support-agent", 160.0, 80.0),
                new LayoutNode("cdn", "CDN", "cdn", 160.0, 80.0),
                new LayoutNode("web-frontend", "Web Frontend", "web-frontend", 160.0, 80.0),
                new LayoutNode("admin-portal", "Admin Portal", "admin-portal", 160.0, 80.0),
                new LayoutNode("api-gateway", "API Gateway", "api-gateway", 160.0, 112.0),
                new LayoutNode(
                    "identity-service", "Identity Service", "identity-service", 160.0, 80.0),
                new LayoutNode(
                    "pricing-service", "Pricing Service", "pricing-service", 160.0, 80.0),
                new LayoutNode("order-service", "Order Service", "order-service", 160.0, 80.0),
                new LayoutNode(
                    "catalog-service", "Catalog Service", "catalog-service", 160.0, 80.0),
                new LayoutNode(
                    "fulfillment-worker", "Fulfillment Worker", "fulfillment-worker", 160.0, 80.0),
                new LayoutNode("session-cache", "Session Cache", "session-cache", 160.0, 80.0),
                new LayoutNode("product-db", "Product DB", "product-db", 160.0, 80.0),
                new LayoutNode("order-db", "Order DB", "order-db", 160.0, 80.0),
                new LayoutNode(
                    "payment-provider", "Payment Provider", "payment-provider", 160.0, 80.0),
                new LayoutNode("email-provider", "Email Provider", "email-provider", 160.0, 80.0)),
            List.of(
                new LayoutEdge(
                    "mobile-enters-cdn",
                    "customer-mobile",
                    "cdn",
                    "uses",
                    "mobile-enters-cdn",
                    "Association"),
                new LayoutEdge(
                    "web-enters-cdn",
                    "customer-web",
                    "cdn",
                    "uses",
                    "web-enters-cdn",
                    "Association"),
                new LayoutEdge(
                    "mobile-calls-gateway",
                    "customer-mobile",
                    "api-gateway",
                    "uses API",
                    "mobile-calls-gateway",
                    "Serving"),
                new LayoutEdge(
                    "web-user-calls-gateway",
                    "customer-web",
                    "api-gateway",
                    "uses API",
                    "web-user-calls-gateway",
                    "Serving"),
                new LayoutEdge(
                    "support-calls-gateway",
                    "support-agent",
                    "api-gateway",
                    "uses API",
                    "support-calls-gateway",
                    "Serving"),
                new LayoutEdge(
                    "cdn-serves-web", "cdn", "web-frontend", "serves", "cdn-serves-web", "Serving"),
                new LayoutEdge(
                    "frontend-calls-gateway",
                    "web-frontend",
                    "api-gateway",
                    "calls",
                    "frontend-calls-gateway",
                    "Serving"),
                new LayoutEdge(
                    "admin-calls-gateway",
                    "admin-portal",
                    "api-gateway",
                    "calls",
                    "admin-calls-gateway",
                    "Serving"),
                new LayoutEdge(
                    "gateway-authenticates",
                    "api-gateway",
                    "identity-service",
                    "authenticates",
                    "gateway-authenticates",
                    "Serving"),
                new LayoutEdge(
                    "gateway-prices-cart",
                    "api-gateway",
                    "pricing-service",
                    "prices cart",
                    "gateway-prices-cart",
                    "Serving"),
                new LayoutEdge(
                    "gateway-places-order",
                    "api-gateway",
                    "order-service",
                    "places order",
                    "gateway-places-order",
                    "Serving"),
                new LayoutEdge(
                    "gateway-queries-catalog",
                    "api-gateway",
                    "catalog-service",
                    "queries catalog",
                    "gateway-queries-catalog",
                    "Serving"),
                new LayoutEdge(
                    "order-requests-payment",
                    "order-service",
                    "payment-provider",
                    "requests payment",
                    "order-requests-payment",
                    "Flow"),
                new LayoutEdge(
                    "payment-confirms-order",
                    "payment-provider",
                    "order-service",
                    "confirms payment",
                    "payment-confirms-order",
                    "Flow"),
                new LayoutEdge(
                    "order-writes-order-db",
                    "order-service",
                    "order-db",
                    "writes order",
                    "order-writes-order-db",
                    "Access"),
                new LayoutEdge(
                    "pricing-reads-products",
                    "pricing-service",
                    "product-db",
                    "reads products",
                    "pricing-reads-products",
                    "Access"),
                new LayoutEdge(
                    "catalog-reads-products",
                    "catalog-service",
                    "product-db",
                    "reads products",
                    "catalog-reads-products",
                    "Access"),
                new LayoutEdge(
                    "identity-caches-session",
                    "identity-service",
                    "session-cache",
                    "caches session",
                    "identity-caches-session",
                    "Access"),
                new LayoutEdge(
                    "order-publishes-fulfillment",
                    "order-service",
                    "fulfillment-worker",
                    "publishes fulfillment",
                    "order-publishes-fulfillment",
                    "Triggering"),
                new LayoutEdge(
                    "fulfillment-reads-order-db",
                    "fulfillment-worker",
                    "order-db",
                    "loads order",
                    "fulfillment-reads-order-db",
                    "Access"),
                new LayoutEdge(
                    "fulfillment-sends-email",
                    "fulfillment-worker",
                    "email-provider",
                    "sends email",
                    "fulfillment-sends-email",
                    "Flow")),
            List.of(
                new LayoutGroup(
                    "users",
                    "Users",
                    List.of("customer-mobile", "customer-web", "support-agent"),
                    GroupProvenance.semanticBacked("users")),
                new LayoutGroup(
                    "edge-platform",
                    "Edge Platform",
                    List.of("cdn", "web-frontend", "admin-portal", "api-gateway"),
                    GroupProvenance.semanticBacked("edge-platform")),
                new LayoutGroup(
                    "core-services",
                    "Core Services",
                    List.of(
                        "identity-service",
                        "pricing-service",
                        "order-service",
                        "catalog-service",
                        "fulfillment-worker"),
                    GroupProvenance.semanticBacked("core-services")),
                new LayoutGroup(
                    "data-platform",
                    "Data Platform",
                    List.of("session-cache", "product-db", "order-db"),
                    GroupProvenance.semanticBacked("data-platform")),
                new LayoutGroup(
                    "external-systems",
                    "External Systems",
                    List.of("payment-provider", "email-provider"),
                    GroupProvenance.semanticBacked("external-systems"))),
            List.of(),
            new LayoutPreferences(
                LayoutDirection.RIGHT,
                LayoutDensity.SPACIOUS,
                null,
                new LayoutRoutingPreferences(
                    LayoutRoutingStyle.ORTHOGONAL,
                    LayoutRoutingProfile.SPACIOUS,
                    LayoutEndpointMerging.AUTO)));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    assertEquals(List.of(), result.warnings());
    assertEquals(17, result.nodes().size());
    assertEquals(21, result.edges().size());
    for (LaidOutEdge edge : result.edges()) {
      assertRouteEndpointsOnNodePerimeters(result, edge.id());
      assertOrthogonalRoute(edge);
      assertNoDegenerateRouteSegments(edge);
      assertTrue(
          cornerCount(edge.points()) <= 8,
          edge.id() + " should keep a bounded corner count, points=" + edge.points());
      assertEquals(
          0,
          endpointBoundaryOverlapCount(result, edge),
          edge.id() + " must leave its endpoint nodes before turning, not hug a node border");
    }
    for (LaidOutGroup group : result.groups()) {
      assertGroupContainsMembers(result, group);
    }
    assertEquals(
        0,
        connectorThroughNodeCount(result),
        "complex service mesh routes should avoid unrelated nodes");
    List<String> detourEdges = excessiveRouteDetourIds(result);
    assertTrue(
        detourEdges.isEmpty() || detourEdges.equals(List.of("payment-confirms-order")),
        "complex service mesh routes should only allow the reverse payment feedback detour, got "
            + detourEdges);
    for (String edgeId :
        List.of("mobile-calls-gateway", "web-user-calls-gateway", "support-calls-gateway")) {
      assertEquals(
          List.of("shared_target_junction"),
          edgeById(result, edgeId).routingHints(),
          "user entry routes should share the API gateway target endpoint");
    }
    for (String edgeId :
        List.of(
            "gateway-authenticates",
            "gateway-prices-cart",
            "gateway-places-order",
            "gateway-queries-catalog")) {
      assertEquals(
          List.of("shared_source_junction"),
          edgeById(result, edgeId).routingHints(),
          "gateway fan-out routes should share the gateway source endpoint");
    }
  }

  @Test
  void junctionRoleSurvivesLayoutAndPassesQualityGeometry() {
    var request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("order-intake", "Order Intake", "order-intake", 160.0, 80.0, null),
                new LayoutNode(
                    "fulfillment-junction", "or", "fulfillment-junction", 28.0, 28.0, "junction"),
                new LayoutNode("fulfillment", "Fulfillment", "fulfillment", 160.0, 80.0, null),
                new LayoutNode("notification", "Notification", "notification", 160.0, 80.0, null)),
            List.of(
                new LayoutEdge(
                    "intake-flows-junction",
                    "order-intake",
                    "fulfillment-junction",
                    "order accepted",
                    "intake-flows-junction",
                    "Flow"),
                new LayoutEdge(
                    "junction-flows-fulfillment",
                    "fulfillment-junction",
                    "fulfillment",
                    "fulfil",
                    "junction-flows-fulfillment",
                    "Flow"),
                new LayoutEdge(
                    "junction-flows-notification",
                    "fulfillment-junction",
                    "notification",
                    "notify",
                    "junction-flows-notification",
                    "Flow")),
            List.of(),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    LaidOutNode junction =
        result.nodes().stream()
            .filter(node -> node.id().equals("fulfillment-junction"))
            .findFirst()
            .orElseThrow();
    assertEquals("junction", junction.role(), "junction role must survive ELK layout");

    int incidentEdges = 0;
    for (LaidOutEdge edge : result.edges()) {
      boolean incident = junction.id().equals(edge.source()) || junction.id().equals(edge.target());
      if (!incident) {
        continue;
      }
      incidentEdges++;
      double centerX = junction.x() + junction.width() / 2.0;
      double centerY = junction.y() + junction.height() / 2.0;
      // Mirrors core LayoutQuality's junction check: the rendered dot radius tracks
      // min(w,h)/2 and 2.0 is core's JUNCTION_ROUTE_TOLERANCE.
      double reach = Math.min(junction.width(), junction.height()) / 2.0 + 2.0;
      double distance = minDistanceToRoute(centerX, centerY, edge.points());
      assertTrue(
          distance <= reach,
          "junction must sit on the route of "
              + edge.id()
              + ", distance="
              + distance
              + ", reach="
              + reach
              + ", junction="
              + junction
              + ", points="
              + edge.points());
    }
    assertEquals(
        3,
        incidentEdges,
        "all three junction edges must survive layout with original endpoint ids");
  }

  // Inlined copy of core LayoutQuality's distanceToRoute/distanceToSegment math: plugins must
  // not depend on core, and this e2e test corroborates that product check against real ELK.
  private static double minDistanceToRoute(double x, double y, List<Point> points) {
    double min = Double.MAX_VALUE;
    for (int i = 0; i + 1 < points.size(); i++) {
      double dx = points.get(i + 1).x() - points.get(i).x();
      double dy = points.get(i + 1).y() - points.get(i).y();
      double lengthSquared = dx * dx + dy * dy;
      double t =
          lengthSquared == 0.0
              ? 0.0
              : Math.clamp(
                  ((x - points.get(i).x()) * dx + (y - points.get(i).y()) * dy) / lengthSquared,
                  0.0,
                  1.0);
      min =
          Math.min(
              min, Math.hypot(x - (points.get(i).x() + t * dx), y - (points.get(i).y() + t * dy)));
    }
    return min;
  }

  private static LayoutRequest fiveLifelineSequenceRequest(List<String> order) {
    List<LayoutNode> nodes =
        List.of(
            new LayoutNode("user", "User", "user", 140.0, 48.0, "lifeline"),
            new LayoutNode("storefront", "Storefront", "storefront", 140.0, 48.0, "lifeline"),
            new LayoutNode("interaction", "Place Order", "interaction", 600.0, 400.0),
            new LayoutNode(
                "orderservice", "Order Service", "orderservice", 140.0, 48.0, "lifeline"),
            new LayoutNode("payment", "Payment", "payment", 140.0, 48.0, "lifeline"),
            new LayoutNode("inventory", "Inventory", "inventory", 140.0, 48.0, "lifeline"));
    List<LayoutEdge> edges =
        List.of(
            new LayoutEdge("m1", "user", "storefront", "browse", "m1", "Message"),
            new LayoutEdge("m2", "storefront", "orderservice", "placeOrder", "m2", "Message"),
            new LayoutEdge("m3", "orderservice", "payment", "charge", "m3", "Message"),
            new LayoutEdge("m4", "payment", "orderservice", "charged", "m4", "Message"),
            new LayoutEdge("m5", "orderservice", "inventory", "reserve", "m5", "Message"),
            new LayoutEdge("m6", "inventory", "orderservice", "reserved", "m6", "Message"),
            new LayoutEdge("m7", "orderservice", "storefront", "confirmed", "m7", "Message"),
            new LayoutEdge("m8", "storefront", "user", "receipt", "m8", "Message"));
    return new LayoutRequest(
        "layout-request.schema.v1",
        "sequence-view",
        nodes,
        edges,
        List.of(),
        List.of(
            new LayoutConstraint(
                "sequence-view.uml.sequence.lifeline-order", "uml.sequence.lifeline-order", order),
            new LayoutConstraint(
                "sequence-view.uml.sequence.message-order",
                "uml.sequence.message-order",
                List.of("m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8"))),
        new LayoutPreferences(
            LayoutDirection.RIGHT,
            LayoutDensity.READABLE,
            null,
            new LayoutRoutingPreferences(
                LayoutRoutingStyle.ORTHOGONAL,
                LayoutRoutingProfile.READABLE,
                LayoutEndpointMerging.OFF)));
  }

  private static LayoutRequest sequenceLayoutRequest() {
    return new LayoutRequest(
        "layout-request.schema.v1",
        "sequence-view",
        List.of(
            new LayoutNode("service", "Order Service", "service", 140.0, 48.0, "lifeline"),
            new LayoutNode(
                "interaction-place-order", "Place Order", "interaction-place-order", 360.0, 260.0),
            new LayoutNode("customer", "Customer", "customer", 140.0, 48.0, "lifeline")),
        List.of(
            new LayoutEdge("m3", "service", "customer", "receiptReady", "m3", "Message"),
            new LayoutEdge("m2", "service", "customer", "accepted", "m2", "Message"),
            new LayoutEdge("m1", "customer", "service", "placeOrder", "m1", "Message")),
        List.of(),
        List.of(
            new LayoutConstraint(
                "sequence-view.uml.sequence.lifeline-order",
                "uml.sequence.lifeline-order",
                List.of("customer", "service")),
            new LayoutConstraint(
                "sequence-view.uml.sequence.message-order",
                "uml.sequence.message-order",
                List.of("m1", "m2", "m3"))),
        new LayoutPreferences(
            LayoutDirection.RIGHT,
            LayoutDensity.READABLE,
            null,
            new LayoutRoutingPreferences(
                LayoutRoutingStyle.ORTHOGONAL,
                LayoutRoutingProfile.READABLE,
                LayoutEndpointMerging.OFF)));
  }

  private static LayoutRequest sequenceLayoutRequestWithDanglingMessage() {
    return new LayoutRequest(
        "layout-request.schema.v1",
        "sequence-view",
        List.of(
            new LayoutNode("service", "Order Service", "service", 140.0, 48.0),
            new LayoutNode(
                "interaction-place-order", "Place Order", "interaction-place-order", 360.0, 260.0),
            new LayoutNode("customer", "Customer", "customer", 140.0, 48.0)),
        List.of(
            new LayoutEdge("m2", "service", "customer", "accepted", "m2", "Message"),
            new LayoutEdge("m3", "service", "missing-customer", "receiptReady", "m3", "Message"),
            new LayoutEdge("m1", "customer", "service", "placeOrder", "m1", "Message")),
        List.of(),
        List.of(
            new LayoutConstraint(
                "sequence-view.uml.sequence.lifeline-order",
                "uml.sequence.lifeline-order",
                List.of("customer", "service")),
            new LayoutConstraint(
                "sequence-view.uml.sequence.message-order",
                "uml.sequence.message-order",
                List.of("m1", "m2", "m3"))),
        new LayoutPreferences(
            LayoutDirection.RIGHT,
            LayoutDensity.READABLE,
            null,
            new LayoutRoutingPreferences(
                LayoutRoutingStyle.ORTHOGONAL,
                LayoutRoutingProfile.READABLE,
                LayoutEndpointMerging.OFF)));
  }

  private static LayoutResult sequenceLayoutResultWithMessageBendPoints() {
    return new LayoutResult(
        "layout-result.schema.v1",
        "sequence-view",
        List.of(
            new LaidOutNode(
                "service", "service", "service", 520.0, 104.0, 140.0, 48.0, "Order Service"),
            new LaidOutNode(
                "customer", "customer", "customer", 100.0, 100.0, 140.0, 48.0, "Customer")),
        List.of(
            new LaidOutEdge(
                "m1",
                "customer",
                "service",
                "m1",
                "m1",
                List.of(),
                List.of(
                    new Point(999.0, 10.0),
                    new Point(700.0, 20.0),
                    new Point(300.0, 30.0),
                    new Point(-50.0, 40.0)),
                "placeOrder")),
        List.of(),
        List.of());
  }

  private static LayoutRequest genericTwoNodeRequest(List<LayoutConstraint> constraints) {
    return new LayoutRequest(
        "layout-request.schema.v1",
        "main",
        List.of(
            new LayoutNode("customer", "Customer", "customer", 160.0, 80.0),
            new LayoutNode("service", "Service", "service", 160.0, 80.0)),
        List.of(
            new LayoutEdge(
                "customer-calls-service",
                "customer",
                "service",
                "calls",
                "customer-calls-service")),
        List.of(),
        constraints,
        null);
  }

  private static double firstSegmentY(LaidOutEdge edge) {
    assertRouted(edge);
    return edge.points().get(0).y();
  }

  private static void assertRouted(LaidOutEdge edge) {
    assertTrue(
        edge.points().size() >= 2,
        "edge " + edge.id() + " should include ELK-generated route points");
  }

  private static LaidOutNode nodeById(LayoutResult result, String id) {
    return result.nodes().stream().filter(node -> node.id().equals(id)).findFirst().orElseThrow();
  }

  private static LaidOutGroup groupById(LayoutResult result, String id) {
    return result.groups().stream()
        .filter(group -> group.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static LaidOutEdge edgeById(LayoutResult result, String id) {
    return result.edges().stream().filter(edge -> edge.id().equals(id)).findFirst().orElseThrow();
  }

  private static void assertGroupContainsNode(LaidOutGroup group, LaidOutNode node) {
    assertTrue(
        node.x() >= group.x() - GEOMETRY_EPSILON
            && node.y() >= group.y() - GEOMETRY_EPSILON
            && node.x() + node.width() <= group.x() + group.width() + GEOMETRY_EPSILON
            && node.y() + node.height() <= group.y() + group.height() + GEOMETRY_EPSILON,
        "group " + group.id() + " should contain node " + node.id());
  }

  private static void assertGroupContainsGroup(LaidOutGroup outer, LaidOutGroup inner) {
    assertTrue(
        inner.x() >= outer.x() - GEOMETRY_EPSILON
            && inner.y() >= outer.y() - GEOMETRY_EPSILON
            && inner.x() + inner.width() <= outer.x() + outer.width() + GEOMETRY_EPSILON
            && inner.y() + inner.height() <= outer.y() + outer.height() + GEOMETRY_EPSILON,
        "group " + outer.id() + " should contain child group " + inner.id());
  }

  private static void assertRouteEndpointOnSide(
      LayoutResult result, String edgeId, String nodeId, boolean start, PortSide side) {
    LaidOutEdge edge = edgeById(result, edgeId);
    LaidOutNode node = nodeById(result, nodeId);
    assertTrue(edge.points().size() >= 2, "edge " + edgeId + " should have route endpoints");
    Point point = start ? edge.points().get(0) : edge.points().get(edge.points().size() - 1);
    assertPointOnSide(point, node, side, edgeId + " endpoint for " + nodeId);
  }

  private static void assertSequenceRouteEndpointOnLifelineSide(
      LayoutResult result, String edgeId, String nodeId, boolean start, PortSide side) {
    LaidOutEdge edge = edgeById(result, edgeId);
    LaidOutNode node = nodeById(result, nodeId);
    assertRouted(edge);
    Point point = start ? edge.points().get(0) : edge.points().get(edge.points().size() - 1);
    switch (side) {
      case WEST ->
          assertEquals(node.x(), point.x(), PORT_SIDE_EPSILON, edgeId + " endpoint for " + nodeId);
      case EAST ->
          assertEquals(
              node.x() + node.width(),
              point.x(),
              PORT_SIDE_EPSILON,
              edgeId + " endpoint for " + nodeId);
      default -> throw new IllegalArgumentException("unsupported sequence lifeline side " + side);
    }
    assertTrue(
        point.y() > node.y() + node.height(),
        edgeId + " endpoint should attach below lifeline head, point=" + point + ", node=" + node);
  }

  private static void assertRouteEndpointsOnNodePerimeters(LayoutResult result, String edgeId) {
    LaidOutEdge edge = edgeById(result, edgeId);
    assertRouted(edge);
    Point sourcePoint = edge.points().get(0);
    Point targetPoint = edge.points().get(edge.points().size() - 1);
    LaidOutNode source = nodeById(result, edge.source());
    LaidOutNode target = nodeById(result, edge.target());
    assertTrue(
        routeEndpointSide(sourcePoint, source) != null,
        "edge "
            + edgeId
            + " should start on source node perimeter, point="
            + sourcePoint
            + ", node="
            + source
            + ", points="
            + edge.points());
    assertTrue(
        routeEndpointSide(targetPoint, target) != null,
        "edge "
            + edgeId
            + " should end on target node perimeter, point="
            + targetPoint
            + ", node="
            + target
            + ", points="
            + edge.points());
  }

  private static void assertPointOnSide(
      Point point, LaidOutNode node, PortSide side, String message) {
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
      LayoutResult result, String firstEdgeId, String secondEdgeId, String nodeId) {
    LaidOutNode node = nodeById(result, nodeId);
    PortSide firstSide = routeEndpointSide(edgeById(result, firstEdgeId).points().get(0), node);
    PortSide secondSide = routeEndpointSide(edgeById(result, secondEdgeId).points().get(0), node);
    return firstSide != null && secondSide != null && firstSide != secondSide;
  }

  private static PortSide routeEndpointSide(Point point, LaidOutNode node) {
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

  private static void assertWithinHorizontalBounds(Point point, LaidOutNode node, String message) {
    assertTrue(
        point.x() >= node.x() - GEOMETRY_EPSILON
            && point.x() <= node.x() + node.width() + GEOMETRY_EPSILON,
        message + " should be within horizontal node bounds, point=" + point + ", node=" + node);
  }

  private static void assertWithinVerticalBounds(Point point, LaidOutNode node, String message) {
    assertTrue(
        point.y() >= node.y() - GEOMETRY_EPSILON
            && point.y() <= node.y() + node.height() + GEOMETRY_EPSILON,
        message + " should be within vertical node bounds, point=" + point + ", node=" + node);
  }

  private static double sourcePortY(LaidOutEdge edge) {
    return edge.points().get(0).y();
  }

  private static double targetPortY(LaidOutEdge edge) {
    return edge.points().get(edge.points().size() - 1).y();
  }

  private static boolean sameVerticalOrder(
      double firstSourceY, double secondSourceY, double firstTargetY, double secondTargetY) {
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

  private static double centerY(LaidOutNode node) {
    return node.y() + node.height() / 2.0;
  }

  private static void assertGroupContainsMembers(LayoutResult result, LaidOutGroup group) {
    for (String memberId : group.members()) {
      LaidOutNode member =
          result.nodes().stream()
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
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new LayoutNode("a", "A", "a", 160.0, 80.0),
                new LayoutNode("b", "B", "b", 160.0, 80.0),
                new LayoutNode("c", "C", "c", 160.0, 80.0)),
            List.of(
                new LayoutEdge("a-to-b", "a", "b", "internal", "a-to-b"),
                new LayoutEdge("a-to-c", "a", "c", "connects", "a-to-c")),
            List.of(
                new LayoutGroup(
                    "group", "Group", List.of("a", "b"), GroupProvenance.semanticBacked("group"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    ElkLayoutRenderArtifacts.write(result);

    assertEquals(
        0,
        connectorThroughNodeCount(result),
        "cross-group routes should avoid unrelated member nodes");
  }

  private static int connectorThroughNodeCount(LayoutResult result) {
    int count = 0;
    for (LaidOutEdge edge : result.edges()) {
      for (int index = 0; index < edge.points().size() - 1; index++) {
        Point start = edge.points().get(index);
        Point end = edge.points().get(index + 1);
        for (LaidOutNode node : result.nodes()) {
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

  private static int excessiveRouteDetourCount(LayoutResult result) {
    return excessiveRouteDetourIds(result).size();
  }

  private static List<String> excessiveRouteDetourIds(LayoutResult result) {
    List<String> ids = new ArrayList<>();
    for (LaidOutEdge edge : result.edges()) {
      if (hasExcessiveRouteDetour(edge)) {
        ids.add(edge.id());
      }
    }
    return ids;
  }

  private static boolean hasExcessiveRouteDetour(LaidOutEdge edge) {
    if (edge.points().size() < 2) {
      return false;
    }
    double routeLength = routeLength(edge.points());
    Point start = edge.points().get(0);
    Point end = edge.points().get(edge.points().size() - 1);
    double directLength = Math.abs(start.x() - end.x()) + Math.abs(start.y() - end.y());
    return directLength > 0.0
        && routeLength > directLength * 1.5
        && routeLength - directLength > 240.0;
  }

  private static int routeCrossingCountNearSource(
      LaidOutEdge first, LaidOutEdge second, LaidOutNode source) {
    int count = 0;
    double nearSourceRight = source.x() + source.width() + 160.0;
    for (int firstIndex = 0; firstIndex < first.points().size() - 1; firstIndex++) {
      Point firstStart = first.points().get(firstIndex);
      Point firstEnd = first.points().get(firstIndex + 1);
      if (Math.min(firstStart.x(), firstEnd.x()) > nearSourceRight) {
        continue;
      }
      for (int secondIndex = 0; secondIndex < second.points().size() - 1; secondIndex++) {
        Point secondStart = second.points().get(secondIndex);
        Point secondEnd = second.points().get(secondIndex + 1);
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
      Point firstStart, Point firstEnd, Point secondStart, Point secondEnd) {
    RouteOrientation firstOrientation = routeOrientation(firstStart, firstEnd);
    RouteOrientation secondOrientation = routeOrientation(secondStart, secondEnd);
    if (firstOrientation == null
        || secondOrientation == null
        || firstOrientation == secondOrientation) {
      return false;
    }
    Point horizontalStart =
        firstOrientation == RouteOrientation.HORIZONTAL ? firstStart : secondStart;
    Point horizontalEnd = firstOrientation == RouteOrientation.HORIZONTAL ? firstEnd : secondEnd;
    Point verticalStart = firstOrientation == RouteOrientation.VERTICAL ? firstStart : secondStart;
    Point verticalEnd = firstOrientation == RouteOrientation.VERTICAL ? firstEnd : secondEnd;
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

  private static double routeLength(List<Point> points) {
    double length = 0.0;
    for (int index = 0; index < points.size() - 1; index++) {
      Point start = points.get(index);
      Point end = points.get(index + 1);
      length += Math.abs(start.x() - end.x()) + Math.abs(start.y() - end.y());
    }
    return length;
  }

  private static int cornerCount(List<Point> points) {
    int corners = 0;
    RouteOrientation previous = null;
    for (int index = 0; index < points.size() - 1; index++) {
      Point start = points.get(index);
      Point end = points.get(index + 1);
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

  // Pristine-routing invariant: every ELK-emitted segment must be axis-aligned. Kept independent of
  // routeOrientation()/cornerCount(): those return null for a non-orthogonal or degenerate segment
  // and then silently `continue` past it, so a stray diagonal would neither fail those checks nor
  // raise the corner count. This asserts the very property those helpers quietly assume.
  private static void assertOrthogonalRoute(LaidOutEdge edge) {
    assertRouted(edge);
    List<Point> points = edge.points();
    for (int index = 0; index < points.size() - 1; index++) {
      Point start = points.get(index);
      Point end = points.get(index + 1);
      boolean diagonal =
          Math.abs(start.x() - end.x()) > ORTHOGONAL_TOLERANCE
              && Math.abs(start.y() - end.y()) > ORTHOGONAL_TOLERANCE;
      assertFalse(
          diagonal,
          "edge "
              + edge.id()
              + " must route with axis-aligned segments, but "
              + start
              + " -> "
              + end
              + " is diagonal; route="
              + points);
    }
  }

  // Pristine-routing invariant: a clean route has no consecutive coincident points, i.e. no
  // zero-length segment. Such a point renders as an invisible no-op bend but signals sloppy route
  // assembly (for example a section boundary duplicated when ELK edge sections are concatenated).
  private static void assertNoDegenerateRouteSegments(LaidOutEdge edge) {
    assertRouted(edge);
    List<Point> points = edge.points();
    for (int index = 0; index < points.size() - 1; index++) {
      Point start = points.get(index);
      Point end = points.get(index + 1);
      assertFalse(
          samePoint(start, end),
          "edge "
              + edge.id()
              + " has a zero-length segment at index "
              + index
              + " ("
              + start
              + "); route="
              + points);
    }
  }

  private static boolean hasSharedInteriorRoutePoint(List<LaidOutEdge> edges) {
    for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
      LaidOutEdge edge = edges.get(edgeIndex);
      for (int pointIndex = 1; pointIndex < edge.points().size() - 1; pointIndex++) {
        Point point = edge.points().get(pointIndex);
        for (int otherIndex = edgeIndex + 1; otherIndex < edges.size(); otherIndex++) {
          if (containsInteriorPoint(edges.get(otherIndex), point)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean containsInteriorPoint(LaidOutEdge edge, Point candidate) {
    for (int index = 1; index < edge.points().size() - 1; index++) {
      if (samePoint(edge.points().get(index), candidate)) {
        return true;
      }
    }
    return false;
  }

  private static boolean samePoint(Point left, Point right) {
    return Math.abs(left.x() - right.x()) <= 0.001 && Math.abs(left.y() - right.y()) <= 0.001;
  }

  private enum RouteOrientation {
    HORIZONTAL,
    VERTICAL
  }

  private static RouteOrientation routeOrientation(Point start, Point end) {
    if (Double.compare(start.y(), end.y()) == 0 && Double.compare(start.x(), end.x()) != 0) {
      return RouteOrientation.HORIZONTAL;
    }
    if (Double.compare(start.x(), end.x()) == 0 && Double.compare(start.y(), end.y()) != 0) {
      return RouteOrientation.VERTICAL;
    }
    return null;
  }

  private static int endpointBoundaryOverlapCount(LayoutResult result, LaidOutEdge edge) {
    return boundaryOverlapCount(nodeById(result, edge.source()), edge.points())
        + boundaryOverlapCount(nodeById(result, edge.target()), edge.points());
  }

  private static int boundaryOverlapCount(LaidOutNode node, List<Point> points) {
    int count = 0;
    for (int index = 0; index < points.size() - 1; index++) {
      Point start = points.get(index);
      Point end = points.get(index + 1);
      if (segmentOverlapsBoundary(start, end, node)) {
        count++;
      }
    }
    return count;
  }

  private static boolean segmentOverlapsBoundary(Point start, Point end, LaidOutNode node) {
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
      double firstStart, double firstEnd, double secondStart, double secondEnd) {
    double firstMin = Math.min(firstStart, firstEnd);
    double firstMax = Math.max(firstStart, firstEnd);
    double secondMin = Math.min(secondStart, secondEnd);
    double secondMax = Math.max(secondStart, secondEnd);
    return Math.max(0.0, Math.min(firstMax, secondMax) - Math.max(firstMin, secondMin));
  }

  private static boolean segmentIntersectsRect(Point start, Point end, LaidOutNode node) {
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

  @Test
  void layeredRootMapsRoutingStyleToElkEdgeRouting() {
    assertEquals(
        EdgeRouting.ORTHOGONAL,
        ElkLayeredOptions.configuredRoot(Direction.RIGHT, null)
            .getProperty(CoreOptions.EDGE_ROUTING));
    assertEquals(
        EdgeRouting.POLYLINE,
        ElkLayeredOptions.configuredRoot(
                Direction.RIGHT, routingStylePreferences(LayoutRoutingStyle.POLYLINE))
            .getProperty(CoreOptions.EDGE_ROUTING));
    assertEquals(
        EdgeRouting.SPLINES,
        ElkLayeredOptions.configuredRoot(
                Direction.RIGHT, routingStylePreferences(LayoutRoutingStyle.SPLINE))
            .getProperty(CoreOptions.EDGE_ROUTING));
  }

  private static LayoutPreferences routingStylePreferences(LayoutRoutingStyle style) {
    return new LayoutPreferences(
        null, null, null, new LayoutRoutingPreferences(style, null, LayoutEndpointMerging.AUTO));
  }

  @Test
  void layeredRootMapsPhaseStrategiesToElkOptions() {
    LayoutPreferences prefs =
        new LayoutPreferences(
            null,
            null,
            null,
            null,
            null,
            LayoutCycleBreaking.MODEL_ORDER,
            new LayoutLayeringPreferences(LayoutLayeringStrategy.COFFMAN_GRAHAM),
            new LayoutCrossingPreferences(
                LayoutCrossingStrategy.NONE, LayoutGreedySwitch.ONE_SIDED),
            new LayoutPlacementPreferences(LayoutPlacementStrategy.NETWORK_SIMPLEX));
    ElkNode root = ElkLayeredOptions.configuredRoot(Direction.RIGHT, prefs);

    assertEquals(
        CycleBreakingStrategy.MODEL_ORDER,
        root.getProperty(LayeredOptions.CYCLE_BREAKING_STRATEGY));
    assertEquals(
        LayeringStrategy.COFFMAN_GRAHAM, root.getProperty(LayeredOptions.LAYERING_STRATEGY));
    assertEquals(
        CrossingMinimizationStrategy.NONE,
        root.getProperty(LayeredOptions.CROSSING_MINIMIZATION_STRATEGY));
    assertEquals(
        GreedySwitchType.ONE_SIDED,
        root.getProperty(LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_TYPE));
    assertEquals(
        NodePlacementStrategy.NETWORK_SIMPLEX,
        root.getProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY));

    // Absent phase-strategy fields preserve today's defaults.
    ElkNode bare = ElkLayeredOptions.configuredRoot(Direction.RIGHT, null);
    assertEquals(
        NodePlacementStrategy.BRANDES_KOEPF,
        bare.getProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY));
  }

  @Test
  void layeredRootMapsGraphTuningToElkOptions() {
    LayoutPreferences prefs =
        new LayoutPreferences(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            LayoutCompaction.BALANCED,
            new LayoutComponentsPreferences(Boolean.FALSE, LayoutComponentsSpacing.SPACIOUS),
            LayoutHighDegreeNodes.ON,
            LayoutThoroughness.HIGH);
    ElkNode root = ElkLayeredOptions.configuredRoot(Direction.RIGHT, prefs);

    assertEquals(
        GraphCompactionStrategy.LEFT_RIGHT_CONSTRAINT_LOCKING,
        root.getProperty(LayeredOptions.COMPACTION_POST_COMPACTION_STRATEGY));
    assertEquals(Boolean.FALSE, root.getProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS));
    assertEquals(Double.valueOf(60.0), root.getProperty(CoreOptions.SPACING_COMPONENT_COMPONENT));
    assertEquals(Boolean.TRUE, root.getProperty(LayeredOptions.HIGH_DEGREE_NODES_TREATMENT));
    assertEquals(Integer.valueOf(21), root.getProperty(LayeredOptions.THOROUGHNESS));
  }

  @Test
  void nonLayeredAlgorithmRejectsLayeredOnlyPreferences() {
    LayoutPreferences prefs =
        new LayoutPreferences(
            null,
            null,
            null,
            null,
            null,
            null,
            new LayoutLayeringPreferences(LayoutLayeringStrategy.NETWORK_SIMPLEX),
            null,
            null,
            null,
            null,
            null,
            null,
            LayoutAlgorithm.TREE);
    LayoutRequest request =
        new LayoutRequest("layout-request.schema.v1", "main", null, null, null, null, prefs);

    IllegalArgumentException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> new ElkLayoutEngine().layout(request));
    org.junit.jupiter.api.Assertions.assertTrue(
        ex.getMessage().contains("layered"),
        "message should explain the layered-only restriction, was: " + ex.getMessage());
  }

  @Test
  void applyNodeHintsSetsPartitionAndLayerConstraint() {
    ElkNode root = ElkGraphUtil.createGraph();
    ElkNode elkNode = ElkGraphUtil.createNode(root);
    LayoutNode node =
        new LayoutNode("n1", "N1", "n1", null, null, null, 2, LayoutLayerConstraint.FIRST);

    ElkLayeredOptions.applyNodeHints(elkNode, node);

    assertEquals(Integer.valueOf(2), elkNode.getProperty(LayeredOptions.PARTITIONING_PARTITION));
    assertEquals(
        LayerConstraint.FIRST, elkNode.getProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT));
  }

  @Test
  void activatePartitioningWhenAnyNodeHasPartition() {
    ElkNode root = ElkGraphUtil.createGraph();
    ElkLayeredOptions.activatePartitioning(
        root,
        java.util.List.of(
            new LayoutNode("a", "A", "a", null, null, null, null, null),
            new LayoutNode("b", "B", "b", null, null, null, 5, null)));

    assertEquals(Boolean.TRUE, root.getProperty(LayeredOptions.PARTITIONING_ACTIVATE));
  }
}

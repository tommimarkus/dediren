package dev.dediren.plugins.elklayout;

import static dev.dediren.ir.RouteGeometry.flatten;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.CubicBezierRoute;
import dev.dediren.contracts.layout.CubicBezierSegment;
import dev.dediren.contracts.layout.EdgeRoute;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutCrossingPreferences;
import dev.dediren.contracts.layout.LayoutCrossingStrategy;
import dev.dediren.contracts.layout.LayoutDensity;
import dev.dediren.contracts.layout.LayoutDirection;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutEdgePriority;
import dev.dediren.contracts.layout.LayoutEndpointMerging;
import dev.dediren.contracts.layout.LayoutGreedySwitch;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.LayoutRoutingPreferences;
import dev.dediren.contracts.layout.LayoutRoutingStyle;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.layout.PolylineRoute;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.elk.alg.layered.options.GreedySwitchType;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.PortAlignment;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Behavioral regressions for the native ELK routing policy. */
class ElkNativeRoutingTest {
  private static final double EPSILON = 0.001;
  private static final double NODE_BODY_INSET = 1.5;

  @Test
  void groupedUseCaseCrossHierarchyRoutesRemainUncrossedWithReservedLabels() throws Exception {
    var path =
        dev.dediren.testsupport.TestSupport.workspaceRoot()
            .resolve("fixtures/layout-request/uml-use-case-basic.json");
    try (var input = java.nio.file.Files.newInputStream(path)) {
      LayoutResult result = new ElkLayoutEngine().layout(LayoutJson.readLayoutRequest(input));
      assertTrue(
          properRouteCrossings(result).isEmpty(),
          () -> "cross-hierarchy crossings: " + properRouteCrossings(result));
    }
  }

  @Test
  void groupedRootLeavesFeedbackCyclesToNativeElkRouting() {
    ElkNode root = ElkGraphUtil.createGraph();

    ElkLayeredOptions.configureGroupedRoot(root, Direction.RIGHT, null);

    assertEquals(Boolean.FALSE, root.getProperty(LayeredOptions.FEEDBACK_EDGES));
    assertEquals(
        GreedySwitchType.TWO_SIDED,
        root.getProperty(LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_HIERARCHICAL_TYPE));
    assertEquals(PortAlignment.JUSTIFIED, root.getProperty(CoreOptions.PORT_ALIGNMENT_DEFAULT));
  }

  @ParameterizedTest
  @MethodSource("greedySwitches")
  void explicitGreedySwitchMapsToFlatAndHierarchicalSweeps(
      LayoutGreedySwitch requested, GreedySwitchType expected) {
    LayoutPreferences preferences =
        preferences(
            LayoutDirection.RIGHT,
            LayoutDensity.READABLE,
            LayoutEndpointMerging.OFF,
            new LayoutCrossingPreferences(LayoutCrossingStrategy.LAYER_SWEEP, requested));
    ElkNode root = ElkGraphUtil.createGraph();

    ElkLayeredOptions.configureGroupedRoot(root, Direction.RIGHT, preferences);

    assertEquals(
        expected, root.getProperty(LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_TYPE));
    assertEquals(
        expected,
        root.getProperty(LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_HIERARCHICAL_TYPE));
  }

  @Test
  void crossingStrategyNoneDoesNotInjectAHierarchicalGreedySweep() {
    LayoutPreferences preferences =
        preferences(
            LayoutDirection.RIGHT,
            LayoutDensity.READABLE,
            LayoutEndpointMerging.OFF,
            new LayoutCrossingPreferences(LayoutCrossingStrategy.NONE, null));
    ElkNode root = ElkGraphUtil.createGraph();

    ElkLayeredOptions.configureGroupedRoot(root, Direction.RIGHT, preferences);

    assertFalse(
        root.hasProperty(LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_HIERARCHICAL_TYPE));
  }

  @Test
  void crossingStrategyNoneOverridesAnExplicitGreedySwitch() {
    LayoutPreferences preferences =
        preferences(
            LayoutDirection.RIGHT,
            LayoutDensity.READABLE,
            LayoutEndpointMerging.OFF,
            new LayoutCrossingPreferences(
                LayoutCrossingStrategy.NONE, LayoutGreedySwitch.ONE_SIDED));
    ElkNode root = ElkGraphUtil.createGraph();

    ElkLayeredOptions.configureGroupedRoot(root, Direction.RIGHT, preferences);

    assertFalse(root.hasProperty(LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_TYPE));
    assertFalse(
        root.hasProperty(LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_HIERARCHICAL_TYPE));
  }

  @ParameterizedTest
  @MethodSource("loopSpacings")
  void nativeSelfLoopSpacingFollowsDensity(LayoutDensity density, double expected) {
    ElkNode root = ElkLayeredOptions.configuredRoot(Direction.RIGHT, preferences(density));

    assertEquals(expected, root.getProperty(CoreOptions.SPACING_NODE_SELF_LOOP), EPSILON);
  }

  @Test
  void visualBandRejectsAnExplicitPartitionThatConflictsWithItsBand() {
    LayoutRequest request =
        request(
            List.of(new LayoutNode("a", "A", "a", 80.0, 40.0, null, 7, null)),
            List.of(),
            List.of(
                new LayoutGroup("band", "Band", List.of("a"), GroupProvenance.visualOnlyGroup())),
            preferences(LayoutDensity.COMPACT));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new ElkLayoutEngine().layout(request));

    assertTrue(error.getMessage().contains("$.nodes[0].partition"), error.getMessage());
    assertTrue(error.getMessage().contains("visual band"), error.getMessage());
  }

  @Test
  void visualBandsRejectASecondClaimOnTheSameNode() {
    LayoutRequest request =
        request(
            List.of(new LayoutNode("a", "A", "a", 80.0, 40.0)),
            List.of(),
            List.of(
                new LayoutGroup("first", "First", List.of("a"), GroupProvenance.visualOnlyGroup()),
                new LayoutGroup(
                    "second", "Second", List.of("a"), GroupProvenance.visualOnlyGroup())),
            preferences(LayoutDensity.COMPACT));

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new ElkLayoutEngine().layout(request));

    assertTrue(error.getMessage().contains("$.groups[1].members[0]"), error.getMessage());
    assertTrue(error.getMessage().contains("visual band"), error.getMessage());
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("bandCases")
  void visualBandsReserveTheirPaddingAndAnEdgeChannelBeforeLayout(
      LayoutDirection direction, LayoutDensity density, double expectedGap) {
    LayoutRequest request =
        request(
            List.of(
                new LayoutNode("a", "A", "a", 80.0, 40.0),
                new LayoutNode("b", "B", "b", 80.0, 40.0)),
            List.of(new LayoutEdge("a-b", "a", "b", "flow", "a-b")),
            List.of(
                new LayoutGroup("first", "First", List.of("a"), GroupProvenance.visualOnlyGroup()),
                new LayoutGroup(
                    "second", "Second", List.of("b"), GroupProvenance.visualOnlyGroup())),
            preferences(direction, density, LayoutEndpointMerging.OFF, null));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    LaidOutGroup first = group(result, "first");
    LaidOutGroup second = group(result, "second");

    assertTrue(result.warnings().isEmpty(), "band spacing should not need an overlap warning");
    assertTrue(
        separation(first, second, direction) + EPSILON >= expectedGap,
        () ->
            "bands should leave an edge channel of at least "
                + expectedGap
                + ", gap="
                + separation(first, second, direction));
  }

  @ParameterizedTest(name = "{0} {1} merging={2}")
  @MethodSource("routingCases")
  void nativeGroupedCyclesAvoidNodeBodiesAndIndependentRouteCrossings(
      LayoutDirection direction, LayoutDensity density, LayoutEndpointMerging merging) {
    LayoutRequest request = groupedCycleRequest(direction, density, merging);

    LayoutResult result = new ElkLayoutEngine().layout(request);

    assertTrue(nodeBodyHits(result).isEmpty(), () -> "node body hits: " + nodeBodyHits(result));
    assertTrue(
        properRouteCrossings(result).isEmpty(),
        () -> "route crossings: " + properRouteCrossings(result));
    assertTrue(
        edge(result, "a-loop").routingHints().isEmpty(),
        "a self-loop keeps dedicated endpoints even when ordinary endpoints merge");
  }

  @ParameterizedTest(name = "{0} {1} {2} merging={3}")
  @MethodSource("styleCases")
  void everyNativeRoutingStyleRemainsTypedAndSourceToTargetAcrossPreferences(
      LayoutRoutingStyle style,
      LayoutDirection direction,
      LayoutDensity density,
      LayoutEndpointMerging merging) {
    LayoutPreferences preferences =
        new LayoutPreferences(
            null,
            direction,
            density,
            null,
            new LayoutRoutingPreferences(style, merging),
            null,
            null,
            null,
            null);
    LayoutRequest request =
        request(
            List.of(
                new LayoutNode("source", "Source", "source", 80.0, 40.0),
                new LayoutNode("target", "Target", "target", 80.0, 40.0)),
            List.of(new LayoutEdge("flow", "source", "target", "flow", "flow", "flow")),
            List.of(
                new LayoutGroup(
                    "source-group",
                    "Source group",
                    List.of("source"),
                    GroupProvenance.semanticBacked("source-group")),
                new LayoutGroup(
                    "target-group",
                    "Target group",
                    List.of("target"),
                    GroupProvenance.semanticBacked("target-group"))),
            preferences);

    LayoutResult result = new ElkLayoutEngine().layout(request);
    LaidOutEdge edge = edge(result, "flow");
    EdgeRoute route = edge.route();
    List<Point> points = flatten(route);

    if (style == LayoutRoutingStyle.SPLINE) {
      assertTrue(route instanceof CubicBezierRoute, () -> "expected cubic route, got " + route);
    } else {
      assertTrue(route instanceof PolylineRoute, () -> "expected polyline route, got " + route);
    }
    assertTrue(points.size() >= 2, () -> "route should have endpoints: " + route);
    assertTrue(
        onPerimeter(points.getFirst(), node(result, "source")), "route must start at source");
    assertTrue(onPerimeter(points.getLast(), node(result, "target")), "route must end at target");
  }

  @Test
  void downwardCompactForkAndJoinKeepLateralPortsOnTheirPaintedBars() {
    LayoutRequest request =
        request(
            List.of(
                new LayoutNode("before", "Before", "before", 160.0, 80.0),
                new LayoutNode("fork", "", "fork", 32.0, 32.0),
                new LayoutNode("primary", "Primary", "primary", 160.0, 80.0),
                new LayoutNode("side", "Side", "side", 160.0, 80.0),
                new LayoutNode("join", "", "join", 32.0, 32.0),
                new LayoutNode("after", "After", "after", 160.0, 80.0)),
            List.of(
                new LayoutEdge("before-fork", "before", "fork", "", "before-fork"),
                new LayoutEdge("fork-primary", "fork", "primary", "", "fork-primary"),
                new LayoutEdge("fork-side", "fork", "side", "", "fork-side"),
                new LayoutEdge("primary-join", "primary", "join", "", "primary-join"),
                new LayoutEdge("side-join", "side", "join", "", "side-join"),
                new LayoutEdge("join-after", "join", "after", "", "join-after")),
            List.of(),
            preferences(
                LayoutDirection.DOWN, LayoutDensity.COMPACT, LayoutEndpointMerging.OFF, null));

    LayoutResult result = new ElkLayoutEngine().layout(request);
    LaidOutNode fork = node(result, "fork");
    LaidOutNode join = node(result, "join");

    assertEquals(32.0, fork.width(), EPSILON, "compact fork must keep its authored width");
    assertEquals(32.0, fork.height(), EPSILON, "compact fork must keep its authored height");
    assertEquals(32.0, join.width(), EPSILON, "compact join must keep its authored width");
    assertEquals(32.0, join.height(), EPSILON, "compact join must keep its authored height");
    assertTrue(fork.width() >= fork.height(), "downward fork bar must remain horizontal");
    assertTrue(join.width() >= join.height(), "downward join bar must remain horizontal");
    assertTrue(
        touchesPaintedUmlBar(flatten(edge(result, "fork-side").route()).getFirst(), fork),
        "fork's lateral branch must touch the painted fork bar");
    assertTrue(
        touchesPaintedUmlBar(flatten(edge(result, "side-join").route()).getLast(), join),
        "join's lateral branch must touch the painted join bar");
  }

  private static Stream<Arguments> greedySwitches() {
    return Stream.of(
        Arguments.of(LayoutGreedySwitch.OFF, GreedySwitchType.OFF),
        Arguments.of(LayoutGreedySwitch.ONE_SIDED, GreedySwitchType.ONE_SIDED),
        Arguments.of(LayoutGreedySwitch.TWO_SIDED, GreedySwitchType.TWO_SIDED));
  }

  private static Stream<Arguments> loopSpacings() {
    return Stream.of(
        Arguments.of(LayoutDensity.COMPACT, 24.0),
        Arguments.of(LayoutDensity.READABLE, 48.0),
        Arguments.of(LayoutDensity.SPACIOUS, 64.0));
  }

  private static Stream<Arguments> bandCases() {
    List<Arguments> cases = new ArrayList<>();
    for (LayoutDirection direction : LayoutDirection.values()) {
      cases.add(Arguments.of(direction, LayoutDensity.COMPACT, 24.0));
      cases.add(Arguments.of(direction, LayoutDensity.READABLE, 48.0));
      cases.add(Arguments.of(direction, LayoutDensity.SPACIOUS, 64.0));
    }
    return cases.stream();
  }

  private static Stream<Arguments> routingCases() {
    List<Arguments> cases = new ArrayList<>();
    for (LayoutDirection direction : LayoutDirection.values()) {
      for (LayoutDensity density : LayoutDensity.values()) {
        for (LayoutEndpointMerging merging :
            List.of(LayoutEndpointMerging.OFF, LayoutEndpointMerging.AUTO)) {
          cases.add(Arguments.of(direction, density, merging));
        }
      }
    }
    return cases.stream();
  }

  private static Stream<Arguments> styleCases() {
    List<Arguments> cases = new ArrayList<>();
    for (LayoutRoutingStyle style : LayoutRoutingStyle.values()) {
      for (LayoutDirection direction : LayoutDirection.values()) {
        for (LayoutDensity density : LayoutDensity.values()) {
          for (LayoutEndpointMerging merging :
              List.of(LayoutEndpointMerging.OFF, LayoutEndpointMerging.AUTO)) {
            cases.add(Arguments.of(style, direction, density, merging));
          }
        }
      }
    }
    return cases.stream();
  }

  private static LayoutRequest groupedCycleRequest(
      LayoutDirection direction, LayoutDensity density, LayoutEndpointMerging merging) {
    List<LayoutNode> nodes =
        List.of(
            new LayoutNode("a", "A", "a", 80.0, 40.0),
            new LayoutNode("b", "B", "b", 80.0, 40.0),
            new LayoutNode("c", "C", "c", 80.0, 40.0),
            new LayoutNode("d", "D", "d", 80.0, 40.0));
    List<LayoutEdge> edges =
        List.of(
            new LayoutEdge("a-b", "a", "b", "flow", "a-b", "flow", new LayoutEdgePriority(9, 5, 5)),
            new LayoutEdge("b-a", "b", "a", "return", "b-a", "return"),
            new LayoutEdge("b-c", "b", "c", "flow", "b-c", "flow"),
            new LayoutEdge("c-d", "c", "d", "flow", "c-d", "flow"),
            new LayoutEdge("d-b", "d", "b", "return", "d-b", "return"),
            new LayoutEdge("a-c", "a", "c", "shortcut", "a-c", "shortcut"),
            new LayoutEdge("a-loop", "a", "a", "retry", "a-loop", "flow"));
    List<LayoutGroup> groups =
        List.of(
            new LayoutGroup(
                "left", "Left", List.of("a", "b"), GroupProvenance.semanticBacked("left")),
            new LayoutGroup(
                "right", "Right", List.of("c", "d"), GroupProvenance.semanticBacked("right")));
    return request(nodes, edges, groups, preferences(direction, density, merging, null));
  }

  private static LayoutPreferences preferences(LayoutDensity density) {
    return preferences(LayoutDirection.RIGHT, density, LayoutEndpointMerging.OFF, null);
  }

  private static LayoutPreferences preferences(
      LayoutDirection direction,
      LayoutDensity density,
      LayoutEndpointMerging merging,
      LayoutCrossingPreferences crossing) {
    return new LayoutPreferences(
        null,
        direction,
        density,
        null,
        new LayoutRoutingPreferences(LayoutRoutingStyle.ORTHOGONAL, merging),
        null,
        null,
        crossing,
        null);
  }

  private static LayoutRequest request(
      List<LayoutNode> nodes,
      List<LayoutEdge> edges,
      List<LayoutGroup> groups,
      LayoutPreferences preferences) {
    return new LayoutRequest(
        ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
        "native-routing",
        nodes,
        edges,
        groups,
        List.of(),
        preferences);
  }

  private static LaidOutGroup group(LayoutResult result, String id) {
    return result.groups().stream()
        .filter(group -> group.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static LaidOutEdge edge(LayoutResult result, String id) {
    return result.edges().stream().filter(edge -> edge.id().equals(id)).findFirst().orElseThrow();
  }

  private static LaidOutNode node(LayoutResult result, String id) {
    return result.nodes().stream().filter(node -> node.id().equals(id)).findFirst().orElseThrow();
  }

  private static boolean onPerimeter(Point point, LaidOutNode node) {
    double left = node.x();
    double right = node.x() + node.width();
    double top = node.y();
    double bottom = node.y() + node.height();
    boolean withinX = point.x() >= left - 1.0 && point.x() <= right + 1.0;
    boolean withinY = point.y() >= top - 1.0 && point.y() <= bottom + 1.0;
    return withinX
        && withinY
        && (Math.abs(point.x() - left) <= 1.0
            || Math.abs(point.x() - right) <= 1.0
            || Math.abs(point.y() - top) <= 1.0
            || Math.abs(point.y() - bottom) <= 1.0);
  }

  private static boolean touchesPaintedUmlBar(Point point, LaidOutNode node) {
    boolean horizontal = node.width() >= node.height();
    double width = horizontal ? node.width() : Math.min(node.width(), 14.0);
    double height = horizontal ? Math.min(node.height(), 14.0) : node.height();
    double left = node.x() + (node.width() - width) / 2.0;
    double top = node.y() + (node.height() - height) / 2.0;
    double dx = Math.max(left - point.x(), Math.max(0.0, point.x() - (left + width)));
    double dy = Math.max(top - point.y(), Math.max(0.0, point.y() - (top + height)));
    return Math.hypot(dx, dy) <= 1.0 + EPSILON;
  }

  private static double separation(
      LaidOutGroup first, LaidOutGroup second, LayoutDirection direction) {
    if (direction == LayoutDirection.LEFT || direction == LayoutDirection.RIGHT) {
      return Math.max(first.x(), second.x())
          - Math.min(first.x() + first.width(), second.x() + second.width());
    }
    return Math.max(first.y(), second.y())
        - Math.min(first.y() + first.height(), second.y() + second.height());
  }

  /** Route/body predicate independent of production route normalization and quality checks. */
  private static List<String> nodeBodyHits(LayoutResult result) {
    List<String> hits = new ArrayList<>();
    for (LaidOutEdge edge : result.edges()) {
      List<Point> points = oraclePoints(edge.route());
      for (int segment = 0; segment < points.size() - 1; segment++) {
        Point start = points.get(segment);
        Point end = points.get(segment + 1);
        for (LaidOutNode node : result.nodes()) {
          if (intersectsOpenBox(start, end, node)) {
            hits.add(edge.id() + "[" + segment + "] through " + node.id());
          }
        }
      }
    }
    return hits;
  }

  private static boolean intersectsOpenBox(Point start, Point end, LaidOutNode node) {
    double left = node.x() + NODE_BODY_INSET;
    double right = node.x() + node.width() - NODE_BODY_INSET;
    double top = node.y() + NODE_BODY_INSET;
    double bottom = node.y() + node.height() - NODE_BODY_INSET;
    return Math.max(start.x(), end.x()) > left
        && Math.min(start.x(), end.x()) < right
        && Math.max(start.y(), end.y()) > top
        && Math.min(start.y(), end.y()) < bottom;
  }

  /** Proper segment intersections, excluding shared endpoints and collinear overlap. */
  private static List<String> properRouteCrossings(LayoutResult result) {
    List<String> crossings = new ArrayList<>();
    for (int leftIndex = 0; leftIndex < result.edges().size(); leftIndex++) {
      LaidOutEdge left = result.edges().get(leftIndex);
      List<Point> leftPoints = oraclePoints(left.route());
      for (int rightIndex = leftIndex + 1; rightIndex < result.edges().size(); rightIndex++) {
        LaidOutEdge right = result.edges().get(rightIndex);
        List<Point> rightPoints = oraclePoints(right.route());
        for (int leftSegment = 0; leftSegment < leftPoints.size() - 1; leftSegment++) {
          for (int rightSegment = 0; rightSegment < rightPoints.size() - 1; rightSegment++) {
            if (properlyCrosses(
                leftPoints.get(leftSegment),
                leftPoints.get(leftSegment + 1),
                rightPoints.get(rightSegment),
                rightPoints.get(rightSegment + 1))) {
              crossings.add(
                  left.id() + "[" + leftSegment + "] x " + right.id() + "[" + rightSegment + "]");
            }
          }
        }
      }
    }
    return crossings;
  }

  private static boolean properlyCrosses(Point a, Point b, Point c, Point d) {
    double abC = cross(a, b, c);
    double abD = cross(a, b, d);
    double cdA = cross(c, d, a);
    double cdB = cross(c, d, b);
    return abC * abD < -EPSILON && cdA * cdB < -EPSILON;
  }

  private static double cross(Point a, Point b, Point point) {
    return (b.x() - a.x()) * (point.y() - a.y()) - (b.y() - a.y()) * (point.x() - a.x());
  }

  /**
   * Fixed, test-local sampling keeps the native-route oracle independent from production
   * flattening.
   */
  private static List<Point> oraclePoints(EdgeRoute route) {
    if (route instanceof PolylineRoute polyline) {
      return polyline.points();
    }
    CubicBezierRoute cubic = (CubicBezierRoute) route;
    List<Point> points = new ArrayList<>();
    Point start = cubic.start();
    points.add(start);
    for (CubicBezierSegment segment : cubic.segments()) {
      for (int sample = 1; sample <= 128; sample++) {
        points.add(cubicPoint(start, segment, sample / 128.0));
      }
      start = segment.end();
    }
    return points;
  }

  private static Point cubicPoint(Point start, CubicBezierSegment segment, double t) {
    double u = 1.0 - t;
    return new Point(
        u * u * u * start.x()
            + 3.0 * u * u * t * segment.control1().x()
            + 3.0 * u * t * t * segment.control2().x()
            + t * t * t * segment.end().x(),
        u * u * u * start.y()
            + 3.0 * u * u * t * segment.control1().y()
            + 3.0 * u * t * t * segment.control2().y()
            + t * t * t * segment.end().y());
  }
}

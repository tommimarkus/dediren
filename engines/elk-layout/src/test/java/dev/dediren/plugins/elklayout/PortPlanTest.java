package dev.dediren.plugins.elklayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutEndpointMerging;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRoutingPreferences;
import dev.dediren.contracts.layout.LayoutRoutingStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.PortSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PortPlanTest {
  @Test
  void ordinaryFlatAndGroupedEndpointsStayFree() {
    List<LayoutEdge> edges = List.of(edge("a-b", "a", "b", "flow"));
    Map<String, LayoutNode> nodes = nodes("a", "b");

    PortPlan flat =
        PortPlan.flat(PortPlan.Ordering.UNCONSTRAINED, edges, nodes, null, Direction.RIGHT);
    PortPlan grouped =
        PortPlan.grouped(edges, nodes, Map.of("a", "g", "b", "g"), Direction.RIGHT, null);

    assertFalse(flat.fixesSourceSide("a-b"));
    assertFalse(flat.fixesTargetSide("a-b"));
    assertFalse(grouped.fixesSourceSide("a-b"));
    assertFalse(grouped.fixesTargetSide("a-b"));
    assertEquals(PortPlan.Ordering.GROUPED, grouped.ordering());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("compactDirections")
  void compactSplitReturnKeepsOnlyItsControlEndpointsFixed(
      Direction direction, PortSide primarySource, PortSide primaryTarget, PortSide alternate) {
    Map<String, LayoutNode> nodes = new LinkedHashMap<>();
    nodes.put("split", new LayoutNode("split", "Split", "split", 36.0, 36.0));
    nodes.put("left", new LayoutNode("left", "Left", "left", 80.0, 40.0));
    nodes.put("right", new LayoutNode("right", "Right", "right", 80.0, 40.0));
    nodes.put("join", new LayoutNode("join", "Join", "join", 36.0, 36.0));
    List<LayoutEdge> edges =
        List.of(
            edge("split-left", "split", "left", "flow"),
            edge("split-right", "split", "right", "flow"),
            edge("left-join", "left", "join", "flow"),
            edge("right-join", "right", "join", "flow"));

    PortPlan plan =
        PortPlan.flat(PortPlan.Ordering.UNCONSTRAINED, edges, nodes, noMerge(), direction);

    assertTrue(plan.fixesSourceSide("split-left"));
    assertTrue(plan.fixesSourceSide("split-right"));
    assertTrue(plan.fixesTargetSide("left-join"));
    assertTrue(plan.fixesTargetSide("right-join"));
    assertFalse(plan.fixesTargetSide("split-left"));
    assertFalse(plan.fixesSourceSide("left-join"));
    assertEquals(primarySource, plan.sourceSide("split-left"));
    assertEquals(alternate, plan.sourceSide("split-right"));
    assertEquals(primaryTarget, plan.targetSide("left-join"));
    assertEquals(alternate, plan.targetSide("right-join"));
  }

  @Test
  void selfLoopNeverSharesAnEndpointPort() {
    Map<String, LayoutNode> nodes = nodes("a", "b", "c", "d");
    List<LayoutEdge> edges =
        List.of(
            edge("loop", "a", "a", "flow"),
            edge("a-b", "a", "b", "flow"),
            edge("a-c", "a", "c", "flow"),
            edge("a-d", "a", "d", "flow"));

    PortPlan plan =
        PortPlan.flat(PortPlan.Ordering.UNCONSTRAINED, edges, nodes, autoMerge(), Direction.RIGHT);

    assertTrue(plan.routingHints("loop").isEmpty());
    assertTrue(plan.routingHints("a-b").contains("shared_source_junction"));
  }

  @Test
  void relationshipTypesKeepIndependentMergePorts() {
    Map<String, LayoutNode> nodes = nodes("a", "b", "c", "d", "e", "f", "g");
    List<LayoutEdge> edges =
        List.of(
            edge("f1", "a", "b", "flow"),
            edge("f2", "a", "c", "flow"),
            edge("f3", "a", "d", "flow"),
            edge("d1", "a", "e", "data"),
            edge("d2", "a", "f", "data"),
            edge("d3", "a", "g", "data"));

    PortPlan plan =
        PortPlan.flat(PortPlan.Ordering.UNCONSTRAINED, edges, nodes, autoMerge(), Direction.RIGHT);

    assertTrue(plan.routingHints("f1").contains("shared_source_junction"));
    assertTrue(plan.routingHints("d1").contains("shared_source_junction"));
    assertEquals(plan.routingHints("f1"), plan.routingHints("f2"));
    assertEquals(plan.routingHints("d1"), plan.routingHints("d2"));
  }

  private static Stream<Arguments> compactDirections() {
    return Stream.of(
        Arguments.of(Direction.RIGHT, PortSide.EAST, PortSide.WEST, PortSide.NORTH),
        Arguments.of(Direction.LEFT, PortSide.WEST, PortSide.EAST, PortSide.NORTH),
        Arguments.of(Direction.DOWN, PortSide.SOUTH, PortSide.NORTH, PortSide.EAST),
        Arguments.of(Direction.UP, PortSide.NORTH, PortSide.SOUTH, PortSide.EAST));
  }

  private static Map<String, LayoutNode> nodes(String... ids) {
    Map<String, LayoutNode> nodes = new LinkedHashMap<>();
    for (String id : ids) {
      nodes.put(id, new LayoutNode(id, id, id, 80.0, 40.0));
    }
    return nodes;
  }

  private static LayoutEdge edge(String id, String source, String target, String type) {
    return new LayoutEdge(id, source, target, type, id, type);
  }

  private static LayoutPreferences noMerge() {
    return preferences(LayoutEndpointMerging.OFF);
  }

  private static LayoutPreferences autoMerge() {
    return preferences(LayoutEndpointMerging.AUTO);
  }

  private static LayoutPreferences preferences(LayoutEndpointMerging merging) {
    return new LayoutPreferences(
        null, null, null, new LayoutRoutingPreferences(LayoutRoutingStyle.ORTHOGONAL, merging));
  }
}

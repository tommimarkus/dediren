package dev.dediren.plugins.elklayout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutDensity;
import dev.dediren.contracts.layout.LayoutDirection;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutEndpointMerging;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.LayoutRoutingPreferences;
import dev.dediren.contracts.layout.LayoutRoutingStyle;
import dev.dediren.contracts.layout.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.elk.core.options.Direction;
import org.junit.jupiter.api.Test;

/**
 * The back-edge decisions {@link PortPlan} makes for {@code layoutGrouped}, and the property the
 * whole fix rests on.
 *
 * <p>{@code ElkLayoutLaneAgreementTest} covers the shape the lanes used to disagree about, and
 * {@code ElkLayoutInvariantFuzzTest} sweeps geometry. Neither reaches these three: the fuzz
 * generator puts every node in a group, so a cycle that closes through an <em>ungrouped</em> node
 * never appears in it; the anti-parallel group axis needs a specific direction pairing; and no test
 * anywhere asserts the acyclicity the reversal set exists to produce.
 */
class PortPlanTest {

  /** How far into a node's bounds a route may reach before it counts as crossing the body. */
  private static final double NODE_BODY_INSET = 1.5;

  /**
   * A cycle between a grouped node and an ungrouped one. The predicate this replaced required both
   * endpoints to have an owner, so it could not see this cycle at all and neither edge was ever
   * reversed; ELK broke the cycle its own way, against the pinned port sides, and the route came
   * back through both node bodies.
   */
  @Test
  void aCycleThroughAnUngroupedNodeIsStillDecided() {
    LayoutRequest request =
        request(
            List.of(node("inside"), node("outside")),
            List.of(
                new LayoutEdge("out", "inside", "outside", "flow", "out"),
                new LayoutEdge("back", "outside", "inside", "flow", "back")),
            List.of(
                new LayoutGroup(
                    "boundary",
                    "Boundary",
                    List.of("inside"),
                    GroupProvenance.semanticBacked("boundary"))),
            LayoutDirection.RIGHT);

    assertThat(nodeBodyCrossings(new ElkLayoutEngine().layout(request)))
        .as("a cycle closing through an ungrouped node must not route through the node bodies")
        .isEmpty();
  }

  /**
   * A group whose own {@code internalDirection} comes out as the exact opposite of the root
   * direction. That is not a cross-flow, it is the layer axis pointing backwards: the member edge's
   * two ports go on the leading and trailing faces in the wrong order, ELK still layers along the
   * root, and the route has to come back across its own target to reconnect.
   *
   * <p>Reduced from a generated counter-example rather than invented, because the crossing only
   * appears once the drawing is tight enough that the route has no way round: the {@code gate}
   * endpoint pulls {@code gate -> store} onto the connector rule's {@code RIGHT} axis while the
   * root runs {@code LEFT}, and the return edges hold the two groups against each other. Removing
   * the correction from {@code alongLayerAxis} puts the route back through {@code store}'s body.
   */
  @Test
  void aGroupAxisPointingBackAlongTheRootAxisIsCorrected() {
    LayoutRequest request =
        request(
            List.of(
                connectorNode("entry"),
                node("sink"),
                connectorNode("gate"),
                node("store")),
            List.of(
                new LayoutEdge("entry-to-gate", "entry", "gate", "flow", "r1"),
                new LayoutEdge("gate-to-store", "gate", "store", "flow", "r2"),
                new LayoutEdge("store-to-entry", "store", "entry", "flow", "r3"),
                new LayoutEdge("store-to-sink", "store", "sink", "flow", "r4")),
            List.of(
                new LayoutGroup(
                    "edge-tier",
                    "Edge",
                    List.of("entry", "sink"),
                    GroupProvenance.semanticBacked("edge-tier")),
                new LayoutGroup(
                    "core-tier",
                    "Core",
                    List.of("gate", "store"),
                    GroupProvenance.semanticBacked("core-tier"))),
            LayoutDirection.LEFT);

    assertThat(nodeBodyCrossings(new ElkLayoutEngine().layout(request)))
        .as("a member edge must not be pinned against the root's own layer axis")
        .isEmpty();
  }

  /**
   * The property everything else rests on: once the plan's reversal set is applied, the digraph
   * handed to ELK has no cycle left, so ELK's cycle breaker finds nothing to break and cannot
   * pick a feedback arc that disagrees with the port sides pinned from the same ranking.
   *
   * <p>Asserted on the arcs directly rather than through geometry, because geometry can only ever
   * show that some particular drawing came out well.
   */
  @Test
  void theGraphHandedToElkIsAcyclic() {
    List<LayoutEdge> edges =
        List.of(
            // a three-node cycle inside one group
            new LayoutEdge("a-b", "a", "b", "flow", "e1"),
            new LayoutEdge("b-c", "b", "c", "flow", "e2"),
            new LayoutEdge("c-a", "c", "a", "flow", "e3"),
            // a cycle across the two groups
            new LayoutEdge("c-d", "c", "d", "flow", "e4"),
            new LayoutEdge("d-a", "d", "a", "flow", "e5"),
            // and one through a node no group claims
            new LayoutEdge("d-loose", "d", "loose", "flow", "e6"),
            new LayoutEdge("loose-b", "loose", "b", "flow", "e7"));
    List<LayoutNode> nodes =
        List.of(node("a"), node("b"), node("c"), node("d"), node("loose"));
    List<LayoutGroup> groups =
        List.of(
            new LayoutGroup(
                "first", "First", List.of("a", "b", "c"), GroupProvenance.semanticBacked("first")),
            new LayoutGroup(
                "second", "Second", List.of("d"), GroupProvenance.semanticBacked("second")));

    assertThat(hasCycle(presentedArcs(nodes, edges, groups, false)))
        .as("guard: the declared graph really is cyclic, so the check below is not vacuous")
        .isTrue();
    assertThat(hasCycle(presentedArcs(nodes, edges, groups, true)))
        .as("the reversal set must leave ELK an acyclic graph")
        .isFalse();
  }

  /**
   * The old cross-group rule, kept as a case of the general one rather than beside it: an edge
   * whose source sits in a later-declared group than its target is reversed, and one running the
   * declared way round is not.
   */
  @Test
  void groupDeclarationOrderStillDecidesCrossGroupEdges() {
    List<LayoutNode> nodes = List.of(node("early"), node("late"));
    List<LayoutEdge> edges =
        List.of(
            new LayoutEdge("forward", "early", "late", "flow", "f"),
            new LayoutEdge("backward", "late", "early", "flow", "b"));
    List<LayoutGroup> groups =
        List.of(
            new LayoutGroup(
                "first", "First", List.of("early"), GroupProvenance.semanticBacked("first")),
            new LayoutGroup(
                "second", "Second", List.of("late"), GroupProvenance.semanticBacked("second")));

    PortPlan plan = groupedPlan(nodes, edges, groups);

    assertThat(plan.ordering()).isEqualTo(PortPlan.Ordering.GROUPED);
    assertThat(plan.reversed("backward"))
        .as("an edge out of the later-declared group runs against the lane's order")
        .isTrue();
    assertThat(plan.reversed("forward")).isFalse();
  }

  /** The flat family declares its ordering and contributes no reversals under either of them. */
  @Test
  void theFlatFamilyDecidesNoBackEdges() {
    List<LayoutNode> nodes = List.of(node("a"), node("b"));
    Map<String, LayoutNode> nodesById = nodesById(nodes);
    List<LayoutEdge> edges =
        List.of(
            new LayoutEdge("a-b", "a", "b", "flow", "e1"),
            new LayoutEdge("b-a", "b", "a", "flow", "e2"));

    for (PortPlan.Ordering ordering :
        List.of(PortPlan.Ordering.UNCONSTRAINED, PortPlan.Ordering.PARTITIONED)) {
      PortPlan plan = PortPlan.flat(ordering, edges, nodesById, null, Direction.RIGHT);
      assertThat(plan.ordering()).isEqualTo(ordering);
      assertThat(plan.reversed("a-b")).isFalse();
      assertThat(plan.reversed("b-a"))
          .as("%s leaves the feedback arc to ELK's own cycle breaker", ordering)
          .isFalse();
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static PortPlan groupedPlan(
      List<LayoutNode> nodes, List<LayoutEdge> edges, List<LayoutGroup> groups) {
    Map<String, LayoutNode> nodesById = nodesById(nodes);
    Map<String, String> ownerByNode = new HashMap<>();
    Map<String, Integer> groupOrderById = new HashMap<>();
    Map<String, Direction> groupDirectionById = new HashMap<>();
    for (int index = 0; index < groups.size(); index++) {
      LayoutGroup group = groups.get(index);
      groupOrderById.put(group.id(), index);
      groupDirectionById.put(group.id(), Direction.RIGHT);
      for (String member : group.members()) {
        if (nodesById.containsKey(member)) {
          ownerByNode.putIfAbsent(member, group.id());
        }
      }
    }
    return PortPlan.grouped(
        edges,
        nodes,
        nodesById,
        ownerByNode,
        groupDirectionById,
        groupOrderById,
        Direction.RIGHT,
        null);
  }

  /** The arcs {@code layoutGrouped} hands ELK, with and without the plan's reversal applied. */
  private static List<String[]> presentedArcs(
      List<LayoutNode> nodes, List<LayoutEdge> edges, List<LayoutGroup> groups, boolean applyPlan) {
    PortPlan plan = groupedPlan(nodes, edges, groups);
    List<String[]> arcs = new ArrayList<>();
    for (LayoutEdge edge : edges) {
      boolean reversed = applyPlan && plan.reversed(edge.id());
      arcs.add(
          reversed
              ? new String[] {edge.target(), edge.source()}
              : new String[] {edge.source(), edge.target()});
    }
    return arcs;
  }

  /** Kahn's algorithm: a node that never reaches in-degree zero is on a cycle. */
  private static boolean hasCycle(List<String[]> arcs) {
    Map<String, List<String>> outgoing = new LinkedHashMap<>();
    Map<String, Integer> indegree = new LinkedHashMap<>();
    for (String[] arc : arcs) {
      outgoing.computeIfAbsent(arc[0], id -> new ArrayList<>()).add(arc[1]);
      indegree.putIfAbsent(arc[0], 0);
      indegree.merge(arc[1], 1, Integer::sum);
    }
    List<String> ready = new ArrayList<>();
    indegree.forEach(
        (id, degree) -> {
          if (degree == 0) {
            ready.add(id);
          }
        });
    int settled = 0;
    while (!ready.isEmpty()) {
      String current = ready.remove(ready.size() - 1);
      settled++;
      for (String next : outgoing.getOrDefault(current, List.of())) {
        if (indegree.merge(next, -1, Integer::sum) == 0) {
          ready.add(next);
        }
      }
    }
    return settled != indegree.size();
  }

  /**
   * Every routed segment that reaches further than {@link #NODE_BODY_INSET} into a node's bounds,
   * including the segment's own source and target. Same check as {@code
   * ElkLayoutLaneAgreementTest}, kept to a bounding-box test here because these fixtures route
   * orthogonally.
   */
  private static List<String> nodeBodyCrossings(LayoutResult result) {
    List<String> crossings = new ArrayList<>();
    for (LaidOutEdge edge : result.edges()) {
      List<Point> points = edge.points();
      for (int index = 0; index < points.size() - 1; index++) {
        Point start = points.get(index);
        Point end = points.get(index + 1);
        for (LaidOutNode node : result.nodes()) {
          double left = node.x() + NODE_BODY_INSET;
          double right = node.x() + node.width() - NODE_BODY_INSET;
          double top = node.y() + NODE_BODY_INSET;
          double bottom = node.y() + node.height() - NODE_BODY_INSET;
          if (right <= left || bottom <= top) {
            continue;
          }
          if (Math.max(start.x(), end.x()) > left
              && Math.min(start.x(), end.x()) < right
              && Math.max(start.y(), end.y()) > top
              && Math.min(start.y(), end.y()) < bottom) {
            crossings.add(
                "edge " + edge.id() + " segment " + index + " crosses node " + node.id());
          }
        }
      }
    }
    return crossings;
  }

  private static Map<String, LayoutNode> nodesById(List<LayoutNode> nodes) {
    Map<String, LayoutNode> byId = new LinkedHashMap<>();
    for (LayoutNode node : nodes) {
      byId.put(node.id(), node);
    }
    return byId;
  }

  private static LayoutNode node(String id) {
    return new LayoutNode(id, id, id, 160.0, 80.0);
  }

  private static LayoutNode connectorNode(String id) {
    return new LayoutNode(id, id, id, 36.0, 36.0);
  }

  private static LayoutRequest request(
      List<LayoutNode> nodes,
      List<LayoutEdge> edges,
      List<LayoutGroup> groups,
      LayoutDirection direction) {
    return new LayoutRequest(
        ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
        "port-plan",
        nodes,
        edges,
        groups,
        List.of(),
        new LayoutPreferences(
            direction,
            LayoutDensity.READABLE,
            null,
            new LayoutRoutingPreferences(
                LayoutRoutingStyle.ORTHOGONAL, LayoutEndpointMerging.OFF)));
  }
}

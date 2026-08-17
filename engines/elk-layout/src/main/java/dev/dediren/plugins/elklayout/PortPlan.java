package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPreferences;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.PortSide;

/**
 * Everything {@link ElkLayoutEngine} decides about an edge's endpoints before ELK runs: which side
 * of each node the edge attaches to, whether that attachment is a shared junction port, how many
 * ports each node side therefore has to carry, and which edges are handed to ELK reversed.
 *
 * <p><b>Why one type.</b> The four {@code layout*} lanes used to carry two independent copies of
 * this decision — a {@code flat*} family with no back-edge concept and a {@code grouped*} family
 * with one — so the answer depended on which copy the lane happened to call. The unifying rule is:
 *
 * <blockquote>
 *
 * A lane that pins port sides against an order it has itself already fixed must also decide which
 * edges run against that order, because ELK's cycle breaker can no longer make that decision
 * consistently with the pinned sides.
 *
 * </blockquote>
 *
 * <p>{@link Ordering} names how much order each lane has fixed, and the reversal set comes from
 * that ordering rather than from a lane-local special case. {@code isCrossGroupBackEdge}'s old
 * "different group owners, later group first" test is not bolted on beside the general rule: under
 * {@link Ordering#GROUPED} the node ranking is keyed on group declaration index first, so an edge
 * between two different groups is reversed exactly when the old predicate said so.
 *
 * <p><b>No port indices.</b> Nodes are configured {@code PortConstraints.FIXED_SIDE} and no {@code
 * PORT_INDEX} is written anywhere, so a plan carries sides, merges, counts and reversal only. Order
 * within a side is ELK's.
 */
final class PortPlan {

  /**
   * The default node box. Port planning has to assume the same box the sizer does — {@link
   * #isConnectorSized} classifies a node by the size it will actually get, and {@code
   * ElkLayoutEngine#setGeneratedDimensions} grows that same box to fit the ports counted here.
   */
  static final double DEFAULT_WIDTH = 160.0;

  static final double DEFAULT_HEIGHT = 80.0;

  private static final double CONNECTOR_SOURCE_MAX_WIDTH = 48.0;
  private static final double CONNECTOR_SOURCE_MAX_HEIGHT = 48.0;
  private static final int MERGEABLE_ENDPOINT_EDGE_COUNT = 3;
  private static final String SHARED_SOURCE_JUNCTION_HINT = "shared_source_junction";
  private static final String SHARED_TARGET_JUNCTION_HINT = "shared_target_junction";
  private static final EndpointMerge NO_ENDPOINT_MERGE = new EndpointMerge(false, false);

  /**
   * Rank of a node that no group claims. Groups are ranked by declaration index from 0, so -1 reads
   * as "declared before any group" and, more importantly, is a fixed rank: the acyclicity argument
   * needs the node ranking to be a total order, not any particular one.
   */
  private static final int UNGROUPED_RANK = -1;

  /** How much of the layer order a lane has already fixed by the time ports are planned. */
  enum Ordering {
    /**
     * {@code layoutFlat}: nothing is fixed. There is no compound node, so a feedback edge routes
     * around the outside of the drawing, and ELK's cycle breaker is free to reverse whatever it
     * likes. The plan contributes no reversals.
     */
    UNCONSTRAINED,

    /**
     * {@code layoutFlatBanded}: one ELK partition per group fixes which band a node lands in, but
     * the members are still root-level nodes, so — as in {@link #UNCONSTRAINED} — a feedback edge
     * still has the whole outside of the drawing to route through. The plan therefore contributes
     * no reversals here either. This is a measured claim, not an assumption: a forced banded sweep
     * of 1500 generated cyclic graphs (2..8 nodes, every direction, density, endpoint merging and
     * grouping split, including partly ungrouped ones) produced zero route-geometry violations, and
     * so did the same sweep with no groups at all. The constant is named separately from {@link
     * #UNCONSTRAINED} because the lanes differ in what they fix, and this is where a
     * partition-derived reversal set would go if evidence ever demanded one.
     */
    PARTITIONED,

    /**
     * {@code layoutGrouped}: members nest inside ELK compound nodes, which fixes the order <em>and
     * </em> confines each edge's route to its group's bounds ({@code
     * ElkGraphUtil#updateContainment}). Both halves matter: this is the one lane where an edge
     * whose ports are pinned against ELK's chosen layer order has nowhere to go except back across
     * the node bodies. The plan therefore chooses its own feedback-arc set.
     */
    GROUPED;

    boolean decidesBackEdges() {
      return this == GROUPED;
    }
  }

  private final Ordering ordering;
  private final Map<String, EndpointSides> sides;
  private final Map<String, EndpointMerge> merges;
  private final Map<String, EnumMap<PortSide, Integer>> portCounts;
  private final Set<String> reversedEdges;
  private final EndpointSides defaultSides;

  private PortPlan(
      Ordering ordering,
      Map<String, EndpointSides> sides,
      Map<String, EndpointMerge> merges,
      Map<String, EnumMap<PortSide, Integer>> portCounts,
      Set<String> reversedEdges,
      EndpointSides defaultSides) {
    this.ordering = ordering;
    this.sides = sides;
    this.merges = merges;
    this.portCounts = portCounts;
    this.reversedEdges = reversedEdges;
    this.defaultSides = defaultSides;
  }

  // --- queries ----------------------------------------------------------------------------------

  Ordering ordering() {
    return ordering;
  }

  /** Side of the declared source's node the edge leaves from, before any reversal swap. */
  PortSide sourceSide(String edgeId) {
    return sides.getOrDefault(edgeId, defaultSides).sourceSide();
  }

  /** Side of the declared target's node the edge arrives at, before any reversal swap. */
  PortSide targetSide(String edgeId) {
    return sides.getOrDefault(edgeId, defaultSides).targetSide();
  }

  boolean mergesSourceEndpoint(String edgeId) {
    return merges.getOrDefault(edgeId, NO_ENDPOINT_MERGE).sourceEndpoint();
  }

  boolean mergesTargetEndpoint(String edgeId) {
    return merges.getOrDefault(edgeId, NO_ENDPOINT_MERGE).targetEndpoint();
  }

  /**
   * True when the edge must be handed to ELK the other way round. The caller swaps the endpoints
   * and the merge flags at {@code createRoutedEdge} and reverses the resulting route points, so the
   * published edge keeps its declared source-to-target orientation.
   */
  boolean reversed(String edgeId) {
    return reversedEdges.contains(edgeId);
  }

  /** Ports this plan puts on each side of a node, or {@code null} when the node carries none. */
  Map<PortSide, Integer> portCounts(String nodeId) {
    return portCounts.get(nodeId);
  }

  List<String> routingHints(String edgeId) {
    EndpointMerge merge = merges.getOrDefault(edgeId, NO_ENDPOINT_MERGE);
    List<String> hints = new ArrayList<>();
    if (merge.sourceEndpoint()) {
      hints.add(SHARED_SOURCE_JUNCTION_HINT);
    }
    if (merge.targetEndpoint()) {
      hints.add(SHARED_TARGET_JUNCTION_HINT);
    }
    return hints;
  }

  // --- construction: the flat family --------------------------------------------------------

  /**
   * Plan for {@code layoutFlat} and, through it, {@code layoutFlatBanded}. Neither ordering
   * contributes reversals (see {@link Ordering}); {@code GROUPED} is rejected because this builder
   * has no group ownership to rank by.
   */
  static PortPlan flat(
      Ordering ordering,
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      LayoutPreferences preferences,
      Direction direction) {
    if (ordering.decidesBackEdges()) {
      throw new IllegalArgumentException(
          "the flat port-plan family cannot rank nodes for " + ordering);
    }
    Map<String, EndpointMerge> merges = flatEndpointMerges(edges, nodes, preferences, direction);
    Map<String, EndpointSides> sides = flatEndpointSides(edges, nodes, merges, direction);
    return new PortPlan(
        ordering,
        sides,
        merges,
        portCounts(edges, nodes, sides, merges, Set.of(), direction),
        Set.of(),
        defaultEndpointSides(direction));
  }

  /**
   * Plan for a sequence-constrained {@code layoutFlat}: the message lattice, not the layered graph,
   * owns the port sides, and endpoint merging is off so every message keeps its own port.
   */
  static PortPlan sequence(
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      LayoutIntentNormalizer sequenceConstraints) {
    Direction direction = Direction.RIGHT;
    EndpointSides defaults = defaultEndpointSides(direction);
    Map<String, EndpointSides> sides = new HashMap<>();
    for (LayoutEdge edge : edges) {
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        continue;
      }
      sides.put(
          edge.id(),
          new EndpointSides(
              sequenceConstraints.sourcePortSide(edge, defaults.sourceSide()),
              sequenceConstraints.targetPortSide(edge, defaults.targetSide())));
    }
    Map<String, EndpointMerge> merges = emptyEndpointMerges(edges);
    return new PortPlan(
        Ordering.UNCONSTRAINED,
        sides,
        merges,
        portCounts(edges, nodes, sides, merges, Set.of(), direction),
        Set.of(),
        defaults);
  }

  // --- construction: the grouped family -----------------------------------------------------

  /**
   * Plan for {@code layoutGrouped}. Ranks the nodes, reverses every edge that runs against that
   * rank, and pins each edge's ports on the axis its endpoints sit on.
   *
   * <p>The axis is unchanged from the old {@code edgeDirection} except for one branch that reversal
   * now expresses better. {@code edgeDirection} answered a cross-group back-edge with the opposite
   * of the root direction, and then the call site ignored that answer and used the root direction
   * anyway because it was building the edge reversed. Reversal is now the general mechanism, so the
   * axis function no longer needs the "opposite" case at all: a reversed edge is handed to ELK
   * forwards and its ports go on the forward sides of its own axis, which for a cross-group edge is
   * the root direction — byte-for-byte what the old back-edge branch did.
   *
   * <p>The per-group {@code internalDirection} axis for a member-to-member edge survives, but only
   * where it is not anti-parallel to the root — see {@link #alongLayerAxis}. It survives at all
   * because {@code ElkLayoutEngineTest#groupedConnectorEdgesKeepHorizontalFlowInsideVerticalGroups}
   * pins it on purpose, not because it is right: the grouped root is configured {@code
   * HierarchyHandling.INCLUDE_CHILDREN}, so ELK lays the whole hierarchy out along the root's
   * direction and a nested compound node's own {@code DIRECTION} never moves a layer. A
   * perpendicular group axis is therefore still measurably wrong — a 3-node chain in a {@code DOWN}
   * group under a {@code RIGHT} root comes out as a diagonal staircase, and a member edge that
   * skips a layer can still reach its target's face from the wrong side (28 interior crossings in
   * 600 generated <em>acyclic</em> grouped graphs). Retiring it is a separate decision that has to
   * move that test, so it is recorded rather than taken here.
   */
  static PortPlan grouped(
      List<LayoutEdge> edges,
      List<LayoutNode> nodes,
      Map<String, LayoutNode> nodesById,
      Map<String, String> ownerByNode,
      Map<String, Direction> groupDirectionById,
      Map<String, Integer> groupOrderById,
      Direction rootDirection,
      LayoutPreferences preferences) {
    Map<String, Integer> rank = groupedNodeRank(nodes, edges, ownerByNode, groupOrderById);
    Set<String> reversedEdges = reversedEdges(edges, nodesById, rank);

    EndpointSides defaults = defaultEndpointSides(rootDirection);
    Map<String, EndpointSides> sides = new HashMap<>();
    for (LayoutEdge edge : edges) {
      if (!nodesById.containsKey(edge.source()) || !nodesById.containsKey(edge.target())) {
        continue;
      }
      sides.put(
          edge.id(),
          defaultEndpointSides(
              endpointAxis(edge, nodesById, ownerByNode, groupDirectionById, rootDirection)));
    }
    Map<String, EndpointMerge> merges =
        groupedEndpointMerges(
            edges, nodesById, ownerByNode, sides, reversedEdges, defaults, preferences);
    return new PortPlan(
        Ordering.GROUPED,
        sides,
        merges,
        portCounts(edges, nodesById, sides, merges, reversedEdges, rootDirection),
        reversedEdges,
        defaults);
  }

  /** The direction whose leading/trailing sides this edge's two ports sit on. */
  private static Direction endpointAxis(
      LayoutEdge edge,
      Map<String, LayoutNode> nodes,
      Map<String, String> ownerByNode,
      Map<String, Direction> groupDirectionById,
      Direction rootDirection) {
    String sourceOwner = ownerByNode.get(edge.source());
    if (sourceOwner == null || !sourceOwner.equals(ownerByNode.get(edge.target()))) {
      return rootDirection;
    }
    if (isConnectorSized(nodes.get(edge.source())) || isConnectorSized(nodes.get(edge.target()))) {
      // A same-source service fan-out through a small junction node reads as a left-to-right call
      // flow whatever the group's own stacking axis is.
      return alongLayerAxis(Direction.RIGHT, rootDirection);
    }
    return alongLayerAxis(
        groupDirectionById.getOrDefault(sourceOwner, Direction.RIGHT), rootDirection);
  }

  /**
   * A member edge's axis, corrected where it points <em>backwards</em> along ELK's own layer axis.
   *
   * <p>A group's {@code internalDirection} is chosen from the group's shape alone and knows nothing
   * about the root, so it can come out as the exact opposite of the root direction — {@code RIGHT}
   * inside a {@code LEFT} drawing, say, which the connector rule produces unconditionally. That is
   * never a cross-flow, it is the same axis pointing the wrong way: the ports go on the leading and
   * trailing faces in the wrong order, ELK still layers along the root, and the edge has to come
   * back across its own target's body to reconnect. Every interior crossing left in the grouped
   * lane once back-edges were decided was one of these.
   *
   * <p>A <em>perpendicular</em> group axis is left alone. That is the deliberate "horizontal flow
   * inside a vertical group" intent pinned by {@code
   * ElkLayoutEngineTest#groupedConnectorEdgesKeepHorizontalFlowInsideVerticalGroups}, and, unlike
   * the anti-parallel case, it does not put the two ports on opposite ends of the layer axis, so it
   * costs readability (a node staircase) rather than correctness.
   */
  private static Direction alongLayerAxis(Direction axis, Direction rootDirection) {
    boolean antiParallel =
        switch (axis) {
          case LEFT -> rootDirection == Direction.RIGHT;
          case RIGHT -> rootDirection == Direction.LEFT;
          case UP -> rootDirection == Direction.DOWN;
          case DOWN -> rootDirection == Direction.UP;
          default -> false;
        };
    return antiParallel ? rootDirection : axis;
  }

  /**
   * A strict total order over the nodes, keyed on group declaration index first and, inside one
   * bucket, on a depth-first reverse postorder of that bucket's own edges.
   *
   * <p>The bucket key is what preserves the old cross-group rule exactly: two nodes owned by
   * different groups compare by group declaration index and nothing else, so an edge between them
   * runs backwards precisely when the later-declared group is its source — which is what {@code
   * isCrossGroupBackEdge} tested. A node no group claims gets {@link #UNGROUPED_RANK}, the one case
   * the old predicate could not see at all (it required both owners non-null), which is why a cycle
   * between a grouped and an ungrouped node used to route through both bodies.
   *
   * <p>Reverse postorder is used inside a bucket because every edge that is not a depth-first back
   * edge runs forwards in it, so the reversal set below is a small feedback-arc set rather than an
   * arbitrary one.
   */
  private static Map<String, Integer> groupedNodeRank(
      List<LayoutNode> nodes,
      List<LayoutEdge> edges,
      Map<String, String> ownerByNode,
      Map<String, Integer> groupOrderById) {
    Map<String, Integer> bucketByNode = new LinkedHashMap<>();
    TreeMap<Integer, List<String>> membersByBucket = new TreeMap<>();
    for (LayoutNode node : nodes) {
      String owner = ownerByNode.get(node.id());
      int bucket =
          owner == null ? UNGROUPED_RANK : groupOrderById.getOrDefault(owner, UNGROUPED_RANK);
      bucketByNode.put(node.id(), bucket);
      membersByBucket.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(node.id());
    }

    Map<String, List<String>> adjacency = new HashMap<>();
    for (LayoutEdge edge : edges) {
      Integer sourceBucket = bucketByNode.get(edge.source());
      Integer targetBucket = bucketByNode.get(edge.target());
      if (sourceBucket == null
          || targetBucket == null
          || !sourceBucket.equals(targetBucket)
          || edge.source().equals(edge.target())) {
        continue;
      }
      adjacency.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge.target());
    }

    Map<String, Integer> rank = new HashMap<>();
    int next = 0;
    for (List<String> members : membersByBucket.values()) {
      for (String id : reversePostorder(members, adjacency)) {
        rank.put(id, next++);
      }
    }
    return rank;
  }

  /**
   * Iterative depth-first reverse postorder over {@code members}, following {@code adjacency} in
   * request order and starting new trees in declaration order. Iterative rather than recursive so a
   * long chain cannot overflow the stack.
   */
  private static List<String> reversePostorder(
      List<String> members, Map<String, List<String>> adjacency) {
    List<String> postorder = new ArrayList<>(members.size());
    Set<String> seen = new HashSet<>();
    for (String root : members) {
      if (!seen.add(root)) {
        continue;
      }
      Deque<String> stack = new ArrayDeque<>();
      Deque<Integer> cursors = new ArrayDeque<>();
      stack.push(root);
      cursors.push(0);
      while (!stack.isEmpty()) {
        String current = stack.peek();
        List<String> successors = adjacency.getOrDefault(current, List.of());
        int cursor = cursors.pop();
        if (cursor < successors.size()) {
          cursors.push(cursor + 1);
          String successor = successors.get(cursor);
          if (seen.add(successor)) {
            stack.push(successor);
            cursors.push(0);
          }
        } else {
          stack.pop();
          postorder.add(current);
        }
      }
    }
    Collections.reverse(postorder);
    return postorder;
  }

  /**
   * The feedback-arc set: every edge whose source outranks its target.
   *
   * <p>This is what makes the graph handed to ELK acyclic, and the argument is the ranking's, not
   * the traversal's. After the swap every presented arc runs strictly forwards in a single total
   * order over the nodes, and a directed cycle would need a strictly increasing walk returning to
   * its own start, so no cycle survives. ELK's cycle breaker then finds nothing to break and cannot
   * disagree with the port sides pinned from the same ranking.
   *
   * <p>A self-loop is excluded: its endpoints have equal rank, so it is neither forwards nor
   * backwards, and ELK routes self-loops outside layering where they create no cycle to break.
   */
  private static Set<String> reversedEdges(
      List<LayoutEdge> edges, Map<String, LayoutNode> nodes, Map<String, Integer> rank) {
    Set<String> reversed = new HashSet<>();
    for (LayoutEdge edge : edges) {
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        continue;
      }
      Integer sourceRank = rank.get(edge.source());
      Integer targetRank = rank.get(edge.target());
      if (sourceRank != null && targetRank != null && sourceRank > targetRank) {
        reversed.add(edge.id());
      }
    }
    return reversed;
  }

  // --- endpoint merging -------------------------------------------------------------------------

  // Endpoint merging is Dediren graph shaping, not route geometry. ELK's MERGE_EDGES and
  // MERGE_HIERARCHY_EDGES are broad booleans; Dediren keeps relationship-type scoped shared ports
  // so intentional fan-in/fan-out junctions remain readable without globally merging unrelated
  // edges.
  private static Map<String, EndpointMerge> flatEndpointMerges(
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      LayoutPreferences preferences,
      Direction direction) {
    if (!ElkLayeredOptions.endpointMergingEnabled(preferences)) {
      return emptyEndpointMerges(edges);
    }

    Map<EndpointKey, Integer> endpointCounts = new HashMap<>();
    for (LayoutEdge edge : edges) {
      String relationshipType = relationshipType(edge);
      if (!nodes.containsKey(edge.source())
          || !nodes.containsKey(edge.target())
          || edge.source().equals(edge.target())
          || relationshipType == null) {
        continue;
      }
      endpointCounts.merge(
          new EndpointKey(edge.source(), sourcePortSide(direction), true, relationshipType),
          1,
          Integer::sum);
      endpointCounts.merge(
          new EndpointKey(edge.target(), targetPortSide(direction), false, relationshipType),
          1,
          Integer::sum);
    }

    Map<String, EndpointMerge> merges = new HashMap<>();
    for (LayoutEdge edge : edges) {
      String relationshipType = relationshipType(edge);
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        continue;
      }
      merges.put(
          edge.id(),
          new EndpointMerge(
              relationshipType != null
                  && endpointCounts.getOrDefault(
                          new EndpointKey(
                              edge.source(), sourcePortSide(direction), true, relationshipType),
                          0)
                      >= MERGEABLE_ENDPOINT_EDGE_COUNT,
              relationshipType != null
                  && endpointCounts.getOrDefault(
                          new EndpointKey(
                              edge.target(), targetPortSide(direction), false, relationshipType),
                          0)
                      >= MERGEABLE_ENDPOINT_EDGE_COUNT));
    }
    return merges;
  }

  private static Map<String, EndpointMerge> groupedEndpointMerges(
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      Map<String, String> ownerByNode,
      Map<String, EndpointSides> sides,
      Set<String> reversedEdges,
      EndpointSides defaults,
      LayoutPreferences preferences) {
    if (!ElkLayeredOptions.endpointMergingEnabled(preferences)) {
      return emptyEndpointMerges(edges);
    }

    Set<String> sourceOnlyGroups = sourceOnlyGroups(edges, ownerByNode);
    Map<EndpointKey, Integer> endpointCounts = new HashMap<>();
    for (LayoutEdge edge : edges) {
      String relationshipType = relationshipType(edge);
      if (!nodes.containsKey(edge.source())
          || !nodes.containsKey(edge.target())
          || edge.source().equals(edge.target())
          || sameOwnerInternalEdge(edge, ownerByNode)
          || relationshipType == null) {
        continue;
      }
      endpointCounts.merge(
          declaredSourcePort(edge, sides, reversedEdges, defaults, relationshipType),
          1,
          Integer::sum);
      endpointCounts.merge(
          declaredTargetPort(edge, sides, reversedEdges, defaults, relationshipType),
          1,
          Integer::sum);
    }

    Map<String, EndpointMerge> merges = new HashMap<>();
    for (LayoutEdge edge : edges) {
      String relationshipType = relationshipType(edge);
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        continue;
      }
      boolean mergeableEndpoint =
          relationshipType != null && !sameOwnerInternalEdge(edge, ownerByNode);
      // Actor/source-only groups often have only two equivalent entry edges into a platform node.
      // Merge that target pair so ELK owns a single shared endpoint instead of forcing an arbitrary
      // port order.
      boolean sourceOnlyGroupTargetEndpoint =
          sourceOnlyGroups.contains(ownerByNode.get(edge.source()));
      merges.put(
          edge.id(),
          new EndpointMerge(
              mergeableEndpoint
                  && endpointCounts.getOrDefault(
                          declaredSourcePort(
                              edge, sides, reversedEdges, defaults, relationshipType),
                          0)
                      >= MERGEABLE_ENDPOINT_EDGE_COUNT,
              mergeableEndpoint
                  && endpointCounts.getOrDefault(
                          declaredTargetPort(
                              edge, sides, reversedEdges, defaults, relationshipType),
                          0)
                      >= (sourceOnlyGroupTargetEndpoint ? 2 : MERGEABLE_ENDPOINT_EDGE_COUNT)));
    }
    return merges;
  }

  private static Set<String> sourceOnlyGroups(
      List<LayoutEdge> edges, Map<String, String> ownerByNode) {
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

  private static boolean sameOwnerInternalEdge(LayoutEdge edge, Map<String, String> ownerByNode) {
    String sourceOwner = ownerByNode.get(edge.source());
    return sourceOwner != null && sourceOwner.equals(ownerByNode.get(edge.target()));
  }

  // --- port identity and counting ---------------------------------------------------------------

  /**
   * The port {@code createRoutedEdge} will actually build on the declared source's node.
   *
   * <p>A reversed edge is created as {@code createRoutedEdge(target, source, ...)}, so the declared
   * source becomes ELK's target endpoint and takes the plan's target side. Keying merges and counts
   * on the port that is really built — rather than on a direction the endpoint no longer has — is
   * what keeps a shared junction port counted once and named once.
   */
  private static EndpointKey declaredSourcePort(
      LayoutEdge edge,
      Map<String, EndpointSides> sides,
      Set<String> reversedEdges,
      EndpointSides defaults,
      String relationshipType) {
    EndpointSides edgeSides = sides.getOrDefault(edge.id(), defaults);
    boolean reversed = reversedEdges.contains(edge.id());
    return new EndpointKey(
        edge.source(),
        reversed ? edgeSides.targetSide() : edgeSides.sourceSide(),
        !reversed,
        relationshipType);
  }

  /** The port {@code createRoutedEdge} will actually build on the declared target's node. */
  private static EndpointKey declaredTargetPort(
      LayoutEdge edge,
      Map<String, EndpointSides> sides,
      Set<String> reversedEdges,
      EndpointSides defaults,
      String relationshipType) {
    EndpointSides edgeSides = sides.getOrDefault(edge.id(), defaults);
    boolean reversed = reversedEdges.contains(edge.id());
    return new EndpointKey(
        edge.target(),
        reversed ? edgeSides.sourceSide() : edgeSides.targetSide(),
        reversed,
        relationshipType);
  }

  /**
   * Ports per node side. ELK accounts for the generated ports itself, but Dediren still raises a
   * node's minimum side length when many ports would otherwise be packed onto one side, so the
   * count has to match the ports that are really built — including the single shared port that a
   * run of merged endpoints collapses to.
   */
  private static Map<String, EnumMap<PortSide, Integer>> portCounts(
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      Map<String, EndpointSides> sides,
      Map<String, EndpointMerge> merges,
      Set<String> reversedEdges,
      Direction direction) {
    EndpointSides defaults = defaultEndpointSides(direction);
    Map<String, EnumMap<PortSide, Integer>> portCounts = new HashMap<>();
    Set<EndpointKey> countedMergePorts = new HashSet<>();
    for (LayoutEdge edge : edges) {
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        continue;
      }
      EndpointMerge merge = merges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
      String relationshipType = relationshipType(edge);
      countPort(
          portCounts,
          countedMergePorts,
          declaredSourcePort(edge, sides, reversedEdges, defaults, relationshipType),
          merge.sourceEndpoint());
      countPort(
          portCounts,
          countedMergePorts,
          declaredTargetPort(edge, sides, reversedEdges, defaults, relationshipType),
          merge.targetEndpoint());
    }
    return portCounts;
  }

  private static void countPort(
      Map<String, EnumMap<PortSide, Integer>> portCounts,
      Set<EndpointKey> countedMergePorts,
      EndpointKey port,
      boolean mergeEndpoint) {
    if (mergeEndpoint && !countedMergePorts.add(port)) {
      return;
    }
    portCounts
        .computeIfAbsent(port.nodeId(), ignored -> new EnumMap<>(PortSide.class))
        .merge(port.side(), 1, Integer::sum);
  }

  // --- sides --------------------------------------------------------------------------------

  private static Map<String, EndpointSides> flatEndpointSides(
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      Map<String, EndpointMerge> merges,
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
    Map<String, EndpointSides> sidesByEdge = new HashMap<>();
    EndpointSides defaultSides = defaultEndpointSides(direction);
    for (LayoutEdge edge : edges) {
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        continue;
      }
      EndpointMerge merge = merges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
      int outgoingIndex = nextEndpointIndex(outgoingIndexes, edge.source());
      int incomingIndex = nextEndpointIndex(incomingIndexes, edge.target());
      PortSide sourceSide = defaultSides.sourceSide();
      PortSide targetSide = defaultSides.targetSide();
      if (!merge.sourceEndpoint()
          && outgoingCounts.getOrDefault(edge.source(), 0) > 1
          && isConnectorSized(nodes.get(edge.source()))) {
        sourceSide = connectorBranchSide(direction, true, outgoingIndex);
      }
      if (!merge.targetEndpoint()
          && incomingCounts.getOrDefault(edge.target(), 0) > 1
          && isConnectorSized(nodes.get(edge.target()))) {
        targetSide = connectorBranchSide(direction, false, incomingIndex);
      }
      sidesByEdge.put(edge.id(), new EndpointSides(sourceSide, targetSide));
    }
    return sidesByEdge;
  }

  private static int nextEndpointIndex(Map<String, Integer> indexes, String nodeId) {
    int index = indexes.getOrDefault(nodeId, 0);
    indexes.put(nodeId, index + 1);
    return index;
  }

  private static PortSide connectorBranchSide(
      Direction direction, boolean sourceEndpoint, int index) {
    PortSide primary = sourceEndpoint ? sourcePortSide(direction) : targetPortSide(direction);
    PortSide[] alternates =
        switch (primary) {
          case EAST, WEST -> new PortSide[] {primary, PortSide.NORTH, PortSide.SOUTH};
          case NORTH, SOUTH -> new PortSide[] {primary, PortSide.EAST, PortSide.WEST};
          default -> new PortSide[] {primary};
        };
    return alternates[Math.min(index, alternates.length - 1)];
  }

  private static EndpointSides defaultEndpointSides(Direction direction) {
    return new EndpointSides(sourcePortSide(direction), targetPortSide(direction));
  }

  private static Map<String, EndpointMerge> emptyEndpointMerges(List<LayoutEdge> edges) {
    Map<String, EndpointMerge> merges = new HashMap<>();
    for (LayoutEdge edge : edges) {
      merges.put(edge.id(), NO_ENDPOINT_MERGE);
    }
    return merges;
  }

  // --- shared vocabulary ------------------------------------------------------------------------

  static PortSide sourcePortSide(Direction direction) {
    return switch (direction) {
      case DOWN -> PortSide.SOUTH;
      case LEFT -> PortSide.WEST;
      case UP -> PortSide.NORTH;
      default -> PortSide.EAST;
    };
  }

  static PortSide targetPortSide(Direction direction) {
    return switch (direction) {
      case DOWN -> PortSide.NORTH;
      case LEFT -> PortSide.EAST;
      case UP -> PortSide.SOUTH;
      default -> PortSide.WEST;
    };
  }

  /** {@code null} rather than a blank string, so an untyped edge never shares a junction port. */
  static String relationshipType(LayoutEdge edge) {
    String relationshipType = edge.relationshipType();
    if (relationshipType == null || relationshipType.isBlank()) {
      return null;
    }
    return relationshipType;
  }

  /** A node small enough that fanning its edges onto one side would crowd it. */
  static boolean isConnectorSized(LayoutNode node) {
    if (node == null) {
      return false;
    }
    double width = node.widthHint() == null ? DEFAULT_WIDTH : node.widthHint();
    double height = node.heightHint() == null ? DEFAULT_HEIGHT : node.heightHint();
    return width <= CONNECTOR_SOURCE_MAX_WIDTH && height <= CONNECTOR_SOURCE_MAX_HEIGHT;
  }

  private record EndpointSides(PortSide sourceSide, PortSide targetSide) {}

  private record EndpointMerge(boolean sourceEndpoint, boolean targetEndpoint) {}

  private record EndpointKey(
      String nodeId, PortSide side, boolean sourceEndpoint, String relationshipType) {}
}

package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.PortSide;

/** Plans only the endpoint intent Dediren owns before ELK runs. */
final class PortPlan {
  static final double DEFAULT_WIDTH = 160.0;
  static final double DEFAULT_HEIGHT = 80.0;

  private static final double CONNECTOR_SOURCE_MAX_WIDTH = 48.0;
  private static final double CONNECTOR_SOURCE_MAX_HEIGHT = 48.0;
  private static final int MERGEABLE_ENDPOINT_EDGE_COUNT = 3;
  private static final String SHARED_SOURCE_JUNCTION_HINT = "shared_source_junction";
  private static final String SHARED_TARGET_JUNCTION_HINT = "shared_target_junction";
  private static final EndpointMerge NO_ENDPOINT_MERGE = new EndpointMerge(false, false);

  /** The layout lane, retained as an explicit statement of which constraints precede ELK. */
  enum Ordering {
    UNCONSTRAINED,
    PARTITIONED,
    GROUPED
  }

  private final Ordering ordering;
  private final Map<String, EndpointSides> sides;
  private final Map<String, EndpointMerge> merges;
  private final Set<String> fixedSourceEndpoints;
  private final Set<String> fixedTargetEndpoints;
  private final EndpointSides defaultSides;

  private PortPlan(
      Ordering ordering,
      Map<String, EndpointSides> sides,
      Map<String, EndpointMerge> merges,
      Set<String> fixedSourceEndpoints,
      Set<String> fixedTargetEndpoints,
      EndpointSides defaultSides) {
    this.ordering = ordering;
    this.sides = sides;
    this.merges = merges;
    this.fixedSourceEndpoints = fixedSourceEndpoints;
    this.fixedTargetEndpoints = fixedTargetEndpoints;
    this.defaultSides = defaultSides;
  }

  Ordering ordering() {
    return ordering;
  }

  PortSide sourceSide(String edgeId) {
    return sides.getOrDefault(edgeId, defaultSides).sourceSide();
  }

  PortSide targetSide(String edgeId) {
    return sides.getOrDefault(edgeId, defaultSides).targetSide();
  }

  boolean fixesSourceSide(String edgeId) {
    return fixedSourceEndpoints.contains(edgeId);
  }

  boolean fixesTargetSide(String edgeId) {
    return fixedTargetEndpoints.contains(edgeId);
  }

  boolean mergesSourceEndpoint(String edgeId) {
    return merges.getOrDefault(edgeId, NO_ENDPOINT_MERGE).sourceEndpoint();
  }

  boolean mergesTargetEndpoint(String edgeId) {
    return merges.getOrDefault(edgeId, NO_ENDPOINT_MERGE).targetEndpoint();
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

  static PortPlan flat(
      Ordering ordering,
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      LayoutPreferences preferences,
      Direction direction) {
    if (ordering == Ordering.GROUPED) {
      throw new IllegalArgumentException("the flat port-plan family cannot serve grouped layout");
    }
    Map<String, EndpointMerge> merges = flatEndpointMerges(edges, nodes, preferences, direction);
    BinaryCorridors corridors = binaryCorridors(edges, nodes);
    Set<String> fixedNodes = compactControlNodes(edges, corridors);
    Map<String, EndpointSides> sides =
        flatEndpointSides(edges, nodes, merges, corridors, direction);
    return new PortPlan(
        ordering,
        sides,
        merges,
        endpointEdges(edges, nodes, fixedNodes, true),
        endpointEdges(edges, nodes, fixedNodes, false),
        defaultEndpointSides(direction));
  }

  static PortPlan sequence(
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      LayoutIntentNormalizer sequenceConstraints) {
    Direction direction = Direction.RIGHT;
    EndpointSides defaults = defaultEndpointSides(direction);
    Map<String, EndpointSides> sides = new HashMap<>();
    Set<String> sourceEndpoints = new HashSet<>();
    Set<String> targetEndpoints = new HashSet<>();
    for (LayoutEdge edge : edges) {
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        continue;
      }
      sides.put(
          edge.id(),
          new EndpointSides(
              sequenceConstraints.sourcePortSide(edge, defaults.sourceSide()),
              sequenceConstraints.targetPortSide(edge, defaults.targetSide())));
      sourceEndpoints.add(edge.id());
      targetEndpoints.add(edge.id());
    }
    return new PortPlan(
        Ordering.UNCONSTRAINED,
        sides,
        emptyEndpointMerges(edges),
        sourceEndpoints,
        targetEndpoints,
        defaults);
  }

  static PortPlan grouped(
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      Map<String, String> ownerByNode,
      Direction direction,
      LayoutPreferences preferences) {
    return new PortPlan(
        Ordering.GROUPED,
        Map.of(),
        groupedEndpointMerges(edges, nodes, ownerByNode, preferences),
        Set.of(),
        Set.of(),
        defaultEndpointSides(direction));
  }

  private static Map<String, EndpointMerge> flatEndpointMerges(
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      LayoutPreferences preferences,
      Direction direction) {
    if (!ElkLayeredOptions.endpointMergingEnabled(preferences)) {
      return emptyEndpointMerges(edges);
    }
    EndpointSides defaults = defaultEndpointSides(direction);
    Map<EndpointKey, Integer> counts = new HashMap<>();
    for (LayoutEdge edge : edges) {
      String type = relationshipType(edge);
      if (!validNonLoop(edge, nodes) || type == null) {
        continue;
      }
      counts.merge(
          new EndpointKey(edge.source(), defaults.sourceSide(), true, type), 1, Integer::sum);
      counts.merge(
          new EndpointKey(edge.target(), defaults.targetSide(), false, type), 1, Integer::sum);
    }
    Map<String, EndpointMerge> merges = new HashMap<>();
    for (LayoutEdge edge : edges) {
      String type = relationshipType(edge);
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        continue;
      }
      boolean mergeable = type != null && validNonLoop(edge, nodes);
      merges.put(
          edge.id(),
          new EndpointMerge(
              mergeable
                  && counts.getOrDefault(
                          new EndpointKey(edge.source(), defaults.sourceSide(), true, type), 0)
                      >= MERGEABLE_ENDPOINT_EDGE_COUNT,
              mergeable
                  && counts.getOrDefault(
                          new EndpointKey(edge.target(), defaults.targetSide(), false, type), 0)
                      >= MERGEABLE_ENDPOINT_EDGE_COUNT));
    }
    return merges;
  }

  private static Map<String, EndpointMerge> groupedEndpointMerges(
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      Map<String, String> ownerByNode,
      LayoutPreferences preferences) {
    if (!ElkLayeredOptions.endpointMergingEnabled(preferences)) {
      return emptyEndpointMerges(edges);
    }
    Map<EndpointKey, Integer> counts = new HashMap<>();
    for (LayoutEdge edge : edges) {
      String type = relationshipType(edge);
      if (!validNonLoop(edge, nodes) || type == null || sameOwnerInternalEdge(edge, ownerByNode)) {
        continue;
      }
      counts.merge(new EndpointKey(edge.source(), null, true, type), 1, Integer::sum);
      counts.merge(new EndpointKey(edge.target(), null, false, type), 1, Integer::sum);
    }
    Set<String> sourceOnlyGroups = sourceOnlyGroups(edges, ownerByNode);
    Map<String, EndpointMerge> merges = new HashMap<>();
    for (LayoutEdge edge : edges) {
      String type = relationshipType(edge);
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        continue;
      }
      boolean mergeable =
          type != null
              && !edge.source().equals(edge.target())
              && !sameOwnerInternalEdge(edge, ownerByNode);
      boolean sourceOnlyTarget = sourceOnlyGroups.contains(ownerByNode.get(edge.source()));
      merges.put(
          edge.id(),
          new EndpointMerge(
              mergeable
                  && counts.getOrDefault(new EndpointKey(edge.source(), null, true, type), 0)
                      >= MERGEABLE_ENDPOINT_EDGE_COUNT,
              mergeable
                  && counts.getOrDefault(new EndpointKey(edge.target(), null, false, type), 0)
                      >= (sourceOnlyTarget ? 2 : MERGEABLE_ENDPOINT_EDGE_COUNT)));
    }
    return merges;
  }

  private static boolean validNonLoop(LayoutEdge edge, Map<String, LayoutNode> nodes) {
    return nodes.containsKey(edge.source())
        && nodes.containsKey(edge.target())
        && !edge.source().equals(edge.target());
  }

  private static Set<String> sourceOnlyGroups(
      List<LayoutEdge> edges, Map<String, String> ownerByNode) {
    Set<String> groups = new HashSet<>(ownerByNode.values());
    Set<String> nonSourceOnly = new HashSet<>();
    Set<String> withOutgoing = new HashSet<>();
    for (LayoutEdge edge : edges) {
      String sourceOwner = ownerByNode.get(edge.source());
      String targetOwner = ownerByNode.get(edge.target());
      if (sourceOwner != null && sourceOwner.equals(targetOwner)) {
        nonSourceOnly.add(sourceOwner);
      } else {
        if (sourceOwner != null) {
          withOutgoing.add(sourceOwner);
        }
        if (targetOwner != null) {
          nonSourceOnly.add(targetOwner);
        }
      }
    }
    groups.retainAll(withOutgoing);
    groups.removeAll(nonSourceOnly);
    return groups;
  }

  private static boolean sameOwnerInternalEdge(LayoutEdge edge, Map<String, String> ownerByNode) {
    String sourceOwner = ownerByNode.get(edge.source());
    return sourceOwner != null && sourceOwner.equals(ownerByNode.get(edge.target()));
  }

  private static Map<String, EndpointSides> flatEndpointSides(
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      Map<String, EndpointMerge> merges,
      BinaryCorridors corridors,
      Direction direction) {
    Map<String, EndpointSides> sides = new HashMap<>();
    EndpointSides defaults = defaultEndpointSides(direction);
    for (LayoutEdge edge : edges) {
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        continue;
      }
      EndpointMerge merge = merges.getOrDefault(edge.id(), NO_ENDPOINT_MERGE);
      PortSide source = defaults.sourceSide();
      PortSide target = defaults.targetSide();
      Integer sourceBranch = corridors.sourceBranches().get(edge.id());
      if (!merge.sourceEndpoint() && sourceBranch != null) {
        source = connectorBranchSide(direction, true, sourceBranch);
      }
      Integer targetBranch = corridors.targetBranches().get(edge.id());
      if (!merge.targetEndpoint() && targetBranch != null) {
        target = connectorBranchSide(direction, false, targetBranch);
      }
      sides.put(edge.id(), new EndpointSides(source, target));
    }
    return sides;
  }

  private static BinaryCorridors binaryCorridors(
      List<LayoutEdge> edges, Map<String, LayoutNode> nodes) {
    Map<String, Integer> outgoingCounts = new HashMap<>();
    Map<String, Integer> incomingCounts = new HashMap<>();
    Map<String, List<LayoutEdge>> outgoingByNode = new LinkedHashMap<>();
    for (LayoutEdge edge : edges) {
      if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
        continue;
      }
      outgoingCounts.merge(edge.source(), 1, Integer::sum);
      incomingCounts.merge(edge.target(), 1, Integer::sum);
      outgoingByNode.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
    }

    Map<String, Integer> sourceBranches = new HashMap<>();
    Map<String, Integer> targetBranches = new HashMap<>();
    for (Map.Entry<String, List<LayoutEdge>> entry : outgoingByNode.entrySet()) {
      List<LayoutEdge> branches = entry.getValue();
      if (branches.size() != 2 || !isConnectorSized(nodes.get(entry.getKey()))) {
        continue;
      }
      LinearBranch first =
          traceLinearBranch(branches.get(0), outgoingByNode, nodes, outgoingCounts, incomingCounts);
      LinearBranch second =
          traceLinearBranch(branches.get(1), outgoingByNode, nodes, outgoingCounts, incomingCounts);
      if (first == null
          || second == null
          || !first.convergenceNodeId().equals(second.convergenceNodeId())
          || first.incomingEdgeId().equals(second.incomingEdgeId())) {
        continue;
      }
      sourceBranches.put(branches.get(0).id(), 0);
      sourceBranches.put(branches.get(1).id(), 1);
      targetBranches.put(first.incomingEdgeId(), 0);
      targetBranches.put(second.incomingEdgeId(), 1);
    }
    return new BinaryCorridors(sourceBranches, targetBranches);
  }

  private static LinearBranch traceLinearBranch(
      LayoutEdge first,
      Map<String, List<LayoutEdge>> outgoingByNode,
      Map<String, LayoutNode> nodes,
      Map<String, Integer> outgoingCounts,
      Map<String, Integer> incomingCounts) {
    LayoutEdge edge = first;
    Set<String> visited = new HashSet<>();
    while (visited.add(edge.id())) {
      String targetId = edge.target();
      LayoutNode target = nodes.get(targetId);
      if (target == null) {
        return null;
      }
      if (incomingCounts.getOrDefault(targetId, 0) == 2 && isConnectorSized(target)) {
        return new LinearBranch(targetId, edge.id());
      }
      if (outgoingCounts.getOrDefault(targetId, 0) != 1) {
        return null;
      }
      List<LayoutEdge> outgoing = outgoingByNode.getOrDefault(targetId, List.of());
      if (outgoing.size() != 1) {
        return null;
      }
      edge = outgoing.getFirst();
    }
    return null;
  }

  private static Set<String> compactControlNodes(
      List<LayoutEdge> edges, BinaryCorridors corridors) {
    Set<String> nodes = new HashSet<>();
    for (LayoutEdge edge : edges) {
      if (corridors.sourceBranches().containsKey(edge.id())) {
        nodes.add(edge.source());
      }
      if (corridors.targetBranches().containsKey(edge.id())) {
        nodes.add(edge.target());
      }
    }
    return nodes;
  }

  private static Set<String> endpointEdges(
      List<LayoutEdge> edges,
      Map<String, LayoutNode> nodes,
      Set<String> fixedNodes,
      boolean source) {
    Set<String> fixed = new HashSet<>();
    for (LayoutEdge edge : edges) {
      String node = source ? edge.source() : edge.target();
      if (nodes.containsKey(node) && fixedNodes.contains(node)) {
        fixed.add(edge.id());
      }
    }
    return fixed;
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

  static String relationshipType(LayoutEdge edge) {
    String relationshipType = edge.relationshipType();
    return relationshipType == null || relationshipType.isBlank() ? null : relationshipType;
  }

  static boolean isConnectorSized(LayoutNode node) {
    if (node == null) {
      return false;
    }
    double width = node.widthHint() == null ? DEFAULT_WIDTH : node.widthHint();
    double height = node.heightHint() == null ? DEFAULT_HEIGHT : node.heightHint();
    return width <= CONNECTOR_SOURCE_MAX_WIDTH && height <= CONNECTOR_SOURCE_MAX_HEIGHT;
  }

  private record EndpointSides(PortSide sourceSide, PortSide targetSide) {}

  private record BinaryCorridors(
      Map<String, Integer> sourceBranches, Map<String, Integer> targetBranches) {}

  private record LinearBranch(String convergenceNodeId, String incomingEdgeId) {}

  private record EndpointMerge(boolean sourceEndpoint, boolean targetEndpoint) {}

  private record EndpointKey(
      String nodeId, PortSide side, boolean sourceEndpoint, String relationshipType) {}
}

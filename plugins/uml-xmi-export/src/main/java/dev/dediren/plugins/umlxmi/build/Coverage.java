package dev.dediren.plugins.umlxmi.build;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Machine-detectable summary of which source elements and relationships an export scoped to a
 * single laid-out view actually represents. XMI is model interchange, but a Dediren export covers
 * only the view whose layout it was handed; everything outside that scope is dropped. {@link
 * #compute} measures represented vs omitted content against the {@link ExportScope} selection so
 * the export envelope can declare the omissions (issue #32) instead of shipping silently lossy
 * interchange.
 */
public record Coverage(
    int representedNodes,
    int omittedNodes,
    Map<String, Integer> omittedNodeTypes,
    int representedRelationships,
    int omittedRelationships,
    Map<String, Integer> omittedRelationshipTypes) {

  public Coverage {
    omittedNodeTypes = Map.copyOf(omittedNodeTypes);
    omittedRelationshipTypes = Map.copyOf(omittedRelationshipTypes);
  }

  public boolean hasOmissions() {
    return omittedNodes > 0 || omittedRelationships > 0;
  }

  public static Coverage compute(
      List<SourceNode> nodes, List<SourceRelationship> relationships, ExportScope scope) {
    int representedNodes = 0;
    var omittedNodeTypes = new TreeMap<String, Integer>();
    for (SourceNode node : nodes) {
      if (scope.nodeIds().contains(node.id())) {
        representedNodes++;
      } else {
        omittedNodeTypes.merge(node.type(), 1, Integer::sum);
      }
    }
    int representedRelationships = 0;
    var omittedRelationshipTypes = new TreeMap<String, Integer>();
    for (SourceRelationship relationship : relationships) {
      if (scope.relationshipIds().contains(relationship.id())) {
        representedRelationships++;
      } else {
        omittedRelationshipTypes.merge(relationship.type(), 1, Integer::sum);
      }
    }
    return new Coverage(
        representedNodes,
        count(omittedNodeTypes),
        omittedNodeTypes,
        representedRelationships,
        count(omittedRelationshipTypes),
        omittedRelationshipTypes);
  }

  /**
   * Renders an omission type histogram as a deterministic {@code Type=count, Type=count} string,
   * sorted by type so the diagnostic message is stable regardless of map implementation.
   */
  public static String describe(Map<String, Integer> typeCounts) {
    return typeCounts.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining(", "));
  }

  private static int count(Map<String, Integer> typeCounts) {
    return typeCounts.values().stream().mapToInt(Integer::intValue).sum();
  }
}

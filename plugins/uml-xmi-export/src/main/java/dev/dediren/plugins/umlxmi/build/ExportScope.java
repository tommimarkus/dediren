package dev.dediren.plugins.umlxmi.build;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.genericGraphPluginData;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.semanticGroupSourceId;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.umlString;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.umlTextArray;

import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record ExportScope(Set<String> nodeIds, Set<String> relationshipIds) {
  public static ExportScope fromRequest(ExportRequest request) {
    return fromRequest(request, genericGraphPluginData(request));
  }

  public static ExportScope fromRequest(ExportRequest request, GenericGraphPluginData pluginData) {
    var sourceNodesById =
        request.source().nodes().stream().collect(Collectors.toMap(SourceNode::id, node -> node));
    var nodeIds =
        request.layoutResult().nodes().stream()
            .map(node -> node.sourceId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    var relationshipIds =
        request.layoutResult().edges().stream()
            .map(edge -> edge.sourceId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    String viewId = request.layoutResult().viewId();
    Optional<GenericGraphView> sourceView =
        viewId == null
            ? Optional.empty()
            : pluginData.views().stream().filter(view -> viewId.equals(view.id())).findFirst();
    sourceView.ifPresent(
        view ->
            addSelectedSourceOnlySequenceFragmentScope(
                nodeIds, relationshipIds, view, sourceNodesById, request.source().relationships()));
    for (LaidOutGroup group : request.layoutResult().groups()) {
      String sourceId = semanticGroupSourceId(group);
      if (sourceId != null) {
        nodeIds.add(sourceId);
      }
    }
    var activityIds =
        nodeIds.stream()
            .map(sourceNodesById::get)
            .filter(node -> node != null)
            .map(node -> umlString(node, "activity"))
            .filter(value -> value != null)
            .toList();
    nodeIds.addAll(activityIds);
    for (SourceRelationship relationship : request.source().relationships()) {
      if (!relationshipIds.contains(relationship.id()) || !relationship.type().equals("Message")) {
        continue;
      }
      addMessageLifelineEndpoint(nodeIds, sourceNodesById, relationship.source());
      addMessageLifelineEndpoint(nodeIds, sourceNodesById, relationship.target());
      String interactionId = umlString(relationship, "interaction");
      if (interactionId != null) {
        nodeIds.add(interactionId);
      }
    }
    var interactionIds =
        nodeIds.stream()
            .map(sourceNodesById::get)
            .filter(node -> node != null)
            .map(node -> umlString(node, "interaction"))
            .filter(value -> value != null)
            .toList();
    nodeIds.addAll(interactionIds);
    return new ExportScope(nodeIds, relationshipIds);
  }

  private static void addSelectedSourceOnlySequenceFragmentScope(
      Set<String> nodeIds,
      Set<String> relationshipIds,
      GenericGraphView view,
      Map<String, SourceNode> sourceNodesById,
      List<SourceRelationship> sourceRelationships) {
    if (view.kind() != GenericGraphViewKind.UML_SEQUENCE) {
      return;
    }
    Set<String> viewRelationshipIds = new HashSet<>(view.relationships());
    var sourceRelationshipsById =
        sourceRelationships.stream()
            .collect(Collectors.toMap(SourceRelationship::id, relationship -> relationship));
    for (String nodeId : view.nodes()) {
      SourceNode node = sourceNodesById.get(nodeId);
      if (node == null || !isSourceOnlySequenceFragmentNode(node)) {
        continue;
      }
      nodeIds.add(node.id());
      if (node.type().equals("InteractionOperand")) {
        addSelectedOperandMessageFragments(
            relationshipIds, viewRelationshipIds, sourceRelationshipsById, node);
      }
    }
  }

  private static boolean isSourceOnlySequenceFragmentNode(SourceNode node) {
    return node.type().equals("CombinedFragment") || node.type().equals("InteractionOperand");
  }

  private static void addSelectedOperandMessageFragments(
      Set<String> relationshipIds,
      Set<String> viewRelationshipIds,
      Map<String, SourceRelationship> sourceRelationshipsById,
      SourceNode operand) {
    for (String fragmentId : umlTextArray(operand, "fragments")) {
      SourceRelationship relationship = sourceRelationshipsById.get(fragmentId);
      if (relationship != null
          && relationship.type().equals("Message")
          && viewRelationshipIds.contains(fragmentId)) {
        relationshipIds.add(fragmentId);
      }
    }
  }

  private static void addMessageLifelineEndpoint(
      Set<String> nodeIds, Map<String, SourceNode> sourceNodesById, String endpointId) {
    SourceNode endpoint = sourceNodesById.get(endpointId);
    if (endpoint != null && endpoint.type().equals("Lifeline")) {
      nodeIds.add(endpoint.id());
    }
  }
}

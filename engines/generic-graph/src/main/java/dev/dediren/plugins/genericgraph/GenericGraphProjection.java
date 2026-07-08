package dev.dediren.plugins.genericgraph;

import dev.dediren.archimate.Archimate;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderMetadataSelector;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewGroup;
import dev.dediren.contracts.source.GenericGraphViewGroupRole;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

final class GenericGraphProjection {
  private GenericGraphProjection() {}

  static RenderMetadata projectRenderMetadata(
      SourceDocument source, GenericGraphView selectedView, String semanticProfile)
      throws IOException {
    var nodes = new LinkedHashMap<String, RenderMetadataSelector>();
    for (String id : selectedView.nodes()) {
      SourceNode sourceNode =
          source.nodes().stream()
              .filter(node -> node.id().equals(id))
              .findFirst()
              .orElseThrow(() -> new IOException("view references missing node " + id));
      nodes.put(
          sourceNode.id(),
          new RenderMetadataSelector(
              sourceNode.type(),
              sourceNode.id(),
              semanticProfile.equals("uml") ? sourceNode.properties().get("uml") : null));
    }

    var edges = new LinkedHashMap<String, RenderMetadataSelector>();
    for (String id : selectedView.relationships()) {
      SourceRelationship relationship =
          source.relationships().stream()
              .filter(candidate -> candidate.id().equals(id))
              .findFirst()
              .orElseThrow(() -> new IOException("view references missing relationship " + id));
      edges.put(
          relationship.id(),
          new RenderMetadataSelector(
              relationship.type(),
              relationship.id(),
              semanticProfile.equals("uml") ? relationship.properties().get("uml") : null));
    }

    var groups = new LinkedHashMap<String, RenderMetadataSelector>();
    for (GenericGraphViewGroup group : selectedView.groups()) {
      if (group.role() != GenericGraphViewGroupRole.SEMANTIC_BOUNDARY
          || group.semanticSourceId() == null) {
        continue;
      }
      SourceNode sourceNode =
          source.nodes().stream()
              .filter(node -> node.id().equals(group.semanticSourceId()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IOException(
                          "group "
                              + group.id()
                              + " references missing semantic source "
                              + group.semanticSourceId()));
      groups.put(group.id(), new RenderMetadataSelector(sourceNode.type(), sourceNode.id(), null));
    }

    return new RenderMetadata(
        ContractVersions.RENDER_METADATA_SCHEMA_VERSION, semanticProfile, nodes, edges, groups);
  }

  static LayoutRequest projectLayoutRequest(
      SourceDocument source, GenericGraphView selectedView, String semanticProfile)
      throws IOException {
    var nodes = new ArrayList<LayoutNode>();
    for (String id : selectedView.nodes()) {
      SourceNode sourceNode =
          source.nodes().stream()
              .filter(node -> node.id().equals(id))
              .findFirst()
              .orElseThrow(() -> new IOException("view references missing node " + id));
      if (isSourceOnlySequenceFragment(semanticProfile, selectedView, sourceNode)) {
        continue;
      }
      nodes.add(
          new LayoutNode(
              sourceNode.id(),
              sourceNode.label(),
              sourceNode.id(),
              GenericGraphLayoutSizing.widthHint(semanticProfile, sourceNode),
              GenericGraphLayoutSizing.heightHint(semanticProfile, sourceNode),
              layoutRole(semanticProfile, sourceNode.type()),
              sourceNode.partition(),
              sourceNode.layerConstraint()));
    }

    var edges = new ArrayList<LayoutEdge>();
    for (String id : selectedView.relationships()) {
      SourceRelationship relationship =
          source.relationships().stream()
              .filter(candidate -> candidate.id().equals(id))
              .findFirst()
              .orElseThrow(() -> new IOException("view references missing relationship " + id));
      edges.add(
          new LayoutEdge(
              relationship.id(),
              relationship.source(),
              relationship.target(),
              relationship.label(),
              relationship.id(),
              relationship.type(),
              relationship.priority()));
    }

    var selectedNodeIds = new LinkedHashSet<>(selectedView.nodes());
    var selectedGroupIds =
        selectedView.groups().stream()
            .map(GenericGraphViewGroup::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var sourceNodeIds =
        source.nodes().stream().map(SourceNode::id).collect(java.util.stream.Collectors.toSet());
    var emittedLayoutNodeIds =
        nodes.stream()
            .map(LayoutNode::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    var groups = new ArrayList<LayoutGroup>();
    for (GenericGraphViewGroup group : selectedView.groups()) {
      for (String member : group.members()) {
        if (!selectedNodeIds.contains(member) && !selectedGroupIds.contains(member)) {
          throw new IOException(
              "group " + group.id() + " references node or group outside view: " + member);
        }
      }
      GroupProvenance provenance;
      if (group.role() == GenericGraphViewGroupRole.LAYOUT_ONLY) {
        provenance = GroupProvenance.visualOnlyGroup();
      } else {
        String sourceId = group.semanticSourceId() == null ? group.id() : group.semanticSourceId();
        if (group.semanticSourceId() != null && !sourceNodeIds.contains(sourceId)) {
          throw new IOException(
              "group " + group.id() + " semantic_source_id references missing node: " + sourceId);
        }
        provenance = GroupProvenance.semanticBacked(sourceId);
      }
      var members =
          group.members().stream()
              .filter(
                  member ->
                      emittedLayoutNodeIds.contains(member) || selectedGroupIds.contains(member))
              .toList();
      if (members.isEmpty()) {
        continue;
      }
      groups.add(new LayoutGroup(group.id(), group.label(), members, provenance));
    }

    return new LayoutRequest(
        ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
        selectedView.id(),
        nodes,
        edges,
        groups,
        projectLayoutConstraints(source, selectedView, semanticProfile),
        selectedView.layoutPreferences());
  }

  private static List<LayoutConstraint> projectLayoutConstraints(
      SourceDocument source, GenericGraphView selectedView, String semanticProfile) {
    if (!semanticProfile.equals("uml")
        || selectedView.kind() != GenericGraphViewKind.UML_SEQUENCE) {
      return List.of();
    }

    var selectedNodeIds = new LinkedHashSet<>(selectedView.nodes());
    var lifelineIds =
        source.nodes().stream()
            .filter(node -> selectedNodeIds.contains(node.id()))
            .filter(node -> node.type().equals("Lifeline"))
            .map(SourceNode::id)
            .toList();

    var sourceRelationshipOrder = new HashMap<String, Integer>();
    for (int index = 0; index < source.relationships().size(); index++) {
      sourceRelationshipOrder.put(source.relationships().get(index).id(), index);
    }
    var selectedRelationshipIds = new LinkedHashSet<>(selectedView.relationships());
    var messageIds =
        source.relationships().stream()
            .filter(relationship -> selectedRelationshipIds.contains(relationship.id()))
            .filter(relationship -> relationship.type().equals("Message"))
            .sorted(
                Comparator.comparing(GenericGraphProjection::umlMessageSequence)
                    .thenComparingInt(
                        relationship -> sourceRelationshipOrder.get(relationship.id())))
            .map(SourceRelationship::id)
            .toList();

    var messageIdSet = new HashSet<>(messageIds);
    var nodesById =
        source.nodes().stream()
            .collect(java.util.stream.Collectors.toMap(SourceNode::id, node -> node, (a, b) -> a));
    var fragmentOpenIds = new ArrayList<String>();
    var operandOpenIds = new ArrayList<String>();
    for (SourceNode node : source.nodes()) {
      if (!selectedNodeIds.contains(node.id()) || !"CombinedFragment".equals(node.type())) {
        continue;
      }
      JsonNode uml = node.properties().get("uml");
      if (uml == null) {
        continue;
      }
      var operandIds = new ArrayList<String>();
      for (JsonNode operand : uml.path("operands")) {
        operandIds.add(operand.asText());
      }
      operandIds.sort(Comparator.comparingInt(id -> operandOrder(nodesById.get(id))));
      for (int index = 0; index < operandIds.size(); index++) {
        String firstMessage =
            firstMessageOfOperand(
                nodesById.get(operandIds.get(index)), nodesById, messageIdSet, new HashSet<>());
        if (firstMessage == null) {
          continue;
        }
        if (index == 0) {
          fragmentOpenIds.add(firstMessage);
        } else {
          operandOpenIds.add(firstMessage);
        }
      }
    }

    var constraints = new ArrayList<LayoutConstraint>();
    constraints.add(
        new LayoutConstraint(
            selectedView.id() + ".uml.sequence.lifeline-order",
            "uml.sequence.lifeline-order",
            lifelineIds));
    constraints.add(
        new LayoutConstraint(
            selectedView.id() + ".uml.sequence.message-order",
            "uml.sequence.message-order",
            messageIds));
    // Dedupe: a nested fragment whose first member is another fragment resolves to the same first
    // message via both the outer and inner iterations, so a message id can be collected twice.
    if (!fragmentOpenIds.isEmpty()) {
      constraints.add(
          new LayoutConstraint(
              selectedView.id() + ".uml.sequence.fragment-open",
              "uml.sequence.fragment-open",
              new ArrayList<>(new LinkedHashSet<>(fragmentOpenIds))));
    }
    if (!operandOpenIds.isEmpty()) {
      constraints.add(
          new LayoutConstraint(
              selectedView.id() + ".uml.sequence.operand-open",
              "uml.sequence.operand-open",
              new ArrayList<>(new LinkedHashSet<>(operandOpenIds))));
    }
    return constraints;
  }

  private static int operandOrder(SourceNode operand) {
    if (operand == null) {
      return Integer.MAX_VALUE;
    }
    JsonNode uml = operand.properties().get("uml");
    JsonNode order = uml == null ? null : uml.get("order");
    return order != null && order.isNumber() ? order.asInt() : Integer.MAX_VALUE;
  }

  private static String firstMessageOfOperand(
      SourceNode operand,
      Map<String, SourceNode> nodesById,
      Set<String> messageIds,
      Set<String> visiting) {
    if (operand == null) {
      return null;
    }
    JsonNode uml = operand.properties().get("uml");
    if (uml == null) {
      return null;
    }
    for (JsonNode member : uml.path("fragments")) {
      String memberId = member.asText();
      if (messageIds.contains(memberId)) {
        return memberId;
      }
      SourceNode nested = nodesById.get(memberId);
      if (nested != null && "CombinedFragment".equals(nested.type()) && visiting.add(memberId)) {
        JsonNode nestedUml = nested.properties().get("uml");
        if (nestedUml == null) {
          continue;
        }
        var nestedOperands = new ArrayList<String>();
        for (JsonNode operandRef : nestedUml.path("operands")) {
          nestedOperands.add(operandRef.asText());
        }
        nestedOperands.sort(Comparator.comparingInt(id -> operandOrder(nodesById.get(id))));
        for (String nestedOperandId : nestedOperands) {
          String found =
              firstMessageOfOperand(
                  nodesById.get(nestedOperandId), nodesById, messageIds, visiting);
          if (found != null) {
            return found;
          }
        }
      }
    }
    return null;
  }

  // Carry roles into the layout-request so backend-neutral layout-quality checks can apply
  // role-aware geometry rules (lifeline message anchors, junction route proximity).
  // Other source types stay role-less.
  private static String layoutRole(String semanticProfile, String sourceType) {
    if ("Lifeline".equals(sourceType)) {
      return "lifeline";
    }
    if ("Interaction".equals(sourceType)) {
      return "interaction";
    }
    if (semanticProfile.equals("archimate") && Archimate.isRelationshipConnectorType(sourceType)) {
      return "junction";
    }
    return null;
  }

  private static boolean isSourceOnlySequenceFragment(
      String semanticProfile, GenericGraphView selectedView, SourceNode node) {
    return semanticProfile.equals("uml")
        && selectedView.kind() == GenericGraphViewKind.UML_SEQUENCE
        && (node.type().equals("CombinedFragment") || node.type().equals("InteractionOperand"));
  }

  private static BigInteger umlMessageSequence(SourceRelationship relationship) {
    JsonNode umlProperties = relationship.properties().get("uml");
    return umlProperties.get("sequence").bigIntegerValue();
  }

  static String sourceSemanticProfile(GenericGraphPluginData pluginData) {
    return GenericGraphSemanticProfiles.sourceSemanticProfile(pluginData);
  }
}

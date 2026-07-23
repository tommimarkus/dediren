package dev.dediren.plugins.umlxmi.write.activity;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.*;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.plugins.umlxmi.build.IdentifierMap;
import dev.dediren.plugins.umlxmi.build.TypeResolver;
import java.util.*;

public final class ActivityWriter {

  private ActivityWriter() {}

  public static void writeActivity(
      StringBuilder xml,
      IdentifierMap ids,
      TypeResolver types,
      SourceNode activity,
      String activityId,
      List<SourceNode> sourceNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    xml.append("<packagedElement xmi:type=\"uml:Activity\" xmi:id=\"")
        .append(attr(activityId))
        .append("\" name=\"")
        .append(attr(activity.label()))
        .append("\">");
    // Seed declared partitions (order-preserving) so an empty swimlane still emits, then collect
    // each node's membership by partition name as the nodes are written.
    var partitionMembers = new LinkedHashMap<String, List<String>>();
    for (String partitionName : umlTextArray(activity, "partitions")) {
      partitionMembers.putIfAbsent(partitionName, new ArrayList<>());
    }
    for (SourceNode node : sourceNodes) {
      if (nodeIds.containsKey(node.id()) && activity.id().equals(umlString(node, "activity"))) {
        writeActivityNode(xml, types, node, nodeIds.get(node.id()));
        String partition = umlString(node, "partition");
        if (partition != null) {
          partitionMembers
              .computeIfAbsent(partition, key -> new ArrayList<>())
              .add(nodeIds.get(node.id()));
        }
      }
    }
    for (SourceRelationship relationship : selectedRelationships) {
      String sourceId = nodeIds.get(relationship.source());
      String targetId = nodeIds.get(relationship.target());
      String relationshipId = relationshipIds.get(relationship.id());
      if (sourceId != null && targetId != null && relationshipId != null) {
        writeActivityEdge(xml, ids, relationship, relationshipId, sourceId, targetId);
      }
    }
    // ActivityPartitions are contained via the composite Activity::group feature (UML 2.5.1 §15.7:
    // group is composite; partition subsets it); each references its member ActivityNodes.
    for (Map.Entry<String, List<String>> partition : partitionMembers.entrySet()) {
      xml.append("<group xmi:type=\"uml:ActivityPartition\" xmi:id=\"")
          .append(attr(ids.xmiId(activity.id() + "-partition-" + partition.getKey())))
          .append("\" name=\"")
          .append(attr(partition.getKey()))
          .append("\"");
      if (!partition.getValue().isEmpty()) {
        xml.append(" node=\"").append(attr(String.join(" ", partition.getValue()))).append("\"");
      }
      xml.append("/>");
    }
    xml.append("</packagedElement>");
  }

  public static void writeActivityNode(
      StringBuilder xml, TypeResolver types, SourceNode node, String nodeId) {
    xml.append("<node xmi:type=\"")
        .append(activityNodeXmiType(node.type()))
        .append("\" xmi:id=\"")
        .append(attr(nodeId))
        .append("\"");
    if (!node.label().isEmpty()) {
      xml.append(" name=\"").append(attr(node.label())).append("\"");
    }
    // ObjectNode (CentralBufferNode) is a TypedElement; resolve its type name to an in-document
    // xmi:id (synthesizing a target when needed) rather than emitting a dangling type-name IDREF.
    String objectType = umlString(node, "type");
    if (objectType != null) {
      xml.append(" type=\"").append(attr(types.resolve(objectType))).append("\"");
    }
    xml.append("/>");
  }

  public static void writeActivityEdge(
      StringBuilder xml,
      IdentifierMap ids,
      SourceRelationship relationship,
      String relationshipId,
      String sourceId,
      String targetId) {
    xml.append("<edge xmi:type=\"")
        .append(activityEdgeXmiType(relationship.type()))
        .append("\" xmi:id=\"")
        .append(attr(relationshipId))
        .append("\"");
    if (!relationship.label().isEmpty()) {
      xml.append(" name=\"").append(attr(relationship.label())).append("\"");
    }
    xml.append(" source=\"").append(attr(sourceId)).append("\" target=\"").append(attr(targetId));
    // UML 2.5.1 §15.7.2: ActivityEdge::guard is an owned ValueSpecification.
    String guard = umlString(relationship, "guard");
    if (guard == null || guard.isBlank()) {
      xml.append("\"/>");
      return;
    }
    xml.append("\"><guard xmi:type=\"uml:OpaqueExpression\" xmi:id=\"")
        .append(attr(ids.xmiId(relationship.id() + "-guard")))
        .append("\"><body>")
        .append(text(guard))
        .append("</body></guard></edge>");
  }

  public static String activityNodeXmiType(String nodeType) {
    return switch (nodeType) {
      case "Action" -> "uml:OpaqueAction";
      case "InitialNode" -> "uml:InitialNode";
      case "ActivityFinalNode" -> "uml:ActivityFinalNode";
      case "DecisionNode" -> "uml:DecisionNode";
      case "MergeNode" -> "uml:MergeNode";
      case "ForkNode" -> "uml:ForkNode";
      case "JoinNode" -> "uml:JoinNode";
      case "ObjectNode" -> "uml:CentralBufferNode";
      default -> "uml:OpaqueAction";
    };
  }

  public static String activityEdgeXmiType(String relationshipType) {
    return relationshipType.equals("ObjectFlow") ? "uml:ObjectFlow" : "uml:ControlFlow";
  }
}

package dev.dediren.plugins.umlxmi.write.activity;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.*;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.*;

public final class ActivityWriter {

  private ActivityWriter() {}

  public static void writeActivity(
      StringBuilder xml,
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
    for (SourceNode node : sourceNodes) {
      if (nodeIds.containsKey(node.id()) && activity.id().equals(umlString(node, "activity"))) {
        writeActivityNode(xml, node, nodeIds.get(node.id()));
      }
    }
    for (SourceRelationship relationship : selectedRelationships) {
      String sourceId = nodeIds.get(relationship.source());
      String targetId = nodeIds.get(relationship.target());
      String relationshipId = relationshipIds.get(relationship.id());
      if (sourceId != null && targetId != null && relationshipId != null) {
        writeActivityEdge(xml, relationship, relationshipId, sourceId, targetId);
      }
    }
    xml.append("</packagedElement>");
  }

  public static void writeActivityNode(StringBuilder xml, SourceNode node, String nodeId) {
    xml.append("<node xmi:type=\"")
        .append(activityNodeXmiType(node.type()))
        .append("\" xmi:id=\"")
        .append(attr(nodeId))
        .append("\"");
    if (!node.label().isEmpty()) {
      xml.append(" name=\"").append(attr(node.label())).append("\"");
    }
    String objectType = umlString(node, "type");
    if (objectType != null) {
      xml.append(" type=\"").append(attr(objectType)).append("\"");
    }
    xml.append("/>");
  }

  public static void writeActivityEdge(
      StringBuilder xml,
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
    xml.append(" source=\"")
        .append(attr(sourceId))
        .append("\" target=\"")
        .append(attr(targetId))
        .append("\"/>");
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

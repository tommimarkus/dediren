package dev.dediren.plugins.umlxmi.write.statemachine;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.*;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.plugins.umlxmi.build.IdentifierMap;
import java.util.*;

public final class StateMachineWriter {

  private StateMachineWriter() {}

  public static void writeStateMachine(
      StringBuilder xml,
      IdentifierMap ids,
      SourceNode stateMachine,
      String stateMachineId,
      List<SourceNode> selectedNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    xml.append("<packagedElement xmi:type=\"uml:StateMachine\" xmi:id=\"")
        .append(attr(stateMachineId))
        .append("\" name=\"")
        .append(attr(stateMachine.label()))
        .append("\">");
    for (SourceNode region : selectedNodes) {
      if (region.type().equals("Region")
          && stateMachine.id().equals(umlString(region, "state_machine"))) {
        writeStateMachineRegion(
            xml, ids, region, selectedNodes, selectedRelationships, nodeIds, relationshipIds);
      }
    }
    xml.append("</packagedElement>");
  }

  public static void writeStateMachineRegion(
      StringBuilder xml,
      IdentifierMap ids,
      SourceNode region,
      List<SourceNode> selectedNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    xml.append("<region xmi:id=\"")
        .append(attr(nodeIds.get(region.id())))
        .append("\" name=\"")
        .append(attr(region.label()))
        .append("\">");
    for (SourceNode vertex : selectedNodes) {
      if (region.id().equals(umlString(vertex, "region"))) {
        writeStateMachineVertex(xml, vertex, nodeIds.get(vertex.id()));
      }
    }
    for (SourceRelationship transition : selectedRelationships) {
      if (transition.type().equals("Transition")
          && region.id().equals(umlString(transition, "region"))) {
        writeTransition(xml, ids, transition, relationshipIds.get(transition.id()), nodeIds);
      }
    }
    xml.append("</region>");
  }

  public static void writeStateMachineVertex(
      StringBuilder xml, SourceNode vertex, String vertexId) {
    switch (vertex.type()) {
      case "State" ->
          xml.append("<subvertex xmi:type=\"uml:State\" xmi:id=\"")
              .append(attr(vertexId))
              .append("\" name=\"")
              .append(attr(vertex.label()))
              .append("\"/>");
      case "FinalState" ->
          xml.append("<subvertex xmi:type=\"uml:FinalState\" xmi:id=\"")
              .append(attr(vertexId))
              .append("\" name=\"")
              .append(attr(vertex.label()))
              .append("\"/>");
      case "Pseudostate" ->
          xml.append("<subvertex xmi:type=\"uml:Pseudostate\" xmi:id=\"")
              .append(attr(vertexId))
              .append("\" name=\"")
              .append(attr(vertex.label()))
              .append("\" kind=\"")
              .append(attr(umlString(vertex, "kind")))
              .append("\"/>");
      default -> {}
    }
  }

  public static void writeTransition(
      StringBuilder xml,
      IdentifierMap ids,
      SourceRelationship transition,
      String transitionId,
      Map<String, String> nodeIds) {
    xml.append("<transition xmi:id=\"")
        .append(attr(transitionId))
        .append("\" name=\"")
        .append(attr(transition.label()))
        .append("\" source=\"")
        .append(attr(nodeIds.get(transition.source())))
        .append("\" target=\"")
        .append(attr(nodeIds.get(transition.target())))
        .append("\" kind=\"")
        .append(attr(Optional.ofNullable(umlString(transition, "kind")).orElse("external")))
        .append("\">");
    String guard = umlString(transition, "guard");
    if (guard != null && !guard.isBlank()) {
      String guardId = ids.xmiId(transition.id() + "-guard");
      String specificationId = ids.xmiId(transition.id() + "-guard-specification");
      xml.append("<guard xmi:type=\"uml:Constraint\" xmi:id=\"")
          .append(attr(guardId))
          .append("\" name=\"")
          .append(attr(guard))
          .append("\">")
          .append("<specification xmi:type=\"uml:OpaqueExpression\" xmi:id=\"")
          .append(attr(specificationId))
          .append("\">")
          .append("<body>")
          .append(text(guard))
          .append("</body>")
          .append("</specification></guard>");
    }
    xml.append("</transition>");
  }
}

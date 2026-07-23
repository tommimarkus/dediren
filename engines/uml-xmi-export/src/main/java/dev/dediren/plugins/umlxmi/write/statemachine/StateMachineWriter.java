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
    // UML 2.5.1 §14.5.11: Transition owns trigger/guard/effect. The source carries a trigger name
    // only, so emit a named uml:Trigger — the [1..1] Trigger::event needs a CallEvent/SignalEvent
    // bound to an Operation/Signal the diagram source does not supply, so the name is the faithful
    // carrier rather than a fabricated event.
    String trigger = umlString(transition, "trigger");
    if (trigger != null && !trigger.isBlank()) {
      xml.append("<trigger xmi:type=\"uml:Trigger\" xmi:id=\"")
          .append(attr(ids.xmiId(transition.id() + "-trigger")))
          .append("\" name=\"")
          .append(attr(trigger))
          .append("\"/>");
    }
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
    String effect = umlString(transition, "effect");
    if (effect != null && !effect.isBlank()) {
      xml.append("<effect xmi:type=\"uml:OpaqueBehavior\" xmi:id=\"")
          .append(attr(ids.xmiId(transition.id() + "-effect")))
          .append("\" name=\"")
          .append(attr(effect))
          .append("\"><body>")
          .append(text(effect))
          .append("</body></effect>");
    }
    xml.append("</transition>");
  }
}

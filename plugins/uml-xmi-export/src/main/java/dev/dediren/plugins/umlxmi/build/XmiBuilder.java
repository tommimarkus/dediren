package dev.dediren.plugins.umlxmi.build;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.UML_NS;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.XMI_NS;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.attr;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.writeEmptyPackagedElement;
import static dev.dediren.plugins.umlxmi.write.activity.ActivityWriter.writeActivity;
import static dev.dediren.plugins.umlxmi.write.classifier.ClassifierWriter.writeClassifier;
import static dev.dediren.plugins.umlxmi.write.classifier.ClassifierWriter.writeEnumeration;
import static dev.dediren.plugins.umlxmi.write.component.ComponentWriter.writeComponent;
import static dev.dediren.plugins.umlxmi.write.component.ComponentWriter.writeComponentRelationships;
import static dev.dediren.plugins.umlxmi.write.deployment.DeploymentWriter.writeDeploymentRelationships;
import static dev.dediren.plugins.umlxmi.write.interaction.InteractionWriter.writeInteraction;
import static dev.dediren.plugins.umlxmi.write.statemachine.StateMachineWriter.writeStateMachine;
import static dev.dediren.plugins.umlxmi.write.usecase.UseCaseWriter.writeUseCase;

import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.UmlXmiExportPolicy;
import dev.dediren.contracts.source.SourceNode;
import java.util.HashMap;
import java.util.stream.Collectors;

public final class XmiBuilder {

  private XmiBuilder() {}

  public static String buildXmi(ExportRequest request, UmlXmiExportPolicy policy) {
    var ids = new IdentifierMap(policy.modelIdentifier());
    ExportScope scope = ExportScope.fromRequest(request);
    var selectedNodes =
        request.source().nodes().stream()
            .filter(node -> scope.nodeIds().contains(node.id()))
            .toList();
    var selectedRelationships =
        request.source().relationships().stream()
            .filter(relationship -> scope.relationshipIds().contains(relationship.id()))
            .toList();
    var sourceNodesById =
        request.source().nodes().stream().collect(Collectors.toMap(SourceNode::id, node -> node));
    var nodeIds = new HashMap<String, String>();
    selectedNodes.forEach(node -> nodeIds.put(node.id(), ids.xmiId(node.id())));
    var relationshipIds = new HashMap<String, String>();
    selectedRelationships.forEach(
        relationship -> relationshipIds.put(relationship.id(), ids.xmiId(relationship.id())));

    StringBuilder xml = new StringBuilder();
    xml.append("<xmi:XMI xmlns:xmi=\"")
        .append(XMI_NS)
        .append("\" xmlns:uml=\"")
        .append(UML_NS)
        .append("\">");
    xml.append("<uml:Model xmi:id=\"")
        .append(attr(policy.modelIdentifier()))
        .append("\" name=\"")
        .append(attr(policy.modelName()))
        .append("\">");
    for (SourceNode node : selectedNodes) {
      String elementId = nodeIds.get(node.id());
      switch (node.type()) {
        case "Package" -> writeEmptyPackagedElement(xml, "uml:Package", node, elementId);
        case "Component" -> writeComponent(xml, node, elementId, selectedNodes, nodeIds);
        case "Class" -> writeClassifier(xml, ids, "uml:Class", node, elementId);
        case "Interface" -> writeClassifier(xml, ids, "uml:Interface", node, elementId);
        case "DataType" -> writeClassifier(xml, ids, "uml:DataType", node, elementId);
        case "Enumeration" -> writeEnumeration(xml, ids, node, elementId);
        case "Node" -> writeEmptyPackagedElement(xml, "uml:Node", node, elementId);
        case "Device" -> writeEmptyPackagedElement(xml, "uml:Device", node, elementId);
        case "ExecutionEnvironment" ->
            writeEmptyPackagedElement(xml, "uml:ExecutionEnvironment", node, elementId);
        case "Artifact" -> writeEmptyPackagedElement(xml, "uml:Artifact", node, elementId);
        case "DeploymentSpecification" ->
            writeEmptyPackagedElement(xml, "uml:DeploymentSpecification", node, elementId);
        case "Actor" -> writeEmptyPackagedElement(xml, "uml:Actor", node, elementId);
        case "UseCase" ->
            writeUseCase(
                xml,
                node,
                elementId,
                selectedNodes,
                selectedRelationships,
                nodeIds,
                relationshipIds);
        case "Activity" ->
            writeActivity(
                xml,
                node,
                elementId,
                request.source().nodes(),
                selectedRelationships,
                nodeIds,
                relationshipIds);
        case "Interaction" ->
            writeInteraction(
                xml,
                ids,
                node,
                elementId,
                request.source().nodes(),
                selectedRelationships,
                nodeIds,
                relationshipIds);
        case "StateMachine" ->
            writeStateMachine(
                xml,
                ids,
                node,
                elementId,
                selectedNodes,
                selectedRelationships,
                nodeIds,
                relationshipIds);
        default -> {}
      }
    }
    writeComponentRelationships(
        xml, selectedRelationships, sourceNodesById, nodeIds, relationshipIds);
    writeDeploymentRelationships(
        xml, selectedRelationships, sourceNodesById, nodeIds, relationshipIds);
    xml.append("</uml:Model></xmi:XMI>\n");
    return xml.toString();
  }
}

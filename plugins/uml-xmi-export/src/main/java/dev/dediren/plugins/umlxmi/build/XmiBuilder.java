package dev.dediren.plugins.umlxmi.build;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.UML_NS;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.XMI_NS;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.attr;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.umlString;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.writeEmptyPackagedElement;
import static dev.dediren.plugins.umlxmi.write.activity.ActivityWriter.writeActivity;
import static dev.dediren.plugins.umlxmi.write.classifier.ClassRelationshipWriter.writeClassRelationships;
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
import dev.dediren.contracts.source.SourceRelationship;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // Nest classifiers under the Package they declare via properties.uml.package, when that
    // Package is itself in scope; everything else stays a direct Model child.
    Set<String> selectedPackageIds =
        selectedNodes.stream()
            .filter(node -> node.type().equals("Package"))
            .map(SourceNode::id)
            .collect(Collectors.toSet());
    var membersByPackage = new LinkedHashMap<String, java.util.List<SourceNode>>();
    var nestedNodeIds = new java.util.HashSet<String>();
    for (SourceNode node : selectedNodes) {
      String packageId = umlString(node, "package");
      if (!node.type().equals("Package")
          && packageId != null
          && selectedPackageIds.contains(packageId)) {
        membersByPackage.computeIfAbsent(packageId, key -> new java.util.ArrayList<>()).add(node);
        nestedNodeIds.add(node.id());
      }
    }

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
      if (nestedNodeIds.contains(node.id())) {
        continue; // written inside its owning Package below
      }
      String elementId = nodeIds.get(node.id());
      if (node.type().equals("Package")) {
        writePackage(
            xml,
            ids,
            request,
            node,
            elementId,
            membersByPackage.getOrDefault(node.id(), List.of()),
            selectedNodes,
            selectedRelationships,
            nodeIds,
            relationshipIds);
      } else {
        writeNodeElement(
            xml,
            ids,
            request,
            node,
            elementId,
            selectedNodes,
            selectedRelationships,
            nodeIds,
            relationshipIds);
      }
    }
    writeClassRelationships(
        xml, ids, selectedRelationships, sourceNodesById, nodeIds, relationshipIds);
    writeComponentRelationships(
        xml, selectedRelationships, sourceNodesById, nodeIds, relationshipIds);
    writeDeploymentRelationships(
        xml, selectedRelationships, sourceNodesById, nodeIds, relationshipIds);
    xml.append("</uml:Model></xmi:XMI>\n");
    return xml.toString();
  }

  private static void writePackage(
      StringBuilder xml,
      IdentifierMap ids,
      ExportRequest request,
      SourceNode packageNode,
      String elementId,
      List<SourceNode> members,
      List<SourceNode> selectedNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    if (members.isEmpty()) {
      writeEmptyPackagedElement(xml, "uml:Package", packageNode, elementId);
      return;
    }
    xml.append("<packagedElement xmi:type=\"uml:Package\" xmi:id=\"")
        .append(attr(elementId))
        .append("\" name=\"")
        .append(attr(packageNode.label()))
        .append("\">");
    for (SourceNode member : members) {
      writeNodeElement(
          xml,
          ids,
          request,
          member,
          nodeIds.get(member.id()),
          selectedNodes,
          selectedRelationships,
          nodeIds,
          relationshipIds);
    }
    xml.append("</packagedElement>");
  }

  private static void writeNodeElement(
      StringBuilder xml,
      IdentifierMap ids,
      ExportRequest request,
      SourceNode node,
      String elementId,
      List<SourceNode> selectedNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
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
              xml, node, elementId, selectedNodes, selectedRelationships, nodeIds, relationshipIds);
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
}

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

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.UmlXmiExportPolicy;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.ModelExportRequest;
import dev.dediren.plugins.umlxmi.write.diagram.DiagramWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class XmiBuilder {

  private XmiBuilder() {}

  /** Node types whose emitted elements can be the target of an attribute type reference. */
  private static final Set<String> CLASSIFIER_TYPES =
      Set.of("Class", "Interface", "DataType", "Enumeration");

  public static String buildXmi(ExportRequest request, UmlXmiExportPolicy policy) {
    ExportScope scope = ExportScope.fromRequest(request);
    var selectedNodes =
        request.source().nodes().stream()
            .filter(node -> scope.nodeIds().contains(node.id()))
            .toList();
    var selectedRelationships =
        request.source().relationships().stream()
            .filter(relationship -> scope.relationshipIds().contains(relationship.id()))
            .toList();
    StringBuilder xml = new StringBuilder();
    openRoot(xml, false);
    writeModelContent(xml, request, policy, selectedNodes, selectedRelationships);
    xml.append("</xmi:XMI>\n");
    return xml.toString();
  }

  /**
   * The whole-model lane: one document carrying the full model once, then one OMG UMLDI diagram per
   * supplied laid-out view (mirrors the ArchiMate-OEF {@code buildModelOef}). The model section
   * includes every source element so a per-view diagram can reference any of them; each diagram
   * serializes only its own view's geometry. The single-view {@link #buildXmi} stays model-only and
   * byte-identical.
   */
  public static String buildModelXmi(ModelExportRequest request, UmlXmiExportPolicy policy) {
    ExportRequest representative =
        new ExportRequest(
            ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION,
            request.source(),
            request.views().getFirst().layout(),
            request.policy());
    // Scope the shared model section to the union of the exported views, not the whole source: the
    // per-family element writers (activity, sequence, …) assume view-scoped input, so mixing an
    // unrelated family's nodes into one model section collides xmi:ids. A class-only package thus
    // emits exactly the single-view class model, plus its diagram.
    Set<String> nodeScope = new java.util.HashSet<>();
    Set<String> relationshipScope = new java.util.HashSet<>();
    for (ModelExportRequest.ViewLayout view : request.views()) {
      ExportScope scope =
          ExportScope.fromRequest(
              new ExportRequest(
                  ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION,
                  request.source(),
                  view.layout(),
                  request.policy()));
      nodeScope.addAll(scope.nodeIds());
      relationshipScope.addAll(scope.relationshipIds());
    }
    var selectedNodes =
        request.source().nodes().stream().filter(node -> nodeScope.contains(node.id())).toList();
    var selectedRelationships =
        request.source().relationships().stream()
            .filter(relationship -> relationshipScope.contains(relationship.id()))
            .toList();

    StringBuilder xml = new StringBuilder();
    openRoot(xml, true);
    ModelContent model =
        writeModelContent(xml, representative, policy, selectedNodes, selectedRelationships);
    for (ModelExportRequest.ViewLayout view : request.views()) {
      DiagramWriter.writeUmlDiagram(
          xml,
          view.layout(),
          new DiagramWriter.DiagramIdentity("id-diagram-" + view.viewId(), view.viewId()),
          model.ids(),
          model.nodeIds(),
          model.relationshipIds());
    }
    xml.append("</xmi:XMI>\n");
    return xml.toString();
  }

  private static void openRoot(StringBuilder xml, boolean withDiagramInterchange) {
    xml.append("<xmi:XMI xmlns:xmi=\"").append(XMI_NS).append("\" xmlns:uml=\"").append(UML_NS);
    if (withDiagramInterchange) {
      xml.append("\" xmlns:umldi=\"")
          .append(DiagramWriter.UMLDI_NS)
          .append("\" xmlns:di=\"")
          .append(DiagramWriter.DI_NS)
          .append("\" xmlns:dc=\"")
          .append(DiagramWriter.DC_NS);
    }
    xml.append("\">");
  }

  /** The model-element id maps a UMLDI diagram needs to reference what the model section wrote. */
  public record ModelContent(
      IdentifierMap ids, Map<String, String> nodeIds, Map<String, String> relationshipIds) {}

  /**
   * Writes the {@code <uml:Model>…</uml:Model>} section for the given scope and returns the id maps
   * (and the shared {@link IdentifierMap}) so a caller can append UMLDI diagrams that reference the
   * emitted elements and mint further globally-unique {@code xmi:id}s. Does not open or close the
   * {@code xmi:XMI} root.
   */
  private static ModelContent writeModelContent(
      StringBuilder xml,
      ExportRequest request,
      UmlXmiExportPolicy policy,
      List<SourceNode> selectedNodes,
      List<SourceRelationship> selectedRelationships) {
    var ids = new IdentifierMap(policy.modelIdentifier());
    var sourceNodesById =
        request.source().nodes().stream().collect(Collectors.toMap(SourceNode::id, node -> node));
    var nodeIds = new HashMap<String, String>();
    selectedNodes.forEach(node -> nodeIds.put(node.id(), ids.xmiId(node.id())));
    var relationshipIds = new HashMap<String, String>();
    selectedRelationships.forEach(
        relationship -> relationshipIds.put(relationship.id(), ids.xmiId(relationship.id())));

    // Index emitted classifiers by name so attribute type references resolve to a real xmi:id
    // instead of a dangling type-name string; unresolved names are synthesized by TypeResolver.
    var classifierIdByName = new HashMap<String, String>();
    for (SourceNode node : selectedNodes) {
      if (CLASSIFIER_TYPES.contains(node.type())) {
        classifierIdByName.putIfAbsent(node.label(), nodeIds.get(node.id()));
      }
    }
    var types = new TypeResolver(ids, classifierIdByName);

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
            types,
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
            types,
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
    // Emit the primitive/data type targets referenced by attributes above, after every classifier
    // has been written so all referenced types are known. XML idrefs need not precede their target.
    types.writeSynthesizedTypes(xml);
    xml.append("</uml:Model>");
    return new ModelContent(ids, nodeIds, relationshipIds);
  }

  private static void writePackage(
      StringBuilder xml,
      IdentifierMap ids,
      TypeResolver types,
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
          types,
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
      TypeResolver types,
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
      case "Class" -> writeClassifier(xml, ids, types, "uml:Class", node, elementId);
      case "Interface" -> writeClassifier(xml, ids, types, "uml:Interface", node, elementId);
      case "DataType" -> writeClassifier(xml, ids, types, "uml:DataType", node, elementId);
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

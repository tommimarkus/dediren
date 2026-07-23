package dev.dediren.plugins.umlxmi.build;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.UML_NS;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.XMI_NS;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.attr;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.genericGraphPluginData;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.umlString;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.writeEmptyPackagedElement;
import static dev.dediren.plugins.umlxmi.write.activity.ActivityWriter.writeActivity;
import static dev.dediren.plugins.umlxmi.write.classifier.ClassRelationshipWriter.writeClassRelationships;
import static dev.dediren.plugins.umlxmi.write.classifier.ClassRelationshipWriter.writeUseCaseAssociations;
import static dev.dediren.plugins.umlxmi.write.classifier.ClassifierWriter.writeClassifier;
import static dev.dediren.plugins.umlxmi.write.classifier.ClassifierWriter.writeEnumeration;
import static dev.dediren.plugins.umlxmi.write.component.ComponentWriter.writeComponent;
import static dev.dediren.plugins.umlxmi.write.component.ComponentWriter.writeComponentRelationships;
import static dev.dediren.plugins.umlxmi.write.deployment.DeploymentWriter.isDeploymentNodeType;
import static dev.dediren.plugins.umlxmi.write.deployment.DeploymentWriter.writeDeploymentModel;
import static dev.dediren.plugins.umlxmi.write.interaction.InteractionWriter.writeInteraction;
import static dev.dediren.plugins.umlxmi.write.statemachine.StateMachineWriter.writeStateMachine;
import static dev.dediren.plugins.umlxmi.write.usecase.UseCaseWriter.writeUseCase;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.UmlXmiExportPolicy;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.ModelExportRequest;
import dev.dediren.plugins.umlxmi.write.diagram.DiagramWriter;
import java.util.ArrayList;
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

  /**
   * A built single-view model plus the set of selected source ids the writers actually emitted, so
   * the engine can distinguish in-view content that is represented from in-view content silently
   * dropped (the coverage blind spot). An id is represented iff its {@code xmi:id} appears in the
   * document.
   */
  public record BuiltModel(
      String content, Set<String> representedNodeIds, Set<String> representedRelationshipIds) {
    public BuiltModel {
      representedNodeIds = Set.copyOf(representedNodeIds);
      representedRelationshipIds = Set.copyOf(representedRelationshipIds);
    }
  }

  public static BuiltModel buildXmi(ExportRequest request, UmlXmiExportPolicy policy) {
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
    var ids = new IdentifierMap(policy.modelIdentifier());
    ModelContent model =
        writeModelContent(xml, ids, request, policy, selectedNodes, selectedRelationships);
    xml.append("</xmi:XMI>\n");
    String content = xml.toString();
    Set<String> representedNodeIds =
        selectedNodes.stream()
            .map(SourceNode::id)
            .filter(id -> isRepresented(content, model.nodeIds().get(id)))
            .collect(Collectors.toSet());
    Set<String> representedRelationshipIds =
        selectedRelationships.stream()
            .map(SourceRelationship::id)
            .filter(id -> isRepresented(content, model.relationshipIds().get(id)))
            .collect(Collectors.toSet());
    return new BuiltModel(content, representedNodeIds, representedRelationshipIds);
  }

  private static boolean isRepresented(String content, String xmiId) {
    return xmiId != null && content.contains("xmi:id=\"" + xmiId + "\"");
  }

  /**
   * The whole-model lane: one document carrying the full model once, then one OMG UMLDI diagram per
   * supplied laid-out view (mirrors the ArchiMate-OEF {@code buildModelOef}). The model section
   * includes every source element so a per-view diagram can reference any of them; each diagram
   * serializes only its own view's geometry. The single-view {@link #buildXmi} stays model-only.
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
    var ids = new IdentifierMap(policy.modelIdentifier());
    ModelContent model =
        writeModelContent(xml, ids, representative, policy, selectedNodes, selectedRelationships);
    Map<String, String> viewLabels = viewLabels(representative);
    for (ModelExportRequest.ViewLayout view : request.views()) {
      DiagramWriter.writeUmlDiagram(
          xml,
          view.layout(),
          resolveDiagramIdentity(policy, view.viewId(), viewLabels.get(view.viewId())),
          ids,
          model.nodeIds(),
          model.relationshipIds());
    }
    xml.append("</xmi:XMI>\n");
    return xml.toString();
  }

  /**
   * Per-view UMLDI diagram identity: an explicit {@code views[viewId]} override wins field by
   * field, else the source-derived default ({@code id-diagram-<viewId>} and the view's own label).
   */
  private static DiagramWriter.DiagramIdentity resolveDiagramIdentity(
      UmlXmiExportPolicy policy, String viewId, String sourceLabel) {
    UmlXmiExportPolicy.DiagramIdentity override =
        policy.views().getOrDefault(viewId, new UmlXmiExportPolicy.DiagramIdentity(null, null));
    String identifier =
        override.diagramIdentifier() != null
            ? override.diagramIdentifier()
            : "id-diagram-" + viewId;
    String name =
        override.diagramName() != null
            ? override.diagramName()
            : sourceLabel != null ? sourceLabel : viewId;
    return new DiagramWriter.DiagramIdentity(identifier, name);
  }

  private static Map<String, String> viewLabels(ExportRequest request) {
    var labels = new HashMap<String, String>();
    try {
      for (GenericGraphView view : genericGraphPluginData(request).views()) {
        labels.put(view.id(), view.label());
      }
    } catch (RuntimeException error) {
      // A UML source always carries generic-graph views; if one somehow does not, fall back to the
      // view id as the diagram name rather than failing the aggregate.
    }
    return labels;
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
  private record ModelContent(Map<String, String> nodeIds, Map<String, String> relationshipIds) {
    ModelContent {
      nodeIds = Map.copyOf(nodeIds);
      relationshipIds = Map.copyOf(relationshipIds);
    }
  }

  /**
   * Writes the {@code <uml:Model>…</uml:Model>} section for the given scope into {@code xml} using
   * the caller's shared {@link IdentifierMap} (so the caller can mint further globally-unique
   * {@code xmi:id}s for UMLDI), and returns the element id maps a diagram references. Does not open
   * or close the {@code xmi:XMI} root.
   */
  private static ModelContent writeModelContent(
      StringBuilder xml,
      IdentifierMap ids,
      ExportRequest request,
      UmlXmiExportPolicy policy,
      List<SourceNode> selectedNodes,
      List<SourceRelationship> selectedRelationships) {
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

    // Generalizations are owned by the specific (source) Classifier, so gather them by owner to
    // nest
    // inside the classifier element rather than emit them as (illegal) standalone packagedElements.
    var generalizationsBySpecific = new HashMap<String, List<String[]>>();
    for (SourceRelationship relationship : selectedRelationships) {
      if (relationship.type().equals("Generalization")
          && relationshipIds.containsKey(relationship.id())
          && nodeIds.containsKey(relationship.source())
          && nodeIds.containsKey(relationship.target())
          && isClassifier(sourceNodesById.get(relationship.source()))
          && isClassifier(sourceNodesById.get(relationship.target()))) {
        generalizationsBySpecific
            .computeIfAbsent(relationship.source(), key -> new ArrayList<>())
            .add(
                new String[] {
                  relationshipIds.get(relationship.id()), nodeIds.get(relationship.target())
                });
      }
    }

    // Deployment node types are emitted as one ownership hierarchy by DeploymentWriter, not by the
    // generic node loop below.
    Set<String> deploymentNodeIds =
        selectedNodes.stream()
            .filter(node -> isDeploymentNodeType(node.type()))
            .map(SourceNode::id)
            .collect(Collectors.toSet());

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
          && !deploymentNodeIds.contains(node.id())
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
      if (nestedNodeIds.contains(node.id()) || deploymentNodeIds.contains(node.id())) {
        continue; // written inside its owning Package, or by the deployment hierarchy, below
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
            sourceNodesById,
            nodeIds,
            relationshipIds,
            generalizationsBySpecific);
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
            sourceNodesById,
            nodeIds,
            relationshipIds,
            generalizationsBySpecific);
      }
    }
    writeDeploymentModel(
        xml, ids, selectedNodes, selectedRelationships, sourceNodesById, nodeIds, relationshipIds);
    writeClassRelationships(
        xml, ids, selectedRelationships, sourceNodesById, nodeIds, relationshipIds);
    writeUseCaseAssociations(
        xml, ids, selectedRelationships, sourceNodesById, nodeIds, relationshipIds);
    writeComponentRelationships(
        xml, selectedRelationships, sourceNodesById, nodeIds, relationshipIds);
    // Emit the primitive/data type targets referenced by attributes above, after every classifier
    // has been written so all referenced types are known. XML idrefs need not precede their target.
    types.writeSynthesizedTypes(xml);
    xml.append("</uml:Model>");
    return new ModelContent(nodeIds, relationshipIds);
  }

  private static boolean isClassifier(SourceNode node) {
    return node != null && CLASSIFIER_TYPES.contains(node.type());
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
      Map<String, SourceNode> sourceNodesById,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds,
      Map<String, List<String[]>> generalizationsBySpecific) {
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
          sourceNodesById,
          nodeIds,
          relationshipIds,
          generalizationsBySpecific);
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
      Map<String, SourceNode> sourceNodesById,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds,
      Map<String, List<String[]>> generalizationsBySpecific) {
    List<String[]> generalizations = generalizationsBySpecific.getOrDefault(node.id(), List.of());
    switch (node.type()) {
      case "Package" -> writeEmptyPackagedElement(xml, "uml:Package", node, elementId);
      case "Component" ->
          writeComponent(
              xml,
              node,
              elementId,
              selectedNodes,
              selectedRelationships,
              sourceNodesById,
              nodeIds,
              relationshipIds);
      case "Class" ->
          writeClassifier(xml, ids, types, "uml:Class", node, elementId, generalizations);
      case "Interface" ->
          writeClassifier(xml, ids, types, "uml:Interface", node, elementId, generalizations);
      case "DataType" ->
          writeClassifier(xml, ids, types, "uml:DataType", node, elementId, generalizations);
      case "Enumeration" -> writeEnumeration(xml, ids, node, elementId);
      case "Actor" -> writeEmptyPackagedElement(xml, "uml:Actor", node, elementId);
      case "UseCase" ->
          writeUseCase(
              xml, node, elementId, selectedNodes, selectedRelationships, nodeIds, relationshipIds);
      case "Activity" ->
          writeActivity(
              xml,
              ids,
              types,
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

package dev.dediren.plugins.umlxmi.write.deployment;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.*;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.plugins.umlxmi.build.IdentifierMap;
import java.util.*;

/**
 * Emits the UML deployment family as a conformant ownership hierarchy: DeploymentTargets
 * (Node/Device/ExecutionEnvironment) nest via {@code nestedNode}; a {@code Deployment} nests inside
 * its target Node ({@code location} subsets owner); a {@code Manifestation} nests inside its
 * Artifact ({@code artifact} subsets owner); and a {@code CommunicationPath} is an Association
 * emitted with two {@code ownedEnd}s. The deployment node types are written here (not by {@code
 * XmiBuilder}'s generic node loop) so the hierarchy can be built in one place.
 */
public final class DeploymentWriter {

  private DeploymentWriter() {}

  private static final Set<String> DEPLOYMENT_TARGET_TYPES =
      Set.of("Node", "Device", "ExecutionEnvironment");
  private static final Set<String> DEPLOYED_ARTIFACT_TYPES =
      Set.of("Artifact", "DeploymentSpecification");

  /** Node types this writer owns end to end (skipped by the generic node loop). */
  public static boolean isDeploymentNodeType(String nodeType) {
    return DEPLOYMENT_TARGET_TYPES.contains(nodeType) || DEPLOYED_ARTIFACT_TYPES.contains(nodeType);
  }

  public static void writeDeploymentModel(
      StringBuilder xml,
      IdentifierMap ids,
      List<SourceNode> selectedNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, SourceNode> sourceNodesById,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    // DeploymentTarget nesting: child target -> parent target, when the parent is in scope.
    var parentByChild = new HashMap<String, String>();
    for (SourceNode node : selectedNodes) {
      if (isDeploymentTargetType(node)) {
        String parent = umlString(node, "node");
        if (parent != null
            && nodeIds.containsKey(parent)
            && isDeploymentTargetType(sourceNodesById.get(parent))) {
          parentByChild.put(node.id(), parent);
        }
      }
    }
    var deploymentsByLocation = new LinkedHashMap<String, List<SourceRelationship>>();
    var manifestationsByArtifact = new LinkedHashMap<String, List<SourceRelationship>>();
    var communicationPaths = new ArrayList<SourceRelationship>();
    for (SourceRelationship relationship : selectedRelationships) {
      if (!isDeploymentExportRelationship(relationship, sourceNodesById)
          || !relationshipIds.containsKey(relationship.id())
          || !nodeIds.containsKey(relationship.source())
          || !nodeIds.containsKey(relationship.target())) {
        continue;
      }
      switch (relationship.type()) {
        case "Deployment" ->
            deploymentsByLocation
                .computeIfAbsent(relationship.target(), key -> new ArrayList<>())
                .add(relationship);
        case "Manifestation" ->
            manifestationsByArtifact
                .computeIfAbsent(relationship.source(), key -> new ArrayList<>())
                .add(relationship);
        case "CommunicationPath" -> communicationPaths.add(relationship);
        default -> {}
      }
    }

    // Top-level DeploymentTargets (not nested under another in-scope target), recursively.
    for (SourceNode node : selectedNodes) {
      if (isDeploymentTargetType(node)
          && nodeIds.containsKey(node.id())
          && !parentByChild.containsKey(node.id())) {
        writeDeploymentTarget(
            xml,
            node,
            true,
            selectedNodes,
            parentByChild,
            deploymentsByLocation,
            nodeIds,
            relationshipIds);
      }
    }
    // Artifacts (and DeploymentSpecifications) with their owned Manifestations.
    for (SourceNode node : selectedNodes) {
      if (isDeployedArtifactType(node) && nodeIds.containsKey(node.id())) {
        writeArtifact(
            xml,
            node,
            nodeIds.get(node.id()),
            manifestationsByArtifact.getOrDefault(node.id(), List.of()),
            nodeIds,
            relationshipIds);
      }
    }
    // CommunicationPaths (Associations) with two ownedEnds.
    for (SourceRelationship communicationPath : communicationPaths) {
      writeCommunicationPath(xml, ids, communicationPath, nodeIds, relationshipIds);
    }
  }

  private static void writeDeploymentTarget(
      StringBuilder xml,
      SourceNode node,
      boolean topLevel,
      List<SourceNode> selectedNodes,
      Map<String, String> parentByChild,
      Map<String, List<SourceRelationship>> deploymentsByLocation,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    String element = topLevel ? "packagedElement" : "nestedNode";
    List<SourceNode> children =
        selectedNodes.stream()
            .filter(child -> node.id().equals(parentByChild.get(child.id())))
            .toList();
    List<SourceRelationship> deployments = deploymentsByLocation.getOrDefault(node.id(), List.of());
    xml.append("<")
        .append(element)
        .append(" xmi:type=\"")
        .append(deploymentTargetUmlType(node.type()))
        .append("\" xmi:id=\"")
        .append(attr(nodeIds.get(node.id())))
        .append("\" name=\"")
        .append(attr(node.label()))
        .append("\"");
    if (children.isEmpty() && deployments.isEmpty()) {
      xml.append("/>");
      return;
    }
    xml.append(">");
    for (SourceNode child : children) {
      writeDeploymentTarget(
          xml,
          child,
          false,
          selectedNodes,
          parentByChild,
          deploymentsByLocation,
          nodeIds,
          relationshipIds);
    }
    for (SourceRelationship deployment : deployments) {
      // UML 2.5.1 §19.5.4: Deployment is owned by its location (location subsets client + owner);
      // deployedArtifact subsets supplier. Emit the specialized ends only, not a contradictory
      // client/supplier pair.
      xml.append("<deployment xmi:type=\"uml:Deployment\" xmi:id=\"")
          .append(attr(relationshipIds.get(deployment.id())))
          .append("\" name=\"")
          .append(attr(deployment.label()))
          .append("\" deployedArtifact=\"")
          .append(attr(nodeIds.get(deployment.source())))
          .append("\" location=\"")
          .append(attr(nodeIds.get(deployment.target())))
          .append("\"/>");
    }
    xml.append("</").append(element).append(">");
  }

  private static void writeArtifact(
      StringBuilder xml,
      SourceNode artifact,
      String artifactId,
      List<SourceRelationship> manifestations,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    String umlType =
        artifact.type().equals("DeploymentSpecification")
            ? "uml:DeploymentSpecification"
            : "uml:Artifact";
    xml.append("<packagedElement xmi:type=\"")
        .append(umlType)
        .append("\" xmi:id=\"")
        .append(attr(artifactId))
        .append("\" name=\"")
        .append(attr(artifact.label()))
        .append("\"");
    if (manifestations.isEmpty()) {
      xml.append("/>");
      return;
    }
    xml.append(">");
    for (SourceRelationship manifestation : manifestations) {
      // UML 2.5.1 §19.5.9: Manifestation is owned by its Artifact (artifact subsets client +
      // owner);
      // utilizedElement subsets supplier.
      xml.append("<manifestation xmi:type=\"uml:Manifestation\" xmi:id=\"")
          .append(attr(relationshipIds.get(manifestation.id())))
          .append("\" name=\"")
          .append(attr(manifestation.label()))
          .append("\" utilizedElement=\"")
          .append(attr(nodeIds.get(manifestation.target())))
          .append("\"/>");
    }
    xml.append("</packagedElement>");
  }

  private static void writeCommunicationPath(
      StringBuilder xml,
      IdentifierMap ids,
      SourceRelationship communicationPath,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    String pathId = relationshipIds.get(communicationPath.id());
    String sourceEnd = ids.xmiId(communicationPath.id() + "-end-source");
    String targetEnd = ids.xmiId(communicationPath.id() + "-end-target");
    // UML 2.5.1 §11.8.1: a CommunicationPath is an Association requiring memberEnd [2..*]; /endType
    // is
    // derived, so emit two ownedEnds typed by the connected nodes rather than a standalone endType.
    xml.append("<packagedElement xmi:type=\"uml:CommunicationPath\" xmi:id=\"")
        .append(attr(pathId))
        .append("\" name=\"")
        .append(attr(communicationPath.label()))
        .append("\" memberEnd=\"")
        .append(attr(sourceEnd + " " + targetEnd))
        .append("\"><ownedEnd xmi:id=\"")
        .append(attr(sourceEnd))
        .append("\" type=\"")
        .append(attr(nodeIds.get(communicationPath.source())))
        .append("\" association=\"")
        .append(attr(pathId))
        .append("\"/><ownedEnd xmi:id=\"")
        .append(attr(targetEnd))
        .append("\" type=\"")
        .append(attr(nodeIds.get(communicationPath.target())))
        .append("\" association=\"")
        .append(attr(pathId))
        .append("\"/></packagedElement>");
  }

  public static boolean isDeploymentExportRelationship(
      SourceRelationship relationship, Map<String, SourceNode> sourceNodesById) {
    SourceNode source = sourceNodesById.get(relationship.source());
    SourceNode target = sourceNodesById.get(relationship.target());
    return switch (relationship.type()) {
      case "Deployment" -> isDeployedArtifactType(source) && isDeploymentTargetType(target);
      case "Manifestation" -> isDeployedArtifactType(source) && isManifestedElementType(target);
      case "CommunicationPath" -> isDeploymentTargetType(source) && isDeploymentTargetType(target);
      default -> false;
    };
  }

  private static String deploymentTargetUmlType(String nodeType) {
    return switch (nodeType) {
      case "Device" -> "uml:Device";
      case "ExecutionEnvironment" -> "uml:ExecutionEnvironment";
      default -> "uml:Node";
    };
  }

  public static boolean isDeployedArtifactType(SourceNode node) {
    return node != null && DEPLOYED_ARTIFACT_TYPES.contains(node.type());
  }

  public static boolean isDeploymentTargetType(SourceNode node) {
    return node != null && DEPLOYMENT_TARGET_TYPES.contains(node.type());
  }

  public static boolean isManifestedElementType(SourceNode node) {
    return node != null
        && (node.type().equals("Package")
            || node.type().equals("Class")
            || node.type().equals("Interface")
            || node.type().equals("DataType")
            || node.type().equals("Enumeration")
            || node.type().equals("Component"));
  }
}

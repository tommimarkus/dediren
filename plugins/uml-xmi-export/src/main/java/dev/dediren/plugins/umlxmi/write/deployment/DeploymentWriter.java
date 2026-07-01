package dev.dediren.plugins.umlxmi.write.deployment;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.*;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.*;

public final class DeploymentWriter {

  private DeploymentWriter() {}

  public static void writeDeploymentRelationships(
      StringBuilder xml,
      List<SourceRelationship> selectedRelationships,
      Map<String, SourceNode> sourceNodesById,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    for (SourceRelationship relationship : selectedRelationships) {
      if (!isDeploymentExportRelationship(relationship, sourceNodesById)
          || !relationshipIds.containsKey(relationship.id())
          || !nodeIds.containsKey(relationship.source())
          || !nodeIds.containsKey(relationship.target())) {
        continue;
      }
      switch (relationship.type()) {
        case "Deployment" -> writeDeployment(xml, relationship, nodeIds, relationshipIds);
        case "Manifestation" -> writeManifestation(xml, relationship, nodeIds, relationshipIds);
        case "CommunicationPath" ->
            writeCommunicationPath(xml, relationship, nodeIds, relationshipIds);
        default -> {}
      }
    }
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

  public static void writeDeployment(
      StringBuilder xml,
      SourceRelationship deployment,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    String artifactId = nodeIds.get(deployment.source());
    String locationId = nodeIds.get(deployment.target());
    xml.append("<packagedElement xmi:type=\"uml:Deployment\" xmi:id=\"")
        .append(attr(relationshipIds.get(deployment.id())))
        .append("\" name=\"")
        .append(attr(deployment.label()))
        .append("\" client=\"")
        .append(attr(artifactId))
        .append("\" supplier=\"")
        .append(attr(locationId))
        .append("\" deployedArtifact=\"")
        .append(attr(artifactId))
        .append("\" location=\"")
        .append(attr(locationId))
        .append("\"/>");
  }

  public static void writeManifestation(
      StringBuilder xml,
      SourceRelationship manifestation,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    String artifactId = nodeIds.get(manifestation.source());
    String utilizedElementId = nodeIds.get(manifestation.target());
    xml.append("<packagedElement xmi:type=\"uml:Manifestation\" xmi:id=\"")
        .append(attr(relationshipIds.get(manifestation.id())))
        .append("\" name=\"")
        .append(attr(manifestation.label()))
        .append("\" client=\"")
        .append(attr(artifactId))
        .append("\" supplier=\"")
        .append(attr(utilizedElementId))
        .append("\" utilizedElement=\"")
        .append(attr(utilizedElementId))
        .append("\"/>");
  }

  public static void writeCommunicationPath(
      StringBuilder xml,
      SourceRelationship communicationPath,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    xml.append("<packagedElement xmi:type=\"uml:CommunicationPath\" xmi:id=\"")
        .append(attr(relationshipIds.get(communicationPath.id())))
        .append("\" name=\"")
        .append(attr(communicationPath.label()))
        .append("\" endType=\"")
        .append(
            attr(
                nodeIds.get(communicationPath.source())
                    + " "
                    + nodeIds.get(communicationPath.target())))
        .append("\"/>");
  }

  public static boolean isDeployedArtifactType(SourceNode node) {
    return node != null
        && (node.type().equals("Artifact") || node.type().equals("DeploymentSpecification"));
  }

  public static boolean isDeploymentTargetType(SourceNode node) {
    return node != null
        && (node.type().equals("Node")
            || node.type().equals("Device")
            || node.type().equals("ExecutionEnvironment"));
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

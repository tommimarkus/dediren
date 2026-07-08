package dev.dediren.plugins.umlxmi.write.component;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.*;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.*;

public final class ComponentWriter {

  private ComponentWriter() {}

  public static void writeComponent(
      StringBuilder xml,
      SourceNode component,
      String componentId,
      List<SourceNode> selectedNodes,
      Map<String, String> nodeIds) {
    xml.append("<packagedElement xmi:type=\"uml:Component\" xmi:id=\"")
        .append(attr(componentId))
        .append("\" name=\"")
        .append(attr(component.label()))
        .append("\">");
    selectedNodes.stream()
        .filter(node -> node.type().equals("Port"))
        .filter(node -> component.id().equals(umlString(node, "component")))
        .filter(node -> nodeIds.containsKey(node.id()))
        .forEach(port -> writeOwnedPort(xml, port, nodeIds));
    xml.append("</packagedElement>");
  }

  public static void writeOwnedPort(
      StringBuilder xml, SourceNode port, Map<String, String> nodeIds) {
    xml.append("<ownedAttribute xmi:type=\"uml:Port\" xmi:id=\"")
        .append(attr(nodeIds.get(port.id())))
        .append("\" name=\"")
        .append(attr(port.label()))
        .append("\"");
    writeReferencedClassifierIds(xml, "provided", umlTextArray(port, "provided"), nodeIds);
    writeReferencedClassifierIds(xml, "required", umlTextArray(port, "required"), nodeIds);
    xml.append("/>");
  }

  public static void writeComponentRelationships(
      StringBuilder xml,
      List<SourceRelationship> selectedRelationships,
      Map<String, SourceNode> sourceNodesById,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    for (SourceRelationship relationship : selectedRelationships) {
      if (!isComponentExportRelationship(relationship, sourceNodesById)
          || !relationshipIds.containsKey(relationship.id())
          || !nodeIds.containsKey(relationship.source())
          || !nodeIds.containsKey(relationship.target())) {
        continue;
      }
      xml.append("<packagedElement xmi:type=\"")
          .append(componentRelationshipXmiType(relationship.type()))
          .append("\" xmi:id=\"")
          .append(attr(relationshipIds.get(relationship.id())))
          .append("\" name=\"")
          .append(attr(relationship.label()))
          .append("\" client=\"")
          .append(attr(nodeIds.get(relationship.source())))
          .append("\" supplier=\"")
          .append(attr(nodeIds.get(relationship.target())))
          .append("\"/>");
    }
  }

  public static boolean isComponentExportRelationship(
      SourceRelationship relationship, Map<String, SourceNode> sourceNodesById) {
    if (!relationship.type().equals("Dependency")
        && !relationship.type().equals("Realization")
        && !relationship.type().equals("Usage")) {
      return false;
    }
    SourceNode source = sourceNodesById.get(relationship.source());
    SourceNode target = sourceNodesById.get(relationship.target());
    return isComponentEndpoint(source) || isComponentEndpoint(target);
  }

  public static boolean isComponentEndpoint(SourceNode node) {
    return node != null && (node.type().equals("Component") || node.type().equals("Port"));
  }

  public static String componentRelationshipXmiType(String type) {
    return switch (type) {
      case "Realization" -> "uml:Realization";
      case "Usage" -> "uml:Usage";
      default -> "uml:Dependency";
    };
  }
}

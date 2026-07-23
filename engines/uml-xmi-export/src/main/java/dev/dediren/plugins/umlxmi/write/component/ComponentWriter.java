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
      List<SourceRelationship> selectedRelationships,
      Map<String, SourceNode> sourceNodesById,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
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
    // A component→Interface Realization is an InterfaceRealization owned by the implementing
    // Component (UML 2.5.1 §10.5.6: implementingClassifier subsets owner), and it is what drives
    // Component::/provided — so it nests here rather than being a standalone uml:Realization.
    for (SourceRelationship relationship : selectedRelationships) {
      if (relationship.type().equals("Realization")
          && component.id().equals(relationship.source())
          && relationshipIds.containsKey(relationship.id())
          && nodeIds.containsKey(relationship.target())
          && isInterface(sourceNodesById.get(relationship.target()))) {
        xml.append("<interfaceRealization xmi:type=\"uml:InterfaceRealization\" xmi:id=\"")
            .append(attr(relationshipIds.get(relationship.id())))
            .append("\" name=\"")
            .append(attr(relationship.label()))
            .append("\" contract=\"")
            .append(attr(nodeIds.get(relationship.target())))
            .append("\"/>");
      }
    }
    xml.append("</packagedElement>");
  }

  public static void writeOwnedPort(
      StringBuilder xml, SourceNode port, Map<String, String> nodeIds) {
    xml.append("<ownedAttribute xmi:type=\"uml:Port\" xmi:id=\"")
        .append(attr(nodeIds.get(port.id())))
        .append("\" name=\"")
        .append(attr(port.label()))
        .append("\"");
    // UML 2.5.1 §11.8.14: Port::/provided and /required are DERIVED from the Port's type, so they
    // are
    // not emitted as structural attributes. A single provided Interface is captured conformantly by
    // typing the Port with it (/provided then derives, spec p273); required and multi-provided
    // interface wiring is carried by the component's Usage / InterfaceRealization.
    List<String> provided = umlTextArray(port, "provided");
    if (provided.size() == 1 && nodeIds.containsKey(provided.get(0))) {
      xml.append(" type=\"").append(attr(nodeIds.get(provided.get(0)))).append("\"");
    }
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
      // A component→Interface Realization is emitted as a nested InterfaceRealization by
      // writeComponent; skip it here so it is not also written as a standalone packagedElement.
      if (relationship.type().equals("Realization")
          && isComponentEndpoint(sourceNodesById.get(relationship.source()))
          && isInterface(sourceNodesById.get(relationship.target()))) {
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

  private static boolean isInterface(SourceNode node) {
    return node != null && node.type().equals("Interface");
  }

  public static String componentRelationshipXmiType(String type) {
    return switch (type) {
      case "Realization" -> "uml:Realization";
      case "Usage" -> "uml:Usage";
      default -> "uml:Dependency";
    };
  }
}

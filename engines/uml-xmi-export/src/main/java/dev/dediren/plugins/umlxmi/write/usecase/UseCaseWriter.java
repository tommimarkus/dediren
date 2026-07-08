package dev.dediren.plugins.umlxmi.write.usecase;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.*;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.*;

public final class UseCaseWriter {

  private UseCaseWriter() {}

  public static void writeUseCase(
      StringBuilder xml,
      SourceNode useCase,
      String useCaseId,
      List<SourceNode> selectedNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    xml.append("<packagedElement xmi:type=\"uml:UseCase\" xmi:id=\"")
        .append(attr(useCaseId))
        .append("\" name=\"")
        .append(attr(useCase.label()))
        .append("\"");
    String subject = umlString(useCase, "subject");
    if (subject != null && nodeIds.containsKey(subject)) {
      xml.append(" subject=\"").append(attr(nodeIds.get(subject))).append("\"");
    }
    List<SourceNode> extensionPoints =
        selectedNodes.stream()
            .filter(node -> node.type().equals("ExtensionPoint"))
            .filter(node -> useCase.id().equals(umlString(node, "use_case")))
            .filter(node -> nodeIds.containsKey(node.id()))
            .toList();
    List<SourceRelationship> includes =
        selectedRelationships.stream()
            .filter(relationship -> relationship.type().equals("Include"))
            .filter(relationship -> useCase.id().equals(relationship.source()))
            .filter(relationship -> relationshipIds.containsKey(relationship.id()))
            .filter(relationship -> nodeIds.containsKey(relationship.target()))
            .toList();
    List<SourceRelationship> extendsRelationships =
        selectedRelationships.stream()
            .filter(relationship -> relationship.type().equals("Extend"))
            .filter(relationship -> useCase.id().equals(relationship.source()))
            .filter(relationship -> relationshipIds.containsKey(relationship.id()))
            .filter(relationship -> nodeIds.containsKey(relationship.target()))
            .toList();
    if (extensionPoints.isEmpty() && includes.isEmpty() && extendsRelationships.isEmpty()) {
      xml.append("/>");
      return;
    }
    xml.append(">");
    for (SourceNode extensionPoint : extensionPoints) {
      writeExtensionPoint(xml, extensionPoint, nodeIds.get(extensionPoint.id()));
    }
    for (SourceRelationship include : includes) {
      writeInclude(xml, include, relationshipIds.get(include.id()), nodeIds);
    }
    for (SourceRelationship extend : extendsRelationships) {
      writeExtend(xml, extend, relationshipIds.get(extend.id()), nodeIds);
    }
    xml.append("</packagedElement>");
  }

  public static void writeExtensionPoint(
      StringBuilder xml, SourceNode extensionPoint, String extensionPointId) {
    xml.append("<extensionPoint xmi:id=\"")
        .append(attr(extensionPointId))
        .append("\" name=\"")
        .append(attr(extensionPoint.label()))
        .append("\"/>");
  }

  public static void writeInclude(
      StringBuilder xml,
      SourceRelationship include,
      String includeId,
      Map<String, String> nodeIds) {
    xml.append("<include xmi:id=\"")
        .append(attr(includeId))
        .append("\" name=\"")
        .append(attr(include.label()))
        .append("\" addition=\"")
        .append(attr(nodeIds.get(include.target())))
        .append("\"/>");
  }

  public static void writeExtend(
      StringBuilder xml, SourceRelationship extend, String extendId, Map<String, String> nodeIds) {
    xml.append("<extend xmi:id=\"")
        .append(attr(extendId))
        .append("\" name=\"")
        .append(attr(extend.label()))
        .append("\" extendedCase=\"")
        .append(attr(nodeIds.get(extend.target())))
        .append("\"");
    String extensionPointId = umlString(extend, "extension_point");
    if (extensionPointId != null && nodeIds.containsKey(extensionPointId)) {
      xml.append(" extensionLocation=\"").append(attr(nodeIds.get(extensionPointId))).append("\"");
    }
    xml.append("/>");
  }
}

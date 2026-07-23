package dev.dediren.plugins.umlxmi.write.classifier;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.attr;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.multiplicityBounds;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.umlString;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.writeMultiplicityValues;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.plugins.umlxmi.build.IdentifierMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits UML class-diagram relationships as owned {@code packagedElement}s. {@code Association},
 * {@code Aggregation}, and {@code Composition} become {@code uml:Association} elements with two
 * {@code ownedEnd} member ends carrying role, type, aggregation kind, and multiplicity; {@code
 * Dependency} and {@code Realization} between classifiers become client/supplier {@code
 * uml:Dependency}/{@code uml:Realization} elements. Relationships whose endpoints are not both UML
 * classifiers are left to the component and deployment writers.
 */
public final class ClassRelationshipWriter {

  private ClassRelationshipWriter() {}

  private static final Set<String> CLASSIFIER_TYPES =
      Set.of("Class", "Interface", "DataType", "Enumeration");

  private static final Set<String> CLASS_RELATIONSHIP_TYPES =
      Set.of("Association", "Aggregation", "Composition", "Dependency", "Realization");

  public static void writeClassRelationships(
      StringBuilder xml,
      IdentifierMap ids,
      List<SourceRelationship> selectedRelationships,
      Map<String, SourceNode> sourceNodesById,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    for (SourceRelationship relationship : selectedRelationships) {
      if (!relationshipIds.containsKey(relationship.id())
          || !nodeIds.containsKey(relationship.source())
          || !nodeIds.containsKey(relationship.target())
          || !isClassRelationship(relationship, sourceNodesById)) {
        continue;
      }
      switch (relationship.type()) {
        case "Association", "Aggregation", "Composition" ->
            writeAssociation(xml, ids, relationship, nodeIds, relationshipIds);
        case "Dependency" ->
            writeClientSupplier(xml, "uml:Dependency", relationship, nodeIds, relationshipIds);
        case "Realization" ->
            writeClientSupplier(xml, "uml:Realization", relationship, nodeIds, relationshipIds);
        default -> {}
      }
    }
  }

  /**
   * Emits Actor↔UseCase {@code uml:Association}s. Actor and UseCase are Classifiers, so their
   * association is an ordinary binary Association with two {@code ownedEnd}s — the same shape as a
   * class association. Their endpoint types are excluded from {@link #isClassRelationship}, so this
   * lane handles them (the source validator accepts them via {@code Uml.isActorUseCasePair});
   * without it the defining relationship of a use-case diagram is silently dropped.
   */
  public static void writeUseCaseAssociations(
      StringBuilder xml,
      IdentifierMap ids,
      List<SourceRelationship> selectedRelationships,
      Map<String, SourceNode> sourceNodesById,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    for (SourceRelationship relationship : selectedRelationships) {
      if (!"Association".equals(relationship.type())
          || !relationshipIds.containsKey(relationship.id())
          || !nodeIds.containsKey(relationship.source())
          || !nodeIds.containsKey(relationship.target())
          || !isActorUseCasePair(
              sourceNodesById.get(relationship.source()),
              sourceNodesById.get(relationship.target()))) {
        continue;
      }
      writeAssociation(xml, ids, relationship, nodeIds, relationshipIds);
    }
  }

  private static boolean isActorUseCasePair(SourceNode source, SourceNode target) {
    return source != null
        && target != null
        && ((source.type().equals("Actor") && target.type().equals("UseCase"))
            || (source.type().equals("UseCase") && target.type().equals("Actor")));
  }

  public static boolean isClassRelationship(
      SourceRelationship relationship, Map<String, SourceNode> sourceNodesById) {
    if (!CLASS_RELATIONSHIP_TYPES.contains(relationship.type())) {
      return false;
    }
    return isClassifier(sourceNodesById.get(relationship.source()))
        && isClassifier(sourceNodesById.get(relationship.target()));
  }

  private static boolean isClassifier(SourceNode node) {
    return node != null && CLASSIFIER_TYPES.contains(node.type());
  }

  private static void writeAssociation(
      StringBuilder xml,
      IdentifierMap ids,
      SourceRelationship relationship,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    String associationId = relationshipIds.get(relationship.id());
    String sourceEndId = ids.xmiId(relationship.id() + "-source-end");
    String targetEndId = ids.xmiId(relationship.id() + "-target-end");
    xml.append("<packagedElement xmi:type=\"uml:Association\" xmi:id=\"")
        .append(attr(associationId))
        .append("\" name=\"")
        .append(attr(relationship.label()))
        .append("\" memberEnd=\"")
        .append(attr(sourceEndId + " " + targetEndId))
        .append("\">");
    writeOwnedEnd(
        xml,
        sourceEndId,
        umlString(relationship, "source_role"),
        nodeIds.get(relationship.source()),
        "none",
        associationId,
        umlString(relationship, "source_multiplicity"));
    writeOwnedEnd(
        xml,
        targetEndId,
        umlString(relationship, "target_role"),
        nodeIds.get(relationship.target()),
        aggregationKind(relationship.type()),
        associationId,
        umlString(relationship, "target_multiplicity"));
    xml.append("</packagedElement>");
  }

  private static void writeOwnedEnd(
      StringBuilder xml,
      String endId,
      String role,
      String typeId,
      String aggregation,
      String associationId,
      String multiplicity) {
    String[] bounds = multiplicityBounds(multiplicity == null ? "1" : multiplicity);
    xml.append("<ownedEnd xmi:id=\"").append(attr(endId)).append("\"");
    if (role != null) {
      xml.append(" name=\"").append(attr(role)).append("\"");
    }
    xml.append(" type=\"")
        .append(attr(typeId))
        .append("\" aggregation=\"")
        .append(attr(aggregation))
        .append("\" association=\"")
        .append(attr(associationId))
        .append("\">");
    writeMultiplicityValues(xml, endId, bounds);
    xml.append("</ownedEnd>");
  }

  private static void writeClientSupplier(
      StringBuilder xml,
      String umlType,
      SourceRelationship relationship,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    xml.append("<packagedElement xmi:type=\"")
        .append(umlType)
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

  private static String aggregationKind(String relationshipType) {
    return switch (relationshipType) {
      case "Composition" -> "composite";
      case "Aggregation" -> "shared";
      default -> "none";
    };
  }
}

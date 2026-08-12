package dev.dediren.archimate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class Archimate {
  private static final Set<String> RELATIONSHIP_CONNECTOR_TYPES =
      Set.of("AndJunction", "OrJunction");

  private static final List<String> ELEMENT_TYPES =
      List.of(
          "Plateau",
          "WorkPackage",
          "Deliverable",
          "ImplementationEvent",
          "Gap",
          "AndJunction",
          "OrJunction",
          "Grouping",
          "Location",
          "Stakeholder",
          "Driver",
          "Assessment",
          "Goal",
          "Outcome",
          "Value",
          "Meaning",
          "Constraint",
          "Requirement",
          "Principle",
          "CourseOfAction",
          "Resource",
          "ValueStream",
          "Capability",
          "BusinessInterface",
          "BusinessCollaboration",
          "BusinessActor",
          "BusinessRole",
          "BusinessProcess",
          "BusinessService",
          "BusinessInteraction",
          "BusinessFunction",
          "BusinessEvent",
          "Product",
          "BusinessObject",
          "Contract",
          "Representation",
          "ApplicationInterface",
          "ApplicationCollaboration",
          "ApplicationComponent",
          "ApplicationService",
          "ApplicationInteraction",
          "ApplicationFunction",
          "ApplicationProcess",
          "ApplicationEvent",
          "DataObject",
          "TechnologyInterface",
          "TechnologyCollaboration",
          "Node",
          "SystemSoftware",
          "Device",
          "Facility",
          "Equipment",
          "Path",
          "TechnologyService",
          "TechnologyInteraction",
          "TechnologyFunction",
          "TechnologyProcess",
          "TechnologyEvent",
          "Artifact",
          "Material",
          "CommunicationNetwork",
          "DistributionNetwork");

  private static final List<String> RELATIONSHIP_TYPES =
      List.of(
          "Composition",
          "Aggregation",
          "Assignment",
          "Realization",
          "Specialization",
          "Serving",
          "Access",
          "Influence",
          "Flow",
          "Triggering",
          "Association");

  private Archimate() {}

  public static List<String> elementTypes() {
    return ELEMENT_TYPES;
  }

  public static boolean isRelationshipConnectorType(String value) {
    return RELATIONSHIP_CONNECTOR_TYPES.contains(value);
  }

  public static void validateElementType(String value, String path)
      throws ArchimateTypeValidationException {
    if (!ELEMENT_TYPES.contains(value)) {
      throw new ArchimateTypeValidationException(ArchimateTypeKind.ELEMENT, value, path);
    }
  }

  public static void validateRelationshipType(String value, String path)
      throws ArchimateTypeValidationException {
    if (!RELATIONSHIP_TYPES.contains(value)) {
      throw new ArchimateTypeValidationException(ArchimateTypeKind.RELATIONSHIP, value, path);
    }
  }

  /**
   * Validates one relationship's endpoints against Dediren's own metamodel-derived legality rules
   * ({@link RelationshipLegality}). The rules express the ArchiMate generic-metamodel relationship
   * semantics (&sect;4&ndash;5) over element categories: any combination they do not recognise as
   * legal is rejected. The check is a sound under-approximation &mdash; it never rejects a valid
   * combination except the documented &sect;5-contradicted set it deliberately rejects (dynamic
   * relationships touching motivation/passive elements; Assignment from passive, motivation, event,
   * or service sources &mdash; {@code ArchimateRelationshipLegalityConformanceTest} holds the
   * authoritative carve-out), but does not compute Appendix B's full derivation closure, so a
   * minority of invalid combinations pass. {@code Association} is always accepted;
   * relationship-connector (junction) endpoints are validated separately by {@link
   * #validateJunctionRelationshipSemantics}.
   */
  public static void validateRelationshipEndpointTypes(
      String relationshipType, String sourceType, String targetType, String path)
      throws ArchimateTypeValidationException {
    validateRelationshipType(relationshipType, path);
    validateElementType(sourceType, path);
    validateElementType(targetType, path);
    if (isRelationshipConnectorType(sourceType) || isRelationshipConnectorType(targetType)) {
      return;
    }
    if (!RelationshipLegality.isAllowedEndpoint(relationshipType, sourceType, targetType)) {
      throw new ArchimateTypeValidationException(
          ArchimateTypeKind.RELATIONSHIP_ENDPOINT,
          sourceType + " -[" + relationshipType + "]-> " + targetType,
          path);
    }
  }

  public static void validateJunctionRelationshipSemantics(
      List<JunctionValidationNode> nodes, List<JunctionValidationRelationship> relationships)
      throws ArchimateJunctionValidationException {
    var nodeTypes = new TreeMap<String, String>();
    var nodePaths = new TreeMap<String, String>();
    for (JunctionValidationNode node : nodes) {
      nodeTypes.put(node.id(), node.nodeType());
      nodePaths.put(node.id(), node.path());
    }

    for (JunctionValidationNode node : nodes) {
      if (!isRelationshipConnectorType(node.nodeType())) {
        continue;
      }
      var incidentRelationships =
          relationships.stream()
              .filter(
                  relationship ->
                      relationship.source().equals(node.id())
                          || relationship.target().equals(node.id()))
              .filter(relationship -> !isJunctionContainmentRelationship(relationship, nodeTypes))
              .toList();
      var relationshipTypes = new TreeSet<String>();
      incidentRelationships.forEach(
          relationship -> relationshipTypes.add(relationship.relationshipType()));
      if (relationshipTypes.size() > 1) {
        throw new ArchimateJunctionValidationException(
            "DEDIREN_ARCHIMATE_JUNCTION_RELATIONSHIP_MIXED",
            node.path(),
            "ArchiMate junction "
                + node.id()
                + " connects multiple relationship types: "
                + String.join(", ", relationshipTypes));
      }
      boolean hasIncoming =
          incidentRelationships.stream()
              .anyMatch(relationship -> relationship.target().equals(node.id()));
      boolean hasOutgoing =
          incidentRelationships.stream()
              .anyMatch(relationship -> relationship.source().equals(node.id()));
      if (!hasIncoming || !hasOutgoing) {
        throw new ArchimateJunctionValidationException(
            "DEDIREN_ARCHIMATE_JUNCTION_DIRECTION_INCOMPLETE",
            node.path(),
            "ArchiMate junction "
                + node.id()
                + " must connect at least one incoming and at least one outgoing relationship");
      }
    }

    for (JunctionValidationRelationship relationship : relationships) {
      if (isJunctionContainmentRelationship(relationship, nodeTypes)) {
        continue;
      }
      String sourceType = nodeTypes.get(relationship.source());
      String targetType = nodeTypes.get(relationship.target());
      if (sourceType == null || targetType == null) {
        continue;
      }
      if (isRelationshipConnectorType(sourceType) || !isRelationshipConnectorType(targetType)) {
        continue;
      }
      String junctionPath = nodePaths.getOrDefault(relationship.target(), "$");
      boolean reachedAnElement =
          validateJunctionReachableTargets(
              relationship.relationshipType(),
              sourceType,
              relationship.target(),
              junctionPath,
              relationships,
              nodeTypes,
              new TreeSet<>());
      if (!reachedAnElement) {
        // Every junction here is already known to have an incoming and an outgoing relationship,
        // so a walk that reaches no element means the chain loops back on itself. The relationship
        // the junction stands for then has no endpoint pair, and nothing below ever checked one.
        throw new ArchimateJunctionValidationException(
            "DEDIREN_ARCHIMATE_JUNCTION_TARGET_UNREACHABLE",
            junctionPath,
            "ArchiMate junction "
                + relationship.target()
                + " routes a "
                + relationship.relationshipType()
                + " that never reaches an element, so its endpoints are never validated; the"
                + " junction chain leads back to itself rather than to a target");
      }
    }
  }

  /** Whether the walk validated at least one real element endpoint. */
  private static boolean validateJunctionReachableTargets(
      String relationshipType,
      String sourceType,
      String junctionId,
      String path,
      List<JunctionValidationRelationship> relationships,
      Map<String, String> nodeTypes,
      Set<String> visited)
      throws ArchimateJunctionValidationException {
    if (!visited.add(junctionId)) {
      return false;
    }
    var outgoing = new ArrayList<JunctionValidationRelationship>();
    for (JunctionValidationRelationship relationship : relationships) {
      if (relationship.source().equals(junctionId)
          && relationship.relationshipType().equals(relationshipType)
          && !isJunctionContainmentRelationship(relationship, nodeTypes)) {
        outgoing.add(relationship);
      }
    }
    boolean reachedAnElement = false;
    for (JunctionValidationRelationship relationship : outgoing) {
      String targetType = nodeTypes.get(relationship.target());
      if (targetType == null) {
        continue;
      }
      if (isRelationshipConnectorType(targetType)) {
        reachedAnElement |=
            validateJunctionReachableTargets(
                relationshipType,
                sourceType,
                relationship.target(),
                path,
                relationships,
                nodeTypes,
                visited);
        continue;
      }
      try {
        validateRelationshipEndpointTypes(relationshipType, sourceType, targetType, path);
      } catch (ArchimateTypeValidationException error) {
        throw new ArchimateJunctionValidationException(error.code(), error.path(), error.message());
      }
      reachedAnElement = true;
    }
    return reachedAnElement;
  }

  private static boolean isJunctionContainmentRelationship(
      JunctionValidationRelationship relationship, Map<String, String> nodeTypes) {
    if (!relationship.relationshipType().equals("Aggregation")
        && !relationship.relationshipType().equals("Composition")) {
      return false;
    }
    String sourceType = nodeTypes.get(relationship.source());
    String targetType = nodeTypes.get(relationship.target());
    if (sourceType == null || targetType == null) {
      return false;
    }
    // §5.5.1 allows a junction to be aggregated or composed *in* a plateau, grouping or location:
    // the container is the source. Drawn the other way round it is not containment, and exempting
    // it from the junction checks would strip an edge nothing else validates.
    return isRelationshipConnectorType(targetType) && isJunctionContainerType(sourceType);
  }

  private static boolean isJunctionContainerType(String nodeType) {
    return nodeType.equals("Plateau") || nodeType.equals("Grouping") || nodeType.equals("Location");
  }
}

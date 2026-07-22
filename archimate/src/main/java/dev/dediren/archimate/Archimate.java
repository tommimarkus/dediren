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

  private static final Set<RelationshipEndpointTriple> REJECTED_RELATIONSHIP_ENDPOINT_TRIPLES =
      Set.of(
          new RelationshipEndpointTriple(
              "ApplicationService", "Realization", "ApplicationComponent"),
          new RelationshipEndpointTriple("BusinessActor", "Triggering", "DataObject"),
          new RelationshipEndpointTriple("DataObject", "Serving", "ApplicationService"),
          new RelationshipEndpointTriple("ApplicationComponent", "Access", "ApplicationFunction"),
          new RelationshipEndpointTriple("BusinessObject", "Flow", "ApplicationComponent"));

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
   * Validates one relationship's endpoints against a <strong>deny list</strong>. This is not a
   * curated allow list: any (source, relationship, target) triple that is not among the five
   * explicitly rejected combinations passes. The polarity is deliberate — ArchiMate's legal
   * endpoint matrix is large and Dediren rejects only what is unambiguously wrong — but it is the
   * opposite of {@code uml}, which defaults to rejecting what it does not recognise. Do not read
   * this as curated legality.
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
    if (REJECTED_RELATIONSHIP_ENDPOINT_TRIPLES.contains(
        new RelationshipEndpointTriple(sourceType, relationshipType, targetType))) {
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
      validateJunctionReachableTargets(
          relationship.relationshipType(),
          sourceType,
          relationship.target(),
          nodePaths.getOrDefault(relationship.target(), "$"),
          relationships,
          nodeTypes,
          new TreeSet<>());
    }
  }

  private static void validateJunctionReachableTargets(
      String relationshipType,
      String sourceType,
      String junctionId,
      String path,
      List<JunctionValidationRelationship> relationships,
      Map<String, String> nodeTypes,
      Set<String> visited)
      throws ArchimateJunctionValidationException {
    if (!visited.add(junctionId)) {
      return;
    }
    var outgoing = new ArrayList<JunctionValidationRelationship>();
    for (JunctionValidationRelationship relationship : relationships) {
      if (relationship.source().equals(junctionId)
          && relationship.relationshipType().equals(relationshipType)
          && !isJunctionContainmentRelationship(relationship, nodeTypes)) {
        outgoing.add(relationship);
      }
    }
    for (JunctionValidationRelationship relationship : outgoing) {
      String targetType = nodeTypes.get(relationship.target());
      if (targetType == null) {
        continue;
      }
      if (isRelationshipConnectorType(targetType)) {
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
    }
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
    return (isRelationshipConnectorType(sourceType) && isJunctionContainerType(targetType))
        || (isRelationshipConnectorType(targetType) && isJunctionContainerType(sourceType));
  }

  private static boolean isJunctionContainerType(String nodeType) {
    return nodeType.equals("Plateau") || nodeType.equals("Grouping") || nodeType.equals("Location");
  }
}

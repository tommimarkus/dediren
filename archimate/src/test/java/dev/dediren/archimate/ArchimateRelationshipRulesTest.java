package dev.dediren.archimate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArchimateRelationshipRulesTest {

  @Test
  void relationshipConnectorTypesAreSupportedElementTypes() {
    assertThat(Archimate.elementTypes()).contains("AndJunction", "OrJunction");
  }

  @Test
  void acceptsCurrentArchimateOefFixtureRelationship() throws Exception {
    Archimate.validateRelationshipEndpointTypes(
        "Realization", "ApplicationComponent", "ApplicationService", "$.relationships[0]");
  }

  @Test
  void relationshipConnectorEndpointsAreAllowedForSupportedRelationshipTypes() throws Exception {
    Archimate.validateRelationshipEndpointTypes(
        "Flow", "ApplicationComponent", "AndJunction", "$.relationships[0]");
    Archimate.validateRelationshipEndpointTypes(
        "Flow", "AndJunction", "ApplicationService", "$.relationships[1]");
  }

  @Test
  void rejectsTypeValidButEndpointInvalidRelationship() {
    ArchimateTypeValidationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            ArchimateTypeValidationException.class,
            () ->
                Archimate.validateRelationshipEndpointTypes(
                    "Realization",
                    "ApplicationService",
                    "ApplicationComponent",
                    "$.relationships[0]"));

    assertThat(error.code()).isEqualTo("DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
    assertThat(error.path()).isEqualTo("$.relationships[0]");
    assertThat(error.message())
        .contains("ApplicationService", "Realization", "ApplicationComponent");
  }

  @Test
  void rejectsArchimateJunctionWithMixedRelationshipTypes() {
    ArchimateJunctionValidationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            ArchimateJunctionValidationException.class,
            () ->
                Archimate.validateJunctionRelationshipSemantics(
                    java.util.List.of(
                        new JunctionValidationNode("api", "ApplicationComponent", "$.nodes[0]"),
                        new JunctionValidationNode("junction", "AndJunction", "$.nodes[1]"),
                        new JunctionValidationNode("orders", "ApplicationService", "$.nodes[2]")),
                    java.util.List.of(
                        new JunctionValidationRelationship("Flow", "api", "junction"),
                        new JunctionValidationRelationship("Serving", "junction", "orders"))));

    assertThat(error.code()).isEqualTo("DEDIREN_ARCHIMATE_JUNCTION_RELATIONSHIP_MIXED");
    assertThat(error.message()).contains("junction", "Flow", "Serving");
  }

  @Test
  void rejectsJunctionWithOnlyOutgoingRelationships() {
    ArchimateJunctionValidationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            ArchimateJunctionValidationException.class,
            () ->
                Archimate.validateJunctionRelationshipSemantics(
                    java.util.List.of(
                        new JunctionValidationNode("junction", "AndJunction", "$.nodes[0]"),
                        new JunctionValidationNode("orders", "ApplicationService", "$.nodes[1]"),
                        new JunctionValidationNode("billing", "ApplicationService", "$.nodes[2]")),
                    java.util.List.of(
                        new JunctionValidationRelationship("Flow", "junction", "orders"),
                        new JunctionValidationRelationship("Flow", "junction", "billing"))));

    assertThat(error.code()).isEqualTo("DEDIREN_ARCHIMATE_JUNCTION_DIRECTION_INCOMPLETE");
    assertThat(error.path()).isEqualTo("$.nodes[0]");
    assertThat(error.message()).contains("junction");
  }

  @Test
  void acceptsJunctionContainedInGroupingWithReachableValidEndpoints() throws Exception {
    // A valid AndJunction routes a Realization from ApplicationComponent to ApplicationService and
    // is
    // visually contained in a Grouping via Composition. The containment relationship must be
    // skipped by
    // the junction semantics (not treated as a mixed or direction-incomplete junction
    // relationship), and
    // the reachable endpoint (ApplicationComponent -> ApplicationService) is a supported
    // Realization.
    Archimate.validateJunctionRelationshipSemantics(
        java.util.List.of(
            new JunctionValidationNode("api", "ApplicationComponent", "$.nodes[0]"),
            new JunctionValidationNode("junction", "AndJunction", "$.nodes[1]"),
            new JunctionValidationNode("orders", "ApplicationService", "$.nodes[2]"),
            new JunctionValidationNode("group", "Grouping", "$.nodes[3]")),
        java.util.List.of(
            new JunctionValidationRelationship("Realization", "api", "junction"),
            new JunctionValidationRelationship("Realization", "junction", "orders"),
            new JunctionValidationRelationship("Composition", "group", "junction")));
  }

  @Test
  void rejectsJunctionWhoseReachableEndpointTypeIsInvalid() {
    // The junction routes a Realization whose resolved endpoints (ApplicationService ->
    // ApplicationComponent)
    // are an unsupported direction; the reachable-targets check must surface that endpoint
    // violation.
    ArchimateJunctionValidationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            ArchimateJunctionValidationException.class,
            () ->
                Archimate.validateJunctionRelationshipSemantics(
                    java.util.List.of(
                        new JunctionValidationNode("svc", "ApplicationService", "$.nodes[0]"),
                        new JunctionValidationNode("junction", "AndJunction", "$.nodes[1]"),
                        new JunctionValidationNode("comp", "ApplicationComponent", "$.nodes[2]")),
                    java.util.List.of(
                        new JunctionValidationRelationship("Realization", "svc", "junction"),
                        new JunctionValidationRelationship("Realization", "junction", "comp"))));

    assertThat(error.code()).isEqualTo("DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
    assertThat(error.path()).isEqualTo("$.nodes[1]");
    assertThat(error.message())
        .contains("ApplicationService", "Realization", "ApplicationComponent");
  }

  @Test
  void rejectsUnsupportedElementType() {
    ArchimateTypeValidationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            ArchimateTypeValidationException.class,
            () -> Archimate.validateElementType("TechnologyNode", "$.nodes[0].type"));

    assertThat(error.code()).isEqualTo("DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
    assertThat(error.path()).isEqualTo("$.nodes[0].type");
    assertThat(error.message()).contains("TechnologyNode");
  }

  @Test
  void rejectsUnsupportedRelationshipType() {
    ArchimateTypeValidationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            ArchimateTypeValidationException.class,
            () -> Archimate.validateRelationshipType("ConnectsTo", "$.relationships[0].type"));

    assertThat(error.code()).isEqualTo("DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED");
    assertThat(error.path()).isEqualTo("$.relationships[0].type");
    assertThat(error.message()).contains("ConnectsTo");
  }

  // --- metamodel-derived endpoint legality (RelationshipLegality) ---------------------------

  @Test
  void acceptsRepresentativeLegalEndpoints() throws Exception {
    // §5 category rules: active->behavior assignment, service->behavior serving, behavior->passive
    // access, any->motivation influence, concrete->abstract realization, same-aspect composition,
    // active-structure composes its interface, behavior->behavior triggering/flow.
    assertAllowed("Assignment", "BusinessRole", "BusinessProcess");
    assertAllowed("Serving", "ApplicationService", "BusinessProcess");
    assertAllowed("Access", "ApplicationFunction", "DataObject");
    assertAllowed("Influence", "ApplicationComponent", "Requirement");
    assertAllowed("Realization", "Artifact", "ApplicationComponent");
    assertAllowed("Composition", "Node", "Device");
    assertAllowed("Composition", "ApplicationComponent", "ApplicationInterface");
    assertAllowed("Aggregation", "Product", "BusinessService"); // composite aggregates a service
    assertAllowed("Serving", "Driver", "Goal"); // motivation-to-motivation serving (§6.6)
    assertAllowed("Triggering", "BusinessProcess", "BusinessEvent");
    assertAllowed("Flow", "ApplicationFunction", "ApplicationFunction");
  }

  @Test
  void acceptsAssociationBetweenAnyEndpoints() throws Exception {
    // Association is the "unspecified relationship" — always legal, the escape hatch.
    assertAllowed("Association", "DataObject", "Goal");
    assertAllowed("Association", "Meaning", "CommunicationNetwork");
  }

  @Test
  void acceptsSpecificationDefinedCrossTypeSpecializations() throws Exception {
    // The two specializations the spec itself defines: Contract is-a BusinessObject (§8),
    // Constraint is-a Requirement (§6). Plus same-type specialization.
    assertAllowed("Specialization", "Constraint", "Requirement");
    assertAllowed("Specialization", "Contract", "BusinessObject");
    assertAllowed("Specialization", "BusinessProcess", "BusinessProcess");
  }

  @Test
  void acceptsGroupingAndLocationAsUniversalConnectors() throws Exception {
    // Grouping/Location connect to anything, even where the bare category rule would reject.
    assertAllowed("Serving", "Grouping", "DataObject");
    assertAllowed("Access", "DataObject", "Location");
  }

  @Test
  void rejectsAllFiveHistoricallyDeniedTriples() {
    // The endpoint violations the previous deny-list rejected must stay rejected (superset).
    assertRejected("Realization", "ApplicationService", "ApplicationComponent");
    assertRejected("Triggering", "BusinessActor", "DataObject");
    assertRejected("Serving", "DataObject", "ApplicationService");
    assertRejected("Access", "ApplicationComponent", "ApplicationFunction");
    assertRejected("Flow", "BusinessObject", "ApplicationComponent");
  }

  @Test
  void rejectsCategoryViolatingEndpoints() {
    // New teeth the deny-list missed, each a §5 category violation.
    assertRejected("Access", "BusinessProcess", "BusinessProcess"); // access target must be passive
    assertRejected(
        "Influence",
        "ApplicationComponent",
        "ApplicationComponent"); // influence -> motivation only
    assertRejected("Serving", "ApplicationService", "DataObject"); // never serve a passive object
    assertRejected(
        "Assignment", "DataObject", "BusinessProcess"); // passive cannot be an assignment source
    assertRejected(
        "Triggering", "BusinessProcess", "Goal"); // triggering is behavior/event, not motivation
    assertRejected(
        "Specialization", "BusinessProcess", "BusinessFunction"); // specialization is same concept
    assertRejected(
        "Composition", "Goal", "ApplicationComponent"); // composition stays within one aspect
    assertRejected(
        "Aggregation", "DataObject", "BusinessProcess"); // no passive -> behavior aggregation
    assertRejected(
        "Serving", "Driver", "ApplicationComponent"); // motivation serves only motivation
  }

  @Test
  void rejectsInvalidTypeNamesThroughEndpointValidation() {
    // The endpoint check must validate all three type names before applying legality; a caller that
    // reaches it with an unknown relationship or element type gets a diagnosed rejection.
    assertThat(
            codeOf(
                () ->
                    Archimate.validateRelationshipEndpointTypes(
                        "ConnectsTo", "ApplicationComponent", "ApplicationService", "$")))
        .isEqualTo("DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED");
    assertThat(
            codeOf(
                () ->
                    Archimate.validateRelationshipEndpointTypes(
                        "Serving", "NotAnElement", "ApplicationService", "$")))
        .isEqualTo("DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
    assertThat(
            codeOf(
                () ->
                    Archimate.validateRelationshipEndpointTypes(
                        "Serving", "ApplicationComponent", "NotAnElement", "$")))
        .isEqualTo("DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED");
  }

  @Test
  void rejectsJunctionWithOnlyIncomingRelationships() {
    // Mirror of the only-outgoing case: a junction needs at least one outgoing relationship too.
    ArchimateJunctionValidationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            ArchimateJunctionValidationException.class,
            () ->
                Archimate.validateJunctionRelationshipSemantics(
                    java.util.List.of(
                        new JunctionValidationNode("junction", "AndJunction", "$.nodes[0]"),
                        new JunctionValidationNode("orders", "ApplicationComponent", "$.nodes[1]"),
                        new JunctionValidationNode(
                            "billing", "ApplicationComponent", "$.nodes[2]")),
                    java.util.List.of(
                        new JunctionValidationRelationship("Flow", "orders", "junction"),
                        new JunctionValidationRelationship("Flow", "billing", "junction"))));

    assertThat(error.code()).isEqualTo("DEDIREN_ARCHIMATE_JUNCTION_DIRECTION_INCOMPLETE");
  }

  @Test
  void rejectsInvalidReachableEndpointAcrossChainedJunctions() {
    // Two chained junctions: the invalid resolved endpoint (ApplicationService -> Application
    // Component) is only reachable by recursing through both junctions.
    ArchimateJunctionValidationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            ArchimateJunctionValidationException.class,
            () ->
                Archimate.validateJunctionRelationshipSemantics(
                    java.util.List.of(
                        new JunctionValidationNode("svc", "ApplicationService", "$.nodes[0]"),
                        new JunctionValidationNode("j1", "AndJunction", "$.nodes[1]"),
                        new JunctionValidationNode("j2", "AndJunction", "$.nodes[2]"),
                        new JunctionValidationNode("comp", "ApplicationComponent", "$.nodes[3]")),
                    java.util.List.of(
                        new JunctionValidationRelationship("Realization", "svc", "j1"),
                        new JunctionValidationRelationship("Realization", "j1", "j2"),
                        new JunctionValidationRelationship("Realization", "j2", "comp"))));

    assertThat(error.code()).isEqualTo("DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
    assertThat(error.message())
        .contains("ApplicationService", "Realization", "ApplicationComponent");
  }

  @Test
  void acceptsJunctionAggregatedInPlateauAndLocation() throws Exception {
    // Containment via Aggregation (not only Composition) and into Plateau/Location (not only
    // Grouping) must be skipped by the junction semantics, leaving a valid Realization path.
    Archimate.validateJunctionRelationshipSemantics(
        java.util.List.of(
            new JunctionValidationNode("api", "ApplicationComponent", "$.nodes[0]"),
            new JunctionValidationNode("junction", "AndJunction", "$.nodes[1]"),
            new JunctionValidationNode("orders", "ApplicationService", "$.nodes[2]"),
            new JunctionValidationNode("plateau", "Plateau", "$.nodes[3]"),
            new JunctionValidationNode("location", "Location", "$.nodes[4]")),
        java.util.List.of(
            new JunctionValidationRelationship("Realization", "api", "junction"),
            new JunctionValidationRelationship("Realization", "junction", "orders"),
            new JunctionValidationRelationship("Aggregation", "plateau", "junction"),
            new JunctionValidationRelationship("Composition", "location", "junction")));
  }

  @Test
  void acceptsJunctionAsContainmentSourceInGrouping() throws Exception {
    // The reverse containment orientation — the junction as the composition source into a Grouping
    // — must also be recognized and skipped.
    Archimate.validateJunctionRelationshipSemantics(
        java.util.List.of(
            new JunctionValidationNode("api", "ApplicationComponent", "$.nodes[0]"),
            new JunctionValidationNode("junction", "AndJunction", "$.nodes[1]"),
            new JunctionValidationNode("orders", "ApplicationService", "$.nodes[2]"),
            new JunctionValidationNode("group", "Grouping", "$.nodes[3]")),
        java.util.List.of(
            new JunctionValidationRelationship("Realization", "api", "junction"),
            new JunctionValidationRelationship("Realization", "junction", "orders"),
            new JunctionValidationRelationship("Composition", "junction", "group")));
  }

  @Test
  void junctionSemanticsIgnoreRelationshipsNotIncidentToTheJunction() throws Exception {
    // A valid junction plus an unrelated relationship between two other nodes must still validate;
    // the unrelated edge must not be folded into the junction's incident set.
    Archimate.validateJunctionRelationshipSemantics(
        java.util.List.of(
            new JunctionValidationNode("api", "ApplicationComponent", "$.nodes[0]"),
            new JunctionValidationNode("junction", "AndJunction", "$.nodes[1]"),
            new JunctionValidationNode("orders", "ApplicationService", "$.nodes[2]"),
            new JunctionValidationNode("a", "BusinessProcess", "$.nodes[3]"),
            new JunctionValidationNode("b", "BusinessEvent", "$.nodes[4]")),
        java.util.List.of(
            new JunctionValidationRelationship("Realization", "api", "junction"),
            new JunctionValidationRelationship("Realization", "junction", "orders"),
            new JunctionValidationRelationship("Triggering", "a", "b")));
  }

  @Test
  void rejectsJunctionMixingContainmentTypeBetweenConcreteEndpoints() {
    // An Aggregation whose endpoints are not connector+container is a real relationship, not
    // containment — so a junction connecting it alongside another type is a mixed junction. This
    // pins the containment-detection endpoint checks (connector side and container-type side).
    ArchimateJunctionValidationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            ArchimateJunctionValidationException.class,
            () ->
                Archimate.validateJunctionRelationshipSemantics(
                    java.util.List.of(
                        new JunctionValidationNode("api", "ApplicationComponent", "$.nodes[0]"),
                        new JunctionValidationNode("junction", "AndJunction", "$.nodes[1]"),
                        new JunctionValidationNode("orders", "ApplicationService", "$.nodes[2]"),
                        new JunctionValidationNode("data", "DataObject", "$.nodes[3]")),
                    java.util.List.of(
                        new JunctionValidationRelationship("Realization", "api", "junction"),
                        new JunctionValidationRelationship("Realization", "junction", "orders"),
                        new JunctionValidationRelationship("Aggregation", "junction", "data"))));

    assertThat(error.code()).isEqualTo("DEDIREN_ARCHIMATE_JUNCTION_RELATIONSHIP_MIXED");
  }

  private static String codeOf(org.junit.jupiter.api.function.Executable action) {
    ArchimateTypeValidationException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            ArchimateTypeValidationException.class, action);
    return error.code();
  }

  private static void assertAllowed(String relationship, String source, String target)
      throws Exception {
    Archimate.validateRelationshipEndpointTypes(relationship, source, target, "$.relationships[0]");
  }

  private static void assertRejected(String relationship, String source, String target) {
    org.junit.jupiter.api.Assertions.assertThrows(
        ArchimateTypeValidationException.class,
        () ->
            Archimate.validateRelationshipEndpointTypes(
                relationship, source, target, "$.relationships[0]"));
  }
}

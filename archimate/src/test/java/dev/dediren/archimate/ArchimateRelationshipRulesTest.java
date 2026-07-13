package dev.dediren.archimate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchimateRelationshipRulesTest {

  // Example endpoint triples, demoted from production (2026-07-13): they never took part in
  // validation — enforcement is the five-triple deny list in Archimate — and an accessor nothing
  // called made them look like curated legality. Kept as data so these tests still prove the
  // examples reference real vocabulary.
  private static final List<RelationshipEndpointTriple> CURATED_RELATIONSHIP_ENDPOINT_TRIPLES =
      List.of(
          new RelationshipEndpointTriple("Grouping", "Composition", "ApplicationComponent"),
          new RelationshipEndpointTriple("Grouping", "Aggregation", "ApplicationService"),
          new RelationshipEndpointTriple("BusinessRole", "Assignment", "BusinessProcess"),
          new RelationshipEndpointTriple(
              "ApplicationComponent", "Realization", "ApplicationService"),
          new RelationshipEndpointTriple(
              "ApplicationComponent", "Specialization", "ApplicationComponent"),
          new RelationshipEndpointTriple("ApplicationService", "Serving", "ApplicationComponent"),
          new RelationshipEndpointTriple("ApplicationComponent", "Serving", "BusinessActor"),
          new RelationshipEndpointTriple("ApplicationFunction", "Access", "DataObject"),
          new RelationshipEndpointTriple("ApplicationService", "Access", "DataObject"),
          new RelationshipEndpointTriple("ApplicationComponent", "Access", "DataObject"),
          new RelationshipEndpointTriple("Goal", "Influence", "Requirement"),
          new RelationshipEndpointTriple("ApplicationComponent", "Flow", "ApplicationService"),
          new RelationshipEndpointTriple("ApplicationService", "Flow", "ApplicationService"),
          new RelationshipEndpointTriple("BusinessProcess", "Triggering", "BusinessProcess"),
          new RelationshipEndpointTriple(
              "ApplicationService", "Triggering", "ApplicationComponent"),
          new RelationshipEndpointTriple("BusinessActor", "Association", "DataObject"));

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
  void curatedRelationshipTriplesOnlyReferenceSupportedNames() {
    assertThat(CURATED_RELATIONSHIP_ENDPOINT_TRIPLES).hasSizeLessThanOrEqualTo(64);
    for (RelationshipEndpointTriple triple : CURATED_RELATIONSHIP_ENDPOINT_TRIPLES) {
      assertThat(Archimate.elementTypes()).contains(triple.sourceType());
      assertThat(Archimate.relationshipTypes()).contains(triple.relationshipType());
      assertThat(Archimate.elementTypes()).contains(triple.targetType());
    }
  }

  @Test
  void curatedRelationshipTriplesAreUnique() {
    var seen = new HashSet<RelationshipEndpointTriple>();
    for (RelationshipEndpointTriple triple : CURATED_RELATIONSHIP_ENDPOINT_TRIPLES) {
      assertThat(seen.add(triple)).isTrue();
    }
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
}

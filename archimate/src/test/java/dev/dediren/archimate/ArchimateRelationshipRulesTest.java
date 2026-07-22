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
}

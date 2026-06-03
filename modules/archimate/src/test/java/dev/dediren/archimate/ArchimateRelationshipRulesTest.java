package dev.dediren.archimate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class ArchimateRelationshipRulesTest {
    @Test
    void relationshipConnectorTypesAreSupportedElementTypes() {
        assertThat(Archimate.elementTypes()).contains("AndJunction", "OrJunction");
    }

    @Test
    void acceptsCurrentArchimateOefFixtureRelationship() throws Exception {
        Archimate.validateRelationshipEndpointTypes(
                "Realization",
                "ApplicationComponent",
                "ApplicationService",
                "$.relationships[0]");
    }

    @Test
    void relationshipConnectorEndpointsAreAllowedForSupportedRelationshipTypes() throws Exception {
        Archimate.validateRelationshipEndpointTypes(
                "Flow",
                "ApplicationComponent",
                "AndJunction",
                "$.relationships[0]");
        Archimate.validateRelationshipEndpointTypes(
                "Flow",
                "AndJunction",
                "ApplicationService",
                "$.relationships[1]");
    }

    @Test
    void rejectsTypeValidButEndpointInvalidRelationship() {
        ArchimateTypeValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                ArchimateTypeValidationException.class,
                () -> Archimate.validateRelationshipEndpointTypes(
                        "Realization",
                        "ApplicationService",
                        "ApplicationComponent",
                        "$.relationships[0]"));

        assertThat(error.code()).isEqualTo("DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED");
        assertThat(error.path()).isEqualTo("$.relationships[0]");
        assertThat(error.message()).contains("ApplicationService", "Realization", "ApplicationComponent");
    }

    @Test
    void curatedRelationshipTriplesOnlyReferenceSupportedNames() {
        assertThat(Archimate.relationshipEndpointTriples()).hasSizeLessThanOrEqualTo(64);
        for (RelationshipEndpointTriple triple : Archimate.relationshipEndpointTriples()) {
            assertThat(Archimate.elementTypes()).contains(triple.sourceType());
            assertThat(Archimate.relationshipTypes()).contains(triple.relationshipType());
            assertThat(Archimate.elementTypes()).contains(triple.targetType());
        }
    }

    @Test
    void curatedRelationshipTriplesAreUnique() {
        var seen = new HashSet<RelationshipEndpointTriple>();
        for (RelationshipEndpointTriple triple : Archimate.relationshipEndpointTriples()) {
            assertThat(seen.add(triple)).isTrue();
        }
    }

    @Test
    void rejectsArchimateJunctionWithMixedRelationshipTypes() {
        ArchimateJunctionValidationException error = org.junit.jupiter.api.Assertions.assertThrows(
                ArchimateJunctionValidationException.class,
                () -> Archimate.validateJunctionRelationshipSemantics(
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
}

package dev.dediren.semantics.graph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.semantics.uml.UmlNotationSemantics;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The graph profile re-declares the Lifeline/Interaction layout-role mapping the UML profile owns
 * (a recorded byte-stability decision — see the comment on {@code
 * GraphNotationSemantics.layoutRole}). {@code LayoutNodeRole}'s javadoc warns that a drifted role
 * string "silently stopped matching" with no diagnostic, so the duplicate is pinned here: the graph
 * profile's mapping must stay a subset of the UML profile's.
 */
class LayoutRoleAgreementTest {
  @Test
  void graphProfileRoleMappingIsASubsetOfTheUmlProfiles() {
    var graph = new GraphNotationSemantics();
    var uml = new UmlNotationSemantics();
    for (String sourceType : List.of("Lifeline", "Interaction")) {
      assertThat(graph.layoutRole(sourceType))
          .as("layout role for " + sourceType + " must match the UML profile's mapping")
          .isNotNull()
          .isEqualTo(uml.layoutRole(sourceType));
    }
  }
}

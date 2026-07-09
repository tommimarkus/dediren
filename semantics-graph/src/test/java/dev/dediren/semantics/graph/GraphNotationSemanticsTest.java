package dev.dediren.semantics.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.dediren.contracts.source.SourceNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphNotationSemanticsTest {
  private final GraphNotationSemantics notation = new GraphNotationSemantics();

  @Test
  void layoutRoleIsNullExceptForBaseLifelineAndInteraction() {
    assertThat(notation.layoutRole("generic.actor")).isNull();
  }

  @Test
  void layoutRolePreservesProfileIndependentLifelineAndInteraction() {
    assertThat(notation.layoutRole("Lifeline")).isEqualTo("lifeline");
    assertThat(notation.layoutRole("Interaction")).isEqualTo("interaction");
    assertThat(notation.layoutRole("Plain")).isNull();
  }

  @Test
  void sizingHintsAreBaseDefaults() {
    SourceNode node = new SourceNode("n", "generic.component", "N", Map.of());
    assertThat(notation.widthHint(node)).isEqualTo(160.0);
    assertThat(notation.heightHint(node)).isEqualTo(80.0);
  }

  @Test
  void filtersNothingHasNoConstraintsAndNoRenderSelectors() {
    SourceNode node = new SourceNode("n", "generic.component", "N", Map.of());
    assertThat(notation.isSourceOnlyNode(null, node)).isFalse();
    assertThat(notation.layoutConstraints(null, null)).isEmpty();
    assertThat(notation.nodeRenderProperties(node)).isNull();
    assertThat(notation.edgeRenderProperties(null)).isNull();
  }

  @Test
  void validateIsANoOp() {
    assertThatCode(() -> notation.validate(null, null)).doesNotThrowAnyException();
  }
}

package dev.dediren.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NotationSemanticsTest {
  /** A notation that contributes nothing — the shape the base/plain profile will use. */
  private static final NotationSemantics NEUTRAL =
      new NotationSemantics() {
        @Override
        public void validate(SourceDocument source, GenericGraphPluginData pluginData) {}

        @Override
        public String layoutRole(String sourceType) {
          return null;
        }

        @Override
        public double widthHint(SourceNode node) {
          return 160.0;
        }

        @Override
        public double heightHint(SourceNode node) {
          return 80.0;
        }

        @Override
        public boolean isSourceOnlyNode(GenericGraphView view, SourceNode node) {
          return false;
        }

        @Override
        public List<LayoutConstraint> layoutConstraints(
            SourceDocument source, GenericGraphView view) {
          return List.of();
        }

        @Override
        public JsonNode nodeRenderProperties(SourceNode node) {
          return null;
        }

        @Override
        public JsonNode edgeRenderProperties(SourceRelationship relationship) {
          return null;
        }
      };

  @Test
  void neutralNotationContributesNothing() {
    assertThat(NEUTRAL.layoutRole("Lifeline")).isNull();
    assertThat(NEUTRAL.widthHint(null)).isEqualTo(160.0);
    assertThat(NEUTRAL.heightHint(null)).isEqualTo(80.0);
    assertThat(NEUTRAL.isSourceOnlyNode(null, null)).isFalse();
    assertThat(NEUTRAL.layoutConstraints(null, null)).isEmpty();
    assertThat(NEUTRAL.nodeRenderProperties(null)).isNull();
    assertThat(NEUTRAL.edgeRenderProperties(null)).isNull();
    assertThatCode(() -> NEUTRAL.validate(null, null)).doesNotThrowAnyException();
  }
}

package dev.dediren.plugins.asciirender;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.ir.LaidOutScene;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Identity test for the ASCII render engine: id and artifact kind. */
class AsciiEngineIdentityTest {
  private final AsciiRenderEngine engine = new AsciiRenderEngine();

  @Test
  void enginePublishesTheAsciiId() {
    assertThat(engine.id()).isEqualTo("ascii");
  }

  @Test
  void renderReturnsResultWithCorrectSchemaVersion() {
    LaidOutScene scene =
        new LaidOutScene("v", java.util.List.of(), java.util.List.of(), java.util.List.of(),
            java.util.List.of());
    ObjectNode policy = JsonNodeFactory.instance.objectNode();
    var result = engine.render(scene, policy, null);
    assertThat(result.result().renderResultSchemaVersion())
        .isEqualTo(ContractVersions.RENDER_RESULT_SCHEMA_VERSION);
  }

  @Test
  void renderReturnsExactlyOneTextArtifact() {
    LaidOutScene scene =
        new LaidOutScene("v", java.util.List.of(), java.util.List.of(), java.util.List.of(),
            java.util.List.of());
    ObjectNode policy = JsonNodeFactory.instance.objectNode();
    var result = engine.render(scene, policy, null);
    assertThat(result.result().artifacts()).hasSize(1);
    assertThat(result.result().artifacts().get(0).kind()).isEqualTo("text");
  }
}

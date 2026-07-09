package dev.dediren.semantics.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticsRouterEngineTest {
  private final SemanticsRouterEngine engine =
      new SemanticsRouterEngine(
          Map.of(GenericGraphSemanticProfile.GENERIC_GRAPH, new GraphNotationSemantics()));

  @Test
  void idIsGenericGraph() {
    assertThat(engine.id()).isEqualTo("generic-graph");
  }

  @Test
  void validateWithoutProfileIsProfileRequired() {
    assertThatThrownBy(() -> engine.validate(source("fixtures/source/valid-basic.json"), null))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_SEMANTIC_PROFILE_REQUIRED");
            });
  }

  @Test
  void validateWithUnsupportedProfileIsRejected() {
    assertThatThrownBy(() -> engine.validate(source("fixtures/source/valid-basic.json"), "bogus"))
        .isInstanceOfSatisfying(
            EngineException.class,
            error -> {
              assertThat(error.exitCode()).isEqualTo(3);
              assertThat(error.diagnostics().getFirst().code())
                  .isEqualTo("DEDIREN_SEMANTIC_PROFILE_UNSUPPORTED");
              assertThat(error.diagnostics().getFirst().path()).isEqualTo("profile");
            });
  }

  @Test
  void projectsBaseProfileLayoutAndRenderMetadata() throws Exception {
    SourceDocument source = source("fixtures/source/valid-basic.json");

    EngineResult<LayoutRequest> layout = engine.projectLayoutRequest(source, "main");
    EngineResult<RenderMetadata> metadata = engine.projectRenderMetadata(source, "main");

    assertThat(layout.value().viewId()).isEqualTo("main");
    assertThat(layout.value().nodes()).hasSize(2);
    assertThat(layout.value().edges()).hasSize(1);
    assertThat(metadata.value().semanticProfile()).isEqualTo("generic-graph");
    assertThat(metadata.value().nodes()).containsKeys("client", "api");
  }

  @Test
  void constructorRejectsNullNotationMap() {
    assertThatThrownBy(() -> new SemanticsRouterEngine(null))
        .isInstanceOf(NullPointerException.class);
  }

  private static SourceDocument source(String fixturePath) throws Exception {
    return JsonSupport.objectMapper()
        .readValue(Files.readString(workspaceRoot().resolve(fixturePath)), SourceDocument.class);
  }

  private static Path workspaceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("schemas/model.schema.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from user.dir");
  }
}

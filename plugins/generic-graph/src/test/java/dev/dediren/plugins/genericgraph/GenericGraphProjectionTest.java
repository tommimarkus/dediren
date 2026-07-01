package dev.dediren.plugins.genericgraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GenericGraphProjectionTest {
  @Test
  void projectionBuildsLayoutRequestAndRenderMetadataForView() throws Exception {
    SourceDocument source = source("fixtures/source/valid-basic.json");
    GenericGraphPluginData pluginData = pluginData(source);
    GenericGraphView view = pluginData.views().getFirst();
    String profile = GenericGraphProjection.sourceSemanticProfile(pluginData);

    LayoutRequest layout = GenericGraphProjection.projectLayoutRequest(source, view, profile);
    RenderMetadata metadata = GenericGraphProjection.projectRenderMetadata(source, view, profile);

    assertThat(layout.viewId()).isEqualTo(view.id());
    assertThat(layout.nodes()).hasSize(2);
    assertThat(layout.edges()).hasSize(1);
    assertThat(metadata.semanticProfile()).isEqualTo("generic-graph");
    assertThat(metadata.nodes()).containsKeys("client", "api");
  }

  private static SourceDocument source(String fixturePath) throws Exception {
    return JsonSupport.objectMapper()
        .readValue(Files.readString(workspaceRoot().resolve(fixturePath)), SourceDocument.class);
  }

  private static GenericGraphPluginData pluginData(SourceDocument source) throws Exception {
    JsonNode pluginValue = source.plugins().get("generic-graph");
    return JsonSupport.objectMapper().treeToValue(pluginValue, GenericGraphPluginData.class);
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

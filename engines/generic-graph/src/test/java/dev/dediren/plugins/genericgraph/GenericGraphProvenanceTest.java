package dev.dediren.plugins.genericgraph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class GenericGraphProvenanceTest {
  @Test
  void projectionStampsSourcePointerBySourceIndex() throws Exception {
    // Reuses the same fixture-loading pattern as GenericGraphProjectionTest: no
    // module-local ProjectionFixtures helper exists, so the source document and view are
    // built inline the same way.
    SourceDocument source = source("fixtures/source/valid-basic.json");
    GenericGraphPluginData pluginData = pluginData(source);
    GenericGraphView view = pluginData.views().getFirst();
    String profile = GenericGraphProjection.sourceSemanticProfile(pluginData);

    LayoutRequest request = GenericGraphProjection.projectLayoutRequest(source, view, profile);

    // Every projected node/edge carries a JSON-Pointer into the source arrays.
    assertThat(request.nodes())
        .allSatisfy(n -> assertThat(n.sourcePointer()).startsWith("/nodes/"));
    assertThat(request.edges())
        .allSatisfy(e -> assertThat(e.sourcePointer()).startsWith("/relationships/"));
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

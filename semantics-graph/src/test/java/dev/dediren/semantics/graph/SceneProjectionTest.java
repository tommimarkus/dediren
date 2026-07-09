package dev.dediren.semantics.graph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.ir.LayoutRequestMapper;
import dev.dediren.ir.SceneGraph;
import dev.dediren.semantics.uml.UmlNotationSemantics;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SceneProjectionTest {
  @Test
  void projectionBuildsLayoutRequestAndRenderMetadataForView() throws Exception {
    SourceDocument source = source("fixtures/source/valid-basic.json");
    GenericGraphPluginData pluginData = pluginData(source);
    GenericGraphView view = pluginData.views().getFirst();

    SceneGraph scene = SceneProjection.projectScene(source, view, new GraphNotationSemantics());
    LayoutRequest layout = LayoutRequestMapper.toRequest(scene);
    RenderMetadata metadata =
        SceneProjection.projectRenderMetadata(source, view, new GraphNotationSemantics());

    assertThat(layout.viewId()).isEqualTo(view.id());
    assertThat(layout.nodes()).hasSize(2);
    assertThat(layout.edges()).hasSize(1);
    assertThat(metadata.semanticProfile()).isEqualTo("generic-graph");
    assertThat(metadata.nodes()).containsKeys("client", "api");
  }

  @Test
  void projectSceneCarriesConstraintsAndGroups() throws Exception {
    SourceDocument source = source("fixtures/source/valid-uml-sequence-basic.json");
    GenericGraphPluginData pluginData = pluginData(source);
    GenericGraphView umlSequenceView =
        pluginData.views().stream()
            .filter(candidate -> candidate.id().equals("sequence-view"))
            .findFirst()
            .orElseThrow();
    UmlNotationSemantics umlNotation = new UmlNotationSemantics();

    SceneGraph scene = SceneProjection.projectScene(source, umlSequenceView, umlNotation);

    assertThat(scene.viewId()).isEqualTo(umlSequenceView.id());
    assertThat(scene.nodes())
        .extracting(dev.dediren.ir.SceneNode::id)
        .containsExactly("interaction-place-order", "customer", "service");
    assertThat(scene.edges())
        .extracting(dev.dediren.ir.SceneEdge::id)
        .containsExactly("m1", "m2", "m3");
    assertThat(scene.groups()).isEmpty();
    assertThat(scene.constraints())
        .extracting(LayoutConstraint::kind)
        .contains("uml.sequence.lifeline-order", "uml.sequence.message-order");
    assertThat(scene.preferences()).isEqualTo(umlSequenceView.layoutPreferences());

    // The projection owns the whole SceneGraph directly now: mapping it to a LayoutRequest must
    // carry the exact same nodes/edges/constraints/preferences (the CLI-edge byte-stability oracle
    // for `dediren project --target layout-request`).
    LayoutRequest mapped = LayoutRequestMapper.toRequest(scene);
    assertThat(mapped.viewId()).isEqualTo(scene.viewId());
    assertThat(mapped.nodes()).hasSameSizeAs(scene.nodes());
    assertThat(mapped.edges()).hasSameSizeAs(scene.edges());
    assertThat(mapped.constraints()).isEqualTo(scene.constraints());
    assertThat(mapped.layoutPreferences()).isEqualTo(scene.preferences());
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

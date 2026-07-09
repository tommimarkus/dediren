package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.LayoutRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutRequestMapperTest {
  @Test
  void mapsSceneNodesAndStampsProvenanceAndVersion() {
    SceneGraph graph =
        new SceneGraph(
            "view-1",
            List.of(
                new SceneNode(
                    "n1", "N1", SourcePointers.node(2), 10.0, 10.0, "lifeline", null, null)),
            List.of(
                new SceneEdge("e1", "n1", "n2", "", SourcePointers.relationship(0), "flow", null)),
            List.of(),
            null);

    LayoutRequest request = LayoutRequestMapper.toRequest(graph);

    assertThat(request.layoutRequestSchemaVersion())
        .isEqualTo(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);
    assertThat(request.viewId()).isEqualTo("view-1");
    assertThat(request.nodes().get(0).sourcePointer()).isEqualTo("/nodes/2");
    assertThat(request.nodes().get(0).sourceId()).isEqualTo("n1");
    assertThat(request.nodes().get(0).role()).isEqualTo("lifeline");
    assertThat(request.edges().get(0).sourcePointer()).isEqualTo("/relationships/0");
    assertThat(request.edges().get(0).sourceId()).isEqualTo("e1");
  }
}

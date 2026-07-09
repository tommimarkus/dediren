package dev.dediren.plugins.elklayout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElkLayoutProvenanceTest {
  @Test
  void layoutCarriesSourcePointerFromRequestToResult() {
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v1",
            "view-1",
            List.of(
                new LayoutNode("a", "A", "a", 40.0, 30.0, null, null, null, "/nodes/0"),
                new LayoutNode("b", "B", "b", 40.0, 30.0, null, null, null, "/nodes/1")),
            List.of(new LayoutEdge("e1", "a", "b", "", "e1", "flow", null, "/relationships/0")),
            List.of(),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);

    assertThat(result.nodes()).allSatisfy(n -> assertThat(n.sourcePointer()).startsWith("/nodes/"));
    assertThat(result.edges().get(0).sourcePointer()).isEqualTo("/relationships/0");
  }
}

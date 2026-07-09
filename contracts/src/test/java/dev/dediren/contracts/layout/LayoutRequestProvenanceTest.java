package dev.dediren.contracts.layout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;

class LayoutRequestProvenanceTest {
  @Test
  void schemaVersionIsV2() {
    assertThat(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION)
        .isEqualTo("layout-request.schema.v2");
  }

  @Test
  void nodeSourcePointerRoundTripsAsSnakeCase() throws Exception {
    LayoutNode node = new LayoutNode("n1", "N1", "n1", 10.0, 10.0, null, null, null, "/nodes/0");
    String json = JsonSupport.writeValueAsString(node);
    assertThat(json).contains("\"source_pointer\":\"/nodes/0\"");
    assertThat(JsonSupport.readValue(json, LayoutNode.class).sourcePointer()).isEqualTo("/nodes/0");
  }

  @Test
  void edgeSourcePointerRoundTrips() throws Exception {
    LayoutEdge edge = new LayoutEdge("e1", "n1", "n2", "", "e1", null, null, "/relationships/0");
    assertThat(
            JsonSupport.readValue(JsonSupport.writeValueAsString(edge), LayoutEdge.class)
                .sourcePointer())
        .isEqualTo("/relationships/0");
  }
}

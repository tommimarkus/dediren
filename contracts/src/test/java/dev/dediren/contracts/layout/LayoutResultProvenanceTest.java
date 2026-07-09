package dev.dediren.contracts.layout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;

class LayoutResultProvenanceTest {
  @Test
  void schemaVersionIsV2() {
    assertThat(ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION).isEqualTo("layout-result.schema.v2");
  }

  @Test
  void laidOutNodeSourcePointerRoundTrips() throws Exception {
    LaidOutNode node = new LaidOutNode("n1", "n1", "p1", 0, 0, 10, 10, "N1", null, "/nodes/0");
    String json = JsonSupport.writeValueAsString(node);
    assertThat(json).contains("\"source_pointer\":\"/nodes/0\"");
    assertThat(JsonSupport.readValue(json, LaidOutNode.class).sourcePointer())
        .isEqualTo("/nodes/0");
  }
}

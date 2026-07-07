package dev.dediren.contracts.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;

class RenderArtifactTest {
  @Test
  void textArtifactOmitsEncoding() throws Exception {
    String json =
        JsonSupport.objectMapper().writeValueAsString(new RenderArtifact("svg", "<svg/>"));
    assertThat(json).contains("\"artifact_kind\":\"svg\"");
    assertThat(json).doesNotContain("encoding");
  }
}

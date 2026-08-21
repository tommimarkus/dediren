package dev.dediren.contracts.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;

class RenderArtifactTest {
  @Test
  void textArtifactOmitsEncoding() throws Exception {
    String json =
        JsonSupport.objectMapper().writeValueAsString(new RenderArtifact("svg+xml", "<svg/>"));
    assertThat(json).contains("\"artifact_kind\":\"svg+xml\"");
    assertThat(json).doesNotContain("encoding");
  }
}

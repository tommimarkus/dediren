package dev.dediren.contracts.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;

class RenderArtifactTest {
    @Test
    void textArtifactOmitsEncoding() throws Exception {
        String json = JsonSupport.objectMapper().writeValueAsString(new RenderArtifact("svg", "<svg/>"));
        assertThat(json).contains("\"artifact_kind\":\"svg\"");
        assertThat(json).doesNotContain("encoding");
    }

    @Test
    void pngArtifactCarriesBase64Encoding() throws Exception {
        RenderArtifact png = new RenderArtifact("png", "aGVsbG8=", "base64");
        String json = JsonSupport.objectMapper().writeValueAsString(png);
        assertThat(json).contains("\"artifact_kind\":\"png\"");
        assertThat(json).contains("\"encoding\":\"base64\"");
        RenderArtifact round = JsonSupport.objectMapper().readValue(json, RenderArtifact.class);
        assertThat(round.encoding()).isEqualTo("base64");
        assertThat(round.content()).isEqualTo("aGVsbG8=");
    }
}

package dev.dediren.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ProvenanceTest {

  private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model/>";
  private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>";

  private static String payload(String viewId) {
    return Provenance.payload(
        "model.schema.v1", "deadbeef", viewId, "oef_policy_sha256", "cafef00d", "2026.07.25");
  }

  @Test
  void roundTripsAStampThroughXmlAndSvg() {
    String payload = payload("main");

    for (String stamped :
        new String[] {Provenance.stampXml(XML, payload), Provenance.stampSvg(SVG, payload)}) {
      Optional<JsonNode> extracted = Provenance.extract(stamped);
      assertThat(extracted).isPresent();
      JsonNode stamp = extracted.get();
      assertThat(stamp.path("model_sha256").asText()).isEqualTo("deadbeef");
      assertThat(stamp.path("view_id").asText()).isEqualTo("main");
      assertThat(stamp.path("oef_policy_sha256").asText()).isEqualTo("cafef00d");
      assertThat(stamp.path("dediren_version").asText()).isEqualTo("2026.07.25");
    }
  }

  @Test
  void extractFindsTheObjectBoundaryByTokenizationNotBraceCounting() {
    // A brace inside a string value must not truncate the payload: extraction has to tokenize the
    // JSON, not scan raw '{'/'}' depth. View ids cannot carry braces today, so this only fails as a
    // latent fragility — a value that ever does would be silently dropped.
    String payload = payload("closes}early");

    for (String stamped :
        new String[] {Provenance.stampXml(XML, payload), Provenance.stampSvg(SVG, payload)}) {
      Optional<JsonNode> extracted = Provenance.extract(stamped);
      assertThat(extracted).isPresent();
      assertThat(extracted.get().path("view_id").asText()).isEqualTo("closes}early");
    }
  }
}

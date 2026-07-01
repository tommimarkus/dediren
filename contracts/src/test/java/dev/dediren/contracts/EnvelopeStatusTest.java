package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;

class EnvelopeStatusTest {
  @Test
  void eachConstantExposesItsWireString() {
    assertThat(EnvelopeStatus.OK.wire()).isEqualTo("ok");
    assertThat(EnvelopeStatus.WARNING.wire()).isEqualTo("warning");
    assertThat(EnvelopeStatus.ERROR.wire()).isEqualTo("error");
  }

  @Test
  void serializesAndDeserializesThroughItsWireString() throws Exception {
    var mapper = JsonSupport.objectMapper();
    for (EnvelopeStatus status : EnvelopeStatus.values()) {
      String json = mapper.writeValueAsString(status);
      assertThat(json).isEqualTo("\"" + status.wire() + "\"");
      assertThat(mapper.readValue(json, EnvelopeStatus.class)).isEqualTo(status);
    }
  }
}

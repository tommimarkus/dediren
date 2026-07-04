package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.json.JsonSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.exc.ValueInstantiationException;

class CommandEnvelopeTest {
  @Test
  void commandEnvelopeRoundTripsWithDiagnosticWireNames() throws Exception {
    var mapper = JsonSupport.objectMapper();
    var envelope =
        new CommandEnvelope<>(
            ContractVersions.ENVELOPE_SCHEMA_VERSION,
            EnvelopeStatus.OK,
            mapper.readTree(
                """
                        {"kind":"sample"}
                        """),
            List.of(
                new Diagnostic("DEDIREN_TEST", DiagnosticSeverity.INFO, "sample", "$.nodes[0]")));

    JsonNode encoded = mapper.valueToTree(envelope);

    assertThat(encoded.get("envelope_schema_version").asText()).isEqualTo("envelope.schema.v1");
    assertThat(encoded.get("status").asText()).isEqualTo("ok");
    assertThat(encoded.get("data").get("kind").asText()).isEqualTo("sample");
    assertThat(encoded.get("diagnostics").get(0).get("severity").asText()).isEqualTo("info");
    assertThat(encoded.get("diagnostics").get(0).get("path").asText()).isEqualTo("$.nodes[0]");

    var decoded = JsonSupport.readValue(encoded.toString(), CommandEnvelope.jsonNodeEnvelopeType());

    assertThat(decoded.status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(decoded.data().get("kind").asText()).isEqualTo("sample");
    assertThat(decoded.diagnostics())
        .containsExactly(
            new Diagnostic("DEDIREN_TEST", DiagnosticSeverity.INFO, "sample", "$.nodes[0]"));
  }

  @Test
  void factoriesUseCurrentEnvelopeSchemaVersionAndDefaultDiagnostics() {
    var mapper = JsonSupport.objectMapper();
    JsonNode data = mapper.createObjectNode().put("kind", "sample");

    var ok = CommandEnvelope.ok(data);
    var error =
        CommandEnvelope.error(
            List.of(new Diagnostic("DEDIREN_ERROR", DiagnosticSeverity.ERROR, "failed", null)));

    assertThat(ok.envelopeSchemaVersion()).isEqualTo(ContractVersions.ENVELOPE_SCHEMA_VERSION);
    assertThat(ok.status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(ok.data()).isEqualTo(data);
    assertThat(ok.diagnostics()).isEmpty();

    assertThat(error.envelopeSchemaVersion()).isEqualTo(ContractVersions.ENVELOPE_SCHEMA_VERSION);
    assertThat(error.status()).isEqualTo(EnvelopeStatus.ERROR);
    assertThat(error.data()).isNull();
    assertThat(error.diagnostics())
        .extracting(Diagnostic::severity)
        .containsExactly(DiagnosticSeverity.ERROR);
  }

  @Test
  void warningFactoryCarriesWarningStatusDataAndDiagnostics() {
    var mapper = JsonSupport.objectMapper();
    JsonNode data = mapper.createObjectNode().put("status", "warning");

    var warning =
        CommandEnvelope.warning(
            data,
            List.of(
                new Diagnostic(
                    "DEDIREN_LAYOUT_QUALITY_WARNING",
                    DiagnosticSeverity.WARNING,
                    "layout quality metric 'overlap_count' is 1",
                    "$.data.overlap_count")));

    assertThat(warning.envelopeSchemaVersion()).isEqualTo(ContractVersions.ENVELOPE_SCHEMA_VERSION);
    assertThat(warning.status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(warning.data()).isEqualTo(data);
    assertThat(warning.diagnostics())
        .extracting(Diagnostic::severity)
        .containsExactly(DiagnosticSeverity.WARNING);
  }

  @Test
  void unknownFieldsAreRejectedLikeSerdeDenyUnknownFields() {
    assertThatThrownBy(
            () ->
                JsonSupport.readValue(
                    """
                {
                  "code": "DEDIREN_TEST",
                  "severity": "info",
                  "message": "sample",
                  "unexpected": true
                }
                """,
                    Diagnostic.class))
        .isInstanceOf(UnrecognizedPropertyException.class);
  }

  @Test
  void missingRequiredDiagnosticFieldsAreRejected() {
    assertThatThrownBy(
            () ->
                JsonSupport.readValue(
                    """
                {
                  "severity": "info",
                  "message": "sample"
                }
                """,
                    Diagnostic.class))
        .isInstanceOf(ValueInstantiationException.class)
        .hasMessageContaining("code");
  }
}

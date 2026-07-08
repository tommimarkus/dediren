package dev.dediren.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.SemanticValidationResult;
import dev.dediren.core.commands.CoreCommands;
import dev.dediren.core.plugins.PluginExecutionException;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Unit coverage for the five in-memory dispatch outcomes (Task 5), driven against fake engine
 * invocations so no plugin classpath is required. The envelope byte-shape parity against the real
 * process path is proven separately by the cli {@code InMemoryParityTest}.
 */
class EngineDispatchTest {

  private static final SemanticValidationResult VALUE =
      new SemanticValidationResult("semantic-validation-result.schema.v1", "archimate", 1L, 2L);

  @Test
  void successWithoutDiagnosticsProducesOkEnvelope() throws Exception {
    PluginRunOutcome outcome =
        EngineDispatch.dispatch("fake", () -> new EngineResult<>(VALUE, List.of()));

    assertThat(outcome.exitCode()).isZero();
    JsonNode envelope = envelope(outcome);
    assertThat(envelope.get("status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/semantic_profile").asText()).isEqualTo("archimate");
    assertThat(envelope.get("diagnostics")).isEmpty();
  }

  @Test
  void infoDiagnosticsRideAnOkEnvelope() throws Exception {
    Diagnostic info =
        new Diagnostic(
            "DEDIREN_OEF_VIEWS_OMITTED", DiagnosticSeverity.INFO, "one view omitted", "p");

    PluginRunOutcome outcome =
        EngineDispatch.dispatch("fake", () -> new EngineResult<>(VALUE, List.of(info)));

    assertThat(outcome.exitCode()).isZero();
    JsonNode envelope = envelope(outcome);
    // Info-severity success diagnostics ride an ok-status envelope, matching the export plugins.
    assertThat(envelope.get("status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_OEF_VIEWS_OMITTED");
  }

  @Test
  void warningDiagnosticsProduceAWarningEnvelope() throws Exception {
    Diagnostic warning =
        new Diagnostic("DEDIREN_TEST_WARNING", DiagnosticSeverity.WARNING, "watch out", null);

    PluginRunOutcome outcome =
        EngineDispatch.dispatch("fake", () -> new EngineResult<>(VALUE, List.of(warning)));

    assertThat(outcome.exitCode()).isZero();
    JsonNode envelope = envelope(outcome);
    assertThat(envelope.get("status").asText()).isEqualTo("warning");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_TEST_WARNING");
  }

  @Test
  void engineExceptionProducesErrorEnvelopeWithExitCode() throws Exception {
    List<Diagnostic> diagnostics =
        List.of(
            new Diagnostic("DEDIREN_ELK_LAYOUT_FAILED", DiagnosticSeverity.ERROR, "boom", null));

    PluginRunOutcome outcome =
        EngineDispatch.dispatch(
            "fake",
            () -> {
              throw new EngineException(diagnostics, 3);
            });

    assertThat(outcome.exitCode()).isEqualTo(3);
    JsonNode envelope = envelope(outcome);
    assertThat(envelope.get("status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_ELK_LAYOUT_FAILED");
  }

  @Test
  void parseFailureReproducesThePublishedParseCodeAndExit() throws Exception {
    // A parse entry point surfaces its published parse-failure envelope as an EngineException; the
    // dispatch renders it exactly like any other enveloped engine error (elk parse row).
    List<Diagnostic> diagnostics =
        List.of(
            new Diagnostic(
                "DEDIREN_ELK_INPUT_INVALID_JSON",
                DiagnosticSeverity.ERROR,
                "layout request JSON is invalid",
                null));

    PluginRunOutcome outcome =
        EngineDispatch.dispatch(
            "fake",
            () -> {
              throw new EngineException(diagnostics, 3);
            });

    assertThat(outcome.exitCode()).isEqualTo(3);
    assertThat(envelope(outcome).at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_ELK_INPUT_INVALID_JSON");
  }

  @Test
  void unexpectedRuntimeExceptionBecomesEngineFailed() {
    assertThatThrownBy(
            () ->
                EngineDispatch.dispatch(
                    "fake",
                    () -> {
                      throw new IllegalStateException("kaboom");
                    }))
        .isInstanceOf(PluginExecutionException.class)
        .satisfies(
            error ->
                assertThat(((PluginExecutionException) error).diagnostic().code())
                    .isEqualTo("DEDIREN_ENGINE_FAILED"));
  }

  @Test
  void uncheckedIoExceptionPropagatesInsteadOfBecomingEngineFailed() {
    // generic-graph structural failures ride UncheckedIOException so the cli can reproduce the
    // plugin-native observable (message to stderr, exit 2); the dispatch must not bury them.
    UncheckedIOException boom =
        new UncheckedIOException(new IOException("missing generic-graph view"));

    assertThatThrownBy(
            () ->
                EngineDispatch.dispatch(
                    "fake",
                    () -> {
                      throw boom;
                    }))
        .isSameAs(boom);
  }

  @Test
  void unknownEngineIdFallsBackToProcessUnknown() throws Exception {
    // No engine is bound, so the overload falls back to the process registry, which reports the
    // published unknown-id diagnostic (decision 7).
    Engines empty = Engines.of(List.of(), List.of(), List.of(), List.of());
    String layout =
        Files.readString(
            dev.dediren.testsupport.TestSupport.workspaceRoot()
                .resolve("fixtures/layout-request/basic.json"));

    assertThatThrownBy(() -> CoreCommands.layoutCommand("no-such-engine", layout, Map.of(), empty))
        .isInstanceOf(PluginExecutionException.class)
        .satisfies(
            error ->
                assertThat(((PluginExecutionException) error).diagnostic().code())
                    .isEqualTo("DEDIREN_PLUGIN_UNKNOWN"));
  }

  private static JsonNode envelope(PluginRunOutcome outcome) {
    return JsonSupport.objectMapper().readTree(outcome.stdout());
  }
}

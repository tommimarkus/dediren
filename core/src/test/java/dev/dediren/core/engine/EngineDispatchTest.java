package dev.dediren.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.SemanticValidationResult;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.core.ProductRootException;
import dev.dediren.core.commands.CoreCommands;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.RenderEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Unit coverage for the five in-memory dispatch outcomes, driven against fake engine invocations so
 * no engine classpath is required. The registry is the only transport: the fifth outcome — an
 * unknown engine id or a wrong capability — is resolved here too, without any filesystem manifest
 * lookup.
 */
class EngineDispatchTest {

  private static final SemanticValidationResult VALUE =
      new SemanticValidationResult("semantic-validation-result.schema.v1", "archimate", 1L, 2L);

  @Test
  void successWithoutDiagnosticsProducesOkEnvelope() throws Exception {
    EngineRunOutcome outcome =
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

    EngineRunOutcome outcome =
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

    EngineRunOutcome outcome =
        EngineDispatch.dispatch("fake", () -> new EngineResult<>(VALUE, List.of(warning)));

    assertThat(outcome.exitCode()).isZero();
    JsonNode envelope = envelope(outcome);
    assertThat(envelope.get("status").asText()).isEqualTo("warning");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_TEST_WARNING");
  }

  @Test
  void dispatchInMemoryReturnsValueOnSuccess() throws Exception {
    EngineResult<SemanticValidationResult> result = new EngineResult<>(VALUE, List.of());

    EngineDispatch.InMemoryOutcome<SemanticValidationResult> outcome =
        EngineDispatch.dispatchInMemory("fake", () -> result);

    assertThat(outcome).isInstanceOf(EngineDispatch.InMemoryOutcome.Value.class);
    var value = (EngineDispatch.InMemoryOutcome.Value<SemanticValidationResult>) outcome;
    assertThat(value.result().value()).isEqualTo(VALUE);
    assertThat(value.result().diagnostics()).isEmpty();
  }

  @Test
  void dispatchInMemoryReturnsFailureCarryingDiagnosticsAndExitCode() throws Exception {
    List<Diagnostic> diagnostics =
        List.of(
            new Diagnostic("DEDIREN_ELK_LAYOUT_FAILED", DiagnosticSeverity.ERROR, "boom", null));

    EngineDispatch.InMemoryOutcome<SemanticValidationResult> outcome =
        EngineDispatch.dispatchInMemory(
            "fake",
            () -> {
              throw new EngineException(diagnostics, 3);
            });

    assertThat(outcome).isInstanceOf(EngineDispatch.InMemoryOutcome.Failure.class);
    var failure = (EngineDispatch.InMemoryOutcome.Failure<SemanticValidationResult>) outcome;
    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.diagnostics())
        .extracting(Diagnostic::code)
        .containsExactly("DEDIREN_ELK_LAYOUT_FAILED");
  }

  @Test
  void dispatchInMemoryThrowsEngineFailedOnUnexpectedException() {
    // The third branch: an unexpected failure is not folded into a Failure outcome but thrown as a
    // structured EngineExecutionException, exactly as the serializing dispatch does.
    assertThatThrownBy(
            () ->
                EngineDispatch.dispatchInMemory(
                    "fake",
                    () -> {
                      throw new IllegalStateException("kaboom");
                    }))
        .isInstanceOf(EngineExecutionException.class)
        .satisfies(
            error ->
                assertThat(((EngineExecutionException) error).diagnostic().code())
                    .isEqualTo("DEDIREN_ENGINE_FAILED"));
  }

  @Test
  void dispatchInMemoryPropagatesUncheckedIoExceptionUnchanged() {
    // A structural failure must ride the raw UncheckedIOException so the cli can reproduce its
    // observable; dispatchInMemory must not bury it as a Failure or ENGINE_FAILED.
    UncheckedIOException boom =
        new UncheckedIOException(new IOException("missing generic-graph view"));

    assertThatThrownBy(
            () ->
                EngineDispatch.dispatchInMemory(
                    "fake",
                    () -> {
                      throw boom;
                    }))
        .isSameAs(boom);
  }

  @Test
  void dispatchInMemoryPropagatesProductRootExceptionUnchanged() {
    // A misconfigured DEDIREN_BUNDLE_ROOT/dediren.bundle.root is an environment misconfiguration,
    // not an engine defect: dispatchInMemory must not bury it as ENGINE_FAILED, so the cli can
    // convert it to its own DEDIREN_PRODUCT_ROOT_UNRESOLVED envelope.
    ProductRootException boom = new ProductRootException("could not locate Dediren product root");

    assertThatThrownBy(
            () ->
                EngineDispatch.dispatchInMemory(
                    "fake",
                    () -> {
                      throw boom;
                    }))
        .isSameAs(boom);
  }

  @Test
  void engineExceptionProducesErrorEnvelopeWithExitCode() throws Exception {
    List<Diagnostic> diagnostics =
        List.of(
            new Diagnostic("DEDIREN_ELK_LAYOUT_FAILED", DiagnosticSeverity.ERROR, "boom", null));

    EngineRunOutcome outcome =
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

    EngineRunOutcome outcome =
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
        .isInstanceOf(EngineExecutionException.class)
        .satisfies(
            error ->
                assertThat(((EngineExecutionException) error).diagnostic().code())
                    .isEqualTo("DEDIREN_ENGINE_FAILED"));
  }

  @Test
  void unexpectedCheckedExceptionBecomesEngineFailed() {
    // The generic bucket is the only safety net now that the process fallback is gone: the catch
    // is widened to Exception, so even a sneaky-thrown checked exception (invisible to the
    // EngineInvocation signature) maps to the published DEDIREN_ENGINE_FAILED diagnostic.
    assertThatThrownBy(
            () ->
                EngineDispatch.dispatch(
                    "fake",
                    () -> {
                      sneakyThrow(new IOException("checked kaboom"));
                      return null;
                    }))
        .isInstanceOf(EngineExecutionException.class)
        .satisfies(
            error ->
                assertThat(((EngineExecutionException) error).diagnostic().code())
                    .isEqualTo("DEDIREN_ENGINE_FAILED"));
  }

  @Test
  void errorsPropagateInsteadOfBecomingEngineFailed() {
    // Errors (OOM, assertion failures) must crash loudly, never be buried in an error envelope.
    AssertionError boom = new AssertionError("engine invariant broken");

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
  void unknownEngineIdIsRejectedWithoutAnyManifestLookup() {
    // Outcome 5a: an id bound to no capability at all yields the published unknown-id diagnostic
    // (kept wire string DEDIREN_PLUGIN_UNKNOWN) straight from the registry — no filesystem
    // manifest is consulted.
    Engines empty = Engines.of(List.of(), List.of(), List.of(), List.of());

    assertThatThrownBy(
            () -> CoreCommands.layoutCommand("no-such-engine", LAYOUT_REQUEST, Map.of(), empty))
        .isInstanceOf(EngineExecutionException.class)
        .satisfies(
            error ->
                assertThat(((EngineExecutionException) error).diagnostic().code())
                    .isEqualTo("DEDIREN_PLUGIN_UNKNOWN"));
  }

  @Test
  void boundIdWithDifferentCapabilityIsUnsupportedCapability() {
    // Outcome 5b: an id that exists in the registry, but under another capability, yields the kept
    // DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY wire string rather than unknown-id.
    Engines engines =
        Engines.of(List.of(), List.of(), List.of(new FakeRenderEngine("fake-render")), List.of());

    assertThatThrownBy(
            () -> CoreCommands.layoutCommand("fake-render", LAYOUT_REQUEST, Map.of(), engines))
        .isInstanceOf(EngineExecutionException.class)
        .satisfies(
            error ->
                assertThat(((EngineExecutionException) error).diagnostic().code())
                    .isEqualTo("DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY"));
  }

  private static final String LAYOUT_REQUEST =
      """
      {
        "layout_request_schema_version": "layout-request.schema.v2",
        "view_id": "main",
        "nodes": [],
        "edges": [],
        "groups": [],
        "constraints": []
      }
      """;

  private record FakeRenderEngine(String id) implements RenderEngine {
    @Override
    public EngineResult<RenderResult> render(
        dev.dediren.ir.LaidOutScene layout,
        JsonNode policy,
        dev.dediren.contracts.render.RenderMetadata metadataOrNull) {
      throw new UnsupportedOperationException("capability-mismatch fake must never be invoked");
    }
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(Throwable error) throws T {
    throw (T) error;
  }

  private static JsonNode envelope(EngineRunOutcome outcome) {
    return JsonSupport.objectMapper().readTree(outcome.stdout());
  }
}

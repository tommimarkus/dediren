package dev.dediren.core.engine;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.util.ContractCollections;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * Maps a typed in-memory engine call to the {@link EngineRunOutcome}{@code (stdout, exitCode)} the
 * cli renders, producing the exact command envelope shape agents consume on stdout. The registry is
 * the only transport; there is no process fallback.
 *
 * <p>The five outcomes:
 *
 * <ol>
 *   <li><b>success</b> — {@link CommandEnvelope#ok}/{@link CommandEnvelope#warning}, exit {@code
 *       0}; info-severity diagnostics ride an {@code ok}-status envelope, matching the export
 *       engines.
 *   <li><b>{@link EngineException}</b> — {@link CommandEnvelope#error} with the exception's exit
 *       code. A parse entry point that fails surfaces its published parse-failure envelope this
 *       same way (for example elk {@code DEDIREN_ELK_INPUT_INVALID_JSON} / exit 3).
 *   <li><b>{@link UncheckedIOException}</b> — re-thrown unchanged so the cli can reproduce a
 *       structural failure's observable (message to stderr, exit 2); it must never be buried as
 *       {@link DiagnosticCode#ENGINE_FAILED}.
 *   <li><b>any other exception</b> — a {@link DiagnosticCode#ENGINE_FAILED} {@link
 *       EngineExecutionException}, the successor of the retired process-crash category.
 *   <li><b>unknown engine id / unsupported capability</b> — resolved by {@link #requireEngine}
 *       before dispatch: an id bound to no capability yields {@link DiagnosticCode#PLUGIN_UNKNOWN};
 *       an id bound only under another capability yields {@link
 *       DiagnosticCode#PLUGIN_UNSUPPORTED_CAPABILITY}. Both keep their published wire strings.
 * </ol>
 */
public final class EngineDispatch {
  private EngineDispatch() {}

  /** A typed engine call: parses (if applicable) and invokes the engine, returning its result. */
  @FunctionalInterface
  public interface EngineInvocation<T> {
    EngineResult<T> invoke() throws EngineException;
  }

  /**
   * In-memory dispatch outcome: either the engine's typed {@link EngineResult}, or a published
   * error envelope's diagnostics plus exit code (an {@link EngineException}). An unexpected failure
   * is not an outcome here — {@link #dispatchInMemory} still throws {@link
   * EngineExecutionException} ({@link DiagnosticCode#ENGINE_FAILED}); an {@link
   * UncheckedIOException} still propagates unchanged. It lets the in-memory build pipe an engine's
   * typed value straight into the next stage while the serializing {@link #dispatch} reuses the
   * same branches to render an envelope.
   */
  public sealed interface InMemoryOutcome<T> {
    record Value<T>(EngineResult<T> result) implements InMemoryOutcome<T> {}

    record Failure<T>(List<Diagnostic> diagnostics, int exitCode) implements InMemoryOutcome<T> {
      public Failure {
        // Normalize the diagnostics defensively, matching EngineResult, so the record never exposes
        // a caller-held mutable list (SpotBugs EI_EXPOSE_REP) without a suppression.
        diagnostics = ContractCollections.listOrEmpty(diagnostics);
      }
    }
  }

  /**
   * Resolves outcome 5: returns the bound engine, or throws the published unknown-id /
   * unsupported-capability diagnostic. An id bound under any other capability in {@code engines} is
   * a capability mismatch, not an unknown id.
   */
  public static <T> T requireEngine(
      Engines engines, String engineId, String capability, Optional<T> engine)
      throws EngineExecutionException {
    if (engine.isPresent()) {
      return engine.get();
    }
    if (isBoundToAnyCapability(engines, engineId)) {
      throw EngineExecutionException.plugin(
          DiagnosticCode.PLUGIN_UNSUPPORTED_CAPABILITY.code(),
          engineId,
          "engine " + engineId + " does not support capability " + capability);
    }
    throw EngineExecutionException.plugin(
        DiagnosticCode.PLUGIN_UNKNOWN.code(), engineId, "unknown engine id: " + engineId);
  }

  /**
   * Invokes the engine and folds its two published failure shapes into a typed {@link
   * InMemoryOutcome} instead of a serialized envelope: a {@link EngineException} becomes {@link
   * InMemoryOutcome.Failure} (its diagnostics + exit code), while an unexpected exception still
   * maps to a {@link DiagnosticCode#ENGINE_FAILED} {@link EngineExecutionException} and an {@link
   * UncheckedIOException} still propagates unchanged. This is the transport the in-memory build
   * consumes; {@link #dispatch} is this method plus serialization.
   */
  public static <T> InMemoryOutcome<T> dispatchInMemory(
      String engineId, EngineInvocation<T> invocation) throws EngineExecutionException {
    try {
      return new InMemoryOutcome.Value<>(invocation.invoke());
    } catch (EngineException error) {
      return new InMemoryOutcome.Failure<>(error.diagnostics(), error.exitCode());
    } catch (UncheckedIOException error) {
      throw error;
    } catch (Exception error) {
      // The only safety net for an unexpected engine failure now that the process fallback is
      // gone: catch Exception — wider than RuntimeException so even a sneaky-thrown checked
      // exception maps to the published diagnostic — but never Throwable, because Errors (OOM,
      // assertion failures) must crash loudly instead of being buried in an error envelope.
      throw EngineExecutionException.plugin(
          DiagnosticCode.ENGINE_FAILED.code(),
          engineId,
          "engine " + engineId + " failed: " + error.getMessage());
    }
  }

  public static <T> EngineRunOutcome dispatch(String engineId, EngineInvocation<T> invocation)
      throws EngineExecutionException {
    return switch (dispatchInMemory(engineId, invocation)) {
      case InMemoryOutcome.Value<T> value ->
          new EngineRunOutcome(
              envelope(value.result().value(), value.result().diagnostics()),
              CommandExitCode.OK.code());
      case InMemoryOutcome.Failure<T> failure ->
          new EngineRunOutcome(
              serialize(CommandEnvelope.error(failure.diagnostics())), failure.exitCode());
    };
  }

  /**
   * Serializes a successful stage envelope for {@code data} carrying {@code diagnostics}, applying
   * the same ok/warning/info-ride-ok policy as the standalone command path. The in-memory build
   * reuses this so an {@code --emit} stage file is byte-identical to what the standalone command
   * prints to stdout.
   */
  public static String envelope(Object data, List<Diagnostic> diagnostics) {
    return serialize(successEnvelope(data, diagnostics));
  }

  private static boolean isBoundToAnyCapability(Engines engines, String engineId) {
    return engines.semantics().containsKey(engineId)
        || engines.layouts().containsKey(engineId)
        || engines.renderers().containsKey(engineId)
        || engines.exporters().containsKey(engineId);
  }

  private static <T> CommandEnvelope<T> successEnvelope(T value, List<Diagnostic> diagnostics) {
    if (diagnostics.isEmpty()) {
      return CommandEnvelope.ok(value);
    }
    boolean anyWarning =
        diagnostics.stream()
            .anyMatch(diagnostic -> diagnostic.severity() != DiagnosticSeverity.INFO);
    if (anyWarning) {
      return CommandEnvelope.warning(value, diagnostics);
    }
    // Info-severity success diagnostics (view-coverage omissions) ride an ok-status envelope so a
    // consumer sees the verdict without descending into data, exactly as the export engines do.
    return new CommandEnvelope<>(
        ContractVersions.ENVELOPE_SCHEMA_VERSION, EnvelopeStatus.OK, value, diagnostics);
  }

  private static String serialize(CommandEnvelope<?> envelope) {
    try {
      return JsonSupport.objectMapper().writeValueAsString(envelope);
    } catch (RuntimeException error) {
      throw new IllegalStateException("command envelope should serialize", error);
    }
  }
}

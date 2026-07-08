package dev.dediren.core.engine;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.plugins.PluginExecutionException;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Maps a typed in-memory engine call to the {@link PluginRunOutcome}{@code (stdout, exitCode)} the
 * cli already renders, reconstructing the exact command envelope a bundled first-party plugin emits
 * over the process boundary. This is the in-memory half of the Task 5 strangler seam; its
 * byte-shape equivalence to the process leg is pinned by the cli {@code InMemoryParityTest}.
 *
 * <p>The five outcomes:
 *
 * <ol>
 *   <li><b>success</b> — {@link CommandEnvelope#ok}/{@link CommandEnvelope#warning}, exit {@code
 *       0}; info-severity diagnostics ride an {@code ok}-status envelope, matching the export
 *       plugins.
 *   <li><b>{@link EngineException}</b> — {@link CommandEnvelope#error} with the exception's exit
 *       code. A parse entry point that fails surfaces its published parse-failure envelope this
 *       same way (for example elk {@code DEDIREN_ELK_INPUT_INVALID_JSON} / exit 3).
 *   <li><b>{@link UncheckedIOException}</b> — re-thrown unchanged so the cli can reproduce a
 *       structural failure's plugin-native observable (message to stderr, exit 2); it must never be
 *       buried as {@link DiagnosticCode#ENGINE_FAILED}.
 *   <li><b>any other runtime exception</b> — a {@link DiagnosticCode#ENGINE_FAILED} {@link
 *       PluginExecutionException}, the successor of the process-crash category.
 * </ol>
 *
 * <p>The fifth outcome — unknown engine id / wrong capability — is resolved by the caller ({@code
 * CoreCommands}), which only dispatches a bound engine and otherwise falls back to the process
 * registry that reports {@link DiagnosticCode#PLUGIN_UNKNOWN} / {@link
 * DiagnosticCode#PLUGIN_UNSUPPORTED_CAPABILITY}.
 */
public final class EngineDispatch {
  private EngineDispatch() {}

  /** A typed engine call: parses (if applicable) and invokes the engine, returning its result. */
  @FunctionalInterface
  public interface EngineInvocation<T> {
    EngineResult<T> invoke() throws EngineException;
  }

  public static <T> PluginRunOutcome dispatch(String engineId, EngineInvocation<T> invocation)
      throws PluginExecutionException {
    EngineResult<T> result;
    try {
      result = invocation.invoke();
    } catch (EngineException error) {
      return new PluginRunOutcome(
          serialize(CommandEnvelope.error(error.diagnostics())), error.exitCode());
    } catch (UncheckedIOException error) {
      throw error;
    } catch (RuntimeException error) {
      throw PluginExecutionException.plugin(
          DiagnosticCode.ENGINE_FAILED.code(),
          engineId,
          "engine " + engineId + " failed: " + error.getMessage());
    }
    return new PluginRunOutcome(
        serialize(successEnvelope(result.value(), result.diagnostics())),
        CommandExitCode.OK.code());
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
    // consumer sees the verdict without descending into data, exactly as the export plugins do.
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

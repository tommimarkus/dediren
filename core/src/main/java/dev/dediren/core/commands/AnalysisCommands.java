package dev.dediren.core.commands;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.analysis.CanonicalJson;
import dev.dediren.core.analysis.ModelDiff;
import dev.dediren.core.analysis.ModelQuery;
import dev.dediren.core.analysis.ProvenanceCheck;
import dev.dediren.core.engine.EngineRunOutcome;
import dev.dediren.core.source.SourceValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Read-side model-intelligence command drivers: {@code diff}, {@code query}, {@code verify}, {@code
 * status}. Unlike {@link CoreCommands}, these run no engine — they load and validate source models
 * (or walk a workspace tree) and analyze the result.
 *
 * <p>Both the CLI and the MCP server call these, so the two lanes emit one envelope byte for byte
 * (pinned by {@code CliMcpParityTest}). Each returns the serialized envelope plus its exit code as
 * an {@link EngineRunOutcome} — the same pass-through shape the stage commands use, which is what
 * makes byte-identical parity trivial: both lanes print or wrap the identical string.
 *
 * <p>The {@code confinementRoot} argument follows the established convention: non-null on the MCP
 * trust boundary (source-fragment paths confined to it, fragment errors sanitized), null on the
 * unconfined CLI/human lane. {@code status} loads no source and takes no such root; its workspace
 * directory is confined by the caller before it arrives here.
 */
public final class AnalysisCommands {
  /**
   * The fixed query vocabulary. Public so the MCP tool schema's advertised {@code kind} enum can be
   * pinned against it ({@code ToolSchemasTest}) — a kind added here but not advertised there is
   * simply unreachable over MCP, exactly the drift {@code BuildCommand.EMIT_KINDS} guards against.
   */
  public static final List<String> QUERY_KINDS = List.of("dependents", "orphans", "view-coverage");

  private AnalysisCommands() {}

  /** Diffs two source models, emitting an {@code ok} envelope of change records. */
  public static EngineRunOutcome diffCommand(
      String oldText, Path oldBaseDir, String newText, Path newBaseDir, Path confinementRoot) {
    SourceDocument oldModel;
    SourceDocument newModel;
    try {
      oldModel =
          SourceValidator.loadAndValidateSourceDocument(oldText, oldBaseDir, confinementRoot);
      newModel =
          SourceValidator.loadAndValidateSourceDocument(newText, newBaseDir, confinementRoot);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    return okOutcome(JsonSupport.objectMapper().valueToTree(ModelDiff.diff(oldModel, newModel)));
  }

  /**
   * Runs a fixed-vocabulary query. The {@code kind} whitelist, the {@code dependents}-requires-id
   * rule, and the unknown-node-id check all live here so both lanes reject the same inputs the same
   * way — they were CLI-only argument checks before the MCP twin existed.
   */
  public static EngineRunOutcome queryCommand(
      String kind, String id, String inputText, Path baseDir, Path confinementRoot) {
    if (kind == null || !QUERY_KINDS.contains(kind)) {
      return usageErrorOutcome(
          "unsupported query kind '" + kind + "': use dependents, orphans, or view-coverage");
    }
    if ("dependents".equals(kind) && id == null) {
      return usageErrorOutcome("query --kind dependents requires --id");
    }
    SourceDocument document;
    try {
      document = SourceValidator.loadAndValidateSourceDocument(inputText, baseDir, confinementRoot);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    if ("dependents".equals(kind)
        && document.nodes().stream().noneMatch(node -> id.equals(node.id()))) {
      return usageErrorOutcome("unknown node id '" + id + "'");
    }
    var result =
        switch (kind) {
          case "dependents" -> ModelQuery.dependents(document, id);
          case "orphans" -> ModelQuery.orphans(document);
          default -> ModelQuery.viewCoverage(document);
        };
    return okOutcome(JsonSupport.objectMapper().valueToTree(result));
  }

  /**
   * Verifies build artifacts under {@code artifactsDir} against the model's recomputed canonical
   * hash. A stale artifact is an error (exit non-zero); an unstamped one is a warning (exit zero);
   * all-current is {@code ok}. The reported artifact paths are relative to {@code artifactsDir}
   * ({@link ProvenanceCheck}), which is what lets the CLI (raw dir) and MCP (real-path dir) lanes
   * agree byte for byte.
   */
  public static EngineRunOutcome verifyCommand(
      String modelText, Path modelBaseDir, Path confinementRoot, Path artifactsDir) {
    if (!Files.isDirectory(artifactsDir)) {
      return usageErrorOutcome("--artifacts must name an existing directory: " + artifactsDir);
    }
    SourceDocument document;
    try {
      document =
          SourceValidator.loadAndValidateSourceDocument(modelText, modelBaseDir, confinementRoot);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    String modelSha = CanonicalJson.sha256(JsonSupport.objectMapper().valueToTree(document));
    var result = ProvenanceCheck.verify(modelSha, artifactsDir);
    var diagnostics = new ArrayList<Diagnostic>();
    for (var artifact : result.artifacts()) {
      if (ProvenanceCheck.STALE.equals(artifact.status())) {
        diagnostics.add(
            new Diagnostic(
                DiagnosticCode.ARTIFACT_STALE.code(),
                DiagnosticSeverity.ERROR,
                artifact.path()
                    + " was built from a different model revision; rebuild it or check out"
                    + " the matching model",
                artifact.path()));
      } else if (ProvenanceCheck.UNSTAMPED.equals(artifact.status())) {
        diagnostics.add(
            new Diagnostic(
                DiagnosticCode.ARTIFACT_UNSTAMPED.code(),
                DiagnosticSeverity.WARNING,
                artifact.path()
                    + " carries no provenance stamp (only `dediren build` artifacts are"
                    + " stamped); currency cannot be decided",
                artifact.path()));
      }
    }
    JsonNode data = JsonSupport.objectMapper().valueToTree(result);
    boolean stale =
        diagnostics.stream().anyMatch(d -> d.code().equals(DiagnosticCode.ARTIFACT_STALE.code()));
    if (stale) {
      return outcome(
          new CommandEnvelope<>(
              ContractVersions.ENVELOPE_SCHEMA_VERSION, EnvelopeStatus.ERROR, data, diagnostics),
          CommandExitCode.INPUT_ERROR);
    }
    if (!diagnostics.isEmpty()) {
      return outcome(
          new CommandEnvelope<>(
              ContractVersions.ENVELOPE_SCHEMA_VERSION, EnvelopeStatus.WARNING, data, diagnostics),
          CommandExitCode.OK);
    }
    return okOutcome(data);
  }

  /** Indexes a workspace's models and stamped artifacts, emitting an {@code ok} envelope. */
  public static EngineRunOutcome statusCommand(Path root) {
    if (!Files.isDirectory(root)) {
      return usageErrorOutcome("--root must name an existing directory: " + root);
    }
    return okOutcome(JsonSupport.objectMapper().valueToTree(ProvenanceCheck.status(root)));
  }

  private static EngineRunOutcome okOutcome(JsonNode data) {
    return outcome(CommandEnvelope.ok(data), CommandExitCode.OK);
  }

  private static EngineRunOutcome usageErrorOutcome(String message) {
    return errorOutcome(
        List.of(
            new Diagnostic(
                DiagnosticCode.COMMAND_INPUT_INVALID.code(),
                DiagnosticSeverity.ERROR,
                message,
                null)));
  }

  private static EngineRunOutcome errorOutcome(List<Diagnostic> diagnostics) {
    return outcome(CommandEnvelope.error(diagnostics), CommandExitCode.INPUT_ERROR);
  }

  private static EngineRunOutcome outcome(
      CommandEnvelope<JsonNode> envelope, CommandExitCode exitCode) {
    try {
      return new EngineRunOutcome(
          JsonSupport.objectMapper().writeValueAsString(envelope), exitCode.code());
    } catch (RuntimeException error) {
      throw new IllegalStateException("analysis envelope should serialize", error);
    }
  }
}

package dev.dediren.core.commands;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.engine.EngineDispatch;
import dev.dediren.core.io.JsonInput;
import dev.dediren.core.plugins.PluginExecutionException;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.core.quality.LayoutQuality;
import dev.dediren.core.quality.LayoutQualityReport;
import dev.dediren.core.source.SourceValidator;
import dev.dediren.core.source.ValidationResult;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.ExportEngine;
import dev.dediren.engine.LayoutEngine;
import dev.dediren.engine.RenderEngine;
import dev.dediren.engine.SemanticsEngine;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LaidOutSceneMapper;
import dev.dediren.ir.LayoutRequestMapper;
import dev.dediren.ir.SceneGraph;
import dev.dediren.ir.quality.InvariantViolation;
import dev.dediren.ir.quality.SequenceInvariants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Stage command drivers over the in-memory engine registry. Every stage command requires an {@link
 * Engines} registry; command inputs are parsed/validated first, then the engine is resolved
 * (unknown id / unsupported capability via {@link EngineDispatch#requireEngine}), then the typed
 * call is dispatched. The overloads without an explicit env map default to the ambient process
 * environment — the export lane reads schema-path variables from it.
 */
public final class CoreCommands {
  private CoreCommands() {}

  public static PluginRunOutcome layoutCommand(
      String engineId, String inputText, Map<String, String> env, Engines engines)
      throws PluginExecutionException {
    // Unwrap a piped stage envelope to its data (the chained-workflow convenience), then route the
    // unwrapped bytes through the engine's parse entry point so a well-formed-but-invalid request
    // reproduces the published DEDIREN_ELK_INPUT_INVALID_JSON envelope rather than core's generic
    // input diagnostic.
    byte[] bytes = layoutRequestBytes(inputText);
    LayoutEngine layout =
        EngineDispatch.requireEngine(engines, engineId, "layout", engines.layoutEngine(engineId));
    return EngineDispatch.dispatch(
        engineId,
        () -> {
          EngineResult<LaidOutScene> laid = layout.layout(layout.parseRequest(bytes));
          return new EngineResult<>(LaidOutSceneMapper.toResult(laid.value()), laid.diagnostics());
        });
  }

  private static byte[] layoutRequestBytes(String inputText) throws PluginExecutionException {
    JsonNode value;
    try {
      value = JsonSupport.objectMapper().readTree(inputText);
    } catch (RuntimeException error) {
      throw commandInputInvalid("layout", error);
    }
    JsonNode data = value.has("envelope_schema_version") ? value.get("data") : value;
    if (data == null) {
      throw commandInputInvalid(
          "layout", new IllegalArgumentException("command envelope does not contain data"));
    }
    try {
      return JsonSupport.objectMapper().writeValueAsBytes(data);
    } catch (RuntimeException error) {
      throw commandInputInvalid("layout", error);
    }
  }

  public static PluginRunOutcome projectCommand(
      String engineId,
      String target,
      String view,
      String inputText,
      Path baseDir,
      Map<String, String> env,
      Engines engines)
      throws PluginExecutionException {
    SourceDocument source;
    try {
      source = SourceValidator.loadAndValidateSourceDocument(inputText, baseDir);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    SemanticsEngine semantics =
        EngineDispatch.requireEngine(
            engines, engineId, "projection", engines.semanticsEngine(engineId));
    if ("render-metadata".equals(target)) {
      return EngineDispatch.dispatch(engineId, () -> semantics.projectRenderMetadata(source, view));
    }
    if ("layout-request".equals(target)) {
      return EngineDispatch.dispatch(
          engineId,
          () -> {
            EngineResult<SceneGraph> projected = semantics.projectScene(source, view);
            return new EngineResult<>(
                LayoutRequestMapper.toRequest(projected.value()), projected.diagnostics());
          });
    }
    // A structural failure's observable: message to stderr, exit 2. The cli catches this
    // UncheckedIOException and prints its cause, keeping the published non-enveloped form.
    throw new UncheckedIOException(new IOException("unsupported target: " + target));
  }

  public static PluginRunOutcome semanticValidateCommand(
      String engineId,
      String profile,
      String inputText,
      Path baseDir,
      Map<String, String> env,
      Engines engines)
      throws PluginExecutionException {
    SourceDocument source;
    try {
      source = SourceValidator.loadAndValidateSourceDocument(inputText, baseDir);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    SemanticsEngine semantics =
        EngineDispatch.requireEngine(
            engines, engineId, "semantic-validation", engines.semanticsEngine(engineId));
    return EngineDispatch.dispatch(engineId, () -> semantics.validate(source, profile));
  }

  public static ValidationResult validateLayoutCommand(String inputText) {
    try {
      return validateLayoutResult(JsonInput.parseCommandData(inputText, LayoutResult.class));
    } catch (RuntimeException error) {
      return commandInputValidationResult("validate-layout", error);
    }
  }

  /**
   * The quality stage over an already-typed {@link LayoutResult}: the in-memory build passes {@code
   * LaidOutSceneMapper.toResult(laid)} straight in with no JSON round-trip, and gets the same
   * verdict the standalone {@code validate-layout} command would produce for the equivalent bytes.
   * A quality {@link RuntimeException} is folded into a {@code DEDIREN_COMMAND_INPUT_INVALID} error
   * result exactly as the string entry point does.
   */
  public static ValidationResult validateLayout(LayoutResult result) {
    try {
      return validateLayoutResult(result);
    } catch (RuntimeException error) {
      return commandInputValidationResult("validate-layout", error);
    }
  }

  private static ValidationResult validateLayoutResult(LayoutResult result) {
    List<Diagnostic> diagnostics = new ArrayList<>(LayoutQuality.validateLayoutDiagnostics(result));
    diagnostics.addAll(sequenceInvariantDiagnostics(result));
    if (!diagnostics.isEmpty()) {
      return new ValidationResult(
          CommandExitCode.INPUT_ERROR.code(), CommandEnvelope.error(diagnostics));
    }
    LayoutQualityReport report = LayoutQuality.validateLayout(result);
    JsonNode data = JsonSupport.objectMapper().valueToTree(report);
    List<Diagnostic> qualityWarnings = LayoutQuality.layoutQualityWarnings(report);
    // A warning verdict is not a failure, so the exit code stays OK; the envelope status and
    // diagnostics carry the verdict for consumers that read the envelope, not just data.
    CommandEnvelope<JsonNode> envelope =
        qualityWarnings.isEmpty()
            ? CommandEnvelope.ok(data)
            : CommandEnvelope.warning(data, qualityWarnings);
    return new ValidationResult(CommandExitCode.OK.code(), envelope);
  }

  /**
   * The typed-IR geometric invariants over a UML-sequence layout (Plan B P5), folded into the same
   * hard-error lane as {@link LayoutQuality#validateLayoutDiagnostics}: a violated invariant (for
   * example a message endpoint off its lifeline axis) is an input error, consistent with that
   * lane's {@code INPUT_ERROR} verdict. Non-sequence layouts carry no lifeline/interaction
   * geometry, so every {@link SequenceInvariants} check returns empty and this contributes nothing.
   */
  private static List<Diagnostic> sequenceInvariantDiagnostics(LayoutResult result) {
    LaidOutScene scene = LaidOutSceneMapper.toScene(result);
    List<InvariantViolation> violations = new ArrayList<>();
    violations.addAll(SequenceInvariants.messageEndpointsOnLifelineAxis(scene));
    violations.addAll(SequenceInvariants.messageYStrictlyIncreasing(scene));
    violations.addAll(SequenceInvariants.interactionFrameEnclosesLifelines(scene));
    return violations.stream()
        .map(violation -> sequenceInvariantDiagnostic(result, violation))
        .toList();
  }

  private static Diagnostic sequenceInvariantDiagnostic(
      LayoutResult result, InvariantViolation violation) {
    return new Diagnostic(
        DiagnosticCode.LAYOUT_SEQUENCE_INVARIANT_VIOLATED.code(),
        DiagnosticSeverity.ERROR,
        "sequence invariant '"
            + violation.invariant()
            + "' violated by '"
            + violation.elementId()
            + "': "
            + violation.detail(),
        elementPath(result, violation.elementId()),
        violation.origin() == null ? null : violation.origin().value());
  }

  private static String elementPath(LayoutResult result, String elementId) {
    List<LaidOutNode> nodes = result.nodes();
    for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
      if (nodes.get(nodeIndex).id().equals(elementId)) {
        return "$.nodes[" + nodeIndex + "]";
      }
    }
    List<LaidOutEdge> edges = result.edges();
    for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
      if (edges.get(edgeIndex).id().equals(elementId)) {
        return "$.edges[" + edgeIndex + "]";
      }
    }
    return "$";
  }

  public static PluginRunOutcome renderCommand(
      String engineId,
      String policyText,
      String metadataText,
      String layoutText,
      Map<String, String> env,
      Engines engines)
      throws PluginExecutionException {
    LayoutResult layoutResult = parseCommandData("render", layoutText, LayoutResult.class);
    JsonNode policy = parseJson("render", policyText);
    RenderMetadata metadata =
        metadataText == null
            ? null
            : parseCommandData("render", metadataText, RenderMetadata.class);
    RenderEngine renderEngine =
        EngineDispatch.requireEngine(engines, engineId, "render", engines.renderEngine(engineId));
    return EngineDispatch.dispatch(
        engineId,
        () -> renderEngine.render(LaidOutSceneMapper.toScene(layoutResult), policy, metadata));
  }

  public static PluginRunOutcome exportCommand(
      String engineId,
      String policyText,
      String sourceText,
      Path sourceBaseDir,
      String layoutText,
      Map<String, String> env,
      Engines engines)
      throws PluginExecutionException {
    SourceDocument source;
    try {
      source = SourceValidator.loadAndValidateSourceDocument(sourceText, sourceBaseDir);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    LayoutResult layoutResult = parseCommandData("export", layoutText, LayoutResult.class);
    JsonNode policy = parseJson("export", policyText);
    var request =
        new ExportRequest(
            ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION, source, layoutResult, policy);
    ExportEngine exportEngine =
        EngineDispatch.requireEngine(engines, engineId, "export", engines.exportEngine(engineId));
    // The export engine receives the CLI's env map explicitly (schema-path variables) and reads
    // nothing else. Decision 9: a relative schema/cache env path resolves against the product
    // root, not the JVM cwd.
    Path productRoot = DedirenPaths.productRoot();
    return EngineDispatch.dispatch(engineId, () -> exportEngine.export(request, env, productRoot));
  }

  static PluginRunOutcome errorOutcome(List<Diagnostic> diagnostics) {
    try {
      return new PluginRunOutcome(
          JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(diagnostics)),
          CommandExitCode.INPUT_ERROR.code());
    } catch (RuntimeException error) {
      throw new IllegalStateException("error envelope should serialize", error);
    }
  }

  private static <T> T parseCommandData(String command, String text, Class<T> type)
      throws PluginExecutionException {
    try {
      return JsonInput.parseCommandData(text, type);
    } catch (RuntimeException error) {
      throw commandInputInvalid(command, error);
    }
  }

  static JsonNode parseJson(String command, String text) throws PluginExecutionException {
    try {
      return JsonSupport.objectMapper().readTree(text);
    } catch (RuntimeException error) {
      throw commandInputInvalid(command, error);
    }
  }

  private static PluginExecutionException commandInputInvalid(String command, Exception error) {
    return PluginExecutionException.command(
        DiagnosticCode.COMMAND_INPUT_INVALID.code(), command, error.getMessage());
  }

  private static ValidationResult commandInputValidationResult(String command, Exception error) {
    var diagnostic =
        PluginExecutionException.command(
                DiagnosticCode.COMMAND_INPUT_INVALID.code(), command, error.getMessage())
            .diagnostic();
    return new ValidationResult(
        CommandExitCode.INPUT_ERROR.code(), CommandEnvelope.error(List.of(diagnostic)));
  }
}

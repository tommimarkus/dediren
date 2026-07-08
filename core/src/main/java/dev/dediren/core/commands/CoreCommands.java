package dev.dediren.core.commands;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.engine.EngineDispatch;
import dev.dediren.core.io.JsonInput;
import dev.dediren.core.plugins.PluginExecutionException;
import dev.dediren.core.plugins.PluginRegistry;
import dev.dediren.core.plugins.PluginRunOptions;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.core.plugins.PluginRunner;
import dev.dediren.core.quality.LayoutQuality;
import dev.dediren.core.quality.LayoutQualityReport;
import dev.dediren.core.source.SourceValidator;
import dev.dediren.core.source.ValidationResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.ExportEngine;
import dev.dediren.engine.LayoutEngine;
import dev.dediren.engine.RenderEngine;
import dev.dediren.engine.SemanticsEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

public final class CoreCommands {
  private CoreCommands() {}

  public static PluginRunOutcome layoutCommand(String plugin, String inputText)
      throws PluginExecutionException {
    return layoutCommand(plugin, inputText, System.getenv());
  }

  public static PluginRunOutcome layoutCommand(
      String plugin, String inputText, Map<String, String> env) throws PluginExecutionException {
    return layoutCommand(
        new LayoutCommandInput(plugin, inputText, PluginRegistry.bundled(env), env));
  }

  public static PluginRunOutcome layoutCommand(LayoutCommandInput input)
      throws PluginExecutionException {
    LayoutRequest request = parseCommandData("layout", input.inputText(), LayoutRequest.class);
    return PluginRunner.runForCapabilityWithRegistry(
        input.registry(),
        input.plugin(),
        "layout",
        List.of("layout"),
        toJson("layout", request),
        PluginRunOptions.defaults().withCandidateEnv(input.env()));
  }

  public static PluginRunOutcome projectCommand(
      String plugin, String target, String view, String inputText, Path baseDir)
      throws PluginExecutionException {
    return projectCommand(plugin, target, view, inputText, baseDir, System.getenv());
  }

  public static PluginRunOutcome projectCommand(
      String plugin,
      String target,
      String view,
      String inputText,
      Path baseDir,
      Map<String, String> env)
      throws PluginExecutionException {
    SourceDocument source;
    try {
      source = SourceValidator.loadAndValidateSourceDocument(inputText, baseDir);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    return PluginRunner.runForCapabilityWithRegistry(
        PluginRegistry.bundled(env),
        plugin,
        "projection",
        List.of("project", "--target", target, "--view", view),
        toJson("project", source),
        PluginRunOptions.defaults().withCandidateEnv(env));
  }

  public static PluginRunOutcome semanticValidateCommand(
      String plugin, String profile, String inputText) throws PluginExecutionException {
    return semanticValidateCommand(plugin, profile, inputText, null);
  }

  public static PluginRunOutcome semanticValidateCommand(
      String plugin, String profile, String inputText, Path baseDir)
      throws PluginExecutionException {
    return semanticValidateCommand(plugin, profile, inputText, baseDir, System.getenv());
  }

  public static PluginRunOutcome semanticValidateCommand(
      String plugin, String profile, String inputText, Path baseDir, Map<String, String> env)
      throws PluginExecutionException {
    SourceDocument source;
    try {
      source = SourceValidator.loadAndValidateSourceDocument(inputText, baseDir);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    return PluginRunner.runForCapabilityWithRegistry(
        PluginRegistry.bundled(env),
        plugin,
        "semantic-validation",
        List.of("validate", "--profile", profile),
        toJson("validate", source),
        PluginRunOptions.defaults().withCandidateEnv(env));
  }

  public static ValidationResult validateLayoutCommand(String inputText) {
    try {
      LayoutResult result = JsonInput.parseCommandData(inputText, LayoutResult.class);
      List<Diagnostic> diagnostics = LayoutQuality.validateLayoutDiagnostics(result);
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
    } catch (RuntimeException error) {
      return commandInputValidationResult("validate-layout", error);
    }
  }

  public static PluginRunOutcome renderCommand(
      String plugin, String policyText, String metadataText, String layoutText)
      throws PluginExecutionException {
    return renderCommand(plugin, policyText, metadataText, layoutText, System.getenv());
  }

  public static PluginRunOutcome renderCommand(
      String plugin,
      String policyText,
      String metadataText,
      String layoutText,
      Map<String, String> env)
      throws PluginExecutionException {
    LayoutResult layoutResult = parseCommandData("render", layoutText, LayoutResult.class);
    JsonNode policy = parseJson("render", policyText);
    RenderMetadata metadata =
        metadataText == null
            ? null
            : parseCommandData("render", metadataText, RenderMetadata.class);

    var input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", JsonSupport.objectMapper().valueToTree(layoutResult));
    input.set("policy", policy);
    if (metadata != null) {
      input.set("render_metadata", JsonSupport.objectMapper().valueToTree(metadata));
    }
    return PluginRunner.runForCapabilityWithRegistry(
        PluginRegistry.bundled(env),
        plugin,
        "render",
        List.of("render"),
        toJson("render", input),
        PluginRunOptions.defaults().withCandidateEnv(env));
  }

  public static PluginRunOutcome exportCommand(
      String plugin, String policyText, String sourceText, Path sourceBaseDir, String layoutText)
      throws PluginExecutionException {
    return exportCommand(
        plugin, policyText, sourceText, sourceBaseDir, layoutText, System.getenv());
  }

  public static PluginRunOutcome exportCommand(
      String plugin,
      String policyText,
      String sourceText,
      Path sourceBaseDir,
      String layoutText,
      Map<String, String> env)
      throws PluginExecutionException {
    SourceDocument source;
    try {
      source = SourceValidator.loadAndValidateSourceDocument(sourceText, sourceBaseDir);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    LayoutResult layoutResult = parseCommandData("export", layoutText, LayoutResult.class);
    JsonNode policy = parseJson("export", policyText);
    var input =
        new ExportRequest(
            ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION, source, layoutResult, policy);
    return PluginRunner.runForCapabilityWithRegistry(
        PluginRegistry.bundled(env),
        plugin,
        "export",
        List.of("export"),
        toJson("export", input),
        PluginRunOptions.defaults().withCandidateEnv(env));
  }

  // Registry-first in-memory dispatch overloads (Task 5 strangler seam). A bound first-party engine
  // runs in-process through EngineDispatch; an unbound id falls back to the process path above, so
  // third-party plugins and the unknown-id/unsupported-capability diagnostics are unchanged.

  public static PluginRunOutcome layoutCommand(
      String plugin, String inputText, Map<String, String> env, Engines engines)
      throws PluginExecutionException {
    Optional<LayoutEngine> engine = engines.layoutEngine(plugin);
    if (engine.isEmpty()) {
      return layoutCommand(plugin, inputText, env);
    }
    LayoutEngine layout = engine.get();
    // Unwrap a piped stage envelope to its data (the chained-workflow convenience JsonInput gives
    // the process path), then route the unwrapped bytes through the engine's parse entry point so a
    // well-formed-but-invalid request reproduces the published DEDIREN_ELK_INPUT_INVALID_JSON
    // envelope rather than core's generic input diagnostic.
    byte[] bytes = layoutRequestBytes(inputText);
    return EngineDispatch.dispatch(plugin, () -> layout.layout(layout.parseRequest(bytes)));
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
      String plugin,
      String target,
      String view,
      String inputText,
      Path baseDir,
      Map<String, String> env,
      Engines engines)
      throws PluginExecutionException {
    Optional<SemanticsEngine> engine = engines.semanticsEngine(plugin);
    if (engine.isEmpty()) {
      return projectCommand(plugin, target, view, inputText, baseDir, env);
    }
    SourceDocument source;
    try {
      source = SourceValidator.loadAndValidateSourceDocument(inputText, baseDir);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    SemanticsEngine semantics = engine.get();
    if ("render-metadata".equals(target)) {
      return EngineDispatch.dispatch(plugin, () -> semantics.projectRenderMetadata(source, view));
    }
    if ("layout-request".equals(target)) {
      return EngineDispatch.dispatch(plugin, () -> semantics.projectLayoutRequest(source, view));
    }
    // Reproduce the plugin-native "unsupported target" observable: message to stderr, exit 2. The
    // cli catches this UncheckedIOException and prints its cause, matching the process form.
    throw new UncheckedIOException(new IOException("unsupported target: " + target));
  }

  public static PluginRunOutcome semanticValidateCommand(
      String plugin,
      String profile,
      String inputText,
      Path baseDir,
      Map<String, String> env,
      Engines engines)
      throws PluginExecutionException {
    Optional<SemanticsEngine> engine = engines.semanticsEngine(plugin);
    if (engine.isEmpty()) {
      return semanticValidateCommand(plugin, profile, inputText, baseDir, env);
    }
    SourceDocument source;
    try {
      source = SourceValidator.loadAndValidateSourceDocument(inputText, baseDir);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    SemanticsEngine semantics = engine.get();
    return EngineDispatch.dispatch(plugin, () -> semantics.validate(source, profile));
  }

  public static PluginRunOutcome renderCommand(
      String plugin,
      String policyText,
      String metadataText,
      String layoutText,
      Map<String, String> env,
      Engines engines)
      throws PluginExecutionException {
    Optional<RenderEngine> engine = engines.renderEngine(plugin);
    if (engine.isEmpty()) {
      return renderCommand(plugin, policyText, metadataText, layoutText, env);
    }
    LayoutResult layoutResult = parseCommandData("render", layoutText, LayoutResult.class);
    JsonNode policy = parseJson("render", policyText);
    RenderMetadata metadata =
        metadataText == null
            ? null
            : parseCommandData("render", metadataText, RenderMetadata.class);
    RenderEngine renderEngine = engine.get();
    return EngineDispatch.dispatch(
        plugin, () -> renderEngine.render(layoutResult, policy, metadata));
  }

  public static PluginRunOutcome exportCommand(
      String plugin,
      String policyText,
      String sourceText,
      Path sourceBaseDir,
      String layoutText,
      Map<String, String> env,
      Engines engines)
      throws PluginExecutionException {
    Optional<ExportEngine> engine = engines.exportEngine(plugin);
    if (engine.isEmpty()) {
      return exportCommand(plugin, policyText, sourceText, sourceBaseDir, layoutText, env);
    }
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
    ExportEngine exportEngine = engine.get();
    // Decision 9: a relative schema/cache env path resolves against the product root, not the JVM
    // cwd, matching the process child (which ran with cwd = product root).
    Path productRoot = DedirenPaths.productRoot();
    return EngineDispatch.dispatch(plugin, () -> exportEngine.export(request, env, productRoot));
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

  private static JsonNode parseJson(String command, String text) throws PluginExecutionException {
    try {
      return JsonSupport.objectMapper().readTree(text);
    } catch (RuntimeException error) {
      throw commandInputInvalid(command, error);
    }
  }

  private static String toJson(String command, Object value) throws PluginExecutionException {
    try {
      return JsonSupport.objectMapper().writeValueAsString(value);
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

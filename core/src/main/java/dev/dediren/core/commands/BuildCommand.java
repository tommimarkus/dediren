package dev.dediren.core.commands;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.build.BuildArtifact;
import dev.dediren.contracts.build.BuildResult;
import dev.dediren.contracts.build.BuildViewOutcome;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.plugins.PluginExecutionException;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.core.source.SourceValidator;
import dev.dediren.core.source.ValidationResult;
import dev.dediren.engine.Engines;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The in-memory {@code build} driver (decision 14): it validates a source document once, then for
 * every selected view chains the same proven stage paths the Task 5 parity gate blessed — {@link
 * CoreCommands#projectCommand project layout-request} → {@link CoreCommands#layoutCommand layout} →
 * {@link CoreCommands#validateLayoutCommand layout-quality validation} → an optional render lane
 * ({@code project render-metadata} + {@code render}) and/or export lanes ({@code archimate-oef},
 * {@code uml-xmi}) — writing each lane's artifact under {@code outDir}. It never re-implements
 * stage logic; it pipes each stage's command envelope into the next exactly as the process CLI did,
 * so {@code --emit} can persist a stage envelope verbatim and every stage's diagnostics surface in
 * the build result unchanged.
 *
 * <p>Aggregation follows the envelope vocabulary: a view is {@code error} if any of its stages
 * failed (it stops at that stage, so its artifacts may be partial), {@code warning} if a stage
 * warned, else {@code ok}; the build rolls those up the same way. A failing view never aborts the
 * others. Lane selection is explicit — at least one of the three policy lanes must be present — and
 * an invalid source or an empty lane set is reported as a build-level error result rather than a
 * partial build. Engine ids are fixed to the bundled Phase-1 set; per-stage {@code --plugin}
 * selection stays on the per-stage subcommands.
 */
public final class BuildCommand {
  private static final String SEMANTICS_ENGINE = "generic-graph";
  private static final String LAYOUT_ENGINE = "elk-layout";
  private static final String RENDER_ENGINE = "render";
  private static final String OEF_ENGINE = "archimate-oef";
  private static final String XMI_ENGINE = "uml-xmi";

  private static final String EMIT_LAYOUT_REQUEST = "layout-request";
  private static final String EMIT_LAYOUT_RESULT = "layout-result";
  private static final String EMIT_RENDER_METADATA = "render-metadata";

  private BuildCommand() {}

  public static PluginRunOutcome run(BuildRequest request, Engines engines)
      throws PluginExecutionException {
    if (request.renderPolicyText() == null
        && request.oefPolicyText() == null
        && request.xmiPolicyText() == null) {
      return buildLevelError(
          new Diagnostic(
              DiagnosticCode.COMMAND_INPUT_INVALID.code(),
              DiagnosticSeverity.ERROR,
              "build requires at least one output lane"
                  + " (--render-policy, --oef-policy, or --xmi-policy)",
              "command:build"));
    }

    SourceDocument source;
    try {
      source =
          SourceValidator.loadAndValidateSourceDocument(
              request.sourceText(), request.sourceBaseDir());
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return buildLevelError(error.diagnostics());
    }

    List<String> views = selectViews(request, source);
    List<BuildViewOutcome> outcomes = new ArrayList<>(views.size());
    boolean anyError = false;
    boolean anyWarning = false;
    int failureExit = CommandExitCode.OK.code();
    for (String view : views) {
      ViewBuild built = buildView(request, engines, view);
      outcomes.add(built.outcome());
      EnvelopeStatus viewStatus = built.outcome().status();
      if (viewStatus == EnvelopeStatus.ERROR) {
        anyError = true;
        failureExit = Math.max(failureExit, built.failureExit());
      } else if (viewStatus == EnvelopeStatus.WARNING) {
        anyWarning = true;
      }
    }

    EnvelopeStatus status =
        anyError ? EnvelopeStatus.ERROR : anyWarning ? EnvelopeStatus.WARNING : EnvelopeStatus.OK;
    int exitCode = anyError ? failureExit : CommandExitCode.OK.code();
    return outcome(
        new BuildResult(ContractVersions.BUILD_RESULT_SCHEMA_VERSION, status, outcomes, List.of()),
        exitCode);
  }

  private static List<String> selectViews(BuildRequest request, SourceDocument source)
      throws PluginExecutionException {
    if (!request.views().isEmpty()) {
      return request.views();
    }
    JsonNode genericGraph = source.plugins().get(SEMANTICS_ENGINE);
    if (genericGraph == null) {
      return List.of();
    }
    try {
      GenericGraphPluginData data =
          JsonSupport.objectMapper().treeToValue(genericGraph, GenericGraphPluginData.class);
      return data.views().stream().map(GenericGraphView::id).toList();
    } catch (RuntimeException error) {
      throw PluginExecutionException.command(
          DiagnosticCode.COMMAND_INPUT_INVALID.code(),
          "build",
          "generic-graph view list is unreadable: " + error.getMessage());
    }
  }

  private static ViewBuild buildView(BuildRequest request, Engines engines, String view) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    List<BuildArtifact> artifacts = new ArrayList<>();
    boolean warning = false;

    StageOutcome layoutRequest =
        runStage(
            diagnostics,
            () ->
                CoreCommands.projectCommand(
                    SEMANTICS_ENGINE,
                    "layout-request",
                    view,
                    request.sourceText(),
                    request.sourceBaseDir(),
                    request.env(),
                    engines));
    if (layoutRequest.failed()) {
      return failedView(view, artifacts, diagnostics, layoutRequest.exitCode());
    }
    emit(request, view, EMIT_LAYOUT_REQUEST, "layout-request.json", layoutRequest.outcome());
    warning |= layoutRequest.warning();

    StageOutcome layout =
        runStage(
            diagnostics,
            () ->
                CoreCommands.layoutCommand(
                    LAYOUT_ENGINE, layoutRequest.outcome().stdout(), request.env(), engines));
    if (layout.failed()) {
      return failedView(view, artifacts, diagnostics, layout.exitCode());
    }
    emit(request, view, EMIT_LAYOUT_RESULT, "layout-result.json", layout.outcome());
    warning |= layout.warning();
    String layoutEnvelope = layout.outcome().stdout();

    ValidationResult quality = CoreCommands.validateLayoutCommand(layoutEnvelope);
    diagnostics.addAll(quality.envelope().diagnostics());
    if (quality.envelope().status() == EnvelopeStatus.ERROR) {
      return failedView(view, artifacts, diagnostics, quality.exitCode());
    }
    warning |= quality.envelope().status() == EnvelopeStatus.WARNING;

    if (request.renderPolicyText() != null) {
      StageOutcome renderMetadata =
          runStage(
              diagnostics,
              () ->
                  CoreCommands.projectCommand(
                      SEMANTICS_ENGINE,
                      "render-metadata",
                      view,
                      request.sourceText(),
                      request.sourceBaseDir(),
                      request.env(),
                      engines));
      if (renderMetadata.failed()) {
        return failedView(view, artifacts, diagnostics, renderMetadata.exitCode());
      }
      emit(request, view, EMIT_RENDER_METADATA, "render-metadata.json", renderMetadata.outcome());
      warning |= renderMetadata.warning();

      StageOutcome render =
          runStage(
              diagnostics,
              () ->
                  CoreCommands.renderCommand(
                      RENDER_ENGINE,
                      request.renderPolicyText(),
                      renderMetadata.outcome().stdout(),
                      layoutEnvelope,
                      request.env(),
                      engines));
      if (render.failed()) {
        return failedView(view, artifacts, diagnostics, render.exitCode());
      }
      warning |= render.warning();
      writeRenderArtifacts(request, view, render.data(), artifacts);
    }

    if (request.oefPolicyText() != null) {
      StageOutcome oef =
          runStage(
              diagnostics,
              () ->
                  CoreCommands.exportCommand(
                      OEF_ENGINE,
                      request.oefPolicyText(),
                      request.sourceText(),
                      request.sourceBaseDir(),
                      layoutEnvelope,
                      request.env(),
                      engines));
      if (oef.failed()) {
        return failedView(view, artifacts, diagnostics, oef.exitCode());
      }
      warning |= oef.warning();
      writeExportArtifact(request, view, "oef", oef.data(), artifacts);
    }

    if (request.xmiPolicyText() != null) {
      StageOutcome xmi =
          runStage(
              diagnostics,
              () ->
                  CoreCommands.exportCommand(
                      XMI_ENGINE,
                      request.xmiPolicyText(),
                      request.sourceText(),
                      request.sourceBaseDir(),
                      layoutEnvelope,
                      request.env(),
                      engines));
      if (xmi.failed()) {
        return failedView(view, artifacts, diagnostics, xmi.exitCode());
      }
      warning |= xmi.warning();
      writeExportArtifact(request, view, "xmi", xmi.data(), artifacts);
    }

    EnvelopeStatus status = warning ? EnvelopeStatus.WARNING : EnvelopeStatus.OK;
    return new ViewBuild(
        new BuildViewOutcome(view, status, artifacts, diagnostics), CommandExitCode.OK.code());
  }

  private static StageOutcome runStage(List<Diagnostic> diagnostics, StageCall call) {
    PluginRunOutcome outcome;
    try {
      outcome = call.run();
    } catch (PluginExecutionException error) {
      // In-memory dispatch surfaces an unexpected engine failure or invalid command input as a
      // structured exception; fold its diagnostic into the view so one failing view never aborts
      // the others.
      diagnostics.add(error.diagnostic());
      return StageOutcome.crashed(CommandExitCode.PLUGIN_ERROR.code());
    }
    CommandEnvelope<JsonNode> envelope =
        JsonSupport.readValue(outcome.stdout(), CommandEnvelope.jsonNodeEnvelopeType());
    diagnostics.addAll(envelope.diagnostics());
    boolean failed =
        outcome.exitCode() != CommandExitCode.OK.code()
            || envelope.status() == EnvelopeStatus.ERROR;
    boolean warning = envelope.status() == EnvelopeStatus.WARNING;
    return new StageOutcome(outcome, envelope.data(), failed, warning, outcome.exitCode());
  }

  private static void emit(
      BuildRequest request, String view, String key, String fileName, PluginRunOutcome stage) {
    if (request.emit().contains(key)) {
      writeFile(request, view, fileName, stage.stdout());
    }
  }

  private static void writeRenderArtifacts(
      BuildRequest request, String view, JsonNode renderData, List<BuildArtifact> artifacts) {
    RenderResult result = JsonSupport.objectMapper().treeToValue(renderData, RenderResult.class);
    for (RenderArtifact artifact : result.artifacts()) {
      String fileName = "diagram." + renderExtension(artifact.artifactKind());
      writeFile(request, view, fileName, artifact.content());
      artifacts.add(new BuildArtifact(artifact.artifactKind(), view + "/" + fileName));
    }
  }

  private static void writeExportArtifact(
      BuildRequest request,
      String view,
      String baseName,
      JsonNode exportData,
      List<BuildArtifact> artifacts) {
    ExportResult result = JsonSupport.objectMapper().treeToValue(exportData, ExportResult.class);
    String fileName = baseName + "." + exportExtension(result.artifactKind());
    writeFile(request, view, fileName, result.content());
    artifacts.add(new BuildArtifact(result.artifactKind(), view + "/" + fileName));
  }

  private static void writeFile(
      BuildRequest request, String view, String fileName, String content) {
    Path target = request.outDir().resolve(view).resolve(fileName);
    try {
      Files.createDirectories(target.getParent());
      Files.writeString(target, content);
    } catch (IOException error) {
      throw new UncheckedIOException("failed to write build artifact " + target, error);
    }
  }

  private static String renderExtension(String artifactKind) {
    return switch (artifactKind) {
      case "svg" -> "svg";
      case "html" -> "html";
      default -> artifactKind;
    };
  }

  private static String exportExtension(String artifactKind) {
    int plus = artifactKind.lastIndexOf('+');
    String suffix = plus >= 0 ? artifactKind.substring(plus + 1) : artifactKind;
    return switch (suffix) {
      case "json" -> "json";
      case "text" -> "txt";
      default -> "xml";
    };
  }

  private static ViewBuild failedView(
      String view, List<BuildArtifact> artifacts, List<Diagnostic> diagnostics, int exitCode) {
    return new ViewBuild(
        new BuildViewOutcome(view, EnvelopeStatus.ERROR, artifacts, diagnostics), exitCode);
  }

  private static PluginRunOutcome buildLevelError(Diagnostic diagnostic) {
    return buildLevelError(List.of(diagnostic));
  }

  private static PluginRunOutcome buildLevelError(List<Diagnostic> diagnostics) {
    return outcome(
        new BuildResult(
            ContractVersions.BUILD_RESULT_SCHEMA_VERSION,
            EnvelopeStatus.ERROR,
            List.of(),
            diagnostics),
        CommandExitCode.INPUT_ERROR.code());
  }

  private static PluginRunOutcome outcome(BuildResult result, int exitCode) {
    try {
      return new PluginRunOutcome(JsonSupport.objectMapper().writeValueAsString(result), exitCode);
    } catch (RuntimeException error) {
      throw new IllegalStateException("build result should serialize", error);
    }
  }

  @FunctionalInterface
  private interface StageCall {
    PluginRunOutcome run() throws PluginExecutionException;
  }

  private record StageOutcome(
      PluginRunOutcome outcome, JsonNode data, boolean failed, boolean warning, int exitCode) {
    static StageOutcome crashed(int exitCode) {
      return new StageOutcome(null, null, true, false, exitCode);
    }
  }

  private record ViewBuild(BuildViewOutcome outcome, int failureExit) {}
}

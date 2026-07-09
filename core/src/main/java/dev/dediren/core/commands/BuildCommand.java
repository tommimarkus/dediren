package dev.dediren.core.commands;

import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.build.BuildArtifact;
import dev.dediren.contracts.build.BuildResult;
import dev.dediren.contracts.build.BuildViewOutcome;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.engine.EngineDispatch;
import dev.dediren.core.plugins.PluginExecutionException;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.core.source.SourceValidator;
import dev.dediren.core.source.ValidationResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.ExportEngine;
import dev.dediren.engine.LayoutEngine;
import dev.dediren.engine.RenderEngine;
import dev.dediren.engine.SemanticsEngine;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LaidOutSceneMapper;
import dev.dediren.ir.LayoutRequestMapper;
import dev.dediren.ir.SceneGraph;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The in-memory {@code build} driver (decision 14): it validates a source document once, then for
 * every selected view pipes the typed IR straight through the stages with no per-stage JSON
 * round-trip — {@link SemanticsEngine#projectScene projectScene} → {@link LayoutEngine#layout
 * layout} → {@link CoreCommands#validateLayout layout-quality validation} → an optional render lane
 * ({@link SemanticsEngine#projectRenderMetadata render-metadata} + {@link RenderEngine#render
 * render}) and/or export lanes ({@code archimate-oef}, {@code uml-xmi}) — writing each lane's
 * artifact under {@code outDir}. The export lanes are record-based, so build maps its in-memory
 * {@link LaidOutScene} to a {@link LayoutResult} via {@link LaidOutSceneMapper#toResult} to
 * assemble the {@link ExportRequest}. Only the {@code --emit} seam serializes a stage: it reuses
 * the same envelope serialization the standalone commands use ({@link EngineDispatch#envelope}), so
 * an emitted stage file is byte-identical to what {@code dediren project}/{@code dediren layout}
 * print.
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
      ViewBuild built = buildView(request, engines, source, view);
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

  private static ViewBuild buildView(
      BuildRequest request, Engines engines, SourceDocument source, String view) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    List<BuildArtifact> artifacts = new ArrayList<>();
    boolean warning = false;

    // Stage 1: semantics projection -> SceneGraph, piped in memory into layout with no
    // re-serialize.
    InMemoryStage<SceneGraph> projection =
        runStage(
            diagnostics,
            () -> {
              SemanticsEngine engine =
                  EngineDispatch.requireEngine(
                      engines,
                      SEMANTICS_ENGINE,
                      "projection",
                      engines.semanticsEngine(SEMANTICS_ENGINE));
              return EngineDispatch.dispatchInMemory(
                  SEMANTICS_ENGINE, () -> engine.projectScene(source, view));
            });
    if (projection.failed()) {
      return failedView(view, artifacts, diagnostics, projection.exitCode());
    }
    SceneGraph scene = projection.value();
    emitEnvelope(
        request,
        view,
        EMIT_LAYOUT_REQUEST,
        "layout-request.json",
        LayoutRequestMapper.toRequest(scene),
        projection.stageDiagnostics());
    warning |= projection.warning();

    // Stage 2: layout -> LaidOutScene.
    InMemoryStage<LaidOutScene> layout =
        runStage(
            diagnostics,
            () -> {
              LayoutEngine engine =
                  EngineDispatch.requireEngine(
                      engines, LAYOUT_ENGINE, "layout", engines.layoutEngine(LAYOUT_ENGINE));
              return EngineDispatch.dispatchInMemory(LAYOUT_ENGINE, () -> engine.layout(scene));
            });
    if (layout.failed()) {
      return failedView(view, artifacts, diagnostics, layout.exitCode());
    }
    LaidOutScene laid = layout.value();
    LayoutResult layoutRecord = LaidOutSceneMapper.toResult(laid);
    emitEnvelope(
        request,
        view,
        EMIT_LAYOUT_RESULT,
        "layout-result.json",
        layoutRecord,
        layout.stageDiagnostics());
    warning |= layout.warning();

    // Stage 3: layout-quality validation over the mapped record (unchanged verdict semantics).
    ValidationResult quality = CoreCommands.validateLayout(layoutRecord);
    diagnostics.addAll(quality.envelope().diagnostics());
    if (quality.envelope().status() == EnvelopeStatus.ERROR) {
      return failedView(view, artifacts, diagnostics, quality.exitCode());
    }
    warning |= quality.envelope().status() == EnvelopeStatus.WARNING;

    // Stage 4: render lane.
    if (request.renderPolicyText() != null) {
      InMemoryStage<RenderMetadata> metadata =
          runStage(
              diagnostics,
              () -> {
                SemanticsEngine engine =
                    EngineDispatch.requireEngine(
                        engines,
                        SEMANTICS_ENGINE,
                        "projection",
                        engines.semanticsEngine(SEMANTICS_ENGINE));
                return EngineDispatch.dispatchInMemory(
                    SEMANTICS_ENGINE, () -> engine.projectRenderMetadata(source, view));
              });
      if (metadata.failed()) {
        return failedView(view, artifacts, diagnostics, metadata.exitCode());
      }
      RenderMetadata renderMetadata = metadata.value();
      emitEnvelope(
          request,
          view,
          EMIT_RENDER_METADATA,
          "render-metadata.json",
          renderMetadata,
          metadata.stageDiagnostics());
      warning |= metadata.warning();

      InMemoryStage<RenderResult> render =
          runStage(
              diagnostics,
              () -> {
                JsonNode policy = CoreCommands.parseJson("render", request.renderPolicyText());
                RenderEngine engine =
                    EngineDispatch.requireEngine(
                        engines, RENDER_ENGINE, "render", engines.renderEngine(RENDER_ENGINE));
                return EngineDispatch.dispatchInMemory(
                    RENDER_ENGINE, () -> engine.render(laid, policy, renderMetadata));
              });
      if (render.failed()) {
        return failedView(view, artifacts, diagnostics, render.exitCode());
      }
      warning |= render.warning();
      writeRenderArtifacts(request, view, render.value(), artifacts);
    }

    // Stage 5: ArchiMate/OEF export lane (map laid -> LayoutResult for the record ExportRequest).
    if (request.oefPolicyText() != null) {
      InMemoryStage<ExportResult> oef =
          runExportStage(
              request,
              engines,
              source,
              layoutRecord,
              OEF_ENGINE,
              request.oefPolicyText(),
              diagnostics);
      if (oef.failed()) {
        return failedView(view, artifacts, diagnostics, oef.exitCode());
      }
      warning |= oef.warning();
      writeExportArtifact(request, view, "oef", oef.value(), artifacts);
    }

    // Stage 6: UML/XMI export lane.
    if (request.xmiPolicyText() != null) {
      InMemoryStage<ExportResult> xmi =
          runExportStage(
              request,
              engines,
              source,
              layoutRecord,
              XMI_ENGINE,
              request.xmiPolicyText(),
              diagnostics);
      if (xmi.failed()) {
        return failedView(view, artifacts, diagnostics, xmi.exitCode());
      }
      warning |= xmi.warning();
      writeExportArtifact(request, view, "xmi", xmi.value(), artifacts);
    }

    EnvelopeStatus status = warning ? EnvelopeStatus.WARNING : EnvelopeStatus.OK;
    return new ViewBuild(
        new BuildViewOutcome(view, status, artifacts, diagnostics), CommandExitCode.OK.code());
  }

  private static InMemoryStage<ExportResult> runExportStage(
      BuildRequest request,
      Engines engines,
      SourceDocument source,
      LayoutResult layoutRecord,
      String engineId,
      String policyText,
      List<Diagnostic> diagnostics) {
    return runStage(
        diagnostics,
        () -> {
          JsonNode policy = CoreCommands.parseJson("export", policyText);
          ExportEngine engine =
              EngineDispatch.requireEngine(
                  engines, engineId, "export", engines.exportEngine(engineId));
          // Decision 9: a relative schema/cache env path resolves against the product root, not the
          // JVM cwd. Resolving it here (outside the dispatch invocation) mirrors the standalone
          // export command, so a lookup failure surfaces the same way rather than as ENGINE_FAILED.
          Path productRoot = DedirenPaths.productRoot();
          ExportRequest exportRequest =
              new ExportRequest(
                  ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION, source, layoutRecord, policy);
          return EngineDispatch.dispatchInMemory(
              engineId, () -> engine.export(exportRequest, request.env(), productRoot));
        });
  }

  /**
   * Runs one in-memory stage, folding the two published stage failures into the per-view
   * diagnostics exactly as the process CLI did: a structured {@link PluginExecutionException}
   * (unknown engine, unsupported capability, invalid policy JSON, or an unexpected engine failure)
   * folds with a {@code PLUGIN_ERROR} exit; a raw {@link UncheckedIOException} structural failure
   * (an unresolvable {@code --views} entry) folds with an {@code INPUT_ERROR} exit so it never
   * aborts the other views. On success the stage's own diagnostics are appended and its warning bit
   * is the same the success envelope would carry (any non-info diagnostic).
   */
  private static <T> InMemoryStage<T> runStage(List<Diagnostic> diagnostics, InMemoryCall<T> call) {
    EngineDispatch.InMemoryOutcome<T> outcome;
    try {
      outcome = call.run();
    } catch (PluginExecutionException error) {
      diagnostics.add(error.diagnostic());
      return InMemoryStage.crashed(CommandExitCode.PLUGIN_ERROR.code());
    } catch (UncheckedIOException error) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.COMMAND_INPUT_INVALID.code(),
              DiagnosticSeverity.ERROR,
              error.getCause() != null ? error.getCause().getMessage() : error.getMessage(),
              "command:build"));
      return InMemoryStage.crashed(CommandExitCode.INPUT_ERROR.code());
    }
    return switch (outcome) {
      case EngineDispatch.InMemoryOutcome.Value<T> value -> {
        List<Diagnostic> stageDiagnostics = value.result().diagnostics();
        diagnostics.addAll(stageDiagnostics);
        boolean warned =
            stageDiagnostics.stream().anyMatch(d -> d.severity() != DiagnosticSeverity.INFO);
        yield new InMemoryStage<>(
            value.result().value(), stageDiagnostics, false, warned, CommandExitCode.OK.code());
      }
      case EngineDispatch.InMemoryOutcome.Failure<T> failure -> {
        diagnostics.addAll(failure.diagnostics());
        yield new InMemoryStage<>(null, List.of(), true, false, failure.exitCode());
      }
    };
  }

  private static void emitEnvelope(
      BuildRequest request,
      String view,
      String key,
      String fileName,
      Object data,
      List<Diagnostic> stageDiagnostics) {
    if (request.emit().contains(key)) {
      writeFile(request, view, fileName, EngineDispatch.envelope(data, stageDiagnostics));
    }
  }

  private static void writeRenderArtifacts(
      BuildRequest request, String view, RenderResult result, List<BuildArtifact> artifacts) {
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
      ExportResult result,
      List<BuildArtifact> artifacts) {
    String fileName = baseName + "." + exportExtension(result.artifactKind());
    writeFile(request, view, fileName, result.content());
    artifacts.add(new BuildArtifact(result.artifactKind(), view + "/" + fileName));
  }

  private static void writeFile(
      BuildRequest request, String view, String fileName, String content) {
    Path target = request.outDir().resolve(view).resolve(fileName);
    try {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
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
    // Every export-result artifact_kind matches the export-result schema pattern
    // "^[a-z0-9][a-z0-9.-]*\+(xml|json|text)$", so a '+' media suffix is always present; the text
    // after the last '+' selects the file extension. There is no no-'+' fallback branch: it is
    // unreachable for schema-valid input, and substring(lastIndexOf('+') + 1) already yields the
    // whole string (== the former fallback) if a '+' were ever absent.
    String suffix = artifactKind.substring(artifactKind.lastIndexOf('+') + 1);
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
  private interface InMemoryCall<T> {
    EngineDispatch.InMemoryOutcome<T> run() throws PluginExecutionException;
  }

  private record InMemoryStage<T>(
      T value, List<Diagnostic> stageDiagnostics, boolean failed, boolean warning, int exitCode) {
    static <T> InMemoryStage<T> crashed(int exitCode) {
      return new InMemoryStage<>(null, List.of(), true, false, exitCode);
    }
  }

  private record ViewBuild(BuildViewOutcome outcome, int failureExit) {}
}

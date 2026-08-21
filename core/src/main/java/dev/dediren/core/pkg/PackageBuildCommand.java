package dev.dediren.core.pkg;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.KnownSchemaVersions;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.pkg.PackageBuildResult;
import dev.dediren.contracts.pkg.PackageDocument;
import dev.dediren.contracts.pkg.PackageDocumentPresentation;
import dev.dediren.contracts.pkg.PackageExport;
import dev.dediren.contracts.pkg.PackageExportLane;
import dev.dediren.contracts.pkg.PackageExportOutcome;
import dev.dediren.contracts.pkg.PackageModel;
import dev.dediren.contracts.pkg.PackageOutputs;
import dev.dediren.contracts.pkg.PackagePresentation;
import dev.dediren.contracts.pkg.PackageView;
import dev.dediren.contracts.pkg.PackageViewOutcome;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.analysis.CanonicalJson;
import dev.dediren.core.analysis.Provenance;
import dev.dediren.core.artifact.ArtifactSink;
import dev.dediren.core.artifact.ExportLane;
import dev.dediren.core.commands.CoreCommands;
import dev.dediren.core.engine.EngineDispatch;
import dev.dediren.core.engine.EngineExecutionException;
import dev.dediren.core.engine.EngineRunOutcome;
import dev.dediren.core.io.BoundedReads;
import dev.dediren.core.io.ConfinedPaths;
import dev.dediren.core.schema.SchemaValidator;
import dev.dediren.core.schema.SchemaVersionGate;
import dev.dediren.core.source.SourceLimits;
import dev.dediren.core.source.SourceValidator;
import dev.dediren.core.source.ValidationResult;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.Engines;
import dev.dediren.engine.ExportEngine;
import dev.dediren.engine.LayoutEngine;
import dev.dediren.engine.ModelExportRequest;
import dev.dediren.engine.RenderEngine;
import dev.dediren.engine.SemanticsEngine;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LaidOutSceneMapper;
import dev.dediren.ir.SceneGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The in-memory package build driver: it schema-validates a package document, checks its
 * cross-references and declared-output topology ({@link PackageValidator}), then for every selected
 * view pipes the typed IR through the same per-view engine pipeline {@code build} uses — {@link
 * SemanticsEngine#projectScene projectScene} → {@link LayoutEngine#layout layout} → layout-quality
 * validation → render — but rendering with an <em>effective</em> policy that carries the view's
 * presentation as accessible-name text, and writing each artifact to its declared output path
 * rather than a fixed view-major layout. Exports route to a view (the per-view lane) or a whole
 * model (the aggregate lane) and land at their declared paths too.
 *
 * <p>Unlike {@code build}'s bare {@code BuildResult} stdout, the whole thing rides inside a
 * standard {@link CommandEnvelope} whose {@code data} is a {@link PackageBuildResult}: one
 * top-level shape for success and every failure class alike, so a caller branches once. The
 * per-view engines and the {@code EngineWiring} that constructs them are untouched — this is
 * orchestration over the existing seam.
 */
public final class PackageBuildCommand {

  private static final String SEMANTICS_ENGINE = "generic-graph";
  private static final String LAYOUT_ENGINE = "elk-layout";
  private static final String RENDER_ENGINE = "render";

  /**
   * Mirror of the twin driver's {@code BuildCommand.CLASS_FAMILY_KINDS} (private there): diagram
   * kinds whose model content is classifier-based and composes safely in one aggregate document.
   * Other UML families (activity, sequence, …) have element writers that walk the full source node
   * list, so mixing them into one model section collides xmi:ids — they are excluded from the
   * whole-model UMLDI aggregate for now (class-family first).
   */
  private static final Set<GenericGraphViewKind> CLASS_FAMILY_KINDS =
      Set.of(GenericGraphViewKind.UML_CLASS, GenericGraphViewKind.UML_DATA);

  private PackageBuildCommand() {}

  public static EngineRunOutcome run(PackageBuildRequest request, Engines engines)
      throws EngineExecutionException {
    JsonNode node;
    try {
      node = JsonSupport.objectMapper().readTree(request.packageText());
    } catch (JacksonException error) {
      return enveloped(
          errorResult(List.of(diag(DiagnosticCode.SCHEMA_INVALID, "package is not valid JSON"))),
          CommandExitCode.INPUT_ERROR.code());
    }

    List<String> schemaErrors =
        SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
            .validate("schemas/package.schema.json", node);
    if (!schemaErrors.isEmpty()) {
      return enveloped(
          errorResult(
              schemaErrors.stream().map(m -> diag(DiagnosticCode.SCHEMA_INVALID, m)).toList()),
          CommandExitCode.INPUT_ERROR.code());
    }

    PackageDocument pkg = JsonSupport.objectMapper().treeToValue(node, PackageDocument.class);

    List<Diagnostic> semantic = PackageValidator.validate(pkg);
    if (!semantic.isEmpty()) {
      return enveloped(errorResult(semantic), CommandExitCode.INPUT_ERROR.code());
    }

    List<PackageView> selected = selectViews(pkg, request.views());
    if (selected == null) {
      return enveloped(
          errorResult(
              List.of(
                  diag(
                      DiagnosticCode.PACKAGE_VIEW_UNKNOWN,
                      "the --views filter names a view the package does not declare"))),
          CommandExitCode.INPUT_ERROR.code());
    }

    // Pre-load every referenced model once, so a view and a model-target export that share a model
    // parse it a single time and a load failure is reported once.
    Map<String, ModelLoad> models = new LinkedHashMap<>();
    for (PackageView view : selected) {
      loadInto(models, resolveModelId(view, pkg), pkg, request);
    }
    if (!request.noExport()) {
      for (PackageExport export : pkg.exports()) {
        if (export.model() != null) {
          loadInto(models, export.model(), pkg, request);
        }
      }
    }

    List<PackageViewOutcome> viewOutcomes = new ArrayList<>();
    Map<String, ViewContext> builtViews = new LinkedHashMap<>();
    Map<String, List<ModelExportRequest.ViewLayout>> layoutsByModel = new LinkedHashMap<>();
    int failureExit = CommandExitCode.OK.code();

    for (PackageView view : selected) {
      String modelId = resolveModelId(view, pkg);
      ModelLoad model = models.get(modelId);
      if (model.failed()) {
        viewOutcomes.add(
            failedView(view, Map.of(), model.diagnostics(), CommandExitCode.INPUT_ERROR.code())
                .outcome());
        failureExit = worst(failureExit, CommandExitCode.INPUT_ERROR.code());
        continue;
      }
      ViewRun run = buildView(request, engines, model, view, pkg.presentation());
      viewOutcomes.add(run.outcome());
      if (run.outcome().status() == EnvelopeStatus.ERROR) {
        failureExit = worst(failureExit, run.exitCode());
      } else if (run.layout() != null) {
        builtViews.put(view.id(), new ViewContext(model, run.layout()));
        layoutsByModel
            .computeIfAbsent(modelId, id -> new ArrayList<>())
            .add(new ModelExportRequest.ViewLayout(view.id(), run.layout()));
      }
    }

    List<PackageExportOutcome> exportOutcomes = new ArrayList<>();
    if (!request.noExport()) {
      for (PackageExport export : pkg.exports()) {
        ExportRun run = runExport(request, engines, models, builtViews, layoutsByModel, export);
        exportOutcomes.add(run.outcome());
        if (run.outcome().status() == EnvelopeStatus.ERROR) {
          failureExit = worst(failureExit, run.exitCode());
        }
      }
    }

    boolean anyError =
        viewOutcomes.stream().anyMatch(v -> v.status() == EnvelopeStatus.ERROR)
            || exportOutcomes.stream().anyMatch(e -> e.status() == EnvelopeStatus.ERROR);
    boolean anyWarning =
        viewOutcomes.stream().anyMatch(v -> v.status() == EnvelopeStatus.WARNING)
            || exportOutcomes.stream().anyMatch(e -> e.status() == EnvelopeStatus.WARNING);
    EnvelopeStatus status =
        anyError ? EnvelopeStatus.ERROR : anyWarning ? EnvelopeStatus.WARNING : EnvelopeStatus.OK;
    int exitCode = anyError ? failureExit : CommandExitCode.OK.code();

    PackageBuildResult result =
        new PackageBuildResult(
            ContractVersions.PACKAGE_BUILD_RESULT_SCHEMA_VERSION,
            status,
            pkg.presentation(),
            viewOutcomes,
            exportOutcomes,
            List.of());
    return enveloped(result, exitCode);
  }

  // --- per-view pipeline ------------------------------------------------------------------------

  private static ViewRun buildView(
      PackageBuildRequest request,
      Engines engines,
      ModelLoad model,
      PackageView view,
      PackageDocumentPresentation packageLevel) {
    SourceDocument source = model.source();
    List<Diagnostic> diagnostics = new ArrayList<>();
    Map<String, String> artifacts = new LinkedHashMap<>();
    boolean warning = false;
    String viewId = view.id();

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
                  SEMANTICS_ENGINE, () -> engine.projectScene(source, viewId));
            });
    if (projection.failed()) {
      return failedView(view, artifacts, diagnostics, projection.exitCode());
    }
    warning |= projection.warning();
    SceneGraph scene = projection.value();

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
    warning |= layout.warning();
    LaidOutScene laid = layout.value();
    LayoutResult layoutRecord = LaidOutSceneMapper.toResult(laid);

    ValidationResult quality = CoreCommands.validateLayout(laid, layoutRecord);
    diagnostics.addAll(quality.envelope().diagnostics());
    if (quality.envelope().status() == EnvelopeStatus.ERROR) {
      return failedView(view, artifacts, diagnostics, quality.exitCode());
    }
    warning |= quality.envelope().status() == EnvelopeStatus.WARNING;

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
                  SEMANTICS_ENGINE, () -> engine.projectRenderMetadata(source, viewId));
            });
    if (metadata.failed()) {
      return failedView(view, artifacts, diagnostics, metadata.exitCode());
    }
    warning |= metadata.warning();
    RenderMetadata renderMetadata = metadata.value();

    JsonNode renderPolicy;
    try {
      renderPolicy = readAndGate(request, view.renderPolicy(), KnownSchemaVersions.RENDER_POLICY);
    } catch (InputProblem problem) {
      diagnostics.add(problem.diagnostic());
      return failedView(view, artifacts, diagnostics, problem.exitCode());
    }
    JsonNode effectivePolicy =
        effectiveRenderPolicy(renderPolicy, view.presentation(), packageLevel);

    InMemoryStage<RenderResult> render =
        runStage(
            diagnostics,
            () -> {
              RenderEngine engine =
                  EngineDispatch.requireEngine(
                      engines, RENDER_ENGINE, "render", engines.renderEngine(RENDER_ENGINE));
              return EngineDispatch.dispatchInMemory(
                  RENDER_ENGINE, () -> engine.render(laid, effectivePolicy, renderMetadata));
            });
    if (render.failed()) {
      return failedView(view, artifacts, diagnostics, render.exitCode());
    }
    warning |= render.warning();

    PackageOutputs outputs = view.outputs();
    try {
      var renderArtifacts = render.value().artifacts();
      // Stamped like the twin's render lane: the model's canonical hash plus the hash of the
      // policy the renderer actually consumed (the effective policy, presentation folded in). The
      // layout and render-metadata JSON stay unstamped, exactly as the twin's --emit stage files.
      // An empty artifact list writes an empty diagram unstamped, as before; with no artifact there
      // is no kind to choose a stamping rule from.
      String diagram =
          renderArtifacts.isEmpty()
              ? ""
              : ArtifactSink.stamp(
                      renderArtifacts.getFirst().artifactKind(),
                      renderArtifacts.getFirst().content(),
                      Provenance.payload(
                          source.modelSchemaVersion(),
                          model.sha(),
                          viewId,
                          "render_policy_sha256",
                          CanonicalJson.sha256(effectivePolicy),
                          dedirenVersion()))
                  .content();
      writeOutput(request, outputs.diagram(), diagram);
      artifacts.put("diagram", outputs.diagram());
      if (outputs.renderMetadata() != null) {
        writeOutput(
            request, outputs.renderMetadata(), JsonSupport.writeValueAsString(renderMetadata));
        artifacts.put("render_metadata", outputs.renderMetadata());
      }
      if (outputs.layout() != null) {
        writeOutput(request, outputs.layout(), JsonSupport.writeValueAsString(layoutRecord));
        artifacts.put("layout", outputs.layout());
      }
    } catch (InputProblem problem) {
      diagnostics.add(problem.diagnostic());
      return failedView(view, artifacts, diagnostics, problem.exitCode());
    }

    EnvelopeStatus status = warning ? EnvelopeStatus.WARNING : EnvelopeStatus.OK;
    return new ViewRun(
        new PackageViewOutcome(viewId, status, artifacts, view.presentation(), diagnostics),
        layoutRecord,
        CommandExitCode.OK.code());
  }

  // --- export lanes -----------------------------------------------------------------------------

  private static ExportRun runExport(
      PackageBuildRequest request,
      Engines engines,
      Map<String, ModelLoad> models,
      Map<String, ViewContext> builtViews,
      Map<String, List<ModelExportRequest.ViewLayout>> layoutsByModel,
      PackageExport export) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    String engineId = ExportLane.of(export.lane()).engineId();
    KnownSchemaVersions.Family family =
        export.lane() == PackageExportLane.ARCHIMATE_OEF
            ? KnownSchemaVersions.OEF_EXPORT_POLICY
            : KnownSchemaVersions.UML_XMI_EXPORT_POLICY;

    JsonNode policy;
    try {
      policy = readAndGate(request, export.policy(), family);
    } catch (InputProblem problem) {
      diagnostics.add(problem.diagnostic());
      return failedExport(export, diagnostics, problem.exitCode());
    }

    InMemoryStage<ExportResult> stage;
    ModelLoad owner;
    String stampViewId;
    if (export.view() != null) {
      ViewContext context = builtViews.get(export.view());
      if (context == null) {
        diagnostics.add(
            diag(
                DiagnosticCode.PACKAGE_VIEW_UNKNOWN,
                "export '"
                    + export.id()
                    + "' cannot run: view '"
                    + export.view()
                    + "' did not build"));
        return failedExport(export, diagnostics, CommandExitCode.INPUT_ERROR.code());
      }
      owner = context.model();
      stampViewId = export.view();
      SourceDocument source = owner.source();
      LayoutResult layout = context.layout();
      stage =
          runStage(
              diagnostics,
              () -> {
                ExportEngine engine =
                    EngineDispatch.requireEngine(
                        engines, engineId, "export", engines.exportEngine(engineId));
                ExportRequest exportRequest =
                    new ExportRequest(
                        ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION, source, layout, policy);
                return EngineDispatch.dispatchInMemory(
                    engineId,
                    () -> engine.export(exportRequest, request.env(), DedirenPaths.productRoot()));
              });
    } else {
      String modelId = export.model();
      ModelLoad model = models.get(modelId);
      if (model == null || model.failed()) {
        diagnostics.add(
            diag(
                DiagnosticCode.PACKAGE_MODEL_UNKNOWN,
                "export '" + export.id() + "' cannot run: model '" + modelId + "' did not load"));
        return failedExport(export, diagnostics, CommandExitCode.INPUT_ERROR.code());
      }
      owner = model;
      // The twin stamps its whole-model aggregates with the literal "model" as the view id.
      stampViewId = "model";
      // Class-family first, mirroring the twin's CLASS_FAMILY_KINDS gate in BuildCommand: only
      // classifier diagram kinds (uml-class, uml-data) feed a whole-model uml-xmi aggregate —
      // other families' element writers walk the full source node list, so a mixed union collides
      // xmi:ids and would ship provisional UMLDI for kinds outside the class family. The OEF
      // aggregate keeps every view. A kindless (generic) view is never in the class family.
      List<ModelExportRequest.ViewLayout> allLayouts =
          layoutsByModel.getOrDefault(modelId, List.of());
      List<ModelExportRequest.ViewLayout> viewLayouts =
          export.lane() == PackageExportLane.UML_XMI
              ? allLayouts.stream()
                  .filter(
                      layout -> {
                        GenericGraphViewKind kind = model.viewKinds().get(layout.viewId());
                        return kind != null && CLASS_FAMILY_KINDS.contains(kind);
                      })
                  .toList()
              : allLayouts;
      SourceDocument source = model.source();
      stage =
          runStage(
              diagnostics,
              () -> {
                ExportEngine engine =
                    EngineDispatch.requireEngine(
                        engines, engineId, "export", engines.exportEngine(engineId));
                ModelExportRequest modelRequest =
                    new ModelExportRequest(source, viewLayouts, policy);
                return EngineDispatch.dispatchInMemory(
                    engineId,
                    () ->
                        engine
                            .exportModel(modelRequest, request.env(), DedirenPaths.productRoot())
                            .orElseThrow(
                                () ->
                                    EngineException.semanticFailure(
                                        DiagnosticCode.ENGINE_FAILED.code(),
                                        "export '"
                                            + export.id()
                                            + "' produced no aggregate for model '"
                                            + modelId
                                            + "'",
                                        "$")));
              });
    }

    if (stage.failed()) {
      return failedExport(export, diagnostics, stage.exitCode());
    }
    try {
      writeOutput(
          request,
          export.output(),
          ArtifactSink.stamp(
                  stage.value().artifactKind(),
                  stage.value().content(),
                  Provenance.payload(
                      owner.source().modelSchemaVersion(),
                      owner.sha(),
                      stampViewId,
                      ExportLane.of(export.lane()).policyShaKey(),
                      CanonicalJson.sha256(policy),
                      dedirenVersion()))
              .content());
    } catch (InputProblem problem) {
      diagnostics.add(problem.diagnostic());
      return failedExport(export, diagnostics, problem.exitCode());
    }
    EnvelopeStatus status = stage.warning() ? EnvelopeStatus.WARNING : EnvelopeStatus.OK;
    return new ExportRun(
        new PackageExportOutcome(
            export.id(), export.view(), export.model(), status, export.output(), diagnostics),
        CommandExitCode.OK.code());
  }

  // --- model loading ----------------------------------------------------------------------------

  private static void loadInto(
      Map<String, ModelLoad> models, String modelId, PackageDocument pkg, PackageBuildRequest req) {
    if (models.containsKey(modelId)) {
      return;
    }
    PackageModel model =
        pkg.models().stream().filter(m -> m.id().equals(modelId)).findFirst().orElse(null);
    if (model == null) {
      // PackageValidator already rejected unknown model refs, so this is unreachable for a package
      // that passed validation; guard defensively rather than NPE.
      models.put(
          modelId,
          ModelLoad.failed(
              List.of(
                  diag(
                      DiagnosticCode.PACKAGE_MODEL_UNKNOWN,
                      "model '" + modelId + "' is not declared"))));
      return;
    }
    Path modelPath;
    try {
      modelPath = resolveInput(req, model.source());
    } catch (InputProblem problem) {
      models.put(modelId, ModelLoad.failed(List.of(problem.diagnostic())));
      return;
    }
    String text;
    try {
      text = BoundedReads.readString(modelPath, SourceLimits.DEFAULT.maxInputFileBytes());
    } catch (BoundedReads.FileTooLargeException tooLarge) {
      // Byte counts are safe to echo on both lanes; the resolved path never is on the MCP lane.
      models.put(
          modelId,
          ModelLoad.failed(
              List.of(
                  diag(
                      DiagnosticCode.INPUT_FILE_TOO_LARGE,
                      "model '" + model.source() + "': " + tooLarge.getMessage()))));
      return;
    } catch (IOException error) {
      models.put(
          modelId,
          ModelLoad.failed(
              List.of(
                  diag(
                      DiagnosticCode.COMMAND_IO_FAILED,
                      "failed to read model '" + model.source() + "'"))));
      return;
    }
    try {
      SourceDocument source =
          SourceValidator.loadAndValidateSourceDocument(
              text, modelPath.getParent(), req.confinementRoot());
      models.put(modelId, ModelLoad.loaded(source));
    } catch (SourceValidator.SourceDiagnosticsException error) {
      models.put(modelId, ModelLoad.failed(error.diagnostics()));
    }
  }

  // --- policy / accessibility -------------------------------------------------------------------

  private static JsonNode readAndGate(
      PackageBuildRequest request, String relPath, KnownSchemaVersions.Family family)
      throws InputProblem {
    Path path = resolveInput(request, relPath);
    String text;
    try {
      text = BoundedReads.readString(path, SourceLimits.DEFAULT.maxInputFileBytes());
    } catch (BoundedReads.FileTooLargeException tooLarge) {
      throw new InputProblem(
          diag(
              DiagnosticCode.INPUT_FILE_TOO_LARGE,
              "policy '" + relPath + "': " + tooLarge.getMessage()),
          CommandExitCode.INPUT_ERROR.code());
    } catch (IOException error) {
      // An unreadable policy is caller-fixable input, like an unreadable model on this lane and
      // like the CLI single-model lane's policy-read mapping: COMMAND_IO_FAILED + INPUT_ERROR.
      throw new InputProblem(
          diag(DiagnosticCode.COMMAND_IO_FAILED, "failed to read policy '" + relPath + "'"),
          CommandExitCode.INPUT_ERROR.code());
    }
    JsonNode node;
    try {
      node = JsonSupport.objectMapper().readTree(text);
    } catch (JacksonException error) {
      // Deliberate divergence from the twin's commented LEGACY PLUGIN_ERROR mapping for malformed
      // policy JSON: on this lane's contract, a policy that is not even JSON is an input error.
      throw new InputProblem(
          diag(DiagnosticCode.SCHEMA_INVALID, "policy '" + relPath + "' is not valid JSON"),
          CommandExitCode.INPUT_ERROR.code());
    }
    Optional<Diagnostic> stale = SchemaVersionGate.check(family, node);
    if (stale.isPresent()) {
      throw new InputProblem(stale.get(), CommandExitCode.INPUT_ERROR.code());
    }
    return node;
  }

  /**
   * The render policy with the view's presentation folded in as accessible-name text and the
   * package's language metadata folded in alongside it, each only when the policy has not already
   * set that key (an explicit policy value always wins). The renderer emits {@code
   * accessibility.title}/{@code .description} as {@code <title>}/{@code <desc>} and {@code
   * accessibility.lang}/{@code .dir} as {@code xml:lang}/{@code direction} on the root; together
   * these give each view its own accessible name, in a declared language, under a shared policy.
   *
   * <p>The two sources are scoped differently on purpose: the accessible name varies per view, so
   * it comes from {@code views[].presentation}; the language and base direction of that prose do
   * not, so they come from the package once.
   */
  private static JsonNode effectiveRenderPolicy(
      JsonNode base, PackagePresentation presentation, PackageDocumentPresentation packageLevel) {
    boolean hasViewText =
        presentation != null && (presentation.title() != null || presentation.question() != null);
    boolean hasPackageLanguage =
        packageLevel != null && (packageLevel.lang() != null || packageLevel.dir() != null);
    if ((!hasViewText && !hasPackageLanguage) || !base.isObject()) {
      return base;
    }
    ObjectNode copy = (ObjectNode) base.deepCopy();
    ObjectNode accessibility =
        copy.has("accessibility") && copy.get("accessibility").isObject()
            ? (ObjectNode) copy.get("accessibility")
            : copy.putObject("accessibility");
    if (hasViewText) {
      if (presentation.title() != null && !accessibility.has("title")) {
        accessibility.put("title", presentation.title());
      }
      if (presentation.question() != null && !accessibility.has("description")) {
        accessibility.put("description", presentation.question());
      }
    }
    if (hasPackageLanguage) {
      if (packageLevel.lang() != null && !accessibility.has("lang")) {
        accessibility.put("lang", packageLevel.lang());
      }
      if (packageLevel.dir() != null && !accessibility.has("dir")) {
        accessibility.put("dir", packageLevel.dir());
      }
    }
    return copy;
  }

  // --- confined path IO -------------------------------------------------------------------------

  private static Path resolveInput(PackageBuildRequest request, String relPath)
      throws InputProblem {
    try {
      return ConfinedPaths.resolveExisting(
          request.confinementRoot(), request.baseDir().resolve(relPath));
    } catch (ConfinedPaths.PathEscapeException error) {
      throw new InputProblem(
          diag(
              DiagnosticCode.COMMAND_INPUT_INVALID,
              "path '" + relPath + "' resolves outside the package root"),
          CommandExitCode.INPUT_ERROR.code());
    }
  }

  private static void writeOutput(PackageBuildRequest request, String relPath, String content)
      throws InputProblem {
    Path target;
    try {
      target =
          ConfinedPaths.resolveAnchored(
              request.confinementRoot(), request.baseDir().resolve(relPath));
    } catch (ConfinedPaths.PathEscapeException error) {
      throw new InputProblem(
          diag(
              DiagnosticCode.COMMAND_INPUT_INVALID,
              "output path '" + relPath + "' resolves outside the package root"),
          CommandExitCode.INPUT_ERROR.code());
    }
    try {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(target, content);
    } catch (IOException error) {
      // An artifact write failure (unwritable target, disk full) is caller-fixable input exactly
      // as in the twin: it folds into a per-outcome error that never aborts the other views, with
      // COMMAND_IO_FAILED + INPUT_ERROR.
      throw new InputProblem(
          diag(DiagnosticCode.COMMAND_IO_FAILED, "failed to write output '" + relPath + "'"),
          CommandExitCode.INPUT_ERROR.code());
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  private static List<PackageView> selectViews(PackageDocument pkg, List<String> filter) {
    if (filter.isEmpty()) {
      return pkg.views();
    }
    Set<String> wanted = new LinkedHashSet<>(filter);
    Set<String> present = new LinkedHashSet<>();
    pkg.views().forEach(view -> present.add(view.id()));
    if (!present.containsAll(wanted)) {
      return null;
    }
    return pkg.views().stream().filter(view -> wanted.contains(view.id())).toList();
  }

  private static String resolveModelId(PackageView view, PackageDocument pkg) {
    return view.model() != null ? view.model() : pkg.models().getFirst().id();
  }

  /**
   * Maps every view id a model declares to its kind, so the uml-xmi aggregate lane can be gated by
   * diagram family (mirrors the twin's {@code BuildCommand.viewKinds}).
   */
  private static Map<String, GenericGraphViewKind> viewKinds(SourceDocument source) {
    JsonNode genericGraph = source.plugins().get(SEMANTICS_ENGINE);
    if (genericGraph == null) {
      return Map.of();
    }
    try {
      GenericGraphPluginData data =
          JsonSupport.objectMapper().treeToValue(genericGraph, GenericGraphPluginData.class);
      Map<String, GenericGraphViewKind> kinds = new HashMap<>();
      for (GenericGraphView view : data.views()) {
        kinds.put(view.id(), view.kind());
      }
      return kinds;
    } catch (RuntimeException error) {
      // An unreadable view list only means no view can prove a class-family kind, so the uml-xmi
      // aggregate skips them all — the same fallback the twin takes.
      return Map.of();
    }
  }

  private static String dedirenVersion() {
    return System.getProperty("dediren.version", "unknown");
  }

  private static int worst(int current, int candidate) {
    return Math.max(current, candidate);
  }

  private static Diagnostic diag(DiagnosticCode code, String message) {
    return new Diagnostic(code.code(), DiagnosticSeverity.ERROR, message, null);
  }

  private static PackageBuildResult errorResult(List<Diagnostic> diagnostics) {
    return new PackageBuildResult(
        ContractVersions.PACKAGE_BUILD_RESULT_SCHEMA_VERSION,
        EnvelopeStatus.ERROR,
        null,
        List.of(),
        List.of(),
        diagnostics);
  }

  private static ViewRun failedView(
      PackageView view, Map<String, String> artifacts, List<Diagnostic> diagnostics, int exitCode) {
    return new ViewRun(
        new PackageViewOutcome(
            view.id(), EnvelopeStatus.ERROR, artifacts, view.presentation(), diagnostics),
        null,
        exitCode);
  }

  private static ExportRun failedExport(
      PackageExport export, List<Diagnostic> diagnostics, int exitCode) {
    return new ExportRun(
        new PackageExportOutcome(
            export.id(), export.view(), export.model(), EnvelopeStatus.ERROR, null, diagnostics),
        exitCode);
  }

  private static EngineRunOutcome enveloped(PackageBuildResult result, int exitCode) {
    CommandEnvelope<PackageBuildResult> envelope =
        new CommandEnvelope<>(
            ContractVersions.ENVELOPE_SCHEMA_VERSION, result.status(), result, List.of());
    return new EngineRunOutcome(JsonSupport.writeValueAsString(envelope), exitCode);
  }

  // --- stage running (self-contained; mirrors the build driver without touching it) -------------

  private static <T> InMemoryStage<T> runStage(List<Diagnostic> diagnostics, InMemoryCall<T> call) {
    EngineDispatch.InMemoryOutcome<T> outcome;
    try {
      outcome = call.run();
    } catch (EngineExecutionException error) {
      diagnostics.add(error.diagnostic());
      return new InMemoryStage<>(null, true, false, CommandExitCode.PLUGIN_ERROR.code());
    }
    return switch (outcome) {
      case EngineDispatch.InMemoryOutcome.Value<T> value -> {
        List<Diagnostic> stageDiagnostics = value.result().diagnostics();
        diagnostics.addAll(stageDiagnostics);
        boolean warned =
            stageDiagnostics.stream().anyMatch(d -> d.severity() != DiagnosticSeverity.INFO);
        yield new InMemoryStage<>(value.result().value(), false, warned, CommandExitCode.OK.code());
      }
      case EngineDispatch.InMemoryOutcome.Failure<T> failure -> {
        diagnostics.addAll(failure.diagnostics());
        yield new InMemoryStage<>(null, true, false, failure.exitCode());
      }
    };
  }

  @FunctionalInterface
  private interface InMemoryCall<T> {
    EngineDispatch.InMemoryOutcome<T> run() throws EngineExecutionException;
  }

  private record InMemoryStage<T>(T value, boolean failed, boolean warning, int exitCode) {}

  /**
   * One loaded model plus the provenance and gating inputs derived from it exactly once: the
   * canonical hash every stamp of the model's artifacts carries (the twin's {@code Stamps} analog)
   * and the view-id-to-kind map the class-family gate consults.
   */
  private record ModelLoad(
      SourceDocument source,
      String sha,
      Map<String, GenericGraphViewKind> viewKinds,
      List<Diagnostic> diagnostics) {

    static ModelLoad loaded(SourceDocument source) {
      return new ModelLoad(
          source,
          CanonicalJson.sha256(JsonSupport.objectMapper().valueToTree(source)),
          PackageBuildCommand.viewKinds(source),
          List.of());
    }

    static ModelLoad failed(List<Diagnostic> diagnostics) {
      return new ModelLoad(null, null, Map.of(), diagnostics);
    }

    boolean failed() {
      return source == null;
    }
  }

  private record ViewContext(ModelLoad model, LayoutResult layout) {}

  private record ViewRun(PackageViewOutcome outcome, LayoutResult layout, int exitCode) {}

  private record ExportRun(PackageExportOutcome outcome, int exitCode) {}

  private static final class InputProblem extends Exception {
    private final transient Diagnostic diagnostic;
    private final int exitCode;

    InputProblem(Diagnostic diagnostic, int exitCode) {
      this.diagnostic = diagnostic;
      this.exitCode = exitCode;
    }

    Diagnostic diagnostic() {
      return diagnostic;
    }

    int exitCode() {
      return exitCode;
    }
  }
}

package dev.dediren.core.commands;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.KnownSchemaVersions;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.engine.EngineDispatch;
import dev.dediren.core.engine.EngineExecutionException;
import dev.dediren.core.engine.EngineRunOutcome;
import dev.dediren.core.io.JsonInput;
import dev.dediren.core.quality.LayoutQuality;
import dev.dediren.core.quality.LayoutQualityReport;
import dev.dediren.core.schema.SchemaVersionGate;
import dev.dediren.core.source.DocumentValidator;
import dev.dediren.core.source.SourceValidator;
import dev.dediren.core.source.ValidationResult;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.ExportEngine;
import dev.dediren.engine.ImportEngine;
import dev.dediren.engine.LayoutEngine;
import dev.dediren.engine.RenderEngine;
import dev.dediren.engine.SemanticsEngine;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LaidOutSceneMapper;
import dev.dediren.ir.LayoutRequestMapper;
import dev.dediren.ir.SceneGraph;
import dev.dediren.ir.quality.InvariantViolation;
import dev.dediren.ir.quality.SequenceInvariants;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  /**
   * Imports external notation into the source-model envelope produced by its engine, re-gated by
   * core before it is published.
   *
   * <p>Import is the one lane that turns untrusted foreign text into a {@link SourceDocument}
   * without those bytes ever meeting the model schema or the input ceilings — the engine hands core
   * typed objects, not a file the load lanes validate. So the emitted document re-enters {@link
   * SourceValidator#gateImportedDocument}: schema version, JSON Schema, and {@link
   * dev.dediren.core.source.SourceLimits} ceilings only.
   *
   * <p>The importer stays authoritative for its own parse and construct diagnostics. A published
   * {@link dev.dediren.engine.EngineException} envelope is republished verbatim — its diagnostics
   * (including a parse diagnostic's {@code "line 2, column 6"} path, which core cannot reconstruct
   * and must not overwrite) and its exit code — and on the success lane the importer's diagnostics
   * ride the envelope whether the re-gate passes or rejects. That need for the typed document
   * between the engine call and the envelope is why this dispatches through {@link
   * EngineDispatch#dispatchInMemory} rather than {@link EngineDispatch#dispatch}; both failure
   * branches keep the shapes that dispatch would have produced.
   */
  public static EngineRunOutcome importCommand(
      String engineId, String source, Map<String, String> env, Engines engines)
      throws EngineExecutionException {
    ImportEngine importer =
        EngineDispatch.requireEngine(engines, engineId, "import", engines.importEngine(engineId));
    EngineDispatch.InMemoryOutcome<SourceDocument> outcome =
        EngineDispatch.dispatchInMemory(engineId, () -> importer.importSource(source));
    return switch (outcome) {
      case EngineDispatch.InMemoryOutcome.Value<SourceDocument> value ->
          gatedImportOutcome(value.result());
      case EngineDispatch.InMemoryOutcome.Failure<SourceDocument> failure ->
          errorOutcome(failure.diagnostics(), failure.exitCode());
    };
  }

  private static EngineRunOutcome gatedImportOutcome(EngineResult<SourceDocument> result) {
    SourceDocument gated;
    try {
      gated = SourceValidator.gateImportedDocument(result.value());
    } catch (SourceValidator.SourceDiagnosticsException error) {
      // PLUGIN_ERROR, whose contract is exactly "produced invalid output". Not INPUT_ERROR: an
      // importer enforces its own ceilings and rejects pathological text itself at exit 2, so by
      // the time a document reaches this gate the caller's input already parsed. Every rejection
      // here is therefore an engine that emitted an out-of-contract document, and telling the
      // caller to fix input that parsed cleanly would be false advice. The importer's diagnostics
      // still lead — they are the only account of the text this document came from.
      var diagnostics = new ArrayList<>(result.diagnostics());
      diagnostics.addAll(error.diagnostics());
      return errorOutcome(diagnostics, CommandExitCode.PLUGIN_ERROR.code());
    }
    // The gated (reparsed) document is what gets published, so the emitted data is exactly what
    // was validated. envelope(...) applies the one ok/warning/info policy every stage shares.
    return new EngineRunOutcome(
        EngineDispatch.envelope(gated, result.diagnostics()), CommandExitCode.OK.code());
  }

  public static EngineRunOutcome layoutCommand(
      String engineId, String inputText, Map<String, String> env, Engines engines)
      throws EngineExecutionException {
    // Unwrap a piped stage envelope to its data (the chained-workflow convenience), then gate the
    // hand-authorable request's schema version (same INPUT_ERROR shape as the policy gates,
    // before any engine is resolved): the gate rejects stale/unknown/absent versions first. A
    // request carrying the current version but otherwise invalid still routes through the
    // engine's parse entry point, so it reproduces the published DEDIREN_ELK_INPUT_INVALID_JSON
    // envelope rather than core's generic input diagnostic.
    JsonNode request = layoutRequestData(inputText);
    Optional<Diagnostic> stale =
        SchemaVersionGate.check(KnownSchemaVersions.LAYOUT_REQUEST, request);
    if (stale.isPresent()) {
      return errorOutcome(List.of(stale.get()));
    }
    byte[] bytes = layoutRequestBytes(request);
    LayoutEngine layout =
        EngineDispatch.requireEngine(engines, engineId, "layout", engines.layoutEngine(engineId));
    return EngineDispatch.dispatch(
        engineId,
        () -> {
          EngineResult<LaidOutScene> laid = layout.layout(layout.parseRequest(bytes));
          return new EngineResult<>(LaidOutSceneMapper.toResult(laid.value()), laid.diagnostics());
        });
  }

  private static JsonNode layoutRequestData(String inputText) throws EngineExecutionException {
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
    return data;
  }

  private static byte[] layoutRequestBytes(JsonNode data) throws EngineExecutionException {
    try {
      return JsonSupport.objectMapper().writeValueAsBytes(data);
    } catch (RuntimeException error) {
      throw commandInputInvalid("layout", error);
    }
  }

  public static EngineRunOutcome projectCommand(
      String engineId,
      String target,
      String view,
      String inputText,
      Path baseDir,
      Map<String, String> env,
      Engines engines)
      throws EngineExecutionException {
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
    return errorOutcome(
        List.of(
            new Diagnostic(
                DiagnosticCode.COMMAND_TARGET_UNSUPPORTED.code(),
                DiagnosticSeverity.ERROR,
                "unsupported target: " + target,
                null)));
  }

  /**
   * The one place the {@code validate} dispatch decision lives: an engine id routes to that
   * engine's semantic validation (which needs a profile — and a profile needs an engine, the two
   * published usage errors), no engine runs the document gate. Both the CLI and the MCP validate
   * tool call this, so the two lanes cannot decide what "validate" means differently: the CLI
   * passes the user's {@code --plugin}/{@code --profile} verbatim, the MCP lane passes the default
   * semantics engine whenever a profile is present.
   */
  public static EngineRunOutcome validateCommand(
      String engineId,
      String profile,
      String inputText,
      Path baseDir,
      Path confinementRoot,
      Map<String, String> env,
      Engines engines)
      throws EngineExecutionException {
    if (engineId != null && profile == null) {
      return usageErrorOutcome(
          DiagnosticCode.VALIDATE_PROFILE_REQUIRED, "validate --plugin requires --profile");
    }
    if (engineId == null && profile != null) {
      return usageErrorOutcome(
          DiagnosticCode.VALIDATE_PLUGIN_REQUIRED, "validate --profile requires --plugin");
    }
    if (engineId != null) {
      return semanticValidateCommand(
          engineId, profile, inputText, baseDir, confinementRoot, env, engines);
    }
    ValidationResult result =
        DocumentValidator.validateDocument(inputText, baseDir, confinementRoot);
    return new EngineRunOutcome(
        JsonSupport.objectMapper().writeValueAsString(result.envelope()), result.exitCode());
  }

  private static EngineRunOutcome usageErrorOutcome(DiagnosticCode code, String message) {
    return new EngineRunOutcome(
        JsonSupport.objectMapper()
            .writeValueAsString(
                CommandEnvelope.error(
                    List.of(new Diagnostic(code.code(), DiagnosticSeverity.ERROR, message, null)))),
        CommandExitCode.INPUT_ERROR.code());
  }

  public static EngineRunOutcome semanticValidateCommand(
      String engineId,
      String profile,
      String inputText,
      Path baseDir,
      Map<String, String> env,
      Engines engines)
      throws EngineExecutionException {
    return semanticValidateCommand(engineId, profile, inputText, baseDir, null, env, engines);
  }

  /**
   * Confinement-aware overload. When {@code confinementRoot} is non-null (the MCP trust boundary),
   * source-fragment paths are confined to it and fragment errors are sanitized; null is the
   * unconfined CLI/human lane. See {@link
   * dev.dediren.core.source.SourceValidator#validateSourceJson(String, Path, Path)}.
   */
  public static EngineRunOutcome semanticValidateCommand(
      String engineId,
      String profile,
      String inputText,
      Path baseDir,
      Path confinementRoot,
      Map<String, String> env,
      Engines engines)
      throws EngineExecutionException {
    SourceDocument source;
    try {
      source = SourceValidator.loadAndValidateSourceDocument(inputText, baseDir, confinementRoot);
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
      LayoutResult result = JsonInput.parseCommandData(inputText, LayoutResult.class);
      return validateLayoutResult(LaidOutSceneMapper.toScene(result), result);
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
    return validateLayout(LaidOutSceneMapper.toScene(result), result);
  }

  /**
   * The quality stage for a caller that already holds the typed scene. The in-memory build does: it
   * receives a {@link LaidOutScene} from the layout engine and maps it to a record for the emit and
   * export lanes, so re-deriving the scene here (scene -> record -> scene, a whole-graph conversion
   * per built view) was pure tax. The standalone validate-layout command, which starts from JSON
   * bytes, still maps once through the overload above.
   */
  public static ValidationResult validateLayout(LaidOutScene scene, LayoutResult result) {
    try {
      return validateLayoutResult(scene, result);
    } catch (RuntimeException error) {
      return commandInputValidationResult("validate-layout", error);
    }
  }

  private static ValidationResult validateLayoutResult(LaidOutScene scene, LayoutResult result) {
    List<Diagnostic> diagnostics = new ArrayList<>(LayoutQuality.validateLayoutDiagnostics(result));
    diagnostics.addAll(sequenceInvariantDiagnostics(scene, result));
    if (!diagnostics.isEmpty()) {
      return new ValidationResult(
          CommandExitCode.INPUT_ERROR.code(), CommandEnvelope.error(diagnostics));
    }
    LayoutQualityReport report = LayoutQuality.validateLayout(result);
    JsonNode data = JsonSupport.objectMapper().valueToTree(report);
    List<Diagnostic> qualityWarnings = new ArrayList<>(LayoutQuality.layoutQualityWarnings(report));
    // Structural hygiene (duplicate ids, non-positive extents) joins the warning lane rather than
    // the hard lane above: such a layout still renders, so failing it here would newly reject input
    // build and validate-layout accept today. See LayoutQuality#layoutStructureWarnings. These
    // carry their own codes and have no data.* counterpart, so they are envelope-only.
    qualityWarnings.addAll(LayoutQuality.layoutStructureWarnings(result));
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
  private static List<Diagnostic> sequenceInvariantDiagnostics(
      LaidOutScene scene, LayoutResult result) {
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

  public static EngineRunOutcome renderCommand(
      String engineId,
      String policyText,
      String metadataText,
      String layoutText,
      Map<String, String> env,
      Engines engines)
      throws EngineExecutionException {
    LayoutResult layoutResult = parseCommandData("render", layoutText, LayoutResult.class);
    JsonNode policy;
    try {
      policy = parsePolicy("render", policyText, KnownSchemaVersions.RENDER_POLICY);
    } catch (PolicyVersionException error) {
      // A stale/unknown policy version is an input error the caller can fix, and no engine has
      // run yet -- publish the gate's diagnostic verbatim (code, message, and its "$.<field>"
      // path) at INPUT_ERROR, exactly like SourceValidator.SourceDiagnosticsException below, rather
      // than laundering it through EngineExecutionException.command (which would overwrite both).
      return errorOutcome(List.of(error.diagnostic()));
    }
    RenderMetadata metadata =
        metadataText == null
            ? null
            : parseCommandData("render", metadataText, RenderMetadata.class);
    RenderEngine renderEngine =
        EngineDispatch.requireEngine(engines, engineId, "render", engines.renderEngine(engineId));
    // Render is the only lane that takes a layout result from outside and turns it into an
    // artifact, and it used to check nothing about that geometry at all. Warn-first: the layout
    // quality and structural findings validate-layout reports as input errors ride this envelope at
    // warning severity and the render still happens, so an existing caller keeps its artifact and
    // gains the signal. Narrower than validate-layout on purpose — that lane also runs
    // SequenceInvariants, which layoutInputWarnings does not restate, so a sequence layout sent
    // straight to render still gets no invariant signal. Extending it is a product decision.
    // Merged by EngineDispatch so the ok/warning decision stays in successEnvelope alone.
    List<Diagnostic> layoutWarnings = LayoutQuality.layoutInputWarnings(layoutResult);
    return EngineDispatch.dispatch(
        engineId,
        layoutWarnings,
        () -> renderEngine.render(LaidOutSceneMapper.toScene(layoutResult), policy, metadata));
  }

  public static EngineRunOutcome exportCommand(
      String engineId,
      String policyText,
      String sourceText,
      Path sourceBaseDir,
      String layoutText,
      Map<String, String> env,
      Engines engines)
      throws EngineExecutionException {
    SourceDocument source;
    try {
      source = SourceValidator.loadAndValidateSourceDocument(sourceText, sourceBaseDir);
    } catch (SourceValidator.SourceDiagnosticsException error) {
      return errorOutcome(error.diagnostics());
    }
    LayoutResult layoutResult = parseCommandData("export", layoutText, LayoutResult.class);
    JsonNode policy;
    try {
      policy = parseExportPolicy(engineId, policyText);
    } catch (PolicyVersionException error) {
      // Same rationale as renderCommand above: a version-gate rejection is an input error with the
      // gate's own diagnostic, surfaced before EngineDispatch.requireEngine ever runs.
      return errorOutcome(List.of(error.diagnostic()));
    }
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

  static EngineRunOutcome errorOutcome(List<Diagnostic> diagnostics) {
    return errorOutcome(diagnostics, CommandExitCode.INPUT_ERROR.code());
  }

  /**
   * The exit-code-carrying form, for the one caller that must republish an engine's own error
   * envelope: {@link #importCommand} folds the engine result itself, so it cannot let {@link
   * EngineDispatch#dispatch} serialize the failure branch and has to reproduce it here — verbatim
   * diagnostics at the exception's exit code.
   */
  static EngineRunOutcome errorOutcome(List<Diagnostic> diagnostics, int exitCode) {
    try {
      return new EngineRunOutcome(
          JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(diagnostics)),
          exitCode);
    } catch (RuntimeException error) {
      throw new IllegalStateException("error envelope should serialize", error);
    }
  }

  private static <T> T parseCommandData(String command, String text, Class<T> type)
      throws EngineExecutionException {
    try {
      return JsonInput.parseCommandData(text, type);
    } catch (RuntimeException error) {
      throw commandInputInvalid(command, error);
    }
  }

  static JsonNode parseJson(String command, String text) throws EngineExecutionException {
    try {
      return JsonSupport.objectMapper().readTree(text);
    } catch (RuntimeException error) {
      throw commandInputInvalid(command, error);
    }
  }

  /**
   * Parses a policy document and rejects it when it does not carry {@code family}'s current schema
   * version. Every policy lane — the standalone render and export commands and both build lanes —
   * goes through here, so a stale policy is caught once, before any engine runs.
   *
   * <p>The gate check is a separate failure shape from a malformed (unparseable) policy: {@code
   * text} that is not JSON at all still throws {@link EngineExecutionException} from {@link
   * #parseJson}, unchanged. A version-gate rejection instead throws {@link PolicyVersionException}
   * so the caller — which holds the family and knows whether it is a standalone command or a build
   * lane — decides how to surface it, exactly as {@link
   * dev.dediren.core.source.SourceValidator.SourceDiagnosticsException} lets its callers decide.
   * That separation is what keeps the gate's {@link Diagnostic} (code, message, and its {@code
   * $.<field>} path) intact instead of being laundered through {@link
   * EngineExecutionException#command}, which would overwrite both the path and, at every catch
   * site, the exit code.
   */
  static JsonNode parsePolicy(String command, String text, KnownSchemaVersions.Family family)
      throws EngineExecutionException, PolicyVersionException {
    JsonNode policy = parseJson(command, text);
    Optional<Diagnostic> stale = SchemaVersionGate.check(family, policy);
    if (stale.isPresent()) {
      throw new PolicyVersionException(stale.get());
    }
    return policy;
  }

  /**
   * The policy family an export engine id expects, or empty for an id that is neither export
   * engine. Empty skips the gate and lets {@code requireEngine} raise {@code
   * DEDIREN_PLUGIN_UNKNOWN}, which preserves today's error precedence: a malformed policy is
   * reported before an unknown engine.
   */
  static Optional<KnownSchemaVersions.Family> exportPolicyFamily(String engineId) {
    return switch (engineId) {
      case "archimate-oef" -> Optional.of(KnownSchemaVersions.OEF_EXPORT_POLICY);
      case "uml-xmi" -> Optional.of(KnownSchemaVersions.UML_XMI_EXPORT_POLICY);
      default -> Optional.empty();
    };
  }

  /**
   * The standalone {@code export} command's policy parse: which family applies depends on which
   * engine id was requested, and an id that is neither export engine skips the gate entirely (see
   * {@link #exportPolicyFamily}). Used ONLY by {@link #exportCommand}, where the engine id is
   * genuinely user-supplied — {@link BuildCommand}'s two export lanes know their family statically
   * and gate directly against {@link KnownSchemaVersions#OEF_EXPORT_POLICY} / {@link
   * KnownSchemaVersions#UML_XMI_EXPORT_POLICY} instead, so a renamed or added engine id cannot
   * silently bypass the gate there.
   */
  static JsonNode parseExportPolicy(String engineId, String policyText)
      throws EngineExecutionException, PolicyVersionException {
    Optional<KnownSchemaVersions.Family> family = exportPolicyFamily(engineId);
    return family.isPresent()
        ? parsePolicy("export", policyText, family.get())
        : parseJson("export", policyText);
  }

  private static EngineExecutionException commandInputInvalid(String command, Exception error) {
    return EngineExecutionException.command(
        DiagnosticCode.COMMAND_INPUT_INVALID.code(), command, error.getMessage());
  }

  private static ValidationResult commandInputValidationResult(String command, Exception error) {
    var diagnostic =
        EngineExecutionException.command(
                DiagnosticCode.COMMAND_INPUT_INVALID.code(), command, error.getMessage())
            .diagnostic();
    return new ValidationResult(
        CommandExitCode.INPUT_ERROR.code(), CommandEnvelope.error(List.of(diagnostic)));
  }

  /**
   * Thrown by {@link #parsePolicy} when a policy document fails {@link SchemaVersionGate}: a
   * superseded or unrecognized schema version. Mirrors {@link
   * dev.dediren.core.source.SourceValidator.SourceDiagnosticsException}'s shape on purpose — a
   * version-gate rejection is a genuinely different failure from a malformed/invalid policy (no
   * engine has run, and the fix is "use the current version," not "fix your JSON" or "fix your
   * policy content"), so it gets its own type instead of being folded into {@link
   * EngineExecutionException}, whose {@code command(...)} factory always overwrites the
   * diagnostic's path to {@code "command:" + command} and whose catch sites always map it to {@link
   * CommandExitCode#PLUGIN_ERROR}. Carrying the gate's {@link Diagnostic} unchanged lets every
   * caller — the standalone render/export commands and {@link BuildCommand}'s hoisted gate —
   * publish it verbatim at {@link CommandExitCode#INPUT_ERROR} instead.
   */
  static final class PolicyVersionException extends Exception {
    private final Diagnostic diagnostic;

    PolicyVersionException(Diagnostic diagnostic) {
      super(diagnostic.message());
      this.diagnostic = diagnostic;
    }

    Diagnostic diagnostic() {
      return diagnostic;
    }
  }
}

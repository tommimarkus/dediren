package dev.dediren.plugins.umlxmi;

import static dev.dediren.plugins.umlxmi.build.XmiBuilder.buildModelXmi;
import static dev.dediren.plugins.umlxmi.build.XmiBuilder.buildXmi;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.genericGraphPluginData;
import static dev.dediren.plugins.umlxmi.policy.PolicyValidation.validatePolicy;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.validateXmiToAvailableStandards;
import static dev.dediren.plugins.umlxmi.write.interaction.InteractionWriter.validateExportableSequenceScope;
import static dev.dediren.plugins.umlxmi.write.interaction.InteractionWriter.validateSelectedCombinedFragmentOperators;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.export.UmlXmiExportPolicy;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ExportEngine;
import dev.dediren.engine.ModelExportRequest;
import dev.dediren.plugins.umlxmi.build.Coverage;
import dev.dediren.plugins.umlxmi.build.ExportScope;
import dev.dediren.plugins.umlxmi.build.XmiBuilder;
import dev.dediren.plugins.umlxmi.build.XmiExportException;
import dev.dediren.plugins.umlxmi.build.XmiValidationException;
import dev.dediren.uml.Uml;
import dev.dediren.uml.UmlValidationException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * First-party {@link ExportEngine} that emits a UML/XMI XML artifact from a source model and its
 * laid-out view. Extracted from {@code Main}'s export orchestration (policy validation, UML source
 * validation, sequence-scope checks, XMI build, and OMG XMI schema validation), preserving every
 * published diagnostic code and exit code, and the info-level view-coverage diagnostics.
 *
 * <p>The OMG XMI schema and cache paths arrive through an explicit {@code productRoot}: a relative
 * {@code DEDIREN_XMI_SCHEMA_PATH} / {@code DEDIREN_SCHEMA_CACHE_DIR} resolves against that root
 * (inside {@code SchemaValidation}) rather than the JVM cwd, so an in-memory build path can supply
 * the product root directly. The engine reads only the env map handed to {@link #export}; it never
 * touches the ambient process environment.
 */
public final class XmiExportEngine implements ExportEngine {

  @Override
  public String id() {
    return "uml-xmi";
  }

  /**
   * Converts export-request bytes to the typed record the engine consumes. The UML/XMI export
   * publishes no dedicated parse-failure envelope, so a malformed stream surfaces as today's raw
   * (non-enveloped) failure by letting the underlying parse exception propagate.
   */
  public ExportRequest parseRequest(byte[] input) {
    return JsonSupport.objectMapper().readValue(input, ExportRequest.class);
  }

  @Override
  public EngineResult<ExportResult> export(
      ExportRequest request, Map<String, String> env, Path productRoot) throws EngineException {
    UmlXmiExportPolicy policy;
    try {
      validatePolicy(request.policy());
      policy = JsonSupport.objectMapper().treeToValue(request.policy(), UmlXmiExportPolicy.class);
    } catch (IllegalArgumentException error) {
      throw failure(DiagnosticCode.UML_XMI_POLICY_INVALID.code(), error.getMessage(), "policy");
    }

    try {
      GenericGraphPluginData pluginData = genericGraphPluginData(request);
      validateSelectedCombinedFragmentOperators(request, pluginData);
      Uml.validateSource(request.source(), pluginData);
    } catch (UmlValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    } catch (XmiExportException error) {
      throw failure(error.code(), error.getMessage(), error.path());
    }
    try {
      validateExportableSequenceScope(request);
    } catch (XmiExportException error) {
      throw failure(error.code(), error.getMessage(), error.path());
    }

    XmiBuilder.BuiltModel built = buildXmi(request, policy);
    String content = built.content();
    Diagnostic conformance;
    try {
      conformance = validateXmiToAvailableStandards(content, env, productRoot);
    } catch (XmiValidationException error) {
      throw failure(error.code(), error.getMessage(), "content");
    }

    var result =
        new ExportResult(ContractVersions.EXPORT_RESULT_SCHEMA_VERSION, "uml-xmi+xml", content);
    Coverage coverage =
        Coverage.compute(
            request.source().nodes(),
            request.source().relationships(),
            ExportScope.fromRequest(request),
            built.representedNodeIds(),
            built.representedRelationshipIds());
    var diagnostics =
        new java.util.ArrayList<>(withIdentityTripwire(policy, coverageDiagnostics(coverage)));
    diagnostics.add(conformance);
    return new EngineResult<>(result, diagnostics);
  }

  /**
   * The whole-model lane: one {@code model.uml.xml} carrying the full model once plus one OMG UMLDI
   * diagram per laid-out view (empty when no views are supplied, so the build driver skips it). The
   * single-view {@link #export} stays model-only; diagram interchange lives only here. Diagram
   * geometry for the class family is emitted today; other view kinds still contribute their model
   * content but their diagram serialization is a later slice.
   */
  @Override
  public java.util.Optional<EngineResult<ExportResult>> exportModel(
      ModelExportRequest request, Map<String, String> env, Path productRoot)
      throws EngineException {
    if (request.views().isEmpty()) {
      return java.util.Optional.empty();
    }
    UmlXmiExportPolicy policy;
    try {
      validatePolicy(request.policy());
      policy = JsonSupport.objectMapper().treeToValue(request.policy(), UmlXmiExportPolicy.class);
    } catch (IllegalArgumentException error) {
      throw failure(DiagnosticCode.UML_XMI_POLICY_INVALID.code(), error.getMessage(), "policy");
    }

    ExportRequest representative =
        new ExportRequest(
            ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION,
            request.source(),
            request.views().getFirst().layout(),
            request.policy());
    try {
      GenericGraphPluginData pluginData = genericGraphPluginData(representative);
      validateSelectedCombinedFragmentOperators(representative, pluginData);
      Uml.validateSource(request.source(), pluginData);
    } catch (UmlValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    } catch (XmiExportException error) {
      throw failure(error.code(), error.getMessage(), error.path());
    }

    String content = buildModelXmi(request, policy);
    Diagnostic conformance;
    try {
      conformance = validateXmiToAvailableStandards(content, env, productRoot);
    } catch (XmiValidationException error) {
      throw failure(error.code(), error.getMessage(), "content");
    }

    var result =
        new ExportResult(ContractVersions.EXPORT_RESULT_SCHEMA_VERSION, "uml-xmi+xml", content);
    var diagnostics = new ArrayList<Diagnostic>();
    diagnostics.add(conformance);
    return java.util.Optional.of(new EngineResult<>(result, diagnostics));
  }

  /**
   * Keep in lockstep with {@code fixtures/export-policy/default-uml-xmi.json}; the drift-pin test
   * fails if the shipped default changes without this constant.
   */
  static final String PLACEHOLDER_MODEL_IDENTIFIER = "id-dediren-uml-basic-model";

  /**
   * The shipped default policy hard-codes fixture identity and export succeeds with it unchanged,
   * so a copied-but-unedited policy silently ships a mis-identified XMI. Appends a warning when the
   * model identifier still equals the shipped placeholder — warning, not error: the export stays
   * usable and the caller decides.
   */
  private static List<Diagnostic> withIdentityTripwire(
      UmlXmiExportPolicy policy, List<Diagnostic> diagnostics) {
    if (!PLACEHOLDER_MODEL_IDENTIFIER.equals(policy.modelIdentifier())) {
      return diagnostics;
    }
    var combined = new ArrayList<>(diagnostics);
    combined.add(
        new Diagnostic(
            DiagnosticCode.EXPORT_IDENTITY_PLACEHOLDER.code(),
            DiagnosticSeverity.WARNING,
            "export policy still carries the shipped fixture identity (model_identifier '"
                + PLACEHOLDER_MODEL_IDENTIFIER
                + "'); copy the default policy and replace its identity fields for a real model",
            "policy.model_identifier"));
    return combined;
  }

  private static EngineException failure(String code, String message, String path) {
    return new EngineException(
        List.of(new Diagnostic(code, DiagnosticSeverity.ERROR, message, path)), 3);
  }

  /**
   * A view-scoped XMI is intentionally partial model interchange, so an omission is informational,
   * not a failure: these {@code info} diagnostics let a consumer see, from stdout JSON alone,
   * exactly which source content this artifact does not represent (issue #32). The thin {@code
   * Main} rides them on an {@code ok}-status envelope.
   */
  private static List<Diagnostic> coverageDiagnostics(Coverage coverage) {
    var diagnostics = new ArrayList<Diagnostic>();
    if (coverage.omittedNodes() > 0) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.XMI_ELEMENTS_OMITTED.code(),
              DiagnosticSeverity.INFO,
              coverage.omittedNodes()
                  + " of "
                  + (coverage.representedNodes() + coverage.omittedNodes())
                  + " source elements are outside the exported view and are not represented in this"
                  + " XMI (omitted by type: "
                  + Coverage.describe(coverage.omittedNodeTypes())
                  + "). This export covers a single laid-out view; export the other views to"
                  + " represent them.",
              "source.nodes"));
    }
    if (coverage.omittedRelationships() > 0) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.XMI_RELATIONSHIPS_OMITTED.code(),
              DiagnosticSeverity.INFO,
              coverage.omittedRelationships()
                  + " of "
                  + (coverage.representedRelationships() + coverage.omittedRelationships())
                  + " source relationships are outside the exported view and are not represented in"
                  + " this XMI (omitted by type: "
                  + Coverage.describe(coverage.omittedRelationshipTypes())
                  + "). This export covers a single laid-out view; export the other views to"
                  + " represent them.",
              "source.relationships"));
    }
    // In-view but not representable: a fidelity gap in the UML/XMI mapping (selected content no
    // writer
    // emitted), surfaced so it cannot pass as silently represented. Empty for a conformant export.
    if (coverage.unrepresentedInViewNodes() > 0) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.XMI_ELEMENTS_OMITTED.code(),
              DiagnosticSeverity.INFO,
              coverage.unrepresentedInViewNodes()
                  + " source elements are within the exported view but could not be represented in"
                  + " this XMI (by type: "
                  + Coverage.describe(coverage.unrepresentedInViewNodeTypes())
                  + "). This is a fidelity gap in the UML/XMI mapping, not a view-scoping omission.",
              "source.nodes"));
    }
    if (coverage.unrepresentedInViewRelationships() > 0) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.XMI_RELATIONSHIPS_OMITTED.code(),
              DiagnosticSeverity.INFO,
              coverage.unrepresentedInViewRelationships()
                  + " source relationships are within the exported view but could not be represented"
                  + " in this XMI (by type: "
                  + Coverage.describe(coverage.unrepresentedInViewRelationshipTypes())
                  + "). This is a fidelity gap in the UML/XMI mapping, not a view-scoping omission.",
              "source.relationships"));
    }
    return diagnostics;
  }
}

package dev.dediren.plugins.umlxmi;

import static dev.dediren.plugins.umlxmi.build.XmiBuilder.buildXmi;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.UML_VERSION;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.XMI_VERSION;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.genericGraphPluginData;
import static dev.dediren.plugins.umlxmi.policy.PolicyValidation.validatePolicy;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.SCHEMA_CACHE_DIR_ENV;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.SCHEMA_FETCHER;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.XMI_SCHEMA_PATH_ENV;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.XMI_SCHEMA_VALIDATOR;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.commandAvailable;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.validateXmiToAvailableStandards;
import static dev.dediren.plugins.umlxmi.write.interaction.InteractionWriter.validateExportableSequenceScope;
import static dev.dediren.plugins.umlxmi.write.interaction.InteractionWriter.validateSelectedCombinedFragmentOperators;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.export.UmlXmiExportPolicy;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.plugins.umlxmi.build.Coverage;
import dev.dediren.plugins.umlxmi.build.ExportScope;
import dev.dediren.plugins.umlxmi.build.XmiExportException;
import dev.dediren.plugins.umlxmi.build.XmiValidationException;
import dev.dediren.uml.Uml;
import dev.dediren.uml.UmlValidationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

public final class Main {

  private Main() {}

  public static String moduleName() {
    return "uml-xmi-export";
  }

  public static void main(String[] args) throws Exception {
    int exitCode = execute(args, System.in, System.out, System.err, System.getenv());
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  public static PluginResult executeForTesting(String[] args, String stdin, Map<String, String> env)
      throws Exception {
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    int exitCode =
        execute(
            args,
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8),
            env);
    return new PluginResult(
        exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
  }

  private static int execute(
      String[] args,
      InputStream stdin,
      PrintStream stdout,
      PrintStream stderr,
      Map<String, String> env)
      throws Exception {
    if (args.length > 0 && args[0].equals("capabilities")) {
      stdout.println(capabilitiesJson());
      return 0;
    }
    if (args.length > 0 && args[0].equals("export")) {
      return exportFromStdin(stdin, stdout, env);
    }
    stderr.println("expected command: capabilities or export");
    return 2;
  }

  private static String capabilitiesJson() throws IOException {
    ObjectNode root = JsonSupport.objectMapper().createObjectNode();
    root.put("plugin_protocol_version", ContractVersions.PLUGIN_PROTOCOL_VERSION);
    root.put("id", "uml-xmi");
    root.set("capabilities", JsonSupport.objectMapper().createArrayNode().add("export"));
    ObjectNode runtime = root.putObject("runtime");
    runtime.put("artifact_kind", "uml-xmi+xml");
    runtime.put("uml_version", UML_VERSION);
    runtime.put("xmi_version", XMI_VERSION);
    ObjectNode schemaValidation = runtime.putObject("schema_validation");
    schemaValidation.put("kind", "omg-xmi-xsd-partial");
    schemaValidation.put("schema_version", XMI_VERSION);
    schemaValidation.put("validator", XMI_SCHEMA_VALIDATOR);
    schemaValidation.put("available", commandAvailable(XMI_SCHEMA_VALIDATOR));
    schemaValidation.put("schema_source", "DEDIREN_XMI_SCHEMA_PATH or runtime cache download");
    schemaValidation.put("schema_path_env", XMI_SCHEMA_PATH_ENV);
    schemaValidation.put("cache_dir_env", SCHEMA_CACHE_DIR_ENV);
    schemaValidation.put("fetcher", SCHEMA_FETCHER);
    schemaValidation.put(
        "limitation", "UML 2.5.1 is published as an XMI metamodel, not an importable XML Schema");
    schemaValidation.put(
        "uml_content_validation",
        "To schema-check the emitted uml:* content, point DEDIREN_XMI_SCHEMA_PATH at a driver schema"
            + " that imports the OMG XMI.xsd and a UML 2.5.1 XSD, then run: xmllint --nonet --noout"
            + " --schema <driver.xsd> <document>. OMG does not publish an importable UML 2.5.1 XSD,"
            + " so supply or generate one (for example from the Eclipse UML2 metamodel) or import"
            + " the document into a UML tool. Without a UML schema only the XMI envelope is checked.");
    return JsonSupport.objectMapper().writeValueAsString(root);
  }

  private static int exportFromStdin(InputStream stdin, PrintStream stdout, Map<String, String> env)
      throws Exception {
    ExportRequest request =
        JsonSupport.objectMapper().readValue(stdin.readAllBytes(), ExportRequest.class);
    UmlXmiExportPolicy policy;
    try {
      validatePolicy(request.policy());
      policy = JsonSupport.objectMapper().treeToValue(request.policy(), UmlXmiExportPolicy.class);
    } catch (IllegalArgumentException error) {
      return exitWithDiagnostic(
          stdout, "DEDIREN_UML_XMI_POLICY_INVALID", error.getMessage(), "policy");
    }

    GenericGraphPluginData pluginData;
    try {
      pluginData = genericGraphPluginData(request);
      validateSelectedCombinedFragmentOperators(request, pluginData);
      Uml.validateSource(request.source(), pluginData);
    } catch (UmlValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.message(), error.path());
    } catch (XmiExportException error) {
      return exitWithDiagnostic(stdout, error.code(), error.getMessage(), error.path());
    }
    try {
      validateExportableSequenceScope(request);
    } catch (XmiExportException error) {
      return exitWithDiagnostic(stdout, error.code(), error.getMessage(), error.path());
    }

    String content = buildXmi(request, policy);
    try {
      validateXmiToAvailableStandards(content, env);
    } catch (XmiValidationException error) {
      return exitWithDiagnostic(stdout, error.code(), error.getMessage(), "content");
    }

    var result =
        new ExportResult(ContractVersions.EXPORT_RESULT_SCHEMA_VERSION, "uml-xmi+xml", content);
    Coverage coverage =
        Coverage.compute(
            request.source().nodes(),
            request.source().relationships(),
            ExportScope.fromRequest(request));
    stdout.println(JsonSupport.objectMapper().writeValueAsString(exportEnvelope(result, coverage)));
    return 0;
  }

  private static CommandEnvelope<ExportResult> exportEnvelope(
      ExportResult result, Coverage coverage) {
    List<Diagnostic> diagnostics = coverageDiagnostics(coverage);
    if (diagnostics.isEmpty()) {
      return CommandEnvelope.ok(result);
    }
    // A view-scoped XMI is intentionally partial model interchange, so an omission is
    // informational,
    // not a failure: status stays "ok" while the diagnostics let a consumer see, from stdout JSON
    // alone, exactly which source content this artifact does not represent (issue #32).
    return new CommandEnvelope<>(
        ContractVersions.ENVELOPE_SCHEMA_VERSION, EnvelopeStatus.OK, result, diagnostics);
  }

  private static List<Diagnostic> coverageDiagnostics(Coverage coverage) {
    var diagnostics = new ArrayList<Diagnostic>();
    if (coverage.omittedNodes() > 0) {
      diagnostics.add(
          new Diagnostic(
              "DEDIREN_XMI_ELEMENTS_OMITTED",
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
              "DEDIREN_XMI_RELATIONSHIPS_OMITTED",
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
    return diagnostics;
  }

  // Defense in depth: this validator only ever parses Dediren's own generated
  // XMI, which never contains a DOCTYPE. Hardening the factory keeps XXE and
  // entity-expansion classes off the table regardless of what the parser is
  // ever pointed at. Package-private so the same-package test can exercise it.

  private static int exitWithDiagnostic(
      PrintStream stdout, String code, String message, String path) throws IOException {
    var diagnostic = new Diagnostic(code, DiagnosticSeverity.ERROR, message, path);
    stdout.println(
        JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(List.of(diagnostic))));
    return 3;
  }
}

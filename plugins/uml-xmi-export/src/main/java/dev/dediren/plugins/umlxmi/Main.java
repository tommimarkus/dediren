package dev.dediren.plugins.umlxmi;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.UML_VERSION;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.XMI_VERSION;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.SCHEMA_CACHE_DIR_ENV;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.SCHEMA_FETCHER;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.XMI_SCHEMA_PATH_ENV;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.XMI_SCHEMA_VALIDATOR;
import static dev.dediren.plugins.umlxmi.schema.SchemaValidation.commandAvailable;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/**
 * Thin process-boundary adapter for the UML/XMI export: it parses stdin, delegates to {@link
 * XmiExportEngine}, and shapes the command envelope. All export orchestration and schema handling
 * live in the engine.
 */
// lean-audit:dup-intentional: cross-plugin envelope boilerplate; see arch-guidelines.md §12
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
    XmiExportEngine engine = new XmiExportEngine();
    ExportRequest request = engine.parseRequest(stdin.readAllBytes());
    try {
      EngineResult<ExportResult> result = engine.export(request, env, Path.of("").toAbsolutePath());
      stdout.println(
          JsonSupport.objectMapper()
              .writeValueAsString(exportEnvelope(result.value(), result.diagnostics())));
      return 0;
    } catch (EngineException error) {
      stdout.println(
          JsonSupport.objectMapper()
              .writeValueAsString(CommandEnvelope.error(error.diagnostics())));
      return error.exitCode();
    }
  }

  /**
   * Shapes the success envelope, preserving the process form: a fully-covered export emits {@link
   * CommandEnvelope#ok} (no {@code diagnostics}), while info-level view-coverage diagnostics ride
   * an {@code ok}-status envelope so a consumer sees which source content this artifact does not
   * represent without descending into {@code data} (issue #32).
   */
  private static CommandEnvelope<ExportResult> exportEnvelope(
      ExportResult result, List<Diagnostic> diagnostics) {
    if (diagnostics.isEmpty()) {
      return CommandEnvelope.ok(result);
    }
    return new CommandEnvelope<>(
        ContractVersions.ENVELOPE_SCHEMA_VERSION, EnvelopeStatus.OK, result, diagnostics);
  }
}

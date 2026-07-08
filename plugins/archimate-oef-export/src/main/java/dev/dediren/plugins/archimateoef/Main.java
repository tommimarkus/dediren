package dev.dediren.plugins.archimateoef;

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
 * Thin process-boundary adapter for the ArchiMate/OEF export: it parses stdin, delegates to {@link
 * OefExportEngine}, and shapes the command envelope. All export orchestration and schema handling
 * live in the engine.
 */
// lean-audit:dup-intentional: cross-plugin envelope boilerplate; see arch-guidelines.md §12
public final class Main {

  private Main() {}

  public static String moduleName() {
    return "archimate-oef-export";
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
    root.put("id", "archimate-oef");
    root.set("capabilities", JsonSupport.objectMapper().createArrayNode().add("export"));
    ObjectNode runtime = root.putObject("runtime");
    runtime.put("artifact_kind", "archimate-oef+xml");
    runtime.put("archimate_version", "3.2");
    runtime.put("oef_namespace", OefExportEngine.OEF_NS);
    ObjectNode schemaValidation = runtime.putObject("schema_validation");
    schemaValidation.put("kind", "official-oef-xsd");
    schemaValidation.put("schema_version", "3.1");
    schemaValidation.put("validator", OefExportEngine.OEF_SCHEMA_VALIDATOR);
    schemaValidation.put("available", commandAvailable(OefExportEngine.OEF_SCHEMA_VALIDATOR));
    schemaValidation.put("schema_source", "DEDIREN_OEF_SCHEMA_DIR or runtime cache download");
    schemaValidation.put("schema_dir_env", OefExportEngine.OEF_SCHEMA_DIR_ENV);
    schemaValidation.put("cache_dir_env", OefExportEngine.SCHEMA_CACHE_DIR_ENV);
    schemaValidation.put("fetcher", OefExportEngine.SCHEMA_FETCHER);
    return JsonSupport.objectMapper().writeValueAsString(root);
  }

  private static boolean commandAvailable(String command) {
    try {
      return new ProcessBuilder(command, "--version").start().waitFor() == 0;
    } catch (IOException | InterruptedException error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  private static int exportFromStdin(InputStream stdin, PrintStream stdout, Map<String, String> env)
      throws Exception {
    OefExportEngine engine = new OefExportEngine();
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
   * Shapes the success envelope, preserving the process form: a clean export emits {@link
   * CommandEnvelope#ok} (no {@code diagnostics}), while info-level view-coverage diagnostics ride
   * an {@code ok}-status envelope so a consumer sees the omission verdict without descending into
   * {@code data} (issue #34).
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

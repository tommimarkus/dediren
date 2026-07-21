package dev.dediren.plugins.umlxmi;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Envelope-shaped test harness for the UML/XMI export: it parses stdin, delegates to {@link
 * XmiExportEngine}, and shapes the command envelope so the existing envelope-asserting suites can
 * drive the engine without a process boundary. All export orchestration and schema handling live in
 * the engine.
 */
// lean-audit:dup-intentional: cross-plugin envelope boilerplate; see arch-guidelines.md §12
public final class Main {

  private Main() {}

  public static String moduleName() {
    return "uml-xmi-export";
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
    if (args.length > 0 && args[0].equals("export")) {
      return exportFromStdin(stdin, stdout, env);
    }
    stderr.println("expected command: export");
    return 2;
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
    boolean warned = diagnostics.stream().anyMatch(d -> d.severity() != DiagnosticSeverity.INFO);
    return new CommandEnvelope<>(
        ContractVersions.ENVELOPE_SCHEMA_VERSION,
        warned ? EnvelopeStatus.WARNING : EnvelopeStatus.OK,
        result,
        diagnostics);
  }
}

package dev.dediren.plugins.render;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.ir.LaidOutSceneMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Envelope-shaped test harness for the SVG render engine: it parses stdin, delegates to {@link
 * SvgRenderEngine}, and shapes the command envelope so the existing render suites can drive the
 * engine without a process boundary.
 */
public final class Main {

  private Main() {}

  public static String moduleName() {
    return "render";
  }

  public static PluginResult executeForTesting(String[] args, String stdin) throws Exception {
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    int exitCode =
        execute(
            args,
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8));
    return new PluginResult(
        exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
  }

  private static int execute(
      String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr) throws Exception {
    if (args.length > 0 && args[0].equals("render")) {
      return renderFromStdin(stdin, stdout);
    }
    stderr.println("expected command: render");
    return 2;
  }

  private static int renderFromStdin(InputStream stdin, PrintStream stdout) throws Exception {
    SvgRenderEngine engine = new SvgRenderEngine();
    SvgRenderEngine.ParsedInput input = engine.parseInput(stdin.readAllBytes());
    try {
      EngineResult<RenderResult> result =
          engine.render(
              LaidOutSceneMapper.toScene(input.layoutResult()),
              input.policy(),
              input.renderMetadata());
      stdout.println(JsonSupport.objectMapper().writeValueAsString(successEnvelope(result)));
      return 0;
    } catch (EngineException error) {
      stdout.println(
          JsonSupport.objectMapper()
              .writeValueAsString(CommandEnvelope.error(error.diagnostics())));
      return error.exitCode();
    }
  }

  /**
   * Mirrors {@code EngineDispatch.successEnvelope}'s ok/warning policy, which is what the CLI and
   * the in-memory build lane both apply to an engine's success diagnostics. Duplicated rather than
   * called because {@code core} is not an allowed edge from an engine module — and a harness that
   * dropped the diagnostics would hide every warning the engine publishes alongside its artifact.
   */
  private static CommandEnvelope<RenderResult> successEnvelope(EngineResult<RenderResult> result) {
    if (result.diagnostics().isEmpty()) {
      return CommandEnvelope.ok(result.value());
    }
    boolean anyWarning =
        result.diagnostics().stream()
            .anyMatch(diagnostic -> diagnostic.severity() != DiagnosticSeverity.INFO);
    return anyWarning
        ? CommandEnvelope.warning(result.value(), result.diagnostics())
        : new CommandEnvelope<>(
            ContractVersions.ENVELOPE_SCHEMA_VERSION,
            EnvelopeStatus.OK,
            result.value(),
            result.diagnostics());
  }
}

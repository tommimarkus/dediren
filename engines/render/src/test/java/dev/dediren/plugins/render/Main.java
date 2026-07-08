package dev.dediren.plugins.render;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.engine.EngineException;
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
      RenderResult result =
          engine.render(input.layoutResult(), input.policy(), input.renderMetadata()).value();
      stdout.println(JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(result)));
      return 0;
    } catch (EngineException error) {
      stdout.println(
          JsonSupport.objectMapper()
              .writeValueAsString(CommandEnvelope.error(error.diagnostics())));
      return error.exitCode();
    }
  }
}

package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.engine.EngineException;
import dev.dediren.ir.LaidOutSceneMapper;
import dev.dediren.ir.SceneGraph;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Envelope-shaped test harness for the ELK layout engine: it parses stdin, delegates to {@link
 * ElkEngine}, and shapes the command envelope so the existing layout suites can drive the engine
 * without a process boundary.
 */
public final class Main {
  private Main() {}

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
    if (args.length > 0 && args[0].equals("layout")) {
      return run(stdin, stdout);
    }
    if (args.length == 0) {
      return run(stdin, stdout);
    }
    stderr.println("expected command: layout");
    return 2;
  }

  static int run(InputStream stdin, PrintStream stdout) throws Exception {
    ElkEngine engine = new ElkEngine();
    SceneGraph scene;
    try {
      scene = engine.parseRequest(stdin.readAllBytes());
    } catch (EngineException error) {
      stdout.println(EnvelopeWriter.error(error.diagnostics()));
      return error.exitCode();
    }

    try {
      LayoutResult result = LaidOutSceneMapper.toResult(engine.layout(scene).value());
      stdout.println(EnvelopeWriter.ok(result));
      return 0;
    } catch (EngineException error) {
      stdout.println(EnvelopeWriter.error(error.diagnostics()));
      return error.exitCode();
    }
  }
}

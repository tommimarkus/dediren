package dev.dediren.plugins.genericgraph;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.plugin.RuntimeCapabilities;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

// lean-audit:dup-intentional: cross-plugin envelope boilerplate; see arch-guidelines.md §12
public final class Main {
  private Main() {}

  public static String moduleName() {
    return "generic-graph";
  }

  public static void main(String[] args) throws Exception {
    int exitCode = execute(args, System.in, System.out, System.err);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
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
    if (args.length > 0 && args[0].equals("capabilities")) {
      stdout.println(
          JsonSupport.objectMapper()
              .writeValueAsString(
                  new RuntimeCapabilities(
                      ContractVersions.PLUGIN_PROTOCOL_VERSION,
                      "generic-graph",
                      List.of("semantic-validation", "projection"),
                      null)));
      return 0;
    }
    if (args.length == 0) {
      stderr.println("expected command: validate or project");
      return 2;
    }
    return switch (args[0]) {
      case "validate" -> validateFromStdin(args, stdin, stdout);
      case "project" -> {
        try {
          yield projectFromStdin(args, stdin, stdout, stderr);
        } catch (UncheckedIOException error) {
          stderr.println(error.getCause().getMessage());
          yield 2;
        }
      }
      default -> {
        stderr.println("expected command: validate or project");
        yield 2;
      }
    };
  }

  private static int validateFromStdin(String[] args, InputStream stdin, PrintStream stdout)
      throws Exception {
    String profile = valueAfter(args, "--profile");
    GenericGraphEngine engine = new GenericGraphEngine();
    SourceDocument source = engine.parseSource(stdin.readAllBytes());
    try {
      var result = engine.validate(source, profile);
      stdout.println(
          JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(result.value())));
      return 0;
    } catch (EngineException error) {
      return printError(stdout, error);
    }
  }

  private static int projectFromStdin(
      String[] args, InputStream stdin, PrintStream stdout, PrintStream stderr) throws Exception {
    String target = valueAfter(args, "--target");
    String view = valueAfter(args, "--view");
    if (!Objects.equals(target, "layout-request") && !Objects.equals(target, "render-metadata")) {
      stderr.println("unsupported target: " + target);
      return 2;
    }
    if (view == null) {
      stderr.println("missing --view");
      return 2;
    }

    GenericGraphEngine engine = new GenericGraphEngine();
    SourceDocument source = engine.parseSource(stdin.readAllBytes());
    try {
      if (target.equals("render-metadata")) {
        var result = engine.projectRenderMetadata(source, view);
        stdout.println(
            JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(result.value())));
      } else {
        var result = engine.projectLayoutRequest(source, view);
        stdout.println(
            JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.ok(result.value())));
      }
      return 0;
    } catch (EngineException error) {
      return printError(stdout, error);
    }
  }

  private static int printError(PrintStream stdout, EngineException error) {
    stdout.println(
        JsonSupport.objectMapper().writeValueAsString(CommandEnvelope.error(error.diagnostics())));
    return error.exitCode();
  }

  private static String valueAfter(String[] args, String flag) {
    for (int i = 0; i < args.length - 1; i++) {
      if (args[i].equals(flag)) {
        return args[i + 1];
      }
    }
    return null;
  }
}

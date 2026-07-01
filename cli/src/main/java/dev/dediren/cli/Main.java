package dev.dediren.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.commands.CoreCommands;
import dev.dediren.core.plugins.PluginExecutionException;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.core.source.SourceValidator;
import dev.dediren.core.source.ValidationResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "dediren",
    mixinStandardHelpOptions = true,
    versionProvider = Main.ProductVersionProvider.class,
    footer = {
      "Agent authoring guide: docs/agent-usage.md",
      "Use it for source JSON shape, generated artifact handoff, fragments, and repair diagnostics."
    })
public final class Main {
  private final InputStream stdin;
  private final Map<String, String> env;

  @Spec private CommandSpec spec;

  private Main(InputStream stdin, Map<String, String> env) {
    this.stdin = stdin;
    this.env = env;
  }

  public static String moduleName() {
    return "cli";
  }

  public static void main(String[] args) {
    int exitCode =
        commandLine(
                System.in,
                new PrintWriter(System.out, true),
                new PrintWriter(System.err, true),
                System.getenv())
            .execute(args);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  public static CliResult executeForTesting(String[] args, String stdin) {
    return executeForTesting(args, stdin, System.getenv());
  }

  static CliResult executeForTesting(String[] args, String stdin, Map<String, String> env) {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    int exitCode =
        commandLine(
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(stdout, true),
                new PrintWriter(stderr, true),
                env)
            .execute(args);
    return new CliResult(
        exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
  }

  private static CommandLine commandLine(
      InputStream stdin, PrintWriter stdout, PrintWriter stderr, Map<String, String> env) {
    CommandLine commandLine = new CommandLine(new Main(stdin, env));
    commandLine.addSubcommand("validate", new ValidateCommand(stdin, env));
    commandLine.addSubcommand("project", new ProjectCommand(stdin, env));
    commandLine.addSubcommand("layout", new LayoutCommand(stdin, env));
    commandLine.addSubcommand("validate-layout", new ValidateLayoutCommand(stdin));
    commandLine.addSubcommand("render", new RenderCommand(stdin, env));
    commandLine.addSubcommand("export", new ExportCommand(env));
    commandLine.setOut(stdout);
    commandLine.setErr(stderr);
    return commandLine;
  }

  @Command(name = "validate", description = "Validate source JSON")
  static final class ValidateCommand implements Callable<Integer> {
    private final InputStream stdin;
    private final Map<String, String> env;

    @Option(names = "--plugin")
    private String plugin;

    @Option(names = "--profile")
    private String profile;

    @Option(names = "--input")
    private Path input;

    @Spec private CommandSpec spec;

    ValidateCommand(InputStream stdin, Map<String, String> env) {
      this.stdin = stdin;
      this.env = env;
    }

    @Override
    public Integer call() throws Exception {
      if (plugin != null && profile == null) {
        return printEnvelope(
            usageError(
                DiagnosticCode.VALIDATE_PROFILE_REQUIRED.code(),
                "validate --plugin requires --profile"));
      }
      if (plugin == null && profile != null) {
        return printEnvelope(
            usageError(
                DiagnosticCode.VALIDATE_PLUGIN_REQUIRED.code(),
                "validate --profile requires --plugin"));
      }
      JsonInputText inputText = readInput("input", input, stdin);
      if (inputText.error() != null) {
        return printEnvelope(inputText.error());
      }
      if (plugin != null) {
        try {
          return printPluginOutcome(
              CoreCommands.semanticValidateCommand(
                  plugin, profile, inputText.text(), inputText.baseDir(), env));
        } catch (PluginExecutionException error) {
          return printPluginError(error);
        }
      }
      ValidationResult result =
          SourceValidator.validateSourceJson(inputText.text(), inputText.baseDir());
      return printValidationResult(result);
    }

    private Integer printValidationResult(ValidationResult result) throws IOException {
      return writeValidationResult(spec, result);
    }

    private Integer printEnvelope(CommandEnvelope<JsonNode> envelope) throws IOException {
      return writeEnvelope(spec, envelope, CommandExitCode.INPUT_ERROR);
    }

    private Integer printPluginOutcome(PluginRunOutcome outcome) {
      return writePluginOutcome(spec, outcome);
    }

    private Integer printPluginError(PluginExecutionException error) throws IOException {
      return writePluginError(spec, error);
    }
  }

  @Command(name = "project", description = "Project source JSON into a backend request")
  static final class ProjectCommand implements Callable<Integer> {
    private final InputStream stdin;
    private final Map<String, String> env;

    @Option(names = "--target", required = true)
    private String target;

    @Option(names = "--plugin", required = true)
    private String plugin;

    @Option(names = "--view", required = true)
    private String view;

    @Option(names = "--input")
    private Path input;

    @Spec private CommandSpec spec;

    ProjectCommand(InputStream stdin, Map<String, String> env) {
      this.stdin = stdin;
      this.env = env;
    }

    @Override
    public Integer call() throws Exception {
      JsonInputText inputText = readInput("input", input, stdin);
      if (inputText.error() != null) {
        return writeEnvelope(spec, inputText.error(), CommandExitCode.INPUT_ERROR);
      }
      try {
        return writePluginOutcome(
            spec,
            CoreCommands.projectCommand(
                plugin, target, view, inputText.text(), inputText.baseDir(), env));
      } catch (PluginExecutionException error) {
        return writePluginError(spec, error);
      }
    }
  }

  @Command(name = "layout", description = "Run a layout plugin")
  static final class LayoutCommand implements Callable<Integer> {
    private final InputStream stdin;
    private final Map<String, String> env;

    @Option(names = "--plugin", required = true)
    private String plugin;

    @Option(names = "--input")
    private Path input;

    @Spec private CommandSpec spec;

    LayoutCommand(InputStream stdin, Map<String, String> env) {
      this.stdin = stdin;
      this.env = env;
    }

    @Override
    public Integer call() throws Exception {
      JsonInputText inputText = readInput("input", input, stdin);
      if (inputText.error() != null) {
        return writeEnvelope(spec, inputText.error(), CommandExitCode.INPUT_ERROR);
      }
      try {
        return writePluginOutcome(spec, CoreCommands.layoutCommand(plugin, inputText.text(), env));
      } catch (PluginExecutionException error) {
        return writePluginError(spec, error);
      }
    }
  }

  @Command(name = "validate-layout", description = "Validate generated layout quality")
  static final class ValidateLayoutCommand implements Callable<Integer> {
    private final InputStream stdin;

    @Option(names = "--input")
    private Path input;

    @Spec private CommandSpec spec;

    ValidateLayoutCommand(InputStream stdin) {
      this.stdin = stdin;
    }

    @Override
    public Integer call() throws Exception {
      JsonInputText inputText = readInput("input", input, stdin);
      if (inputText.error() != null) {
        return writeEnvelope(spec, inputText.error(), CommandExitCode.INPUT_ERROR);
      }
      return writeValidationResult(spec, CoreCommands.validateLayoutCommand(inputText.text()));
    }
  }

  @Command(name = "render", description = "Run a render plugin")
  static final class RenderCommand implements Callable<Integer> {
    private final InputStream stdin;
    private final Map<String, String> env;

    @Option(names = "--plugin", required = true)
    private String plugin;

    @Option(names = "--policy", required = true)
    private Path policy;

    @Option(names = "--metadata")
    private Path metadata;

    @Option(names = "--input")
    private Path input;

    @Spec private CommandSpec spec;

    RenderCommand(InputStream stdin, Map<String, String> env) {
      this.stdin = stdin;
      this.env = env;
    }

    @Override
    public Integer call() throws Exception {
      JsonInputText layoutText = readInput("input", input, stdin);
      if (layoutText.error() != null) {
        return writeEnvelope(spec, layoutText.error(), CommandExitCode.INPUT_ERROR);
      }
      JsonInputText policyText = readFile("policy", policy);
      if (policyText.error() != null) {
        return writeEnvelope(spec, policyText.error(), CommandExitCode.INPUT_ERROR);
      }
      JsonInputText metadataText = metadata == null ? null : readFile("metadata", metadata);
      if (metadataText != null && metadataText.error() != null) {
        return writeEnvelope(spec, metadataText.error(), CommandExitCode.INPUT_ERROR);
      }
      try {
        return writePluginOutcome(
            spec,
            CoreCommands.renderCommand(
                plugin,
                policyText.text(),
                metadataText == null ? null : metadataText.text(),
                layoutText.text(),
                env));
      } catch (PluginExecutionException error) {
        return writePluginError(spec, error);
      }
    }
  }

  @Command(name = "export", description = "Run an export plugin")
  static final class ExportCommand implements Callable<Integer> {
    private final Map<String, String> env;

    @Option(names = "--plugin", required = true)
    private String plugin;

    @Option(names = "--policy", required = true)
    private Path policy;

    @Option(names = "--source", required = true)
    private Path source;

    @Option(names = "--layout", required = true)
    private Path layout;

    @Spec private CommandSpec spec;

    ExportCommand(Map<String, String> env) {
      this.env = env;
    }

    @Override
    public Integer call() throws Exception {
      JsonInputText sourceText = readFile("source", source);
      if (sourceText.error() != null) {
        return writeEnvelope(spec, sourceText.error(), CommandExitCode.INPUT_ERROR);
      }
      JsonInputText policyText = readFile("policy", policy);
      if (policyText.error() != null) {
        return writeEnvelope(spec, policyText.error(), CommandExitCode.INPUT_ERROR);
      }
      JsonInputText layoutText = readFile("layout", layout);
      if (layoutText.error() != null) {
        return writeEnvelope(spec, layoutText.error(), CommandExitCode.INPUT_ERROR);
      }
      try {
        return writePluginOutcome(
            spec,
            CoreCommands.exportCommand(
                plugin,
                policyText.text(),
                sourceText.text(),
                sourceText.baseDir(),
                layoutText.text(),
                env));
      } catch (PluginExecutionException error) {
        return writePluginError(spec, error);
      }
    }
  }

  private static JsonInputText readInput(String label, Path input, InputStream stdin) {
    if (input == null) {
      try {
        return new JsonInputText(
            new String(stdin.readAllBytes(), StandardCharsets.UTF_8), null, null);
      } catch (IOException error) {
        return new JsonInputText(null, null, commandInputError(label, null, error));
      }
    }
    return readFile(label, input);
  }

  private static JsonInputText readFile(String label, Path path) {
    try {
      Path baseDir = path.getParent() == null ? Path.of(".") : path.getParent();
      return new JsonInputText(Files.readString(path), baseDir, null);
    } catch (IOException error) {
      return new JsonInputText(null, null, commandInputError(label, path, error));
    }
  }

  private static Integer writeValidationResult(CommandSpec spec, ValidationResult result)
      throws IOException {
    spec.commandLine()
        .getOut()
        .println(JsonSupport.objectMapper().writeValueAsString(result.envelope()));
    return result.exitCode();
  }

  private static Integer writeEnvelope(
      CommandSpec spec, CommandEnvelope<JsonNode> envelope, CommandExitCode exitCode)
      throws IOException {
    spec.commandLine().getOut().println(JsonSupport.objectMapper().writeValueAsString(envelope));
    return exitCode.code();
  }

  private static Integer writePluginOutcome(CommandSpec spec, PluginRunOutcome outcome) {
    PrintWriter out = spec.commandLine().getOut();
    out.print(outcome.stdout());
    out.flush();
    return outcome.exitCode();
  }

  private static Integer writePluginError(CommandSpec spec, PluginExecutionException error)
      throws IOException {
    return writeEnvelope(
        spec, CommandEnvelope.error(List.of(error.diagnostic())), CommandExitCode.PLUGIN_ERROR);
  }

  private static CommandEnvelope<JsonNode> usageError(String code, String message) {
    return CommandEnvelope.error(
        List.of(new Diagnostic(code, DiagnosticSeverity.ERROR, message, null)));
  }

  private static CommandEnvelope<JsonNode> commandInputError(
      String label, Path path, IOException error) {
    String diagnosticPath = path == null ? label : label + ":" + path;
    return CommandEnvelope.error(
        List.of(
            new Diagnostic(
                DiagnosticCode.COMMAND_INPUT_INVALID.code(),
                DiagnosticSeverity.ERROR,
                "failed to read " + label + ": " + error.getMessage(),
                diagnosticPath)));
  }

  public static final class ProductVersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {"dediren " + System.getProperty("dediren.version", "unknown")};
    }
  }

  private record JsonInputText(String text, Path baseDir, CommandEnvelope<JsonNode> error) {}
}

package dev.dediren.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.Diagnostic;
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
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "dediren",
        mixinStandardHelpOptions = true,
        version = "dediren-java-migration",
        footer = {
                "Agent authoring guide: docs/agent-usage.md",
                "Use it for source JSON shape, generated artifact handoff, fragments, and repair diagnostics."
        })
public final class Main {
    private final InputStream stdin;

    @Spec
    private CommandSpec spec;

    private Main(InputStream stdin) {
        this.stdin = stdin;
    }

    public static String moduleName() {
        return "cli";
    }

    public static void main(String[] args) {
        int exitCode = commandLine(System.in, new PrintWriter(System.out, true), new PrintWriter(System.err, true))
                .execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static CliResult executeForTesting(String[] args, String stdin) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = commandLine(
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(stdout, true),
                new PrintWriter(stderr, true))
                .execute(args);
        return new CliResult(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static CommandLine commandLine(InputStream stdin, PrintWriter stdout, PrintWriter stderr) {
        CommandLine commandLine = new CommandLine(new Main(stdin));
        commandLine.addSubcommand("validate", new ValidateCommand(stdin));
        commandLine.addSubcommand("project", new ProjectCommand(stdin));
        commandLine.addSubcommand("layout", new LayoutCommand(stdin));
        commandLine.addSubcommand("validate-layout", new ValidateLayoutCommand(stdin));
        commandLine.addSubcommand("render", new RenderCommand(stdin));
        commandLine.addSubcommand("export", new ExportCommand());
        commandLine.setOut(stdout);
        commandLine.setErr(stderr);
        return commandLine;
    }

    @Command(name = "validate", description = "Validate source JSON")
    static final class ValidateCommand implements Callable<Integer> {
        private final InputStream stdin;

        @Option(names = "--plugin")
        private String plugin;

        @Option(names = "--profile")
        private String profile;

        @Option(names = "--input")
        private Path input;

        @Spec
        private CommandSpec spec;

        ValidateCommand(InputStream stdin) {
            this.stdin = stdin;
        }

        @Override
        public Integer call() throws Exception {
            if (plugin != null && profile == null) {
                return printEnvelope(usageError(
                        "DEDIREN_VALIDATE_PROFILE_REQUIRED",
                        "validate --plugin requires --profile"));
            }
            if (plugin == null && profile != null) {
                return printEnvelope(usageError(
                        "DEDIREN_VALIDATE_PLUGIN_REQUIRED",
                        "validate --profile requires --plugin"));
            }
            JsonInputText inputText = readInput("input", input, stdin);
            if (inputText.error() != null) {
                return printEnvelope(inputText.error());
            }
            if (plugin != null) {
                try {
                    return printPluginOutcome(CoreCommands.semanticValidateCommand(
                            plugin,
                            profile,
                            inputText.text(),
                            inputText.baseDir()));
                } catch (PluginExecutionException error) {
                    return printPluginError(error);
                }
            }
            ValidationResult result = SourceValidator.validateSourceJson(inputText.text(), inputText.baseDir());
            return printValidationResult(result);
        }

        private Integer printValidationResult(ValidationResult result) throws IOException {
            return writeValidationResult(spec, result);
        }

        private Integer printEnvelope(CommandEnvelope<JsonNode> envelope) throws IOException {
            return writeEnvelope(spec, envelope, 2);
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

        @Option(names = "--target", required = true)
        private String target;

        @Option(names = "--plugin", required = true)
        private String plugin;

        @Option(names = "--view", required = true)
        private String view;

        @Option(names = "--input")
        private Path input;

        @Spec
        private CommandSpec spec;

        ProjectCommand(InputStream stdin) {
            this.stdin = stdin;
        }

        @Override
        public Integer call() throws Exception {
            JsonInputText inputText = readInput("input", input, stdin);
            if (inputText.error() != null) {
                return writeEnvelope(spec, inputText.error(), 2);
            }
            try {
                return writePluginOutcome(spec, CoreCommands.projectCommand(
                        plugin,
                        target,
                        view,
                        inputText.text(),
                        inputText.baseDir()));
            } catch (PluginExecutionException error) {
                return writePluginError(spec, error);
            }
        }
    }

    @Command(name = "layout", description = "Run a layout plugin")
    static final class LayoutCommand implements Callable<Integer> {
        private final InputStream stdin;

        @Option(names = "--plugin", required = true)
        private String plugin;

        @Option(names = "--input")
        private Path input;

        @Spec
        private CommandSpec spec;

        LayoutCommand(InputStream stdin) {
            this.stdin = stdin;
        }

        @Override
        public Integer call() throws Exception {
            JsonInputText inputText = readInput("input", input, stdin);
            if (inputText.error() != null) {
                return writeEnvelope(spec, inputText.error(), 2);
            }
            try {
                return writePluginOutcome(spec, CoreCommands.layoutCommand(plugin, inputText.text()));
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

        @Spec
        private CommandSpec spec;

        ValidateLayoutCommand(InputStream stdin) {
            this.stdin = stdin;
        }

        @Override
        public Integer call() throws Exception {
            JsonInputText inputText = readInput("input", input, stdin);
            if (inputText.error() != null) {
                return writeEnvelope(spec, inputText.error(), 2);
            }
            return writeValidationResult(spec, CoreCommands.validateLayoutCommand(inputText.text()));
        }
    }

    @Command(name = "render", description = "Run a render plugin")
    static final class RenderCommand implements Callable<Integer> {
        private final InputStream stdin;

        @Option(names = "--plugin", required = true)
        private String plugin;

        @Option(names = "--policy", required = true)
        private Path policy;

        @Option(names = "--metadata")
        private Path metadata;

        @Option(names = "--input")
        private Path input;

        @Spec
        private CommandSpec spec;

        RenderCommand(InputStream stdin) {
            this.stdin = stdin;
        }

        @Override
        public Integer call() throws Exception {
            JsonInputText layoutText = readInput("input", input, stdin);
            if (layoutText.error() != null) {
                return writeEnvelope(spec, layoutText.error(), 2);
            }
            JsonInputText policyText = readFile("policy", policy);
            if (policyText.error() != null) {
                return writeEnvelope(spec, policyText.error(), 2);
            }
            JsonInputText metadataText = metadata == null ? null : readFile("metadata", metadata);
            if (metadataText != null && metadataText.error() != null) {
                return writeEnvelope(spec, metadataText.error(), 2);
            }
            try {
                return writePluginOutcome(spec, CoreCommands.renderCommand(
                        plugin,
                        policyText.text(),
                        metadataText == null ? null : metadataText.text(),
                        layoutText.text()));
            } catch (PluginExecutionException error) {
                return writePluginError(spec, error);
            }
        }
    }

    @Command(name = "export", description = "Run an export plugin")
    static final class ExportCommand implements Callable<Integer> {
        @Option(names = "--plugin", required = true)
        private String plugin;

        @Option(names = "--policy", required = true)
        private Path policy;

        @Option(names = "--source", required = true)
        private Path source;

        @Option(names = "--layout", required = true)
        private Path layout;

        @Spec
        private CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            JsonInputText sourceText = readFile("source", source);
            if (sourceText.error() != null) {
                return writeEnvelope(spec, sourceText.error(), 2);
            }
            JsonInputText policyText = readFile("policy", policy);
            if (policyText.error() != null) {
                return writeEnvelope(spec, policyText.error(), 2);
            }
            JsonInputText layoutText = readFile("layout", layout);
            if (layoutText.error() != null) {
                return writeEnvelope(spec, layoutText.error(), 2);
            }
            try {
                return writePluginOutcome(spec, CoreCommands.exportCommand(
                        plugin,
                        policyText.text(),
                        sourceText.text(),
                        sourceText.baseDir(),
                        layoutText.text()));
            } catch (PluginExecutionException error) {
                return writePluginError(spec, error);
            }
        }
    }

    private static JsonInputText readInput(String label, Path input, InputStream stdin) {
        if (input == null) {
            try {
                return new JsonInputText(new String(stdin.readAllBytes(), StandardCharsets.UTF_8), null, null);
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

    private static Integer writeValidationResult(CommandSpec spec, ValidationResult result) throws IOException {
        spec.commandLine().getOut().println(JsonSupport.objectMapper().writeValueAsString(result.envelope()));
        return result.exitCode();
    }

    private static Integer writeEnvelope(CommandSpec spec, CommandEnvelope<JsonNode> envelope, int exitCode)
            throws IOException {
        spec.commandLine().getOut().println(JsonSupport.objectMapper().writeValueAsString(envelope));
        return exitCode;
    }

    private static Integer writePluginOutcome(CommandSpec spec, PluginRunOutcome outcome) {
        spec.commandLine().getOut().print(outcome.stdout());
        return outcome.exitCode();
    }

    private static Integer writePluginError(CommandSpec spec, PluginExecutionException error) throws IOException {
        return writeEnvelope(spec, CommandEnvelope.error(List.of(error.diagnostic())), 3);
    }

    private static CommandEnvelope<JsonNode> usageError(String code, String message) {
        return CommandEnvelope.error(List.of(new Diagnostic(code, DiagnosticSeverity.ERROR, message, null)));
    }

    private static CommandEnvelope<JsonNode> commandInputError(String label, Path path, IOException error) {
        String diagnosticPath = path == null ? label : label + ":" + path;
        return CommandEnvelope.error(List.of(new Diagnostic(
                "DEDIREN_COMMAND_INPUT_INVALID",
                DiagnosticSeverity.ERROR,
                "failed to read " + label + ": " + error.getMessage(),
                diagnosticPath)));
    }

    private record JsonInputText(String text, Path baseDir, CommandEnvelope<JsonNode> error) {
    }
}

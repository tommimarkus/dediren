package dev.dediren.mcp;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.ProductRootException;
import dev.dediren.core.commands.BuildCommand;
import dev.dediren.core.commands.BuildRequest;
import dev.dediren.core.commands.CoreCommands;
import dev.dediren.core.engine.EngineExecutionException;
import dev.dediren.core.engine.EngineRunOutcome;
import dev.dediren.core.source.SourceValidator;
import dev.dediren.core.source.ValidationResult;
import dev.dediren.engine.Engines;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The three tool handlers.
 *
 * <p>Each is a thin shell: confine the model-supplied paths, call the same {@code core} entry point
 * the CLI calls, and hand the resulting envelope JSON back verbatim as the tool result's text. The
 * envelope is already the agent contract — "decide success or failure from the JSON alone" — so the
 * MCP layer adds no second result format, only MCP's native {@code isError} flag on top.
 */
public final class DedirenTools {
  /** The semantics engine's wire id. A public contract string, like a schema id — not a class. */
  private static final String SEMANTICS_ENGINE = "generic-graph";

  /**
   * The model view id shape (schema {@code model.schema.json}). Applied here as a defence-in-depth
   * guard on the {@code views} tool argument: a model-supplied view id feeds {@code core}'s
   * per-view output path ({@code outDir.resolve(view)}), and this is a direct model input, not one
   * validated against an actual model view id first.
   */
  private static final Pattern VIEW_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

  private final Path root;
  private final Engines engines;
  private final Map<String, String> env;

  public DedirenTools(Path root, Engines engines, Map<String, String> env) {
    this.root = root;
    this.engines = engines;
    this.env = Map.copyOf(env);
  }

  public CallToolResult guide(CallToolRequest request) {
    String topic = stringArg(request, "topic");
    String body = topic == null ? GuideCatalog.index() : GuideCatalog.section(topic);
    return CallToolResult.builder().addTextContent(body).isError(false).build();
  }

  public CallToolResult validate(CallToolRequest request) {
    String source = stringArg(request, "source");
    if (source == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "validate requires 'source'", null);
    }
    Path sourcePath;
    try {
      sourcePath = WorkspacePaths.resolveExisting(root, source);
    } catch (PathOutsideRootException escape) {
      return pathEscape(escape);
    }
    String text;
    try {
      text = Files.readString(sourcePath);
    } catch (IOException error) {
      return readFailure("source", source, error);
    }
    Path baseDir = sourcePath.getParent();

    String profile = stringArg(request, "profile");
    try {
      if (profile != null) {
        EngineRunOutcome outcome =
            CoreCommands.semanticValidateCommand(
                SEMANTICS_ENGINE, profile, text, baseDir, root, env, engines);
        return envelope(outcome.stdout(), outcome.exitCode() != 0);
      }
      // The model chose this source, so its fragment paths are model-supplied too: confine them to
      // the same --root the tool arguments are confined to (fragment errors are sanitized in core).
      ValidationResult result = SourceValidator.validateSourceJson(text, baseDir, root);
      return envelope(serialize(result.envelope()), result.exitCode() != 0);
    } catch (EngineExecutionException failure) {
      return engineFailure(failure);
    } catch (ProductRootException failure) {
      return error(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED, failure.getMessage(), null);
    } catch (UncheckedIOException failure) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, failure.getMessage(), source);
    }
  }

  public CallToolResult build(CallToolRequest request) {
    String source = stringArg(request, "source");
    if (source == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "build requires 'source'", null);
    }
    String out = stringArg(request, "out");
    if (out == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "build requires 'out'", null);
    }
    List<String> views;
    List<String> emit;
    try {
      views = stringListArg(request, "views");
      emit = stringListArg(request, "emit");
    } catch (InvalidListArgumentException invalid) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, invalid.getMessage(), invalid.argument());
    }
    for (String view : views) {
      if (!VIEW_ID_PATTERN.matcher(view).matches()) {
        return error(DiagnosticCode.COMMAND_INPUT_INVALID, "invalid view id: " + view, view);
      }
    }

    Path sourcePath;
    Path outPath;
    String renderPolicy;
    String oefPolicy;
    String xmiPolicy;
    try {
      sourcePath = WorkspacePaths.resolveExisting(root, source);
      outPath = WorkspacePaths.resolveForWrite(root, out);
      renderPolicy = readOptionalPolicy(request, "render_policy");
      oefPolicy = readOptionalPolicy(request, "oef_policy");
      xmiPolicy = readOptionalPolicy(request, "xmi_policy");
    } catch (PathOutsideRootException escape) {
      return pathEscape(escape);
    } catch (IOException error) {
      System.err.println("dediren mcp: failed to read policy: " + error.getMessage());
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "failed to read policy", null);
    }

    String sourceText;
    try {
      sourceText = Files.readString(sourcePath);
    } catch (IOException error) {
      return readFailure("source", source, error);
    }

    BuildRequest buildRequest =
        new BuildRequest(
            sourceText,
            sourcePath.getParent(),
            // The model chose this source, so confine its fragment paths to the same --root.
            root,
            views,
            renderPolicy,
            oefPolicy,
            xmiPolicy,
            Set.copyOf(emit),
            outPath,
            env);
    try {
      EngineRunOutcome outcome = BuildCommand.run(buildRequest, engines);
      return envelope(outcome.stdout(), outcome.exitCode() != 0);
    } catch (EngineExecutionException failure) {
      return engineFailure(failure);
    } catch (ProductRootException failure) {
      return error(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED, failure.getMessage(), null);
    } catch (UncheckedIOException failure) {
      return error(DiagnosticCode.COMMAND_IO_FAILED, failure.getMessage(), null);
    }
  }

  private String readOptionalPolicy(CallToolRequest request, String argument)
      throws PathOutsideRootException, IOException {
    String value = stringArg(request, argument);
    if (value == null) {
      return null;
    }
    return Files.readString(WorkspacePaths.resolveExisting(root, value));
  }

  private static String stringArg(CallToolRequest request, String name) {
    Object value = request.arguments().get(name);
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  /**
   * The de-duplicated string elements of a list argument, in first-seen order. An absent argument
   * is an empty list; a <em>present but malformed</em> one is an error rather than a silent empty
   * list, because empty already means something specific ("build every view", "emit nothing").
   * Quietly turning a malformed request into that different, valid request is the failure this
   * guards: {@code views: [123]} used to build every view under a success envelope.
   *
   * <p>The offending element is reported by index, not by value: the index is enough to repair, and
   * an element can be an arbitrarily large nested structure.
   */
  private static List<String> stringListArg(CallToolRequest request, String name)
      throws InvalidListArgumentException {
    Object value = request.arguments().get(name);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> raw)) {
      throw new InvalidListArgumentException(name, "'" + name + "' must be an array of strings");
    }
    Set<String> items = new LinkedHashSet<>();
    for (int index = 0; index < raw.size(); index++) {
      Object item = raw.get(index);
      if (!(item instanceof String text) || text.isBlank()) {
        throw new InvalidListArgumentException(
            name, "'" + name + "'[" + index + "] must be a non-blank string");
      }
      items.add(text);
    }
    return List.copyOf(items);
  }

  /** A list tool argument whose elements are not all non-blank strings. */
  private static final class InvalidListArgumentException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String argument;

    InvalidListArgumentException(String argument, String message) {
      super(message);
      this.argument = argument;
    }

    String argument() {
      return argument;
    }
  }

  private static CallToolResult envelope(String json, boolean isError) {
    return CallToolResult.builder().addTextContent(json).isError(isError).build();
  }

  private static CallToolResult pathEscape(PathOutsideRootException escape) {
    // stderr, deliberately: escape.getMessage() is already the model-safe generic text (see
    // PathOutsideRootException's class doc). The resolved absolute target / IOException detail is
    // for human debugging only and must never reach the tool result.
    System.err.println("dediren mcp: path escape (" + escape.candidate() + "): " + escape.detail());
    return error(DiagnosticCode.MCP_PATH_OUTSIDE_ROOT, escape.getMessage(), escape.candidate());
  }

  /**
   * Builds a generic, model-safe read-failure envelope and logs the real {@link IOException} text
   * to stderr. {@code error.getMessage()} for a failed read on an already-resolved path routinely
   * carries the resolved absolute path (for example {@code NoSuchFileException}'s message is the
   * path itself), so it must never reach the model; {@code candidate} is the model's own original
   * argument and is safe to echo back.
   */
  private static CallToolResult readFailure(String label, String candidate, IOException error) {
    System.err.println(
        "dediren mcp: failed to read " + label + " '" + candidate + "': " + error.getMessage());
    return error(
        DiagnosticCode.COMMAND_INPUT_INVALID,
        "failed to read " + label + " '" + candidate + "'",
        candidate);
  }

  private static CallToolResult engineFailure(EngineExecutionException failure) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    diagnostics.add(failure.diagnostic());
    return envelope(serialize(CommandEnvelope.error(diagnostics)), true);
  }

  private static CallToolResult error(DiagnosticCode code, String message, String path) {
    return envelope(
        serialize(
            CommandEnvelope.error(
                List.of(new Diagnostic(code.code(), DiagnosticSeverity.ERROR, message, path)))),
        true);
  }

  private static String serialize(Object envelope) {
    return JsonSupport.objectMapper().writeValueAsString(envelope);
  }
}

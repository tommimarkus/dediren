package dev.dediren.mcp;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.ProductRootException;
import dev.dediren.core.commands.AnalysisCommands;
import dev.dediren.core.commands.BuildCommand;
import dev.dediren.core.commands.BuildRequest;
import dev.dediren.core.commands.CoreCommands;
import dev.dediren.core.engine.EngineExecutionException;
import dev.dediren.core.engine.EngineRunOutcome;
import dev.dediren.core.io.BoundedReads;
import dev.dediren.core.io.ConfinedPaths;
import dev.dediren.core.pkg.PackageBuildCommand;
import dev.dediren.core.pkg.PackageBuildRequest;
import dev.dediren.core.source.SourceLimits;
import dev.dediren.engine.Engines;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The tool handlers, one per registered MCP tool: import, guide, validate, diff, query, verify,
 * status, and build (model or package mode).
 *
 * <p>Each is a thin shell: confine the model-supplied paths, call the same {@code core} entry point
 * the CLI calls, and hand the resulting envelope JSON back verbatim as the tool result's text. The
 * envelope is already the agent contract — "decide success or failure from the JSON alone" — so the
 * MCP layer adds no second result format, only MCP's native {@code isError} flag on top.
 */
public final class DedirenTools {
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

  public CallToolResult importSource(CallToolRequest request) {
    String source = stringArg(request, "source");
    String content = stringArg(request, "content");
    if ((source == null) == (content == null)) {
      return error(
          DiagnosticCode.COMMAND_INPUT_INVALID,
          "import requires exactly one of 'source' or 'content'",
          null);
    }
    String plugin = stringArg(request, "plugin");
    if (plugin == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "import requires 'plugin'", null);
    }
    if (!"mermaid".equals(plugin) && !"dot".equals(plugin)) {
      return error(
          DiagnosticCode.COMMAND_INPUT_INVALID,
          "'plugin' must be 'mermaid' or 'dot'",
          "plugin");
    }
    String text;
    if (source != null) {
      Path sourcePath;
      try {
        sourcePath = WorkspacePaths.resolveExisting(root, source);
      } catch (PathOutsideRootException escape) {
        return pathEscape(escape);
      }
      try {
        text = readBounded(sourcePath);
      } catch (IOException error) {
        return readFailure("source", source, error);
      }
    } else {
      if (utf8Bytes(content) > SourceLimits.DEFAULT.maxInputFileBytes()) {
        return error(
            DiagnosticCode.INPUT_FILE_TOO_LARGE,
            "inline content exceeds the input ceiling of "
                + SourceLimits.DEFAULT.maxInputFileBytes()
                + " bytes",
            "content");
      }
      text = content;
    }
    String output = outputArg(request);
    if (output == null) {
      return error(
          DiagnosticCode.COMMAND_INPUT_INVALID, "'output' must be 'data' or 'svg'", "output");
    }
    try {
      CoreCommands.ImportedSourceResult imported =
          CoreCommands.importSource(plugin, text, env, engines);
      if (!"svg".equals(output) || !imported.succeeded()) {
        return envelope(imported.outcome().stdout(), imported.outcome().exitCode() != 0);
      }
      String policy = readImportRenderPolicy(request);
      CoreCommands.ImportedRenderResult rendered =
          CoreCommands.renderImportedMain(imported.source(), policy, env, engines);
      if (!rendered.succeeded()) {
        return envelope(rendered.outcome().stdout(), true);
      }
      if (rendered.render().artifacts().isEmpty()) {
        return error(
            DiagnosticCode.COMMAND_IO_FAILED, "render did not produce an SVG artifact", null);
      }
      String svg = rendered.render().artifacts().getFirst().content();
      if (!hasSvgRoot(svg)) {
        return error(
            DiagnosticCode.COMMAND_IO_FAILED, "render did not produce an SVG artifact", null);
      }
      return CallToolResult.builder()
          .addTextContent(imported.outcome().stdout())
          .addContent(
              new ImageContent(
                  null,
                  Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8)),
                  "image/svg+xml"))
          .isError(false)
          .build();
    } catch (EngineExecutionException failure) {
      return engineFailure(failure);
    } catch (ProductRootException failure) {
      return error(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED, failure.getMessage(), null);
    } catch (PathOutsideRootException escape) {
      return pathEscape(escape);
    } catch (PolicyReadException failure) {
      return readFailure(failure.argument(), failure.candidate(), failure.ioCause());
    }
  }

  public CallToolResult guide(CallToolRequest request) {
    String topic = stringArg(request, "topic");
    if (topic == null) {
      return CallToolResult.builder().addTextContent(GuideCatalog.index()).isError(false).build();
    }
    // An unknown topic is a failed call, not a successful one that happens to describe a failure:
    // MCP clients branch on isError, and every other bad argument in this class sets it. The body
    // still lists the valid topics, so the model can retry without a second round trip.
    return CallToolResult.builder()
        .addTextContent(GuideCatalog.section(topic))
        .isError(!GuideCatalog.hasSection(topic))
        .build();
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
      text = readBounded(sourcePath);
    } catch (IOException error) {
      return readFailure("source", source, error);
    }
    Path baseDir = sourcePath.getParent();

    String profile = stringArg(request, "profile");
    try {
      // The profile/document dispatch decision lives in core's validateCommand, shared with the
      // CLI. The model chose this source, so its fragment paths are model-supplied too: confine
      // them to the same --root the tool arguments are confined to (fragment errors are sanitized
      // in core).
      EngineRunOutcome outcome =
          CoreCommands.validateCommand(
              profile == null ? null : BuildCommand.SEMANTICS_ENGINE,
              profile,
              text,
              baseDir,
              root,
              env,
              engines);
      return envelope(outcome.stdout(), outcome.exitCode() != 0);
    } catch (EngineExecutionException failure) {
      return engineFailure(failure);
    } catch (UncheckedIOException failure) {
      return ioFailure(failure);
    } catch (ProductRootException failure) {
      return error(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED, failure.getMessage(), null);
    }
  }

  public CallToolResult diff(CallToolRequest request) {
    String oldArg = stringArg(request, "old");
    if (oldArg == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "diff requires 'old'", null);
    }
    String newArg = stringArg(request, "new");
    if (newArg == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "diff requires 'new'", null);
    }
    Path oldPath;
    Path newPath;
    try {
      oldPath = WorkspacePaths.resolveExisting(root, oldArg);
      newPath = WorkspacePaths.resolveExisting(root, newArg);
    } catch (PathOutsideRootException escape) {
      return pathEscape(escape);
    }
    String oldText;
    try {
      oldText = readBounded(oldPath);
    } catch (IOException error) {
      return readFailure("old", oldArg, error);
    }
    String newText;
    try {
      newText = readBounded(newPath);
    } catch (IOException error) {
      return readFailure("new", newArg, error);
    }
    try {
      // The model chose both sources, so their fragment paths are model-supplied too: confine them
      // to the same --root the tool arguments are confined to.
      EngineRunOutcome outcome =
          AnalysisCommands.diffCommand(
              oldText, oldPath.getParent(), newText, newPath.getParent(), root);
      return envelope(outcome.stdout(), outcome.exitCode() != 0);
    } catch (UncheckedIOException failure) {
      return ioFailure(failure);
    } catch (ProductRootException failure) {
      return error(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED, failure.getMessage(), null);
    }
  }

  public CallToolResult query(CallToolRequest request) {
    String source = stringArg(request, "source");
    if (source == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "query requires 'source'", null);
    }
    String kind = stringArg(request, "kind");
    if (kind == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "query requires 'kind'", null);
    }
    String id = stringArg(request, "id");
    Path sourcePath;
    try {
      sourcePath = WorkspacePaths.resolveExisting(root, source);
    } catch (PathOutsideRootException escape) {
      return pathEscape(escape);
    }
    String text;
    try {
      text = readBounded(sourcePath);
    } catch (IOException error) {
      return readFailure("source", source, error);
    }
    try {
      EngineRunOutcome outcome =
          AnalysisCommands.queryCommand(kind, id, text, sourcePath.getParent(), root);
      return envelope(outcome.stdout(), outcome.exitCode() != 0);
    } catch (UncheckedIOException failure) {
      return ioFailure(failure);
    } catch (ProductRootException failure) {
      return error(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED, failure.getMessage(), null);
    }
  }

  public CallToolResult verify(CallToolRequest request) {
    String source = stringArg(request, "source");
    if (source == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "verify requires 'source'", null);
    }
    String artifactsArg = stringArg(request, "artifacts");
    if (artifactsArg == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "verify requires 'artifacts'", null);
    }
    Path sourcePath;
    Path artifactsPath;
    try {
      sourcePath = WorkspacePaths.resolveExisting(root, source);
      artifactsPath = WorkspacePaths.resolveExisting(root, artifactsArg);
    } catch (PathOutsideRootException escape) {
      return pathEscape(escape);
    }
    // Report the model's own candidate, never the resolved absolute path, in the failure message.
    if (!Files.isDirectory(artifactsPath)) {
      return error(
          DiagnosticCode.COMMAND_INPUT_INVALID,
          "'artifacts' is not a directory: " + artifactsArg,
          artifactsArg);
    }
    String text;
    try {
      text = readBounded(sourcePath);
    } catch (IOException error) {
      return readFailure("source", source, error);
    }
    try {
      EngineRunOutcome outcome =
          AnalysisCommands.verifyCommand(text, sourcePath.getParent(), root, artifactsPath);
      return envelope(outcome.stdout(), outcome.exitCode() != 0);
    } catch (ProductRootException failure) {
      return error(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED, failure.getMessage(), null);
    } catch (UncheckedIOException failure) {
      return ioFailure(failure);
    }
  }

  public CallToolResult status(CallToolRequest request) {
    String dir = stringArg(request, "dir");
    Path target;
    try {
      target = WorkspacePaths.resolveExisting(root, dir == null ? "." : dir);
    } catch (PathOutsideRootException escape) {
      return pathEscape(escape);
    }
    if (!Files.isDirectory(target)) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "'dir' is not a directory: " + dir, dir);
    }
    try {
      // The walk full-loads every model candidate under 'dir', and each candidate's fragment paths
      // are model-supplied too: confine them to the same --root the tool arguments are confined to.
      EngineRunOutcome outcome = AnalysisCommands.statusCommand(target, root);
      return envelope(outcome.stdout(), outcome.exitCode() != 0);
    } catch (UncheckedIOException failure) {
      return ioFailure(failure);
    }
  }

  public CallToolResult build(CallToolRequest request) {
    String output = outputArg(request);
    if (output == null) {
      return error(
          DiagnosticCode.COMMAND_INPUT_INVALID, "'output' must be 'data' or 'svg'", "output");
    }
    String packageArg = stringArg(request, "package");
    if (packageArg != null) {
      return buildPackage(request, packageArg, output);
    }
    String source = stringArg(request, "source");
    if (source == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "build requires 'source'", null);
    }
    String out = stringArg(request, "out");
    if (out == null) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, "build requires 'out'", null);
    }
    if ("svg".equals(output) && stringArg(request, "render_policy") == null) {
      return error(
          DiagnosticCode.COMMAND_INPUT_INVALID,
          "build output 'svg' requires 'render_policy' for a source build",
          "render_policy");
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
    } catch (PolicyReadException failure) {
      return readFailure(failure.argument(), failure.candidate(), failure.ioCause());
    }

    String sourceText;
    try {
      sourceText = readBounded(sourcePath);
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
      return withBuildImages(outcome, output, outPath, false);
    } catch (EngineExecutionException failure) {
      return engineFailure(failure);
    } catch (ProductRootException failure) {
      return error(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED, failure.getMessage(), null);
    } catch (UncheckedIOException failure) {
      return error(DiagnosticCode.COMMAND_IO_FAILED, failure.getMessage(), null);
    }
  }

  private CallToolResult buildPackage(CallToolRequest request, String packageArg, String output) {
    if (stringArg(request, "source") != null
        || stringArg(request, "out") != null
        || stringArg(request, "render_policy") != null
        || stringArg(request, "oef_policy") != null
        || stringArg(request, "xmi_policy") != null) {
      return error(
          DiagnosticCode.COMMAND_INPUT_INVALID,
          "'package' is mutually exclusive with source/out/render_policy/oef_policy/xmi_policy",
          null);
    }
    List<String> views;
    try {
      views = stringListArg(request, "views");
    } catch (InvalidListArgumentException invalid) {
      return error(DiagnosticCode.COMMAND_INPUT_INVALID, invalid.getMessage(), invalid.argument());
    }
    for (String view : views) {
      if (!VIEW_ID_PATTERN.matcher(view).matches()) {
        return error(DiagnosticCode.COMMAND_INPUT_INVALID, "invalid view id: " + view, view);
      }
    }
    boolean noExport = request.arguments().get("no_export") instanceof Boolean flag && flag;

    Path packagePath;
    try {
      packagePath = WorkspacePaths.resolveExisting(root, packageArg);
    } catch (PathOutsideRootException escape) {
      return pathEscape(escape);
    }
    String packageText;
    try {
      packageText = readBounded(packagePath);
    } catch (IOException error) {
      return readFailure("package", packageArg, error);
    }

    PackageBuildRequest packageRequest =
        new PackageBuildRequest(packageText, packagePath.getParent(), root, env, views, noExport);
    try {
      EngineRunOutcome outcome = PackageBuildCommand.run(packageRequest, engines);
      return withBuildImages(outcome, output, packagePath.getParent(), true);
    } catch (EngineExecutionException failure) {
      return engineFailure(failure);
    } catch (ProductRootException failure) {
      return error(DiagnosticCode.PRODUCT_ROOT_UNRESOLVED, failure.getMessage(), null);
    } catch (UncheckedIOException failure) {
      return error(DiagnosticCode.COMMAND_IO_FAILED, failure.getMessage(), null);
    }
  }

  private String readOptionalPolicy(CallToolRequest request, String argument)
      throws PathOutsideRootException, PolicyReadException {
    String value = stringArg(request, argument);
    if (value == null) {
      return null;
    }
    Path resolved = WorkspacePaths.resolveExisting(root, value);
    try {
      return readBounded(resolved);
    } catch (IOException error) {
      // Carry the argument name out: three policy arguments share one catch in build(), and an
      // agent repairing from the envelope has to know which one to fix.
      throw new PolicyReadException(argument, value, error);
    }
  }

  private String readImportRenderPolicy(CallToolRequest request)
      throws PathOutsideRootException, PolicyReadException {
    String selected = readOptionalPolicy(request, "render_policy");
    if (selected != null) {
      return selected;
    }
    Path bundled = DedirenPaths.productRoot().resolve("fixtures/render-policy/default-svg.json");
    try {
      return readBounded(bundled);
    } catch (IOException error) {
      throw new PolicyReadException("render_policy", "bundled default SVG policy", error);
    }
  }

  private CallToolResult withBuildImages(
      EngineRunOutcome outcome, String output, Path artifactRoot, boolean packageBuild) {
    if (!"svg".equals(output)) {
      return envelope(outcome.stdout(), outcome.exitCode() != 0);
    }
    try {
      List<ImageContent> images = buildSvgImages(outcome.stdout(), artifactRoot, packageBuild);
      CallToolResult.Builder result =
          CallToolResult.builder()
              .addTextContent(outcome.stdout())
              .isError(outcome.exitCode() != 0);
      images.forEach(result::addContent);
      return result.build();
    } catch (InlineArtifactException failure) {
      System.err.println("dediren mcp: inline SVG artifact failure: " + failure.getMessage());
      return error(DiagnosticCode.COMMAND_IO_FAILED, "could not read generated SVG artifact", null);
    }
  }

  private static List<ImageContent> buildSvgImages(
      String outcomeJson, Path artifactRoot, boolean packageBuild) throws InlineArtifactException {
    if (utf8Bytes(outcomeJson) > SourceLimits.DEFAULT.maxInputFileBytes()) {
      throw new InlineArtifactException("result exceeds the bounded JSON ceiling");
    }
    JsonNode result;
    try {
      result = JsonSupport.objectMapper().readTree(outcomeJson);
    } catch (RuntimeException parseFailure) {
      throw new InlineArtifactException("result is not JSON", parseFailure);
    }
    JsonNode data = packageBuild ? result.path("data") : result;
    if (packageBuild
        ? !"package-build-result.schema.v1".equals(data.path("package_build_result_schema_version").asText())
        : !"build-result.schema.v1".equals(data.path("build_result_schema_version").asText())) {
      throw new InlineArtifactException("result has an unexpected build schema");
    }
    JsonNode views = data.path("views");
    if (!views.isArray()) {
      throw new InlineArtifactException("result does not declare views");
    }
    List<String> paths = new ArrayList<>();
    for (JsonNode view : views) {
      if (packageBuild) {
        JsonNode diagram = view.path("artifacts").path("diagram");
        if (diagram.isTextual()) {
          paths.add(diagram.asText());
        }
      } else {
        JsonNode artifacts = view.path("artifacts");
        if (!artifacts.isArray()) {
          throw new InlineArtifactException("view does not declare artifacts");
        }
        for (JsonNode artifact : artifacts) {
          if ("svg".equals(artifact.path("artifact_kind").asText())
              && artifact.path("path").isTextual()) {
            paths.add(artifact.path("path").asText());
          }
        }
      }
    }
    List<ImageContent> images = new ArrayList<>();
    long total = 0;
    for (String path : paths) {
      Path svg;
      try {
        svg = ConfinedPaths.resolveExisting(artifactRoot, artifactRoot.resolve(path));
      } catch (ConfinedPaths.PathEscapeException escape) {
        throw new InlineArtifactException("reported artifact escapes its output root", escape);
      }
      try {
        if (!Files.isRegularFile(svg)) {
          throw new InlineArtifactException("reported artifact is not a regular file");
        }
        long size = Files.size(svg);
        if (size > SourceLimits.DEFAULT.maxInputFileBytes() - total) {
          throw new InlineArtifactException("decoded SVG artifacts exceed 64 MiB");
        }
        String content = BoundedReads.readString(svg, SourceLimits.DEFAULT.maxInputFileBytes());
        if (!hasSvgRoot(content)) {
          throw new InlineArtifactException("reported SVG artifact has no SVG root");
        }
        total += size;
        images.add(
            new ImageContent(
                null,
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)),
                "image/svg+xml"));
      } catch (IOException error) {
        throw new InlineArtifactException("could not read reported artifact", error);
      }
    }
    return List.copyOf(images);
  }

  private static boolean hasSvgRoot(String content) {
    String trimmed = content.stripLeading();
    int root = trimmed.indexOf("<svg");
    return root >= 0
        && (root + 4 == trimmed.length()
            || Character.isWhitespace(trimmed.charAt(root + 4))
            || trimmed.charAt(root + 4) == '>');
  }

  private static long utf8Bytes(String text) {
    long bytes = 0;
    for (int index = 0; index < text.length(); index++) {
      char unit = text.charAt(index);
      if (unit <= 0x7f) {
        bytes++;
      } else if (unit <= 0x7ff) {
        bytes += 2;
      } else if (Character.isHighSurrogate(unit)
          && index + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(index + 1))) {
        bytes += 4;
        index++;
      } else if (Character.isSurrogate(unit)) {
        // Standard UTF-8 encoding replaces an unpaired surrogate with one ASCII replacement byte.
        bytes++;
      } else {
        bytes += 3;
      }
    }
    return bytes;
  }

  private static String outputArg(CallToolRequest request) {
    Object value = request.arguments().get("output");
    if (value == null) {
      return "data";
    }
    return value instanceof String output && ("data".equals(output) || "svg".equals(output))
        ? output
        : null;
  }

  private static final class InlineArtifactException extends Exception {
    private static final long serialVersionUID = 1L;

    InlineArtifactException(String message) {
      super(message);
    }

    InlineArtifactException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A policy argument that resolved inside the root but could not be read. */
  private static final class PolicyReadException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String argument;
    private final String candidate;
    private final IOException cause;

    PolicyReadException(String argument, String candidate, IOException cause) {
      super(cause);
      this.argument = argument;
      this.candidate = candidate;
      this.cause = cause;
    }

    String argument() {
      return argument;
    }

    String candidate() {
      return candidate;
    }

    IOException ioCause() {
      return cause;
    }
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
   * Every model-supplied path is read through the input byte ceiling, so an oversized file becomes
   * a clean {@code INPUT_FILE_TOO_LARGE} envelope instead of an allocation the size of the file.
   */
  private static String readBounded(Path path) throws IOException {
    return BoundedReads.readString(path, SourceLimits.DEFAULT.maxInputFileBytes());
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
    if (error instanceof BoundedReads.FileTooLargeException) {
      // Byte counts are safe to echo; a resolved path is not. The candidate is the model's own
      // argument string, so the sanitization discipline of the plain read-failure branch holds.
      return error(
          DiagnosticCode.INPUT_FILE_TOO_LARGE,
          "failed to read " + label + " '" + candidate + "': " + error.getMessage(),
          candidate);
    }
    return error(
        DiagnosticCode.COMMAND_INPUT_INVALID,
        "failed to read " + label + " '" + candidate + "'",
        candidate);
  }

  /**
   * A workspace I/O failure while walking the confined tree (for example an unreadable subdirectory
   * under {@code artifacts}/{@code dir}), or a product schema that failed to load during source
   * validation (a broken install). The {@link UncheckedIOException}'s message can carry a resolved
   * absolute path (an {@code AccessDeniedException} names the path itself), so keep it stderr-only
   * and return a path-free envelope — the same discipline {@link #pathEscape} and {@link
   * #readFailure} apply. ({@code build}'s own catch keeps the message verbatim on purpose: it is
   * pinned lane-for-lane against the CLI's {@code printCommandIoFailure} by {@code
   * CliMcpParityTest}, and the CLI lane deliberately surfaces the path for human debugging.)
   */
  private static CallToolResult ioFailure(UncheckedIOException failure) {
    System.err.println("dediren mcp: workspace I/O failure: " + failure.getMessage());
    return error(
        DiagnosticCode.COMMAND_IO_FAILED, "an I/O error occurred accessing the workspace", null);
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

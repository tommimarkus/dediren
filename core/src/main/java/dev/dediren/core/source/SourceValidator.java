package dev.dediren.core.source;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.PluginRequirement;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.schema.SchemaValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class SourceValidator {
  private SourceValidator() {}

  public static ValidationResult validateSourceJson(String text, Path baseDir) {
    return validateSourceJson(text, baseDir, null);
  }

  /**
   * The confinement-aware overload. When {@code confinementRoot} is non-null (the MCP trust
   * boundary, where a model — not a human — chose the fragment paths), every source-fragment path
   * is confined to that root and fragment errors are sanitized so the model-facing envelope cannot
   * fingerprint the host filesystem. When it is null (the CLI/human lane), behaviour is
   * byte-for-byte identical to the two-argument form: no confinement, and read failures carry their
   * real message, because a human legitimately references fragments across their own project.
   */
  public static ValidationResult validateSourceJson(
      String text, Path baseDir, Path confinementRoot) {
    try {
      SourceDocument document = loadAndValidateSourceDocument(text, baseDir, confinementRoot);
      var data = JsonSupport.objectMapper().createObjectNode();
      data.put("model_schema_version", document.modelSchemaVersion());
      data.put("node_count", document.nodes().size());
      data.put("relationship_count", document.relationships().size());
      return new ValidationResult(CommandExitCode.OK.code(), CommandEnvelope.ok(data));
    } catch (SourceDiagnosticsException error) {
      return new ValidationResult(
          CommandExitCode.INPUT_ERROR.code(), CommandEnvelope.error(error.diagnostics()));
    }
  }

  public static SourceDocument loadAndValidateSourceDocument(String text, Path baseDir)
      throws SourceDiagnosticsException {
    return loadAndValidateSourceDocument(text, baseDir, null);
  }

  /**
   * Confinement-aware overload; see {@link #validateSourceJson(String, Path, Path)} for the meaning
   * of {@code confinementRoot}.
   */
  public static SourceDocument loadAndValidateSourceDocument(
      String text, Path baseDir, Path confinementRoot) throws SourceDiagnosticsException {
    SourceDocument document = loadSourceDocument(text, baseDir, confinementRoot);
    List<Diagnostic> diagnostics = validateSourceDocument(document);
    if (!diagnostics.isEmpty()) {
      throw new SourceDiagnosticsException(diagnostics);
    }
    return document;
  }

  private static SourceDocument loadSourceDocument(String text, Path baseDir, Path confinementRoot)
      throws SourceDiagnosticsException {
    SourceDocument root = parseSourceDocument(text);
    if (root.fragments().isEmpty()) {
      return root;
    }
    if (baseDir == null) {
      throw new SourceDiagnosticsException(
          List.of(
              error(
                  DiagnosticCode.FRAGMENT_BASE_DIR_REQUIRED,
                  "source fragments require file input so relative fragment paths can be resolved",
                  "$.fragments")));
    }

    var requiredPlugins = new ArrayList<>(root.requiredPlugins());
    var nodes = new ArrayList<>(root.nodes());
    var relationships = new ArrayList<>(root.relationships());
    var plugins = new LinkedHashMap<>(root.plugins());
    for (int i = 0; i < root.fragments().size(); i++) {
      String fragment = root.fragments().get(i);
      Path fragmentPath = Path.of(fragment);
      if (fragmentPath.isAbsolute()) {
        throw new SourceDiagnosticsException(
            List.of(
                error(
                    DiagnosticCode.FRAGMENT_PATH_UNSUPPORTED,
                    "fragment '" + fragment + "' must be relative to the source model",
                    "$.fragments[" + i + "]")));
      }
      Path fragmentReadPath =
          resolveFragmentReadPath(confinementRoot, baseDir, fragment, fragmentPath, i);
      SourceDocument fragmentDocument;
      try {
        fragmentDocument = parseSourceDocument(Files.readString(fragmentReadPath));
      } catch (IOException readError) {
        // CLI/human lane (null root): the real message helps a human and routinely carries the
        // resolved absolute path. MCP lane (root set): sanitize — echo only the model-supplied
        // fragment string, never the resolved absolute path or an exists-vs-not-exists signal.
        throw new SourceDiagnosticsException(
            List.of(
                error(
                    DiagnosticCode.FRAGMENT_READ_FAILED,
                    confinementRoot == null
                        ? "failed to read fragment '" + fragment + "': " + readError.getMessage()
                        : "failed to read fragment '" + fragment + "'",
                    "$.fragments[" + i + "]")));
      }
      if (!fragmentDocument.fragments().isEmpty()) {
        throw new SourceDiagnosticsException(
            List.of(
                error(
                    DiagnosticCode.FRAGMENT_NESTED_UNSUPPORTED,
                    "fragment '" + fragment + "' declares nested fragments",
                    "$.fragments[" + i + "]")));
      }
      mergeRequiredPlugins(requiredPlugins, fragmentDocument.requiredPlugins());
      nodes.addAll(fragmentDocument.nodes());
      relationships.addAll(fragmentDocument.relationships());
      mergePlugins(plugins, fragmentDocument.plugins(), "$.plugins");
    }
    return new SourceDocument(
        root.modelSchemaVersion(), List.of(), requiredPlugins, nodes, relationships, plugins);
  }

  /**
   * Resolves the path a source fragment is read from — the confinement decision and the read path
   * are the same computation, so there is no second, independently-resolved path for the two to
   * diverge on.
   *
   * <p>On the CLI/human lane ({@code confinementRoot} is null) this is byte-identical to the
   * pre-confinement behaviour: the raw, unresolved {@code baseDir.resolve(fragmentPath)}, because a
   * human legitimately references fragments across their own project and read failures should carry
   * their real message.
   *
   * <p>On the MCP lane ({@code confinementRoot} is non-null, a model — not a human — chose the
   * path) the path is confined to {@code confinementRoot} before it is returned. Mirrors {@code mcp
   * WorkspacePaths.resolveForWrite} (core cannot depend on the mcp module): walk up from the
   * requested path to the nearest existing ancestor, real-path-resolve that ancestor (following
   * symlinks), then re-append the remainder and confine the result to the real root.
   *
   * <p>Critically, the walk starts from the <em>unnormalized</em> {@code baseDir.resolve(
   * fragmentPath)} — calling {@link Path#normalize()} on the full path before the walk would
   * lexically collapse a {@code link/..} sequence with no regard for what {@code link} actually is
   * on disk, discarding the symlink before the walk (or the eventual physical read) ever sees it.
   * {@link Files#exists} and {@link Path#toRealPath} both resolve symlinks physically,
   * component-by-component — exactly like the OS call a read performs — so leaving the path
   * unnormalized until it is anchored on a real, existing ancestor keeps the confinement decision
   * and the physical read looking at the same target.
   *
   * <p>Fail closed and sanitized: an escape — or a root/ancestor that cannot be real-path-resolved
   * — yields {@link DiagnosticCode#MCP_PATH_OUTSIDE_ROOT} carrying only the model-supplied fragment
   * string, never the resolved absolute target, so the model-facing envelope cannot fingerprint the
   * host filesystem. A fragment that stays within the root but does not exist is left to the read
   * step, which reports it as a sanitized {@code FRAGMENT_READ_FAILED}.
   */
  private static Path resolveFragmentReadPath(
      Path confinementRoot, Path baseDir, String fragment, Path fragmentPath, int index)
      throws SourceDiagnosticsException {
    Path resolved = baseDir.resolve(fragmentPath);
    if (confinementRoot == null) {
      return resolved;
    }
    Path realRoot;
    try {
      realRoot = confinementRoot.toRealPath();
    } catch (IOException error) {
      throw fragmentOutsideRoot(fragment, index);
    }
    Path existing = resolved;
    while (existing != null && !existing.toFile().exists()) {
      existing = existing.getParent();
    }
    if (existing == null) {
      throw fragmentOutsideRoot(fragment, index);
    }
    Path realExisting;
    try {
      realExisting = existing.toRealPath();
    } catch (IOException error) {
      throw fragmentOutsideRoot(fragment, index);
    }
    if (!realExisting.startsWith(realRoot)) {
      throw fragmentOutsideRoot(fragment, index);
    }
    Path target = realExisting.resolve(existing.relativize(resolved)).normalize();
    if (!target.startsWith(realRoot)) {
      throw fragmentOutsideRoot(fragment, index);
    }
    return target;
  }

  private static SourceDiagnosticsException fragmentOutsideRoot(String fragment, int index) {
    return new SourceDiagnosticsException(
        List.of(
            error(
                DiagnosticCode.MCP_PATH_OUTSIDE_ROOT,
                "fragment '" + fragment + "' resolves outside the workspace root",
                "$.fragments[" + index + "]")));
  }

  private static SourceDocument parseSourceDocument(String text) throws SourceDiagnosticsException {
    JsonNode value;
    try {
      value = JsonSupport.objectMapper().readTree(text);
    } catch (JacksonException error) {
      throw new SourceDiagnosticsException(List.of(schemaError(error.getMessage())));
    }
    var errors =
        SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
            .validate("schemas/model.schema.json", value);
    if (!errors.isEmpty()) {
      throw new SourceDiagnosticsException(
          errors.stream().map(SourceValidator::schemaError).toList());
    }
    try {
      return JsonSupport.objectMapper().treeToValue(value, SourceDocument.class);
    } catch (JacksonException error) {
      throw new SourceDiagnosticsException(List.of(schemaError(error.getMessage())));
    }
  }

  private static List<Diagnostic> validateSourceDocument(SourceDocument document) {
    var diagnostics = new ArrayList<Diagnostic>();
    var ids = new java.util.HashSet<String>();
    var nodeIds = new java.util.HashSet<String>();
    for (SourceNode node : document.nodes()) {
      if (!ids.add(node.id())) {
        diagnostics.add(
            error(
                DiagnosticCode.DUPLICATE_ID,
                "duplicate id '" + node.id() + "'",
                "$.nodes[?(@.id=='" + node.id() + "')]"));
      }
      nodeIds.add(node.id());
    }
    for (SourceRelationship relationship : document.relationships()) {
      if (!ids.add(relationship.id())) {
        diagnostics.add(
            error(
                DiagnosticCode.DUPLICATE_ID,
                "duplicate id '" + relationship.id() + "'",
                "$.relationships[?(@.id=='" + relationship.id() + "')]"));
      }
      if (!nodeIds.contains(relationship.source())) {
        diagnostics.add(
            error(
                DiagnosticCode.DANGLING_ENDPOINT,
                "relationship '"
                    + relationship.id()
                    + "' references missing source '"
                    + relationship.source()
                    + "'",
                "$.relationships[?(@.id=='" + relationship.id() + "')].source"));
      }
      if (!nodeIds.contains(relationship.target())) {
        diagnostics.add(
            error(
                DiagnosticCode.DANGLING_ENDPOINT,
                "relationship '"
                    + relationship.id()
                    + "' references missing target '"
                    + relationship.target()
                    + "'",
                "$.relationships[?(@.id=='" + relationship.id() + "')].target"));
      }
    }
    return diagnostics;
  }

  private static void mergeRequiredPlugins(
      List<PluginRequirement> root, List<PluginRequirement> fragment)
      throws SourceDiagnosticsException {
    for (PluginRequirement requirement : fragment) {
      var existing = root.stream().filter(item -> item.id().equals(requirement.id())).findFirst();
      if (existing.isPresent() && !existing.get().version().equals(requirement.version())) {
        throw new SourceDiagnosticsException(
            List.of(
                error(
                    DiagnosticCode.FRAGMENT_CONFLICT,
                    "required plugin '"
                        + requirement.id()
                        + "' has conflicting versions '"
                        + existing.get().version()
                        + "' and '"
                        + requirement.version()
                        + "'",
                    "$.required_plugins")));
      }
      if (existing.isEmpty()) {
        root.add(requirement);
      }
    }
  }

  private static void mergePlugins(
      Map<String, JsonNode> root, Map<String, JsonNode> fragment, String path)
      throws SourceDiagnosticsException {
    for (Map.Entry<String, JsonNode> entry : fragment.entrySet()) {
      JsonNode existing = root.get(entry.getKey());
      if (existing == null) {
        root.put(entry.getKey(), entry.getValue());
      } else {
        root.put(
            entry.getKey(), mergeValue(existing, entry.getValue(), path + "." + entry.getKey()));
      }
    }
  }

  private static JsonNode mergeValue(JsonNode target, JsonNode source, String path)
      throws SourceDiagnosticsException {
    if (target.isObject() && source.isObject()) {
      ObjectNode merged = ((ObjectNode) target.deepCopy());
      var fields = source.properties().iterator();
      while (fields.hasNext()) {
        var field = fields.next();
        JsonNode existing = merged.get(field.getKey());
        merged.set(
            field.getKey(),
            existing == null
                ? field.getValue()
                : mergeValue(existing, field.getValue(), path + "." + field.getKey()));
      }
      return merged;
    }
    if (target.isArray() && source.isArray()) {
      ArrayNode merged = (ArrayNode) target.deepCopy();
      source.forEach(merged::add);
      return merged;
    }
    if (target.equals(source)) {
      return target;
    }
    throw new SourceDiagnosticsException(
        List.of(
            error(DiagnosticCode.FRAGMENT_CONFLICT, "fragment value conflicts at " + path, path)));
  }

  private static Diagnostic schemaError(String message) {
    return error(DiagnosticCode.SCHEMA_INVALID, message, null);
  }

  private static Diagnostic error(DiagnosticCode code, String message, String path) {
    return new Diagnostic(code.code(), DiagnosticSeverity.ERROR, message, path);
  }

  public static final class SourceDiagnosticsException extends Exception {
    private final List<Diagnostic> diagnostics;

    SourceDiagnosticsException(List<Diagnostic> diagnostics) {
      super(diagnostics.isEmpty() ? "source diagnostics" : diagnostics.getFirst().message());
      this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
      return diagnostics;
    }
  }
}

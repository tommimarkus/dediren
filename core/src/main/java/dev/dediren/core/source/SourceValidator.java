package dev.dediren.core.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.schema.SchemaValidator;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.PluginRequirement;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SourceValidator {
    private SourceValidator() {
    }

    public static ValidationResult validateSourceJson(String text, Path baseDir) {
        try {
            SourceDocument document = loadAndValidateSourceDocument(text, baseDir);
            var data = JsonSupport.objectMapper().createObjectNode();
            data.put("model_schema_version", document.modelSchemaVersion());
            data.put("node_count", document.nodes().size());
            data.put("relationship_count", document.relationships().size());
            return new ValidationResult(0, CommandEnvelope.ok(data));
        } catch (SourceDiagnosticsException error) {
            return new ValidationResult(2, CommandEnvelope.error(error.diagnostics()));
        }
    }

    public static SourceDocument loadAndValidateSourceDocument(String text, Path baseDir)
            throws SourceDiagnosticsException {
        SourceDocument document = loadSourceDocument(text, baseDir);
        List<Diagnostic> diagnostics = validateSourceDocument(document);
        if (!diagnostics.isEmpty()) {
            throw new SourceDiagnosticsException(diagnostics);
        }
        return document;
    }

    private static SourceDocument loadSourceDocument(String text, Path baseDir) throws SourceDiagnosticsException {
        SourceDocument root = parseSourceDocument(text);
        if (root.fragments().isEmpty()) {
            return root;
        }
        if (baseDir == null) {
            throw new SourceDiagnosticsException(List.of(error(
                    "DEDIREN_FRAGMENT_BASE_DIR_REQUIRED",
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
                throw new SourceDiagnosticsException(List.of(error(
                        "DEDIREN_FRAGMENT_PATH_UNSUPPORTED",
                        "fragment '" + fragment + "' must be relative to the source model",
                        "$.fragments[" + i + "]")));
            }
            SourceDocument fragmentDocument;
            try {
                fragmentDocument = parseSourceDocument(Files.readString(baseDir.resolve(fragmentPath)));
            } catch (IOException readError) {
                throw new SourceDiagnosticsException(List.of(error(
                        "DEDIREN_FRAGMENT_READ_FAILED",
                        "failed to read fragment '" + fragment + "': " + readError.getMessage(),
                        "$.fragments[" + i + "]")));
            }
            if (!fragmentDocument.fragments().isEmpty()) {
                throw new SourceDiagnosticsException(List.of(error(
                        "DEDIREN_FRAGMENT_NESTED_UNSUPPORTED",
                        "fragment '" + fragment + "' declares nested fragments",
                        "$.fragments[" + i + "]")));
            }
            mergeRequiredPlugins(requiredPlugins, fragmentDocument.requiredPlugins());
            nodes.addAll(fragmentDocument.nodes());
            relationships.addAll(fragmentDocument.relationships());
            mergePlugins(plugins, fragmentDocument.plugins(), "$.plugins");
        }
        return new SourceDocument(
                root.modelSchemaVersion(),
                List.of(),
                requiredPlugins,
                nodes,
                relationships,
                plugins);
    }

    private static SourceDocument parseSourceDocument(String text) throws SourceDiagnosticsException {
        JsonNode value;
        try {
            value = JsonSupport.objectMapper().readTree(text);
        } catch (IOException error) {
            throw new SourceDiagnosticsException(List.of(schemaError(error.getMessage())));
        }
        var errors = SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
                .validate("schemas/model.schema.json", value);
        if (!errors.isEmpty()) {
            throw new SourceDiagnosticsException(List.of(schemaError(errors.getFirst())));
        }
        try {
            return JsonSupport.objectMapper().treeToValue(value, SourceDocument.class);
        } catch (IOException error) {
            throw new SourceDiagnosticsException(List.of(schemaError(error.getMessage())));
        }
    }

    private static List<Diagnostic> validateSourceDocument(SourceDocument document) {
        var diagnostics = new ArrayList<Diagnostic>();
        var ids = new java.util.HashSet<String>();
        var nodeIds = new java.util.HashSet<String>();
        for (SourceNode node : document.nodes()) {
            if (!ids.add(node.id())) {
                diagnostics.add(error("DEDIREN_DUPLICATE_ID", "duplicate id '" + node.id() + "'",
                        "$.nodes[?(@.id=='" + node.id() + "')]"));
            }
            nodeIds.add(node.id());
        }
        for (SourceRelationship relationship : document.relationships()) {
            if (!ids.add(relationship.id())) {
                diagnostics.add(error("DEDIREN_DUPLICATE_ID", "duplicate id '" + relationship.id() + "'",
                        "$.relationships[?(@.id=='" + relationship.id() + "')]"));
            }
            if (!nodeIds.contains(relationship.source())) {
                diagnostics.add(error(
                        "DEDIREN_DANGLING_ENDPOINT",
                        "relationship '" + relationship.id() + "' references missing source '" + relationship.source() + "'",
                        "$.relationships[?(@.id=='" + relationship.id() + "')].source"));
            }
            if (!nodeIds.contains(relationship.target())) {
                diagnostics.add(error(
                        "DEDIREN_DANGLING_ENDPOINT",
                        "relationship '" + relationship.id() + "' references missing target '" + relationship.target() + "'",
                        "$.relationships[?(@.id=='" + relationship.id() + "')].target"));
            }
        }
        return diagnostics;
    }

    private static void mergeRequiredPlugins(List<PluginRequirement> root, List<PluginRequirement> fragment)
            throws SourceDiagnosticsException {
        for (PluginRequirement requirement : fragment) {
            var existing = root.stream()
                    .filter(item -> item.id().equals(requirement.id()))
                    .findFirst();
            if (existing.isPresent() && !existing.get().version().equals(requirement.version())) {
                throw new SourceDiagnosticsException(List.of(error(
                        "DEDIREN_FRAGMENT_CONFLICT",
                        "required plugin '" + requirement.id() + "' has conflicting versions '"
                                + existing.get().version() + "' and '" + requirement.version() + "'",
                        "$.required_plugins")));
            }
            if (existing.isEmpty()) {
                root.add(requirement);
            }
        }
    }

    private static void mergePlugins(Map<String, JsonNode> root, Map<String, JsonNode> fragment, String path)
            throws SourceDiagnosticsException {
        for (Map.Entry<String, JsonNode> entry : fragment.entrySet()) {
            JsonNode existing = root.get(entry.getKey());
            if (existing == null) {
                root.put(entry.getKey(), entry.getValue());
            } else {
                root.put(entry.getKey(), mergeValue(existing, entry.getValue(), path + "." + entry.getKey()));
            }
        }
    }

    private static JsonNode mergeValue(JsonNode target, JsonNode source, String path) throws SourceDiagnosticsException {
        if (target.isObject() && source.isObject()) {
            ObjectNode merged = ((ObjectNode) target.deepCopy());
            var fields = source.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode existing = merged.get(field.getKey());
                merged.set(field.getKey(), existing == null
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
        throw new SourceDiagnosticsException(List.of(error(
                "DEDIREN_FRAGMENT_CONFLICT",
                "fragment value conflicts at " + path,
                path)));
    }

    private static Diagnostic schemaError(String message) {
        return error("DEDIREN_SCHEMA_INVALID", message, null);
    }

    private static Diagnostic error(String code, String message, String path) {
        return new Diagnostic(code, DiagnosticSeverity.ERROR, message, path);
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

package dev.dediren.core.source;

import dev.dediren.contracts.CommandEnvelope;
import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.KnownSchemaVersions;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.DedirenPaths;
import dev.dediren.core.schema.SchemaValidator;
import dev.dediren.core.schema.SchemaVersionGate;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Family-dispatching front door for {@code validate}: a hand-authored document is recognized by the
 * version field it carries and validated against its own family — the source model keeps its full
 * semantic path, while a policy or kept layout-request gets the same version gate plus JSON Schema
 * validation it would otherwise only meet at build time (the schema-migration design's "Known
 * asymmetry", closed).
 *
 * <p>Detection is by version field, current or legacy, so the renamed-field wrinkle
 * (svg_render_policy_schema_version) still routes to the render-policy family. A document carrying
 * {@code model_schema_version} — or none of the registered fields — takes the source-model path,
 * preserving today's behaviour byte for byte for models and for unrecognizable input.
 */
public final class DocumentValidator {

  private static final Map<String, String> SCHEMA_FILES =
      Map.of(
          "render-policy", "schemas/render-policy.schema.json",
          "oef-export-policy", "schemas/oef-export-policy.schema.json",
          "uml-xmi-export-policy", "schemas/uml-xmi-export-policy.schema.json",
          "layout-request", "schemas/layout-request.schema.json");

  private DocumentValidator() {}

  /**
   * See {@link SourceValidator#validateSourceJson(String, Path, Path)} for {@code confinementRoot};
   * it only affects the source-model path (policies carry no fragments).
   */
  public static ValidationResult validateDocument(String text, Path baseDir, Path confinementRoot) {
    JsonNode value;
    try {
      value = JsonSupport.objectMapper().readTree(text);
    } catch (JacksonException error) {
      // Unparseable input keeps today's source-model observable exactly.
      return SourceValidator.validateSourceJson(text, baseDir, confinementRoot);
    }
    KnownSchemaVersions.Family family = detectNonModelFamily(value);
    if (family == null) {
      return SourceValidator.validateSourceJson(text, baseDir, confinementRoot);
    }

    Optional<Diagnostic> staleVersion = SchemaVersionGate.check(family, value);
    if (staleVersion.isPresent()) {
      return new ValidationResult(
          CommandExitCode.INPUT_ERROR.code(), CommandEnvelope.error(List.of(staleVersion.get())));
    }
    List<String> errors =
        SchemaValidator.fromRepositoryRoot(DedirenPaths.productRoot())
            .validate(SCHEMA_FILES.get(family.name()), value);
    if (!errors.isEmpty()) {
      return new ValidationResult(
          CommandExitCode.INPUT_ERROR.code(),
          CommandEnvelope.error(
              errors.stream()
                  .map(
                      message ->
                          new Diagnostic(
                              DiagnosticCode.SCHEMA_INVALID.code(),
                              DiagnosticSeverity.ERROR,
                              message,
                              null))
                  .toList()));
    }
    var data = JsonSupport.objectMapper().createObjectNode();
    data.put(family.versionField(), family.currentVersion());
    return new ValidationResult(CommandExitCode.OK.code(), CommandEnvelope.ok(data));
  }

  /**
   * The non-model family whose version field (current or legacy) this document carries, or null for
   * the source-model path. {@code model_schema_version} wins outright so a degenerate document
   * carrying several version fields stays a model deterministically.
   */
  private static KnownSchemaVersions.Family detectNonModelFamily(JsonNode value) {
    if (!value.isObject() || value.has(KnownSchemaVersions.MODEL.versionField())) {
      return null;
    }
    for (KnownSchemaVersions.Family family : KnownSchemaVersions.ALL) {
      if (family == KnownSchemaVersions.MODEL) {
        continue;
      }
      for (String field : family.versionFields()) {
        if (value.has(field)) {
          return family;
        }
      }
    }
    return null;
  }
}

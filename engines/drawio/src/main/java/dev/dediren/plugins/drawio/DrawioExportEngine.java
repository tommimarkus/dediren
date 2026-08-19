package dev.dediren.plugins.drawio;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.export.DrawioExportPolicy;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.ExportEngine;
import dev.dediren.plugins.drawio.mx.MxWriter;
import dev.dediren.plugins.drawio.write.DrawioDocumentBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * First-party {@link ExportEngine} emitting a draw.io (mxGraph/{@code .drawio}) artifact from a
 * source model and its laid-out view. Structured like the OEF exporter — validate the policy,
 * build, serialise, return the accumulated diagnostics — with two deliberate differences.
 *
 * <p><strong>No schema fetch, so no {@code env} or {@code productRoot} use.</strong> Both
 * parameters are part of the {@link ExportEngine} contract and are ignored here: the mxGraph format
 * has no published schema this export validates against, so there is nothing to cache, download, or
 * resolve against a product root. That is why this engine has no {@code schema-cache} edge.
 *
 * <p><strong>Mapping gaps degrade rather than fail.</strong> The OEF exporter turns a layout
 * reference that resolves to nothing into an error envelope; here it is a warning on an artifact
 * that still opens, because a {@code .drawio} is an editable picture rather than a model
 * interchange document. Only a policy this engine cannot read stops the export.
 *
 * <h2>Policy validation</h2>
 *
 * <p>The required fields are checked by hand before {@code treeToValue}, exactly as the OEF
 * exporter does and for the same reason: on the standalone {@code export} lane the engine receives
 * a raw {@link JsonNode} that no schema validator has seen, so an absent field would otherwise
 * surface as a null deep inside the build rather than as {@code DEDIREN_DRAWIO_POLICY_INVALID}. The
 * binding itself then rejects a field the schema does not declare, since the mapper is configured
 * to fail on unknown properties and the policy schema is {@code additionalProperties: false}.
 */
public final class DrawioExportEngine implements ExportEngine {
  public static final String ARTIFACT_KIND = "drawio+xml";

  @Override
  public String id() {
    return "drawio";
  }

  @Override
  public EngineResult<ExportResult> export(
      ExportRequest request, Map<String, String> env, Path productRoot) throws EngineException {
    Objects.requireNonNull(request, "request");
    DrawioExportPolicy policy = readPolicy(request.policy());

    DrawioDocumentBuilder.Document document =
        DrawioDocumentBuilder.build(request.source(), request.layoutResult(), policy);
    String content = MxWriter.write(document.file());

    return new EngineResult<>(
        new ExportResult(ContractVersions.EXPORT_RESULT_SCHEMA_VERSION, ARTIFACT_KIND, content),
        document.diagnostics());
  }

  private static DrawioExportPolicy readPolicy(JsonNode policy) throws EngineException {
    try {
      validatePolicy(policy);
      return JsonSupport.objectMapper().treeToValue(policy, DrawioExportPolicy.class);
    } catch (IllegalArgumentException | JacksonException invalid) {
      throw EngineException.semanticFailure(
          DiagnosticCode.DRAWIO_POLICY_INVALID.code(), invalid.getMessage(), "policy");
    }
  }

  /** Mirrors {@code schemas/drawio-export-policy.schema.json}'s required fields and constants. */
  private static void validatePolicy(JsonNode policy) {
    if (policy == null || !policy.isObject()) {
      throw new IllegalArgumentException("policy must be an object");
    }
    for (String field : List.of("drawio_export_policy_schema_version", "diagram_name")) {
      if (!policy.hasNonNull(field) || !policy.get(field).isString()) {
        throw new IllegalArgumentException("policy missing required string field " + field);
      }
    }
    String declaredVersion = policy.get("drawio_export_policy_schema_version").stringValue();
    if (!ContractVersions.DRAWIO_EXPORT_POLICY_SCHEMA_VERSION.equals(declaredVersion)) {
      throw new IllegalArgumentException(
          "policy declares drawio_export_policy_schema_version '"
              + declaredVersion
              + "'; this engine reads '"
              + ContractVersions.DRAWIO_EXPORT_POLICY_SCHEMA_VERSION
              + "'");
    }
    if (policy.get("diagram_name").stringValue().isBlank()) {
      throw new IllegalArgumentException("policy field diagram_name must not be blank");
    }
  }
}

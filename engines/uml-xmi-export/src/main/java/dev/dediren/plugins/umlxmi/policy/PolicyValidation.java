package dev.dediren.plugins.umlxmi.policy;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.UML_VERSION;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.XMI_VERSION;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class PolicyValidation {

  private PolicyValidation() {}

  public static void validatePolicy(JsonNode policy) {
    if (policy == null || !policy.isObject()) {
      throw new IllegalArgumentException("policy must be an object");
    }
    for (String field :
        List.of("uml_xmi_export_policy_schema_version", "model_identifier", "model_name")) {
      if (!policy.hasNonNull(field) || !policy.get(field).isTextual()) {
        throw new IllegalArgumentException("policy missing required string field " + field);
      }
    }
    if (policy.has("xmi_version") && !policy.get("xmi_version").asText().equals(XMI_VERSION)) {
      throw new IllegalArgumentException("xmi_version must be " + XMI_VERSION);
    }
    if (policy.has("uml_version") && !policy.get("uml_version").asText().equals(UML_VERSION)) {
      throw new IllegalArgumentException("uml_version must be " + UML_VERSION);
    }
  }
}

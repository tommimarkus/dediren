package dev.dediren.contracts;

import java.util.List;

/**
 * The version history of every hand-authored Dediren schema family: which versions have shipped, in
 * order, and which JSON field carries the version.
 *
 * <p>This registry is what lets the version gate tell "a version I recognize as superseded" apart
 * from "a string I have never heard of" — the difference between an actionable upgrade instruction
 * and a shrug. Every entry in {@link Family#priorVersions()} must have a matching subsection in the
 * {@code ## Migration} section of {@code docs/agent-usage.md}; {@code MigrationRegistryTest} pins
 * the two together in both directions, so a bump cannot ship without its upgrade steps.
 *
 * <p>Data only: {@code contracts} owns no logic.
 */
public final class KnownSchemaVersions {

  /**
   * One hand-authored schema family.
   *
   * @param name the family's short name, used in diagnostic messages
   * @param versionFields the JSON field names that have carried this family's version, current
   *     first. More than one entry means the field itself was renamed, and a file written before
   *     that rename does not carry the current field name at all.
   * @param versions every version that has shipped, oldest first, current last. Never empty.
   */
  public record Family(String name, List<String> versionFields, List<String> versions) {
    public Family {
      versionFields = List.copyOf(versionFields);
      versions = List.copyOf(versions);
      if (versionFields.isEmpty()) {
        throw new IllegalArgumentException("family '" + name + "' must name its version field");
      }
      if (versions.isEmpty()) {
        throw new IllegalArgumentException("family '" + name + "' must have a current version");
      }
    }

    /** The version this build accepts. */
    public String currentVersion() {
      return versions.get(versions.size() - 1);
    }

    /** Every superseded version, oldest first. Empty when the family has never been bumped. */
    public List<String> priorVersions() {
      return versions.subList(0, versions.size() - 1);
    }

    /** The version field a file should carry today. */
    public String versionField() {
      return versionFields.get(0);
    }
  }

  public static final Family MODEL =
      new Family(
          "model", List.of("model_schema_version"), List.of(ContractVersions.MODEL_SCHEMA_VERSION));

  // The oldest entry is a different family id on purpose: the schema was renamed from
  // svg-render-policy to render-policy (238da5a), and the version field was renamed with it. A
  // file from before that rename carries svg_render_policy_schema_version and no
  // render_policy_schema_version at all, so the gate must know the old field name to recognize
  // it.
  public static final Family RENDER_POLICY =
      new Family(
          "render-policy",
          List.of("render_policy_schema_version", "svg_render_policy_schema_version"),
          List.of(
              "svg-render-policy.schema.v1",
              "render-policy.schema.v1",
              "render-policy.schema.v2",
              ContractVersions.RENDER_POLICY_SCHEMA_VERSION));

  public static final Family OEF_EXPORT_POLICY =
      new Family(
          "oef-export-policy",
          List.of("oef_export_policy_schema_version"),
          List.of(ContractVersions.OEF_EXPORT_POLICY_SCHEMA_VERSION));

  public static final Family UML_XMI_EXPORT_POLICY =
      new Family(
          "uml-xmi-export-policy",
          List.of("uml_xmi_export_policy_schema_version"),
          List.of(ContractVersions.UML_XMI_EXPORT_POLICY_SCHEMA_VERSION));

  public static final List<Family> ALL =
      List.of(MODEL, RENDER_POLICY, OEF_EXPORT_POLICY, UML_XMI_EXPORT_POLICY);

  private KnownSchemaVersions() {}
}

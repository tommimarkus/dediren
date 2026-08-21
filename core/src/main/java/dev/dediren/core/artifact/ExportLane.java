package dev.dediren.core.artifact;

import dev.dediren.contracts.pkg.PackageExportLane;

/**
 * The first-party export lanes and the three orchestration facts each one carries: which engine
 * serves it, what its written file is called, and which provenance field its policy hash is stamped
 * under.
 *
 * <p>These lived as four {@code private static final String} constants duplicated across {@code
 * BuildCommand} and {@code PackageBuildCommand}, plus two independently written two-valued
 * ternaries that picked the provenance field name — one comparing a hardcoded base file name
 * ({@code "oef".equals(baseName)}), the other switching on {@link PackageExportLane}. Same
 * decision, two homes, no owner: a third lane reaching either orchestrator would have silently
 * inherited the XMI field name.
 *
 * <p>This is deliberately in {@code core} rather than {@code contracts}: it is orchestration
 * policy, and {@code contracts} holds wire records only, which is why {@link PackageExportLane}
 * stays a bare enum carrying nothing but its JSON spelling.
 */
public enum ExportLane {
  ARCHIMATE_OEF("archimate-oef", "oef", "oef_policy_sha256"),
  UML_XMI("uml-xmi", "xmi", "xmi_policy_sha256");

  private final String engineId;
  private final String baseFileName;
  private final String policyShaKey;

  ExportLane(String engineId, String baseFileName, String policyShaKey) {
    this.engineId = engineId;
    this.baseFileName = baseFileName;
    this.policyShaKey = policyShaKey;
  }

  /** The engine id the registry resolves this lane through. */
  public String engineId() {
    return engineId;
  }

  /** The written artifact's base name, before the serialization extension. */
  public String baseFileName() {
    return baseFileName;
  }

  /** The provenance payload field this lane's policy hash is stamped under. */
  public String policyShaKey() {
    return policyShaKey;
  }

  /** The lane a package export entry targets. Total over {@link PackageExportLane}. */
  public static ExportLane of(PackageExportLane lane) {
    return switch (lane) {
      case ARCHIMATE_OEF -> ARCHIMATE_OEF;
      case UML_XMI -> UML_XMI;
    };
  }
}

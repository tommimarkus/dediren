package dev.dediren.contracts.export;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import java.util.Map;

/**
 * The hand-authored draw.io export policy. The top-level {@code diagram_name} names the exported
 * page — on the single-view lane it is the whole document's one page; on the whole-model lane it
 * names the default page a view falls back to. The additive-optional {@code views} map supplies a
 * per-view page-name override, so a multi-view document never reuses one page name for two views.
 */
public record DrawioExportPolicy(
    String drawioExportPolicySchemaVersion, String diagramName, Map<String, ViewIdentity> views) {
  public DrawioExportPolicy {
    views = mapOrEmpty(views);
  }

  public DrawioExportPolicy(String drawioExportPolicySchemaVersion, String diagramName) {
    this(drawioExportPolicySchemaVersion, diagramName, Map.of());
  }

  /** A per-view identity override; the field is optional, an unset field keeps its default. */
  public record ViewIdentity(String diagramName) {}
}

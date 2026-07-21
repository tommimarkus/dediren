package dev.dediren.contracts.export;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import java.util.Map;

/**
 * The hand-authored OEF export policy. The top-level identity fields drive the model header and —
 * on the single-view lane only — the exported view's identity. The additive-optional {@code views}
 * map supplies per-view identity overrides; on the whole-model lane a view without an override gets
 * a source-derived default ({@code id-view-<view-id>} + the view's own label, viewpoint falling
 * back to the top-level {@code viewpoint}), so a multi-view document never reuses one identity for
 * two views.
 */
public record OefExportPolicy(
    String oefExportPolicySchemaVersion,
    String modelIdentifier,
    String modelName,
    String viewIdentifier,
    String viewName,
    String viewpoint,
    Map<String, ViewIdentity> views) {
  public OefExportPolicy {
    views = mapOrEmpty(views);
  }

  public OefExportPolicy(
      String oefExportPolicySchemaVersion,
      String modelIdentifier,
      String modelName,
      String viewIdentifier,
      String viewName,
      String viewpoint) {
    this(
        oefExportPolicySchemaVersion,
        modelIdentifier,
        modelName,
        viewIdentifier,
        viewName,
        viewpoint,
        Map.of());
  }

  /** A per-view identity override; every field optional, unset fields keep their defaults. */
  public record ViewIdentity(String viewIdentifier, String viewName, String viewpoint) {}
}

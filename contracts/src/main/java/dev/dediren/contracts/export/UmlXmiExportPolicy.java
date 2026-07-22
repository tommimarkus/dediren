package dev.dediren.contracts.export;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import java.util.Map;

public record UmlXmiExportPolicy(
    String umlXmiExportPolicySchemaVersion,
    String modelIdentifier,
    String modelName,
    String xmiVersion,
    String umlVersion,
    Map<String, DiagramIdentity> views) {
  public UmlXmiExportPolicy {
    views = mapOrEmpty(views);
  }

  /** Back-compatible constructor for callers that predate the per-view diagram identity map. */
  public UmlXmiExportPolicy(
      String umlXmiExportPolicySchemaVersion,
      String modelIdentifier,
      String modelName,
      String xmiVersion,
      String umlVersion) {
    this(
        umlXmiExportPolicySchemaVersion,
        modelIdentifier,
        modelName,
        xmiVersion,
        umlVersion,
        Map.of());
  }

  /**
   * A per-view UMLDI diagram identity override for the whole-model lane, keyed by source view id.
   * Every field is optional; an unset field keeps the source-derived default.
   */
  public record DiagramIdentity(String diagramIdentifier, String diagramName) {}
}

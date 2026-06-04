package dev.dediren.contracts.export;

public record OefExportPolicy(
        String oefExportPolicySchemaVersion,
        String modelIdentifier,
        String modelName,
        String viewIdentifier,
        String viewName,
        String viewpoint) {
}

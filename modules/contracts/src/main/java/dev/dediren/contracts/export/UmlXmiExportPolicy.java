package dev.dediren.contracts.export;

public record UmlXmiExportPolicy(
        String umlXmiExportPolicySchemaVersion,
        String modelIdentifier,
        String modelName,
        String xmiVersion,
        String umlVersion) {
}

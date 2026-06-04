package dev.dediren.contracts.layout;

public record SemanticValidationResult(
        String semanticValidationResultSchemaVersion,
        String semanticProfile,
        long nodeCount,
        long relationshipCount) {
}

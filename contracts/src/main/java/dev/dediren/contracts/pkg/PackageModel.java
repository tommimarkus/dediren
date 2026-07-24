package dev.dediren.contracts.pkg;

/**
 * One source model referenced by a package: an {@code id} the package's views and exports bind to,
 * and the {@code source} path of the model document. The model's semantic profile is declared
 * inside the model itself and is deliberately not restated here.
 */
public record PackageModel(String id, String source) {}

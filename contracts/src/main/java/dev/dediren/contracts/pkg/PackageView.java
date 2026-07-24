package dev.dediren.contracts.pkg;

/**
 * One view in a package: bound to a {@code model} (optional when the package declares exactly one),
 * rendered with its own {@code renderPolicy}, carrying opaque {@code presentation}, and
 * materialized to its declared {@code outputs}.
 */
public record PackageView(
    String id,
    String model,
    String renderPolicy,
    PackagePresentation presentation,
    PackageOutputs outputs) {}

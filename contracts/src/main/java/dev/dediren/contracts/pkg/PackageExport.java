package dev.dediren.contracts.pkg;

/**
 * One export in a package. It targets exactly one of {@code view} (one focused file) or {@code
 * model} (the whole-model aggregate — every one of that model's views in a single file); the two
 * are mutually exclusive. {@code lane} selects OEF or UML/XMI, {@code policy} is the export policy
 * path, and {@code output} is the declared destination.
 */
public record PackageExport(
    String id, String view, String model, PackageExportLane lane, String policy, String output) {}

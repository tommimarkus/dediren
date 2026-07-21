package dev.dediren.core.schema;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.KnownSchemaVersions;
import dev.dediren.contracts.MigrationOperation;
import dev.dediren.contracts.MigrationPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Rejects a hand-authored document whose schema version is not the one this build accepts, and —
 * when the version is one the registry recognizes — says so in terms the reader can act on.
 *
 * <p>The whole point is the difference between the two diagnostics it emits. {@code
 * SCHEMA_VERSION_OUTDATED} names the version found, the version wanted, and where the upgrade steps
 * live, so an agent holding the file can apply them without further help. {@code
 * SCHEMA_VERSION_UNKNOWN} is the honest shrug for a version this build has never heard of.
 */
public final class SchemaVersionGate {

  private static final String GUIDE_POINTER =
      "upgrade it with the 'Migration' section of the agent guide"
          + " (MCP: call dediren_guide with topic 'migration')";

  private SchemaVersionGate() {}

  /**
   * Returns a diagnostic when {@code document} does not carry {@code family}'s current schema
   * version, or empty when it does.
   */
  public static Optional<Diagnostic> check(KnownSchemaVersions.Family family, JsonNode document) {
    String found = findVersion(family, document);
    if (family.currentVersion().equals(found)) {
      return Optional.empty();
    }
    if (found != null && family.priorVersions().contains(found)) {
      return Optional.of(
          diagnostic(
                  DiagnosticCode.SCHEMA_VERSION_OUTDATED,
                  family,
                  "'"
                      + found
                      + "' is a superseded "
                      + family.name()
                      + " schema version; this build accepts '"
                      + family.currentVersion()
                      + "'. To fix, apply the attached migration operations, or "
                      + GUIDE_POINTER
                      + ".")
              .withMigration(composedPath(family, found)));
    }
    String describedAs =
        found == null ? "no '" + family.versionField() + "' field" : "'" + found + "'";
    return Optional.of(
        diagnostic(
            DiagnosticCode.SCHEMA_VERSION_UNKNOWN,
            family,
            describedAs
                + " is not a "
                + family.name()
                + " schema version this build knows; it accepts '"
                + family.currentVersion()
                + "'."));
  }

  /**
   * The machine-readable path from {@code found} to the family's current version: the registry's
   * single-hop steps concatenated, with intermediate {@code set_version} writes to the same pointer
   * pruned (only the final version write survives). A {@code regenerate} step anywhere in the chain
   * collapses the whole path to just {@code regenerate} — mechanical edits cannot cross a
   * judgment-bound hop.
   */
  private static MigrationPath composedPath(KnownSchemaVersions.Family family, String found) {
    int start = family.versions().indexOf(found);
    var operations = new ArrayList<MigrationOperation>();
    for (int i = start; i < family.steps().size(); i++) {
      for (MigrationOperation operation : family.steps().get(i).operations()) {
        if ("regenerate".equals(operation.op())) {
          return new MigrationPath(
              found, family.currentVersion(), List.of(MigrationOperation.regenerate()));
        }
        operations.add(operation);
      }
    }
    var pruned = new ArrayList<MigrationOperation>();
    for (int i = 0; i < operations.size(); i++) {
      MigrationOperation operation = operations.get(i);
      if ("set_version".equals(operation.op()) && laterVersionWriteExists(operations, i)) {
        continue;
      }
      pruned.add(operation);
    }
    return new MigrationPath(found, family.currentVersion(), pruned);
  }

  private static boolean laterVersionWriteExists(List<MigrationOperation> operations, int index) {
    for (int i = index + 1; i < operations.size(); i++) {
      MigrationOperation later = operations.get(i);
      if ("set_version".equals(later.op())
          && later.pointer().equals(operations.get(index).pointer())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The version string this document carries, or null when it carries none.
   *
   * <p>Falls back to the family's legacy field names, so a file written before a version-field
   * rename is still recognized as outdated rather than dismissed as unknown.
   */
  private static String findVersion(KnownSchemaVersions.Family family, JsonNode document) {
    for (String field : family.versionFields()) {
      JsonNode value = document.get(field);
      if (value != null && value.isTextual()) {
        return value.asText();
      }
    }
    return null;
  }

  private static Diagnostic diagnostic(
      DiagnosticCode code, KnownSchemaVersions.Family family, String message) {
    return new Diagnostic(
        code.code(), DiagnosticSeverity.ERROR, message, "$." + family.versionField());
  }
}

package dev.dediren.contracts;

import java.util.List;
import java.util.Objects;

/**
 * An ordered list of {@link MigrationOperation}s taking a document {@code from} one schema version
 * {@code to} another. The registry records one single-hop path per bump ({@link
 * KnownSchemaVersions.Family#steps()}); the version gate delivers one composed path (found version
 * → current) on the {@code DEDIREN_SCHEMA_VERSION_OUTDATED} diagnostic.
 */
public record MigrationPath(String from, String to, List<MigrationOperation> operations) {
  public MigrationPath {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    operations = List.copyOf(operations);
    if (operations.isEmpty()) {
      throw new IllegalArgumentException("migration path " + from + " -> " + to + " has no steps");
    }
  }
}

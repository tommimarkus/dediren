package dev.dediren.contracts;

import java.util.Objects;

/**
 * One mechanical migration edit, delivered as data on a {@code DEDIREN_SCHEMA_VERSION_OUTDATED}
 * diagnostic so an agent applies exact JSON-Pointer edits instead of transcribing prose. Dediren
 * never applies these itself — the consumer remains the hands, and the stale file keeps failing
 * validation until it is migrated (schema-migration design, amendment 2026-07-21).
 *
 * <p>The vocabulary is deliberately tiny: {@code rename_field} (move the value at {@code pointer}
 * to {@code to}), {@code remove_key} (delete {@code pointer}), {@code set_version} (set {@code
 * pointer} to {@code value}), and {@code regenerate} (do not hand-edit — re-emit the file with the
 * producing command; carries no pointer). A future bump whose steps cannot be said in these ops is
 * the recorded escalation trigger for revisiting a full migrate command.
 */
public record MigrationOperation(String op, String pointer, String to, String value) {
  public MigrationOperation {
    Objects.requireNonNull(op, "op");
  }

  public static MigrationOperation renameField(String pointer, String to) {
    return new MigrationOperation("rename_field", pointer, to, null);
  }

  public static MigrationOperation removeKey(String pointer) {
    return new MigrationOperation("remove_key", pointer, null, null);
  }

  public static MigrationOperation setVersion(String pointer, String value) {
    return new MigrationOperation("set_version", pointer, null, value);
  }

  public static MigrationOperation regenerate() {
    return new MigrationOperation("regenerate", null, null, null);
  }
}

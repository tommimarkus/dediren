package dev.dediren.schemacache;

/**
 * The validator or fetch lane could not run. {@link Kind} classifies why, because the useful caller
 * advice differs by class: schema-placement/proxy remediation only helps when the schema set itself
 * is missing or broken, while a saturated validator just wants a retry.
 */
public class SchemaCacheException extends Exception {
  /** Why the lane could not run. */
  public enum Kind {
    /** Missing, unreadable, or non-compiling XSD set (including unresolved imports). */
    SCHEMA_SET,
    /** The JAXP provider rejected the validator's configuration, or the validator itself broke. */
    CONFIG,
    /** A compile+validate run exceeded its wall-clock budget or was interrupted. */
    TIMEOUT,
    /** The validator is at its concurrency cap - transient by nature. */
    SATURATED,
    /** The pinned schema download could not be completed. */
    FETCH
  }

  private final Kind kind;

  public SchemaCacheException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public SchemaCacheException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  /**
   * The caller-facing advice for this failure class. Placement/proxy remediation (the caller's
   * {@code schemaSetRemediation}) only fits a missing or broken schema set; the transient classes
   * say so instead, so an agent hitting saturation is told to retry, not to reconfigure a proxy.
   */
  public String advice(String schemaSetRemediation) {
    return switch (kind) {
      case SCHEMA_SET, FETCH -> schemaSetRemediation;
      case SATURATED -> "This is transient: the validator is at capacity; retry the export.";
      case TIMEOUT ->
          "Retry the export; a recurring timeout means this process's validator lane is wedged.";
      case CONFIG ->
          "This is a JVM XML-validator configuration problem, not a schema placement problem.";
    };
  }
}

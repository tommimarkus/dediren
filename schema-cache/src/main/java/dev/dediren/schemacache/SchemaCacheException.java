package dev.dediren.schemacache;

public class SchemaCacheException extends Exception {
  public SchemaCacheException(String message) {
    super(message);
  }

  public SchemaCacheException(String message, Throwable cause) {
    super(message, cause);
  }
}

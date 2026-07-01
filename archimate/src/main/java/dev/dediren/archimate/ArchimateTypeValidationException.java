package dev.dediren.archimate;

public final class ArchimateTypeValidationException extends Exception {
  private final ArchimateTypeKind kind;
  private final String value;
  private final String path;

  ArchimateTypeValidationException(ArchimateTypeKind kind, String value, String path) {
    super(message(kind, value));
    this.kind = kind;
    this.value = value;
    this.path = path;
  }

  public String code() {
    return switch (kind) {
      case ELEMENT -> "DEDIREN_ARCHIMATE_ELEMENT_TYPE_UNSUPPORTED";
      case RELATIONSHIP -> "DEDIREN_ARCHIMATE_RELATIONSHIP_TYPE_UNSUPPORTED";
      case RELATIONSHIP_ENDPOINT -> "DEDIREN_ARCHIMATE_RELATIONSHIP_ENDPOINT_UNSUPPORTED";
    };
  }

  public String value() {
    return value;
  }

  public String path() {
    return path;
  }

  public String message() {
    return getMessage();
  }

  private static String message(ArchimateTypeKind kind, String value) {
    return switch (kind) {
      case ELEMENT -> "unsupported ArchiMate element type: " + value;
      case RELATIONSHIP -> "unsupported ArchiMate relationship type: " + value;
      case RELATIONSHIP_ENDPOINT -> "unsupported ArchiMate relationship endpoint: " + value;
    };
  }
}

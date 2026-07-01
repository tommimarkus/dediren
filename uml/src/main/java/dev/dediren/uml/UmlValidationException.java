package dev.dediren.uml;

public final class UmlValidationException extends Exception {
  private final UmlTypeKind kind;
  private final String value;
  private final String path;

  UmlValidationException(UmlTypeKind kind, String value, String path) {
    super(message(kind, value));
    this.kind = kind;
    this.value = value;
    this.path = path;
  }

  public String code() {
    return switch (kind) {
      case ELEMENT -> "DEDIREN_UML_ELEMENT_TYPE_UNSUPPORTED";
      case ELEMENT_PROPERTY -> "DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED";
      case RELATIONSHIP -> "DEDIREN_UML_RELATIONSHIP_TYPE_UNSUPPORTED";
      case RELATIONSHIP_PROPERTY -> "DEDIREN_UML_RELATIONSHIP_PROPERTY_INVALID";
      case RELATIONSHIP_ENDPOINT -> "DEDIREN_UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED";
      case MULTIPLICITY -> "DEDIREN_UML_MULTIPLICITY_INVALID";
      case VIEW_KIND -> "DEDIREN_UML_VIEW_KIND_UNSUPPORTED_ELEMENT";
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

  private static String message(UmlTypeKind kind, String value) {
    return switch (kind) {
      case ELEMENT -> "unsupported UML element type: " + value;
      case ELEMENT_PROPERTY -> "invalid UML element property: " + value;
      case RELATIONSHIP -> "unsupported UML relationship type: " + value;
      case RELATIONSHIP_PROPERTY -> "invalid UML relationship property: " + value;
      case RELATIONSHIP_ENDPOINT -> "unsupported UML relationship endpoint: " + value;
      case MULTIPLICITY -> "invalid UML multiplicity: " + value;
      case VIEW_KIND -> "view contains unsupported UML element: " + value;
    };
  }
}

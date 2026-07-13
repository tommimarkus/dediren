package dev.dediren.uml;

import dev.dediren.contracts.DiagnosticCode;

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
      case ELEMENT -> DiagnosticCode.UML_ELEMENT_TYPE_UNSUPPORTED.code();
      case ELEMENT_PROPERTY -> DiagnosticCode.UML_ELEMENT_PROPERTY_UNSUPPORTED.code();
      case RELATIONSHIP -> DiagnosticCode.UML_RELATIONSHIP_TYPE_UNSUPPORTED.code();
      case RELATIONSHIP_PROPERTY -> DiagnosticCode.UML_RELATIONSHIP_PROPERTY_INVALID.code();
      case RELATIONSHIP_ENDPOINT -> DiagnosticCode.UML_RELATIONSHIP_ENDPOINT_UNSUPPORTED.code();
      case MULTIPLICITY -> DiagnosticCode.UML_MULTIPLICITY_INVALID.code();
      case VIEW_KIND -> DiagnosticCode.UML_VIEW_KIND_UNSUPPORTED_ELEMENT.code();
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

package dev.dediren.plugins.umlxmi.build;

public final class XmiValidationException extends Exception {
  private final String code;

  public XmiValidationException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}

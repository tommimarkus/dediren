package dev.dediren.plugins.umlxmi.build;

public final class XmiExportException extends Exception {
  private final String code;
  private final String path;

  public XmiExportException(String code, String message, String path) {
    super(message);
    this.code = code;
    this.path = path;
  }

  public String code() {
    return code;
  }

  public String path() {
    return path;
  }
}

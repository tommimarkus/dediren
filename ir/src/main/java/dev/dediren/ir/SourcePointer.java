package dev.dediren.ir;

/** RFC 6901 JSON-Pointer into the source model document. */
public record SourcePointer(String jsonPointer) {
  public String value() {
    return jsonPointer;
  }
}

package dev.dediren.engine;

/**
 * XML 1.0 character policy shared by every XML-emitting engine (SVG, OEF, XMI): characters XML 1.0
 * cannot represent at all (C0 controls other than tab/LF/CR, lone surrogates, U+FFFE/U+FFFF) are
 * replaced with U+FFFD via {@link #scrub}, so a contract-valid label can never yield an ill-formed
 * artifact.
 *
 * <p>{@link #text} and {@link #attr} additionally own entity escaping for the string-composed
 * emitters (OEF, XMI) — one implementation, referenced from both engines, because engines may not
 * depend on each other and duplicated escaping rules drift. The SVG engine escapes structurally in
 * its StAX-backed {@code SvgWriter} and does not use these.
 */
public final class XmlText {
  private XmlText() {}

  /** Element content: scrubbed and {@code & < >}-escaped. */
  public static String text(String value) {
    return scrub(value == null ? "" : value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  /** Attribute value: {@link #text} plus {@code "} escaping. */
  public static String attr(String value) {
    return text(value).replace("\"", "&quot;");
  }

  public static String scrub(String value) {
    if (value == null) {
      return null;
    }
    int firstInvalid = -1;
    for (int i = 0; i < value.length(); i++) {
      if (!validAt(value, i)) {
        firstInvalid = i;
        break;
      }
    }
    if (firstInvalid < 0) {
      return value;
    }
    StringBuilder out = new StringBuilder(value.length());
    out.append(value, 0, firstInvalid);
    for (int i = firstInvalid; i < value.length(); i++) {
      out.append(validAt(value, i) ? value.charAt(i) : '�');
    }
    return out.toString();
  }

  private static boolean validAt(String value, int index) {
    char c = value.charAt(index);
    if (Character.isHighSurrogate(c)) {
      return index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1));
    }
    if (Character.isLowSurrogate(c)) {
      return index > 0 && Character.isHighSurrogate(value.charAt(index - 1));
    }
    return c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c <= 0xFFFD);
  }
}

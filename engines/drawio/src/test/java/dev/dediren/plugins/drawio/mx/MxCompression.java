package dev.dediren.plugins.drawio.mx;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.Deflater;

/**
 * draw.io's own writer, for tests: percent-encode the UTF-8 XML, raw-deflate it, base64 it.
 *
 * <p>A compressed page cannot be a checked-in fixture and stay reviewable — it is an opaque base64
 * blob, so a reviewer cannot see what the test actually feeds the reader, and an attacker payload
 * hidden in one would be invisible. Tests therefore encode their plain XML here at run time, which
 * keeps the payload readable in the test source and keeps the encoder and the decoder honest about
 * each other.
 */
final class MxCompression {

  private MxCompression() {}

  /** The wire form of one compressed {@code <diagram>} body. */
  static String encode(String xml) {
    return base64(deflateRaw(encodeUriComponent(xml).getBytes(US_ASCII)));
  }

  /** {@code encodeURIComponent}: everything outside its unreserved set is %XX per UTF-8 byte. */
  private static String encodeUriComponent(String text) {
    String unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()";
    StringBuilder out = new StringBuilder();
    for (byte raw : text.getBytes(UTF_8)) {
      int value = raw & 0xff;
      if (value < 0x80 && unreserved.indexOf((char) value) >= 0) {
        out.append((char) value);
      } else {
        out.append('%').append(String.format(Locale.ROOT, "%02X", value));
      }
    }
    return out.toString();
  }

  private static byte[] deflateRaw(byte[] data) {
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    deflater.setInput(data);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    while (!deflater.finished()) {
      out.write(chunk, 0, deflater.deflate(chunk));
    }
    deflater.end();
    return out.toByteArray();
  }

  private static String base64(byte[] data) {
    return Base64.getEncoder().encodeToString(data);
  }
}

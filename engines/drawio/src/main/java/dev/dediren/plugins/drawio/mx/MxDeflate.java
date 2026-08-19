package dev.dediren.plugins.drawio.mx;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.engine.EngineException;
import dev.dediren.plugins.drawio.DrawioLimits;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decoder for draw.io's compressed {@code <diagram>} body.
 *
 * <p>draw.io writes a page as <em>percent-encoded UTF-8 XML → raw DEFLATE → base64</em>, because
 * its writer applies {@code encodeURIComponent} <em>before</em> deflating. Reading therefore runs
 * the three steps in reverse, and each one has a trap:
 *
 * <ol>
 *   <li><strong>Raw DEFLATE, not zlib.</strong> {@code new Inflater(true)}. A zlib-wrapped inflater
 *       reads the first data byte as a header and fails on every real file.
 *   <li><strong>The budget is enforced during the inflate, not after it.</strong> Inflating into an
 *       unbounded buffer and measuring afterwards is not a guard; it is the bomb going off before
 *       the alarm.
 *   <li><strong>Percent-decoding is hand-rolled.</strong> {@link java.net.URLDecoder} maps {@code
 *       +} to a space (it decodes {@code application/x-www-form-urlencoded}, a different grammar).
 *       draw.io's own reader is {@code decodeURIComponent}, which leaves a literal {@code +} alone,
 *       so URLDecoder silently corrupts every label containing one.
 * </ol>
 *
 * <p><strong>Why an instance and not a static method.</strong> The decompression budget is a
 * running total across every page of one document (see {@link DrawioLimits}); a static {@code
 * decode(payload)} would reset it per call, and a 256-page file would then buy 256 × the ceiling.
 * Making the remaining budget instance state means a caller gets the aggregate by construction:
 * there is no per-call budget argument to pass, and no way to spend more than one document's worth
 * without deliberately constructing a second decoder. One instance per document; not thread-safe,
 * and it does not need to be — a document is decoded on one thread.
 *
 * <p>Depends only on {@code contracts}, {@code engine-api}, and the JDK. The bounded-read loop in
 * {@link #inflate} is deliberately a local copy of the shape of {@code core}'s {@code
 * BoundedReads.readString(InputStream, long)}: engines may not depend on {@code core} (§2, §5), so
 * a dozen lines are duplicated rather than an illegal edge added.
 */
public final class MxDeflate {

  /**
   * The base64 text of one page cannot exceed the whole document's byte ceiling, and base64 costs 4
   * characters per 3 bytes. Derived from {@link DrawioLimits#MAX_INPUT_BYTES}, not an independent
   * tunable — the cap exists so an absurd payload is refused <em>before</em> it is decoded into a
   * byte array.
   */
  private static final long MAX_BASE64_CHARS = 4 * ((DrawioLimits.MAX_INPUT_BYTES + 2) / 3);

  private static final int CHUNK_BYTES = 64 * 1024;

  private final long maxBase64Chars;
  private long remainingBytes;

  private MxDeflate(long maxBase64Chars, long maxDecompressedBytes) {
    this.maxBase64Chars = maxBase64Chars;
    this.remainingBytes = maxDecompressedBytes;
  }

  /** A decoder carrying one document's worth of budget: the only entry point production uses. */
  public static MxDeflate forDocument() {
    return new MxDeflate(MAX_BASE64_CHARS, DrawioLimits.MAX_DECOMPRESSED_BYTES);
  }

  /**
   * Visible for testing so the ceilings can be exercised at a byte boundary instead of at 64 MiB.
   * Deliberately not public: a public "give me a bigger budget" factory would be an escape hatch
   * around the ceiling this class exists to enforce.
   */
  static MxDeflate withBudgets(long maxBase64Chars, long maxDecompressedBytes) {
    return new MxDeflate(maxBase64Chars, maxDecompressedBytes);
  }

  /**
   * Decodes one compressed {@code <diagram>} body to its XML text, spending from this decoder's
   * remaining decompression budget.
   *
   * @throws EngineException {@code DRAWIO_INPUT_TOO_LARGE} if the payload text is over the ceiling,
   *     {@code DRAWIO_DECOMPRESSED_TOO_LARGE} if inflating it would exhaust the running budget, or
   *     {@code DRAWIO_DECOMPRESSION_FAILED} if it is not base64, not a raw DEFLATE stream, or not
   *     validly percent-encoded.
   */
  public String decodeDiagramBody(String payload) throws EngineException {
    Objects.requireNonNull(payload, "payload");
    if (payload.length() > maxBase64Chars) {
      throw DrawioLimits.limit(
          DiagnosticCode.DRAWIO_INPUT_TOO_LARGE,
          "compressed diagram payload exceeds the input ceiling of "
              + maxBase64Chars
              + " base64 characters");
    }
    return percentDecode(inflate(decodeBase64(payload)));
  }

  private static byte[] decodeBase64(String payload) throws EngineException {
    // Real files are pretty-printed, so the payload may be wrapped; strip ASCII whitespace and then
    // decode strictly. The MIME decoder would have been shorter, but it silently *ignores* every
    // illegal character, which turns "this is not base64 at all" into an empty stream.
    StringBuilder compact = new StringBuilder(payload.length());
    for (int index = 0; index < payload.length(); index++) {
      char character = payload.charAt(index);
      if (character != ' ' && character != '\t' && character != '\r' && character != '\n') {
        compact.append(character);
      }
    }
    try {
      return Base64.getDecoder().decode(compact.toString());
    } catch (IllegalArgumentException notBase64) {
      throw decompressionFailed("compressed diagram payload is not valid base64");
    }
  }

  /**
   * Raw-inflates through a running byte counter, aborting the moment the budget is exhausted. The
   * loop is the shape of {@code BoundedReads.readString(InputStream, long)} — copied rather than
   * called because engines may not depend on {@code core}.
   */
  private byte[] inflate(byte[] compressed) throws EngineException {
    Inflater inflater = new Inflater(true); // nowrap: draw.io writes raw DEFLATE, not zlib
    inflater.setInput(compressed);
    ByteArrayOutputStream inflated = new ByteArrayOutputStream();
    byte[] chunk = new byte[CHUNK_BYTES];
    long produced = 0;
    try {
      while (!inflater.finished()) {
        int read = inflater.inflate(chunk);
        if (read == 0) {
          // finished() is false, so the stream still owes output: it is truncated, needs a
          // preset dictionary draw.io never writes, or is otherwise unreadable.
          throw decompressionFailed("compressed diagram payload ends mid-stream");
        }
        produced += read;
        if (produced > remainingBytes) {
          // Checked here, inside the loop, so at most one chunk beyond the ceiling is ever held.
          throw DrawioLimits.limit(
              DiagnosticCode.DRAWIO_DECOMPRESSED_TOO_LARGE,
              "decompressed diagram content exceeds the document's remaining "
                  + remainingBytes
                  + "-byte decompression budget");
        }
        inflated.write(chunk, 0, read);
      }
    } catch (DataFormatException malformed) {
      throw decompressionFailed("compressed diagram payload is not a raw DEFLATE stream");
    } finally {
      inflater.end();
    }
    remainingBytes -= produced;
    return inflated.toByteArray();
  }

  /**
   * {@code decodeURIComponent}, not {@code URLDecoder.decode}: {@code %XX} escapes are decoded to
   * bytes and every other byte — including a literal {@code +} — passes through untouched. The
   * result can only shrink, so it needs no budget of its own.
   */
  private static String percentDecode(byte[] encoded) throws EngineException {
    ByteArrayOutputStream decoded = new ByteArrayOutputStream(encoded.length);
    for (int index = 0; index < encoded.length; index++) {
      byte value = encoded[index];
      if (value != '%') {
        decoded.write(value);
        continue;
      }
      if (index + 2 >= encoded.length) {
        throw decompressionFailed("diagram content ends inside a percent escape");
      }
      int high = hexDigit(encoded[index + 1]);
      int low = hexDigit(encoded[index + 2]);
      if (high < 0 || low < 0) {
        // decodeURIComponent throws URIError here, so draw.io itself refuses the file too.
        throw decompressionFailed("diagram content carries a malformed percent escape");
      }
      decoded.write((high << 4) | low);
      index += 2;
    }
    return decoded.toString(StandardCharsets.UTF_8);
  }

  private static int hexDigit(byte value) {
    if (value >= '0' && value <= '9') {
      return value - '0';
    }
    if (value >= 'a' && value <= 'f') {
      return value - 'a' + 10;
    }
    if (value >= 'A' && value <= 'F') {
      return value - 'A' + 10;
    }
    return -1;
  }

  private static EngineException decompressionFailed(String message) {
    return DrawioLimits.limit(DiagnosticCode.DRAWIO_DECOMPRESSION_FAILED, message);
  }
}

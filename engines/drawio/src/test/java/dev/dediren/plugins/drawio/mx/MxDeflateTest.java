package dev.dediren.plugins.drawio.mx;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.engine.EngineException;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * The draw.io compressed {@code <diagram>} body, and the three details that are load-bearing when
 * reading it: raw DEFLATE (not zlib), a running decompression budget enforced during the inflate,
 * and a hand-rolled percent decoder.
 *
 * <p>{@link #drawioEncode} is written out longhand rather than reused from production code on
 * purpose: it is the executable statement of the wire format this decoder claims to read, so a
 * production bug must not be able to cancel itself out in the round trip.
 *
 * <p>This test lives in the {@code mx} package because the budget-taking factory is deliberately
 * package-private — a public "give me a bigger budget" entry point would be an escape hatch around
 * the very ceiling the class exists to enforce.
 */
class MxDeflateTest {

  @Test
  void aBodyEncodedTheWayDrawioEncodesItRoundTripsExactly() {
    String xml =
        "<mxGraphModel dx=\"1\"><root><mxCell id=\"2\" value=\"Zażółć gęślą 😀\"/></root>"
            + "</mxGraphModel>";
    assertThat(decode(drawioEncode(xml))).isEqualTo(xml);
  }

  @Test
  void aLabelContainingAPlusSurvivesBecauseThePercentDecoderIsNotUrlDecoder() {
    // decodeURIComponent - which is what draw.io itself uses - leaves a literal '+' alone, and
    // real files carry one. URLDecoder.decode maps '+' to a space, so swapping it in here would
    // silently rewrite every such label. This test is that swap's tripwire.
    String encoded = "<mxCell value=\"Order + Invoice\"/>";
    String wire = percentEncodeLeavingPlusLiteral(encoded);
    assertThat(wire).contains("+");

    String decoded = decode(base64(deflateRaw(wire.getBytes(US_ASCII))));
    assertThat(decoded).isEqualTo(encoded);
    assertThat(decoded).contains("Order + Invoice");
    assertThat(decoded).doesNotContain("Order   Invoice");
  }

  @Test
  void aTinyPayloadThatInflatesPastTheCeilingIsRefusedDuringTheInflate() {
    // A ~64 KiB payload nominally inflating to 64 MiB + 1. If the guard ran after the inflate
    // instead of during it, the bomb would already have gone off. Memory stays bounded because
    // the loop aborts within one chunk of the ceiling.
    String bomb = repeatedBytePayload(64L * 1024 * 1024 + 1);
    assertThat(bomb.length()).isLessThan(1024 * 1024);

    assertRefused(
        () -> MxDeflate.forDocument().decodeDiagramBody(bomb),
        "DEDIREN_DRAWIO_DECOMPRESSED_TOO_LARGE");
  }

  @Test
  void theCeilingIsExactAtTheByteBoundary() throws Exception {
    MxDeflate atCeiling = MxDeflate.withBudgets(1_000_000, 4096);
    assertThat(atCeiling.decodeDiagramBody(payloadOfBytes(4096))).hasSize(4096);

    MxDeflate overCeiling = MxDeflate.withBudgets(1_000_000, 4096);
    assertRefused(
        () -> overCeiling.decodeDiagramBody(payloadOfBytes(4097)),
        "DEDIREN_DRAWIO_DECOMPRESSED_TOO_LARGE");
  }

  @Test
  void theBudgetIsARunningTotalSoASecondPageCannotSpendItAgain() throws Exception {
    // Each page is individually well under the ceiling; together they are over it. A per-call
    // budget would accept both, which is how a 256-page file would buy 256x the ceiling.
    MxDeflate decoder = MxDeflate.withBudgets(1_000_000, 1000);
    assertThat(decoder.decodeDiagramBody(payloadOfBytes(600))).hasSize(600);

    assertRefused(
        () -> decoder.decodeDiagramBody(payloadOfBytes(600)),
        "DEDIREN_DRAWIO_DECOMPRESSED_TOO_LARGE");
  }

  @Test
  void aBase64PayloadOverTheCeilingIsRefusedBeforeItIsDecoded() {
    MxDeflate decoder = MxDeflate.withBudgets(8, 1_000_000);
    assertRefused(
        () -> decoder.decodeDiagramBody(payloadOfBytes(600)), "DEDIREN_DRAWIO_INPUT_TOO_LARGE");
  }

  @Test
  void malformedInputFailsAsADiagnosticNotAsARawZipException() {
    // Not base64 at all.
    assertRefused(
        () -> MxDeflate.forDocument().decodeDiagramBody("not base64 %%% at all"),
        "DEDIREN_DRAWIO_DECOMPRESSION_FAILED");
    // Valid base64, but the bytes are not a raw DEFLATE stream ("Hello world").
    assertRefused(
        () -> MxDeflate.forDocument().decodeDiagramBody("SGVsbG8gd29ybGQ="),
        "DEDIREN_DRAWIO_DECOMPRESSION_FAILED");
    // Valid base64 and a valid stream prefix, but truncated mid-stream.
    String truncated = base64(Arrays.copyOf(deflateRaw("x".repeat(4096).getBytes(US_ASCII)), 4));
    assertRefused(
        () -> MxDeflate.forDocument().decodeDiagramBody(truncated),
        "DEDIREN_DRAWIO_DECOMPRESSION_FAILED");
  }

  @Test
  void aMalformedPercentEscapeIsRefusedTheWayDecodeUriComponentRefusesIt() {
    assertRefused(
        () ->
            MxDeflate.forDocument()
                .decodeDiagramBody(base64(deflateRaw("value%ZZhere".getBytes(US_ASCII)))),
        "DEDIREN_DRAWIO_DECOMPRESSION_FAILED");
    assertRefused(
        () ->
            MxDeflate.forDocument()
                .decodeDiagramBody(base64(deflateRaw("truncated%4".getBytes(US_ASCII)))),
        "DEDIREN_DRAWIO_DECOMPRESSION_FAILED");
  }

  @Test
  void zlibWrappedInputIsNotAcceptedBecauseTheRealFormatIsRawDeflate() {
    Deflater zlib = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
    zlib.setInput("<mxCell/>".getBytes(US_ASCII));
    zlib.finish();
    byte[] chunk = new byte[256];
    int written = zlib.deflate(chunk);
    zlib.end();

    assertRefused(
        () -> MxDeflate.forDocument().decodeDiagramBody(base64(Arrays.copyOf(chunk, written))),
        "DEDIREN_DRAWIO_DECOMPRESSION_FAILED");
  }

  @Test
  void whitespaceWrappedPayloadsDecodeBecauseRealFilesArePrettyPrinted() {
    String xml = "<mxGraphModel/>";
    String wrapped = "\n  " + drawioEncode(xml) + "\n  ";
    assertThat(decode(wrapped)).isEqualTo(xml);
  }

  // --- Plumbing ---------------------------------------------------------------------------------

  private static String decode(String payload) {
    try {
      return MxDeflate.forDocument().decodeDiagramBody(payload);
    } catch (EngineException error) {
      throw new AssertionError("expected a clean decode", error);
    }
  }

  /** draw.io's own encoder: percent-encode the UTF-8 XML, raw-deflate it, base64 it. */
  private static String drawioEncode(String xml) {
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

  /**
   * The same encoding, except a literal {@code +} is left raw. That is what a writer emitting
   * {@code decodeURIComponent}-readable text may produce, and what URLDecoder would corrupt.
   */
  private static String percentEncodeLeavingPlusLiteral(String text) {
    return encodeUriComponent(text).replace("%2B", "+");
  }

  private static String payloadOfBytes(int inflatedBytes) {
    return repeatedBytePayload(inflatedBytes);
  }

  /** A raw-DEFLATE payload of {@code inflatedBytes} 'A's, deflated without holding them all. */
  private static String repeatedBytePayload(long inflatedBytes) {
    Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    byte[] source = new byte[64 * 1024];
    Arrays.fill(source, (byte) 'A');
    byte[] sink = new byte[64 * 1024];
    long remaining = inflatedBytes;
    while (remaining > 0) {
      int step = (int) Math.min(source.length, remaining);
      deflater.setInput(source, 0, step);
      remaining -= step;
      while (!deflater.needsInput()) {
        compressed.write(sink, 0, deflater.deflate(sink));
      }
    }
    deflater.finish();
    while (!deflater.finished()) {
      compressed.write(sink, 0, deflater.deflate(sink));
    }
    deflater.end();
    return base64(compressed.toByteArray());
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

  private static void assertRefused(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
    assertThatThrownBy(call)
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              EngineException failure = (EngineException) error;
              assertThat(failure.exitCode()).isEqualTo(2);
              assertThat(failure.diagnostics()).hasSize(1);
              Diagnostic diagnostic = failure.diagnostics().get(0);
              assertThat(diagnostic.code()).isEqualTo(code);
              assertThat(diagnostic.path()).isEqualTo("$");
            });
  }
}

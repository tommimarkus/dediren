package dev.dediren.plugins.drawio;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.engine.EngineException;

/**
 * Bounded-input ceilings for the draw.io reader, plus the shared diagnostic-construction helpers
 * the decoder, parser, and mapper use. Modelled on {@code DotLimits}: generous by design, they turn
 * pathological input into a clean diagnostic instead of an OOM or a wedged run, and are not there
 * to police document size.
 *
 * <p><strong>Decompression rule.</strong> Compressed input buys an attacker no more budget than an
 * uncompressed file would have had: {@link #MAX_DECOMPRESSED_BYTES} equals {@link #MAX_INPUT_BYTES}
 * and is a <em>running total across every page in the document</em>, not a per-page allowance. A
 * per-page cap of this size would admit {@link #MAX_PAGES} × 64 MiB = 16 GiB.
 *
 * <p><strong>No compression-ratio cap, deliberately.</strong> Raw DEFLATE tops out near 1032:1, so
 * the absolute ceiling above already bounds the worst case; a ratio cap would add a second tunable
 * and a second diagnostic code without bounding anything the byte ceiling does not already bound.
 * This is a decision, not an omission — do not "fix" it by adding one.
 *
 * <p>Public rather than package-private (unlike {@code DotLimits}) only because the {@code mx}
 * subpackage holds the decoder that has to enforce {@link #MAX_DECOMPRESSED_BYTES}.
 */
public final class DrawioLimits {

  /**
   * Equals {@code SourceLimits.DEFAULT.maxInputFileBytes()}. {@code BoundedReads} already enforces
   * that ceiling on the CLI and MCP lanes before the engine is handed a byte, so any larger value
   * would be unreachable dead code and any smaller one would reject files core accepts.
   */
  public static final long MAX_INPUT_BYTES = 64L * 1024 * 1024;

  /**
   * An {@code mxCell} is the draw.io analogue of a DOT statement: {@code
   * DotLimits.MAX_STATEMENTS}.
   */
  public static final int MAX_CELLS = 200_000;

  /**
   * Must not exceed {@code SourceLimits.DEFAULT.maxElements()}. If the importer accepted more, an
   * over-large document would pass here and then be refused by {@code
   * SourceValidator.gateImportedDocument} at exit 3 with a core diagnostic — a misleading report of
   * the importer's own exit-2 {@code DEDIREN_DRAWIO_ELEMENT_LIMIT_EXCEEDED}.
   */
  public static final int MAX_ELEMENTS = 100_000;

  /** {@code DotLimits.MAX_NESTING}, applied to the {@code mxCell} {@code parent} chain. */
  public static final int MAX_NESTING = 256;

  /** {@code DotLimits.MAX_TOKEN_BYTES}, applied per label and per attribute value. */
  public static final int MAX_TOKEN_BYTES = 64 * 1024;

  /** Each {@code <diagram>} is an independent graph costing one decompression. */
  public static final int MAX_PAGES = 256;

  /**
   * Running total across every page, not per page — see the decompression rule in the class
   * javadoc. Equal to {@link #MAX_INPUT_BYTES} by construction.
   */
  public static final long MAX_DECOMPRESSED_BYTES = MAX_INPUT_BYTES;

  private DrawioLimits() {}

  /** Refuses a label or attribute value whose UTF-8 encoding exceeds {@link #MAX_TOKEN_BYTES}. */
  public static void checkTokenBytes(String token) throws EngineException {
    if (utf8Length(token) > MAX_TOKEN_BYTES) {
      throw limit(
          DiagnosticCode.DRAWIO_TOKEN_LIMIT_EXCEEDED, "draw.io token exceeds the 64 KiB ceiling");
    }
  }

  /** UTF-8 byte length: the wire cost, which {@code String.length()} understates for non-ASCII. */
  public static long utf8Length(String value) {
    long bytes = 0;
    for (int index = 0; index < value.length(); ) {
      int codePoint = value.codePointAt(index);
      bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
      index += Character.charCount(codePoint);
    }
    return bytes;
  }

  public static EngineException syntax(String message, int line, int column) {
    return failure(DiagnosticCode.DRAWIO_SYNTAX_INVALID, message, line, column);
  }

  public static EngineException unsupported(String message, int line, int column) {
    return failure(DiagnosticCode.DRAWIO_UNSUPPORTED_CONSTRUCT, message, line, column);
  }

  public static EngineException failure(DiagnosticCode code, String message, int line, int column) {
    return EngineException.structuralFailure(
        code.code(), message, "line " + line + ", column " + column);
  }

  /** A ceiling breach: no useful location, so the document root is the path. */
  public static EngineException limit(DiagnosticCode code, String message) {
    return EngineException.structuralFailure(code.code(), message, "$");
  }
}

package dev.dediren.core.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whole-file reads with a byte ceiling, for model-supplied input paths. Every dediren input lane
 * reads documents fully into memory before parsing, so the byte ceiling — not element counts — is
 * the heap bound (see docs/threat-model.md). The ceiling is checked against {@link Files#size}
 * before the read, so an oversized file is rejected without buffering any of it. Size-then-read is
 * not atomic; like {@link ConfinedPaths}, that TOCTOU window is an accepted residual because the
 * process runs with the invoking user's own authority.
 */
public final class BoundedReads {
  private BoundedReads() {}

  /**
   * A file over the ceiling. The message carries byte counts only, never the path — call sites on a
   * trust boundary compose their own context and stay sanitized.
   */
  public static final class FileTooLargeException extends IOException {
    public FileTooLargeException(long actualBytes, long maxBytes) {
      super("file is " + actualBytes + " bytes; the input ceiling is " + maxBytes + " bytes");
    }
  }

  public static String readString(Path path, long maxBytes) throws IOException {
    long size = Files.size(path);
    if (size > maxBytes) {
      throw new FileTooLargeException(size, maxBytes);
    }
    return Files.readString(path);
  }

  /** Reads a stream through the same byte ceiling without an unbounded {@code readAllBytes()}. */
  public static String readString(InputStream input, long maxBytes) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    long total = 0;
    int read;
    while ((read = input.read(chunk)) != -1) {
      total += read;
      if (total > maxBytes) {
        throw new FileTooLargeException(total, maxBytes);
      }
      bytes.write(chunk, 0, read);
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }
}

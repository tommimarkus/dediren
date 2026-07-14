package dev.dediren.mcp;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Claims the process's real stdout for the JSON-RPC channel and makes stray prints harmless.
 *
 * <p>In stdio MCP, stdout <em>is</em> the protocol. One {@code System.out.println} anywhere in
 * core, an engine, or a transitive dependency corrupts a frame, and the failure mode is the client
 * silently going dark — no error surfaces, the tools simply stop working. Today's SLF4J and CDS
 * notices happen to go to stderr, but that is luck, not a guarantee, and it will not survive the
 * next dependency.
 *
 * <p>So: take the real file descriptor for the transport, then point {@code System.out} at stderr.
 * A stray print then degrades to log noise instead of protocol corruption. The dist-smoke test
 * ({@code DistTool.assertMcpStdoutIsProtocolOnly}) is this control's gate — it is the only place
 * the real process streams are observable.
 */
public final class StdoutIntegrity {
  private StdoutIntegrity() {}

  /**
   * Returns the real stdout for the transport, and redirects {@code System.out} to stderr.
   *
   * <p><b>The returned stream must stay unbuffered.</b> {@link FrameScanningOutputStream} decorates
   * it and discharges the correlation ledger when a frame's trailing newline is <em>written</em>,
   * which is one write before the transport flushes. That is only sound because the bytes reach the
   * file descriptor as they are written: with a {@link java.io.BufferedOutputStream} in between,
   * the ledger would go square while the final frame was still sitting in the buffer, {@code
   * EofSignalingInputStream} would release stdin's EOF on the strength of it, and the SDK would
   * close the session — losing exactly the response the ledger was invented to protect. Do not wrap
   * this. If a buffer is ever genuinely needed here, the discharge must move to the flush.
   */
  public static OutputStream claimStdout() {
    OutputStream protocolChannel = new FileOutputStream(FileDescriptor.out);
    System.setOut(
        new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    return protocolChannel;
  }
}

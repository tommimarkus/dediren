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

  /** Returns the real stdout for the transport, and redirects {@code System.out} to stderr. */
  public static OutputStream claimStdout() {
    OutputStream protocolChannel = new FileOutputStream(FileDescriptor.out);
    System.setOut(
        new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    return protocolChannel;
  }
}

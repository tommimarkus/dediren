package dev.dediren.mcp;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Wraps an {@link OutputStream} and records every write on a shared {@link OutboundActivityClock}.
 *
 * <p>This is the outbound half of the drain described on {@link EofSignalingInputStream}: it gives
 * the input side something to poll so it can tell whether the MCP stdio transport's response
 * pipeline is still actively flushing before letting the transport observe stdin's EOF.
 *
 * <p>All three {@code write} overloads are forwarded to the delegate explicitly rather than relying
 * on {@link FilterOutputStream}'s default byte-at-a-time delegation, matching the sibling {@link
 * EofSignalingInputStream}'s treatment of its {@code read} overloads.
 */
final class DrainTrackingOutputStream extends FilterOutputStream {
  private final OutboundActivityClock clock;

  DrainTrackingOutputStream(OutputStream out, OutboundActivityClock clock) {
    super(out);
    this.clock = clock;
  }

  @Override
  public void write(int b) throws IOException {
    out.write(b);
    clock.recordWrite();
  }

  @Override
  public void write(byte[] b) throws IOException {
    out.write(b);
    clock.recordWrite();
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    out.write(b, off, len);
    clock.recordWrite();
  }
}

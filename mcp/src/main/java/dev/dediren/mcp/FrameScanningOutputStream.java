package dev.dediren.mcp;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Wraps stdout and discharges each JSON-RPC response it carries from the shared {@link
 * PendingRequests} ledger.
 *
 * <p>This is the outbound half of the EOF correlation described on {@link EofSignalingInputStream}:
 * the input side counts requests in, this side counts responses out, and stdin's EOF is held until
 * the two agree. It replaces a decorator that merely timestamped writes for an idle-timer heuristic
 * — which could not distinguish "the response has not started yet" from "the response finished a
 * while ago", and so dropped every response slower than the timer.
 *
 * <p>The ledger is only discharged <em>after</em> the bytes have gone to the delegate, so a
 * response is never counted as delivered before it is written. Note also that a frame is only
 * recognized once its trailing newline arrives, which is the write immediately preceding the
 * transport's flush.
 *
 * <p>All three {@code write} overloads forward explicitly rather than relying on {@link
 * FilterOutputStream}'s byte-at-a-time default, matching the sibling {@link
 * EofSignalingInputStream}'s treatment of its {@code read} overloads.
 */
final class FrameScanningOutputStream extends FilterOutputStream {
  private final FrameSplitter frames;

  FrameScanningOutputStream(OutputStream out, PendingRequests pending) {
    super(out);
    this.frames = new FrameSplitter(pending::observeOutboundFrame);
  }

  @Override
  public void write(int b) throws IOException {
    out.write(b);
    frames.accept(b);
  }

  @Override
  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    out.write(b, off, len);
    frames.accept(b, off, len);
  }
}

package dev.dediren.mcp;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared between {@link EofSignalingInputStream} and {@link DrainTrackingOutputStream}: records
 * whether the outbound side has written anything yet, and if so, how long ago its most recent write
 * was.
 *
 * <p>Distinguishing "nothing has been written yet" from "something was written and it has since
 * gone quiet" matters: a plain idle clock cannot tell "a response hasn't started writing yet" apart
 * from "a response finished writing a while ago," and treating the former as safe-to-proceed is
 * exactly the bug this class exists to avoid. See {@link EofSignalingInputStream} for how the two
 * states drive the drain wait.
 */
final class OutboundActivityClock {
  private final AtomicLong lastActivityNanos = new AtomicLong();
  private volatile boolean everWritten;

  void recordWrite() {
    everWritten = true;
    lastActivityNanos.set(System.nanoTime());
  }

  boolean everWritten() {
    return everWritten;
  }

  /** Only meaningful once {@link #everWritten()} is {@code true}. */
  long idleNanos() {
    return System.nanoTime() - lastActivityNanos.get();
  }
}

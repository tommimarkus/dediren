package dev.dediren.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Wraps an {@link InputStream}; the instant any read call observes EOF (a {@code -1} return),
 * drains the outbound side before counting down a latch.
 *
 * <p>The MCP stdio transport reads this stream on its own thread and never calls {@code
 * System.exit} or interrupts the thread that started the server, so nothing else surfaces stdin's
 * EOF back to the caller. This decorator is how {@code DedirenMcpServer.serveOn} learns the client
 * closed stdin: it awaits the latch instead of self-joining the calling thread.
 *
 * <p>The vendored SDK's own inbound-reader loop reacts to that same {@code -1} by closing its
 * session synchronously, on the same thread, the instant it observes EOF -- with no drain phase of
 * its own. If the SDK's outbound pipeline still has a response in flight (dispatched to a different
 * scheduler and not yet written, or not even started yet) when that happens, it is dropped
 * silently: no error frame, no warning, just missing bytes on stdout. This is trivially reachable
 * whenever a whole batch of requests is piped in at once (for example a file redirected onto
 * stdin): the last byte's EOF is observed essentially the instant it is consumed, with no pacing to
 * give the async response pipeline a head start, so the SDK's own EOF handling can -- and,
 * empirically, reliably does -- race ahead of it and silently truncate the reply stream.
 *
 * <p>Because the SDK reads EOF directly from this stream, this is the only point where Dediren can
 * intervene: before propagating {@code -1} to the SDK's reader, this class blocks until the
 * outbound side (tracked via a shared {@link OutboundActivityClock}) has written at least once and
 * then gone quiet for {@link #IDLE_WINDOW}, bounded by {@link #MAX_DRAIN} so a stuck engine cannot
 * hang the process forever. Requiring at least one write before treating the clock as "idle enough"
 * matters: a response that has been dispatched but has not started writing yet is not
 * distinguishable from a genuinely finished one by elapsed time alone, so an idle check that fires
 * before the first write would let EOF straight through with nothing drained at all. If this stream
 * itself never read a single byte before observing EOF, there is provably nothing in flight to
 * drain, so the wait is skipped entirely and a client that connects and immediately disconnects
 * pays no penalty.
 *
 * <p>EOF can be observed from {@link #read()}, {@link #read(byte[])}, or {@link #read(byte[], int,
 * int)} depending on which one the caller (or a buffering layer above it) happens to use, so all
 * three are overridden explicitly rather than relying on {@link FilterInputStream}'s default
 * delegation between them.
 */
final class EofSignalingInputStream extends FilterInputStream {
  private static final Duration IDLE_WINDOW = Duration.ofMillis(150);
  private static final Duration MAX_DRAIN = Duration.ofSeconds(2);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

  private final CountDownLatch eofLatch;
  private final OutboundActivityClock outboundActivity;
  private volatile boolean everReadAByte;

  EofSignalingInputStream(
      InputStream in, CountDownLatch eofLatch, OutboundActivityClock outboundActivity) {
    super(in);
    this.eofLatch = eofLatch;
    this.outboundActivity = outboundActivity;
  }

  @Override
  public int read() throws IOException {
    int value = super.read();
    if (value == -1) {
      onEof();
    } else {
      everReadAByte = true;
    }
    return value;
  }

  @Override
  public int read(byte[] b) throws IOException {
    int count = super.read(b);
    if (count == -1) {
      onEof();
    } else if (count > 0) {
      everReadAByte = true;
    }
    return count;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int count = super.read(b, off, len);
    if (count == -1) {
      onEof();
    } else if (count > 0) {
      everReadAByte = true;
    }
    return count;
  }

  private void onEof() {
    if (everReadAByte) {
      drainOutboundActivity();
    }
    eofLatch.countDown();
  }

  /**
   * Blocks the calling thread (the SDK's own inbound-reader thread) until the outbound side has
   * written at least once and then been quiet for {@link #IDLE_WINDOW}, or {@link #MAX_DRAIN} total
   * has elapsed, whichever comes first. Polling rather than a completion signal because the
   * vendored SDK exposes no hook for "all dispatched responses have been written"; a batch that
   * (legitimately) produces no response at all -- for example one containing only notifications --
   * pays the full {@link #MAX_DRAIN} before this gives up, which is the bounded cost of not having
   * that hook.
   */
  private void drainOutboundActivity() {
    long deadlineNanos = System.nanoTime() + MAX_DRAIN.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      if (outboundActivity.everWritten() && outboundActivity.idleNanos() >= IDLE_WINDOW.toNanos()) {
        return;
      }
      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}

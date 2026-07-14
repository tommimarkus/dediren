package dev.dediren.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Wraps stdin: registers every JSON-RPC request that passes through it, and when a read observes
 * EOF, holds that EOF until every registered request has been answered before counting down a
 * latch.
 *
 * <p>Two jobs, both forced on us by the SDK's stdio transport.
 *
 * <p><b>Surfacing EOF.</b> The transport reads this stream on its own thread and never calls {@code
 * System.exit} nor interrupts the thread that started the server, so nothing else tells {@code
 * DedirenMcpServer.serveOn} that the client closed stdin. It awaits this latch instead of
 * self-joining.
 *
 * <p><b>Not losing responses to EOF.</b> The SDK's inbound loop reacts to that same {@code -1} by
 * closing its session synchronously, on this very thread, with no drain phase: {@code
 * startInboundProcessing}'s finally block sets {@code isClosing} and closes the session, after
 * which {@code startOutboundProcessing} <em>silently discards</em> any frame that arrives. Tool
 * calls run on {@code Schedulers.boundedElastic()}, so the reader races ahead of every response
 * still being computed. Whenever a whole batch arrives at once -- a file redirected onto stdin, an
 * MCP client that pipelines -- EOF lands the instant the last byte is consumed, and the responses
 * lose the race. No error frame, no warning, just missing bytes on stdout.
 *
 * <p>Because the SDK takes EOF straight from this stream, this is the only place Dediren can
 * intervene. The wait is correlation, not a timer: {@link PendingRequests} counts the requests this
 * stream has read against the responses the paired {@link FrameScanningOutputStream} has written,
 * and EOF is held until that ledger is square. A batch of notifications alone owes nothing and pays
 * nothing; a batch ending in an eight-second build waits eight seconds. The previous implementation
 * guessed with an idle timer ("the outbound stream has been quiet for 150ms, so it must be done")
 * and thereby dropped the response of every tool slower than the window -- which is to say, {@code
 * dediren_build}, every time.
 *
 * <p>{@link #MAX_WAIT} is a backstop against a request that is never answered at all, not a drain
 * policy: it exists so a wedged engine cannot hang the process forever. If it ever fires, that is a
 * bug and it says so on stderr, naming the ids whose responses were lost. Exiting silently, having
 * dropped a response, is the one thing this class must never do. (stderr, never stdout: stdout is
 * the protocol channel.)
 *
 * <p>EOF can surface from {@link #read()}, {@link #read(byte[])}, or {@link #read(byte[], int,
 * int)}, depending on which one the caller or a buffering layer above it happens to use, so all
 * three are overridden explicitly rather than left to {@link FilterInputStream}'s default
 * delegation.
 */
final class EofSignalingInputStream extends FilterInputStream {
  private static final Duration MAX_WAIT = Duration.ofSeconds(60);

  private final CountDownLatch eofLatch;
  private final PendingRequests pending;
  private final FrameSplitter frames;

  EofSignalingInputStream(InputStream in, CountDownLatch eofLatch, PendingRequests pending) {
    super(in);
    this.eofLatch = eofLatch;
    this.pending = pending;
    this.frames = new FrameSplitter(pending::observeInboundFrame);
  }

  @Override
  public int read() throws IOException {
    int value = super.read();
    if (value == -1) {
      onEof();
    } else {
      frames.accept(value);
    }
    return value;
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int count = super.read(b, off, len);
    if (count == -1) {
      onEof();
    } else if (count > 0) {
      frames.accept(b, off, count);
    }
    return count;
  }

  /**
   * Runs on the SDK's own inbound-reader thread, inside the read call that is about to return
   * {@code -1}. Blocking here is the whole point: the SDK has not seen EOF yet, so its outbound
   * pipeline is still live and can finish writing what it owes.
   *
   * <p>Every request this stream read was registered synchronously by the read that returned its
   * bytes, so by the time EOF is observed the ledger is already complete: there is no request the
   * transport can still be about to see.
   */
  private void onEof() {
    try {
      if (!pending.awaitAllAnswered(MAX_WAIT)) {
        reportUnanswered();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } finally {
      eofLatch.countDown();
    }
  }

  private void reportUnanswered() {
    List<String> lost = pending.outstandingIds();
    // System.err, deliberately. StdoutIntegrity repoints System.out at stderr precisely so stdout
    // carries protocol frames only, and the SLF4J levels that would carry an alarm (warn/error) are
    // banned in first-party code. This is a human debug-channel message about a protocol-level
    // failure, and it must be impossible to miss.
    System.err.println(
        "dediren mcp: stdin reached EOF with "
            + lost.size()
            + " JSON-RPC request(s) unanswered after waiting "
            + MAX_WAIT.toSeconds()
            + "s; no response was written for id(s) "
            + lost
            + ". This is a bug: the client will see those calls hang or fail.");
  }
}

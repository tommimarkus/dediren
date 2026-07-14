package dev.dediren.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
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
 * <p><b>Not wedging on garbage.</b> EOF is not the only way the SDK's reader loop ends. A frame it
 * cannot deserialize is fatal to it: {@code startInboundProcessing} catches the failure, breaks out
 * of the loop, sets {@code isClosing} and closes the session -- and never reads this stream again.
 * EOF is then never observed, the latch never counts down, {@code serveOn} waits on it forever, and
 * because the SDK's schedulers are non-daemon threads the JVM does not exit either. One malformed
 * line and the server is a wedged, unkillable process. {@link #MAX_WAIT} is no help: it bounds the
 * ledger await, and the await never begins. So this stream watches for that frame itself and
 * releases EOF when it sees one. See {@link #readableByTransport(String)} for why that check cannot
 * false-positive.
 *
 * <p><b>Not wedging on a read failure.</b> EOF is not the only way a read can end without producing
 * a byte. {@code startInboundProcessing} also has a plain {@code catch (IOException)} around its
 * read loop, and it treats that exactly like the fatal-frame case: break out, set {@code
 * isClosing}, close the session, never read this stream again. A dying pty (EIO), a FIFO or socket
 * peer reset, anything that surfaces as an {@link IOException} from the underlying stream rather
 * than a clean {@code -1} -- none of it reaches {@link #onEof()}, so the latch never counts down
 * and {@code serveOn} waits on it forever, same as the fatal-frame wedge. So every read override
 * catches {@link IOException} from the delegate call, reports it the same way {@link
 * #onUnreadableFrame} does, and releases the latch -- then rethrows, because the SDK still needs to
 * see the failure to unwind its own loop. This is not a drain: like the fatal-frame path, the
 * transport is already gone, so there is nothing left to wait for.
 *
 * <p>EOF or a read failure can surface from {@link #read()}, {@link #read(byte[])}, or {@link
 * #read(byte[], int, int)}, depending on which one the caller or a buffering layer above it happens
 * to use, so all three are overridden explicitly rather than left to {@link FilterInputStream}'s
 * default delegation. {@link #skip(long)} is overridden too, even though nothing upstream calls it
 * today ({@code BufferedReader}/{@code StreamDecoder} always read): {@link FilterInputStream}'s
 * default {@code skip} delegates straight to the wrapped stream, which would let bytes pass the
 * frame splitter and the correlation ledger unseen.
 */
final class EofSignalingInputStream extends FilterInputStream {
  private static final Duration MAX_WAIT = Duration.ofSeconds(60);

  /**
   * Mirrors the mapper {@code DedirenMcpServer} hands the transport, because {@link
   * #readableByTransport} is only a faithful oracle for what the SDK will accept if it asks the
   * same question of the same mapper. Built by {@link McpJsonMappers#standard()} -- the one place
   * that configuration lives -- so this cannot drift from the transport's own mapper.
   */
  private static final McpJsonMapper MAPPER = McpJsonMappers.standard();

  /** How much of an unreadable frame to quote on stderr. Enough to identify it, not to flood. */
  private static final int PREVIEW_LIMIT = 120;

  private final CountDownLatch eofLatch;
  private final PendingRequests pending;
  private final FrameSplitter frames;

  EofSignalingInputStream(InputStream in, CountDownLatch eofLatch, PendingRequests pending) {
    super(in);
    this.eofLatch = eofLatch;
    this.pending = pending;
    this.frames = new FrameSplitter(this::observeInboundFrame);
  }

  @Override
  public int read() throws IOException {
    int value;
    try {
      value = super.read();
    } catch (IOException failure) {
      onReadFailure(failure);
      throw failure;
    }
    if (value == -1) {
      onEof();
    } else {
      frames.accept(value);
    }
    return value;
  }

  @Override
  public int read(byte[] b) throws IOException {
    // Delegates to the (byte[], int, int) overload below, which is where the failure handling
    // lives -- see that override for why this one needs none of its own.
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int count;
    try {
      count = super.read(b, off, len);
    } catch (IOException failure) {
      onReadFailure(failure);
      throw failure;
    }
    if (count == -1) {
      onEof();
    } else if (count > 0) {
      frames.accept(b, off, count);
    }
    return count;
  }

  /**
   * Discards {@code n} bytes by reading and dropping them through {@link #read(byte[], int, int)},
   * rather than {@link FilterInputStream}'s default of delegating straight to the wrapped stream's
   * {@code skip}. A delegated skip would move the underlying stream forward without the frame
   * splitter or {@link PendingRequests} ever seeing those bytes -- silently corrupting whichever
   * frame they belonged to, or dropping a request off the ledger entirely. Routing through {@link
   * #read(byte[], int, int)} keeps every byte visible to both, at the cost of an allocation nothing
   * upstream is known to trigger.
   */
  @Override
  public long skip(long n) throws IOException {
    if (n <= 0) {
      return 0;
    }
    byte[] discard = new byte[(int) Math.min(n, 8192)];
    long skipped = 0;
    while (skipped < n) {
      int count = read(discard, 0, (int) Math.min(discard.length, n - skipped));
      if (count < 0) {
        break;
      }
      skipped += count;
    }
    return skipped;
  }

  /**
   * Registers a frame with the ledger, and notices the one kind of frame that kills the transport.
   *
   * <p>Ordering is the whole trick. This runs from inside the read that produced the bytes, so
   * every request is on the ledger before the SDK has even parsed it, let alone answered it.
   */
  private void observeInboundFrame(String frame) {
    pending.observeInboundFrame(frame);
    if (!readableByTransport(frame)) {
      onUnreadableFrame(frame);
    }
  }

  /**
   * Whether the SDK's reader can deserialize this frame -- answered by asking the SDK, not by
   * reimplementing its judgement.
   *
   * <p>This is the safety property the whole fatal-frame path rests on. Releasing EOF early on a
   * frame the SDK would in fact have accepted would shut the server down mid-conversation and drop
   * live responses -- a cure worse than the wedge. Guessing at the classification (is a non-string
   * {@code method} fatal? does a number coerce? is a bare array a frame?) is exactly how such a
   * false positive gets written. So the question is put to {@link
   * McpSchema#deserializeJsonRpcMessage}, which is the same call, on the same string, that the
   * reader loop is about to make: if it throws here it will throw there, and if it does not, we do
   * nothing. The cost is one extra parse per inbound frame, which against a tool call that compiles
   * a diagram is not a cost at all.
   */
  private static boolean readableByTransport(String frame) {
    try {
      McpSchema.deserializeJsonRpcMessage(MAPPER, frame);
      return true;
    } catch (IOException | RuntimeException unreadable) {
      return false;
    }
  }

  /**
   * The transport is about to die on this frame. Say so, then release EOF so the process can exit.
   *
   * <p>There is nothing here to drain. The SDK closes its session the moment it reaches this frame,
   * after which it discards every outbound write, so responses still in flight are already lost --
   * waiting for the ledger would buy nothing but a wedge with extra steps. Wait instead and the
   * deadlock is total: the SDK cannot dispatch the frames it has not read yet, because the read
   * that would deliver them is this one.
   */
  private void onUnreadableFrame(String frame) {
    // stderr, deliberately: stdout is the protocol channel (see StdoutIntegrity), and the SLF4J
    // levels that would carry an alarm are banned in first-party code.
    System.err.println(
        "dediren mcp: stdin carried a frame the JSON-RPC transport cannot read, which is fatal to"
            + " it -- it stops reading, so no further request on this connection can be answered."
            + " Shutting down rather than hanging. Offending frame: "
            + preview(frame));
    eofLatch.countDown();
  }

  /**
   * The delegate read just failed. Say so, then release EOF so the process can exit -- symmetric
   * with {@link #onUnreadableFrame}, and for the same reason: {@code startInboundProcessing}'s
   * {@code catch (IOException)} breaks its read loop exactly the way the fatal-frame case does, and
   * never reads this stream again, so nothing will ever call {@link #onEof()} for us.
   *
   * <p>There is nothing here to drain, for the same reason as {@link #onUnreadableFrame}: the SDK
   * is about to close its session on catching this exception, and outbound writes made after that
   * are discarded, so responses still in flight are already lost. This method never swallows the
   * failure -- the caller must still rethrow it, so the SDK sees the same exception and unwinds.
   */
  private void onReadFailure(IOException failure) {
    if (eofLatch.getCount() == 0) {
      // Already released -- an earlier failure or an unreadable frame already ended this session.
      return;
    }
    // stderr, deliberately: stdout is the protocol channel (see StdoutIntegrity), and the SLF4J
    // levels that would carry an alarm are banned in first-party code.
    System.err.println(
        "dediren mcp: reading stdin failed ("
            + failure
            + "), which is fatal to the JSON-RPC transport -- it stops reading, so no further"
            + " request on this connection can be answered. Shutting down rather than hanging.");
    eofLatch.countDown();
  }

  private static String preview(String frame) {
    String oneLine = frame.strip();
    return oneLine.length() <= PREVIEW_LIMIT
        ? "<" + oneLine + ">"
        : "<" + oneLine.substring(0, PREVIEW_LIMIT) + "...> (" + oneLine.length() + " chars)";
  }

  /**
   * Runs on the SDK's own inbound-reader thread, inside the read call that is about to return
   * {@code -1}. Blocking here is the whole point: the SDK has not seen EOF yet, so its outbound
   * pipeline is still live and can finish writing what it owes.
   *
   * <p><b>But not every EOF may be blocked on</b>, and getting this backwards deadlocks the server.
   * When stdin's last line has no trailing newline, {@code BufferedReader} sees EOF <em>twice</em>,
   * and the two mean opposite things:
   *
   * <pre>
   *   stdin "…{id:1}\n…{id:2}\n"      stdin "…{id:1}\n…{id:2}"   (no trailing newline)
   *   ---------------------------     -------------------------------------------------
   *   read()  -> bytes                read()  -> bytes
   *   readLine -> id:1  (dispatched)  readLine -> id:1  (dispatched)
   *   readLine -> id:2  (dispatched)  read()  -> -1   EOF #1: id:2 NOT dispatched yet --
   *   read()  -> -1     EOF: safe                     readLine needs this -1 to know the
   *   readLine -> null                                line ended. Block and it never ends.
   *                                   readLine -> id:2  (dispatched)
   *                                   read()  -> -1   EOF #2: safe, everything dispatched
   *                                   readLine -> null
   * </pre>
   *
   * <p>A non-empty splitter buffer is exactly the signal that tells the two apart: bytes still
   * unterminated means the reader above has not been handed that line yet. So on EOF #1 we only
   * <em>register</em> the tail request on the ledger and get out of the way, returning the {@code
   * -1} that lets {@code readLine} complete the line and the SDK dispatch it. The wait then happens
   * on EOF #2, by which time the ledger is complete and every frame has been dispatched. (An
   * earlier draft of this fix flushed the tail and awaited in the same call, which registered the
   * request correctly and then deadlocked waiting for a response to a request the SDK could not
   * receive, because the read that would deliver it was the one doing the waiting.)
   *
   * <p>That EOF #2 always arrives is a property of {@code BufferedReader}: having returned an
   * unterminated line, it must read again to distinguish "more data" from "end of input". {@code
   * serveOnDoesNotDropTheFinalFrameWhenStdinHasNoTrailingNewline} is what holds that guarantee down
   * -- if a JDK ever cached EOF and stopped re-reading, that test fails loudly rather than the
   * server hanging quietly.
   */
  private void onEof() {
    if (frames.flushPartial()) {
      // EOF #1 of two: the tail is on the ledger, but the SDK is still waiting on this very read
      // to be able to see it. Return -1 and let it.
      return;
    }
    if (eofLatch.getCount() == 0) {
      // Already released -- an unreadable frame killed the transport. Nothing will answer now, and
      // waiting sixty seconds to discover that only delays the exit.
      return;
    }
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

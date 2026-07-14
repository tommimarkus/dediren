package dev.dediren.mcp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Reassembles newline-delimited JSON-RPC frames out of the arbitrary byte chunks that pass through
 * a stream decorator, and hands each complete frame to a consumer.
 *
 * <p>Neither side of the stdio transport writes or reads whole frames at a time. Outbound, the SDK
 * writes the serialized frame and its trailing newline as two separate {@code write(byte[])} calls;
 * inbound, its {@link java.io.BufferedReader} pulls bulk chunks that routinely contain several
 * frames at once, or half of one. So a decorator that wants to see frames has to buffer bytes and
 * cut them itself.
 *
 * <p>Cutting on the {@code 0x0A} byte is safe for UTF-8 specifically: a newline byte can never
 * occur inside a multi-byte sequence, so a chunk boundary can never split a character in a way that
 * corrupts a frame. Bytes are buffered and decoded only once a frame is complete, never chunk by
 * chunk.
 *
 * <p>A newline always ends a frame, even an empty one. A blank line is not nothing: {@code
 * BufferedReader.readLine()} hands the SDK an empty string, which is not valid JSON, and the SDK's
 * reader loop dies on it. An observer that quietly swallowed blank lines would not see the frame
 * that killed the transport. What is <em>not</em> a frame is an empty buffer at end of input --
 * that is just input that ended on a newline, which is the normal case and owes nobody anything.
 * Hence {@link #flushPartial()} emits only when something is actually buffered.
 *
 * <p>This class only observes. The bytes it is shown are passed through to the underlying stream
 * unchanged by the decorator that owns it; nothing here can alter, delay, or drop them.
 */
final class FrameSplitter {
  private static final byte NEWLINE = (byte) '\n';

  /**
   * The most bytes a single still-unterminated frame may accumulate in {@link #partial}. Framing is
   * newline-delimited, so nothing else bounds how long a "line" can run: without this, a peer that
   * simply never sends a newline would grow {@link #partial} without limit and OOM the process one
   * chunk at a time. 16 MiB is far above any legitimate request or response this product produces
   * -- a large hand-authored source model is still comfortably under it -- while keeping the worst
   * case a fixed, small multiple of one frame rather than unbounded.
   *
   * <p>This class only exposes the crossing as a query ({@link #exceedsMaxFrameSize()}); it never
   * acts on it. {@code emit}/{@code flushPartial} are unaffected, so a caller that never checks the
   * query keeps today's behavior exactly. Deciding what "oversized" means -- and whether that is
   * even a meaningful concern, which it is only for untrusted input -- is the caller's job; see
   * {@code EofSignalingInputStream#checkFrameSize} for the inbound side, which is the one direction
   * a client actually controls.
   */
  static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

  private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
  private final Consumer<String> onFrame;

  FrameSplitter(Consumer<String> onFrame) {
    this.onFrame = onFrame;
  }

  synchronized void accept(int b) {
    if ((byte) b == NEWLINE) {
      emit();
    } else {
      partial.write(b);
    }
  }

  synchronized void accept(byte[] bytes, int offset, int length) {
    int start = offset;
    int end = offset + length;
    for (int i = offset; i < end; i++) {
      if (bytes[i] == NEWLINE) {
        partial.write(bytes, start, i - start);
        emit();
        start = i + 1;
      }
    }
    partial.write(bytes, start, end - start);
  }

  /**
   * Emits whatever is still buffered as a final frame, because a stream's last line need not end in
   * a newline and the frame is no less real for it. Returns whether there was anything to emit.
   *
   * <p>The SDK reads with {@link java.io.BufferedReader#readLine()}, which returns an unterminated
   * final line and dispatches it like any other. So an observer that emitted only on {@code 0x0A}
   * would have a blind spot exactly where the correlation ledger must not have one: the last
   * request of a batch would never be registered, EOF would be released as though nothing were
   * owed, and the response -- to work that had already run -- would be discarded in silence. Call
   * this when the underlying stream is known to be exhausted.
   *
   * <p>The return value matters as much as the emission: a non-empty buffer at EOF is proof that
   * the reader above has <em>not yet</em> been given that last line, which changes what the caller
   * may safely do next. See {@code EofSignalingInputStream.onEof}.
   */
  synchronized boolean flushPartial() {
    if (partial.size() == 0) {
      return false;
    }
    emit();
    return true;
  }

  /**
   * Whether the still-unterminated frame currently buffered in {@link #partial} has crossed {@link
   * #MAX_FRAME_BYTES}. A pure query: unlike {@link #emit}, nothing here clears the buffer or
   * notifies {@link #onFrame}.
   */
  synchronized boolean exceedsMaxFrameSize() {
    return partial.size() > MAX_FRAME_BYTES;
  }

  private void emit() {
    String frame = partial.toString(StandardCharsets.UTF_8);
    partial.reset();
    onFrame.accept(frame);
  }
}

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
 * <p>This class only observes. The bytes it is shown are passed through to the underlying stream
 * unchanged by the decorator that owns it; nothing here can alter, delay, or drop them.
 */
final class FrameSplitter {
  private static final byte NEWLINE = (byte) '\n';

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

  private void emit() {
    if (partial.size() == 0) {
      return;
    }
    String frame = partial.toString(StandardCharsets.UTF_8);
    partial.reset();
    onFrame.accept(frame);
  }
}

package dev.dediren.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

/**
 * Wraps an {@link InputStream} and counts down a latch the instant any read call observes EOF (a
 * {@code -1} return).
 *
 * <p>The MCP stdio transport reads this stream on its own thread and never calls {@code
 * System.exit} or interrupts the thread that started the server, so nothing else surfaces stdin's
 * EOF back to the caller. This decorator is how {@code DedirenMcpServer.serveOn} learns the client
 * closed stdin: it awaits the latch instead of self-joining the calling thread.
 *
 * <p>EOF can be observed from {@link #read()}, {@link #read(byte[])}, or {@link #read(byte[], int,
 * int)} depending on which one the caller (or a buffering layer above it) happens to use, so all
 * three are overridden explicitly rather than relying on {@link FilterInputStream}'s default
 * delegation between them.
 */
final class EofSignalingInputStream extends FilterInputStream {
  private final CountDownLatch eofLatch;

  EofSignalingInputStream(InputStream in, CountDownLatch eofLatch) {
    super(in);
    this.eofLatch = eofLatch;
  }

  @Override
  public int read() throws IOException {
    int value = super.read();
    if (value == -1) {
      eofLatch.countDown();
    }
    return value;
  }

  @Override
  public int read(byte[] b) throws IOException {
    int count = super.read(b);
    if (count == -1) {
      eofLatch.countDown();
    }
    return count;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int count = super.read(b, off, len);
    if (count == -1) {
      eofLatch.countDown();
    }
    return count;
  }
}

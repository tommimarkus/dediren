package dev.dediren.schemacache;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads one subprocess pipe to EOF on its own thread so neither pipe can back-pressure the other.
 *
 * <p>Draining a child's stdout to EOF before reading its stderr deadlocks as soon as the child
 * fills the ~64 KiB stderr pipe: it blocks in {@code write(2)}, so it never exits, so stdout never
 * reaches EOF. Every subprocess this module runs — the schema validator and the schema fetcher —
 * drains both pipes concurrently through this class.
 */
final class StreamDrain {

  private volatile byte[] bytes = new byte[0];
  private Thread thread;

  private StreamDrain() {}

  static StreamDrain start(InputStream stream) {
    StreamDrain drain = new StreamDrain();
    Thread thread =
        new Thread(
            () -> {
              try (InputStream source = stream) {
                drain.bytes = source.readAllBytes();
              } catch (IOException ignored) {
                // A pipe that cannot be read leaves the captured bytes empty; the caller's details
                // then fall back to naming the command and its exit status, which still reaches the
                // agent as a structured diagnostic.
              }
            });
    thread.setDaemon(true);
    thread.setName("dediren-subprocess-drain");
    drain.thread = thread;
    thread.start();
    return drain;
  }

  byte[] await() throws InterruptedException {
    thread.join();
    return bytes;
  }
}

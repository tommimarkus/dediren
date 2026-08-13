package dev.dediren.schemacache;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

@FunctionalInterface
interface HttpTransport {
  Response fetch(URI url) throws Exception;

  record Response(int statusCode, InputStream body, long deadlineNanos) {
    Response(int statusCode, InputStream body) {
      this(statusCode, body, Long.MAX_VALUE);
    }

    Response(int statusCode, InputStream body, Duration timeout) {
      this(statusCode, body, deadlineAfter(timeout));
    }

    private static long deadlineAfter(Duration timeout) {
      long now = System.nanoTime();
      long delay = timeout.toNanos();
      return delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
    }
  }
}

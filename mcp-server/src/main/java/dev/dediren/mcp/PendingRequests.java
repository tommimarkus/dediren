package dev.dediren.mcp;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ledger of JSON-RPC requests that have been read from stdin but not yet answered on stdout.
 *
 * <p>This is what lets {@link EofSignalingInputStream} hold stdin's EOF for exactly as long as
 * something is actually in flight, and not one millisecond longer. It replaces an earlier
 * time-based heuristic ("the outbound stream has been quiet for 150ms, so it must be finished")
 * that was simply wrong: elapsed silence is not a proxy for in-flight work. A tool that takes
 * longer than the idle window to produce its first byte looks identical to a tool that finished
 * long ago, so every response slower than the window was dropped — deterministically, silently, and
 * on the flagship {@code dediren_build} path.
 *
 * <p>JSON-RPC 2.0 gives us the exact signal instead of a guess. A frame carrying both an {@code id}
 * and a {@code method} is a request, and the protocol guarantees it is answered exactly once. A
 * frame carrying an {@code id} and no {@code method} is that answer. Notifications (a {@code
 * method}, no {@code id}) are never answered and so never register. Count requests in as they are
 * read, count responses out as they are written, and "is anything still in flight" stops being a
 * guess and becomes arithmetic.
 *
 * <p>Ids are keyed by their textual form, and outstanding requests are counted rather than merely
 * flagged, so a response whose id never matched an inbound request (an error frame with a {@code
 * null} id, say, or a server-initiated request) can never decrement the ledger below what it
 * actually owes.
 */
final class PendingRequests {
  /**
   * Frame classification only ever calls {@code readTree}, so the protocol mapper's binding
   * configuration is irrelevant here; a plain mapper keeps this decoupled from both the SDK's
   * mapper and the product envelope's.
   */
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition allAnswered = lock.newCondition();

  /**
   * Request id -> how many reads of that id are still owed a response. Guarded by {@link #lock}.
   */
  private final Map<String, Integer> outstanding = new LinkedHashMap<>();

  /** Registers an inbound frame. Only a request (an {@code id} <em>and</em> a {@code method}). */
  void observeInboundFrame(String frame) {
    JsonNode node = parse(frame);
    if (node == null) {
      return;
    }
    String id = idOf(node);
    if (id == null || !hasMethod(node)) {
      return;
    }
    lock.lock();
    try {
      outstanding.merge(id, 1, Integer::sum);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Discharges an outbound frame. Only a response (an {@code id} and <em>no</em> {@code method}).
   */
  void observeOutboundFrame(String frame) {
    JsonNode node = parse(frame);
    if (node == null) {
      return;
    }
    String id = idOf(node);
    if (id == null || hasMethod(node)) {
      return;
    }
    lock.lock();
    try {
      Integer owed = outstanding.get(id);
      if (owed == null) {
        return;
      }
      if (owed > 1) {
        outstanding.put(id, owed - 1);
      } else {
        outstanding.remove(id);
      }
      if (outstanding.isEmpty()) {
        allAnswered.signalAll();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Blocks until every request read so far has been answered, or {@code timeout} elapses. Returns
   * {@code true} if the ledger drained, {@code false} if it timed out still owing responses.
   */
  boolean awaitAllAnswered(Duration timeout) throws InterruptedException {
    lock.lock();
    try {
      long remainingNanos = timeout.toNanos();
      while (!outstanding.isEmpty()) {
        if (remainingNanos <= 0) {
          return false;
        }
        remainingNanos = allAnswered.awaitNanos(remainingNanos);
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  /** The ids still owed a response. Empty unless {@link #awaitAllAnswered} gave up. */
  List<String> outstandingIds() {
    lock.lock();
    try {
      return List.copyOf(outstanding.keySet());
    } finally {
      lock.unlock();
    }
  }

  private static boolean hasMethod(JsonNode frame) {
    JsonNode method = frame.get("method");
    return method != null && !method.isNull();
  }

  /** The frame's id in textual form, or {@code null} if it carries no usable id. */
  private static String idOf(JsonNode frame) {
    JsonNode id = frame.get("id");
    // NullNode is a value node, so "id": null (a JSON-RPC error frame for an unparseable request)
    // must be excluded explicitly -- it identifies nothing and must never discharge a real id.
    if (id == null || !id.isValueNode() || id.isNull()) {
      return null;
    }
    return id.asText();
  }

  /**
   * A frame this side cannot parse is a frame it cannot correlate. The SDK still answers (or
   * rejects) it on its own; declining to guess here only ever costs the backstop wait, never a
   * wrong decrement.
   */
  private static JsonNode parse(String frame) {
    try {
      JsonNode node = MAPPER.readTree(frame);
      return node != null && node.isObject() ? node : null;
    } catch (RuntimeException notJson) {
      return null;
    }
  }
}

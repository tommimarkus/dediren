package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.dediren.engine.Engines;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class DedirenMcpServerTest {

  /** Tool registration is engine-independent; mcp must not depend on cli to get a real registry. */
  private McpSyncServer serverIn(Path root, boolean readOnly) {
    return DedirenMcpServer.create(
        root,
        Engines.of(List.of(), List.of(), List.of(), List.of()),
        Map.of(),
        readOnly,
        new ByteArrayInputStream(new byte[0]),
        new ByteArrayOutputStream());
  }

  @Test
  void registersEveryToolByDefault(@TempDir Path root) {
    McpSyncServer server = serverIn(root, false);
    try {
      List<String> names = server.listTools().stream().map(Tool::name).toList();

      assertThat(names)
          .containsExactlyInAnyOrder(
              "dediren_validate",
              "dediren_build",
              "dediren_guide",
              "dediren_diff",
              "dediren_query",
              "dediren_verify",
              "dediren_status");
    } finally {
      server.close();
    }
  }

  @Test
  void readOnlyModeOmitsOnlyTheBuildTool(@TempDir Path root) {
    McpSyncServer server = serverIn(root, true);
    try {
      List<String> names = server.listTools().stream().map(Tool::name).toList();

      // The four analysis tools are read-only, so they stay registered under --read-only; only the
      // artifact-writing build tool drops out.
      assertThat(names)
          .containsExactlyInAnyOrder(
              "dediren_validate",
              "dediren_guide",
              "dediren_diff",
              "dediren_query",
              "dediren_verify",
              "dediren_status");
      assertThat(names).doesNotContain("dediren_build");
    } finally {
      server.close();
    }
  }

  @Test
  void everyToolAdvertisesAnInputSchema(@TempDir Path root) {
    // A static check on ToolSchemas' own JSON strings, not just "some schema is present": an
    // advertised schema that forgot to require an argument DedirenTools actually needs (see its
    // own missing-argument error envelopes) would still pass a bare non-null check.
    Map<String, List<String>> expectedRequired =
        Map.of(
            "dediren_validate", List.of("source"),
            "dediren_build", List.of("source", "out"),
            "dediren_diff", List.of("old", "new"),
            "dediren_query", List.of("source", "kind"),
            "dediren_verify", List.of("source", "artifacts"));
    McpSyncServer server = serverIn(root, false);
    try {
      for (Tool tool : server.listTools()) {
        assertThat(tool.inputSchema())
            .as("tool %s must advertise an input schema", tool.name())
            .isNotNull();
        assertThat(tool.description())
            .as("tool %s must have a description", tool.name())
            .isNotBlank();
        List<String> required = expectedRequired.get(tool.name());
        if (required != null) {
          assertThat(tool.inputSchema().get("required"))
              .as("tool %s must require %s", tool.name(), required)
              .isEqualTo(required);
        }
      }
    } finally {
      server.close();
    }
  }

  /**
   * The read-only MCP resources are registered on the same server the CLI builds — verified here in
   * process, cheaply, rather than only through the heavyweight packaged {@code -Pdist-smoke}. The
   * two anchor URIs are stable product contract: {@code dediren://schema/model.schema.json} (the
   * source-model schema served verbatim) and {@code dediren://diagnostics/catalog} (the generated
   * catalog); a pinned guide topic and the fixture family cover the other two resource families.
   * This in-process check guards the registration input ({@link DedirenResources#specifications()}
   * wired via {@code .resources(...)}); the true {@code resources/list} / {@code resources/read}
   * round-trip over stdio is covered by the dist-tool packaged MCP smoke.
   */
  @Test
  void registersTheReadOnlyResourcesByDefault(@TempDir Path root) {
    McpSyncServer server = serverIn(root, false);
    try {
      List<String> uris = server.listResources().stream().map(Resource::uri).toList();

      assertThat(uris)
          .contains(
              "dediren://schema/model.schema.json",
              "dediren://diagnostics/catalog",
              "dediren://guide/source-json");
      assertThat(uris)
          .as("the bundle's fixture files are served as resources too")
          .anyMatch(uri -> uri.startsWith("dediren://fixture/"));
    } finally {
      server.close();
    }
  }

  /**
   * Regression test for a self-join hang: {@code serveOn} must return once stdin hits EOF, not
   * block forever waiting on the calling thread to join itself. Stdin is already at EOF before the
   * call, so a correct implementation returns promptly; the buggy {@code
   * Thread.currentThread().join()} implementation never returns, and the JUnit 5 timeout fails the
   * test loudly instead of hanging the suite.
   */
  @Test
  void serveOnReturnsWhenStdinIsAtEof(@TempDir Path root) {
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () ->
            DedirenMcpServer.serveOn(
                root,
                Engines.of(List.of(), List.of(), List.of(), List.of()),
                Map.of(),
                true,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream()));
  }

  /**
   * An end-to-end smoke test of the real tool wiring: a whole request batch -- initialize, the
   * initialized notification, tools/list, and a tools/call -- driven through {@code serveOn} with
   * the actual {@link DedirenTools} handlers behind it (not the bare-bones {@link #slowToolServer}
   * the tests below use), asserting every response is present and the guide tool's real content
   * comes back.
   *
   * <p>It does <em>not</em> prove the batch-EOF drop-prevention fix on its own: {@code
   * dediren_guide} answers from a string constant in single-digit milliseconds, so it wins the EOF
   * race even against an implementation that does not really wait for anything still in flight.
   * That claim belongs to {@code serveOnHoldsEofUntilASlowToolHasAnswered} below, which is shaped
   * specifically to lose that race against anything but genuine request/response correlation.
   */
  @Test
  void serveOnAnswersAWholeBatchThroughTheRealToolWiring(@TempDir Path root) {
    String requests =
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"regression-test","version":"1"}}}
        {"jsonrpc":"2.0","method":"notifications/initialized"}
        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
        {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"dediren_guide","arguments":{"topic":"source-json"}}}
        """;
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () ->
            DedirenMcpServer.serveOn(
                root,
                Engines.of(List.of(), List.of(), List.of(), List.of()),
                Map.of(),
                true,
                new ByteArrayInputStream(
                    requests.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                stdout));

    String output = stdout.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(output).as("initialize response").contains("\"id\":1");
    assertThat(output).as("tools/list response").contains("\"id\":2");
    assertThat(output).as("tools/call response").contains("\"id\":3");
    assertThat(output).as("guide content").contains("Minimal Source JSON");
  }

  /**
   * The test above is green for the wrong reason, and on its own it is false confidence: {@code
   * dediren_guide} answers from a string constant in single-digit milliseconds, so it wins the EOF
   * race even against an implementation that does not really wait. That is exactly how the shipped
   * bundle came to drop every {@code dediren_build} response while the suite stayed green.
   *
   * <p>The real contract is that the response of a tool that takes <em>real time</em> survives a
   * batch that EOFs immediately. So drive the production {@code serveOn} transport path with a tool
   * that deliberately sleeps well past any plausible idle window before answering. Against the
   * time-based heuristic this replaced (a 150ms quiet period), both cases here fail with the
   * response silently absent; against request/response correlation, the wait is exactly as long as
   * the tool takes and no longer.
   *
   * <p>Two durations, because one is a coincidence: 200ms clears the old window by a hair, 800ms
   * clears it by a mile, and neither may be truncated.
   */
  @ParameterizedTest(name = "a {0}ms tool still gets its response written")
  @ValueSource(longs = {200, 800})
  void serveOnHoldsEofUntilASlowToolHasAnswered(long toolMillis) {
    String requests =
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"slow-tool-test","version":"1"}}}
        {"jsonrpc":"2.0","method":"notifications/initialized"}
        {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"slow_tool","arguments":{}}}
        """;
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () ->
            DedirenMcpServer.serveOn(
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                stdout,
                (in, out) -> slowToolServer(in, out, Duration.ofMillis(toolMillis))));

    String output = stdout.toString(StandardCharsets.UTF_8);
    assertThat(output).as("initialize response").contains("\"id\":1");
    assertThat(output)
        .as("the slow tool's response must survive stdin's EOF, not be silently dropped")
        .contains("\"id\":2");
    assertThat(output).as("the slow tool's result payload").contains(SLOW_TOOL_SENTINEL);
  }

  /**
   * A batch's final line need not end in a newline -- {@code printf '%s' "$frame" | dediren mcp},
   * an editor with no trailing-newline setting, a client that writes exactly what it means to say.
   * The SDK's reader is {@link java.io.BufferedReader#readLine()}, which returns and dispatches an
   * unterminated final line like any other. So the correlation ledger must see it too.
   *
   * <p>It did not. {@code FrameSplitter} cut only on {@code 0x0A}, so the last frame stayed in its
   * partial buffer and was never registered: the ledger believed nothing was owed, EOF was released
   * the instant the last byte was read, the SDK closed the session, and the tool's response -- for
   * work that had already <em>run</em> -- was silently discarded. Exit code 0, response absent.
   * That is precisely the failure the id-correlation design exists to make impossible, reintroduced
   * by a missing byte.
   *
   * <p>The tool must therefore be slow enough to actually lose that race; a fast one answers before
   * anybody notices the ledger is wrong and would pass against the bug.
   */
  @Test
  void serveOnDoesNotDropTheFinalFrameWhenStdinHasNoTrailingNewline() {
    // Text block closed on the content line: deliberately NO trailing newline after the last frame.
    String requests =
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"no-trailing-newline-test","version":"1"}}}
        {"jsonrpc":"2.0","method":"notifications/initialized"}
        {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"slow_tool","arguments":{}}}""";
    assertThat(requests)
        .as("the premise of this test: stdin's last frame is unterminated")
        .doesNotEndWith("\n");
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () ->
            DedirenMcpServer.serveOn(
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                stdout,
                (in, out) -> slowToolServer(in, out, Duration.ofMillis(400))));

    String output = stdout.toString(StandardCharsets.UTF_8);
    assertThat(output).as("initialize response").contains("\"id\":1");
    assertThat(output)
        .as("the unterminated final frame's response must be written, not silently discarded")
        .contains("\"id\":2");
    assertThat(output).as("the slow tool's result payload").contains(SLOW_TOOL_SENTINEL);
  }

  /**
   * A frame the transport cannot deserialize is fatal to it: {@code
   * StdioServerTransportProvider$StdioMcpSessionTransport.startInboundProcessing} catches the
   * deserialization failure, <em>breaks out of its read loop</em>, sets {@code isClosing} and
   * closes the session. It never reads our stream again -- so {@code EofSignalingInputStream} never
   * sees EOF, never counts the latch down, and {@code serveOn} waits on it forever. The SDK's
   * schedulers are non-daemon threads, so the JVM does not exit either: one malformed line and the
   * server is a wedged, unkillable process. The 60s ledger backstop cannot save this; it is a bound
   * on the <em>await</em>, and the await never begins.
   *
   * <p>Every case here is a frame the SDK itself throws on, which is exactly the trigger condition:
   * bad JSON, a {@code method} that will not bind to a String, a blank line (there is no {@code
   * isBlank} guard in the loop), and valid JSON that is not a JSON-RPC object at all.
   */
  @ParameterizedTest(name = "an unreadable frame [{0}] must not wedge the server")
  @ValueSource(
      strings = {
        "{not json",
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":{\"not\":\"a string\"}}",
        "",
        "[1,2,3]",
      })
  void serveOnReturnsWhenAFrameCannotBeReadByTheTransport(String unreadable, @TempDir Path root) {
    String requests =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
            + "\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"wedge-test\","
            + "\"version\":\"1\"}}}\n"
            + unreadable
            + "\n";

    assertTimeoutPreemptively(
        Duration.ofSeconds(15),
        () ->
            DedirenMcpServer.serveOn(
                root,
                Engines.of(List.of(), List.of(), List.of(), List.of()),
                Map.of(),
                true,
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream()));
  }

  /**
   * A different door to the same wedge as the test above, and the one the fatal-frame fix did not
   * close: {@code startInboundProcessing} also has a plain {@code catch (IOException)} around its
   * read loop, and reacts to it exactly like the unreadable-frame case -- break out, close the
   * session, never read this stream again. That is what a dying pty (EIO) or a FIFO/socket peer
   * reset looks like from here: not a clean {@code -1}, but an {@link java.io.IOException} out of
   * the underlying stream's {@code read}. Nothing about the fatal-frame fix touches that path,
   * because it is not a frame at all -- there is no string for {@code readableByTransport} to
   * judge. Without a read-failure handler in {@code EofSignalingInputStream}, {@link
   * EofSignalingInputStream#onEof()} is simply never reached, the latch never counts down, and
   * {@code serveOn} hangs forever (the SDK's schedulers are non-daemon, so not even the JVM exits).
   *
   * <p>The stream here answers a few bytes of a real request -- enough that {@code initialize}
   * would have gone out over a healthy connection -- and then fails every subsequent read, standing
   * in for the pty dying mid-conversation rather than at the very first byte.
   */
  @Test
  void serveOnReturnsWhenStdinReadThrowsIoException(@TempDir Path root) {
    String prefix =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
            + "\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"read-failure-test\","
            + "\"version\":\"1\"}}}\n";

    assertTimeoutPreemptively(
        Duration.ofSeconds(15),
        () ->
            DedirenMcpServer.serveOn(
                root,
                Engines.of(List.of(), List.of(), List.of(), List.of()),
                Map.of(),
                true,
                new FailsAfterPrefixInputStream(prefix),
                new ByteArrayOutputStream()));
  }

  /**
   * An {@link java.io.InputStream} that answers a fixed prefix normally, then fails every
   * subsequent read with an {@link java.io.IOException} -- standing in for a dying pty (EIO) or a
   * FIFO/socket peer reset partway through a conversation, as opposed to a clean EOF.
   */
  private static final class FailsAfterPrefixInputStream extends java.io.InputStream {
    private final byte[] prefix;
    private int position;

    FailsAfterPrefixInputStream(String prefix) {
      this.prefix = prefix.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int read() throws java.io.IOException {
      if (position >= prefix.length) {
        throw new java.io.IOException("simulated EIO: dead pty");
      }
      return prefix[position++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws java.io.IOException {
      if (position >= prefix.length) {
        throw new java.io.IOException("simulated EIO: dead pty");
      }
      int count = Math.min(len, prefix.length - position);
      System.arraycopy(prefix, position, b, off, count);
      position += count;
      return count;
    }
  }

  /**
   * A notification-only batch owes no responses at all, so EOF is released as soon as the ledger is
   * square -- which, for notifications, is immediately. This is a hang detector, not a stopwatch:
   * the bound is deliberately far larger than the work, because a tight bound on ~10ms of work buys
   * nothing and flakes on a loaded CI box. That EOF is not held for some fixed drain budget is
   * enforced by the design (correlation, not a timer) and proven by the slow-tool tests above,
   * which would fail outright against any timer-based drain.
   */
  @Test
  void serveOnDoesNotWaitOutABatchThatOwesNoResponses(@TempDir Path root) {
    String notifications =
        """
        {"jsonrpc":"2.0","method":"notifications/initialized"}
        {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"1"}}
        """;

    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () ->
            DedirenMcpServer.serveOn(
                root,
                Engines.of(List.of(), List.of(), List.of(), List.of()),
                Map.of(),
                true,
                new ByteArrayInputStream(notifications.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream()));
  }

  /**
   * F5: a peer that never sends a newline must not be allowed to grow {@code
   * EofSignalingInputStream}/{@code FrameSplitter}'s buffer without bound. {@link
   * BoundedGarbageInputStream} declares a size well past {@link FrameSplitter#MAX_FRAME_BYTES} but
   * produces bytes lazily rather than pre-materializing them, so the assertion below on how much of
   * it was actually consumed is the load-bearing one: a pre-allocated array the size of the bound
   * would resolve via the ordinary end-of-stream {@code flushPartial} path regardless of whether
   * the bound fires early, and would prove nothing about boundedness. Reading only a small fraction
   * of the declared size, rather than draining the whole thing before giving up, is exactly the
   * "doesn't OOM" property F5 exists for.
   */
  @Test
  void serveOnStopsReadingWellBeforeAnOversizedFrameEnds(@TempDir Path root) {
    long declaredSize = 4L * FrameSplitter.MAX_FRAME_BYTES;
    BoundedGarbageInputStream garbage = new BoundedGarbageInputStream(declaredSize);

    assertTimeoutPreemptively(
        Duration.ofSeconds(15),
        () ->
            DedirenMcpServer.serveOn(
                root,
                Engines.of(List.of(), List.of(), List.of(), List.of()),
                Map.of(),
                true,
                garbage,
                new ByteArrayOutputStream()));

    assertThat(garbage.bytesProduced())
        .as(
            "the read loop must stop near MAX_FRAME_BYTES (%d), not drain the full declared size"
                + " (%d)",
            FrameSplitter.MAX_FRAME_BYTES, declaredSize)
        .isLessThan(declaredSize / 2);
  }

  /**
   * An {@link java.io.InputStream} that never pre-materializes its payload: it hands out {@code
   * 'a'} bytes (no newline, ever) up to a fixed declared size, then EOFs, counting how many it
   * actually produced. Standing in for a client that streams a multi-gigabyte frame -- the actual
   * attack F5 defends against -- without the test itself needing to allocate that much memory.
   */
  private static final class BoundedGarbageInputStream extends java.io.InputStream {
    private final long declaredSize;
    private long produced;

    BoundedGarbageInputStream(long declaredSize) {
      this.declaredSize = declaredSize;
    }

    @Override
    public synchronized int read() {
      if (produced >= declaredSize) {
        return -1;
      }
      produced++;
      return 'a';
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
      if (produced >= declaredSize) {
        return -1;
      }
      int count = (int) Math.min(len, declaredSize - produced);
      java.util.Arrays.fill(b, off, off + count, (byte) 'a');
      produced += count;
      return count;
    }

    synchronized long bytesProduced() {
      return produced;
    }
  }

  private static final String SLOW_TOOL_SENTINEL = "slow-tool-answered";

  /**
   * A server whose one tool takes {@code delay} to answer. The transport wiring is production's.
   */
  private static McpSyncServer slowToolServer(
      java.io.InputStream in, java.io.OutputStream out, Duration delay) {
    McpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
    return McpServer.sync(new StdioServerTransportProvider(mapper, in, out))
        .serverInfo("dediren-slow-tool-test", "1")
        .capabilities(ServerCapabilities.builder().tools(false).build())
        .toolCall(
            Tool.builder()
                .name("slow_tool")
                .description("Sleeps, then answers. Stands in for any tool that does real work.")
                .inputSchema(mapper, "{\"type\":\"object\",\"properties\":{}}")
                .build(),
            (exchange, request) -> {
              try {
                Thread.sleep(delay.toMillis());
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              return CallToolResult.builder()
                  .addTextContent(SLOW_TOOL_SENTINEL)
                  .isError(false)
                  .build();
            })
        .build();
  }
}

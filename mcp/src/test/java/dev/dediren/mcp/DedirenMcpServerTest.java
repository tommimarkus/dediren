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
  void registersAllThreeToolsByDefault(@TempDir Path root) {
    McpSyncServer server = serverIn(root, false);
    try {
      List<String> names = server.listTools().stream().map(Tool::name).toList();

      assertThat(names)
          .containsExactlyInAnyOrder("dediren_validate", "dediren_build", "dediren_guide");
    } finally {
      server.close();
    }
  }

  @Test
  void readOnlyModeOmitsTheBuildTool(@TempDir Path root) {
    McpSyncServer server = serverIn(root, true);
    try {
      List<String> names = server.listTools().stream().map(Tool::name).toList();

      assertThat(names).containsExactlyInAnyOrder("dediren_validate", "dediren_guide");
      assertThat(names).doesNotContain("dediren_build");
    } finally {
      server.close();
    }
  }

  @Test
  void everyToolAdvertisesAnInputSchema(@TempDir Path root) {
    McpSyncServer server = serverIn(root, false);
    try {
      for (Tool tool : server.listTools()) {
        assertThat(tool.inputSchema())
            .as("tool %s must advertise an input schema", tool.name())
            .isNotNull();
        assertThat(tool.description())
            .as("tool %s must have a description", tool.name())
            .isNotBlank();
      }
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
   * Regression test for silent response loss on a batch-EOF race: when a whole request batch is
   * available on stdin from the start (as it is whenever stdin is redirected from a file, the
   * packaged launcher's own dist-smoke drives it exactly this way), the underlying stream's EOF is
   * observed essentially the instant the last byte is consumed. The vendored SDK's inbound-reader
   * loop reacts to that EOF by closing its session synchronously, with no drain phase, so any
   * response still in flight on another scheduler at that instant is dropped -- silently, with no
   * error frame. Without the drain in {@code EofSignalingInputStream}, this reliably drops the tail
   * of a multi-request batch; every request here must still get its matching response.
   */
  @Test
  void serveOnDoesNotDropResponsesWhenStdinBatchEofsImmediately(@TempDir Path root) {
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

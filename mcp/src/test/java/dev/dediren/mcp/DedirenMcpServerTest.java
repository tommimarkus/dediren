package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.dediren.engine.Engines;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}

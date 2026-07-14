package dev.dediren.mcp;

import dev.dediren.engine.Engines;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assembles the {@code dediren mcp} stdio server.
 *
 * <p>The server is spawned by an MCP client, which owns its lifetime and reaps it — there is no
 * daemon here: no port, no PID file, no idle timeout, no concurrent-client arbitration. That is why
 * this needs no lifecycle design of its own.
 *
 * <p>Under {@code readOnly}, {@code dediren_build} is not registered at all. An absent tool is a
 * better contract than a tool that exists and refuses: the model never sees a capability it cannot
 * use, and it costs nothing in the client's context window.
 */
public final class DedirenMcpServer {
  private static final String SERVER_NAME = "dediren";

  private DedirenMcpServer() {}

  /** Builds the server over the supplied streams. Used by {@code serve} and by tests. */
  public static McpSyncServer create(
      Path root,
      Engines engines,
      Map<String, String> env,
      boolean readOnly,
      InputStream in,
      OutputStream out) {
    // The protocol mapper serializes MCP frames, not Dediren envelopes: envelopes are produced by
    // core and passed through as text, so this mapper never touches the product contract.
    McpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
    StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper, in, out);
    DedirenTools tools = new DedirenTools(root, engines, env);

    var specification =
        McpServer.sync(transport)
            .serverInfo(SERVER_NAME, System.getProperty("dediren.version", "unknown"))
            .capabilities(ServerCapabilities.builder().tools(false).build())
            .toolCall(
                Tool.builder()
                    .name("dediren_validate")
                    .description(
                        "Validate a Dediren source JSON model. Returns the validation envelope:"
                            + " status 'ok' means the model is legal, status 'error' carries the"
                            + " diagnostics to repair. Call dediren_guide with topic 'repair' for"
                            + " the repair rules.")
                    .inputSchema(mapper, ToolSchemas.VALIDATE)
                    .build(),
                (exchange, request) -> tools.validate(request))
            .toolCall(
                Tool.builder()
                    .name("dediren_guide")
                    .description(
                        "Fetch a section of the Dediren agent authoring guide by topic. Omit"
                            + " 'topic' to list the available topics. Start here when authoring a"
                            + " model: topic 'source-json' is the minimal source shape.")
                    .inputSchema(mapper, ToolSchemas.GUIDE)
                    .build(),
                (exchange, request) -> tools.guide(request));

    if (!readOnly) {
      specification =
          specification.toolCall(
              Tool.builder()
                  .name("dediren_build")
                  .description(
                      "Compile a Dediren source model into artifacts (SVG render, ArchiMate OEF,"
                          + " and/or UML XMI) under an output directory. Select a lane by passing"
                          + " its policy: render_policy, oef_policy, xmi_policy. Returns the"
                          + " build-result envelope, which names every artifact written.")
                  .inputSchema(mapper, ToolSchemas.BUILD)
                  .build(),
              (exchange, request) -> tools.build(request));
    }
    return specification.build();
  }

  /** Runs the server on the process's real stdio, blocking until the client closes stdin. */
  public static void serve(Path root, Engines engines, Map<String, String> env, boolean readOnly)
      throws InterruptedException {
    OutputStream protocolChannel = StdoutIntegrity.claimStdout();
    McpSyncServer server = create(root, engines, env, readOnly, System.in, protocolChannel);
    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    // The stdio transport reads System.in on its own thread and completes when the client closes
    // it. Park until the JVM is torn down by that completion or by the shutdown hook.
    Thread.currentThread().join();
  }
}

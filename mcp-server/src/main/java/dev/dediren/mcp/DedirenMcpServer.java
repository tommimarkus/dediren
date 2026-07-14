package dev.dediren.mcp;

import dev.dediren.engine.Engines;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

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
    // core and passed through as text, so this mapper never touches the product contract. Built by
    // McpJsonMappers so this stays identical to the oracle EofSignalingInputStream uses.
    McpJsonMapper mapper = McpJsonMappers.standard();
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
                          + " build-result envelope, which names every artifact written. A"
                          + " DEDIREN_SCHEMA_VERSION_OUTDATED error means a source or policy file"
                          + " declares a superseded schema version: call dediren_guide with topic"
                          + " 'migration' for the upgrade steps.")
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
    serveOn(root, engines, env, readOnly, System.in, protocolChannel);
  }

  /** Builds an MCP server over the transport streams it is handed. */
  @FunctionalInterface
  interface ServerFactory {
    McpSyncServer create(InputStream in, OutputStream out);
  }

  /** Runs the Dediren tool server over the given streams, returning once {@code in} reaches EOF. */
  static void serveOn(
      Path root,
      Engines engines,
      Map<String, String> env,
      boolean readOnly,
      InputStream in,
      OutputStream out)
      throws InterruptedException {
    serveOn(
        in,
        out,
        (decoratedIn, decoratedOut) ->
            create(root, engines, env, readOnly, decoratedIn, decoratedOut));
  }

  /**
   * Runs the server the factory builds over the given streams, returning once {@code in} reaches
   * EOF.
   *
   * <p>Everything here is transport-level and independent of which tools are registered, which is
   * why the server arrives as a factory: the decoration has to happen before the transport is
   * constructed, and tests need to drive this exact code path with a tool of their own choosing
   * (one slow enough to lose the EOF race) rather than re-assembling a look-alike of it.
   *
   * <p>The stdio transport reads {@code in} on its own thread; on EOF it closes the session but
   * neither calls {@code System.exit} nor interrupts this thread, so nothing unblocks a plain
   * self-join. {@code in} is wrapped so the wrapper counts down a latch the instant a read call
   * observes EOF, and this method awaits that latch instead, then closes the server itself.
   *
   * <p>{@code in} and {@code out} also share a {@link PendingRequests} ledger. The input wrapper
   * registers each JSON-RPC request it reads; the output wrapper discharges each response it
   * writes; and EOF is held from the SDK until the ledger is square, so a response still being
   * computed when stdin closes cannot be discarded by the SDK's own EOF-triggered close. Requests
   * in, responses out — nothing is guessed and nothing waits longer than it must.
   */
  static void serveOn(InputStream in, OutputStream out, ServerFactory factory)
      throws InterruptedException {
    CountDownLatch stdinClosed = new CountDownLatch(1);
    PendingRequests pending = new PendingRequests();
    McpSyncServer server =
        factory.create(
            new EofSignalingInputStream(in, stdinClosed, pending),
            new FrameScanningOutputStream(out, pending));
    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    stdinClosed.await();
    server.close();
  }
}

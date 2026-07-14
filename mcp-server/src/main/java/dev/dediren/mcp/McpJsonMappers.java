package dev.dediren.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place the MCP protocol mapper is configured.
 *
 * <p>{@code DedirenMcpServer} hands this mapper to the real transport; {@code
 * EofSignalingInputStream} hands an identically-built one to {@code
 * McpSchema#deserializeJsonRpcMessage} as the oracle for whether the transport can read a given
 * frame (see {@code EofSignalingInputStream#readableByTransport}). That oracle is only faithful if
 * it asks the same question, with the same mapper configuration, that the live transport would ask
 * -- a mapper built differently could accept a frame the transport rejects (or the reverse), which
 * would either miss the fatal-frame case entirely or release EOF early and drop live responses.
 * Both call sites go through this method so the two configurations cannot drift apart by editing
 * one and forgetting the other.
 */
final class McpJsonMappers {
  private McpJsonMappers() {}

  /** The default-configured protocol mapper, shared by the live transport and its oracle. */
  static McpJsonMapper standard() {
    return new JacksonMcpJsonMapper(JsonMapper.builder().build());
  }
}

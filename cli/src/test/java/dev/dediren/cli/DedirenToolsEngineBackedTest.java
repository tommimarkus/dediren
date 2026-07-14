package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.mcp.DedirenTools;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * {@link DedirenTools} branches that only run behind a real {@link dev.dediren.engine.Engines}
 * registry: the {@code validate} profile lane ({@code CoreCommands.semanticValidateCommand}, not
 * {@code SourceValidator}) and {@code build}'s exception-to-envelope mapping. {@code mcp-server}'s
 * own {@code DedirenToolsTest} is confined to an empty registry ({@code Engines.of(List.of(),
 * ...)}) because {@code mcp-server} must not depend on {@code cli} -- only {@code cli}'s {@link
 * EngineWiring} may construct the bundled engines. These cases live here, next to {@link
 * CliMcpParityTest} (the other test that needs the same registry), rather than in {@code
 * mcp-server}.
 *
 * <p>Unlike {@link CliMcpParityTest}, nothing here compares against the CLI lane -- these are
 * {@link DedirenTools}'s own correctness on branches its narrower {@code mcp-server} test cannot
 * reach at all, not a parity check.
 */
class DedirenToolsEngineBackedTest {

  private static Path fixture(String name) {
    return Path.of("..", "fixtures", "source", name).toAbsolutePath().normalize();
  }

  private static Path policy(String name) {
    return Path.of("..", "fixtures", "render-policy", name).toAbsolutePath().normalize();
  }

  private static String textOf(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static JsonNode envelopeOf(CallToolResult result) {
    return JsonSupport.objectMapper().readTree(textOf(result));
  }

  @Test
  void validateWithAProfileReturnsTheSemanticValidationEnvelope(@TempDir Path root)
      throws Exception {
    Files.copy(fixture("valid-uml-sequence-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .validate(
                new CallToolRequest(
                    "dediren_validate", Map.of("source", "model.json", "profile", "uml")));

    JsonNode envelope = envelopeOf(result);
    assertThat(envelope.path("status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/semantic_profile").asText()).isEqualTo("uml");
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
  }

  /**
   * Forces the same {@code UncheckedIOException} -> {@code DEDIREN_COMMAND_IO_FAILED}
   * exception-to-envelope branch as {@link CliMcpParityTest}'s build-error parity case, but
   * asserted here as {@link DedirenTools#build}'s own correctness rather than a CLI comparison. See
   * that test's doc for why the write collision reaches this branch specifically.
   */
  @Test
  void buildReachesAnExceptionToEnvelopeBranchOnAWriteCollision(@TempDir Path root)
      throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));
    Files.copy(policy("default-svg.json"), root.resolve("policy.json"));
    Path out = Files.createDirectories(root.resolve("out"));
    Files.writeString(out.resolve("main"), "occupied");

    CallToolResult result =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", out.toString(),
                        "render_policy", "policy.json")));

    assertThat(result.isError()).isTrue();
    JsonNode envelope = envelopeOf(result);
    assertThat(envelope.path("status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_COMMAND_IO_FAILED");
  }
}

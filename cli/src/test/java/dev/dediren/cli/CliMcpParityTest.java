package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.mcp.DedirenTools;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The MCP surface must not drift from the CLI. Same inputs, same envelope — byte for byte.
 *
 * <p>Both lanes call the same core entry points, so a divergence here means the MCP layer has
 * started interpreting, reformatting, or re-deriving something it should be passing through.
 */
class CliMcpParityTest {

  private static String textOf(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static Path fixture(String name) {
    return Path.of("..", "fixtures", "source", name).toAbsolutePath().normalize();
  }

  private static Path policy(String name) {
    return Path.of("..", "fixtures", "render-policy", name).toAbsolutePath().normalize();
  }

  @Test
  void validateProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);

    CliResult cli =
        Main.executeForTesting(new String[] {"validate", "--input", source.toString()}, "");

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
  }

  @Test
  void validateProducesTheSameErrorEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("invalid-duplicate-id.json"), source);

    CliResult cli =
        Main.executeForTesting(new String[] {"validate", "--input", source.toString()}, "");

    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(cli.exitCode()).isNotZero();
    assertThat(textOf(mcp).strip()).isEqualTo(cli.stdout().strip());
    assertThat(mcp.isError()).isTrue();
  }

  @Test
  void buildProducesTheSameEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-pipeline-rich.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("rich-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    CliResult cli =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              cliOut.toString(),
              "--render-policy",
              renderPolicy.toString()
            },
            "");

    Path mcpOut = Files.createDirectories(root.resolve("mcp-out"));
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "render_policy", "policy.json")));

    assertThat(cli.exitCode()).isZero();
    assertThat(mcp.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
    assertThat(mcpOut.resolve("main")).isDirectory();
  }

  /** The envelope names the artifacts it wrote, so the out dir differs between the two lanes. */
  private static String normalizePaths(String envelope, Path out) {
    return envelope.replace(out.toString(), "<OUT>").strip();
  }
}

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

  /**
   * The one lane the suite never drove: a build that fails <em>after</em> validation, inside {@code
   * BuildCommand.run} itself, so it escapes as a raw exception rather than folding into a per-view
   * error in the returned {@code BuildResult} envelope. That is exactly where the two lanes diverge
   * in code -- {@code DedirenTools.build}'s own {@code catch} clauses versus {@code Main}'s {@code
   * writePluginError}/{@code printCommandIoFailure}/{@code printProductRootFailure} -- and it had
   * no coverage at all.
   *
   * <p>Forced by occupying the view's write target with a plain file before the build runs: {@code
   * BuildCommand.writeFile} calls {@code Files.createDirectories(outDir/"main")} to create the view
   * directory, and per the {@code Files.createDirectories} contract that throws {@code
   * FileAlreadyExistsException} when the target exists but is not a directory. That IOException is
   * wrapped as an {@code UncheckedIOException} and thrown from a call site outside every {@code
   * runStage} try/catch in {@code BuildCommand}, so it propagates out of {@code
   * BuildCommand.run(...)} uncaught -- straight into the one exception->envelope branch this test
   * had never exercised on either lane.
   */
  @Test
  void buildProducesTheSameErrorEnvelopeThroughBothLanes(@TempDir Path root) throws Exception {
    Path source = root.resolve("model.json");
    Files.copy(fixture("valid-basic.json"), source);
    Path renderPolicy = root.resolve("policy.json");
    Files.copy(policy("default-svg.json"), renderPolicy);

    Path cliOut = Files.createDirectories(root.resolve("cli-out"));
    Files.writeString(cliOut.resolve("main"), "occupied");
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
    Files.writeString(mcpOut.resolve("main"), "occupied");
    CallToolResult mcp =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", mcpOut.toString(),
                        "render_policy", "policy.json")));

    assertThat(cli.exitCode()).isNotZero();
    assertThat(mcp.isError()).isEqualTo(cli.exitCode() != 0);
    assertThat(normalizePaths(textOf(mcp), mcpOut)).isEqualTo(normalizePaths(cli.stdout(), cliOut));
    // Sanity check that this is genuinely the exception->envelope branch, not a per-view
    // BuildResult error: DedirenTools.build's UncheckedIOException catch and Main's
    // printCommandIoFailure both publish DEDIREN_COMMAND_IO_FAILED.
    assertThat(textOf(mcp)).contains("DEDIREN_COMMAND_IO_FAILED");
  }

  /** The envelope names the artifacts it wrote, so the out dir differs between the two lanes. */
  private static String normalizePaths(String envelope, Path out) {
    return envelope.replace(out.toString(), "<OUT>").strip();
  }
}

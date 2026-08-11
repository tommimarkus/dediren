package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.Engines;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code dediren_build} tool's package lane, engine-free paths only (the registry is empty —
 * real-engine coverage lives in the cli parity/smoke tests, since mcp must not depend on cli). Pins
 * the guards a package call meets before any engine: mutual exclusion with the single-model
 * options, schema rejection, and workspace-root confinement of the package path.
 */
class DedirenBuildPackageToolTest {

  private static DedirenTools toolsIn(Path root) {
    return new DedirenTools(root, Engines.of(List.of(), List.of(), List.of(), List.of()), Map.of());
  }

  private static String textOf(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static CallToolResult buildIn(Path root, Map<String, ?> arguments) {
    DedirenTools tools = toolsIn(root);
    CallToolResult opened =
        tools.workspaceOpen(new CallToolRequest("dediren_workspace_open", Map.of()));
    String id =
        JsonSupport.objectMapper().readTree(textOf(opened)).at("/data/workspace_id").asText();
    Map<String, Object> isolated = new LinkedHashMap<>(arguments);
    isolated.put("workspace_id", id);
    return tools.build(new CallToolRequest("dediren_build", isolated));
  }

  @Test
  void packageIsMutuallyExclusiveWithTheSingleModelOptions(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("package.json"), "{}");

    CallToolResult result =
        buildIn(root, Map.of("package", "package.json", "source", "model.json"));

    assertThat(textOf(result)).contains("mutually exclusive");
  }

  @Test
  void schemaInvalidPackageIsRejectedWithoutReachingAnEngine(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("package.json"), "{}");

    CallToolResult result = buildIn(root, Map.of("package", "package.json"));

    assertThat(JsonSupport.objectMapper().readTree(textOf(result)).at("/status").asText())
        .isEqualTo("error");
  }

  @Test
  void aPackagePathOutsideTheRootIsRejected(@TempDir Path root) {
    CallToolResult result = buildIn(root, Map.of("package", "../escape/package.json"));

    assertThat(textOf(result)).contains("outside the workspace root");
  }
}

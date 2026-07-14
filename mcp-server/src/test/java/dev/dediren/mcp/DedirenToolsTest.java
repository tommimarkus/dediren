package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.Engines;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class DedirenToolsTest {

  /** The checked-in fixtures are the authority on the source shape — never hand-roll one. */
  private static Path fixture(String name) {
    return Path.of("..", "fixtures", "source", name).toAbsolutePath().normalize();
  }

  private static String textOf(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }

  private static JsonNode envelopeOf(CallToolResult result) {
    return JsonSupport.objectMapper().readTree(textOf(result));
  }

  /**
   * An empty registry. None of these cases reaches an engine: schema validation is engine-free
   * (SourceValidator), the guide never touches core, and the path-escape and missing-argument cases
   * fail before dispatch. Real-engine coverage lives in CliMcpParityTest (Task 6), which is in cli
   * because only cli may construct engines — and mcp must not depend on cli, which depends on mcp.
   */
  private static Engines noEngines() {
    return Engines.of(List.of(), List.of(), List.of(), List.of());
  }

  private DedirenTools toolsIn(Path root) {
    return new DedirenTools(root, noEngines(), Map.of());
  }

  @Test
  void guideWithoutTopicReturnsTheIndex() {
    CallToolResult result =
        toolsIn(Path.of(".")).guide(new CallToolRequest("dediren_guide", Map.of()));

    assertThat(textOf(result)).contains("topics");
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
  }

  @Test
  void guideWithTopicReturnsThatSection() {
    CallToolResult result =
        toolsIn(Path.of("."))
            .guide(new CallToolRequest("dediren_guide", Map.of("topic", "render-policy")));

    assertThat(textOf(result)).contains("Render Policy Options");
  }

  @Test
  void validateReturnsTheEnvelopeVerbatim(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(envelopeOf(result).path("status").asText()).isEqualTo("ok");
    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
  }

  @Test
  void validateFlagsAnErrorEnvelopeAsIsError(@TempDir Path root) throws Exception {
    Files.copy(fixture("invalid-duplicate-id.json"), root.resolve("broken.json"));

    CallToolResult result =
        toolsIn(root)
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "broken.json")));

    assertThat(envelopeOf(result).path("status").asText()).isEqualTo("error");
    assertThat(result.isError()).isTrue();
  }

  @Test
  void validateRejectsASourceOutsideTheRoot(@TempDir Path root) {
    CallToolResult result =
        toolsIn(root)
            .validate(
                new CallToolRequest("dediren_validate", Map.of("source", "../../etc/passwd")));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
  }

  @Test
  void buildRejectsAnOutDirOutsideTheRoot(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build", Map.of("source", "model.json", "out", "../escape")));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
  }

  @Test
  void buildRequiresASource(@TempDir Path root) {
    CallToolResult result =
        toolsIn(root).build(new CallToolRequest("dediren_build", Map.of("out", "out")));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }
}

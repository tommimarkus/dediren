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
  void validateRejectsASourceOutsideTheRoot(@TempDir Path root) throws Exception {
    CallToolResult result =
        toolsIn(root)
            .validate(
                new CallToolRequest("dediren_validate", Map.of("source", "../../etc/passwd")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    // The envelope goes to the model. It must never leak the resolved absolute workspace root
    // (host filesystem reconnaissance) -- only the model's own candidate string comes back.
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toString());
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
    assertThat(diagnostic.path("message").asText()).contains("../../etc/passwd");
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
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    // Same host-filesystem-reconnaissance concern as the validate case above.
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toString());
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
  }

  // A source is model-supplied, so its fragments[] paths are model-supplied too. They must be
  // confined to --root exactly like the tool's own path arguments, and their errors sanitized. No
  // fixture carries a fragment shape, so these mirror CliValidateTest's inline fragment models.
  private static String modelWithFragment(String fragmentPath) {
    return """
        {
          "model_schema_version": "model.schema.v1",
          "fragments": ["%s"],
          "nodes": [],
          "relationships": [],
          "plugins": { "generic-graph": { "views": [] } }
        }
        """
        .formatted(fragmentPath);
  }

  @Test
  void validateConfinesAnEscapingSourceFragmentToTheRoot(@TempDir Path root) throws Exception {
    // The source is inside root, but its fragment escapes it. The fragment target need not exist:
    // confinement decides before the read, so no exists-vs-not-exists oracle is possible.
    Files.writeString(root.resolve("model.json"), modelWithFragment("../frag-escape.json"));

    CallToolResult result =
        toolsIn(root)
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    assertThat(diagnostic.path("path").asText()).isEqualTo("$.fragments[0]");
    // Only the model's own relative fragment string comes back -- never the resolved absolute
    // target or the workspace root, and never a distinguishable exists-vs-not-exists signal.
    assertThat(diagnostic.path("message").asText()).contains("../frag-escape.json");
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toString());
    assertThat(diagnostic.path("message").asText()).doesNotContain(root.toRealPath().toString());
    assertThat(diagnostic.path("message").asText())
        .doesNotContain(root.getParent().toRealPath().toString());
  }

  @Test
  void validateLoadsALegitimateFragmentInsideTheRoot(@TempDir Path root) throws Exception {
    // A fragment in a subdirectory of root must still load -- confinement must not false-reject it.
    Files.writeString(root.resolve("model.json"), modelWithFragment("sub/piece.json"));
    Files.createDirectories(root.resolve("sub"));
    Files.writeString(
        root.resolve("sub/piece.json"),
        """
        {
          "model_schema_version": "model.schema.v1",
          "nodes": [
            { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
          ],
          "relationships": [],
          "plugins": { "generic-graph": { "views": [] } }
        }
        """);

    CallToolResult result =
        toolsIn(root)
            .validate(new CallToolRequest("dediren_validate", Map.of("source", "model.json")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    JsonNode envelope = envelopeOf(result);
    assertThat(envelope.path("status").asText()).isEqualTo("ok");
    assertThat(envelope.path("data").path("node_count").asInt()).isEqualTo(1);
  }

  @Test
  void buildRequiresASource(@TempDir Path root) {
    CallToolResult result =
        toolsIn(root).build(new CallToolRequest("dediren_build", Map.of("out", "out")));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }

  @Test
  void buildRejectsAViewIdWithAPathSeparator(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "views", List.of("../evil"))));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("diagnostics").path(0).path("code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    // Rejected before any write: the out directory is never even created.
    assertThat(Files.exists(root.resolve("out"))).isFalse();
  }

  // THE LIVE CASE. A blank string clears the SDK's input-schema validation ({"type":"string"} has
  // no minLength), reaches this handler, and used to be dropped -- collapsing views to empty, which
  // BuildCommand.selectViews reads as "build every view". Verified on the shipped bundle: a 3-view
  // model with views:[""] built all three under status:"ok".
  @Test
  void buildRejectsABlankViewsElement(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "views", List.of(""))));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'views'[0]");
    // The whole point: a malformed list must not silently become "build every view".
    assertThat(Files.exists(root.resolve("out"))).isFalse();
  }

  // Defence in depth. The SDK's input validation (on by default, opt-out only) rejects this over a
  // real connection before the handler sees it -- but the handler must not depend on that default.
  @Test
  void buildRejectsANonStringViewsElement(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "views", List.of(1))));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'views'[0]");
    assertThat(diagnostic.path("path").asText()).isEqualTo("views");
    // The whole point: a malformed list must not silently become "build every view".
    assertThat(Files.exists(root.resolve("out"))).isFalse();
  }

  @Test
  void buildRejectsABlankEmitElement(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "emit", List.of("layout-request", "  "))));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'emit'[1]");
  }

  @Test
  void buildRejectsAViewsArgumentThatIsNotAnArray(@TempDir Path root) throws Exception {
    Files.copy(fixture("valid-basic.json"), root.resolve("model.json"));

    CallToolResult result =
        toolsIn(root)
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source", "model.json",
                        "out", "out",
                        "views", "main")));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'views' must be an array of strings");
  }
}

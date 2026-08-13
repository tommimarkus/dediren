package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.mcp.DedirenTools;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
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
  void buildRecordsAWriteCollisionAsAPerViewError(@TempDir Path root) throws Exception {
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
                        "source",
                        "model.json",
                        "out",
                        out.toString(),
                        "render_policy",
                        "policy.json")));

    assertThat(result.isError()).isTrue();
    JsonNode buildResult = envelopeOf(result);
    assertThat(buildResult.path("status").asText()).isEqualTo("error");
    assertThat(buildResult.at("/views/0/status").asText()).isEqualTo("error");
    assertThat(buildResult.at("/views/0/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_COMMAND_IO_FAILED");
  }

  /**
   * The real-harm case behind the {@code stringListArg} guard (see {@code DedirenTools.build}):
   * with an empty engine registry, {@code mcp-server}'s own {@code DedirenToolsTest} can only prove
   * that a regressed guard would let the request reach {@code BuildCommand} -- it fails there at
   * engine dispatch either way, so it cannot show what a regression would actually *do*. Only a
   * real registry can reproduce the shipped defect: a blank {@code views} element used to be
   * silently dropped, collapsing the list to empty, which {@code BuildCommand.selectViews} reads as
   * "build every view" -- so a malformed single-view request quietly became a different, larger,
   * successful one. {@code valid-uml-basic.json} has three views (class-view, data-view,
   * activity-view), so a regression here would build and write all three instead of rejecting the
   * request.
   */
  @Test
  void buildRejectsABlankViewsElementInsteadOfSilentlyBuildingEveryView(@TempDir Path root)
      throws Exception {
    Files.copy(fixture("valid-uml-basic.json"), root.resolve("model.json"));
    Files.copy(policy("uml-svg.json"), root.resolve("policy.json"));
    Path out = root.resolve("out");

    CallToolResult result =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source",
                        "model.json",
                        "out",
                        out.toString(),
                        "render_policy",
                        "policy.json",
                        "views",
                        List.of(""))));

    assertThat(result.isError()).isTrue();
    JsonNode diagnostic = envelopeOf(result).path("diagnostics").path(0);
    assertThat(diagnostic.path("code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(diagnostic.path("message").asText()).contains("'views'[0]");
    // The whole point: a malformed request must not silently become "build every view" and write
    // every view's output directory.
    assertThat(Files.exists(out.resolve("class-view"))).isFalse();
    assertThat(Files.exists(out.resolve("data-view"))).isFalse();
    assertThat(Files.exists(out.resolve("activity-view"))).isFalse();
  }

  @Test
  void inlineMermaidContentProducesTheSameDataEnvelopeAsTheConfinedFile(@TempDir Path root)
      throws Exception {
    String mermaid = "flowchart TD\nstart[Start] --> finish[Finish]\n";
    Files.writeString(root.resolve("diagram.mmd"), mermaid);
    DedirenTools tools = new DedirenTools(root, EngineWiring.defaults(), Map.of());

    CallToolResult fromFile =
        tools.importSource(
            new CallToolRequest(
                "dediren_import", Map.of("source", "diagram.mmd", "plugin", "mermaid")));
    CallToolResult fromContent =
        tools.importSource(
            new CallToolRequest(
                "dediren_import", Map.of("content", mermaid, "plugin", "mermaid")));

    assertThat(fromFile.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(fromContent.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(textOf(fromContent)).isEqualTo(textOf(fromFile));
    assertThat(fromContent.content()).hasSize(1);
    assertThat(fromContent.content().getFirst()).isInstanceOf(TextContent.class);
  }

  @Test
  void svgOutputReturnsDecodedImagesAfterTheDataEnvelopeInRequestedViewOrder(@TempDir Path root)
      throws Exception {
    Files.copy(fixture("valid-uml-basic.json"), root.resolve("model.json"));
    Files.copy(policy("uml-svg.json"), root.resolve("policy.json"));

    CallToolResult result =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source",
                        "model.json",
                        "out",
                        "out",
                        "render_policy",
                        "policy.json",
                        "views",
                        List.of("activity-view", "class-view"),
                        "output",
                        "svg")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(result.content()).hasSize(3);
    assertThat(result.content().getFirst()).isInstanceOf(TextContent.class);
    assertDecodedSvgImage(result.content().get(1), "activity-view");
    assertDecodedSvgImage(result.content().get(2), "class-view");
  }

  @Test
  void failedSvgBuildRetainsEarlierSvgImagesButNeverAttachesOneForTheFailedView(
      @TempDir Path root) throws Exception {
    Files.copy(fixture("valid-uml-basic.json"), root.resolve("model.json"));
    Files.copy(policy("uml-svg.json"), root.resolve("policy.json"));
    Path out = Files.createDirectories(root.resolve("out"));
    Files.writeString(out.resolve("activity-view"), "occupied");

    CallToolResult result =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build",
                    Map.of(
                        "source",
                        "model.json",
                        "out",
                        "out",
                        "render_policy",
                        "policy.json",
                        "views",
                        List.of("class-view", "activity-view"),
                        "output",
                        "svg")));

    assertThat(result.isError()).isTrue();
    assertThat(envelopeOf(result).path("status").asText()).isEqualTo("error");
    assertThat(result.content()).hasSize(2);
    assertDecodedSvgImage(result.content().get(1), "class-view");
  }

  @Test
  void packageBuildUsesItsDeclaredPolicyAndCanReturnItsDiagramAsAnInlineSvg(
      @TempDir Path root) throws Exception {
    Files.copy(fixture("valid-pipeline-rich.json"), root.resolve("model.json"));
    Files.copy(policy("rich-svg.json"), root.resolve("render-policy.json"));
    Files.writeString(
        root.resolve("package.json"),
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "model", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "model", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/main.svg" } }
          ]
        }
        """);

    CallToolResult result =
        new DedirenTools(root, EngineWiring.defaults(), Map.of())
            .build(
                new CallToolRequest(
                    "dediren_build", Map.of("package", "package.json", "output", "svg")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(result.content()).hasSize(2);
    assertThat(result.content().getFirst()).isInstanceOf(TextContent.class);
    assertDecodedSvgImage(result.content().get(1), "main");
  }

  private static void assertDecodedSvgImage(Object content, String expectedView) {
    JsonNode image = JsonSupport.objectMapper().valueToTree(content);
    assertThat(image.path("type").asText()).isEqualTo("image");
    assertThat(image.path("mimeType").asText()).isEqualTo("image/svg+xml");
    String svg =
        new String(Base64.getDecoder().decode(image.path("data").asText()), StandardCharsets.UTF_8);
    assertThat(svg).contains("<svg", "\"view_id\":\"" + expectedView + "\"");
  }
}

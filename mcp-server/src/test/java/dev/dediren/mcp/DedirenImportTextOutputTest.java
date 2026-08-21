package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.ImportEngine;
import dev.dediren.engine.LayoutEngine;
import dev.dediren.engine.RenderEngine;
import dev.dediren.engine.SemanticsEngine;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.SceneGraph;
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

/**
 * {@link DedirenTools#importSource}'s {@code output: "text"} lane. Mirrors {@link
 * DedirenImportToolTest}'s fake-{@link ImportEngine} style, plus fake {@link SemanticsEngine},
 * {@link LayoutEngine}, and {@link RenderEngine} standing in for the {@code generic-graph} /
 * {@code elk-layout} / {@code ascii} bundled engines this in-memory render lane calls through
 * {@code CoreCommands.renderImportedMain}. Real-engine text-output coverage lives in {@code
 * DedirenToolsEngineBackedTest} (cli), same split as the existing SVG lane.
 */
class DedirenImportTextOutputTest {

  private static Path renderPolicyFixture(String name) {
    return Path.of("..", "fixtures", "render-policy", name).toAbsolutePath().normalize();
  }

  private static String textOf(CallToolResult result, int index) {
    return ((TextContent) result.content().get(index)).text();
  }

  private static Engines engines(RenderEngine render) {
    return Engines.of(
        List.of(new FakeSemanticsEngine()),
        List.of(new FakeLayoutEngine()),
        List.of(render),
        List.of(),
        List.of(new StubMermaidImporter()));
  }

  @Test
  void textOutputReturnsTheEnvelopeAndTheRenderedDiagramAsASecondTextContent(@TempDir Path root)
      throws Exception {
    Files.copy(renderPolicyFixture("ascii-text.json"), root.resolve("policy.json"));
    DedirenTools tools =
        new DedirenTools(root, engines(new FakeAsciiRenderEngine("┌┐\n")), Map.of());

    CallToolResult result =
        tools.importSource(
            new CallToolRequest(
                "dediren_import",
                Map.of(
                    "content",
                    "flowchart TD\nA --> B\n",
                    "plugin",
                    "mermaid",
                    "output",
                    "text",
                    "render_policy",
                    "policy.json")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(result.content()).hasSize(2);
    JsonNode envelope = JsonSupport.objectMapper().readTree(textOf(result, 0));
    assertThat(envelope.path("status").asText()).isEqualTo("ok");
    assertThat(textOf(result, 1)).isEqualTo("┌┐\n");
  }

  @Test
  void textOutputWithNoTextArtifactIsAToolError(@TempDir Path root) throws Exception {
    Files.copy(renderPolicyFixture("ascii-text.json"), root.resolve("policy.json"));
    DedirenTools tools = new DedirenTools(root, engines(new FakeAsciiRenderEngine(null)), Map.of());

    CallToolResult result =
        tools.importSource(
            new CallToolRequest(
                "dediren_import",
                Map.of(
                    "content",
                    "flowchart TD\nA --> B\n",
                    "plugin",
                    "mermaid",
                    "output",
                    "text",
                    "render_policy",
                    "policy.json")));

    assertThat(result.isError()).isTrue();
    JsonNode envelope = JsonSupport.objectMapper().readTree(textOf(result, 0));
    assertThat(envelope.at("/diagnostics/0/message").asText()).contains("text artifact");
  }

  @Test
  void invalidOutputValueUsesTheImportErrorMessage(@TempDir Path root) {
    DedirenTools tools =
        new DedirenTools(root, engines(new FakeAsciiRenderEngine("┌┐\n")), Map.of());

    CallToolResult result =
        tools.importSource(
            new CallToolRequest(
                "dediren_import",
                Map.of(
                    "content", "flowchart TD\nA --> B\n",
                    "plugin", "mermaid",
                    "output", "bogus")));

    assertThat(result.isError()).isTrue();
    JsonNode envelope = JsonSupport.objectMapper().readTree(textOf(result, 0));
    assertThat(envelope.at("/diagnostics/0/message").asText())
        .isEqualTo("'output' must be 'data', 'svg', 'image', or 'text'");
  }

  @Test
  void buildRejectsTextOutputWithItsOwnUnchangedErrorMessage(@TempDir Path root) {
    DedirenTools tools =
        new DedirenTools(
            root, Engines.of(List.of(), List.of(), List.of(), List.of()), Map.of());

    CallToolResult result =
        tools.build(
            new CallToolRequest(
                "dediren_build",
                Map.of("source", "model.json", "out", "out", "output", "text")));

    assertThat(result.isError()).isTrue();
    JsonNode envelope = JsonSupport.objectMapper().readTree(textOf(result, 0));
    assertThat(envelope.at("/diagnostics/0/message").asText())
        .isEqualTo("'output' must be 'data', 'svg', or 'image'");
  }

  private static final class StubMermaidImporter implements ImportEngine {
    @Override
    public String id() {
      return "mermaid";
    }

    @Override
    public EngineResult<SourceDocument> importSource(String source) {
      return new EngineResult<>(
          new SourceDocument(
              "model.schema.v1", List.of(), List.of(), List.of(), List.of(), Map.of()),
          List.of());
    }
  }

  private static final class FakeSemanticsEngine implements SemanticsEngine {
    @Override
    public String id() {
      return "generic-graph";
    }

    @Override
    public EngineResult<dev.dediren.contracts.layout.SemanticValidationResult> validate(
        SourceDocument source, String profile) {
      throw new UnsupportedOperationException("not exercised by the text-output lane");
    }

    @Override
    public EngineResult<SceneGraph> projectScene(SourceDocument source, String view) {
      return new EngineResult<>(
          new SceneGraph(view, List.of(), List.of(), List.of(), List.of(), null), List.of());
    }

    @Override
    public EngineResult<RenderMetadata> projectRenderMetadata(
        SourceDocument source, String view) {
      return new EngineResult<>(
          new RenderMetadata("render-metadata.schema.v1", null, Map.of(), Map.of(), Map.of()),
          List.of());
    }
  }

  private static final class FakeLayoutEngine implements LayoutEngine {
    @Override
    public String id() {
      return "elk-layout";
    }

    @Override
    public SceneGraph parseRequest(byte[] input) {
      throw new UnsupportedOperationException("not exercised by the text-output lane");
    }

    @Override
    public EngineResult<LaidOutScene> layout(SceneGraph scene) {
      return new EngineResult<>(
          new LaidOutScene(scene.viewId(), List.of(), List.of(), List.of(), List.of()), List.of());
    }
  }

  /** Returns one {@code text} artifact with {@code content}, or no artifacts at all if null. */
  private static final class FakeAsciiRenderEngine implements RenderEngine {
    private final String content;

    FakeAsciiRenderEngine(String content) {
      this.content = content;
    }

    @Override
    public String id() {
      return "ascii";
    }

    @Override
    public EngineResult<RenderResult> render(
        LaidOutScene scene, JsonNode policy, RenderMetadata metadataOrNull) throws EngineException {
      List<RenderArtifact> artifacts =
          content == null
              ? List.of()
              : List.of(new RenderArtifact("text", content));
      return new EngineResult<>(
          new RenderResult(
              dev.dediren.contracts.ContractVersions.RENDER_RESULT_SCHEMA_VERSION, artifacts),
          List.of());
    }
  }
}

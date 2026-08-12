package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.ImportEngine;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class DedirenImportToolTest {
  @Test
  void importIsRegisteredWhenTheServerIsReadOnly(@TempDir Path root) {
    var server =
        DedirenMcpServer.create(
            root,
            Engines.of(List.of(), List.of(), List.of(), List.of(), List.of(new StubMermaidImporter())),
            Map.of(),
            true,
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream());
    try {
      assertThat(server.listTools().stream().map(tool -> tool.name()).toList())
          .contains("dediren_import");
    } finally {
      server.close();
    }
  }

  @Test
  void readOnlySafeImportUsesTheSameEnvelopeAndConfinesTheSource(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("diagram.mmd"), "flowchart TD\nA --> B\n");
    DedirenTools tools =
        new DedirenTools(
            root,
            Engines.of(List.of(), List.of(), List.of(), List.of(), List.of(new StubMermaidImporter())),
            Map.of());

    var result = tools.importSource(new CallToolRequest("dediren_import", Map.of("source", "diagram.mmd", "plugin", "mermaid")));
    var escaped = tools.importSource(new CallToolRequest("dediren_import", Map.of("source", "../diagram.mmd", "plugin", "mermaid")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    assertThat(JsonSupport.objectMapper().readTree(((TextContent) result.content().get(0)).text()).at("/data/model_schema_version").asText()).isEqualTo("model.schema.v1");
    assertThat(escaped.isError()).isTrue();
    assertThat(JsonSupport.objectMapper().readTree(((TextContent) escaped.content().get(0)).text()).at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
  }

  private static final class StubMermaidImporter implements ImportEngine {
    @Override
    public String id() {
      return "mermaid";
    }

    @Override
    public EngineResult<JsonNode> importSource(String source) {
      return new EngineResult<>(
          JsonSupport.objectMapper().readTree("{\"model_schema_version\":\"model.schema.v1\"}"),
          List.of());
    }
  }
}

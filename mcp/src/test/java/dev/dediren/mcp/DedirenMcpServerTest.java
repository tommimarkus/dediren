package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.engine.Engines;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DedirenMcpServerTest {

  /** Tool registration is engine-independent; mcp must not depend on cli to get a real registry. */
  private McpSyncServer serverIn(Path root, boolean readOnly) {
    return DedirenMcpServer.create(
        root,
        Engines.of(List.of(), List.of(), List.of(), List.of()),
        Map.of(),
        readOnly,
        new ByteArrayInputStream(new byte[0]),
        new ByteArrayOutputStream());
  }

  @Test
  void registersAllThreeToolsByDefault(@TempDir Path root) {
    McpSyncServer server = serverIn(root, false);
    try {
      List<String> names = server.listTools().stream().map(Tool::name).toList();

      assertThat(names)
          .containsExactlyInAnyOrder("dediren_validate", "dediren_build", "dediren_guide");
    } finally {
      server.close();
    }
  }

  @Test
  void readOnlyModeOmitsTheBuildTool(@TempDir Path root) {
    McpSyncServer server = serverIn(root, true);
    try {
      List<String> names = server.listTools().stream().map(Tool::name).toList();

      assertThat(names).containsExactlyInAnyOrder("dediren_validate", "dediren_guide");
      assertThat(names).doesNotContain("dediren_build");
    } finally {
      server.close();
    }
  }

  @Test
  void everyToolAdvertisesAnInputSchema(@TempDir Path root) {
    McpSyncServer server = serverIn(root, false);
    try {
      for (Tool tool : server.listTools()) {
        assertThat(tool.inputSchema())
            .as("tool %s must advertise an input schema", tool.name())
            .isNotNull();
        assertThat(tool.description())
            .as("tool %s must have a description", tool.name())
            .isNotBlank();
      }
    } finally {
      server.close();
    }
  }
}

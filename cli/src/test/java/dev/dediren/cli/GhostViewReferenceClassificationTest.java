package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Lane-agreement regression for ghost view references: a view node id absent from the source used
 * to pass every validation lane and crash {@code SceneProjection} post-validation, surfacing as
 * {@code DEDIREN_ENGINE_FAILED} (exit 3, PLUGIN_ERROR) — the one diagnostic the shipped agent guide
 * tells agents is not an input problem and must be reported rather than retried with modified JSON,
 * misdirecting them away from a one-character model fix. The router's base validation now
 * classifies it (like the other three ghost classes) as a structured structural input error: error
 * envelope on stdout with its own code, exit 2.
 */
class GhostViewReferenceClassificationTest {
  @TempDir Path temp;

  private static final String GHOST_VIEW_NODE_SOURCE =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [
          { "id": "client", "type": "generic.actor", "label": "Client", "properties": {} }
        ],
        "relationships": [],
        "plugins": {
          "generic-graph": {
            "views": [
              {
                "id": "main",
                "label": "Main",
                "nodes": ["client", "clientt"],
                "relationships": []
              }
            ]
          }
        }
      }
      """;

  @Test
  void buildWithGhostViewNodeIdIsAStructuredInputErrorNotEngineFailed() throws Exception {
    Path source = temp.resolve("ghost-view-node.json");
    Files.writeString(source, GHOST_VIEW_NODE_SOURCE);

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              source.toString(),
              "--out",
              temp.resolve("out").toString(),
              "--render-policy",
              workspaceRoot().resolve("fixtures/render-policy/default-svg.json").toString()
            },
            "");

    JsonNode buildResult = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(buildResult.at("/status").asText()).isEqualTo("error");
    assertThat(buildResult.at("/views/0/view_id").asText()).isEqualTo("main");
    assertThat(buildResult.at("/views/0/status").asText()).isEqualTo("error");
    assertThat(buildResult.at("/views/0/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_GENERIC_GRAPH_VIEW_NODE_UNKNOWN");
    assertThat(buildResult.at("/views/0/diagnostics/0/message").asText()).contains("clientt");
    assertThat(result.stdout()).doesNotContain("DEDIREN_ENGINE_FAILED");
  }

  private static Path workspaceRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

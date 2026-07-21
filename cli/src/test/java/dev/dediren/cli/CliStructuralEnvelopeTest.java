package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Structural failures must be decidable from stdout JSON alone, like every other outcome: an error
 * envelope on stdout and exit 2 — never a bare stderr line with empty stdout. Guards the closure of
 * the last non-enveloped failure lane (missing {@code plugins.generic-graph}, unknown view,
 * unsupported {@code project} target).
 */
class CliStructuralEnvelopeTest {
  @TempDir Path temp;

  private static final String PLUGINLESS_SOURCE =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [],
        "relationships": [],
        "plugins": {}
      }
      """;

  @Test
  void semanticValidateWithoutGenericGraphBlockEmitsErrorEnvelope() throws Exception {
    Path source = temp.resolve("model.json");
    Files.writeString(source, PLUGINLESS_SOURCE);

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "validate",
              "--plugin",
              "generic-graph",
              "--profile",
              "archimate",
              "--input",
              source.toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.get("status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_GENERIC_GRAPH_PLUGIN_REQUIRED");
  }

  @Test
  void projectWithUnknownViewEmitsErrorEnvelope() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "project",
              "--target",
              "layout-request",
              "--plugin",
              "generic-graph",
              "--view",
              "no-such-view",
              "--input",
              workspaceRoot().resolve("fixtures/source/valid-basic.json").toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.get("status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_GENERIC_GRAPH_VIEW_UNKNOWN");
    assertThat(envelope.at("/diagnostics/0/message").asText()).contains("no-such-view");
  }

  @Test
  void projectWithUnsupportedTargetEmitsErrorEnvelope() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "project",
              "--target",
              "not-a-target",
              "--plugin",
              "generic-graph",
              "--view",
              "main",
              "--input",
              workspaceRoot().resolve("fixtures/source/valid-basic.json").toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.get("status").asText()).isEqualTo("error");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_COMMAND_TARGET_UNSUPPORTED");
    assertThat(envelope.at("/diagnostics/0/message").asText()).contains("not-a-target");
  }

  private static Path workspaceRoot() {
    return Path.of("..").toAbsolutePath().normalize();
  }
}

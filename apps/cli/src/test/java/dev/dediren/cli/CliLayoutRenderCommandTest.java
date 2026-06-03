package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliLayoutRenderCommandTest {
    @TempDir
    Path temp;

    @Test
    void validateLayoutReportsQualityFromFile() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "validate-layout",
                "--input",
                workspaceRoot().resolve("fixtures/layout-result/basic.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(envelope.at("/data/status").asText()).isEqualTo("ok");
        assertThat(envelope.at("/data/overlap_count").asInt()).isZero();
    }

    @Test
    void validateLayoutRejectsEmptyRoutesAndEndpointMisses() throws Exception {
        Path layout = temp.resolve("invalid-route-layout.json");
        Files.writeString(layout, """
                {
                  "layout_result_schema_version": "layout-result.schema.v1",
                  "view_id": "main",
                  "nodes": [
                    { "id": "source", "source_id": "source", "projection_id": "source", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "Source" },
                    { "id": "target", "source_id": "target", "projection_id": "target", "x": 300.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "Target" }
                  ],
                  "edges": [
                    { "id": "empty", "source": "source", "target": "target", "source_id": "empty", "projection_id": "empty", "routing_hints": [], "points": [], "label": "empty" },
                    { "id": "misses-target", "source": "source", "target": "target", "source_id": "misses-target", "projection_id": "misses-target", "routing_hints": [], "points": [{"x": 100.0, "y": 40.0}, {"x": 250.0, "y": 40.0}], "label": "misses target" }
                  ],
                  "groups": [],
                  "warnings": []
                }
                """);

        CliResult result = Main.executeForTesting(new String[]{
                "validate-layout",
                "--input",
                layout.toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText())
                .isEqualTo("DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY");
        assertThat(envelope.at("/diagnostics/1/code").asText())
                .isEqualTo("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
    }

    @Test
    void layoutMissingInputFileReturnsJsonEnvelope() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "layout",
                "--plugin",
                "elk-layout",
                "--input",
                temp.resolve("missing-layout-request.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    }

    @Test
    void renderMissingPolicyFileReturnsJsonEnvelope() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "render",
                "--plugin",
                "svg-render",
                "--policy",
                temp.resolve("missing-policy.json").toString(),
                "--input",
                workspaceRoot().resolve("fixtures/layout-result/basic.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    }

    private static Path workspaceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("schemas/model.schema.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from user.dir");
    }
}

package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class CliValidateTest {
    @TempDir
    Path temp;

    @Test
    void topLevelHelpPointsToAgentUsageGuide() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{"--help"}, "");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("Agent authoring guide: docs/agent-usage.md");
        assertThat(result.stdout()).contains("source JSON shape");
    }

    @Test
    void validateAcceptsValidSourceFromFile() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "validate",
                "--input",
                workspaceRoot().resolve("fixtures/source/valid-basic.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(envelope.get("status").asText()).isEqualTo("ok");
        assertThat(envelope.at("/data/model_schema_version").asText()).isEqualTo("model.schema.v1");
    }

    @Test
    void validateRejectsAuthoredGeometry() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "validate",
                "--input",
                workspaceRoot().resolve("fixtures/source/invalid-absolute-geometry.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_SCHEMA_INVALID");
    }

    @Test
    void validateRejectsDuplicateIds() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "validate",
                "--input",
                workspaceRoot().resolve("fixtures/source/invalid-duplicate-id.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_DUPLICATE_ID");
    }

    @Test
    void validateRejectsDanglingRelationshipEndpoint() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "validate",
                "--input",
                workspaceRoot().resolve("fixtures/source/invalid-dangling-relationship.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_DANGLING_ENDPOINT");
    }

    @Test
    void validateAssemblesSourceFragmentsFromFileInput() throws Exception {
        Path fragments = temp.resolve("fragments");
        Files.createDirectories(fragments);
        Files.writeString(temp.resolve("model.json"), """
                {
                  "model_schema_version": "model.schema.v1",
                  "fragments": ["fragments/application.json", "fragments/view.json"],
                  "nodes": [],
                  "relationships": [],
                  "plugins": {
                    "generic-graph": { "views": [] }
                  }
                }
                """);
        Files.writeString(fragments.resolve("application.json"), """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "client", "type": "BusinessActor", "label": "Client", "properties": {} },
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                  ],
                  "relationships": [
                    {
                      "id": "client-calls-api",
                      "type": "Association",
                      "source": "client",
                      "target": "api",
                      "label": "calls",
                      "properties": {}
                    }
                  ],
                  "plugins": {
                    "generic-graph": { "views": [] }
                  }
                }
                """);
        Files.writeString(fragments.resolve("view.json"), """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [],
                  "relationships": [],
                  "plugins": {
                    "generic-graph": {
                      "views": [
                        {
                          "id": "main",
                          "label": "Main",
                          "nodes": ["client", "api"],
                          "relationships": ["client-calls-api"]
                        }
                      ]
                    }
                  }
                }
                """);

        CliResult result = Main.executeForTesting(new String[]{
                "validate",
                "--input",
                temp.resolve("model.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isZero();
        assertThat(envelope.at("/data/node_count").asInt()).isEqualTo(2);
        assertThat(envelope.at("/data/relationship_count").asInt()).isEqualTo(1);
    }

    @Test
    void validateRejectsDuplicateIdsAfterFragmentAssembly() throws Exception {
        Path fragments = temp.resolve("fragments");
        Files.createDirectories(fragments);
        Files.writeString(temp.resolve("model.json"), """
                {
                  "model_schema_version": "model.schema.v1",
                  "fragments": ["fragments/one.json", "fragments/two.json"],
                  "nodes": [],
                  "relationships": [],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """);
        for (String name : new String[]{"one.json", "two.json"}) {
            Files.writeString(fragments.resolve(name), """
                    {
                      "model_schema_version": "model.schema.v1",
                      "nodes": [
                        { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                      ],
                      "relationships": [],
                      "plugins": { "generic-graph": { "views": [] } }
                    }
                    """);
        }

        CliResult result = Main.executeForTesting(new String[]{
                "validate",
                "--input",
                temp.resolve("model.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_DUPLICATE_ID");
    }

    @Test
    void validateRejectsFragmentsFromStdinWithoutBaseDirectory() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{"validate"}, """
                {
                  "model_schema_version": "model.schema.v1",
                  "fragments": ["fragments/application.json"],
                  "nodes": [],
                  "relationships": [],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """);

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_FRAGMENT_BASE_DIR_REQUIRED");
    }

    @Test
    void validateMissingInputFileReturnsJsonEnvelope() throws Exception {
        CliResult result = Main.executeForTesting(new String[]{
                "validate",
                "--input",
                temp.resolve("missing.json").toString()
        }, "");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    }

    @Test
    void validateWithPluginButWithoutProfileReturnsProfileRequiredEnvelope() throws Exception {
        CliResult result = Main.executeForTesting(
                new String[]{"validate", "--plugin", "archimate-oef"}, "{}");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_VALIDATE_PROFILE_REQUIRED");
    }

    @Test
    void validateWithProfileButWithoutPluginReturnsPluginRequiredEnvelope() throws Exception {
        CliResult result = Main.executeForTesting(
                new String[]{"validate", "--profile", "archimate"}, "{}");

        JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_VALIDATE_PLUGIN_REQUIRED");
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

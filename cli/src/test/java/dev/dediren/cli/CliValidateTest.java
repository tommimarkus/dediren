package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class CliValidateTest {
  @TempDir Path temp;

  @Test
  void topLevelHelpPointsToAgentUsageGuide() throws Exception {
    CliResult result = Main.executeForTesting(new String[] {"--help"}, "");

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("Agent authoring guide: docs/agent-usage.md");
    assertThat(result.stdout()).contains("source JSON shape");
  }

  @Test
  void validateAcceptsValidSourceFromFile() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "validate",
              "--input",
              workspaceRoot().resolve("fixtures/source/valid-basic.json").toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isZero();
    assertThat(envelope.get("status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/model_schema_version").asText()).isEqualTo("model.schema.v1");
  }

  @Test
  void validateAcceptsCurrentRenderPolicyDocument() throws Exception {
    // The schema-migration design's "Known asymmetry": policies used to be gated only at build
    // time. validate now dispatches on the document's version field, so a policy gets the same
    // fast feedback as a source model.
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "validate",
              "--input",
              workspaceRoot().resolve("fixtures/render-policy/default-svg.json").toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isZero();
    assertThat(envelope.get("status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/render_policy_schema_version").asText())
        .isEqualTo("render-policy.schema.v3");
  }

  @Test
  void validateAcceptsCurrentOefExportPolicyDocument() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "validate",
              "--input",
              workspaceRoot().resolve("fixtures/export-policy/default-oef.json").toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isZero();
    assertThat(envelope.get("status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/oef_export_policy_schema_version").asText())
        .isEqualTo("oef-export-policy.schema.v1");
  }

  @Test
  void validateFlagsStaleRenderPolicyAsOutdated() throws Exception {
    Path policy = temp.resolve("stale-policy.json");
    Files.writeString(policy, "{\"render_policy_schema_version\":\"render-policy.schema.v2\"}");

    CliResult result =
        Main.executeForTesting(new String[] {"validate", "--input", policy.toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_SCHEMA_VERSION_OUTDATED");
    // The machine-readable migration path rides the diagnostic (schema-migration amendment
    // 2026-07-21): exact JSON-Pointer edits instead of prose transcription; the agent applies
    // them, dediren never does.
    assertThat(envelope.at("/diagnostics/0/migration/from").asText())
        .isEqualTo("render-policy.schema.v2");
    assertThat(envelope.at("/diagnostics/0/migration/to").asText())
        .isEqualTo("render-policy.schema.v3");
    assertThat(envelope.at("/diagnostics/0/migration/operations/0/op").asText())
        .isEqualTo("remove_key");
    assertThat(envelope.at("/diagnostics/0/migration/operations/0/pointer").asText())
        .isEqualTo("/interactive");
    assertThat(envelope.at("/diagnostics/0/migration/operations/2/op").asText())
        .isEqualTo("set_version");
  }

  @Test
  void validateRecognizesLegacyRenderPolicyVersionField() throws Exception {
    // The renamed-field wrinkle: the oldest render-policy files carry
    // svg_render_policy_schema_version and no current field at all; they must classify as
    // outdated (with upgrade steps), never as unknown.
    Path policy = temp.resolve("legacy-policy.json");
    Files.writeString(
        policy, "{\"svg_render_policy_schema_version\":\"svg-render-policy.schema.v1\"}");

    CliResult result =
        Main.executeForTesting(new String[] {"validate", "--input", policy.toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_SCHEMA_VERSION_OUTDATED");
  }

  @Test
  void validateFlagsUnknownPolicyVersion() throws Exception {
    Path policy = temp.resolve("unknown-policy.json");
    Files.writeString(policy, "{\"render_policy_schema_version\":\"render-policy.schema.v9\"}");

    CliResult result =
        Main.executeForTesting(new String[] {"validate", "--input", policy.toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
  }

  @Test
  void validateRejectsSchemaInvalidCurrentVersionPolicy() throws Exception {
    // Current version clears the gate; the missing required page/margin blocks must then fail
    // JSON Schema validation against the policy's own schema, not the model schema.
    Path policy = temp.resolve("invalid-policy.json");
    Files.writeString(policy, "{\"render_policy_schema_version\":\"render-policy.schema.v3\"}");

    CliResult result =
        Main.executeForTesting(new String[] {"validate", "--input", policy.toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_SCHEMA_INVALID");
  }

  @Test
  void validateRejectsAuthoredGeometry() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "validate",
              "--input",
              workspaceRoot().resolve("fixtures/source/invalid-absolute-geometry.json").toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_SCHEMA_INVALID");
  }

  @Test
  void validateRejectsDuplicateIds() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "validate",
              "--input",
              workspaceRoot().resolve("fixtures/source/invalid-duplicate-id.json").toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_DUPLICATE_ID");
  }

  @Test
  void validateRejectsDanglingRelationshipEndpoint() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {
              "validate",
              "--input",
              workspaceRoot()
                  .resolve("fixtures/source/invalid-dangling-relationship.json")
                  .toString()
            },
            "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_DANGLING_ENDPOINT");
  }

  @Test
  void validateAssemblesSourceFragmentsFromFileInput() throws Exception {
    Path fragments = temp.resolve("fragments");
    Files.createDirectories(fragments);
    Files.writeString(
        temp.resolve("model.json"),
        """
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
    Files.writeString(
        fragments.resolve("application.json"),
        """
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
    Files.writeString(
        fragments.resolve("view.json"),
        """
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

    CliResult result =
        Main.executeForTesting(
            new String[] {"validate", "--input", temp.resolve("model.json").toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isZero();
    assertThat(envelope.at("/data/node_count").asInt()).isEqualTo(2);
    assertThat(envelope.at("/data/relationship_count").asInt()).isEqualTo(1);
  }

  @Test
  void validateRejectsDuplicateIdsAfterFragmentAssembly() throws Exception {
    Path fragments = temp.resolve("fragments");
    Files.createDirectories(fragments);
    Files.writeString(
        temp.resolve("model.json"),
        """
                {
                  "model_schema_version": "model.schema.v1",
                  "fragments": ["fragments/one.json", "fragments/two.json"],
                  "nodes": [],
                  "relationships": [],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """);
    for (String name : new String[] {"one.json", "two.json"}) {
      Files.writeString(
          fragments.resolve(name),
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
    }

    CliResult result =
        Main.executeForTesting(
            new String[] {"validate", "--input", temp.resolve("model.json").toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_DUPLICATE_ID");
  }

  @Test
  void validateRejectsFragmentsFromStdinWithoutBaseDirectory() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {"validate"},
            """
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
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_FRAGMENT_BASE_DIR_REQUIRED");
  }

  @Test
  void validateRejectsAbsoluteFragmentPath() throws Exception {
    Files.writeString(
        temp.resolve("model.json"),
        """
                {
                  "model_schema_version": "model.schema.v1",
                  "fragments": ["%s"],
                  "nodes": [],
                  "relationships": [],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """
            .formatted(temp.resolve("absolute-fragment.json")));

    CliResult result =
        Main.executeForTesting(
            new String[] {"validate", "--input", temp.resolve("model.json").toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_FRAGMENT_PATH_UNSUPPORTED");
    assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo("$.fragments[0]");
  }

  @Test
  void validateReportsUnreadableFragmentWithReadFailedEnvelope() throws Exception {
    Files.writeString(
        temp.resolve("model.json"),
        """
                {
                  "model_schema_version": "model.schema.v1",
                  "fragments": ["fragments/missing.json"],
                  "nodes": [],
                  "relationships": [],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """);

    CliResult result =
        Main.executeForTesting(
            new String[] {"validate", "--input", temp.resolve("model.json").toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_FRAGMENT_READ_FAILED");
    assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo("$.fragments[0]");
  }

  @Test
  void validateRejectsFragmentDeclaringNestedFragments() throws Exception {
    Path fragments = temp.resolve("fragments");
    Files.createDirectories(fragments);
    Files.writeString(
        temp.resolve("model.json"),
        """
                {
                  "model_schema_version": "model.schema.v1",
                  "fragments": ["fragments/child.json"],
                  "nodes": [],
                  "relationships": [],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """);
    Files.writeString(
        fragments.resolve("child.json"),
        """
                {
                  "model_schema_version": "model.schema.v1",
                  "fragments": ["grandchild.json"],
                  "nodes": [],
                  "relationships": [],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """);

    CliResult result =
        Main.executeForTesting(
            new String[] {"validate", "--input", temp.resolve("model.json").toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_FRAGMENT_NESTED_UNSUPPORTED");
    assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo("$.fragments[0]");
  }

  @Test
  void validateMissingInputFileReturnsJsonEnvelope() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {"validate", "--input", temp.resolve("missing.json").toString()}, "");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }

  @Test
  void validateWithPluginButWithoutProfileReturnsProfileRequiredEnvelope() throws Exception {
    CliResult result =
        Main.executeForTesting(new String[] {"validate", "--plugin", "archimate-oef"}, "{}");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_VALIDATE_PROFILE_REQUIRED");
  }

  @Test
  void validateWithProfileButWithoutPluginReturnsPluginRequiredEnvelope() throws Exception {
    CliResult result =
        Main.executeForTesting(new String[] {"validate", "--profile", "archimate"}, "{}");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_VALIDATE_PLUGIN_REQUIRED");
  }

  private static Path workspaceRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

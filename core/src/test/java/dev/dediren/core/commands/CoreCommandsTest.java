package dev.dediren.core.commands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.plugins.PluginRegistry;
import dev.dediren.core.plugins.PluginRunOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreCommandsTest {
  @TempDir Path temp;

  @Test
  void semanticValidateCommandRejectsInvalidSourceBeforePluginLookup() throws Exception {
    String input =
        """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                  ],
                  "relationships": [
                    {
                      "id": "missing-source",
                      "type": "Association",
                      "source": "missing",
                      "target": "api",
                      "label": "invalid",
                      "properties": {}
                    }
                  ],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """;

    PluginRunOutcome outcome =
        CoreCommands.semanticValidateCommand("missing-plugin", "archimate", input);

    assertThat(outcome.exitCode()).isEqualTo(2);
    assertThat(outcome.stdout()).contains("DEDIREN_DANGLING_ENDPOINT");
  }

  @Test
  void validateLayoutCommandSurfacesQualityWarningAtEnvelopeLevel() throws Exception {
    String layout =
        """
                {
                  "layout_result_schema_version": "layout-result.schema.v1",
                  "view_id": "main",
                  "nodes": [
                    { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A" },
                    { "id": "b", "source_id": "b", "projection_id": "b", "x": 50.0, "y": 20.0, "width": 100.0, "height": 80.0, "label": "B" }
                  ],
                  "edges": [],
                  "groups": [],
                  "warnings": []
                }
                """;

    var result = CoreCommands.validateLayoutCommand(layout);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(result.envelope().diagnostics())
        .extracting(Diagnostic::code)
        .containsOnly("DEDIREN_LAYOUT_QUALITY_WARNING");
    assertThat(result.envelope().data().at("/status").asText()).isEqualTo("warning");
    assertThat(result.envelope().data().at("/overlap_count").asInt()).isEqualTo(1);
  }

  @Test
  void validateLayoutCommandKeepsOkEnvelopeForCleanLayout() throws Exception {
    String layout =
        """
                {
                  "layout_result_schema_version": "layout-result.schema.v1",
                  "view_id": "main",
                  "nodes": [
                    { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A" }
                  ],
                  "edges": [],
                  "groups": [],
                  "warnings": []
                }
                """;

    var result = CoreCommands.validateLayoutCommand(layout);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(result.envelope().diagnostics()).isEmpty();
  }

  @Test
  void layoutCommandRunsThroughPluginRuntimeWithCandidateEnvironment() throws Exception {
    writeManifest(
        temp,
        "runtime-testbed",
        testbedExecutable().toString(),
        List.of("layout"),
        List.of("DEDIREN_TEST_PLUGIN_CAPABILITIES"));
    String request =
        """
                {
                  "layout_request_schema_version": "layout-request.schema.v1",
                  "view_id": "main",
                  "nodes": [],
                  "edges": [],
                  "groups": [],
                  "constraints": []
                }
                """;

    PluginRunOutcome outcome =
        CoreCommands.layoutCommand(
            new LayoutCommandInput(
                "runtime-testbed",
                request,
                PluginRegistry.fromDirs(List.of(temp)),
                Map.of("DEDIREN_TEST_PLUGIN_CAPABILITIES", "layout")));

    assertThat(outcome.exitCode()).isZero();
    assertThat(outcome.stdout()).contains("\"layout_result_schema_version\"");
  }

  private Path testbedExecutable() throws IOException {
    Path script = temp.resolve("runtime-testbed.sh");
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath = System.getProperty("java.class.path");
    Files.writeString(
        script,
        """
                #!/bin/sh
                exec "%s" -cp "%s" dev.dediren.testbeds.pluginruntime.Main "$@"
                """
            .formatted(java, classpath));
    script.toFile().setExecutable(true);
    return script;
  }

  private static void writeManifest(
      Path dir, String id, String executable, List<String> capabilities, List<String> allowedEnv)
      throws IOException {
    var manifest = JsonSupport.objectMapper().createObjectNode();
    manifest.put("plugin_manifest_schema_version", "plugin-manifest.schema.v1");
    manifest.put("id", id);
    manifest.put("version", "0.1.0");
    manifest.put("executable", executable);
    var capabilityArray = manifest.putArray("capabilities");
    capabilities.forEach(capabilityArray::add);
    var envArray = manifest.putArray("allowed_env");
    allowedEnv.forEach(envArray::add);
    Files.writeString(
        dir.resolve(id + ".manifest.json"),
        JsonSupport.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
  }
}

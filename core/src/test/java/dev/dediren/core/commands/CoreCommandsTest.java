package dev.dediren.core.commands;

import static org.assertj.core.api.Assertions.assertThat;

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

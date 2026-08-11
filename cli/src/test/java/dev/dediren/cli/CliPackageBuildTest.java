package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.mcp.DedirenTools;
import dev.dediren.testsupport.TestSupport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end coverage for {@code dediren build --package} through the real wired engines: a whole
 * package builds each view to its declared path and returns one command envelope wrapping a
 * package-build-result. Also pins the CLI-lane guards: {@code --package} is mutually exclusive with
 * the single-model options, and a bare directory reads its {@code package.json}.
 */
class CliPackageBuildTest {

  @Test
  void mcpPackageInputsStayPackageRelativeAndOutputsAreWorkspaceIsolated(@TempDir Path temp)
      throws Exception {
    Path product = TestSupport.workspaceRoot();
    Path packageDir = Files.createDirectories(temp.resolve("package"));
    Files.copy(
        product.resolve("fixtures/source/valid-pipeline-archimate.json"),
        packageDir.resolve("model.json"));
    Files.copy(
        product.resolve("fixtures/render-policy/archimate-svg.json"),
        packageDir.resolve("render-policy.json"));
    Files.writeString(
        packageDir.resolve("package.json"),
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ]
        }
        """);
    DedirenTools tools = new DedirenTools(temp, EngineWiring.defaults(), Map.of());
    var opened = tools.workspaceOpen(new CallToolRequest("dediren_workspace_open", Map.of()));
    JsonNode workspace =
        JsonSupport.objectMapper()
            .readTree(((TextContent) opened.content().getFirst()).text())
            .path("data");

    var result =
        tools.build(
            new CallToolRequest(
                "dediren_build",
                Map.of(
                    "workspace_id",
                    workspace.path("workspace_id").asText(),
                    "package",
                    "package/package.json")));

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    Path workspaceRoot = temp.resolve(workspace.path("workspace_path").asText());
    assertThat(workspaceRoot.resolve("generated/svg/main.svg")).exists();
    assertThat(packageDir.resolve("generated")).doesNotExist();
  }

  @Test
  void buildsAPackageToDeclaredPathsThroughRealEngines(@TempDir Path temp) throws Exception {
    Path root = TestSupport.workspaceRoot();
    Files.copy(
        root.resolve("fixtures/source/valid-pipeline-archimate.json"), temp.resolve("model.json"));
    Files.copy(
        root.resolve("fixtures/render-policy/archimate-svg.json"),
        temp.resolve("render-policy.json"));
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "presentation": { "title": "Cooperation Overview" },
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ]
        }
        """;
    Files.writeString(temp.resolve("package.json"), pkg);

    CliResult result =
        Main.executeForTesting(
            new String[] {"build", "--package", temp.resolve("package.json").toString()}, "");

    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/package_build_result_schema_version").asText())
        .isEqualTo("package-build-result.schema.v1");
    assertThat(envelope.at("/data/views/0/artifacts/diagram").asText())
        .isEqualTo("generated/svg/main.svg");
    // Package-built diagrams carry the same provenance stamp the single-model build lane injects,
    // so `dediren verify`/`status` classify them instead of reporting them unstamped.
    assertThat(Files.readString(temp.resolve("generated/svg/main.svg")))
        .contains("<svg")
        .contains("dediren-provenance");
  }

  @Test
  void artifactWriteFailureExitsWithInputError(@TempDir Path temp) throws Exception {
    Path root = TestSupport.workspaceRoot();
    Files.copy(
        root.resolve("fixtures/source/valid-pipeline-archimate.json"), temp.resolve("model.json"));
    Files.copy(
        root.resolve("fixtures/render-policy/archimate-svg.json"),
        temp.resolve("render-policy.json"));
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ]
        }
        """;
    Files.writeString(temp.resolve("package.json"), pkg);
    // A directory squatting on the declared output path makes the artifact write fail; the package
    // lane maps that as caller-fixable input (exit 2), mirroring the single-model build lane.
    Files.createDirectories(temp.resolve("generated/svg/main.svg"));

    CliResult result =
        Main.executeForTesting(
            new String[] {"build", "--package", temp.resolve("package.json").toString()}, "");

    assertThat(result.exitCode()).describedAs(result.stdout()).isEqualTo(2);
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/status").asText()).isEqualTo("error");
    assertThat(result.stdout()).contains("DEDIREN_COMMAND_IO_FAILED");
  }

  @Test
  void packageOptionRejectsSingleModelOptions(@TempDir Path temp) throws Exception {
    Files.writeString(temp.resolve("package.json"), "{}");

    CliResult result =
        Main.executeForTesting(
            new String[] {
              "build",
              "--package",
              temp.resolve("package.json").toString(),
              "--input",
              "model.json",
              "--out",
              temp.toString()
            },
            "");

    assertThat(result.exitCode()).isEqualTo(2);
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/status").asText()).isEqualTo("error");
    assertThat(result.stdout()).contains("cannot be combined");
  }

  @Test
  void bareDirectoryReadsItsPackageJson(@TempDir Path temp) throws Exception {
    Path root = TestSupport.workspaceRoot();
    Files.copy(
        root.resolve("fixtures/source/valid-pipeline-archimate.json"), temp.resolve("model.json"));
    Files.copy(
        root.resolve("fixtures/render-policy/archimate-svg.json"),
        temp.resolve("render-policy.json"));
    String pkg =
        """
        {
          "package_schema_version": "package.schema.v1",
          "models": [ { "id": "arch", "source": "model.json" } ],
          "views": [
            { "id": "main", "model": "arch", "render_policy": "render-policy.json",
              "outputs": { "diagram": "generated/svg/main.svg" } }
          ]
        }
        """;
    Files.writeString(temp.resolve("package.json"), pkg);

    CliResult result = Main.executeForTesting(new String[] {"build", temp.toString()}, "");

    assertThat(result.exitCode()).describedAs(result.stdout()).isZero();
    assertThat(Files.exists(temp.resolve("generated/svg/main.svg"))).isTrue();
  }
}

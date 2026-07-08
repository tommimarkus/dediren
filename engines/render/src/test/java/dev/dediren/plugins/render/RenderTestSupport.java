package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared render-invocation plumbing for the render-plugin tests that drive {@code Main} through its
 * JSON stdin/stdout boundary. Keeps the audit, determinism, injection, and degenerate suites from
 * each re-implementing the same "build input, run, assert ok, pull the SVG" dance.
 */
final class RenderTestSupport {
  private RenderTestSupport() {}

  /** Runs the render command on {@code input} and returns the first artifact's SVG content. */
  static String render(JsonNode input) throws Exception {
    PluginResult result =
        Main.executeForTesting(
            new String[] {"render"}, JsonSupport.objectMapper().writeValueAsString(input));
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).describedAs(result.stderr()).isZero();
    assertThat(envelope.at("/status").asText()).describedAs(result.stdout()).isEqualTo("ok");
    return envelope.at("/data/artifacts/0/content").asText();
  }

  /** Builds a render input from checked-in fixtures and returns the rendered SVG. */
  static String renderFixtures(String layoutPath, String policyPath, String metadataPath)
      throws Exception {
    return render(fixtureInput(layoutPath, policyPath, metadataPath));
  }

  /** Assembles a render input node from checked-in fixtures without rendering it. */
  static ObjectNode fixtureInput(String layoutPath, String policyPath, String metadataPath)
      throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", fixtureJson(layoutPath));
    input.set("policy", fixtureJson(policyPath));
    if (metadataPath != null) {
      input.set("render_metadata", fixtureJson(metadataPath));
    }
    return input;
  }

  static JsonNode fixtureJson(String path) throws Exception {
    return JsonSupport.objectMapper().readTree(Files.readString(workspaceRoot().resolve(path)));
  }

  static Path workspaceRoot() {
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

package dev.dediren.plugins.elklayout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class ElkLayoutRenderArtifacts {
  private ElkLayoutRenderArtifacts() {}

  static void write(LayoutResult result) {
    if (!Boolean.getBoolean("dediren.elk.render-artifacts")) {
      return; // debug-only; default test runs stay hermetic and do not boot render
    }
    try {
      writeSvg(testMethodName(), result);
    } catch (Exception error) {
      throw new AssertionError("failed to write ELK layout SVG render artifact", error);
    }
  }

  static void write(JsonNode layoutResult) {
    write(JsonSupport.objectMapper().convertValue(layoutResult, LayoutResult.class));
  }

  private static void writeSvg(String testName, LayoutResult result) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", JsonSupport.objectMapper().valueToTree(result));
    input.set(
        "policy",
        JsonSupport.objectMapper()
            .readTree(workspaceRoot().resolve("fixtures/render-policy/default-svg.json").toFile()));

    dev.dediren.plugins.render.PluginResult renderResult =
        dev.dediren.plugins.render.Main.executeForTesting(
            new String[] {"render"}, JsonSupport.objectMapper().writeValueAsString(input));
    JsonNode envelope = JsonSupport.objectMapper().readTree(renderResult.stdout());
    if (renderResult.exitCode() != 0 || !"ok".equals(envelope.path("status").asText())) {
      throw new AssertionError(
          "SVG render failed for "
              + testName
              + ", stdout="
              + renderResult.stdout()
              + ", stderr="
              + renderResult.stderr());
    }

    String content = envelope.path("data").path("content").asText();
    Path output =
        workspaceRoot()
            .resolve(".test-output/renders/elk-layout")
            .resolve(safeFileName(testName) + ".svg");
    Files.createDirectories(output.getParent());
    Files.writeString(output, content, StandardCharsets.UTF_8);
  }

  private static Path workspaceRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("fixtures/render-policy/default-svg.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("could not locate dediren workspace root");
  }

  private static String testMethodName() {
    return StackWalker.getInstance()
        .walk(
            frames ->
                frames
                    .map(StackWalker.StackFrame::getMethodName)
                    .filter(
                        name ->
                            !name.equals("write")
                                && !name.equals("writeSvg")
                                && !name.equals("testMethodName"))
                    .findFirst()
                    .orElse("unknown-test"));
  }

  private static String safeFileName(String name) {
    return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }
}

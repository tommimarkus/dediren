package dev.dediren.plugins.elklayout;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class ElkLayoutRenderArtifacts {
  // Wipe the output directory once per JVM run so it holds only this run's renders, never a
  // cumulative pile of artifacts from renamed or deleted tests.
  private static final AtomicBoolean CLEANED = new AtomicBoolean();

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

    String content = svgArtifactContent(envelope);
    if (content.isBlank()) {
      throw new AssertionError(
          "SVG render produced no svg artifact content for "
              + testName
              + ", stdout="
              + renderResult.stdout());
    }
    Path outputDir = workspaceRoot().resolve(".test-output/renders/elk-layout");
    cleanOnce(outputDir);
    Path output = outputDir.resolve(safeFileName(testName) + ".svg");
    Files.createDirectories(output.getParent());
    Files.writeString(output, content, StandardCharsets.UTF_8);
  }

  // Render returns data.artifacts[] (svg, plus png when raster policy is set); pick the svg one.
  private static String svgArtifactContent(JsonNode envelope) {
    for (JsonNode artifact : envelope.path("data").path("artifacts")) {
      if ("svg".equals(artifact.path("artifact_kind").asText())) {
        return artifact.path("content").asText();
      }
    }
    return "";
  }

  private static void cleanOnce(Path dir) throws IOException {
    if (!CLEANED.compareAndSet(false, true) || !Files.isDirectory(dir)) {
      return;
    }
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path visited, IOException failure)
              throws IOException {
            Files.delete(visited);
            return FileVisitResult.CONTINUE;
          }
        });
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

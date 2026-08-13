package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.cli.EngineWiring;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DedirenToolsRasterFallbackTest {
  private static final String MERMAID = "flowchart TD\nstart[Start] --> finish[Finish]\n";

  @Test
  void explicitPngFallsBackToTheExactJsonEnvelopeWhenConverterIsMissingOrFails(
      @TempDir Path root) throws Exception {
    ResvgRasterizer missing =
        ResvgRasterizer.resolve("missing-resvg", Map.of("PATH", root.toString()));
    Path failingExecutable = executable(root.resolve("failing-resvg"), "#!/bin/sh\nexit 3\n");
    ResvgRasterizer failing = ResvgRasterizer.resolve(failingExecutable.toString(), Map.of());
    DedirenTools baselineTools = new DedirenTools(root, EngineWiring.defaults(), Map.of(), missing);
    CallToolResult baseline = baselineTools.importSource(dataRequest());

    CallToolResult missingResult = baselineTools.importSource(pngRequest());
    CallToolResult failingResult =
        new DedirenTools(root, EngineWiring.defaults(), Map.of(), failing)
            .importSource(pngRequest());

    assertJsonOnlyFallback(missingResult, baseline);
    assertJsonOnlyFallback(failingResult, baseline);
  }

  private static CallToolRequest dataRequest() {
    return new CallToolRequest(
        "dediren_import", Map.of("content", MERMAID, "plugin", "mermaid", "output", "data"));
  }

  private static CallToolRequest pngRequest() {
    return new CallToolRequest(
        "dediren_import",
        Map.of(
            "content",
            MERMAID,
            "plugin",
            "mermaid",
            "output",
            "image",
            "accepted_image_types",
            List.of("image/png")));
  }

  private static void assertJsonOnlyFallback(CallToolResult actual, CallToolResult baseline) {
    assertThat(actual.isError()).isEqualTo(baseline.isError());
    assertThat(actual.content()).hasSize(1);
    assertThat(actual.content().getFirst()).isInstanceOf(TextContent.class);
    assertThat(((TextContent) actual.content().getFirst()).text())
        .isEqualTo(((TextContent) baseline.content().getFirst()).text());
  }

  private static Path executable(Path target, String script) throws Exception {
    Files.writeString(target, script, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(
        target,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
    return target;
  }
}

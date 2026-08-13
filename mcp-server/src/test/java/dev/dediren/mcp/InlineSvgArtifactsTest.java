package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InlineSvgArtifactsTest {

  @Test
  void reportedBuildArtifactIsReconfinedBeforeItIsRead(@TempDir Path temp) throws Exception {
    Path artifacts = Files.createDirectory(temp.resolve("artifacts"));
    Files.writeString(temp.resolve("outside.svg"), "<svg></svg>");
    String outcome =
        """
        {
          "build_result_schema_version": "build-result.schema.v1",
          "views": [{
            "artifacts": [{"artifact_kind": "svg", "path": "../outside.svg"}]
          }]
        }
        """;

    assertThatThrownBy(() -> DedirenTools.buildSvgImages(outcome, artifacts, false))
        .isInstanceOf(DedirenTools.InlineArtifactException.class)
        .hasMessageContaining("escapes its output root");
  }
}

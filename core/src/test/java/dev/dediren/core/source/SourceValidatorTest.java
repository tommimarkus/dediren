package dev.dediren.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class SourceValidatorTest {
  @TempDir Path temp;

  @Test
  void validateSourceUsesExplicitBundleRootOutsideCurrentWorkingDirectory() throws Exception {
    Path bundleRoot = temp.resolve("bundle-root");
    Path outsideBundle = temp.resolve("outside-bundle");
    Files.createDirectories(bundleRoot.resolve("schemas"));
    Files.createDirectories(outsideBundle);
    Files.writeString(bundleRoot.resolve("schemas/model.schema.json"), "{}");
    String originalUserDir = System.getProperty("user.dir");
    String originalBundleRoot = System.getProperty("dediren.bundle.root");
    System.setProperty("user.dir", outsideBundle.toString());
    System.setProperty("dediren.bundle.root", bundleRoot.toString());
    try {
      ValidationResult result =
          SourceValidator.validateSourceJson(
              """
                    {
                      "model_schema_version": "model.schema.v1",
                      "nodes": [],
                      "relationships": [],
                      "plugins": { "generic-graph": { "views": [] } }
                    }
                    """,
              null);

      assertThat(result.exitCode()).isZero();
      JsonNode data = result.envelope().data();
      assertThat(data.path("node_count").asInt()).isZero();
      assertThat(data.path("relationship_count").asInt()).isZero();
      assertThat(data.path("model_schema_version").asText()).isEqualTo("model.schema.v1");
    } finally {
      restoreProperty("user.dir", originalUserDir);
      restoreProperty("dediren.bundle.root", originalBundleRoot);
    }
  }

  @Test
  void validateSourceFailsWhenBundleRootIsAbsentAndCwdHasNoSchema() throws Exception {
    Path outsideBundle = temp.resolve("outside-bundle-no-schema");
    Files.createDirectories(outsideBundle); // deliberately no schemas/ dir
    String originalUserDir = System.getProperty("user.dir");
    String originalBundleRoot = System.getProperty("dediren.bundle.root");
    System.setProperty("user.dir", outsideBundle.toString());
    System.clearProperty("dediren.bundle.root");
    try {
      // DedirenPaths.productRoot() throws IllegalStateException when neither
      // dediren.bundle.root nor a schemas/model.schema.json ancestor is found;
      // validateSourceJson does not catch it, so it propagates as the failure signal.
      assertThatThrownBy(
              () ->
                  SourceValidator.validateSourceJson(
                      """
                    {
                      "model_schema_version": "model.schema.v1",
                      "nodes": [],
                      "relationships": [],
                      "plugins": { "generic-graph": { "views": [] } }
                    }
                    """,
                      null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("dediren.bundle.root");
    } finally {
      restoreProperty("user.dir", originalUserDir);
      restoreProperty("dediren.bundle.root", originalBundleRoot);
    }
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }
}

package dev.dediren.core.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.Diagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import tools.jackson.databind.JsonNode;

// Mutates JVM-global state (user.dir / dediren.bundle.root system properties); must never run
// concurrently with other test classes if parallel execution is ever enabled.
@Isolated
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

  @Test
  void validateSourceReportsEverySchemaViolationNotJustTheFirst() {
    // Two independent schema violations: node "n1" is missing required "label" and
    // node "n2" is missing required "properties". SchemaValidator must surface both,
    // not just the alphabetically-first violation message.
    ValidationResult result =
        SourceValidator.validateSourceJson(
            """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "n1", "type": "Type", "properties": {} },
                    { "id": "n2", "type": "Type", "label": "Two" }
                  ],
                  "relationships": [],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """,
            null);

    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    var diagnostics = result.envelope().diagnostics();
    assertThat(diagnostics.size()).isGreaterThanOrEqualTo(2);
    assertThat(diagnostics)
        .allSatisfy(d -> assertThat(d.code()).isEqualTo("DEDIREN_SCHEMA_INVALID"));
    // The two violation messages must be distinct: this proves they are independent
    // sibling violations, not the same violation reported twice.
    assertThat(diagnostics.stream().map(Diagnostic::message).distinct().count())
        .isGreaterThanOrEqualTo(2);
  }

  // --- Source-fragment confinement (the MCP trust boundary). The null confinement root is the
  // CLI/human lane and must stay byte-identical; a non-null root is the confined MCP lane. ---

  private static final String FRAGMENT_NODE =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [
          { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
        ],
        "relationships": [],
        "plugins": { "generic-graph": { "views": [] } }
      }
      """;

  private static String modelWithFragment(String fragmentPath) {
    return """
        {
          "model_schema_version": "model.schema.v1",
          "fragments": ["%s"],
          "nodes": [],
          "relationships": [],
          "plugins": { "generic-graph": { "views": [] } }
        }
        """
        .formatted(fragmentPath);
  }

  @Test
  void cliLaneResolvesADotDotFragmentUnchanged() throws Exception {
    // The human lane (null confinement root) must keep resolving a '..' traversal fragment exactly
    // as before -- a human legitimately references fragments across their own project. This guards
    // against accidentally confining the human lane.
    Path base = Files.createDirectories(temp.resolve("base"));
    Files.createDirectories(temp.resolve("shared"));
    Files.writeString(temp.resolve("shared/piece.json"), FRAGMENT_NODE);

    ValidationResult result =
        SourceValidator.validateSourceJson(modelWithFragment("../shared/piece.json"), base);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().data().path("node_count").asInt()).isEqualTo(1);
  }

  @Test
  void confinedLaneRejectsADotDotFragmentThatEscapesRoot() throws Exception {
    Path base = Files.createDirectories(temp.resolve("base"));
    // The escaping target need not exist: confinement decides before the read, so there is no
    // exists-vs-not-exists oracle either way.
    ValidationResult result =
        SourceValidator.validateSourceJson(
            modelWithFragment("../escape.json"), base, /* confinementRoot= */ base);

    assertThat(result.exitCode()).isEqualTo(CommandExitCode.INPUT_ERROR.code());
    Diagnostic diagnostic = result.envelope().diagnostics().get(0);
    assertThat(diagnostic.code()).isEqualTo("DEDIREN_MCP_PATH_OUTSIDE_ROOT");
    assertThat(diagnostic.path()).isEqualTo("$.fragments[0]");
    // Sanitized: only the model-supplied relative string, never the resolved absolute target.
    assertThat(diagnostic.message()).contains("../escape.json");
    assertThat(diagnostic.message()).doesNotContain(temp.toString());
    assertThat(diagnostic.message()).doesNotContain(temp.toRealPath().toString());
  }

  @Test
  void confinedLaneLoadsAFragmentInsideRoot() throws Exception {
    Path base = Files.createDirectories(temp.resolve("base"));
    Files.writeString(base.resolve("piece.json"), FRAGMENT_NODE);

    ValidationResult result =
        SourceValidator.validateSourceJson(
            modelWithFragment("piece.json"), base, /* confinementRoot= */ base);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().data().path("node_count").asInt()).isEqualTo(1);
  }

  private static void restoreProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }
}

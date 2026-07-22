package dev.dediren.core.commands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.analysis.Provenance;
import dev.dediren.core.engine.EngineRunOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Direct unit tests for the shared read-side command drivers. Both the CLI and MCP lanes call these
 * verbatim, so pinning every early-exit gate and result branch here — at the source, engine-free —
 * is cheaper and more precise than proving each only through an integration lane, and it is the
 * convention the sibling {@code CoreCommandsTest} already follows for {@code CoreCommands}.
 *
 * <p>Models are inline rather than fixture-loaded: these cases exercise validation and analysis
 * branches, not any particular fixture's content, so a self-contained model keeps the test honest
 * about what it drives.
 */
class AnalysisCommandsTest {

  private static final String MODEL =
      """
      {
        "model_schema_version": "model.schema.v1",
        "nodes": [
          { "id": "api", "type": "generic.component", "label": "API", "properties": {} },
          { "id": "db", "type": "generic.component", "label": "DB", "properties": {} }
        ],
        "relationships": [
          { "id": "api-uses-db", "type": "generic.calls", "source": "api", "target": "db",
            "label": "uses", "properties": {} }
        ],
        "plugins": {
          "generic-graph": {
            "views": [
              { "id": "main", "label": "Main", "nodes": ["api", "db"],
                "relationships": ["api-uses-db"], "groups": [] }
            ]
          }
        }
      }
      """;

  private static JsonNode envelopeOf(EngineRunOutcome outcome) {
    return JsonSupport.objectMapper().readTree(outcome.stdout());
  }

  private static String code(EngineRunOutcome outcome) {
    return envelopeOf(outcome).path("diagnostics").path(0).path("code").asText();
  }

  // --- diff -----------------------------------------------------------------

  @Test
  void diffOfValidModelsIsAnOkEnvelope() {
    EngineRunOutcome outcome =
        AnalysisCommands.diffCommand(MODEL, Path.of("."), MODEL, Path.of("."), null);

    assertThat(outcome.exitCode()).isZero();
    JsonNode envelope = envelopeOf(outcome);
    assertThat(envelope.path("status").asText()).isEqualTo("ok");
    assertThat(envelope.path("data").path("diff_result_schema_version").asText())
        .isEqualTo("diff-result.schema.v1");
  }

  @Test
  void diffSurfacesASourceDiagnosticAsAnErrorEnvelope() {
    EngineRunOutcome outcome =
        AnalysisCommands.diffCommand(
            "{\"model_schema_version\":\"model.schema.v0\"}",
            Path.of("."),
            MODEL,
            Path.of("."),
            null);

    assertThat(outcome.exitCode()).isNotZero();
    assertThat(envelopeOf(outcome).path("status").asText()).isEqualTo("error");
    assertThat(code(outcome)).isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
  }

  // --- query ----------------------------------------------------------------

  @Test
  void queryRejectsAnUnknownKind() {
    EngineRunOutcome outcome =
        AnalysisCommands.queryCommand("bogus", null, MODEL, Path.of("."), null);

    assertThat(outcome.exitCode()).isNotZero();
    assertThat(code(outcome)).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(envelopeOf(outcome).path("diagnostics").path(0).path("message").asText())
        .contains("unsupported query kind");
  }

  @Test
  void queryRejectsANullKindWithoutThrowing() {
    // The MCP lane can present an absent 'kind'; List.of(...).contains(null) throws, so the guard
    // must short-circuit null rather than reach the whitelist check.
    EngineRunOutcome outcome = AnalysisCommands.queryCommand(null, null, MODEL, Path.of("."), null);

    assertThat(outcome.exitCode()).isNotZero();
    assertThat(code(outcome)).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
  }

  @Test
  void queryDependentsRequiresAnId() {
    EngineRunOutcome outcome =
        AnalysisCommands.queryCommand("dependents", null, MODEL, Path.of("."), null);

    assertThat(outcome.exitCode()).isNotZero();
    assertThat(envelopeOf(outcome).path("diagnostics").path(0).path("message").asText())
        .contains("requires --id");
  }

  @Test
  void queryDependentsRejectsAnUnknownNodeId() {
    EngineRunOutcome outcome =
        AnalysisCommands.queryCommand("dependents", "ghost", MODEL, Path.of("."), null);

    assertThat(outcome.exitCode()).isNotZero();
    assertThat(envelopeOf(outcome).path("diagnostics").path(0).path("message").asText())
        .contains("unknown node id 'ghost'");
  }

  @Test
  void queryDependentsWithAValidIdIsAnOkResult() {
    EngineRunOutcome outcome =
        AnalysisCommands.queryCommand("dependents", "db", MODEL, Path.of("."), null);

    assertThat(outcome.exitCode()).isZero();
    assertThat(envelopeOf(outcome).path("data").path("query_result_schema_version").asText())
        .isEqualTo("query-result.schema.v1");
  }

  @Test
  void queryOrphansIsAnOkResult() {
    EngineRunOutcome outcome =
        AnalysisCommands.queryCommand("orphans", null, MODEL, Path.of("."), null);

    assertThat(outcome.exitCode()).isZero();
    assertThat(envelopeOf(outcome).path("status").asText()).isEqualTo("ok");
  }

  @Test
  void queryViewCoverageIsAnOkResult() {
    EngineRunOutcome outcome =
        AnalysisCommands.queryCommand("view-coverage", null, MODEL, Path.of("."), null);

    assertThat(outcome.exitCode()).isZero();
    assertThat(envelopeOf(outcome).path("status").asText()).isEqualTo("ok");
  }

  // --- verify (tri-state + the CLI-only directory gate) ---------------------

  @Test
  void verifyRejectsAnArtifactsPathThatIsNotADirectory(@TempDir Path root) throws Exception {
    Path notADir = root.resolve("plain.svg");
    Files.writeString(notADir, "<svg/>");

    EngineRunOutcome outcome = AnalysisCommands.verifyCommand(MODEL, root, null, notADir);

    assertThat(outcome.exitCode()).isNotZero();
    assertThat(code(outcome)).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(envelopeOf(outcome).path("diagnostics").path(0).path("message").asText())
        .contains("must name an existing directory");
  }

  @Test
  void verifyReportsAnUnstampedArtifactAsAWarning(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("diagram.svg"), "<svg/>");

    EngineRunOutcome outcome = AnalysisCommands.verifyCommand(MODEL, root, null, root);

    assertThat(outcome.exitCode()).isZero();
    JsonNode envelope = envelopeOf(outcome);
    assertThat(envelope.path("status").asText()).isEqualTo("warning");
    assertThat(envelope.path("data").path("artifacts").path(0).path("status").asText())
        .isEqualTo("unstamped");
  }

  @Test
  void verifyReportsAStaleArtifactAsAnError(@TempDir Path root) throws Exception {
    // A stamp whose model_sha256 cannot match the model's recomputed canonical hash: 64 zeros is a
    // legal-shape hash the model can never produce, so verify must classify it stale.
    String stamp =
        Provenance.payload(
            "model.schema.v1", "0".repeat(64), "main", "render_policy_sha256", "abc123", "test");
    String svg = Provenance.stampSvg("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>", stamp);
    Files.writeString(root.resolve("diagram.svg"), svg);

    EngineRunOutcome outcome = AnalysisCommands.verifyCommand(MODEL, root, null, root);

    assertThat(outcome.exitCode()).isNotZero();
    JsonNode envelope = envelopeOf(outcome);
    assertThat(envelope.path("status").asText()).isEqualTo("error");
    assertThat(code(outcome)).isEqualTo("DEDIREN_ARTIFACT_STALE");
    assertThat(envelope.path("data").path("artifacts").path(0).path("status").asText())
        .isEqualTo("stale");
  }

  // --- status (happy + the CLI-only directory gate) -------------------------

  @Test
  void statusRejectsARootThatIsNotADirectory(@TempDir Path root) throws Exception {
    Path notADir = root.resolve("plain.json");
    Files.writeString(notADir, MODEL);

    EngineRunOutcome outcome = AnalysisCommands.statusCommand(notADir);

    assertThat(outcome.exitCode()).isNotZero();
    assertThat(code(outcome)).isEqualTo("DEDIREN_COMMAND_INPUT_INVALID");
    assertThat(envelopeOf(outcome).path("diagnostics").path(0).path("message").asText())
        .contains("must name an existing directory");
  }

  @Test
  void statusIndexesTheModelsUnderTheRoot(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("model.json"), MODEL);

    EngineRunOutcome outcome = AnalysisCommands.statusCommand(root);

    assertThat(outcome.exitCode()).isZero();
    JsonNode envelope = envelopeOf(outcome);
    assertThat(envelope.path("status").asText()).isEqualTo("ok");
    assertThat(envelope.path("data").path("models").path(0).path("path").asText())
        .isEqualTo("model.json");
  }
}

package dev.dediren.core.commands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.CommandExitCode;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.ImportEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ImportCommandTest {
  @Test
  void importCommandEnvelopesTheMermaidModelAndPreservesAggregatedWarnings() throws Exception {
    var outcome =
        CoreCommands.importCommand(
            "mermaid",
            "flowchart TB\nA[Start] --> B[End]\nstyle A fill:#fff\n",
            Map.of(),
            Engines.of(
                List.of(), List.of(), List.of(), List.of(), List.of(new StubMermaidImporter())));

    JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());
    assertThat(outcome.exitCode()).isZero();
    assertThat(envelope.path("status").asText()).isEqualTo("warning");
    assertThat(envelope.at("/data/model_schema_version").asText()).isEqualTo("model.schema.v1");
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_MERMAID_HINT_IGNORED");
  }

  private static final class StubMermaidImporter implements ImportEngine {
    @Override
    public String id() {
      return "mermaid";
    }

    @Override
    public EngineResult<SourceDocument> importSource(String source) {
      return new EngineResult<>(
          new SourceDocument(
              "model.schema.v1", List.of(), List.of(), List.of(), List.of(), Map.of()),
          List.of(
              new Diagnostic(
                  "DEDIREN_MERMAID_HINT_IGNORED", DiagnosticSeverity.WARNING, "style: 1", "$")));
    }
  }

  @Test
  void importCommandPreservesAtomicEngineFailure() throws Exception {
    ImportEngine rejecting =
        new ImportEngine() {
          @Override
          public String id() {
            return "mermaid";
          }

          @Override
          public EngineResult<SourceDocument> importSource(String source)
              throws dev.dediren.engine.EngineException {
            throw new dev.dediren.engine.EngineException(
                List.of(
                    new Diagnostic(
                        "DEDIREN_MERMAID_SYNTAX_INVALID",
                        DiagnosticSeverity.ERROR,
                        "expected a node after -->",
                        "line 2, column 6")),
                2);
          }
        };

    var outcome =
        CoreCommands.importCommand(
            "mermaid",
            "flowchart TD\nA -->\n",
            Map.of(),
            Engines.of(List.of(), List.of(), List.of(), List.of(), List.of(rejecting)));
    JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());

    assertThat(outcome.exitCode()).isEqualTo(2);
    assertThat(envelope.has("data")).isFalse();
    assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo("line 2, column 6");
  }

  /**
   * The import lane turns untrusted foreign text into a source model on a path that never touched
   * the model schema, so core re-gates the emitted document — here the schema version, the first
   * check {@code SourceValidator}'s parse makes. The importer's own diagnostic still rides the
   * rejection envelope verbatim, path included: core adjudicates the document it was handed, not
   * what the importer said about the text it parsed.
   */
  @Test
  void importCommandRegatesTheImporterDocumentAgainstTheModelSchemaVersion() throws Exception {
    ImportEngine importer =
        stub(
            new SourceDocument(
                "engine-owned-sentinel", List.of(), List.of(), List.of(), List.of(), Map.of()),
            List.of(
                new Diagnostic(
                    "DEDIREN_MERMAID_HINT_IGNORED",
                    DiagnosticSeverity.WARNING,
                    "style: 1",
                    "line 4, column 1")));

    var outcome =
        CoreCommands.importCommand(
            "mermaid",
            "flowchart TD\nA\n",
            Map.of(),
            Engines.of(List.of(), List.of(), List.of(), List.of(), List.of(importer)));
    JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());

    assertThat(outcome.exitCode()).isEqualTo(CommandExitCode.PLUGIN_ERROR.code());
    assertThat(envelope.path("status").asText()).isEqualTo("error");
    assertThat(envelope.has("data")).isFalse();
    assertThat(envelope.at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_MERMAID_HINT_IGNORED");
    assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo("line 4, column 1");
    assertThat(envelope.at("/diagnostics/1/code").asText())
        .isEqualTo("DEDIREN_SCHEMA_VERSION_UNKNOWN");
  }

  /**
   * The version gate alone would let any structurally illegal document through, so the re-gate runs
   * the full JSON Schema too: this node id violates {@code model.schema.json}'s id pattern, which
   * only schema validation catches.
   */
  @Test
  void importCommandRegatesTheImporterDocumentAgainstTheModelJsonSchema() throws Exception {
    ImportEngine importer =
        stub(
            new SourceDocument(
                "model.schema.v1",
                List.of(),
                List.of(),
                List.of(new SourceNode("not a legal id", "generic.node", "Start", Map.of())),
                List.of(),
                Map.of()),
            List.of());

    var outcome =
        CoreCommands.importCommand(
            "mermaid",
            "flowchart TD\nA[Start]\n",
            Map.of(),
            Engines.of(List.of(), List.of(), List.of(), List.of(), List.of(importer)));
    JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());

    assertThat(outcome.exitCode()).isEqualTo(CommandExitCode.PLUGIN_ERROR.code());
    assertThat(envelope.has("data")).isFalse();
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_SCHEMA_INVALID");
  }

  /** A document that clears the re-gate is still published, with the importer's diagnostics. */
  @Test
  void importCommandPublishesADocumentThatClearsTheRegate() throws Exception {
    ImportEngine importer =
        stub(
            new SourceDocument(
                "model.schema.v1",
                List.of(),
                List.of(),
                List.of(new SourceNode("start", "generic.node", "Start", Map.of())),
                List.of(),
                Map.of()),
            List.of());

    var outcome =
        CoreCommands.importCommand(
            "mermaid",
            "flowchart TD\nstart[Start]\n",
            Map.of(),
            Engines.of(List.of(), List.of(), List.of(), List.of(), List.of(importer)));
    JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());

    assertThat(outcome.exitCode()).isZero();
    assertThat(envelope.path("status").asText()).isEqualTo("ok");
    assertThat(envelope.at("/data/nodes/0/id").asText()).isEqualTo("start");
  }

  private static ImportEngine stub(SourceDocument document, List<Diagnostic> diagnostics) {
    return new ImportEngine() {
      @Override
      public String id() {
        return "mermaid";
      }

      @Override
      public EngineResult<SourceDocument> importSource(String source) {
        return new EngineResult<>(document, diagnostics);
      }
    };
  }
}

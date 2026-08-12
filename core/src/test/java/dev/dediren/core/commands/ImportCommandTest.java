package dev.dediren.core.commands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
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
            Engines.of(List.of(), List.of(), List.of(), List.of(), List.of(new StubMermaidImporter())));

    JsonNode envelope = JsonSupport.objectMapper().readTree(outcome.stdout());
    assertThat(outcome.exitCode()).isZero();
    assertThat(envelope.path("status").asText()).isEqualTo("warning");
    assertThat(envelope.at("/data/model_schema_version").asText()).isEqualTo("model.schema.v1");
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_MERMAID_HINT_IGNORED");
  }

  private static final class StubMermaidImporter implements ImportEngine {
    @Override
    public String id() {
      return "mermaid";
    }

    @Override
    public EngineResult<JsonNode> importSource(String source) {
      return new EngineResult<>(
          JsonSupport.objectMapper().readTree("{\"model_schema_version\":\"model.schema.v1\"}"),
          List.of(
              new Diagnostic(
                  "DEDIREN_MERMAID_HINT_IGNORED", DiagnosticSeverity.WARNING, "style: 1", "$")));
    }
  }
}

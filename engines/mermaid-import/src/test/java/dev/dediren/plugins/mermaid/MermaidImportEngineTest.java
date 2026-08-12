package dev.dediren.plugins.mermaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.engine.EngineException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MermaidImportEngineTest {
  private final MermaidImportEngine engine = new MermaidImportEngine();

  @Test
  void importsTheSupportedFlowchartSubsetIntoTheGenericModelContract() throws Exception {
    JsonNode model = engine.importSource(fixture("flowchart-v1.mmd")).value();

    assertThat(model.path("model_schema_version").asText()).isEqualTo("model.schema.v1");
    assertThat(model.path("nodes")).hasSize(3);
    assertThat(model.path("nodes").get(0).path("type").asText()).isEqualTo("generic.node");
    assertThat(model.path("relationships")).hasSize(2);
    assertThat(model.path("relationships").get(0).path("type").asText()).isEqualTo("generic.link");
    assertThat(model.at("/plugins/generic-graph/views/0/id").asText()).isEqualTo("main");
    assertThat(model.at("/plugins/generic-graph/views/0/layout_preferences/direction").asText())
        .isEqualTo("right");
    assertThat(model.at("/plugins/generic-graph/views/0/groups/0/role").asText())
        .isEqualTo("layout-only");
    assertThat(model.at("/plugins/generic-graph/views/0/groups/0/members"))
        .containsExactly("API", "Store");
  }

  @Test
  void warnsOncePerPresentationHintFamilyWithoutDroppingTheModel() throws Exception {
    var result = engine.importSource("flowchart TB\nA --> B\nclassDef hot fill:#f00\nstyle A fill:#fff\n");

    assertThat(result.value().path("nodes")).hasSize(2);
    assertThat(result.diagnostics()).extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_MERMAID_HINT_IGNORED");
    assertThat(result.diagnostics().get(0).message()).contains("classDef").contains("style");
  }

  @Test
  void rejectsNonFlowchartAndUnsafeOrAmbiguousInputAtomicallyWithLocation() {
    assertRejected("unsupported-sequence.mmd", "DEDIREN_MERMAID_UNSUPPORTED_DIAGRAM", 1, 1);
    assertRejected("invalid-interaction.mmd", "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT", 3, 3);
    assertRejected("flowchart TD\nA --- B\n", "DEDIREN_MERMAID_UNSUPPORTED_EDGE", 2, 3);
    assertRejected("flowchart TD\nA[<b>HTML</b>]\n", "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT", 2, 3);
    assertRejected("flowchart TD\nA -->\n", "DEDIREN_MERMAID_SYNTAX_INVALID", 2, 6);
  }

  @Test
  void acceptsBoundaryLimitsAndRejectsTheFirstValueAboveEachLimit() throws Exception {
    assertThat(engine.importSource(flowchartWithStatements(200_000)).value().path("relationships"))
        .hasSize(200_000);
    assertLimit(flowchartWithStatements(200_001), "DEDIREN_MERMAID_STATEMENT_LIMIT_EXCEEDED");
    assertThat(engine.importSource(nestedSubgraphs(256)).value().path("nodes")).hasSize(1);
    assertLimit(nestedSubgraphs(257), "DEDIREN_MERMAID_NESTING_LIMIT_EXCEEDED");
    assertThat(engine.importSource("flowchart TD\nA[" + "x".repeat(65_536) + "]\n").value().path("nodes"))
        .hasSize(1);
    assertLimit("flowchart TD\nA[" + "x".repeat(65_537) + "]\n", "DEDIREN_MERMAID_TOKEN_LIMIT_EXCEEDED");
    assertLimit(flowchartWithStatements(100_001), "DEDIREN_MERMAID_ELEMENT_LIMIT_EXCEEDED");
    assertThat(engine.importSource(commentAtBytes(64 * 1024 * 1024 - 1)).value()).isNotNull();
    assertThat(engine.importSource(commentAtBytes(64 * 1024 * 1024)).value()).isNotNull();
    assertLimit(commentAtBytes(64 * 1024 * 1024 + 1), "DEDIREN_MERMAID_INPUT_TOO_LARGE");
  }

  @Test
  void parserNeverLeaksPartialOutputForMalformedFuzzSeeds() {
    String[] seeds = {"", "graph", "flowchart TD\nA-->", "flowchart TD\nsubgraph A\nend\nend", "flowchart TD\nA[\u0000]"};
    for (String seed : seeds) {
      try {
        engine.importSource(seed);
      } catch (EngineException expected) {
        assertThat(expected.exitCode()).isEqualTo(2);
        assertThat(expected.diagnostics()).isNotEmpty();
      }
    }
  }

  private void assertLimit(String source, String code) {
    assertThatThrownBy(() -> engine.importSource(source))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              var diagnostic = ((EngineException) error).diagnostics().get(0);
              assertThat(diagnostic.code()).isEqualTo(code);
              assertThat(diagnostic.path()).isEqualTo("$");
            });
  }

  private void assertRejected(String source, String code, int line, int column) {
    assertThatThrownBy(() -> engine.importSource(fixture(source)))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              var diagnostic = ((EngineException) error).diagnostics().get(0);
              assertThat(diagnostic.code()).isEqualTo(code);
              assertThat(diagnostic.path()).isEqualTo("line " + line + ", column " + column);
            });
  }

  private static String fixture(String name) throws Exception {
    return Files.readString(Path.of("..", "..", "fixtures", "mermaid", name));
  }

  private static String flowchartWithStatements(int count) {
    return "flowchart TD\n" + "A --> B\n".repeat(count);
  }

  private static String nestedSubgraphs(int depth) {
    return "flowchart TD\n" + "subgraph g\n".repeat(depth) + "A\n" + "end\n".repeat(depth);
  }

  private static String commentAtBytes(int bytes) {
    String prefix = "flowchart TD\n%%";
    return prefix + "x".repeat(bytes - prefix.length());
  }
}

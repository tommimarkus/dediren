package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class CliImportCommandTest {
  @TempDir Path temp;

  @Test
  void importReadsAConfinedFileOrBoundedStdinAndPrintsTheSameEnvelope() throws Exception {
    Path source = temp.resolve("diagram.mmd");
    String mermaid = "flowchart RL\nA[One] --> B[Two]\n";
    Files.writeString(source, mermaid);

    CliResult file =
        Main.executeForTesting(
            new String[] {"import", "--plugin", "mermaid", "--input", source.toString()}, "");
    CliResult stdin =
        Main.executeForTesting(new String[] {"import", "--plugin", "mermaid"}, mermaid);

    assertThat(file.exitCode()).isZero();
    assertThat(stdin.exitCode()).isZero();
    assertThat(
            JsonSupport.objectMapper()
                .readTree(file.stdout())
                .at("/data/plugins/generic-graph/views/0/layout_preferences/direction")
                .asText())
        .isEqualTo("left");
    assertThat(stdin.stdout()).isEqualTo(file.stdout());
  }

  @Test
  void importRejectsMalformedInputWithThePublishedExitCodeAndLocation() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {"import", "--plugin", "mermaid"}, "flowchart TD\nA -->\n");

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(
            JsonSupport.objectMapper().readTree(result.stdout()).at("/diagnostics/0/code").asText())
        .isEqualTo("DEDIREN_MERMAID_SYNTAX_INVALID");
    assertThat(
            JsonSupport.objectMapper().readTree(result.stdout()).at("/diagnostics/0/path").asText())
        .isEqualTo("line 2, column 6");
    assertThat(JsonSupport.objectMapper().readTree(result.stdout()).has("data")).isFalse();
  }

  @Test
  void importedModelValidatesAndRendersThroughTheRealPipeline() throws Exception {
    Path root = dev.dediren.testsupport.TestSupport.workspaceRoot();
    CliResult imported =
        Main.executeForTesting(
            new String[] {"import", "--plugin", "mermaid"},
            "flowchart LR\nClient[Client] --> API[API] --> Store[(Store)]\n");
    JsonNode importedEnvelope = JsonSupport.objectMapper().readTree(imported.stdout());
    Path model = temp.resolve("imported.json");
    Files.writeString(
        model, JsonSupport.objectMapper().writeValueAsString(importedEnvelope.get("data")));

    CliResult validation =
        Main.executeForTesting(new String[] {"validate", "--input", model.toString()}, "");
    Path out = temp.resolve("rendered");
    CliResult build =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              model.toString(),
              "--out",
              out.toString(),
              "--render-policy",
              root.resolve("fixtures/render-policy/default-svg.json").toString()
            },
            "");

    assertThat(validation.exitCode()).describedAs(validation.stdout()).isZero();
    assertThat(build.exitCode()).describedAs(build.stdout()).isZero();
    assertThat(Files.readString(out.resolve("main/diagram.svg")))
        .contains("<svg", "Client", "API", "Store");
  }

  /**
   * The DOT engine's own parse location has to survive the command layer unchanged, the same way
   * the Mermaid case above proves it. Engine-level tests pin the parser's location and core-level
   * tests pin that core preserves whatever an importer publishes; only this exercises the composed
   * path, so a broken {@code EngineWiring} registration or a changed engine id would otherwise ship
   * with both of those still green.
   */
  @Test
  void dotImportRejectsMalformedInputWithThePublishedExitCodeAndLocation() throws Exception {
    CliResult result =
        Main.executeForTesting(
            new String[] {"import", "--plugin", "dot"}, "digraph G {\na -> ;\n}\n");

    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(envelope.at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_DOT_SYNTAX_INVALID");
    assertThat(envelope.at("/diagnostics/0/path").asText()).isEqualTo("line 2, column 6");
    assertThat(envelope.has("data")).isFalse();
  }

  @Test
  void importedDotModelValidatesAndRendersThroughTheRealPipeline() throws Exception {
    Path root = dev.dediren.testsupport.TestSupport.workspaceRoot();
    CliResult imported =
        Main.executeForTesting(
            new String[] {"import", "--plugin", "dot"},
            "digraph G {\nrankdir=LR;\nClient -> API -> Store;\n}\n");
    JsonNode importedEnvelope = JsonSupport.objectMapper().readTree(imported.stdout());
    Path model = temp.resolve("imported-dot.json");
    Files.writeString(
        model, JsonSupport.objectMapper().writeValueAsString(importedEnvelope.get("data")));

    CliResult validation =
        Main.executeForTesting(new String[] {"validate", "--input", model.toString()}, "");
    Path out = temp.resolve("rendered-dot");
    CliResult build =
        Main.executeForTesting(
            new String[] {
              "build",
              "--input",
              model.toString(),
              "--out",
              out.toString(),
              "--render-policy",
              root.resolve("fixtures/render-policy/default-svg.json").toString()
            },
            "");

    assertThat(imported.exitCode()).describedAs(imported.stdout()).isZero();
    assertThat(
            importedEnvelope
                .at("/data/plugins/generic-graph/views/0/layout_preferences/direction")
                .asText())
        .isEqualTo("right");
    assertThat(validation.exitCode()).describedAs(validation.stdout()).isZero();
    assertThat(build.exitCode()).describedAs(build.stdout()).isZero();
    assertThat(Files.readString(out.resolve("main/diagram.svg")))
        .contains("<svg", "Client", "API", "Store");
  }

  @Test
  void compatibilityImportsPreserveSemanticsAndValidateThroughTheComposedCli() throws Exception {
    CliResult mermaid =
        Main.executeForTesting(
            new String[] {"import", "--plugin", "mermaid"},
            "flowchart LR\nA[\"one<br>two\"]\n --> B\nB ---|peer<br/>edge| C\n");
    JsonNode mermaidEnvelope = JsonSupport.objectMapper().readTree(mermaid.stdout());

    assertThat(mermaid.exitCode()).describedAs(mermaid.stdout()).isZero();
    assertThat(mermaidEnvelope.at("/status").asText()).isEqualTo("warning");
    assertThat(mermaidEnvelope.at("/data/nodes/0/label").asText()).isEqualTo("one\ntwo");
    assertThat(mermaidEnvelope.at("/data/relationships/0/type").asText()).isEqualTo("generic.link");
    assertThat(mermaidEnvelope.at("/data/relationships/1/type").asText()).isEqualTo("generic.edge");
    assertThat(mermaidEnvelope.at("/data/relationships/1/label").asText()).isEqualTo("peer\nedge");
    assertImportedDataValidates(mermaidEnvelope.get("data"), "compatibility-mermaid.json");

    CliResult dot =
        Main.executeForTesting(
            new String[] {"import", "--plugin", "dot"},
            "digraph G { rankdir=TB; node [color=red]; a, b [shape=diamond]; a -> b; }");
    JsonNode dotEnvelope = JsonSupport.objectMapper().readTree(dot.stdout());

    assertThat(dot.exitCode()).describedAs(dot.stdout()).isZero();
    assertThat(dotEnvelope.at("/data/nodes")).hasSize(2);
    assertThat(dotEnvelope.at("/data/nodes/0/properties/dot/attributes/color").asText())
        .isEqualTo("red");
    assertThat(dotEnvelope.at("/data/nodes/1/properties/dot/attributes/shape").asText())
        .isEqualTo("diamond");
    assertThat(dotEnvelope.at("/data/relationships/0/source").asText()).isEqualTo("a");
    assertThat(dotEnvelope.at("/data/relationships/0/target").asText()).isEqualTo("b");
    assertImportedDataValidates(dotEnvelope.get("data"), "compatibility-dot.json");
  }

  private void assertImportedDataValidates(JsonNode data, String fileName) throws Exception {
    Path model = temp.resolve(fileName);
    Files.writeString(model, JsonSupport.objectMapper().writeValueAsString(data));
    CliResult validation =
        Main.executeForTesting(new String[] {"validate", "--input", model.toString()}, "");
    assertThat(validation.exitCode()).describedAs(validation.stdout()).isZero();
  }
}

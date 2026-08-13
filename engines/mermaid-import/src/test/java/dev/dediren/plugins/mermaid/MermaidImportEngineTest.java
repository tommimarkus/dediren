package dev.dediren.plugins.mermaid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.engine.EngineException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MermaidImportEngineTest {
  private final MermaidImportEngine engine = new MermaidImportEngine();

  @Test
  void importsTheSupportedFlowchartSubsetIntoTheGenericModelContract() throws Exception {
    JsonNode model = model(fixture("flowchart-v1.mmd"));

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
    assertThat(textValues(model.at("/plugins/generic-graph/views/0/groups/0/members")))
        .containsExactly("API", "Store");
  }

  @Test
  void warnsOncePerPresentationHintFamilyWithoutDroppingTheModel() throws Exception {
    var result =
        engine.importSource(
            """
            flowchart TB
            subgraph g[Group]
              direction LR
              A --> B
            end
            classDef hot fill:#f00
            class A hot
            style A fill:#fff
            linkStyle 0 stroke:#000
            """);

    assertThat(result.value().nodes()).hasSize(2);
    assertThat(result.diagnostics())
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_MERMAID_HINT_IGNORED");
    assertThat(result.diagnostics().get(0).message())
        .contains("node shape", "direction", "classDef", "class", "style", "linkStyle");
  }

  @Test
  void preservesLegalIdsReservesThemBeforeNormalizingAndRecordsChangedOriginals() throws Exception {
    JsonNode model = model("flowchart TD\nbad-id --> bad/id\nbad?id --> café\n");

    List<String> ids = new ArrayList<>();
    model.path("nodes").forEach(node -> ids.add(node.path("id").asText()));

    assertThat(ids).containsExactly("bad-id", "bad-id-2", "bad-id-3", "caf-u00e9");
    assertThat(model.at("/nodes/0/properties/mermaid/original_id").isMissingNode()).isTrue();
    assertThat(model.at("/nodes/1/properties/mermaid/original_id").asText()).isEqualTo("bad/id");
    assertThat(model.at("/nodes/2/properties/mermaid/original_id").asText()).isEqualTo("bad?id");
    assertThat(model.at("/nodes/3/properties/mermaid/original_id").asText()).isEqualTo("café");
  }

  @Test
  void rejectsNonFlowchartAndUnsafeOrAmbiguousInputAtomicallyWithLocation() {
    assertRejected("unsupported-sequence.mmd", "DEDIREN_MERMAID_UNSUPPORTED_DIAGRAM", 1, 1);
    assertRejected("invalid-interaction.mmd", "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT", 3, 3);
    assertRejectedText(
        "flowchart TD\nA[<b>HTML</b>]\n", "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT", 2, 3);
    assertRejectedText("flowchart TD\nA -->\n", "DEDIREN_MERMAID_SYNTAX_INVALID", 2, 6);
  }

  @Test
  void acceptsBoundaryLimitsAndRejectsTheFirstValueAboveEachLimit() throws Exception {
    assertThat(engine.importSource(flowchartWithHints(199_999)).value().nodes()).hasSize(1);
    assertThat(engine.importSource(flowchartWithHints(200_000)).value().nodes()).hasSize(1);
    assertLimit(flowchartWithHints(200_001), "DEDIREN_MERMAID_STATEMENT_LIMIT_EXCEEDED");
    assertThat(engine.importSource(flowchartWithEdges(99_997)).value().relationships())
        .hasSize(99_997);
    assertThat(engine.importSource(flowchartWithEdges(99_998)).value().relationships())
        .hasSize(99_998);
    assertLimit(flowchartWithEdges(99_999), "DEDIREN_MERMAID_ELEMENT_LIMIT_EXCEEDED");
    assertThat(engine.importSource(nestedSubgraphs(255)).value().nodes()).hasSize(1);
    assertThat(engine.importSource(nestedSubgraphs(256)).value().nodes()).hasSize(1);
    assertLimit(nestedSubgraphs(257), "DEDIREN_MERMAID_NESTING_LIMIT_EXCEEDED");
    assertThat(engine.importSource("flowchart TD\nA[" + "x".repeat(65_535) + "]\n").value().nodes())
        .hasSize(1);
    assertThat(engine.importSource("flowchart TD\nA[" + "x".repeat(65_536) + "]\n").value().nodes())
        .hasSize(1);
    assertLimit(
        "flowchart TD\nA[" + "x".repeat(65_537) + "]\n", "DEDIREN_MERMAID_TOKEN_LIMIT_EXCEEDED");
    assertThat(engine.importSource(commentAtBytes(64 * 1024 * 1024 - 1)).value()).isNotNull();
    assertThat(engine.importSource(commentAtBytes(64 * 1024 * 1024)).value()).isNotNull();
    assertLimit(commentAtBytes(64 * 1024 * 1024 + 1), "DEDIREN_MERMAID_INPUT_TOO_LARGE");
  }

  @Test
  void acceptsDocumentedCommonNodesUnicodeLabelsChainsCommentsAndDirections() throws Exception {
    String shapes =
        """
        flowchart TD
        %% comment
        A[Square]; B(Round); C([Stadium]); D[[Subroutine]]; E[(Cylinder)]
        F((Circle)); G{Diamond}; H{{Hexagon}}; I[/Trapezoid\\]; J[\\Lean left\\]
        A -->|named| B --> C
        D -- second --> E
        K[Zażółć gęślą 😀]
        """;
    JsonNode model = model(shapes);

    assertThat(model.path("nodes")).hasSize(11);
    assertThat(model.path("relationships")).hasSize(3);
    assertThat(model.at("/nodes/10/label").asText()).isEqualTo("Zażółć gęślą 😀");
    assertThat(engine.importSource(shapes).diagnostics())
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_MERMAID_HINT_IGNORED");

    assertDirection("graph TB\nA --> B\n", "down");
    assertDirection("flowchart TD\nA --> B\n", "down");
    assertDirection("flowchart BT\nA --> B\n", "up");
    assertDirection("flowchart LR\nA --> B\n", "right");
    assertDirection("flowchart RL\nA --> B\n", "left");
  }

  @Test
  void assemblesLogicalStatementsAcrossPhysicalLinesWithoutChangingTheirOrigin() throws Exception {
    JsonNode model =
        model(
            "flowchart TD\r\n"
                + "A\r\n"
                + "  --> B\r\n"
                + "  --> C; D --> E %% comment\r\n"
                + "F[\"balanced\r\n"
                + "quoted label\"] --> G\r\n");

    assertThat(model.path("relationships")).hasSize(4);
    assertThat(model.at("/nodes/5/label").asText()).isEqualTo("balanced\nquoted label");
    assertRejectedText("flowchart TD\nA\n -->\n", "DEDIREN_MERMAID_SYNTAX_INVALID", 3, 5);
  }

  @Test
  void normalizesOnlySafeBrTagsInsideLabelsAndRejectsOtherMarkupAtomically() throws Exception {
    JsonNode model =
        model(
            "flowchart TD\n"
                + "A[one<br>two] --> B[three<br/>four] --> C[five<BR />six]\n"
                + "D -- label<br/>next --> E\n");

    assertThat(model.at("/nodes/0/label").asText()).isEqualTo("one\ntwo");
    assertThat(model.at("/nodes/1/label").asText()).isEqualTo("three\nfour");
    assertThat(model.at("/nodes/2/label").asText()).isEqualTo("five\nsix");
    assertThat(model.at("/relationships/2/label").asText()).isEqualTo("label\nnext");
    assertRejectedText(
        "flowchart TD\nA[one<br><script>bad</script>] --> B\n",
        "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT",
        2,
        3);
    assertRejectedText(
        "flowchart TD\nA[one<br class=unsafe>] --> B\n",
        "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT",
        2,
        3);
    assertRejectedText(
        "flowchart TD\nA[one<br >unsafe] --> B\n", "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT", 2, 3);
    assertRejectedText(
        "flowchart TD\nA<br> --> B\n", "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT", 2, 2);
  }

  @Test
  void preservesCommentsAndPhysicalLocationsInsideMultilineQuotedLabels() throws Exception {
    JsonNode model = model("flowchart TD\nA[\"one\n%% still label\ntwo\"] --> B\n");

    assertThat(model.at("/nodes/0/label").asText()).isEqualTo("one\n%% still label\ntwo");
    assertRejectedText(
        "flowchart TD\nA[\"one\n<script>unsafe</script>\"] --> B\n",
        "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT",
        3,
        1);
  }

  @Test
  void importsSolidUndirectedEdgesWithKindsLabelsOrderingAndOneCaveatWarning() throws Exception {
    var result =
        engine.importSource("flowchart TD\nA --- B -- solid label --- C -->|directed| D\n");

    assertThat(result.value().relationships())
        .extracting(relationship -> relationship.type())
        .containsExactly("generic.edge", "generic.edge", "generic.link");
    assertThat(result.value().relationships())
        .extracting(relationship -> relationship.label())
        .containsExactly("", "solid label", "directed");
    assertThat(result.diagnostics())
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_MERMAID_HINT_IGNORED");
    assertThat(result.diagnostics().get(0).message())
        .contains("default-arrowhead", "marker_end: none");
  }

  @Test
  void rejectsBareStructuralKeywordsAsNodeIdentifiersButPermitsThemInLabels() throws Exception {
    assertRejectedText("flowchart TD\nstyle\n", "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT", 2, 1);
    assertRejectedText(
        "flowchart TD\nstyle[false accept]\n", "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT", 2, 1);
    assertThat(model("flowchart TD\nA[style] --> B[direction]\n").path("nodes")).hasSize(2);
  }

  @Test
  void nestedSubgraphsBecomeLayoutOnlyGroupsWithDescendantMembership() throws Exception {
    JsonNode model =
        model(
            """
            flowchart TD
            subgraph outer[Outer]
              A
              subgraph inner[Inner]
                B --> C
              end
            end
            """);

    assertThat(model.at("/plugins/generic-graph/views/0/groups")).hasSize(2);
    assertThat(textValues(model.at("/plugins/generic-graph/views/0/groups/0/members")))
        .containsExactly("A", "B", "C");
    assertThat(textValues(model.at("/plugins/generic-graph/views/0/groups/1/members")))
        .containsExactly("B", "C");
  }

  @Test
  void rejectsEveryOutOfSubsetFamilyAtomically() {
    assertRejectedText("flowchart TD\nA -.-> B\n", "DEDIREN_MERMAID_UNSUPPORTED_EDGE", 2, 3);
    assertRejectedText("flowchart TD\nA ==> B\n", "DEDIREN_MERMAID_UNSUPPORTED_EDGE", 2, 3);
    assertRejectedText("flowchart TD\nA <--> B\n", "DEDIREN_MERMAID_UNSUPPORTED_EDGE", 2, 3);
    assertRejectedText("flowchart TD\nA ~~~ B\n", "DEDIREN_MERMAID_UNSUPPORTED_EDGE", 2, 3);
    assertRejectedText("flowchart TD\nA --x B\n", "DEDIREN_MERMAID_UNSUPPORTED_EDGE", 2, 3);
    assertRejectedText("flowchart TD\nA --o B\n", "DEDIREN_MERMAID_UNSUPPORTED_EDGE", 2, 3);
    assertRejectedText("flowchart TD\nA x-- B\n", "DEDIREN_MERMAID_UNSUPPORTED_EDGE", 2, 3);
    assertRejectedText("flowchart TD\nA o-- B\n", "DEDIREN_MERMAID_UNSUPPORTED_EDGE", 2, 3);
    assertRejectedText(
        "flowchart TD\nA@{ img: 'x' }\n", "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT", 2, 2);
    assertRejectedText(
        "flowchart TD\naccTitle: unsafe\n", "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT", 2, 1);
    assertRejectedText(
        "flowchart TD\nhref A \"https://example.invalid\"\n",
        "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT",
        2,
        1);
    assertRejectedText(
        "flowchart TD\nA[https://example.invalid]\n",
        "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT",
        2,
        3);
    assertRejectedText(
        "flowchart TD\nsubgraph My Group\nend\n", "DEDIREN_MERMAID_SYNTAX_INVALID", 2, 10);
    assertRejectedText("flowchart-elk TD\nA --> B\n", "DEDIREN_MERMAID_UNSUPPORTED_DIAGRAM", 1, 1);
  }

  @Test
  void malformedGrammarBranchesPublishExactSyntaxLocations() {
    assertRejectedText("flowchart TD\nend\n", "DEDIREN_MERMAID_SYNTAX_INVALID", 2, 1);
    assertRejectedText("flowchart TD\nsubgraph\n", "DEDIREN_MERMAID_SYNTAX_INVALID", 2, 9);
    assertRejectedText("flowchart TD\ndirection LR\n", "DEDIREN_MERMAID_SYNTAX_INVALID", 2, 1);
    assertRejectedText("flowchart ZZ\n", "DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT", 1, 11);
    assertRejectedText("flowchart TD\nA -->|x B\n", "DEDIREN_MERMAID_SYNTAX_INVALID", 2, 6);
    assertRejectedText("flowchart TD\nA[unterminated\n", "DEDIREN_MERMAID_SYNTAX_INVALID", 2, 2);
    assertRejectedText("flowchart TD\nA[\"unterminated]\n", "DEDIREN_MERMAID_SYNTAX_INVALID", 2, 3);
  }

  @Test
  void parserNeverLeaksPartialOutputForMalformedFuzzSeeds() {
    String[] seeds = {
      "",
      "graph",
      "flowchart TD\nA-->",
      "flowchart TD\nsubgraph A\nend\nend",
      "flowchart TD\nA[\u0000]"
    };
    for (String seed : seeds) {
      assertThatThrownBy(() -> engine.importSource(seed))
          .isInstanceOf(EngineException.class)
          .satisfies(
              error -> {
                EngineException expected = (EngineException) error;
                assertThat(expected.exitCode()).isEqualTo(2);
                assertThat(expected.diagnostics()).hasSize(1);
              });
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

  private static String flowchartWithEdges(int count) {
    return "flowchart TD\n" + "A --> B\n".repeat(count);
  }

  private static String flowchartWithHints(int count) {
    return "flowchart TD\nA\n" + "style A fill:#fff\n".repeat(count - 1);
  }

  private static String nestedSubgraphs(int depth) {
    return "flowchart TD\n" + "subgraph g\n".repeat(depth) + "A\n" + "end\n".repeat(depth);
  }

  private static String commentAtBytes(int bytes) {
    String prefix = "flowchart TD\n%%";
    return prefix + "x".repeat(bytes - prefix.length());
  }

  private JsonNode model(String source) throws Exception {
    SourceDocument document = engine.importSource(source).value();
    return JsonSupport.objectMapper().valueToTree(document);
  }

  private void assertDirection(String source, String direction) throws Exception {
    assertThat(
            model(source)
                .at("/plugins/generic-graph/views/0/layout_preferences/direction")
                .asText())
        .isEqualTo(direction);
  }

  private static List<String> textValues(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }

  private void assertRejectedText(String source, String code, int line, int column) {
    assertThatThrownBy(() -> engine.importSource(source))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              EngineException engineError = (EngineException) error;
              assertThat(engineError.exitCode()).isEqualTo(2);
              assertThat(engineError.diagnostics()).hasSize(1);
              assertThat(engineError.diagnostics().get(0).code()).isEqualTo(code);
              assertThat(engineError.diagnostics().get(0).path())
                  .isEqualTo("line " + line + ", column " + column);
            });
  }
}

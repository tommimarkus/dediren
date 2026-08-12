package dev.dediren.plugins.dotimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.engine.EngineException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DotParserTest {

  @Test
  void parsesEveryValidFixtureWithoutError() throws Exception {
    for (String name :
        List.of(
            "valid-basic.dot",
            "valid-chains.dot",
            "valid-clusters.dot",
            "valid-quoted-ids.dot",
            "valid-rankdir.dot",
            "valid-strict.dot",
            "valid-undirected.dot",
            "valid-attr-defaults.dot")) {
      DotDocument document = DotParser.parse(fixture(name));
      assertThat(document).describedAs(name).isNotNull();
    }
  }

  @Test
  void parsesStrictDigraphHeaderAndUndirectedGraphHeader() throws Exception {
    DotDocument strictDigraph = DotParser.parse(fixture("valid-strict.dot"));
    assertThat(strictDigraph.strict()).isTrue();
    assertThat(strictDigraph.directed()).isTrue();

    DotDocument undirected = DotParser.parse(fixture("valid-undirected.dot"));
    assertThat(undirected.strict()).isFalse();
    assertThat(undirected.directed()).isFalse();
  }

  @Test
  void parsesNodeAndEdgeStatementsFromTheBasicFixture() throws Exception {
    DotDocument document = DotParser.parse(fixture("valid-basic.dot"));

    assertThat(document.id()).isEqualTo("SimpleSystem");
    List<DotNodeStatement> nodes = nodeStatements(document.statements());
    assertThat(nodes)
        .extracting(DotNodeStatement::id)
        .containsExactly("user", "service", "database");
    assertThat(nodes.get(0).attributes()).containsEntry("label", "User");

    List<DotEdgeStatement> edges = edgeStatements(document.statements());
    assertThat(edges).hasSize(2);
    assertThat(edges.get(0).endpoints()).containsExactly("user", "service");
    assertThat(edges.get(1).endpoints()).containsExactly("service", "database");
  }

  @Test
  void parsesAnEdgeChainAsOneStatementWithEveryEndpoint() throws Exception {
    DotDocument document = DotParser.parse(fixture("valid-chains.dot"));

    List<DotEdgeStatement> edges = edgeStatements(document.statements());
    assertThat(edges).hasSize(3);
    assertThat(edges.get(0).endpoints()).containsExactly("producer", "processor", "consumer");
    assertThat(edges.get(1).endpoints()).containsExactly("producer", "analytics");
    assertThat(edges.get(2).endpoints()).containsExactly("consumer", "analytics");
  }

  @Test
  void parsesClusterAndNonClusterSubgraphsNestedArbitrarily() throws Exception {
    DotDocument document = DotParser.parse(fixture("valid-clusters.dot"));

    List<DotSubgraphStatement> topLevel = subgraphStatements(document.statements());
    assertThat(topLevel).hasSize(1);
    DotSubgraphStatement backend = topLevel.get(0);
    assertThat(backend.id()).isEqualTo("cluster_backend");
    assertThat(backend.cluster()).isTrue();

    List<DotSubgraphStatement> nested = subgraphStatements(backend.statements());
    assertThat(nested).hasSize(1);
    DotSubgraphStatement data = nested.get(0);
    assertThat(data.id()).isEqualTo("cluster_data");
    assertThat(data.cluster()).isTrue();
    assertThat(nodeStatements(data.statements()))
        .extracting(DotNodeStatement::id)
        .containsExactly("primary", "replica");
  }

  @Test
  void parsesQuotedIdentifiersWithSpacesPunctuationAndNonAsciiAcrossAllCommentStyles()
      throws Exception {
    DotDocument document = DotParser.parse(fixture("valid-quoted-ids.dot"));

    assertThat(nodeStatements(document.statements()))
        .extracting(DotNodeStatement::id)
        .containsExactly("Client Application", "API-Gateway/v2", "数据库");
  }

  @Test
  void resolvesNodeEdgeAndGraphDefaultsLiveAtEachStatementsDeclarationPointScopedToItsSubgraph()
      throws Exception {
    DotDocument document = DotParser.parse(fixture("valid-attr-defaults.dot"));

    assertThat(document.attributes()).containsEntry("fontname", "Arial");

    List<DotStatement> top = document.statements();
    DotNodeStatement startNode = nodeStatement(top, "startNode");
    assertThat(startNode.attributes()).containsEntry("shape", "box").containsEntry("color", "blue");

    DotSubgraphStatement middle = subgraphStatements(top).get(0);
    assertThat(middle.id()).isEqualTo("cluster_middle");
    DotNodeStatement midNode = nodeStatement(middle.statements(), "midNode");
    assertThat(midNode.attributes()).containsEntry("shape", "box").containsEntry("color", "red");

    DotNodeStatement endNode = nodeStatement(top, "endNode");
    assertThat(endNode.attributes())
        .containsEntry("shape", "ellipse")
        .containsEntry("color", "blue");

    for (DotEdgeStatement edge : edgeStatements(top)) {
      assertThat(edge.attributes()).containsEntry("color", "green");
    }
  }

  @Test
  void reportsUnsupportedConstructsFromFixturesInsteadOfSilentlyDroppingThem() {
    assertRejectedCode(
        fixtureUnchecked("unsupported-html-label.dot"), "DEDIREN_DOT_UNSUPPORTED_CONSTRUCT");
    assertRejectedCode(
        fixtureUnchecked("unsupported-ports.dot"), "DEDIREN_DOT_UNSUPPORTED_CONSTRUCT");
    assertRejectedCode(
        fixtureUnchecked("unsupported-subgraph-edge.dot"), "DEDIREN_DOT_UNSUPPORTED_CONSTRUCT");
  }

  @Test
  void reportsTheFixturesUnclosedBraceAsASyntaxErrorLocatedAtEndOfFile() throws Exception {
    assertThatThrownBy(() -> DotParser.parse(fixture("invalid-syntax.dot")))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              EngineException engineError = (EngineException) error;
              assertThat(engineError.exitCode()).isEqualTo(2);
              assertThat(engineError.diagnostics()).hasSize(1);
              assertThat(engineError.diagnostics().get(0).code())
                  .isEqualTo("DEDIREN_DOT_SYNTAX_INVALID");
              assertThat(engineError.diagnostics().get(0).path()).isEqualTo("line 7, column 1");
            });
  }

  @Test
  void reportsOriginalMalformedGrammarWithExactSyntaxLocations() {
    assertRejectedText("digraph G { a -> ; }", "DEDIREN_DOT_SYNTAX_INVALID", 1, 18);
    assertRejectedText("digraph G { a [color=] }", "DEDIREN_DOT_SYNTAX_INVALID", 1, 22);
    assertRejectedText("digraph G { a [color red] }", "DEDIREN_DOT_SYNTAX_INVALID", 1, 22);
    assertRejectedText("digraph G { a -> b }}", "DEDIREN_DOT_SYNTAX_INVALID", 1, 21);
    assertRejectedText("notagraph G { a -> b }", "DEDIREN_DOT_SYNTAX_INVALID", 1, 1);
  }

  @Test
  void rejectsAnEdgeOperatorThatDoesNotMatchTheDeclaredGraphType() {
    assertRejectedText("digraph G { a -- b; }", "DEDIREN_DOT_SYNTAX_INVALID", 1, 15);
    assertRejectedText("graph G { a -> b; }", "DEDIREN_DOT_SYNTAX_INVALID", 1, 13);
  }

  @Test
  void reportsAnHtmlLikeAttributeValueRegardlessOfWhichAttributeCarriesIt() {
    assertRejectedText(
        "digraph G { a [tooltip=<x>]; }", "DEDIREN_DOT_UNSUPPORTED_CONSTRUCT", 1, 24);
  }

  @Test
  void reportsAPortOnAPlainNodeStatementNotOnlyOnAnEdgeEndpoint() {
    assertRejectedText("digraph G { a:n; }", "DEDIREN_DOT_UNSUPPORTED_CONSTRUCT", 1, 14);
  }

  @Test
  void reportsANamedSubgraphUsedAsAnEdgeEndpointAsUnsupported() {
    assertRejectedText(
        "digraph G { subgraph s { a; } -> b; }", "DEDIREN_DOT_UNSUPPORTED_CONSTRUCT", 1, 31);
  }

  @Test
  void acceptsBoundaryLimitsAndRejectsTheFirstValueAboveEachLimit() throws Exception {
    assertThat(DotParser.parse(dotWithStatements(199_999))).isNotNull();
    assertThat(DotParser.parse(dotWithStatements(200_000))).isNotNull();
    assertLimit(dotWithStatements(200_001), "DEDIREN_DOT_STATEMENT_LIMIT_EXCEEDED");

    assertThat(DotParser.parse(dotWithEdges(99_997))).isNotNull();
    assertThat(DotParser.parse(dotWithEdges(99_998))).isNotNull();
    assertLimit(dotWithEdges(99_999), "DEDIREN_DOT_ELEMENT_LIMIT_EXCEEDED");

    assertThat(DotParser.parse(nestedSubgraphs(255))).isNotNull();
    assertThat(DotParser.parse(nestedSubgraphs(256))).isNotNull();
    assertLimit(nestedSubgraphs(257), "DEDIREN_DOT_NESTING_LIMIT_EXCEEDED");

    assertThat(DotParser.parse(dotWithLabelBytes(65_535))).isNotNull();
    assertThat(DotParser.parse(dotWithLabelBytes(65_536))).isNotNull();
    assertLimit(dotWithLabelBytes(65_537), "DEDIREN_DOT_TOKEN_LIMIT_EXCEEDED");

    assertThat(DotParser.parse(dotAtBytes(64 * 1024 * 1024 - 1))).isNotNull();
    assertThat(DotParser.parse(dotAtBytes(64 * 1024 * 1024))).isNotNull();
    assertLimit(dotAtBytes(64 * 1024 * 1024 + 1), "DEDIREN_DOT_INPUT_TOO_LARGE");
  }

  /**
   * The element ceiling has to bound ONE statement too, not just a document made of many. An edge
   * chain is a single statement whose length the statement ceiling never constrains, so if hops
   * were counted only after the chain was complete, a crafted {@code a->a->a->...} would be bounded
   * by the 64 MiB input ceiling rather than by the 100000 elements this ceiling advertises.
   */
  @Test
  void boundsOneLongEdgeChainByTheElementCeilingNotOnlyManySeparateStatements() throws Exception {
    assertThat(DotParser.parse(singleChain(99_998))).isNotNull();
    assertLimit(singleChain(99_999), "DEDIREN_DOT_ELEMENT_LIMIT_EXCEEDED");
  }

  @Test
  void parserNeverLeaksPartialOutputForMalformedSeeds() {
    String[] seeds = {"", "digraph", "digraph G {", "digraph G { a -> }", "digraph G { a [x= ] }"};
    for (String seed : seeds) {
      assertThatThrownBy(() -> DotParser.parse(seed))
          .isInstanceOf(EngineException.class)
          .satisfies(
              error -> {
                EngineException expected = (EngineException) error;
                assertThat(expected.exitCode()).isEqualTo(2);
                assertThat(expected.diagnostics()).hasSize(1);
              });
    }
  }

  private static String dotWithStatements(int count) {
    return "digraph G{" + "n;".repeat(count) + "}";
  }

  private static String dotWithEdges(int count) {
    return "digraph G{" + "a->b;".repeat(count) + "}";
  }

  /** One statement, {@code hops} edges, and only two distinct node ids. */
  private static String singleChain(int hops) {
    return "digraph G{a" + "->b->a".repeat(hops / 2) + (hops % 2 == 1 ? "->b" : "") + ";}";
  }

  private static String nestedSubgraphs(int depth) {
    return "digraph G{" + "subgraph s{".repeat(depth) + "n;" + "}".repeat(depth) + "}";
  }

  private static String dotWithLabelBytes(int bytes) {
    return "digraph G{a[label=\"" + "x".repeat(bytes) + "\"];}";
  }

  private static String dotAtBytes(int bytes) {
    String prefix = "digraph G{//";
    String suffix = "\n}";
    int padding = bytes - prefix.length() - suffix.length();
    return prefix + "x".repeat(Math.max(0, padding)) + suffix;
  }

  private static List<DotNodeStatement> nodeStatements(List<DotStatement> statements) {
    return statements.stream()
        .filter(DotNodeStatement.class::isInstance)
        .map(DotNodeStatement.class::cast)
        .toList();
  }

  private static List<DotEdgeStatement> edgeStatements(List<DotStatement> statements) {
    return statements.stream()
        .filter(DotEdgeStatement.class::isInstance)
        .map(DotEdgeStatement.class::cast)
        .toList();
  }

  private static List<DotSubgraphStatement> subgraphStatements(List<DotStatement> statements) {
    return statements.stream()
        .filter(DotSubgraphStatement.class::isInstance)
        .map(DotSubgraphStatement.class::cast)
        .toList();
  }

  private static DotNodeStatement nodeStatement(List<DotStatement> statements, String id) {
    return nodeStatements(statements).stream()
        .filter(node -> node.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no node statement for id " + id));
  }

  private static String fixture(String name) throws Exception {
    return Files.readString(Path.of("..", "..", "fixtures", "dot", name));
  }

  private static String fixtureUnchecked(String name) {
    try {
      return fixture(name);
    } catch (Exception exception) {
      throw new RuntimeException(exception);
    }
  }

  private void assertRejectedCode(String source, String code) {
    assertThatThrownBy(() -> DotParser.parse(source))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              EngineException engineError = (EngineException) error;
              assertThat(engineError.exitCode()).isEqualTo(2);
              assertThat(engineError.diagnostics()).hasSize(1);
              assertThat(engineError.diagnostics().get(0).code()).isEqualTo(code);
            });
  }

  private void assertRejectedText(String source, String code, int line, int column) {
    assertThatThrownBy(() -> DotParser.parse(source))
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

  private void assertLimit(String source, String code) {
    assertThatThrownBy(() -> DotParser.parse(source))
        .isInstanceOf(EngineException.class)
        .satisfies(
            error -> {
              EngineException engineError = (EngineException) error;
              var diagnostic = engineError.diagnostics().get(0);
              assertThat(diagnostic.code()).isEqualTo(code);
              assertThat(diagnostic.path()).isEqualTo("$");
            });
  }
}

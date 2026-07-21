package dev.dediren.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.analysis.QueryResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Unit tests for the {@link ModelQuery} vocabulary, calling {@code dependents}/{@code
 * orphans}/{@code viewCoverage} directly and asserting the returned {@link QueryResult} against
 * hand-computed expected records. Each test also pins the two sibling result blocks to {@code null}
 * so the kind-selection routing is exercised, not only the block under test.
 */
class ModelQueryTest {

  @Test
  void dependentsOfANodeWithNoIncidentEdgesIsEmpty() {
    QueryResult result =
        ModelQuery.dependents(
            doc(
                List.of(node("iso"), node("a"), node("b")),
                List.of(rel("r1", "generic.calls", "a", "b", "calls"))),
            "iso");

    assertThat(result.queryResultSchemaVersion())
        .isEqualTo(ContractVersions.QUERY_RESULT_SCHEMA_VERSION);
    assertThat(result.kind()).isEqualTo("dependents");
    assertThat(result.orphans()).isNull();
    assertThat(result.viewCoverage()).isNull();
    assertThat(result.dependents())
        .isEqualTo(new QueryResult.Dependents("iso", List.of(), List.of()));
  }

  @Test
  void dependentsReportsInboundAndOutboundSortedByRelationshipId() {
    QueryResult result =
        ModelQuery.dependents(
            doc(
                List.of(node("api"), node("client"), node("web"), node("db"), node("x"), node("y")),
                List.of(
                    rel("r-in-2", "calls", "client", "api", "c1"),
                    rel("r-in-1", "reads", "web", "api", "c2"),
                    rel("r-out", "writes", "api", "db", "c3"),
                    rel("r-other", "calls", "x", "y", "c4"))),
            "api");

    assertThat(result.kind()).isEqualTo("dependents");
    assertThat(result.orphans()).isNull();
    assertThat(result.viewCoverage()).isNull();
    assertThat(result.dependents())
        .isEqualTo(
            new QueryResult.Dependents(
                "api",
                List.of(
                    new QueryResult.Dependents.Edge("r-in-1", "reads", "web"),
                    new QueryResult.Dependents.Edge("r-in-2", "calls", "client")),
                List.of(new QueryResult.Dependents.Edge("r-out", "writes", "db"))));
  }

  @Test
  void orphansReportsNodesWithNoIncidentRelationships() {
    QueryResult result =
        ModelQuery.orphans(
            doc(
                List.of(node("a"), node("b"), node("c")),
                List.of(rel("r1", "generic.calls", "a", "b", "calls"))));

    assertThat(result.kind()).isEqualTo("orphans");
    assertThat(result.dependents()).isNull();
    assertThat(result.viewCoverage()).isNull();
    // No views present, so every node is a view orphan; only "c" has no relationship.
    assertThat(result.orphans())
        .isEqualTo(new QueryResult.Orphans(List.of("c"), List.of("a", "b", "c")));
  }

  @Test
  void orphansSeparatesRelationshipOrphansFromViewOrphans() {
    QueryResult result =
        ModelQuery.orphans(
            docWithViews(
                List.of(node("a"), node("b")),
                List.of(rel("r1", "generic.calls", "a", "b", "calls")),
                view("v", "V", List.of("a"), List.of("r1"))));

    assertThat(result.dependents()).isNull();
    assertThat(result.viewCoverage()).isNull();
    // Both nodes have a relationship (no relationship orphans); "b" is in no view.
    assertThat(result.orphans()).isEqualTo(new QueryResult.Orphans(List.of(), List.of("b")));
  }

  @Test
  void viewCoverageCountsMembershipAndNamesUncoveredNodes() {
    QueryResult result =
        ModelQuery.viewCoverage(
            docWithViews(
                List.of(node("a"), node("b"), node("c")),
                List.of(
                    rel("r1", "generic.calls", "a", "b", "c1"),
                    rel("r2", "generic.calls", "b", "c", "c2")),
                view("v1", "V1", List.of("a", "b"), List.of("r1")),
                view("v2", "V2", List.of("a"), List.of())));

    assertThat(result.kind()).isEqualTo("view-coverage");
    assertThat(result.dependents()).isNull();
    assertThat(result.orphans()).isNull();
    // Views sorted by id; "a" covered by both views, "c" covered by none.
    assertThat(result.viewCoverage())
        .isEqualTo(
            new QueryResult.ViewCoverage(
                List.of(
                    new QueryResult.ViewCoverage.ViewStat("v1", 2, 1),
                    new QueryResult.ViewCoverage.ViewStat("v2", 1, 0)),
                3,
                2,
                List.of("c")));
  }

  @Test
  void viewCoverageWithNoViewsMarksEveryNodeUncovered() {
    QueryResult result = ModelQuery.viewCoverage(doc(List.of(node("a"), node("b")), List.of()));

    assertThat(result.kind()).isEqualTo("view-coverage");
    assertThat(result.dependents()).isNull();
    assertThat(result.orphans()).isNull();
    assertThat(result.viewCoverage())
        .isEqualTo(new QueryResult.ViewCoverage(List.of(), 2, 0, List.of("a", "b")));
  }

  // --- helpers -------------------------------------------------------------

  private static SourceNode node(String id) {
    return new SourceNode(id, "generic.component", id, Map.of());
  }

  private static SourceRelationship rel(
      String id, String type, String source, String target, String label) {
    return new SourceRelationship(id, type, source, target, label, Map.of());
  }

  private static GenericGraphView view(
      String id, String label, List<String> nodes, List<String> relationships) {
    return new GenericGraphView(id, label, null, nodes, relationships, null, null);
  }

  private static SourceDocument doc(
      List<SourceNode> nodes, List<SourceRelationship> relationships) {
    return new SourceDocument(
        ContractVersions.MODEL_SCHEMA_VERSION, null, null, nodes, relationships, Map.of());
  }

  private static SourceDocument docWithViews(
      List<SourceNode> nodes, List<SourceRelationship> relationships, GenericGraphView... views) {
    JsonNode pluginData =
        JsonSupport.readTree(
            JsonSupport.writeValueAsString(new GenericGraphPluginData(null, List.of(views))));
    return new SourceDocument(
        ContractVersions.MODEL_SCHEMA_VERSION,
        null,
        null,
        nodes,
        relationships,
        Map.of("generic-graph", pluginData));
  }
}

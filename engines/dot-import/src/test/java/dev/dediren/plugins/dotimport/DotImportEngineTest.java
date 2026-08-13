package dev.dediren.plugins.dotimport;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.SourceDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DotImportEngineTest {
  private final DotImportEngine engine = new DotImportEngine();

  @Test
  void publishesTheEngineIdExactlyAsDot() {
    assertThat(engine.id()).isEqualTo("dot");
  }

  @Test
  void importsADirectedGraphIntoTheGenericModelContract() throws Exception {
    JsonNode model = model(fixture("valid-basic.dot"));

    assertThat(model.path("model_schema_version").asText()).isEqualTo("model.schema.v1");
    assertThat(model.path("nodes")).hasSize(3);
    assertThat(model.path("nodes").get(0).path("type").asText()).isEqualTo("generic.node");
    assertThat(model.path("nodes").get(0).path("label").asText()).isEqualTo("User");
    assertThat(model.path("relationships")).hasSize(2);
    assertThat(model.path("relationships").get(0).path("type").asText()).isEqualTo("generic.link");
    assertThat(model.at("/plugins/generic-graph/semantic_profile").asText())
        .isEqualTo("generic-graph");
    assertThat(model.at("/plugins/generic-graph/views/0/id").asText()).isEqualTo("main");
  }

  @Test
  void mapsUndirectedEdgesToGenericEdgeAndWarnsAboutDefaultArrowheadRendering() throws Exception {
    var result = engine.importSource(fixture("valid-undirected.dot"));
    JsonNode model = JsonSupport.objectMapper().valueToTree(result.value());

    assertThat(model.path("relationships").get(0).path("type").asText()).isEqualTo("generic.edge");
    assertThat(result.diagnostics())
        .extracting(d -> d.code())
        .containsExactly("DEDIREN_DOT_HINT_IGNORED");
    assertThat(result.diagnostics().get(0).message()).contains("arrowheads", "marker_end: none");
  }

  /**
   * Discarded attributes and undirectedness each contribute one half of the single hint message,
   * and the fixtures only ever exercise one half at a time. This is the fourth branch: both halves
   * present, joined by "; " into one warning rather than emitted as two diagnostics.
   */
  @Test
  void joinsDiscardedAttributesAndTheUndirectedWarningIntoOneHint() throws Exception {
    var result = engine.importSource("graph G {\nbgcolor=white;\na -- b;\n}\n");

    assertThat(result.diagnostics())
        .extracting(d -> d.code())
        .containsExactly("DEDIREN_DOT_HINT_IGNORED");
    assertThat(result.diagnostics().get(0).message())
        .contains("bgcolor (1)")
        .contains("; ")
        .contains("marker_end: none");
  }

  @Test
  void mergesRepeatedNodeMentionsWithTheLatestMentionWinningPerKey() throws Exception {
    JsonNode model =
        model(
            """
            digraph {
              a [color=red];
              a [shape=box, color=blue];
              a -> b;
            }
            """);

    assertThat(model.at("/nodes/0/id").asText()).isEqualTo("a");
    assertThat(model.at("/nodes/0/properties/dot/attributes/color").asText()).isEqualTo("blue");
    assertThat(model.at("/nodes/0/properties/dot/attributes/shape").asText()).isEqualTo("box");
  }

  @Test
  void mapsCommaSeparatedNodesInDeclarationOrderAndMergesLaterMentions() throws Exception {
    JsonNode model =
        model(
            "digraph { node [color=red]; a, b [shape=diamond]; "
                + "a [color=blue]; subgraph cluster_group { c, d; } }");

    List<String> ids = new ArrayList<>();
    model.path("nodes").forEach(node -> ids.add(node.path("id").asText()));
    assertThat(ids).containsExactly("a", "b", "c", "d");
    assertThat(model.at("/nodes/0/properties/dot/attributes/color").asText()).isEqualTo("blue");
    assertThat(model.at("/nodes/0/properties/dot/attributes/shape").asText()).isEqualTo("diamond");
    assertThat(model.at("/nodes/1/properties/dot/attributes/color").asText()).isEqualTo("red");
    assertThat(model.at("/nodes/1/properties/dot/attributes/shape").asText()).isEqualTo("diamond");
    assertThat(textValues(model.at("/plugins/generic-graph/views/0/groups/0/members")))
        .containsExactly("c", "d");
  }

  @Test
  void mapsRankdirToLayoutPreferencesDirection() throws Exception {
    assertThat(
            model(fixture("valid-rankdir.dot"))
                .at("/plugins/generic-graph/views/0/layout_preferences/direction")
                .asText())
        .isEqualTo("right");
    assertDirection("digraph { rankdir=TB; a -> b; }", "down");
    assertDirection("digraph { rankdir=RL; a -> b; }", "left");
    assertDirection("digraph { rankdir=BT; a -> b; }", "up");
  }

  @Test
  void flattensClustersAtAnyNestingDepthToLayoutOnlyGroupsWithDirectMembersOnly() throws Exception {
    JsonNode model = model(fixture("valid-clusters.dot"));

    assertThat(model.at("/plugins/generic-graph/views/0/groups")).hasSize(2);
    assertThat(model.at("/plugins/generic-graph/views/0/groups/0/id").asText())
        .isEqualTo("cluster_backend");
    assertThat(model.at("/plugins/generic-graph/views/0/groups/0/role").asText())
        .isEqualTo("layout-only");
    assertThat(model.at("/plugins/generic-graph/views/0/groups/0/label").asText())
        .isEqualTo("Backend");
    assertThat(textValues(model.at("/plugins/generic-graph/views/0/groups/0/members")))
        .containsExactly("api", "cache");
    assertThat(model.at("/plugins/generic-graph/views/0/groups/1/id").asText())
        .isEqualTo("cluster_data");
    assertThat(textValues(model.at("/plugins/generic-graph/views/0/groups/1/members")))
        .containsExactly("primary", "replica");
  }

  @Test
  void strictGraphsDeduplicateParallelEdgesBetweenTheSameOrderedEndpointPair() throws Exception {
    JsonNode model = model(fixture("valid-strict.dot"));

    assertThat(model.path("relationships")).hasSize(2);
    assertThat(model.at("/relationships/0/source").asText()).isEqualTo("task1");
    assertThat(model.at("/relationships/0/target").asText()).isEqualTo("task2");
    assertThat(model.at("/relationships/1/source").asText()).isEqualTo("task2");
    assertThat(model.at("/relationships/1/target").asText()).isEqualTo("task3");
  }

  @Test
  void expandsEdgeChainsIntoAdjacentPairEdges() throws Exception {
    JsonNode model = model(fixture("valid-chains.dot"));

    assertThat(model.path("relationships")).hasSize(4);
  }

  @Test
  void normalizesIllegalIdsAndRecordsTheOriginalOnlyWhenChanged() throws Exception {
    JsonNode model = model(fixture("valid-quoted-ids.dot"));

    List<String> ids = new ArrayList<>();
    model.path("nodes").forEach(node -> ids.add(node.path("id").asText()));
    assertThat(ids)
        .containsExactly("Client-Application", "API-Gateway-v2", "node--u6570-u636e-u5e93");
    assertThat(model.at("/nodes/0/properties/dot/original_id").asText())
        .isEqualTo("Client Application");
    assertThat(model.at("/nodes/1/properties/dot/original_id").asText())
        .isEqualTo("API-Gateway/v2");
    assertThat(model.at("/nodes/2/properties/dot/original_id").asText()).isEqualTo("数据库");
    assertThat(model.at("/nodes/2/label").asText()).isEqualTo("数据库");
  }

  @Test
  void discardsGraphAndSubgraphAttributesWithNoContractHomeIntoOneAggregatedHint()
      throws Exception {
    var result = engine.importSource(fixture("valid-attr-defaults.dot"));

    assertThat(result.diagnostics())
        .extracting(d -> d.code())
        .containsExactly("DEDIREN_DOT_HINT_IGNORED");
    assertThat(result.diagnostics().get(0).message()).contains("fontname (1)");
    assertThat(result.diagnostics().get(0).message()).doesNotContain("arrowheads");

    JsonNode model = JsonSupport.objectMapper().valueToTree(result.value());
    JsonNode startNode = nodeById(model, "startNode");
    assertThat(startNode.path("properties").path("dot").path("attributes").path("shape").asText())
        .isEqualTo("box");
    assertThat(startNode.path("properties").path("dot").path("attributes").path("color").asText())
        .isEqualTo("blue");
    JsonNode midNode = nodeById(model, "midNode");
    assertThat(midNode.path("properties").path("dot").path("attributes").path("color").asText())
        .isEqualTo("red");
  }

  private static JsonNode nodeById(JsonNode model, String id) {
    for (JsonNode node : model.path("nodes")) {
      if (node.path("id").asText().equals(id)) {
        return node;
      }
    }
    throw new AssertionError("node not found: " + id);
  }

  private static String fixture(String name) throws Exception {
    return Files.readString(Path.of("..", "..", "fixtures", "dot", name));
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
}

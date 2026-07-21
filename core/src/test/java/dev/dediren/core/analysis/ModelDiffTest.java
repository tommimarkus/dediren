package dev.dediren.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.analysis.DiffResult;
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
import tools.jackson.databind.node.StringNode;

/**
 * Unit tests for {@link ModelDiff#diff}, calling it directly on hand-authored models and asserting
 * the returned {@link DiffResult} against hand-computed expected records — no round-trip through
 * the SUT. Every assertion pins whole {@code EntityChanges}/{@code ViewChanges} records so the
 * added/removed/changed routing is fully exercised, not just the bucket under test.
 */
class ModelDiffTest {

  @Test
  void diffOfTwoEmptyModelsIsAllEmpty() {
    SourceDocument empty = doc(List.of(), List.of());

    DiffResult result = ModelDiff.diff(empty, empty);

    assertThat(result.diffResultSchemaVersion())
        .isEqualTo(ContractVersions.DIFF_RESULT_SCHEMA_VERSION);
    assertThat(result.nodes()).isEqualTo(emptyChanges());
    assertThat(result.relationships()).isEqualTo(emptyChanges());
    assertThat(result.views())
        .isEqualTo(new DiffResult.ViewChanges(List.of(), List.of(), List.of()));
  }

  @Test
  void diffReportsAddedRemovedAndChangedNodesInDistinctBuckets() {
    DiffResult result =
        ModelDiff.diff(
            doc(
                List.of(node("a", "generic.component", "A"), node("b", "generic.component", "B")),
                List.of()),
            doc(
                List.of(node("a", "generic.component", "A2"), node("c", "generic.component", "C")),
                List.of()));

    assertThat(result.nodes())
        .isEqualTo(
            new DiffResult.EntityChanges(
                List.of(new DiffResult.EntityRef("c", "generic.component", "C")),
                List.of(new DiffResult.EntityRef("b", "generic.component", "B")),
                List.of(
                    new DiffResult.ChangedEntity(
                        "a",
                        List.of(new DiffResult.FieldChange("label", text("A"), text("A2")))))));
    assertThat(result.relationships()).isEqualTo(emptyChanges());
  }

  @Test
  void changedNodeFieldsAreOrderedAlphabeticallyByFieldName() {
    DiffResult result =
        ModelDiff.diff(
            doc(List.of(node("a", "t1", "L1", Map.of("tier", text("silver")))), List.of()),
            doc(List.of(node("a", "t2", "L2", Map.of("tier", text("gold")))), List.of()));

    // TreeMap ordering: "label" < "properties.tier" < "type".
    assertThat(result.nodes().changed())
        .containsExactly(
            new DiffResult.ChangedEntity(
                "a",
                List.of(
                    new DiffResult.FieldChange("label", text("L1"), text("L2")),
                    new DiffResult.FieldChange("properties.tier", text("silver"), text("gold")),
                    new DiffResult.FieldChange("type", text("t1"), text("t2")))));
    assertThat(result.nodes().added()).isEmpty();
    assertThat(result.nodes().removed()).isEmpty();
  }

  @Test
  void relationshipOnlyPropertyChangeIsReportedUnderRelationships() {
    List<SourceNode> nodes =
        List.of(node("a", "generic.component", "A"), node("b", "generic.component", "B"));

    DiffResult result =
        ModelDiff.diff(
            doc(nodes, List.of(rel("r1", "generic.calls", "a", "b", "calls", prop("weight", "1")))),
            doc(
                nodes,
                List.of(rel("r1", "generic.calls", "a", "b", "calls", prop("weight", "2")))));

    assertThat(result.nodes()).isEqualTo(emptyChanges());
    assertThat(result.relationships())
        .isEqualTo(
            new DiffResult.EntityChanges(
                List.of(),
                List.of(),
                List.of(
                    new DiffResult.ChangedEntity(
                        "r1",
                        List.of(
                            new DiffResult.FieldChange(
                                "properties.weight", text("1"), text("2")))))));
  }

  @Test
  void diffReportsAddedRemovedAndChangedRelationships() {
    List<SourceNode> nodes =
        List.of(
            node("a", "generic.component", "A"),
            node("b", "generic.component", "B"),
            node("c", "generic.component", "C"),
            node("d", "generic.component", "D"));

    DiffResult result =
        ModelDiff.diff(
            doc(
                nodes,
                List.of(
                    rel("r1", "generic.calls", "a", "b", "calls"),
                    rel("r3", "generic.calls", "a", "d", "calls"))),
            doc(
                nodes,
                List.of(
                    rel("r1", "generic.calls", "a", "c", "calls"),
                    rel("r2", "generic.calls", "a", "b", "calls"))));

    assertThat(result.relationships())
        .isEqualTo(
            new DiffResult.EntityChanges(
                List.of(new DiffResult.EntityRef("r2", "generic.calls", "calls")),
                List.of(new DiffResult.EntityRef("r3", "generic.calls", "calls")),
                List.of(
                    new DiffResult.ChangedEntity(
                        "r1",
                        List.of(new DiffResult.FieldChange("target", text("b"), text("c")))))));
    assertThat(result.nodes()).isEqualTo(emptyChanges());
  }

  @Test
  void duplicateNodeAddedToAViewCollapsesToASingleMembershipChange() {
    List<SourceNode> nodes =
        List.of(node("a", "generic.component", "A"), node("b", "generic.component", "B"));

    DiffResult result =
        ModelDiff.diff(
            docWithViews(nodes, List.of(), view("main", "Main", List.of("a"), List.of())),
            docWithViews(
                nodes, List.of(), view("main", "Main", List.of("a", "b", "b"), List.of())));

    assertThat(result.nodes()).isEqualTo(emptyChanges());
    assertThat(result.views())
        .isEqualTo(
            new DiffResult.ViewChanges(
                List.of(),
                List.of(),
                List.of(
                    new DiffResult.ChangedView(
                        "main", List.of("b"), List.of(), List.of(), List.of()))));
  }

  @Test
  void diffReportsAddedAndRemovedViews() {
    List<SourceNode> nodes = List.of(node("a", "generic.component", "A"));

    DiffResult result =
        ModelDiff.diff(
            docWithViews(nodes, List.of(), view("v1", "V1", List.of("a"), List.of())),
            docWithViews(nodes, List.of(), view("v2", "V2", List.of("a"), List.of())));

    assertThat(result.views())
        .isEqualTo(new DiffResult.ViewChanges(List.of("v2"), List.of("v1"), List.of()));
  }

  // --- helpers -------------------------------------------------------------

  private static DiffResult.EntityChanges emptyChanges() {
    return new DiffResult.EntityChanges(List.of(), List.of(), List.of());
  }

  private static JsonNode text(String value) {
    return StringNode.valueOf(value);
  }

  private static Map<String, JsonNode> prop(String key, String value) {
    return Map.of(key, text(value));
  }

  private static SourceNode node(String id, String type, String label) {
    return new SourceNode(id, type, label, Map.of());
  }

  private static SourceNode node(
      String id, String type, String label, Map<String, JsonNode> properties) {
    return new SourceNode(id, type, label, properties);
  }

  private static SourceRelationship rel(
      String id, String type, String source, String target, String label) {
    return new SourceRelationship(id, type, source, target, label, Map.of());
  }

  private static SourceRelationship rel(
      String id,
      String type,
      String source,
      String target,
      String label,
      Map<String, JsonNode> properties) {
    return new SourceRelationship(id, type, source, target, label, properties);
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

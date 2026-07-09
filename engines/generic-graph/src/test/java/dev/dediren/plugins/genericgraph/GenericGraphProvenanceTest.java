package dev.dediren.plugins.genericgraph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class GenericGraphProvenanceTest {
  @Test
  void projectionStampsSourcePointerBySourceIndex() throws Exception {
    // Reuses the same fixture-loading pattern as GenericGraphProjectionTest: no
    // module-local ProjectionFixtures helper exists, so the source document and view are
    // built inline the same way.
    SourceDocument source = source("fixtures/source/valid-basic.json");
    GenericGraphPluginData pluginData = pluginData(source);
    GenericGraphView view = pluginData.views().getFirst();
    String profile = GenericGraphProjection.sourceSemanticProfile(pluginData);

    LayoutRequest request = GenericGraphProjection.projectLayoutRequest(source, view, profile);

    // Every projected node/edge carries a JSON-Pointer into the source arrays.
    assertThat(request.nodes())
        .allSatisfy(n -> assertThat(n.sourcePointer()).startsWith("/nodes/"));
    assertThat(request.edges())
        .allSatisfy(e -> assertThat(e.sourcePointer()).startsWith("/relationships/"));
  }

  @Test
  void projectionStampsExactSourceIndexWhenViewOrderDivergesFromSourceOrder() throws Exception {
    // valid-basic.json's view selects nodes/relationships in exactly source.nodes() order, so
    // a startsWith("/nodes/") assertion there cannot tell correct source-indexed provenance
    // (pointer index = position in source.nodes()) apart from a view-indexed bug (pointer
    // index = position in the view's own id list): both land on "/nodes/0", "/nodes/1" etc.
    // Here the view selects a subset of source nodes/relationships in an order that diverges
    // from the source array order, so the two indexing schemes disagree and only the correct
    // one produces the exact pointer asserted below.
    SourceNode nodeA = new SourceNode("node-a", "generic.actor", "A", Map.of());
    SourceNode nodeB = new SourceNode("node-b", "generic.component", "B", Map.of());
    SourceNode nodeC = new SourceNode("node-c", "generic.component", "C", Map.of());
    SourceRelationship relP =
        new SourceRelationship("edge-p", "generic.calls", "node-a", "node-c", "p", Map.of());
    SourceRelationship relQ =
        new SourceRelationship("edge-q", "generic.calls", "node-c", "node-a", "q", Map.of());
    SourceRelationship relR =
        new SourceRelationship("edge-r", "generic.calls", "node-a", "node-c", "r", Map.of());

    SourceDocument source =
        new SourceDocument(
            "model.schema.v1",
            List.of(),
            List.of(),
            List.of(nodeA, nodeB, nodeC),
            List.of(relP, relQ, relR),
            Map.of());

    // View omits "node-b" (index 1) entirely and lists the remaining two nodes in the reverse
    // of their source order: source index 2 ("node-c") first, then source index 0 ("node-a").
    // Same shape for relationships: omits "edge-q" (index 1), lists index 2 ("edge-r") before
    // index 0 ("edge-p").
    GenericGraphView view =
        new GenericGraphView(
            "diverging-view",
            "Diverging View",
            GenericGraphViewKind.GENERIC,
            List.of("node-c", "node-a"),
            List.of("edge-r", "edge-p"),
            null,
            List.of());

    LayoutRequest request =
        GenericGraphProjection.projectLayoutRequest(source, view, "generic-graph");

    assertThat(request.nodes()).hasSize(2);
    assertThat(request.edges()).hasSize(2);
    // Correct source-indexed provenance: "node-c" is source.nodes()[2], "node-a" is
    // source.nodes()[0] -- independent of their (reversed) position in the view's node list.
    // A view-indexed bug would instead stamp "node-c" (first in the view list) as "/nodes/0"
    // and "node-a" (second in the view list) as "/nodes/1".
    assertThat(nodeById(request, "node-c").sourcePointer()).isEqualTo("/nodes/2");
    assertThat(nodeById(request, "node-a").sourcePointer()).isEqualTo("/nodes/0");
    // Same invariant for relationships: "edge-r" is source.relationships()[2], "edge-p" is
    // source.relationships()[0]. A view-indexed bug would stamp "edge-r" (first in the view
    // list) as "/relationships/0" and "edge-p" (second in the view list) as "/relationships/1".
    assertThat(edgeById(request, "edge-r").sourcePointer()).isEqualTo("/relationships/2");
    assertThat(edgeById(request, "edge-p").sourcePointer()).isEqualTo("/relationships/0");
  }

  @Test
  void projectionSkipsSourceOnlySequenceFragmentsButKeepsSourceIndexedPointersForSurvivors()
      throws Exception {
    // Pins isSourceOnlySequenceFragment's drop path: a UML-sequence view containing
    // CombinedFragment/InteractionOperand nodes (source-only chrome with no on-canvas scene
    // node) must drop exactly those nodes from the projected layout request while every
    // surviving node keeps its true source.nodes() index. The skip-eligible nodes are placed
    // both before and between the surviving lifelines in source order, and the view lists all
    // five nodes in an order that also diverges from source order, so neither a view-indexed
    // bug nor a "count only emitted nodes" compaction bug could coincidentally reproduce the
    // correct pointers for both survivors.
    SourceNode cfLead = new SourceNode("cf-lead", "CombinedFragment", "Lead Fragment", Map.of());
    SourceNode lifelineA = new SourceNode("lifeline-a", "Lifeline", "Lifeline A", Map.of());
    SourceNode cfMid = new SourceNode("cf-mid", "CombinedFragment", "Mid Fragment", Map.of());
    SourceNode lifelineB = new SourceNode("lifeline-b", "Lifeline", "Lifeline B", Map.of());
    SourceNode opTail =
        new SourceNode("op-tail", "InteractionOperand", "Trailing Operand", Map.of());

    SourceDocument source =
        new SourceDocument(
            "model.schema.v1",
            List.of(),
            List.of(),
            // Source order: cf-lead(0), lifeline-a(1), cf-mid(2), lifeline-b(3), op-tail(4).
            List.of(cfLead, lifelineA, cfMid, lifelineB, opTail),
            List.of(),
            Map.of());

    GenericGraphView view =
        new GenericGraphView(
            "sequence-skip-view",
            "Sequence Skip View",
            GenericGraphViewKind.UML_SEQUENCE,
            // View order diverges from source order: "lifeline-b" (source index 3) is listed
            // before "cf-lead" (source index 0) and "lifeline-a" (source index 1).
            List.of("lifeline-b", "cf-lead", "lifeline-a", "cf-mid", "op-tail"),
            List.of(),
            null,
            List.of());

    LayoutRequest request = GenericGraphProjection.projectLayoutRequest(source, view, "uml");

    // The CombinedFragment/InteractionOperand nodes never become scene nodes.
    assertThat(request.nodes())
        .extracting(LayoutNode::id)
        .containsExactly("lifeline-b", "lifeline-a");
    // "lifeline-b" is source.nodes()[3]. A view-indexed bug would stamp its position in the
    // view's node list (0) instead; a compacted "count only survivors emitted so far" bug would
    // stamp 0 as well, since it is the first surviving node emitted.
    assertThat(nodeById(request, "lifeline-b").sourcePointer()).isEqualTo("/nodes/3");
    // "lifeline-a" is source.nodes()[1]. A view-indexed bug would stamp 2 (its view-list
    // position) instead.
    assertThat(nodeById(request, "lifeline-a").sourcePointer()).isEqualTo("/nodes/1");
  }

  private static LayoutNode nodeById(LayoutRequest request, String id) {
    return request.nodes().stream()
        .filter(node -> node.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no projected node with id " + id));
  }

  private static LayoutEdge edgeById(LayoutRequest request, String id) {
    return request.edges().stream()
        .filter(edge -> edge.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no projected edge with id " + id));
  }

  private static SourceDocument source(String fixturePath) throws Exception {
    return JsonSupport.objectMapper()
        .readValue(Files.readString(workspaceRoot().resolve(fixturePath)), SourceDocument.class);
  }

  private static GenericGraphPluginData pluginData(SourceDocument source) throws Exception {
    JsonNode pluginValue = source.plugins().get("generic-graph");
    return JsonSupport.objectMapper().treeToValue(pluginValue, GenericGraphPluginData.class);
  }

  private static Path workspaceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("schemas/model.schema.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from user.dir");
  }
}

package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end counterpart to {@code SvgIdsTest}: the minted ids and their {@code url(#…)} references
 * have to stay in agreement in the emitted document, not just in the minter.
 *
 * <p>Deterministic version of the two defects {@code RenderFuzzTest} found by sampling. A duplicate
 * layout id used to emit two markers with one id, so the second edge painted the first edge's
 * arrowhead; an id containing {@code )} used to emit {@code url(#marker-end-a)b)}, whose url token
 * closes at the {@code )} and resolves nothing, silently dropping the marker. Both are contract-
 * valid input: {@code layout-result.schema.json} constrains neither id charset nor uniqueness.
 */
class SvgIdSafetyTest {

  @Test
  void duplicateAndUnsafeLayoutIdsStillYieldUniqueResolvableSvgIds() throws Exception {
    String svg = RenderTestSupport.render(adversarialIdLayout());

    SvgAudit.auditStructure(svg);
    assertThat(svg)
        .contains("id=\"marker-end-dup\"")
        .contains("id=\"marker-end-dup-2\"")
        .contains("id=\"marker-end-a_b\"");
    // The second duplicate references its own marker, not its neighbour's.
    assertThat(svg).contains("marker-end=\"url(#marker-end-dup-2)\"");
    // No reference is left pointing at an id the ')' truncated away.
    assertThat(svg).doesNotContain("url(#marker-end-a)");
  }

  private static ObjectNode adversarialIdLayout() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v2");
    layout.put("view_id", "svg-id-safety");

    ArrayNode nodes = layout.putArray("nodes");
    node(nodes, "n1", 40, 40);
    node(nodes, "n2", 320, 40);

    ArrayNode edges = layout.putArray("edges");
    edge(edges, "dup");
    edge(edges, "dup");
    edge(edges, "a)b");

    layout.putArray("groups");
    layout.putArray("warnings");
    input.set("policy", RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json"));
    return input;
  }

  private static void node(ArrayNode nodes, String id, double x, double y) {
    ObjectNode node = nodes.addObject();
    node.put("id", id).put("source_id", id).put("projection_id", id);
    node.put("x", x).put("y", y).put("width", 160).put("height", 60);
    node.put("label", id);
  }

  private static void edge(ArrayNode edges, String id) {
    ObjectNode edge = edges.addObject();
    edge.put("id", id).put("source", "n1").put("target", "n2");
    edge.put("source_id", id).put("projection_id", id);
    ArrayNode points = edge.putArray("points");
    points.addObject().put("x", 200).put("y", 70);
    points.addObject().put("x", 320).put("y", 70);
    edge.put("label", "");
  }
}

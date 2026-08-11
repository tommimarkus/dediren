package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End adornments — multiplicities and role names — are UML association notation, and the property
 * keys that carry them ({@code source_role}, {@code target_multiplicity}, …) are UML's. A generic
 * or ArchiMate view whose edges happen to carry a property of that name must not acquire UML
 * association adornments from it; only a view whose render metadata declares the UML profile does.
 */
class EdgeEndAdornmentProfileTest {

  @Test
  void genericProfileEdgeWithRolePropertyGetsNoEndAdornments() throws Exception {
    String svg =
        RenderTestSupport.render(
            adornmentInput("generic-graph", "fixtures/render-policy/default-svg.json"));

    assertThat(svg).doesNotContain("data-dediren-edge-adornment");
    assertThat(svg).doesNotContain(">customer<").doesNotContain(">0..*<");
  }

  @Test
  void umlProfileEdgeWithRolePropertyStillGetsEndAdornments() throws Exception {
    String svg =
        RenderTestSupport.render(adornmentInput("uml", "fixtures/render-policy/uml-svg.json"));

    assertThat(svg)
        .contains("data-dediren-edge-adornment=\"source_role\"")
        .contains("data-dediren-edge-adornment=\"target_multiplicity\"")
        .contains(">customer<")
        .contains(">0..*<");
  }

  /** The same layout and the same edge properties under two profiles — only the profile differs. */
  private static ObjectNode adornmentInput(String semanticProfile, String policyPath)
      throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v2");
    layout.put("view_id", "end-adornment-profile");

    ArrayNode nodes = layout.putArray("nodes");
    node(nodes, "class-customer", 40);
    node(nodes, "class-order", 360);

    ArrayNode edges = layout.putArray("edges");
    ObjectNode edge = edges.addObject();
    edge.put("id", "customer-places-order")
        .put("source", "class-customer")
        .put("target", "class-order")
        .put("source_id", "customer-places-order")
        .put("projection_id", "customer-places-order");
    ArrayNode points = edge.putArray("points");
    points.addObject().put("x", 200).put("y", 70);
    points.addObject().put("x", 360).put("y", 70);
    edge.put("label", "places");

    layout.putArray("groups");
    layout.putArray("warnings");

    ObjectNode metadata = input.putObject("render_metadata");
    metadata.put("render_metadata_schema_version", "render-metadata.schema.v1");
    metadata.put("semantic_profile", semanticProfile);
    ObjectNode metadataNodes = metadata.putObject("nodes");
    selector(metadataNodes, "class-customer");
    selector(metadataNodes, "class-order");
    ObjectNode metadataEdge = metadata.putObject("edges").putObject("customer-places-order");
    metadataEdge.put("type", "Association").put("source_id", "customer-places-order");
    metadataEdge
        .putObject("properties")
        .put("source_multiplicity", "1")
        .put("target_multiplicity", "0..*")
        .put("source_role", "customer")
        .put("target_role", "orders");

    input.set("policy", RenderTestSupport.fixtureJson(policyPath));
    return input;
  }

  private static void node(ArrayNode nodes, String id, double x) {
    ObjectNode node = nodes.addObject();
    node.put("id", id).put("source_id", id).put("projection_id", id);
    node.put("x", x).put("y", 40).put("width", 160).put("height", 60);
    node.put("label", id);
  }

  private static void selector(ObjectNode nodes, String id) {
    nodes.putObject(id).put("type", "Class").put("source_id", id);
  }
}

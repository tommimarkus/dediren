package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Pins that exact cubic route extrema, rather than a flattened sample, own every viewBox edge. */
class RouteBoundsTest {

  private static final double SERIALIZATION_PRECISION = 0.05;

  @Test
  void cubicInkTouchesAllFourMeasuredViewportEdges() throws Exception {
    ObjectNode input =
        RenderTestSupport.fixtureInput(
            "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json", null);
    ObjectNode layout = (ObjectNode) input.get("layout_result");
    layout.set("nodes", JsonSupport.objectMapper().createArrayNode());
    layout.set("groups", JsonSupport.objectMapper().createArrayNode());
    ArrayNode edges = JsonSupport.objectMapper().createArrayNode();
    layout.set("edges", edges);
    addCurve(edges, "top", 0, 100, 100, -200, 200, -200, 300, 100);
    addCurve(edges, "bottom", 0, 100, 100, 400, 200, 400, 300, 100);
    addCurve(edges, "left", 100, 0, -200, 100, -200, 200, 100, 300);
    addCurve(edges, "right", 100, 0, 400, 100, 400, 200, 100, 300);
    ((ObjectNode) input.get("policy"))
        .putObject("margin")
        .put("top", 0)
        .put("right", 0)
        .put("bottom", 0)
        .put("left", 0);

    Document document = SvgAudit.parse(RenderTestSupport.render(input));
    double[] viewBox = viewBox(document);

    // Each symmetric cubic reaches its scalar extremum at t=0.5: -125 or 325. The default
    // 1.5px stroke adds 0.75px. The root serializes the viewBox to one decimal place, so an
    // under-measurement as large as the old flattening tolerance cannot hide behind a one-sided
    // containment assertion.
    assertThat(viewBox[0]).isCloseTo(-125.75, within(SERIALIZATION_PRECISION));
    assertThat(viewBox[1]).isCloseTo(-125.75, within(SERIALIZATION_PRECISION));
    assertThat(viewBox[2]).isCloseTo(325.75, within(SERIALIZATION_PRECISION));
    assertThat(viewBox[3]).isCloseTo(325.75, within(SERIALIZATION_PRECISION));
  }

  @Test
  void jumpAdmissionIncludesAssociationEndAdornmentPaint() throws Exception {
    ObjectNode input = associationInput(false);
    var parsed =
        new SvgRenderEngine().parseInput(JsonSupport.objectMapper().writeValueAsBytes(input));
    var scene =
        SvgDocument.resolve(
            parsed.layoutResult(),
            parsed.renderMetadata(),
            JsonSupport.objectMapper()
                .treeToValue(parsed.policy(), dev.dediren.contracts.render.RenderPolicy.class));
    var owner = scene.edges().get(1);
    assertThat(owner.adornments()).hasSize(1);
    assertThat(owner.lineJumps()).as("the jump arc would paint through the source role").isEmpty();
  }

  @Test
  void cubicEndAdornmentUsesTheExactEndpointTangent() throws Exception {
    ObjectNode input = associationInput(true);
    var parsed =
        new SvgRenderEngine().parseInput(JsonSupport.objectMapper().writeValueAsBytes(input));
    var scene =
        SvgDocument.resolve(
            parsed.layoutResult(),
            parsed.renderMetadata(),
            JsonSupport.objectMapper()
                .treeToValue(parsed.policy(), dev.dediren.contracts.render.RenderPolicy.class));
    var label = scene.edges().get(0).adornments().get(0).adornment().label();
    assertThat(label.x()).isCloseTo(12.0, org.assertj.core.data.Offset.offset(0.000001));
    assertThat(label.y()).isCloseTo(20.76, org.assertj.core.data.Offset.offset(0.000001));
  }

  private static ObjectNode associationInput(boolean curve) throws Exception {
    ObjectNode input =
        RenderTestSupport.fixtureInput(
            "fixtures/layout-result/basic.json", "fixtures/render-policy/uml-svg.json", null);
    ObjectNode layout = (ObjectNode) input.get("layout_result");
    layout.putArray("nodes");
    layout.putArray("groups");
    ArrayNode edges = layout.putArray("edges");
    if (!curve) {
      ObjectNode crossing = edges.addObject();
      crossing
          .put("id", "crossing")
          .put("source", "c")
          .put("target", "d")
          .put("source_id", "crossing")
          .put("projection_id", "crossing")
          .put("label", "");
      crossing.putArray("routing_hints");
      ArrayNode points = crossing.putObject("route").put("kind", "polyline").putArray("points");
      points.addObject().put("x", 16).put("y", 50);
      points.addObject().put("x", 16).put("y", 150);
    }
    if (curve) {
      addCurve(edges, "owner", 0, 0, 0, 100, 100, 100, 100, 0);
    } else {
      ObjectNode owner = edges.addObject();
      owner
          .put("id", "owner")
          .put("source", "a")
          .put("target", "b")
          .put("source_id", "owner")
          .put("projection_id", "owner")
          .put("label", "");
      owner.putArray("routing_hints");
      ArrayNode points = owner.putObject("route").put("kind", "polyline").putArray("points");
      points.addObject().put("x", 0).put("y", 100);
      points.addObject().put("x", 100).put("y", 100);
    }
    ObjectNode metadata = input.putObject("render_metadata");
    metadata
        .put("render_metadata_schema_version", "render-metadata.schema.v1")
        .put("semantic_profile", "uml");
    metadata.putObject("nodes");
    metadata.putObject("groups");
    metadata
        .putObject("edges")
        .putObject("owner")
        .put("type", "Association")
        .put("source_id", "owner")
        .putObject("properties")
        .put("source_role", "source");
    return input;
  }

  private static void addCurve(
      ArrayNode edges,
      String id,
      double sx,
      double sy,
      double c1x,
      double c1y,
      double c2x,
      double c2y,
      double ex,
      double ey) {
    ObjectNode edge = edges.addObject();
    edge.put("id", id).put("source", id + "-s").put("target", id + "-t");
    edge.put("source_id", id).put("projection_id", id).put("label", "");
    edge.putArray("routing_hints");
    ObjectNode route = edge.putObject("route").put("kind", "cubic_bezier");
    route.putObject("start").put("x", sx).put("y", sy);
    ObjectNode segment = route.putArray("segments").addObject();
    segment.putObject("control1").put("x", c1x).put("y", c1y);
    segment.putObject("control2").put("x", c2x).put("y", c2y);
    segment.putObject("end").put("x", ex).put("y", ey);
  }

  private static double[] viewBox(Document document) {
    String[] values = document.getDocumentElement().getAttribute("viewBox").split("\\s+");
    double minX = Double.parseDouble(values[0]);
    double minY = Double.parseDouble(values[1]);
    return new double[] {
      minX, minY, minX + Double.parseDouble(values[2]), minY + Double.parseDouble(values[3])
    };
  }
}

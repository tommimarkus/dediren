package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.layout.PolylineRoute;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.plugins.render.svg.LabelBox;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ensures group headings use the already-reserved title band without being crossed by routes. */
class GroupTitlePlacementTest {

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"middle", "end"})
  void nonDefaultTitleAlignmentStillClearsRoutes(String alignment) throws Exception {
    LaidOutGroup group = new LaidOutGroup("g", "g", "g", null, 0, 0, 420, 180, List.of(), "Group");
    double crossingX = alignment.equals("middle") ? 210 : 400;
    LaidOutEdge edge =
        new LaidOutEdge(
            "e",
            "a",
            "b",
            "e",
            "e",
            List.of(),
            new PolylineRoute(List.of(new Point(crossingX, -30), new Point(crossingX, 80))),
            "");
    LayoutResult layout =
        new LayoutResult(
            "layout-result.schema.v3", "v", List.of(), List.of(edge), List.of(group), List.of());
    var policyJson =
        (tools.jackson.databind.node.ObjectNode)
            RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json");
    policyJson.withObject("/style/group").put("label_align", alignment);
    RenderPolicy policy = JsonSupport.objectMapper().treeToValue(policyJson, RenderPolicy.class);
    LabelBox title =
        SvgDocument.resolve(layout, null, policy).groups().getFirst().title().visibleBox();
    assertThat(title.overlaps(new LabelBox(crossingX - 6, -30, crossingX + 6, 80))).isFalse();
    assertThat(title.minX()).isGreaterThanOrEqualTo(8);
    assertThat(title.maxX()).isLessThanOrEqualTo(412);
  }

  @Test
  void groupTitleMovesWithinItsTitleBandWhenIncomingRoutesCrossItsPreferredAnchor()
      throws Exception {
    LaidOutGroup group =
        new LaidOutGroup(
            "orchestration",
            "orchestration",
            "orchestration",
            null,
            0.0,
            0.0,
            420.0,
            180.0,
            List.of(),
            "Orchestration & engines (tier 2)");
    LaidOutEdge incoming =
        new LaidOutEdge(
            "incoming",
            "outside",
            "inside",
            "incoming",
            "incoming",
            List.of(),
            new PolylineRoute(List.of(new Point(50.0, -30.0), new Point(50.0, 80.0))),
            "");
    LayoutResult layout =
        new LayoutResult(
            "layout-result.schema.v3",
            "group-title-clearance",
            List.of(),
            List.of(incoming),
            List.of(group),
            List.of());
    RenderPolicy policy =
        JsonSupport.objectMapper()
            .treeToValue(
                RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json"),
                RenderPolicy.class);

    PlacedScene scene = SvgDocument.resolve(layout, null, policy);
    LabelBox title = scene.groups().getFirst().title().visibleBox();
    LabelBox incomingRoute = new LabelBox(44.0, -30.0, 56.0, 80.0);

    assertThat(title.overlaps(incomingRoute))
        .as("the placed title must clear a route crossing the default eight-unit title inset")
        .isFalse();
    assertThat(title.minY()).isGreaterThanOrEqualTo(0.0);
    assertThat(title.maxY()).isLessThanOrEqualTo(32.0);
  }
}

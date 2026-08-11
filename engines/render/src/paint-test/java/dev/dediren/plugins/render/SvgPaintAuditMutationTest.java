package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SvgPaintAuditMutationTest {

  @Test
  void middleBaselineUsesPinnedFontXHeightRatherThanAlphabeticOrInkCenter() throws Exception {
    String middle =
        "<g data-dediren-node-id=\"middle\"><text x=\"30\" y=\"40\""
            + " dominant-baseline=\"middle\">Label</text></g>";
    String alphabetic =
        "<g data-dediren-node-id=\"alphabetic\"><text x=\"30\" y=\"40\">Label</text></g>";
    SvgPaintAudit.Report report = SvgPaintAudit.audit(svg(100, 80, middle + alphabetic));
    SvgPaintAudit.Bounds middleBounds = report.semanticBounds().get("node:middle");
    SvgPaintAudit.Bounds alphabeticBounds = report.semanticBounds().get("node:alphabetic");

    // SVG middle uses half the nominal font x-height below y. Liberation Sans' pinned OpenType
    // OS/2 table declares sxHeight=1082 at unitsPerEm=2048 (font SHA-256 is manifest-gated).
    double expectedShift = 14.0 * 1082.0 / 2048.0 / 2.0;
    assertThat(middleBounds.y() - alphabeticBounds.y()).isCloseTo(expectedShift, within(0.01));
    assertThat(middleBounds.y()).isNotEqualTo(40 - middleBounds.height() / 2.0);
  }

  @Test
  void textAnchorUsesChunkAdvanceAndSpacingPreservesAsymmetricGlyphOutlines() throws Exception {
    SvgPaintAudit.Bounds middle42 = textBounds("middle", 42);
    SvgPaintAudit.Bounds middle52 = textBounds("middle", 52);
    SvgPaintAudit.Bounds start52 = textBounds("start", 52);
    SvgPaintAudit.Bounds end52 = textBounds("end", 52);

    // SVG text-anchor translates the same text geometry by the declared chunk advance. With two
    // glyphs and lengthAdjust=spacing, adding 10 to textLength adds exactly 10 to their one gap.
    assertThat(middle52.width() - middle42.width()).isCloseTo(10, within(0.01));
    assertThat(middle42.x() - middle52.x()).isCloseTo(5, within(0.01));
    assertThat(middle52.x() - start52.x()).isCloseTo(-26, within(0.01));
    assertThat(end52.x() - start52.x()).isCloseTo(-52, within(0.01));
    assertThat(middle52.width()).isCloseTo(start52.width(), within(0.01));
    assertThat(end52.width()).isCloseTo(start52.width(), within(0.01));
  }

  @Test
  void rejectsNonFiniteAndZeroAreaPaintWithNearbyControls() throws Exception {
    assertRule(audit(node("zero", 10, 10, 0, 20, "")), "non_positive_paint", "node:zero");
    assertRule(
        SvgPaintAudit.audit(
            svg(100, 100, node("bad", 10, 10, 20, 20, "")).replace("x=\"10.0\"", "x=\"NaN\"")),
        "non_finite_geometry",
        "node:bad");

    assertThat(audit(node("good", 10, 10, 20, 20, "")).violations()).isEmpty();
  }

  @Test
  void detectsViewBoxEscapesForShapesLabelsMarkersAndFilters() throws Exception {
    assertRule(
        SvgPaintAudit.audit(svg(100, 100, node("shape", 90, 20, 20, 20, ""))),
        "viewbox_escape",
        "node:shape");
    assertThat(SvgPaintAudit.audit(svg(100, 100, node("shape", 75, 20, 20, 20, ""))).violations())
        .isEmpty();

    String labelBad =
        node("label", 10, 20, 40, 30, "<text x=\"98\" y=\"40\" fill=\"#000000\">clipped</text>");
    assertRule(SvgPaintAudit.audit(svg(100, 100, labelBad)), "viewbox_escape", "node:label");

    String marker =
        "<marker id=\"arrow\" markerWidth=\"10\" markerHeight=\"10\" refX=\"9\" refY=\"5\""
            + " orient=\"auto\"><path d=\"M 1 1 L 9 5 L 1 9\" fill=\"none\" stroke=\"#000000\""
            + " stroke-width=\"2\"/></marker>";
    String clippedArrow =
        "<g data-dediren-edge-id=\"arrow-edge\">"
            + marker
            + "<path d=\"M 10 70 L 99 70\" fill=\"none\" stroke=\"#000000\""
            + " marker-end=\"url(#arrow)\"/></g>";
    assertRule(
        SvgPaintAudit.audit(svg(100, 100, clippedArrow)), "viewbox_escape", "edge:arrow-edge");
    assertThat(
            SvgPaintAudit.audit(svg(100, 100, clippedArrow.replace("L 99 70", "L 88 70")))
                .violations())
        .isEmpty();

    String strokedLabel =
        "<g data-dediren-node-id=\"stroked-label\"><text x=\"1\" y=\"40\" fill=\"#000000\""
            + " stroke=\"#000000\" stroke-width=\"6\">I</text></g>";
    assertRule(
        SvgPaintAudit.audit(svg(100, 100, strokedLabel)), "viewbox_escape", "node:stroked-label");
    assertThat(
            SvgPaintAudit.audit(
                    svg(
                        100,
                        100,
                        strokedLabel.replace(" stroke=\"#000000\" stroke-width=\"6\"", "")))
                .violations())
        .isEmpty();
    assertThat(
            SvgPaintAudit.audit(svg(100, 100, strokedLabel.replace("x=\"1\"", "x=\"5\"")))
                .violations())
        .isEmpty();

    String filter =
        "<defs><filter id=\"blur\" x=\"-100%\" y=\"-100%\" width=\"300%\""
            + " height=\"300%\"><feGaussianBlur stdDeviation=\"5\"/></filter></defs>";
    String filtered =
        filter
            + "<g data-dediren-node-id=\"filtered\"><rect data-dediren-node-shape=\"rectangle\""
            + " x=\"2\" y=\"14\" width=\"12\" height=\"12\" fill=\"#000000\""
            + " filter=\"url(#blur)\"/></g>";
    assertRule(SvgPaintAudit.audit(svg(100, 100, filtered)), "viewbox_escape", "node:filtered");
    assertThat(
            SvgPaintAudit.audit(svg(100, 100, filtered.replace("x=\"2\"", "x=\"30\"")))
                .violations())
        .isEmpty();
  }

  @Test
  void filteredTextIsAdvisoryRatherThanAFalseGeometryMeasurement() throws Exception {
    String filteredText =
        "<defs><filter id=\"text-blur\"><feGaussianBlur stdDeviation=\"2\"/></filter></defs>"
            + "<g data-dediren-node-id=\"filtered-text\"><text x=\"20\" y=\"35\""
            + " filter=\"url(#text-blur)\">label</text></g>";

    SvgPaintAudit.Report report = SvgPaintAudit.audit(svg(100, 70, filteredText));

    assertThat(report.violations()).isEmpty();
    assertThat(report.advisories())
        .filteredOn(advisory -> advisory.code().equals("not_measurable"))
        .extracting(SvgPaintAudit.Violation::semanticIds)
        .contains(List.of("node:filtered-text"));
  }

  @Test
  void detectsInShapeLabelOverflowAndExemptsActorExternalLabels() throws Exception {
    String displaced =
        node("n", 10, 10, 60, 30, "<text x=\"82\" y=\"25\" fill=\"#000000\">moved</text>");
    assertRule(SvgPaintAudit.audit(svg(120, 80, displaced)), "node_label_overflow", "node:n");

    String centered =
        node(
            "n",
            10,
            10,
            60,
            30,
            "<text x=\"40\" y=\"25\" text-anchor=\"middle\" dominant-baseline=\"middle\""
                + " fill=\"#000000\">ok</text>");
    assertThat(SvgPaintAudit.audit(svg(120, 80, centered)).violations()).isEmpty();

    String actor =
        "<g data-dediren-node-id=\"actor\"><g data-dediren-node-shape=\"uml_actor\"><circle"
            + " cx=\"30\" cy=\"20\" r=\"8\" fill=\"#ffffff\" stroke=\"#000000\"/><path d=\"M 30 28"
            + " L 30 55 M 15 38 L 45 38\" fill=\"none\" stroke=\"#000000\"/></g><g"
            + " data-dediren-node-decorator=\"uml_actor\"><text x=\"30\" y=\"72\""
            + " text-anchor=\"middle\">Actor</text></g></g>";
    assertThat(SvgPaintAudit.audit(svg(100, 90, actor)).violations()).isEmpty();
  }

  @Test
  void detectsActualPaintedNodeOverlapAndAcceptsSeparatedOrSequenceStructures() throws Exception {
    String overlap = svg(140, 90, node("a", 10, 10, 50, 40, "") + node("b", 45, 20, 50, 40, ""));
    assertRule(SvgPaintAudit.audit(overlap), "node_paint_overlap", "node:a|node:b");

    String separated = svg(140, 90, node("a", 10, 10, 50, 40, "") + node("b", 75, 20, 50, 40, ""));
    assertThat(SvgPaintAudit.audit(separated).violations()).isEmpty();

    String sequence =
        svg(
            140,
            100,
            "<g data-dediren-node-id=\"interaction\" data-dediren-node-type=\"Interaction\""
                + " data-dediren-sequence-interaction=\"true\"><rect"
                + " data-dediren-node-shape=\"uml_interaction\" x=\"5\" y=\"5\" width=\"125\""
                + " height=\"85\" fill=\"#ffffff\" stroke=\"#000000\"/></g><g"
                + " data-dediren-node-id=\"life\" data-dediren-node-type=\"Lifeline\""
                + " data-dediren-sequence-lifeline=\"true\"><rect"
                + " data-dediren-node-shape=\"uml_lifeline\" x=\"45\" y=\"15\" width=\"40\""
                + " height=\"20\" fill=\"#ffffff\" stroke=\"#000000\"/><line"
                + " data-dediren-sequence-lifeline-stem=\"life\" x1=\"65\" y1=\"35\" x2=\"65\""
                + " y2=\"80\" stroke=\"#000000\"/></g>");
    assertThat(SvgPaintAudit.audit(sequence).violations()).isEmpty();

    String partiallyOverlappingInteraction =
        svg(
            140,
            100,
            "<g data-dediren-node-id=\"interaction\" data-dediren-node-type=\"Interaction\" data-dediren-sequence-interaction=\"true\"><rect data-dediren-node-shape=\"uml_interaction\" x=\"5\" y=\"5\" width=\"60\" height=\"85\" fill=\"#ffffff\" stroke=\"#000000\"/></g>"
                + "<g data-dediren-node-id=\"unrelated-life\" data-dediren-node-type=\"Lifeline\" data-dediren-sequence-lifeline=\"true\"><rect data-dediren-node-shape=\"uml_lifeline\" x=\"55\" y=\"15\" width=\"40\" height=\"25\" fill=\"#ffffff\" stroke=\"#000000\"/></g>");
    assertRule(
        SvgPaintAudit.audit(partiallyOverlappingInteraction),
        "node_paint_overlap",
        "node:interaction|node:unrelated-life");

    String collidingLifelines =
        svg(
            140,
            100,
            "<g data-dediren-node-id=\"life-a\" data-dediren-node-type=\"Lifeline\" data-dediren-sequence-lifeline=\"true\"><rect data-dediren-node-shape=\"uml_lifeline\" x=\"35\" y=\"15\" width=\"45\" height=\"25\" fill=\"#ffffff\" stroke=\"#000000\"/></g>"
                + "<g data-dediren-node-id=\"life-b\" data-dediren-node-type=\"Lifeline\" data-dediren-sequence-lifeline=\"true\"><rect data-dediren-node-shape=\"uml_lifeline\" x=\"60\" y=\"20\" width=\"45\" height=\"25\" fill=\"#ffffff\" stroke=\"#000000\"/></g>");
    assertRule(
        SvgPaintAudit.audit(collidingLifelines), "node_paint_overlap", "node:life-a|node:life-b");

    String actorLabel =
        "<g data-dediren-node-id=\"actor\"><g data-dediren-node-shape=\"uml_actor\">"
            + "<circle cx=\"20\" cy=\"20\" r=\"8\" fill=\"#ffffff\" stroke=\"#000000\"/>"
            + "</g><text x=\"58\" y=\"45\" text-anchor=\"middle\">Actor</text></g>";
    assertRule(
        SvgPaintAudit.audit(svg(130, 90, actorLabel + node("label-target", 45, 30, 35, 25, ""))),
        "node_paint_overlap",
        "node:actor|node:label-target");
    assertThat(
            SvgPaintAudit.audit(svg(130, 90, actorLabel + node("label-target", 85, 30, 35, 25, "")))
                .violations())
        .isEmpty();
  }

  @Test
  void negativeAndNonzeroViewBoxesKeepBatikMasksAndJdkTextInSvgUserSpace() throws Exception {
    String overlapping =
        svgWithViewBox(
            -50, 20, 100, 100, node("a", -40, 30, 30, 25, "") + node("b", -20, 40, 30, 25, ""));
    assertRule(SvgPaintAudit.audit(overlapping), "node_paint_overlap", "node:a|node:b");

    String legal =
        svgWithViewBox(
            -50,
            20,
            100,
            100,
            node("a", -40, 30, 25, 25, "")
                + node(
                    "b",
                    5,
                    40,
                    30,
                    25,
                    "<text x=\"20\" y=\"52\" text-anchor=\"middle\""
                        + " dominant-baseline=\"middle\">B</text>"));
    assertThat(SvgPaintAudit.audit(legal).violations()).isEmpty();
  }

  @Test
  void detectsGroupAndEdgeLabelCollisionsAndExemptsDuplicateHalo() throws Exception {
    String member = node("member", 10, 15, 45, 28, "");
    String badGroup =
        "<g data-dediren-group-id=\"g\"><rect x=\"2\" y=\"2\" width=\"115\" height=\"70\""
            + " fill=\"none\" stroke=\"#000000\"/><text x=\"15\" y=\"28\">Group</text></g>"
            + member;
    assertRule(
        SvgPaintAudit.audit(svg(130, 85, badGroup)),
        "group_label_member_collision",
        "group:g|node:member");
    assertThat(
            SvgPaintAudit.audit(svg(130, 85, badGroup.replace("y=\"28\"", "y=\"11\"")))
                .violations())
        .isEmpty();

    String edgeOnNode =
        edge("a", "M 5 75 L 125 75", "<text x=\"50\" y=\"35\">edge</text>")
            + node("n", 40, 20, 45, 30, "");
    assertRule(
        SvgPaintAudit.audit(svg(140, 90, edgeOnNode)),
        "edge_label_node_collision",
        "edge:a|node:n");

    String labels =
        edge("a", "M 5 20 L 125 20", "<text x=\"45\" y=\"55\">alpha</text>")
            + edge("b", "M 5 78 L 125 78", "<text x=\"50\" y=\"55\">beta</text>");
    assertRule(
        SvgPaintAudit.audit(svg(140, 90, labels)), "edge_label_label_collision", "edge:a|edge:b");

    String halo =
        edge(
            "halo",
            "M 5 20 L 125 20",
            "<text x=\"65\" y=\"55\" text-anchor=\"middle\" fill=\"none\" stroke=\"#ffffff\""
                + " stroke-width=\"2\">same</text><text x=\"65\" y=\"55\" text-anchor=\"middle\""
                + " fill=\"#000000\">same</text>");
    assertThat(SvgPaintAudit.audit(svg(140, 90, halo)).violations()).isEmpty();

    String displacedHalo =
        halo.replace(
            "<text x=\"65\" y=\"55\" text-anchor=\"middle\" fill=\"#000000\">",
            "<text x=\"68\" y=\"55\" text-anchor=\"middle\" fill=\"#000000\">");
    assertRule(
        SvgPaintAudit.audit(svg(140, 90, displacedHalo)),
        "edge_label_label_collision",
        "edge:halo");

    String accidentalSameEdgeLabels =
        edge(
            "same-edge",
            "M 5 20 L 125 20",
            "<text x=\"65\" y=\"55\" text-anchor=\"middle\">first</text>"
                + "<text x=\"65\" y=\"55\" text-anchor=\"middle\">other</text>");
    assertRule(
        SvgPaintAudit.audit(svg(140, 90, accidentalSameEdgeLabels)),
        "edge_label_label_collision",
        "edge:same-edge");
  }

  @Test
  void detectsRoutesThroughLabelsAndNonEndpointNodesWithProductionShapedEndpointControl()
      throws Exception {
    String nodes =
        node("source", 5, 30, 20, 20, "")
            + node("middle", 55, 30, 20, 20, "")
            + node("target", 105, 30, 20, 20, "");
    String throughNode = edge("e", "M 25 40 L 105 40", "") + nodes;
    SvgPaintAudit.Report nodeReport = SvgPaintAudit.audit(svg(140, 90, throughNode));
    assertRule(nodeReport, "edge_route_node_collision", "edge:e|node:middle");
    assertThat(nodeReport.violations())
        .noneMatch(
            violation ->
                violation.code().equals("edge_route_node_collision")
                    && (violation.semanticIds().contains("source")
                        || violation.semanticIds().contains("target")));

    String aroundNode = edge("e", "M 25 40 L 42 40 L 42 12 L 95 12 L 95 40 L 105 40", "") + nodes;
    assertThat(SvgPaintAudit.audit(svg(140, 90, aroundNode)).violations()).isEmpty();

    String routeThroughLabel =
        edge("route", "M 5 45 L 130 45", "")
            + node("labelled", 20, 5, 40, 20, "<text x=\"65\" y=\"45\">collision</text>");
    assertRule(
        SvgPaintAudit.audit(svg(140, 90, routeThroughLabel)),
        "edge_route_label_collision",
        "edge:route|node:labelled");
  }

  @Test
  void violationIsStructuredAndActionable() throws Exception {
    SvgPaintAudit.Violation violation =
        SvgPaintAudit.audit(svg(50, 50, node("n", 45, 10, 10, 10, ""))).violations().getFirst();

    assertThat(violation.code()).isNotBlank();
    assertThat(violation.semanticIds()).containsExactly("node:n");
    assertThat(violation.transformedBounds().width()).isPositive();
    assertThat(violation.observed()).isNotBlank();
    assertThat(violation.expected()).isNotBlank();
  }

  private static SvgPaintAudit.Report audit(String body) throws Exception {
    return SvgPaintAudit.audit(svg(100, 100, body));
  }

  private static String svg(int width, int height, String body) {
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\""
        + width
        + "\" height=\""
        + height
        + "\" viewBox=\"0 0 "
        + width
        + " "
        + height
        + "\"><rect width=\""
        + width
        + "\" height=\""
        + height
        + "\" fill=\"#ffffff\"/><g font-family=\"Liberation Sans\" font-size=\"14\">"
        + body
        + "</g></svg>";
  }

  private static String svgWithViewBox(
      double minimumX, double minimumY, int width, int height, String body) {
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\""
        + width
        + "\" height=\""
        + height
        + "\" viewBox=\""
        + minimumX
        + " "
        + minimumY
        + " "
        + width
        + " "
        + height
        + "\"><rect x=\""
        + minimumX
        + "\" y=\""
        + minimumY
        + "\" width=\""
        + width
        + "\" height=\""
        + height
        + "\" fill=\"#ffffff\"/><g font-family=\"Liberation Sans\" font-size=\"14\">"
        + body
        + "</g></svg>";
  }

  private static String node(
      String id, double x, double y, double width, double height, String extra) {
    return "<g data-dediren-node-id=\""
        + id
        + "\"><rect data-dediren-node-shape=\"rectangle\" x=\""
        + x
        + "\" y=\""
        + y
        + "\" width=\""
        + width
        + "\" height=\""
        + height
        + "\" fill=\"#ffffff\" stroke=\"#000000\"/>"
        + extra
        + "</g>";
  }

  private static String edge(String id, String path, String extra) {
    return "<g data-dediren-edge-id=\""
        + id
        + "\"><path d=\""
        + path
        + "\" fill=\"none\" stroke=\"#000000\"/>"
        + extra
        + "</g>";
  }

  private static void assertRule(
      SvgPaintAudit.Report report, String code, String combinedSemanticId) {
    List<String> expectedIds = List.of(combinedSemanticId.split("\\|", -1));
    assertThat(report.violations())
        .describedAs(
            "violations=%s bounds=%s advisories=%s",
            report.violations(), report.semanticBounds(), report.advisories())
        .filteredOn(violation -> violation.code().equals(code))
        .extracting(SvgPaintAudit.Violation::semanticIds)
        .contains(expectedIds);
  }

  private static org.assertj.core.data.Offset<Double> within(double value) {
    return org.assertj.core.data.Offset.offset(value);
  }

  private static SvgPaintAudit.Bounds textBounds(String anchor, double desiredAdvance)
      throws Exception {
    return SvgPaintAudit.audit(
            svg(
                140,
                80,
                "<g data-dediren-node-id=\"label\"><text x=\"70\" y=\"40\" text-anchor=\""
                    + anchor
                    + "\" textLength=\""
                    + desiredAdvance
                    + "\" lengthAdjust=\"spacing\">jA</text></g>"))
        .semanticBounds()
        .get("node:label");
  }
}

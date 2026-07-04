package dev.dediren.core.quality;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutQualityTest {
  @Test
  void overlappingNodesAreCounted() {
    var nodes = new ArrayList<LaidOutNode>();
    nodes.add(node("a", 0.0, 0.0));
    nodes.add(node("b", 50.0, 20.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()));

    assertThat(report.overlapCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
  }

  @Test
  void qualityWarningsAreEmittedPerNonzeroNonInformationalCountPointingIntoData() {
    var nodes = new ArrayList<LaidOutNode>();
    nodes.add(node("a", 0.0, 0.0));
    nodes.add(node("b", 50.0, 20.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()));
    var warnings = LayoutQuality.layoutQualityWarnings(report);

    assertThat(warnings)
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_QUALITY_WARNING");
    assertThat(warnings)
        .extracting(diagnostic -> diagnostic.severity())
        .containsOnly(DiagnosticSeverity.WARNING);
    assertThat(warnings.get(0).path()).isEqualTo("$.data.overlap_count");
    assertThat(warnings.get(0).message()).contains("overlap_count").contains("1");
  }

  @Test
  void cleanLayoutEmitsNoQualityWarnings() {
    var nodes = List.of(node("api", 0.0, 0.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()));

    assertThat(LayoutQuality.layoutQualityWarnings(report)).isEmpty();
  }

  @Test
  void informationalEdgeCrossingsAloneEmitNoQualityWarnings() {
    var nodes =
        List.of(
            node("a", 0.0, 0.0),
            node("b", 400.0, 400.0),
            node("c", 0.0, 400.0),
            node("d", 400.0, 0.0));
    var edges =
        List.of(
            edge("a-b", "a", "b", List.of(new Point(100.0, 80.0), new Point(400.0, 440.0))),
            edge("c-d", "c", "d", List.of(new Point(100.0, 440.0), new Point(400.0, 40.0))));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, edges, List.of()));

    assertThat(report.edgeCrossingCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("ok");
    assertThat(LayoutQuality.layoutQualityWarnings(report)).isEmpty();
  }

  @Test
  void routeDiagnosticsRejectSinglePointRoutes() {
    var nodes = List.of(node("source", 0.0, 0.0), node("target", 300.0, 0.0));
    var edges = List.of(edge("one-point", "source", "target", List.of(new Point(100.0, 40.0))));

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()));

    assertThat(diagnostics)
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_ROUTE_POINTS_INSUFFICIENT");
    assertThat(diagnostics.get(0).path()).isEqualTo("$.edges[0].points");
  }

  @Test
  void routeDiagnosticsReportEmptyRoutesAndEndpointMisses() {
    var nodes = new ArrayList<LaidOutNode>();
    nodes.add(node("source", 0.0, 0.0));
    nodes.add(node("target", 300.0, 0.0));
    var edges = new ArrayList<LaidOutEdge>();
    edges.add(edge("empty", "source", "target", List.of()));
    edges.add(
        edge(
            "misses-target",
            "source",
            "target",
            List.of(new Point(100.0, 40.0), new Point(250.0, 40.0))));

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()));

    assertThat(diagnostics)
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly(
            "DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY",
            "DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
    assertThat(diagnostics)
        .extracting(diagnostic -> diagnostic.severity())
        .containsOnly(DiagnosticSeverity.ERROR);
    assertThat(diagnostics.get(0).path()).isEqualTo("$.edges[0].points");
    assertThat(diagnostics.get(1).path()).isEqualTo("$.edges[1].points[-1]");
  }

  @Test
  void lifelineMessageEndpointsOnLifelineAxisAreAccepted() {
    var nodes = new ArrayList<LaidOutNode>();
    nodes.add(lifelineNode("customer", 100.0, 100.0, 140.0, 48.0));
    nodes.add(lifelineNode("service", 520.0, 104.0, 140.0, 48.0));
    var edges = new ArrayList<LaidOutEdge>();
    edges.add(
        edge(
            "m1",
            "customer",
            "service",
            List.of(new Point(240.0, 180.0), new Point(520.0, 180.0))));

    LayoutResult result = layoutResult(nodes, edges, List.of());

    assertThat(LayoutQuality.validateLayoutDiagnostics(result)).isEmpty();
    assertThat(LayoutQuality.validateLayout(result).status()).isEqualTo("ok");
  }

  @Test
  void lifelineMessageEndpointOffLifelineAxisIsStillRejected() {
    var nodes = new ArrayList<LaidOutNode>();
    nodes.add(lifelineNode("customer", 100.0, 100.0, 140.0, 48.0));
    nodes.add(lifelineNode("service", 520.0, 104.0, 140.0, 48.0));
    var edges = new ArrayList<LaidOutEdge>();
    edges.add(
        edge(
            "m1",
            "customer",
            "service",
            List.of(new Point(240.0, 180.0), new Point(400.0, 180.0))));

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()));

    assertThat(diagnostics)
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
    assertThat(diagnostics.get(0).path()).isEqualTo("$.edges[0].points[-1]");
  }

  @Test
  void ordinaryNodeEndpointOnVerticalAxisBelowNodeIsStillRejected() {
    var nodes = new ArrayList<LaidOutNode>();
    nodes.add(node("source", 0.0, 0.0));
    nodes.add(node("target", 300.0, 0.0));
    var edges = new ArrayList<LaidOutEdge>();
    edges.add(
        edge("m1", "source", "target", List.of(new Point(0.0, 40.0), new Point(300.0, 200.0))));

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()));

    assertThat(diagnostics)
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
    assertThat(diagnostics.get(0).path()).isEqualTo("$.edges[0].points[-1]");
  }

  @Test
  void routeAndBoundaryIssuesAreCounted() {
    var nodes = new ArrayList<LaidOutNode>();
    nodes.add(node("source", 0.0, 0.0));
    nodes.add(node("target", 500.0, 0.0));
    nodes.add(node("middle", 240.0, 20.0));
    var groups =
        List.of(
            new LaidOutGroup(
                "group",
                "group",
                "group",
                null,
                0.0,
                0.0,
                100.0,
                100.0,
                List.of("middle"),
                "Group"));
    var edges =
        List.of(
            edge(
                "crosses",
                "source",
                "target",
                List.of(new Point(100.0, 40.0), new Point(500.0, 40.0))));

    LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, edges, groups));

    assertThat(report.connectorThroughNodeCount()).isEqualTo(1);
    assertThat(report.groupBoundaryIssueCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
  }

  @Test
  void nestedGroupMembersAreCountedAsGroupBoundaryMembers() {
    var nodes = List.of(node("source", 0.0, 30.0), node("target", 200.0, 30.0));
    var edges =
        List.of(
            edge(
                "internal",
                "source",
                "target",
                List.of(new Point(100.0, 40.0), new Point(200.0, 40.0))));
    var groups =
        List.of(
            new LaidOutGroup(
                "outer",
                "outer",
                "outer",
                null,
                -30.0,
                -30.0,
                360.0,
                200.0,
                List.of("inner"),
                "Outer"),
            new LaidOutGroup(
                "inner",
                "inner",
                "inner",
                null,
                -10.0,
                0.0,
                320.0,
                140.0,
                List.of("source", "target"),
                "Inner"));

    LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, edges, groups));

    assertThat(report.groupBoundaryIssueCount()).isZero();
    assertThat(report.status()).isEqualTo("ok");
  }

  @Test
  void excessiveDetoursAndCloseParallelRoutesAreCounted() {
    var edges = new ArrayList<LaidOutEdge>();
    edges.add(
        edge(
            "detour",
            "detour-source",
            "detour-target",
            List.of(
                new Point(100.0, 40.0),
                new Point(100.0, 640.0),
                new Point(300.0, 640.0),
                new Point(300.0, 40.0))));
    edges.add(edge("primary", "a", "b", List.of(new Point(0.0, 0.0), new Point(200.0, 0.0))));
    edges.add(edge("too-close", "c", "d", List.of(new Point(0.0, 16.0), new Point(200.0, 16.0))));
    edges.add(edge("readable", "e", "f", List.of(new Point(0.0, 80.0), new Point(200.0, 80.0))));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()));

    assertThat(report.routeDetourCount()).isEqualTo(1);
    assertThat(report.routeCloseParallelCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
  }

  @Test
  void labeledEdgeTrappedInDenseParallelBandIsCountedAsDissociation() {
    // Three unrelated labeled edges running parallel 44px apart (ELK's edge-edge spacing band):
    // the middle edge cannot host a centered label without it landing on a neighbour's route,
    // which is exactly the render dissociation issue #31 describes. Only the trapped middle edge
    // (neighbours on both sides) is counted; the two outer edges have open space on one side.
    var edges =
        List.of(
            horizontalEdge("top", 200.0),
            horizontalEdge("middle", 244.0),
            horizontalEdge("bottom", 288.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()));

    assertThat(report.edgeLabelDissociationCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
  }

  @Test
  void dissociationEmitsAQualityWarningPointingIntoData() {
    var edges =
        List.of(
            horizontalEdge("top", 200.0),
            horizontalEdge("middle", 244.0),
            horizontalEdge("bottom", 288.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()));
    var warnings = LayoutQuality.layoutQualityWarnings(report);

    assertThat(warnings)
        .extracting(diagnostic -> diagnostic.path())
        .contains("$.data.edge_label_dissociation_count");
  }

  @Test
  void twoParallelLabeledEdgesAreNotDissociated() {
    // A pair has open space above and below to host both labels; dissociation needs a band of 3+.
    var edges = List.of(horizontalEdge("top", 200.0), horizontalEdge("bottom", 244.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()));

    assertThat(report.edgeLabelDissociationCount()).isZero();
    assertThat(report.status()).isEqualTo("ok");
  }

  @Test
  void wellSpacedParallelLabeledEdgesAreNotDissociated() {
    // 70px apart clears the band gap: each label fits between the routes without dissociating.
    var edges =
        List.of(
            horizontalEdge("top", 200.0),
            horizontalEdge("middle", 270.0),
            horizontalEdge("bottom", 340.0));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()))
                .edgeLabelDissociationCount())
        .isZero();
  }

  @Test
  void neighbourExactlyAtBandGapIsCounted() {
    // Band gap is inclusive (<= 52px): a middle edge whose neighbours sit exactly 52px away on
    // both sides is trapped. Pins the lower boundary so a narrowing of the gap constant is caught.
    var edges =
        List.of(
            horizontalEdge("top", 200.0),
            horizontalEdge("middle", 252.0),
            horizontalEdge("bottom", 304.0));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()))
                .edgeLabelDissociationCount())
        .isEqualTo(1);
  }

  @Test
  void neighbourJustBeyondBandGapIsNotCounted() {
    // 54px between routes clears the 52px band gap: the middle edge has no qualifying neighbour.
    // Pins the upper boundary so a widening of the gap constant is caught.
    var edges =
        List.of(
            horizontalEdge("top", 200.0),
            horizontalEdge("middle", 254.0),
            horizontalEdge("bottom", 308.0));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()))
                .edgeLabelDissociationCount())
        .isZero();
  }

  @Test
  void verticalTrappedBandIsCountedLikeHorizontal() {
    // The check is orientation-agnostic: a middle vertical run boxed in by parallel vertical
    // neighbours dissociates its label just as a horizontal band does.
    var edges =
        List.of(
            verticalEdge("left", 200.0),
            verticalEdge("middle", 244.0),
            verticalEdge("right", 288.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()));

    assertThat(report.edgeLabelDissociationCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
  }

  @Test
  void unlabeledParallelEdgesAreNotDissociated() {
    // No labels means no attribution to lose; the check applies only to labeled edges.
    var edges =
        List.of(
            unlabeledHorizontalEdge("top", 200.0),
            unlabeledHorizontalEdge("middle", 244.0),
            unlabeledHorizontalEdge("bottom", 288.0));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()))
                .edgeLabelDissociationCount())
        .isZero();
  }

  @Test
  void parallelEdgesSharingAnEndpointNodeAreNotDissociated() {
    // Edges fanning out from a shared node are visually grouped; a reader attributes them by their
    // shared origin, so they are excluded like the crossing and close-parallel checks.
    var edges =
        List.of(
            edge("a", "hub", "x", List.of(new Point(100.0, 200.0), new Point(500.0, 200.0))),
            edge("b", "hub", "y", List.of(new Point(100.0, 244.0), new Point(500.0, 244.0))),
            edge("c", "hub", "z", List.of(new Point(100.0, 288.0), new Point(500.0, 288.0))));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()))
                .edgeLabelDissociationCount())
        .isZero();
  }

  private static LaidOutEdge horizontalEdge(String id, double y) {
    return new LaidOutEdge(
        id,
        "src-" + id,
        "tgt-" + id,
        id,
        id,
        List.of(),
        List.of(new Point(100.0, y), new Point(500.0, y)),
        id);
  }

  private static LaidOutEdge unlabeledHorizontalEdge(String id, double y) {
    return new LaidOutEdge(
        id,
        "src-" + id,
        "tgt-" + id,
        id,
        id,
        List.of(),
        List.of(new Point(100.0, y), new Point(500.0, y)),
        "");
  }

  private static LaidOutEdge verticalEdge(String id, double x) {
    return new LaidOutEdge(
        id,
        "src-" + id,
        "tgt-" + id,
        id,
        id,
        List.of(),
        List.of(new Point(x, 100.0), new Point(x, 500.0)),
        id);
  }

  private static LaidOutNode junctionNode(String id, double x, double y) {
    return new LaidOutNode(id, id, id, x, y, 28.0, 28.0, "", "junction");
  }

  @Test
  void junctionCornerAttachedEdgeIsReported() {
    var nodes = List.of(node("upstream", 0.0, 0.0), junctionNode("junction", 200.0, 26.0));
    // Endpoint (200, 28) is on the junction box perimeter (passes the endpoint check) but
    // ~18.4 from the center (214, 40), just past reach = min(28, 28)/2 + tolerance = 16.
    var edges =
        List.of(
            edge(
                "into-junction",
                "upstream",
                "junction",
                List.of(new Point(100.0, 40.0), new Point(200.0, 28.0))));

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()));

    assertThat(diagnostics)
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE");
    assertThat(diagnostics.get(0).severity()).isEqualTo(DiagnosticSeverity.ERROR);
    assertThat(diagnostics.get(0).path()).isEqualTo("$.nodes[1]");
  }

  @Test
  void junctionCenterAttachedEdgeIsAccepted() {
    var nodes = List.of(node("upstream", 0.0, 0.0), junctionNode("junction", 200.0, 26.0));
    // Endpoint (200, 40) is level with the junction center (214, 40): 14 from it, within
    // reach = min(28, 28)/2 + tolerance = 16.
    var edges =
        List.of(
            edge(
                "into-junction",
                "upstream",
                "junction",
                List.of(new Point(100.0, 40.0), new Point(200.0, 40.0))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .isEmpty();
  }

  @Test
  void junctionEdgeJustInsideReachIsAccepted() {
    // Endpoint (200, 47.7) is sqrt(14^2 + 7.7^2) = 15.98 from the center: just inside reach 16.
    var nodes = List.of(node("upstream", 0.0, 0.0), junctionNode("junction", 200.0, 26.0));
    var edges =
        List.of(
            edge(
                "into-junction",
                "upstream",
                "junction",
                List.of(new Point(100.0, 47.7), new Point(200.0, 47.7))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .isEmpty();
  }

  @Test
  void junctionEdgeJustOutsideReachIsReported() {
    // Endpoint (200, 48) is sqrt(14^2 + 8^2) = 16.12 from the center: just past reach 16.
    var nodes = List.of(node("upstream", 0.0, 0.0), junctionNode("junction", 200.0, 26.0));
    var edges =
        List.of(
            edge(
                "into-junction",
                "upstream",
                "junction",
                List.of(new Point(100.0, 48.0), new Point(200.0, 48.0))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE");
  }

  @Test
  void labelAtExactlyTwiceCapacityIsAccepted() {
    // 60x48 box: capacity = floor((60-16)/7) * floor((48-16)/16) = 6 * 2 = 12; threshold 12 * 2 =
    // 24.
    var nodes =
        List.of(
            new LaidOutNode(
                "edge-case", "edge-case", "edge-case", 0.0, 0.0, 60.0, 48.0, "x".repeat(24), null));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()))
                .labelSpaceIssueCount())
        .isZero();
  }

  @Test
  void labelOneCharOverTwiceCapacityIsCounted() {
    var nodes =
        List.of(
            new LaidOutNode(
                "edge-case", "edge-case", "edge-case", 0.0, 0.0, 60.0, 48.0, "x".repeat(25), null));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()))
                .labelSpaceIssueCount())
        .isEqualTo(1);
  }

  @Test
  void nodeAtExactlyTheIconFloorIsChecked() {
    // 40x40 sits exactly at LABEL_SPACE_MIN_DIMENSION, so it is NOT exempt:
    // capacity = floor((40-16)/7) * max(1, floor((40-16)/16)) = 3 * 1 = 3; threshold 6; 8 chars
    // fire.
    var nodes =
        List.of(
            new LaidOutNode(
                "at-floor", "at-floor", "at-floor", 0.0, 0.0, 40.0, 40.0, "overflow", null));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()))
                .labelSpaceIssueCount())
        .isEqualTo(1);
  }

  @Test
  void nodeJustBelowTheIconFloorIsExempt() {
    var nodes =
        List.of(
            new LaidOutNode(
                "below-floor",
                "below-floor",
                "below-floor",
                0.0,
                0.0,
                39.0,
                39.0,
                "overflow",
                null));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()))
                .labelSpaceIssueCount())
        .isZero();
  }

  @Test
  void labelClearlyOverflowingNodeCapacityIsCounted() {
    var nodes =
        List.of(
            new LaidOutNode(
                "tiny",
                "tiny",
                "tiny",
                0.0,
                0.0,
                60.0,
                48.0,
                "An extremely long label that cannot possibly fit",
                null));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()));

    assertThat(report.labelSpaceIssueCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
  }

  @Test
  void typicalLabelsWithinNodeCapacityAreAccepted() {
    var nodes = List.of(node("api", 0.0, 0.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()));

    assertThat(report.labelSpaceIssueCount()).isZero();
    assertThat(report.status()).isEqualTo("ok");
  }

  @Test
  void smallIconNodeLabelsAreExemptFromLabelSpaceCheck() {
    // UML ports, gates, and pseudostates are icon-sized boxes whose labels render adjacent to
    // the shape, not inside it: a 32x32 port labeled "client" must not fire.
    var nodes =
        List.of(new LaidOutNode("port", "port", "port", 0.0, 0.0, 32.0, 32.0, "client", null));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()))
                .labelSpaceIssueCount())
        .isZero();
  }

  @Test
  void junctionLabelsAreExemptFromLabelSpaceCheck() {
    // The 28x28 size also triggers the icon floor; the role check is the junction's explicit guard.
    var nodes =
        List.of(
            new LaidOutNode(
                "junction",
                "junction",
                "junction",
                0.0,
                0.0,
                28.0,
                28.0,
                "a junction label rendered adjacent to the dot",
                "junction"));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()))
                .labelSpaceIssueCount())
        .isZero();
  }

  @Test
  void memberTouchingLabelBandLowerEdgeIsAccepted() {
    // Band is group y 0..24 exclusive at the boundary: a member starting exactly at y 24 clears it.
    var nodes = List.of(node("member", 10.0, 24.0));
    var groups =
        List.of(
            new LaidOutGroup(
                "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), "Zone"));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups))
                .groupLabelBandIssueCount())
        .isZero();
  }

  @Test
  void memberOneUnitInsideLabelBandIsCounted() {
    var nodes = List.of(node("member", 10.0, 23.0));
    var groups =
        List.of(
            new LaidOutGroup(
                "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), "Zone"));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups))
                .groupLabelBandIssueCount())
        .isEqualTo(1);
  }

  @Test
  void groupMembersInsideLabelBandAreCounted() {
    var nodes = List.of(node("member", 10.0, 10.0));
    var groups =
        List.of(
            new LaidOutGroup(
                "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), "Zone"));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

    assertThat(report.groupLabelBandIssueCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
  }

  @Test
  void groupMembersBelowLabelBandAreAccepted() {
    var nodes = List.of(node("member", 10.0, 32.0));
    var groups =
        List.of(
            new LaidOutGroup(
                "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), "Zone"));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

    assertThat(report.groupLabelBandIssueCount()).isZero();
    assertThat(report.status()).isEqualTo("ok");
  }

  @Test
  void blankLabeledGroupHasNoLabelBandReservation() {
    var nodes = List.of(node("member", 10.0, 10.0));
    var groups =
        List.of(
            new LaidOutGroup(
                "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), "  "));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups))
                .groupLabelBandIssueCount())
        .isZero();
  }

  @Test
  void unlabeledGroupHasNoLabelBandReservation() {
    var nodes = List.of(node("member", 10.0, 10.0));
    var groups =
        List.of(
            new LaidOutGroup(
                "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), null));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups))
                .groupLabelBandIssueCount())
        .isZero();
  }

  @Test
  void threeLevelNestedContainmentValidatesCleanly() {
    var nodes = List.of(node("leaf", 60.0, 110.0));
    var groups =
        List.of(
            new LaidOutGroup(
                "outer",
                "outer",
                "outer",
                null,
                0.0,
                0.0,
                400.0,
                320.0,
                List.of("middle"),
                "Outer"),
            new LaidOutGroup(
                "middle",
                "middle",
                "middle",
                null,
                30.0,
                40.0,
                320.0,
                240.0,
                List.of("inner"),
                "Middle"),
            new LaidOutGroup(
                "inner",
                "inner",
                "inner",
                null,
                50.0,
                80.0,
                260.0,
                160.0,
                List.of("leaf"),
                "Inner"));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

    assertThat(report.groupBoundaryIssueCount()).isZero();
    assertThat(report.groupLabelBandIssueCount()).isZero();
    assertThat(report.status()).isEqualTo("ok");
  }

  @Test
  void memberEscapingDeepNestedGroupIsCounted() {
    var nodes = List.of(node("leaf", 290.0, 110.0));
    var groups =
        List.of(
            new LaidOutGroup(
                "outer",
                "outer",
                "outer",
                null,
                0.0,
                0.0,
                400.0,
                320.0,
                List.of("middle"),
                "Outer"),
            new LaidOutGroup(
                "middle",
                "middle",
                "middle",
                null,
                30.0,
                40.0,
                320.0,
                240.0,
                List.of("inner"),
                "Middle"),
            new LaidOutGroup(
                "inner",
                "inner",
                "inner",
                null,
                50.0,
                80.0,
                260.0,
                160.0,
                List.of("leaf"),
                "Inner"));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

    assertThat(report.groupBoundaryIssueCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
  }

  @Test
  void crossingEdgePairsAreCountedAsInformationOnly() {
    var nodes =
        List.of(
            node("a", 0.0, 0.0),
            node("b", 400.0, 400.0),
            node("c", 0.0, 400.0),
            node("d", 400.0, 0.0));
    var edges =
        List.of(
            edge("a-b", "a", "b", List.of(new Point(100.0, 80.0), new Point(400.0, 440.0))),
            edge("c-d", "c", "d", List.of(new Point(100.0, 440.0), new Point(400.0, 40.0))));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, edges, List.of()));

    assertThat(report.edgeCrossingCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("ok");
  }

  @Test
  void edgesSharingAnEndpointNodeAreNotCountedAsCrossings() {
    var nodes =
        List.of(node("hub", 0.0, 0.0), node("left", 300.0, 0.0), node("right", 300.0, 200.0));
    var edges =
        List.of(
            edge(
                "hub-left", "hub", "left", List.of(new Point(100.0, 40.0), new Point(300.0, 40.0))),
            edge(
                "hub-right",
                "hub",
                "right",
                List.of(new Point(100.0, 40.0), new Point(300.0, 240.0))));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, edges, List.of()));

    assertThat(report.edgeCrossingCount()).isZero();
  }

  @Test
  void allSharedEndpointCombinationsAreExcludedFromCrossings() {
    var crossing = List.of(new Point(0.0, 0.0), new Point(200.0, 200.0));
    var counterCrossing = List.of(new Point(0.0, 200.0), new Point(200.0, 0.0));

    var sharedTarget =
        List.of(edge("p-q", "p", "q", crossing), edge("r-q", "r", "q", counterCrossing));
    var sourceIsOthersTarget =
        List.of(edge("p-q", "p", "q", crossing), edge("q-r", "q", "r", counterCrossing));
    var targetIsOthersSource =
        List.of(edge("q-p", "q", "p", crossing), edge("r-q", "r", "q", counterCrossing));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), sharedTarget, List.of()))
                .edgeCrossingCount())
        .isZero();
    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), sourceIsOthersTarget, List.of()))
                .edgeCrossingCount())
        .isZero();
    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), targetIsOthersSource, List.of()))
                .edgeCrossingCount())
        .isZero();
  }

  private static LayoutResult layoutResult(
      List<LaidOutNode> nodes, List<LaidOutEdge> edges, List<LaidOutGroup> groups) {
    return new LayoutResult("layout-result.schema.v1", "main", nodes, edges, groups, List.of());
  }

  private static LaidOutNode node(String id, double x, double y) {
    return new LaidOutNode(id, id, id, x, y, 100.0, 80.0, id);
  }

  private static LaidOutNode lifelineNode(
      String id, double x, double y, double width, double height) {
    return new LaidOutNode(id, id, id, x, y, width, height, id, "lifeline");
  }

  private static LaidOutEdge edge(String id, String source, String target, List<Point> points) {
    return new LaidOutEdge(id, source, target, id, id, List.of(), points, id);
  }
}

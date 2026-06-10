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

        LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()));

        assertThat(report.overlapCount()).isEqualTo(1);
        assertThat(report.status()).isEqualTo("warning");
    }

    @Test
    void routeDiagnosticsReportEmptyRoutesAndEndpointMisses() {
        var nodes = new ArrayList<LaidOutNode>();
        nodes.add(node("source", 0.0, 0.0));
        nodes.add(node("target", 300.0, 0.0));
        var edges = new ArrayList<LaidOutEdge>();
        edges.add(edge("empty", "source", "target", List.of()));
        edges.add(edge("misses-target", "source", "target", List.of(
                new Point(100.0, 40.0),
                new Point(250.0, 40.0))));

        var diagnostics = LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()));

        assertThat(diagnostics).extracting(diagnostic -> diagnostic.code()).containsExactly(
                "DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY",
                "DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
        assertThat(diagnostics).extracting(diagnostic -> diagnostic.severity())
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
        edges.add(edge("m1", "customer", "service", List.of(
                new Point(240.0, 180.0),
                new Point(520.0, 180.0))));

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
        edges.add(edge("m1", "customer", "service", List.of(
                new Point(240.0, 180.0),
                new Point(400.0, 180.0))));

        var diagnostics = LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()));

        assertThat(diagnostics).extracting(diagnostic -> diagnostic.code())
                .containsExactly("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
        assertThat(diagnostics.get(0).path()).isEqualTo("$.edges[0].points[-1]");
    }

    @Test
    void ordinaryNodeEndpointOnVerticalAxisBelowNodeIsStillRejected() {
        var nodes = new ArrayList<LaidOutNode>();
        nodes.add(node("source", 0.0, 0.0));
        nodes.add(node("target", 300.0, 0.0));
        var edges = new ArrayList<LaidOutEdge>();
        edges.add(edge("m1", "source", "target", List.of(
                new Point(0.0, 40.0),
                new Point(300.0, 200.0))));

        var diagnostics = LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()));

        assertThat(diagnostics).extracting(diagnostic -> diagnostic.code())
                .containsExactly("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
        assertThat(diagnostics.get(0).path()).isEqualTo("$.edges[0].points[-1]");
    }

    @Test
    void routeAndBoundaryIssuesAreCounted() {
        var nodes = new ArrayList<LaidOutNode>();
        nodes.add(node("source", 0.0, 0.0));
        nodes.add(node("target", 500.0, 0.0));
        nodes.add(node("middle", 240.0, 20.0));
        var groups = List.of(new LaidOutGroup(
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
        var edges = List.of(edge("crosses", "source", "target", List.of(
                new Point(100.0, 40.0),
                new Point(500.0, 40.0))));

        LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, edges, groups));

        assertThat(report.connectorThroughNodeCount()).isEqualTo(1);
        assertThat(report.groupBoundaryIssueCount()).isEqualTo(1);
        assertThat(report.status()).isEqualTo("warning");
    }

    @Test
    void nestedGroupMembersAreCountedAsGroupBoundaryMembers() {
        var nodes = List.of(
                node("source", 0.0, 30.0),
                node("target", 200.0, 30.0));
        var edges = List.of(edge("internal", "source", "target", List.of(
                new Point(100.0, 40.0),
                new Point(200.0, 40.0))));
        var groups = List.of(
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
        edges.add(edge("detour", "detour-source", "detour-target", List.of(
                new Point(100.0, 40.0),
                new Point(100.0, 640.0),
                new Point(300.0, 640.0),
                new Point(300.0, 40.0))));
        edges.add(edge("primary", "a", "b", List.of(
                new Point(0.0, 0.0),
                new Point(200.0, 0.0))));
        edges.add(edge("too-close", "c", "d", List.of(
                new Point(0.0, 16.0),
                new Point(200.0, 16.0))));
        edges.add(edge("readable", "e", "f", List.of(
                new Point(0.0, 80.0),
                new Point(200.0, 80.0))));

        LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()));

        assertThat(report.routeDetourCount()).isEqualTo(1);
        assertThat(report.routeCloseParallelCount()).isEqualTo(1);
        assertThat(report.status()).isEqualTo("warning");
    }

    private static LaidOutNode junctionNode(String id, double x, double y) {
        return new LaidOutNode(id, id, id, x, y, 28.0, 28.0, "", "junction");
    }

    @Test
    void junctionCornerAttachedEdgeIsReported() {
        var nodes = List.of(
                node("upstream", 0.0, 0.0),
                junctionNode("junction", 200.0, 26.0));
        // Endpoint (200, 28) is on the junction box perimeter (passes the endpoint check) but
        // ~18.4 from the center (214, 40), just past reach = min(28, 28)/2 + tolerance = 16.
        var edges = List.of(edge("into-junction", "upstream", "junction", List.of(
                new Point(100.0, 40.0),
                new Point(200.0, 28.0))));

        var diagnostics = LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()));

        assertThat(diagnostics).extracting(diagnostic -> diagnostic.code())
                .containsExactly("DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE");
        assertThat(diagnostics.get(0).severity()).isEqualTo(DiagnosticSeverity.ERROR);
        assertThat(diagnostics.get(0).path()).isEqualTo("$.nodes[1]");
    }

    @Test
    void junctionCenterAttachedEdgeIsAccepted() {
        var nodes = List.of(
                node("upstream", 0.0, 0.0),
                junctionNode("junction", 200.0, 26.0));
        // Endpoint (200, 40) is level with the junction center (214, 40): 14 from it, within
        // reach = min(28, 28)/2 + tolerance = 16.
        var edges = List.of(edge("into-junction", "upstream", "junction", List.of(
                new Point(100.0, 40.0),
                new Point(200.0, 40.0))));

        assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()))).isEmpty();
    }

    @Test
    void labelClearlyOverflowingNodeCapacityIsCounted() {
        var nodes = List.of(new LaidOutNode("tiny", "tiny", "tiny", 0.0, 0.0, 60.0, 24.0,
                "An extremely long label that cannot possibly fit", null));

        LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()));

        assertThat(report.labelSpaceIssueCount()).isEqualTo(1);
        assertThat(report.status()).isEqualTo("warning");
    }

    @Test
    void typicalLabelsWithinNodeCapacityAreAccepted() {
        var nodes = List.of(node("api", 0.0, 0.0));

        LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()));

        assertThat(report.labelSpaceIssueCount()).isZero();
        assertThat(report.status()).isEqualTo("ok");
    }

    @Test
    void junctionLabelsAreExemptFromLabelSpaceCheck() {
        var nodes = List.of(new LaidOutNode("junction", "junction", "junction", 0.0, 0.0, 28.0, 28.0,
                "a junction label rendered adjacent to the dot", "junction"));

        assertThat(LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()))
                .labelSpaceIssueCount()).isZero();
    }

    @Test
    void groupMembersInsideLabelBandAreCounted() {
        var nodes = List.of(node("member", 10.0, 10.0));
        var groups = List.of(new LaidOutGroup(
                "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), "Zone"));

        LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

        assertThat(report.groupLabelBandIssueCount()).isEqualTo(1);
        assertThat(report.status()).isEqualTo("warning");
    }

    @Test
    void groupMembersBelowLabelBandAreAccepted() {
        var nodes = List.of(node("member", 10.0, 32.0));
        var groups = List.of(new LaidOutGroup(
                "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), "Zone"));

        LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

        assertThat(report.groupLabelBandIssueCount()).isZero();
        assertThat(report.status()).isEqualTo("ok");
    }

    @Test
    void unlabeledGroupHasNoLabelBandReservation() {
        var nodes = List.of(node("member", 10.0, 10.0));
        var groups = List.of(new LaidOutGroup(
                "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), null));

        assertThat(LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups))
                .groupLabelBandIssueCount()).isZero();
    }

    @Test
    void threeLevelNestedContainmentValidatesCleanly() {
        var nodes = List.of(node("leaf", 60.0, 110.0));
        var groups = List.of(
                new LaidOutGroup("outer", "outer", "outer", null, 0.0, 0.0, 400.0, 320.0,
                        List.of("middle"), "Outer"),
                new LaidOutGroup("middle", "middle", "middle", null, 30.0, 40.0, 320.0, 240.0,
                        List.of("inner"), "Middle"),
                new LaidOutGroup("inner", "inner", "inner", null, 50.0, 80.0, 260.0, 160.0,
                        List.of("leaf"), "Inner"));

        LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

        assertThat(report.groupBoundaryIssueCount()).isZero();
        assertThat(report.groupLabelBandIssueCount()).isZero();
        assertThat(report.status()).isEqualTo("ok");
    }

    @Test
    void memberEscapingDeepNestedGroupIsCounted() {
        var nodes = List.of(node("leaf", 290.0, 110.0));
        var groups = List.of(
                new LaidOutGroup("outer", "outer", "outer", null, 0.0, 0.0, 400.0, 320.0,
                        List.of("middle"), "Outer"),
                new LaidOutGroup("middle", "middle", "middle", null, 30.0, 40.0, 320.0, 240.0,
                        List.of("inner"), "Middle"),
                new LaidOutGroup("inner", "inner", "inner", null, 50.0, 80.0, 260.0, 160.0,
                        List.of("leaf"), "Inner"));

        LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups));

        assertThat(report.groupBoundaryIssueCount()).isEqualTo(1);
        assertThat(report.status()).isEqualTo("warning");
    }

    @Test
    void crossingEdgePairsAreCountedAsInformationOnly() {
        var nodes = List.of(
                node("a", 0.0, 0.0),
                node("b", 400.0, 400.0),
                node("c", 0.0, 400.0),
                node("d", 400.0, 0.0));
        var edges = List.of(
                edge("a-b", "a", "b", List.of(new Point(100.0, 80.0), new Point(400.0, 440.0))),
                edge("c-d", "c", "d", List.of(new Point(100.0, 440.0), new Point(400.0, 40.0))));

        LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, edges, List.of()));

        assertThat(report.edgeCrossingCount()).isEqualTo(1);
        assertThat(report.status()).isEqualTo("ok");
    }

    @Test
    void edgesSharingAnEndpointNodeAreNotCountedAsCrossings() {
        var nodes = List.of(
                node("hub", 0.0, 0.0),
                node("left", 300.0, 0.0),
                node("right", 300.0, 200.0));
        var edges = List.of(
                edge("hub-left", "hub", "left", List.of(new Point(100.0, 40.0), new Point(300.0, 40.0))),
                edge("hub-right", "hub", "right", List.of(new Point(100.0, 40.0), new Point(300.0, 240.0))));

        LayoutQualityReport report = LayoutQuality.validateLayout(layoutResult(nodes, edges, List.of()));

        assertThat(report.edgeCrossingCount()).isZero();
    }

    private static LayoutResult layoutResult(
            List<LaidOutNode> nodes,
            List<LaidOutEdge> edges,
            List<LaidOutGroup> groups) {
        return new LayoutResult("layout-result.schema.v1", "main", nodes, edges, groups, List.of());
    }

    private static LaidOutNode node(String id, double x, double y) {
        return new LaidOutNode(id, id, id, x, y, 100.0, 80.0, id);
    }

    private static LaidOutNode lifelineNode(String id, double x, double y, double width, double height) {
        return new LaidOutNode(id, id, id, x, y, width, height, id, "lifeline");
    }

    private static LaidOutEdge edge(String id, String source, String target, List<Point> points) {
        return new LaidOutEdge(id, source, target, id, id, List.of(), points, id);
    }

}

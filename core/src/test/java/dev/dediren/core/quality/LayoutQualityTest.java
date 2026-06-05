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
                node("source", 0.0, 0.0),
                node("target", 200.0, 0.0));
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
                        140.0,
                        List.of("inner"),
                        "Outer"),
                new LaidOutGroup(
                        "inner",
                        "inner",
                        "inner",
                        null,
                        -10.0,
                        -10.0,
                        320.0,
                        100.0,
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

    private static LayoutResult layoutResult(
            List<LaidOutNode> nodes,
            List<LaidOutEdge> edges,
            List<LaidOutGroup> groups) {
        return new LayoutResult("layout-result.schema.v1", "main", nodes, edges, groups, List.of());
    }

    private static LaidOutNode node(String id, double x, double y) {
        return new LaidOutNode(id, id, id, x, y, 100.0, 80.0, id);
    }

    private static LaidOutEdge edge(String id, String source, String target, List<Point> points) {
        return new LaidOutEdge(id, source, target, id, id, List.of(), points, id);
    }

}

package dev.dediren.core.quality;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan B P2-T4 (tightened): the HARD-ERROR lane of {@link LayoutQuality#validateLayoutDiagnostics}
 * must thread the offending element's existing {@code sourcePointer()} onto the emitted {@link
 * dev.dediren.contracts.Diagnostic#sourcePointer()}, so a downstream consumer can jump straight
 * from a layout-quality error to the source model element that caused it.
 */
class LayoutQualityProvenanceTest {

  @Test
  void nonFiniteNodeGeometryDiagnosticCarriesTheNodesSourcePointer() {
    var node =
        new LaidOutNode(
            "bad",
            "bad",
            "bad",
            Double.POSITIVE_INFINITY,
            0.0,
            100.0,
            80.0,
            "bad",
            null,
            "/nodes/2");

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(List.of(node), List.of()));

    assertThat(diagnostics)
        .filteredOn(diagnostic -> diagnostic.code().equals("DEDIREN_LAYOUT_NON_FINITE_GEOMETRY"))
        .singleElement()
        .extracting(diagnostic -> diagnostic.sourcePointer())
        .isEqualTo("/nodes/2");
  }

  @Test
  void nonFiniteRoutePointDiagnosticCarriesTheEdgesSourcePointer() {
    var edge =
        new LaidOutEdge(
            "e",
            "s",
            "t",
            "e",
            "e",
            List.of(),
            List.of(new Point(Double.NaN, 0.0), new Point(100.0, 0.0)),
            "e",
            "/edges/5");

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(List.of(), List.of(edge)));

    assertThat(diagnostics)
        .filteredOn(diagnostic -> diagnostic.code().equals("DEDIREN_LAYOUT_NON_FINITE_GEOMETRY"))
        .singleElement()
        .extracting(diagnostic -> diagnostic.sourcePointer())
        .isEqualTo("/edges/5");
  }

  @Test
  void routeEndpointOffPerimeterDiagnosticCarriesTheEdgesSourcePointer() {
    var source = node("source", 0.0, 0.0, null);
    var target = node("target", 300.0, 0.0, null);
    var edge =
        new LaidOutEdge(
            "misses-target",
            "source",
            "target",
            "misses-target",
            "misses-target",
            List.of(),
            List.of(new Point(100.0, 40.0), new Point(250.0, 40.0)),
            "misses-target",
            "/edges/9");

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(
            layoutResult(List.of(source, target), List.of(edge)));

    assertThat(diagnostics)
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
    assertThat(diagnostics.get(0).sourcePointer()).isEqualTo("/edges/9");
  }

  @Test
  void routeEndpointOffPerimeterOnSourceSideDiagnosticCarriesTheEdgesSourcePointer() {
    // Mirrors routeEndpointOffPerimeterDiagnosticCarriesTheEdgesSourcePointer, but on the SOURCE
    // side: the first route point is interior to the source node (not on its perimeter) while the
    // last point sits on the target node's perimeter, so only the source-side branch of
    // LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER fires.
    var source = node("source", 0.0, 0.0, null);
    var target = node("target", 300.0, 0.0, null);
    var edge =
        new LaidOutEdge(
            "misses-source",
            "source",
            "target",
            "misses-source",
            "misses-source",
            List.of(),
            List.of(new Point(55.0, 45.0), new Point(300.0, 40.0)),
            "misses-source",
            "/edges/11");

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(
            layoutResult(List.of(source, target), List.of(edge)));

    assertThat(diagnostics)
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_ROUTE_ENDPOINT_OFF_NODE_PERIMETER");
    assertThat(diagnostics.get(0).sourcePointer()).isEqualTo("/edges/11");
  }

  @Test
  void routePointsEmptyDiagnosticCarriesTheEdgesSourcePointer() {
    var edge =
        new LaidOutEdge(
            "empty-route",
            "source",
            "target",
            "empty-route",
            "empty-route",
            List.of(),
            List.of(),
            "empty-route",
            "/edges/12");

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(List.of(), List.of(edge)));

    assertThat(diagnostics)
        .filteredOn(diagnostic -> diagnostic.code().equals("DEDIREN_LAYOUT_ROUTE_POINTS_EMPTY"))
        .singleElement()
        .extracting(diagnostic -> diagnostic.sourcePointer())
        .isEqualTo("/edges/12");
  }

  @Test
  void routePointsInsufficientDiagnosticCarriesTheEdgesSourcePointer() {
    var edge =
        new LaidOutEdge(
            "one-point-route",
            "source",
            "target",
            "one-point-route",
            "one-point-route",
            List.of(),
            List.of(new Point(50.0, 40.0)),
            "one-point-route",
            "/edges/13");

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(List.of(), List.of(edge)));

    assertThat(diagnostics)
        .filteredOn(
            diagnostic -> diagnostic.code().equals("DEDIREN_LAYOUT_ROUTE_POINTS_INSUFFICIENT"))
        .singleElement()
        .extracting(diagnostic -> diagnostic.sourcePointer())
        .isEqualTo("/edges/13");
  }

  @Test
  void junctionOffIncidentRouteDiagnosticCarriesTheJunctionNodesSourcePointer() {
    var upstream = node("upstream", 0.0, 0.0, null);
    var junction =
        new LaidOutNode(
            "junction",
            "junction",
            "junction",
            200.0,
            26.0,
            28.0,
            28.0,
            "",
            "junction",
            "/nodes/7");
    var edge =
        new LaidOutEdge(
            "into-junction",
            "upstream",
            "junction",
            "into-junction",
            "into-junction",
            List.of(),
            // Endpoint (200, 28) is on the junction perimeter but past the center reach,
            // matching LayoutQualityTest#junctionCornerAttachedEdgeIsReported.
            List.of(new Point(100.0, 40.0), new Point(200.0, 28.0)),
            "into-junction");

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(
            layoutResult(List.of(upstream, junction), List.of(edge)));

    assertThat(diagnostics)
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE");
    assertThat(diagnostics.get(0).sourcePointer()).isEqualTo("/nodes/7");
  }

  @Test
  void degenerateSelfLoopDiagnosticCarriesTheEdgesSourcePointer() {
    // source == target: the node and the edge are distinct source-model elements, so this pins
    // down which one's pointer the diagnostic must carry (the edge's, since the diagnostic's
    // path targets $.edges[...] — the degenerate route belongs to the edge, not the node).
    var node = node("a", 0.0, 0.0, "/nodes/9"); // 100 x 80 at the origin
    var edge =
        new LaidOutEdge(
            "loop",
            "a",
            "a",
            "loop",
            "loop",
            List.of(),
            List.of(new Point(50.0, 0.0), new Point(60.0, 10.0), new Point(50.0, 0.0)),
            "loop",
            "/edges/3");

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(List.of(node), List.of(edge)));

    assertThat(diagnostics)
        .filteredOn(diagnostic -> diagnostic.code().equals("DEDIREN_LAYOUT_SELF_LOOP_DEGENERATE"))
        .singleElement()
        .extracting(diagnostic -> diagnostic.sourcePointer())
        .isEqualTo("/edges/3");
  }

  @Test
  void nonFiniteNodeGeometryDiagnosticCarriesNullWhenTheNodeHasNoSourcePointer() {
    var node =
        new LaidOutNode("bad", "bad", "bad", Double.POSITIVE_INFINITY, 0.0, 100.0, 80.0, "bad");

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(List.of(node), List.of()));

    assertThat(diagnostics)
        .filteredOn(diagnostic -> diagnostic.code().equals("DEDIREN_LAYOUT_NON_FINITE_GEOMETRY"))
        .singleElement()
        .extracting(diagnostic -> diagnostic.sourcePointer())
        .isNull();
  }

  private static LaidOutNode node(String id, double x, double y, String sourcePointer) {
    return new LaidOutNode(id, id, id, x, y, 100.0, 80.0, id, null, sourcePointer);
  }

  private static LayoutResult layoutResult(List<LaidOutNode> nodes, List<LaidOutEdge> edges) {
    return new LayoutResult(
        ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION, "main", nodes, edges, List.of(), List.of());
  }
}

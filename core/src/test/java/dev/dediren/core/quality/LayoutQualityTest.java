package dev.dediren.core.quality;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
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

  /**
   * Pins the overlap predicate's strict boundary: rectangles that exactly touch (x[i] + width[i] ==
   * x[i+1]) are NOT an overlap. The elk-layout sequence normalizer guarantees lifeline columns
   * satisfy exactly this touching-allowed invariant and documents that it matches this class's
   * strict predicate — engines may not depend on core, so the agreement is held by this pin: a
   * drift to an inclusive comparison here would flag every normalizer-packed sequence layout.
   */
  @Test
  void exactlyTouchingNodesAreNotCountedAsOverlap() {
    var nodes = new ArrayList<LaidOutNode>();
    nodes.add(node("a", 0.0, 0.0));
    nodes.add(node("b", 100.0, 0.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()));

    assertThat(report.overlapCount()).isZero();
    assertThat(report.status()).isEqualTo("ok");
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
    // Endpoints sit on the lifeline axis (each head box's horizontal center: customer
    // 100 + 140/2 = 170, service 520 + 140/2 = 590), matching the render engine's own
    // head-box-center stem convention.
    edges.add(
        edge(
            "m1",
            "customer",
            "service",
            List.of(new Point(170.0, 180.0), new Point(590.0, 180.0))));

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
    // First point on customer's axis (170); last point misses service's axis (590).
    edges.add(
        edge(
            "m1",
            "customer",
            "service",
            List.of(new Point(170.0, 180.0), new Point(400.0, 180.0))));

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
  void nonFiniteNodeGeometryIsReported() {
    // A layout plugin bug — or a JSON coordinate like 1e999 that Jackson deserializes to Infinity —
    // reaches core as a non-finite coordinate that every downstream geometry check silently
    // mis-handles (NaN comparisons are always false). Catch it at the source.
    var nodes =
        List.of(
            new LaidOutNode(
                "bad", "bad", "bad", Double.POSITIVE_INFINITY, 0.0, 100.0, 80.0, "bad"));

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, List.of(), List.of()));

    assertThat(diagnostics)
        .filteredOn(diagnostic -> diagnostic.code().equals("DEDIREN_LAYOUT_NON_FINITE_GEOMETRY"))
        .singleElement()
        .satisfies(
            diagnostic -> {
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.ERROR);
              assertThat(diagnostic.path()).isEqualTo("$.nodes[0]");
            });
  }

  @Test
  void nonFiniteRoutePointIsReported() {
    var edges =
        List.of(edge("e", "s", "t", List.of(new Point(Double.NaN, 0.0), new Point(100.0, 0.0))));

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(List.of(), edges, List.of()));

    assertThat(diagnostics)
        .filteredOn(diagnostic -> diagnostic.code().equals("DEDIREN_LAYOUT_NON_FINITE_GEOMETRY"))
        .singleElement()
        .extracting(diagnostic -> diagnostic.path())
        .isEqualTo("$.edges[0].points[0]");
  }

  @Test
  void nonFiniteGroupGeometryIsReported() {
    var groups =
        List.of(
            new LaidOutGroup(
                "g", "g", "g", null, Double.NaN, 0.0, 100.0, 100.0, List.of(), "Group"));

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(List.of(), List.of(), groups));

    assertThat(diagnostics)
        .filteredOn(diagnostic -> diagnostic.code().equals("DEDIREN_LAYOUT_NON_FINITE_GEOMETRY"))
        .singleElement()
        .extracting(diagnostic -> diagnostic.path())
        .isEqualTo("$.groups[0]");
  }

  @Test
  void finiteLayoutRaisesNoNonFiniteDiagnostic() {
    var nodes = List.of(node("a", 0.0, 0.0), node("b", 300.0, 0.0));
    var edges =
        List.of(edge("a-b", "a", "b", List.of(new Point(100.0, 40.0), new Point(300.0, 40.0))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .extracting(diagnostic -> diagnostic.code())
        .doesNotContain("DEDIREN_LAYOUT_NON_FINITE_GEOMETRY");
  }

  @Test
  void emptyLayoutValidatesCleanly() {
    LayoutResult empty = layoutResult(List.of(), List.of(), List.of());

    assertThat(LayoutQuality.validateLayout(empty).status()).isEqualTo("ok");
    assertThat(LayoutQuality.validateLayoutDiagnostics(empty)).isEmpty();
  }

  @Test
  void degenerateSelfLoopHiddenInsideItsNodeIsReported() {
    // A self-loop (source == target) whose route never leaves the node box renders as a dot or a
    // line hidden behind the node — an invisible self-reference (taxonomy T10).
    var nodes = List.of(node("a", 0.0, 0.0)); // 100 x 80 at the origin
    var edges =
        List.of(
            edge(
                "loop",
                "a",
                "a",
                List.of(new Point(50.0, 0.0), new Point(60.0, 10.0), new Point(50.0, 0.0))));

    var diagnostics =
        LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of()));

    assertThat(diagnostics)
        .filteredOn(diagnostic -> diagnostic.code().equals("DEDIREN_LAYOUT_SELF_LOOP_DEGENERATE"))
        .singleElement()
        .satisfies(
            diagnostic -> {
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.ERROR);
              assertThat(diagnostic.path()).isEqualTo("$.edges[0]");
            });
  }

  @Test
  void selfLoopExtendingOutsideItsNodeIsAccepted() {
    var nodes = List.of(node("a", 0.0, 0.0)); // 100 x 80 at the origin
    var edges =
        List.of(
            edge(
                "loop",
                "a",
                "a",
                List.of(
                    new Point(100.0, 20.0),
                    new Point(140.0, 20.0),
                    new Point(140.0, 60.0),
                    new Point(100.0, 60.0))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .extracting(diagnostic -> diagnostic.code())
        .doesNotContain("DEDIREN_LAYOUT_SELF_LOOP_DEGENERATE");
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
  void sequenceInteractionContainerIsNotCountedAsOverlapOrConnectorThrough() {
    var interaction =
        new LaidOutNode("ix", "ix", "ix", 0.0, 0.0, 400.0, 300.0, "Interaction", "interaction");
    var a = new LaidOutNode("a", "a", "a", 40.0, 20.0, 100.0, 48.0, "A", "lifeline");
    var b = new LaidOutNode("b", "b", "b", 260.0, 20.0, 100.0, 48.0, "B", "lifeline");
    var nodes = List.of(interaction, a, b);
    // Endpoints sit on the lifeline axis (a's center x=40+100/2=90, b's center x=260+100/2=310),
    // matching the onLifelineAxis convention already exercised by
    // lifelineMessageEndpointsOnLifelineAxisAreAccepted.
    var edges =
        List.of(edge("m", "a", "b", List.of(new Point(90.0, 120.0), new Point(310.0, 120.0))));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, edges, List.of()));

    // The interaction frame legitimately encloses its lifelines and messages: it must not
    // register as an overlap, and messages routed inside it are not "through-node" hits.
    assertThat(report.overlapCount()).isEqualTo(0);
    assertThat(report.connectorThroughNodeCount()).isEqualTo(0);
    assertThat(report.status()).isEqualTo("ok");
  }

  @Test
  void executionBarOnLifelineStemIsNotCountedAsOverlapOrConnectorThrough() {
    var customer = lifelineNode("customer", 100.0, 100.0, 140.0, 48.0);
    var service = lifelineNode("service", 520.0, 100.0, 140.0, 48.0);
    // The activation bar legitimately sits ON the service lifeline's stem (centerX 590):
    // its rectangle overlaps the lifeline head box, and the message that activates it
    // terminates by passing through its rectangle -- exactly the geometry layout now produces
    // once ExecutionSpecification bars are placed on the stem (correct UML, not a defect).
    var exec = new LaidOutNode("exec", "exec", "exec", 584.0, 124.0, 12.0, 200.0, "", "execution");
    var nodes = List.of(customer, service, exec);
    var edges =
        List.of(
            edge(
                "m1",
                "customer",
                "service",
                List.of(new Point(170.0, 150.0), new Point(590.0, 200.0))));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, edges, List.of()));

    assertThat(report.overlapCount()).isEqualTo(0);
    assertThat(report.connectorThroughNodeCount()).isEqualTo(0);
    assertThat(report.status()).isEqualTo("ok");
  }

  @Test
  void destructionMarkerOnLifelineStemIsNotCountedAsOverlapOrConnectorThrough() {
    var customer = lifelineNode("customer", 100.0, 100.0, 140.0, 48.0);
    var service = lifelineNode("service", 520.0, 100.0, 140.0, 48.0);
    // The destruction marker legitimately sits ON the customer lifeline's stem (centerX 170),
    // overlapping the head box, and the destroying message terminates by passing through its
    // rectangle -- the same "chrome sits on the stem" geometry as an activation bar.
    var destroy =
        new LaidOutNode(
            "destroy", "destroy", "destroy", 164.0, 124.0, 12.0, 24.0, "", "destruction");
    var nodes = List.of(customer, service, destroy);
    var edges =
        List.of(
            edge(
                "m2",
                "service",
                "customer",
                List.of(new Point(590.0, 150.0), new Point(170.0, 140.0))));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, edges, List.of()));

    assertThat(report.overlapCount()).isEqualTo(0);
    assertThat(report.connectorThroughNodeCount()).isEqualTo(0);
    assertThat(report.status()).isEqualTo("ok");
  }

  @Test
  void sequenceChromeExemptionIsScopedAndOrdinaryOverlapsAreStillCounted() {
    var customer = lifelineNode("customer", 100.0, 100.0, 140.0, 48.0);
    var service = lifelineNode("service", 520.0, 100.0, 140.0, 48.0);
    var exec = new LaidOutNode("exec", "exec", "exec", 584.0, 124.0, 12.0, 200.0, "", "execution");
    // An unrelated pair of ORDINARY (non-chrome) nodes genuinely overlaps. This must still be
    // counted: the exemption is scoped to interaction/execution/destruction chrome, not a
    // blanket disable of the overlap counter.
    var ordinaryA = node("ordinary-a", 700.0, 400.0);
    var ordinaryB = node("ordinary-b", 750.0, 420.0);
    var nodes = List.of(customer, service, exec, ordinaryA, ordinaryB);
    var edges =
        List.of(
            edge(
                "m1",
                "customer",
                "service",
                List.of(new Point(170.0, 150.0), new Point(590.0, 200.0))));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, edges, List.of()));

    // The exec/service overlap and the message-through-exec hit are exempted sequence chrome;
    // only the genuine ordinary-a/ordinary-b overlap is counted.
    assertThat(report.overlapCount()).isEqualTo(1);
    assertThat(report.connectorThroughNodeCount()).isEqualTo(0);
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

    assertThat(dissociationCount(edges)).isZero();
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

    assertThat(dissociationCount(edges)).isEqualTo(1);
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

    assertThat(dissociationCount(edges)).isZero();
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

    assertThat(dissociationCount(edges)).isZero();
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

    assertThat(dissociationCount(edges)).isZero();
  }

  private static int dissociationCount(List<LaidOutEdge> edges) {
    return LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()))
        .edgeLabelDissociationCount();
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

    assertThat(labelSpaceIssueCount(nodes)).isZero();
  }

  @Test
  void labelOneCharOverTwiceCapacityIsCounted() {
    var nodes =
        List.of(
            new LaidOutNode(
                "edge-case", "edge-case", "edge-case", 0.0, 0.0, 60.0, 48.0, "x".repeat(25), null));

    assertThat(labelSpaceIssueCount(nodes)).isEqualTo(1);
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

    assertThat(labelSpaceIssueCount(nodes)).isEqualTo(1);
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

    assertThat(labelSpaceIssueCount(nodes)).isZero();
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

    assertThat(labelSpaceIssueCount(nodes)).isZero();
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

    assertThat(labelSpaceIssueCount(nodes)).isZero();
  }

  private static int labelSpaceIssueCount(List<LaidOutNode> nodes) {
    return LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of()))
        .labelSpaceIssueCount();
  }

  @Test
  void memberTouchingLabelBandLowerEdgeIsAccepted() {
    // Band is group y 0..24 exclusive at the boundary: a member starting exactly at y 24 clears it.
    var nodes = List.of(node("member", 10.0, 24.0));

    assertThat(groupLabelBandIssueCount(nodes, singleZoneGroup("Zone"))).isZero();
  }

  @Test
  void memberOneUnitInsideLabelBandIsCounted() {
    var nodes = List.of(node("member", 10.0, 23.0));

    assertThat(groupLabelBandIssueCount(nodes, singleZoneGroup("Zone"))).isEqualTo(1);
  }

  @Test
  void groupMembersInsideLabelBandAreCounted() {
    var nodes = List.of(node("member", 10.0, 10.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), singleZoneGroup("Zone")));

    assertThat(report.groupLabelBandIssueCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
  }

  @Test
  void groupMembersBelowLabelBandAreAccepted() {
    var nodes = List.of(node("member", 10.0, 32.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), singleZoneGroup("Zone")));

    assertThat(report.groupLabelBandIssueCount()).isZero();
    assertThat(report.status()).isEqualTo("ok");
  }

  @Test
  void blankLabeledGroupHasNoLabelBandReservation() {
    var nodes = List.of(node("member", 10.0, 10.0));

    assertThat(groupLabelBandIssueCount(nodes, singleZoneGroup("  "))).isZero();
  }

  @Test
  void unlabeledGroupHasNoLabelBandReservation() {
    var nodes = List.of(node("member", 10.0, 10.0));

    assertThat(groupLabelBandIssueCount(nodes, singleZoneGroup(null))).isZero();
  }

  private static List<LaidOutGroup> singleZoneGroup(String label) {
    return List.of(
        new LaidOutGroup(
            "zone", "zone", "zone", null, 0.0, 0.0, 300.0, 200.0, List.of("member"), label));
  }

  private static int groupLabelBandIssueCount(List<LaidOutNode> nodes, List<LaidOutGroup> groups) {
    return LayoutQuality.validateLayout(layoutResult(nodes, List.of(), groups))
        .groupLabelBandIssueCount();
  }

  @Test
  void threeLevelNestedContainmentValidatesCleanly() {
    var nodes = List.of(node("leaf", 60.0, 110.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), threeLevelNestedGroups()));

    assertThat(report.groupBoundaryIssueCount()).isZero();
    assertThat(report.groupLabelBandIssueCount()).isZero();
    assertThat(report.status()).isEqualTo("ok");
  }

  @Test
  void memberEscapingDeepNestedGroupIsCounted() {
    var nodes = List.of(node("leaf", 290.0, 110.0));

    LayoutQualityReport report =
        LayoutQuality.validateLayout(layoutResult(nodes, List.of(), threeLevelNestedGroups()));

    assertThat(report.groupBoundaryIssueCount()).isEqualTo(1);
    assertThat(report.status()).isEqualTo("warning");
  }

  private static List<LaidOutGroup> threeLevelNestedGroups() {
    return List.of(
        new LaidOutGroup(
            "outer", "outer", "outer", null, 0.0, 0.0, 400.0, 320.0, List.of("middle"), "Outer"),
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
            "inner", "inner", "inner", null, 50.0, 80.0, 260.0, 160.0, List.of("leaf"), "Inner"));
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

  // ---------------------------------------------------------------------------------------------
  // Threshold-arithmetic boundary tests. These pin the exact constants and formulas inside the
  // geometry helper predicates so PIT MathMutator / ConditionalsBoundaryMutator survivors die: a
  // diagnostic-outcome test only proves *which* code came back, not that the arithmetic under it is
  // the intended one. Each "why this coordinate" note derives the boundary from the named
  // LayoutQuality constant, so a future reader can re-check it without running PIT.
  // ---------------------------------------------------------------------------------------------

  @Test
  void junctionInteriorDiagonalAtReachBoundaryIsAccepted() {
    // Pins distanceToSegment's point-to-segment distance formula for an INTERIOR projection
    // (t strictly between 0 and 1) on a DIAGONAL segment (dx and dy both non-zero) -- the existing
    // junctionEdge*Reach tests only exercise the clamped-endpoint case (dy == 0), which masks every
    // MathMutator on the dy terms and on lengthSquared. The edge target is a non-existent node so
    // findNode(target) == null short-circuits the endpoint checks (leaving only the junction
    // check), which is the only way to test an interior foot: a real perimeter-anchored endpoint
    // would sit <= 14 from the centre and dominate the minimum.
    // Junction 28x28 at (186,186): centre (200,200), reach = min(28,28)/2 + JUNCTION_ROUTE_TOL
    // = 14 + 2 = 16. Segment (129,128)->(289,248): dx=160, dy=120, lengthSquared=40000, and the
    // centre projects to t = 20000/40000 = 0.5 -> foot (209,188) -> distance hypot(9,12) = 15.0
    // exactly (a 3-4-5 triple). 15 <= 16 so the junction is on-route: accepted. Any perturbation of
    // the distance formula throws the value to ~58/87/101, well past 16.
    var nodes = List.of(junctionNode("j", 186.0, 186.0));
    var edges =
        List.of(
            edge(
                "into-ghost",
                "j",
                "ghost",
                List.of(new Point(129.0, 128.0), new Point(289.0, 248.0))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .isEmpty();
  }

  @Test
  void junctionInteriorDiagonalBeyondReachIsReported() {
    // Companion to the accepted case: same interior-diagonal geometry, distance just over reach.
    // Segment (132,124)->(292,244): dx=160, dy=120, t = 20000/40000 = 0.5 -> foot (212,184) ->
    // distance hypot(12,16) = 20.0 exactly. 20 > reach 16 so the junction is off its incident
    // route.
    var nodes = List.of(junctionNode("j", 186.0, 186.0));
    var edges =
        List.of(
            edge(
                "into-ghost",
                "j",
                "ghost",
                List.of(new Point(132.0, 124.0), new Point(292.0, 244.0))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE");
  }

  @Test
  void selfLoopReachingExactlyLeftEscapeBoundaryIsDegenerate() {
    // selfLoopEscapesNode escapes on the left when point.x() < left - SELF_LOOP_MIN_ESCAPE.
    // Node "a" is 100x80 at the origin: left = 0, so the boundary is x = 0 - 4 = -4. A loop whose
    // leftmost point sits exactly at x = -4 does NOT escape (-4 < -4 is false) and is degenerate.
    // ConditionalsBoundary (< -> <=) would treat -4 as an escape and suppress the diagnostic.
    var nodes = List.of(node("a", 0.0, 0.0));
    var edges =
        List.of(
            edge(
                "loop",
                "a",
                "a",
                List.of(
                    new Point(0.0, 20.0),
                    new Point(-4.0, 20.0),
                    new Point(-4.0, 60.0),
                    new Point(0.0, 60.0))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_SELF_LOOP_DEGENERATE");
  }

  @Test
  void selfLoopReachingExactlyRightEscapeBoundaryIsDegenerate() {
    // Right escape when point.x() > right + SELF_LOOP_MIN_ESCAPE. right = 0 + 100 = 100, so the
    // boundary is x = 100 + 4 = 104. A loop poking exactly to x = 104 does NOT escape (104 > 104 is
    // false) and is degenerate. Kills the ConditionalsBoundary (> -> >=) on the right test and the
    // MathMutator on right + SELF_LOOP_MIN_ESCAPE and on right = node.x() + node.width().
    var nodes = List.of(node("a", 0.0, 0.0));
    var edges =
        List.of(
            edge(
                "loop",
                "a",
                "a",
                List.of(
                    new Point(100.0, 20.0),
                    new Point(104.0, 20.0),
                    new Point(104.0, 60.0),
                    new Point(100.0, 60.0))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_SELF_LOOP_DEGENERATE");
  }

  @Test
  void selfLoopReachingExactlyTopEscapeBoundaryIsDegenerate() {
    // Top escape when point.y() < top - SELF_LOOP_MIN_ESCAPE. top = 0, boundary y = 0 - 4 = -4.
    // A loop reaching exactly y = -4 does NOT escape (-4 < -4 is false) and is degenerate.
    var nodes = List.of(node("a", 0.0, 0.0));
    var edges =
        List.of(
            edge(
                "loop",
                "a",
                "a",
                List.of(
                    new Point(20.0, 0.0),
                    new Point(20.0, -4.0),
                    new Point(60.0, -4.0),
                    new Point(60.0, 0.0))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_SELF_LOOP_DEGENERATE");
  }

  @Test
  void selfLoopReachingExactlyBottomEscapeBoundaryIsDegenerate() {
    // Bottom escape when point.y() > bottom + SELF_LOOP_MIN_ESCAPE. bottom = 0 + 80 = 80, boundary
    // y = 80 + 4 = 84. A loop reaching exactly y = 84 does NOT escape (84 > 84 is false) and is
    // degenerate. Kills the ConditionalsBoundary (> -> >=) and the MathMutator on
    // bottom + SELF_LOOP_MIN_ESCAPE and on bottom = node.y() + node.height().
    var nodes = List.of(node("a", 0.0, 0.0));
    var edges =
        List.of(
            edge(
                "loop",
                "a",
                "a",
                List.of(
                    new Point(20.0, 80.0),
                    new Point(20.0, 84.0),
                    new Point(60.0, 84.0),
                    new Point(60.0, 80.0))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .extracting(diagnostic -> diagnostic.code())
        .containsExactly("DEDIREN_LAYOUT_SELF_LOOP_DEGENERATE");
  }

  @Test
  void endpointAtTopLeftPerimeterToleranceCornerIsAccepted() {
    // pointOnNodePerimeter admits a point inside the box expanded by ROUTE_ENDPOINT_TOLERANCE
    // (1.5). Target 100x80 at (300,0): left = 300, top = 0. The expanded top-left corner is
    // (300 - 1.5, 0 - 1.5) = (298.5, -1.5); a route endpoint exactly there is on-perimeter
    // (>= left - tol AND >= top - tol both hold with equality). ConditionalsBoundary (>= -> >) on
    // either the x lower bound (L335) or the y lower bound (L337) would reject it.
    var nodes = new ArrayList<LaidOutNode>();
    nodes.add(node("source", 0.0, 0.0));
    nodes.add(node("target", 300.0, 0.0));
    var edges =
        List.of(
            edge("e", "source", "target", List.of(new Point(100.0, 40.0), new Point(298.5, -1.5))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .isEmpty();
  }

  @Test
  void endpointAtBottomRightPerimeterToleranceCornerIsAccepted() {
    // Mirror of the top-left corner on the upper bounds. Target 100x80 at (300,0): right = 400,
    // bottom = 80. The expanded bottom-right corner is (400 + 1.5, 80 + 1.5) = (401.5, 81.5); a
    // route endpoint exactly there is on-perimeter. Kills ConditionalsBoundary (<= -> <) on the x
    // upper bound (L336) and y upper bound (L338), and the MathMutator on right + tolerance /
    // bottom + tolerance.
    var nodes = new ArrayList<LaidOutNode>();
    nodes.add(node("source", 0.0, 0.0));
    nodes.add(node("target", 300.0, 0.0));
    var edges =
        List.of(
            edge("e", "source", "target", List.of(new Point(100.0, 40.0), new Point(401.5, 81.5))));

    assertThat(LayoutQuality.validateLayoutDiagnostics(layoutResult(nodes, edges, List.of())))
        .isEmpty();
  }

  @Test
  void touchingRoutesAreNotCountedAsProperCrossings() {
    // segmentsProperlyCross returns o1*o2 < 0 && o3*o4 < 0 -- interiors must intersect; a T-touch
    // (an endpoint lying ON the other segment) is NOT a crossing. A vertical run (100,-50)->(100,0)
    // touches a horizontal run (0,0)->(200,0) at the horizontal's interior point (100,0). One
    // orientation determinant is then exactly 0, so exactly one of o1*o2 / o3*o4 is 0 -> the strict
    // `< 0` keeps the product from counting. ConditionalsBoundary (< -> <=) would count 0 as a
    // cross; the MathMutator (* -> /) turns the zero product into a signed infinity that is < 0
    // (the collinear point is the divisor, the other endpoint is on the negative side). Swapping
    // the edge order moves the zero from the first factor to the second, so both orderings are
    // asserted to pin both `< 0` operators and both `*`.
    var horizontal = edge("h", "hs", "ht", List.of(new Point(0.0, 0.0), new Point(200.0, 0.0)));
    var vertical = edge("v", "vs", "vt", List.of(new Point(100.0, -50.0), new Point(100.0, 0.0)));

    assertThat(
            LayoutQuality.validateLayout(
                    layoutResult(List.of(), List.of(horizontal, vertical), List.of()))
                .edgeCrossingCount())
        .isZero();
    assertThat(
            LayoutQuality.validateLayout(
                    layoutResult(List.of(), List.of(vertical, horizontal), List.of()))
                .edgeCrossingCount())
        .isZero();
  }

  @Test
  void detourAtExactlyRatioThresholdIsNotCounted() {
    // hasExcessiveDetour needs routeLength > directLength * ROUTE_DETOUR_RATIO (1.5) AND
    // routeLength - directLength > ROUTE_DETOUR_EXCESS (240). Staple (0,0)->(0,125)->(500,125)->
    // (500,0): directLength = |0-500| + |0-0| = 500, routeLength = 125 + 500 + 125 = 750 = 1.5*500
    // exactly. The ratio test is `>` so 750 > 750 is false -> not counted (excess 250 > 240 holds,
    // so the ratio is the sole binding constraint). Kills ConditionalsBoundary (> -> >=) and
    // MathMutator (* -> /, which drops the threshold to 500/1.5 ~= 333) on the ratio test.
    var edges =
        List.of(
            edge(
                "detour",
                "s",
                "t",
                List.of(
                    new Point(0.0, 0.0),
                    new Point(0.0, 125.0),
                    new Point(500.0, 125.0),
                    new Point(500.0, 0.0))));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()))
                .routeDetourCount())
        .isZero();
  }

  @Test
  void detourAtExactlyExcessThresholdIsNotCounted() {
    // Excess boundary with the ratio comfortably satisfied. Staple (0,0)->(0,120)->(200,120)->
    // (200,0): directLength = 200, routeLength = 120 + 200 + 120 = 440, ratio 440/200 = 2.2 > 1.5.
    // routeLength - directLength = 240 = ROUTE_DETOUR_EXCESS exactly; the excess test is `>` so
    // 240 > 240 is false -> not counted. Kills ConditionalsBoundary (> -> >=) and MathMutator
    // (- -> +, which turns 240 into routeLength + directLength = 640 > 240) on the excess test.
    var edges =
        List.of(
            edge(
                "detour",
                "s",
                "t",
                List.of(
                    new Point(0.0, 0.0),
                    new Point(0.0, 120.0),
                    new Point(200.0, 120.0),
                    new Point(200.0, 0.0))));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()))
                .routeDetourCount())
        .isZero();
  }

  @Test
  void closedRouteWithZeroDirectLengthIsNeverADetour() {
    // The directLength > 0.0 guard short-circuits a closed route (first point == last point) whose
    // endpoints coincide: directLength = 0, so a straight-line "shortcut" length of 0 is never
    // exceeded. Route (0,0)->(0,300)->(300,300)->(300,0)->(0,0) has directLength 0 and routeLength
    // 1200. ConditionalsBoundary (> -> >=) on `directLength > 0.0` would let 0 through and then
    // count 1200 > 0 as an infinite-ratio detour.
    var edges =
        List.of(
            edge(
                "closed",
                "s",
                "t",
                List.of(
                    new Point(0.0, 0.0),
                    new Point(0.0, 300.0),
                    new Point(300.0, 300.0),
                    new Point(300.0, 0.0),
                    new Point(0.0, 0.0))));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()))
                .routeDetourCount())
        .isZero();
  }

  @Test
  void detourDirectLengthIsManhattanSumNotDifference() {
    // directLength = |dx| + |dy| (Manhattan). Endpoints (0,0)->(180,120) differ in BOTH axes, so
    // the sum 300 differs from the MathMutator's |dx| - |dy| = 60. Route
    // (0,0)->(-75,0)->(-75,120)->(180,120) has routeLength = 75 + 120 + 255 = 450 = 1.5 * 300, so
    // the true ratio test is exactly on its boundary and NOT counted. Under the (+ -> -) mutant
    // directLength collapses to 60, ratio 450 > 90 and excess 390 > 240 both hold -> counted, so
    // the not-counted assertion kills it. (A horizontal-only route cannot distinguish +/- because
    // dy = 0.)
    var edges =
        List.of(
            edge(
                "detour",
                "s",
                "t",
                List.of(
                    new Point(0.0, 0.0),
                    new Point(-75.0, 0.0),
                    new Point(-75.0, 120.0),
                    new Point(180.0, 120.0))));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()))
                .routeDetourCount())
        .isZero();
  }

  @Test
  void groupMemberFlushWithTopLeftCornerIsContained() {
    // rectangleContains requires innerX >= outerX AND innerY >= outerY (plus the far edges). Member
    // 100x80 sharing the group's top-left corner (0,0) has innerX == outerX and innerY == outerY,
    // so both lower-bound tests hold with equality -> contained, no boundary issue.
    // ConditionalsBoundary (>= -> >) on either lower bound would flag the flush member as escaping.
    var nodes = List.of(node("member", 0.0, 0.0));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), singleZoneGroup(null)))
                .groupBoundaryIssueCount())
        .isZero();
  }

  @Test
  void groupMemberFlushWithBottomRightCornerIsContained() {
    // Group is 300x200 at the origin (singleZoneGroup). Member 100x80 at (200,120) has
    // innerX + innerWidth = 300 == outerX + outerWidth and innerY + innerHeight = 200 ==
    // outerY + outerHeight, so both far-edge tests hold with equality -> contained.
    // ConditionalsBoundary (<= -> <) on either far edge flags it; MathMutator (+ -> -) on
    // outerX + outerWidth / outerY + outerHeight makes the right-hand side negative and rejects it.
    var nodes = List.of(node("member", 200.0, 120.0));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), singleZoneGroup(null)))
                .groupBoundaryIssueCount())
        .isZero();
  }

  @Test
  void groupMemberEscapingRightOnlyPinsInnerWidthSum() {
    // Member 100x80 at (250,50) escapes ONLY on the right: innerX + innerWidth = 350 > 300, while
    // the other three edges stay inside -> not contained, counted once. Under the MathMutator that
    // rewrites innerX + innerWidth as innerX - innerWidth (150 <= 300) the member would read as
    // contained and the count would drop to 0, so asserting exactly 1 kills that survivor -- a
    // flush member cannot distinguish +/- here (the difference is always <= the sum for width > 0).
    var nodes = List.of(node("member", 250.0, 50.0));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), singleZoneGroup(null)))
                .groupBoundaryIssueCount())
        .isEqualTo(1);
  }

  @Test
  void nodesTouchingAtSharedVerticalEdgeDoNotOverlap() {
    // rectanglesOverlap's first test is leftX < rightX + rightWidth. The first node (index 0, the
    // left operand) sits at x = 100; the second (right operand) is 100 wide at x = 0, so
    // rightX + rightWidth = 100. leftX < 100 is false (they only touch), so no overlap.
    // ConditionalsBoundary (< -> <=) would count the shared edge as an overlap. (The MathMutator
    // + -> - on this line is already killed by overlappingNodesAreCounted.)
    var nodes = List.of(node("right", 100.0, 0.0), node("left", 0.0, 0.0));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(nodes, List.of(), List.of())).overlapCount())
        .isZero();
  }

  @Test
  void escapedChildGroupIsCountedAsBoundaryIssue() {
    // Exercises the child-GROUP containment branch of countGroupBoundaryIssues (previously only its
    // node-member sibling was covered). A child group 100x100 at (50,50) escapes its 100x100 parent
    // at the origin (50 + 100 = 150 > 100). The count++ there must run once; IncrementsMutator
    // (++ -> --) would make it -1.
    var groups =
        List.of(
            new LaidOutGroup(
                "outer", "outer", "outer", null, 0.0, 0.0, 100.0, 100.0, List.of("child"), null),
            new LaidOutGroup(
                "child", "child", "child", null, 50.0, 50.0, 100.0, 100.0, List.of(), null));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), List.of(), groups))
                .groupBoundaryIssueCount())
        .isEqualTo(1);
  }

  @Test
  void edgeCrossingUnrelatedGroupIsCountedAsBoundaryIssue() {
    // Exercises the edge-through-group branch of countGroupBoundaryIssues. An edge from "a" to "b"
    // -- neither a member of the group -- routes horizontally through a 100x100 group at (200,200):
    // segment (100,250)->(400,250) intersects the group rect. The count++ there must run once;
    // IncrementsMutator (++ -> --) would make it -1.
    var groups =
        List.of(new LaidOutGroup("g", "g", "g", null, 200.0, 200.0, 100.0, 100.0, List.of(), null));
    var edges =
        List.of(
            edge("through", "a", "b", List.of(new Point(100.0, 250.0), new Point(400.0, 250.0))));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), edges, groups))
                .groupBoundaryIssueCount())
        .isEqualTo(1);
  }

  @Test
  void childGroupWithinLabelBandIsCounted() {
    // Exercises the child-GROUP branch of countGroupLabelBandIssues. Parent group is labeled, so it
    // reserves the top GROUP_LABEL_BAND_HEIGHT (24) band. A child group at (10,10) 100x50 overlaps
    // that band (its y-span 10..60 meets 0..24). The count++ there must run once; IncrementsMutator
    // (++ -> --) would make it -1.
    var groups =
        List.of(
            new LaidOutGroup(
                "parent",
                "parent",
                "parent",
                null,
                0.0,
                0.0,
                300.0,
                200.0,
                List.of("child"),
                "Zone"),
            new LaidOutGroup(
                "child", "child", "child", null, 10.0, 10.0, 100.0, 50.0, List.of(), "Child"));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), List.of(), groups))
                .groupLabelBandIssueCount())
        .isEqualTo(1);
  }

  @Test
  void segmentTiltedByExactlyGeometryEpsilonIsNotHorizontal() {
    // sameCoordinate treats two coordinates as equal when |left - right| <= GEOMETRY_EPSILON
    // (0.001). A segment (100,0.0)->(500,0.001) has |dy| == 0.001 exactly (the literal 0.001 parses
    // to the same double as the constant), so it is classified HORIZONTAL and pairs with the
    // plainly horizontal edge 16 units away (< ROUTE_CLOSE_PARALLEL_DISTANCE 20, overlap 400 >=
    // ROUTE_CLOSE_PARALLEL_MIN_OVERLAP 40) as one close-parallel run. ConditionalsBoundary
    // (<= -> <) would make |dy| == 0.001 fail the equality, drop the segment (returns null), and
    // erase the close-parallel pair -> count 0 instead of 1.
    var edges =
        List.of(
            edge("tilted", "ts", "tt", List.of(new Point(100.0, 0.0), new Point(500.0, 0.001))),
            edge("flat", "fs", "ft", List.of(new Point(100.0, 16.0), new Point(500.0, 16.0))));

    assertThat(
            LayoutQuality.validateLayout(layoutResult(List.of(), edges, List.of()))
                .routeCloseParallelCount())
        .isEqualTo(1);
  }

  private static LayoutResult layoutResult(
      List<LaidOutNode> nodes, List<LaidOutEdge> edges, List<LaidOutGroup> groups) {
    return new LayoutResult(
        ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION, "main", nodes, edges, groups, List.of());
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

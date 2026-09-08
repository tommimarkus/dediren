package dev.dediren.plugins.elklayout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.CubicBezierRoute;
import dev.dediren.contracts.layout.CubicBezierSegment;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LayoutDirection;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutEndpointMerging;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.LayoutRoutingPreferences;
import dev.dediren.contracts.layout.LayoutRoutingStyle;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.layout.PolylineRoute;
import dev.dediren.ir.RouteGeometry;
import dev.dediren.plugins.render.svg.EdgeRenderer;
import java.util.List;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.junit.jupiter.api.Test;

class LayoutRouteGeometryTest {

  @Test
  void realElkSplineUsesInheritedRootStyleAndReachesSvgAsCubicCommands() {
    LayoutResult result = new ElkLayoutEngine().layout(request(List.of()));

    assertThat(result.edges())
        .singleElement()
        .satisfies(
            edge -> {
              assertThat(edge.route()).isInstanceOf(CubicBezierRoute.class);
              CubicBezierRoute route = (CubicBezierRoute) edge.route();
              assertThat(route.segments()).isNotEmpty();
              assertThat(EdgeRenderer.pathData(edge, List.of())).startsWith("M ").contains(" C ");
            });
  }

  @Test
  void realElkSplinePreservesAContinuousCrossHierarchyRoute() {
    LayoutGroup group =
        new LayoutGroup(
            "source-group",
            "Source group",
            List.of("source"),
            GroupProvenance.semanticBacked("source-group"));

    LayoutResult result = new ElkLayoutEngine().layout(request(List.of(group)));

    assertThat(result.edges())
        .singleElement()
        .satisfies(
            edge -> {
              assertThat(edge.route()).isInstanceOf(CubicBezierRoute.class);
              CubicBezierRoute route = (CubicBezierRoute) edge.route();
              assertThat(route.segments()).isNotEmpty();
              assertThat(RouteGeometry.flatten(route)).hasSizeGreaterThan(1);
              for (CubicBezierSegment segment : route.segments()) {
                assertThat(segment.control1()).isNotNull();
                assertThat(segment.control2()).isNotNull();
                assertThat(segment.end()).isNotNull();
              }
            });
  }

  @Test
  void decodesNativeSplineControlsAndMultipleSectionsSourceToTargetInAbsoluteCoordinates() {
    ElkNode root = ElkGraphUtil.createGraph();
    root.setLocation(100, 200);
    ElkNode container = ElkGraphUtil.createNode(root);
    container.setLocation(10, 20);
    ElkEdge edge = ElkGraphUtil.createEdge(container);
    edge.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.SPLINES);

    ElkEdgeSection targetSection = section(edge, 30, 0, 60, 0, 40, 10, 50, 10);
    ElkEdgeSection sourceSection = section(edge, 0, 0, 30, 0, 10, 10, 20, 10);
    sourceSection.getOutgoingSections().add(targetSection);
    targetSection.getIncomingSections().add(sourceSection);

    assertThat(ElkLayoutEngine.route(edge))
        .isEqualTo(
            new CubicBezierRoute(
                point(110, 220),
                List.of(
                    segment(120, 230, 130, 230, 140, 220), segment(150, 230, 160, 230, 170, 220))));
  }

  @Test
  void decodesPolylineSectionsWithoutInventingDuplicateJoinPoints() {
    ElkNode root = ElkGraphUtil.createGraph();
    ElkEdge edge = ElkGraphUtil.createEdge(root);
    ElkEdgeSection second = section(edge, 20, 0, 40, 10, 30, 0);
    ElkEdgeSection first = section(edge, 0, 0, 20, 0, 10, 0);
    first.getOutgoingSections().add(second);
    second.getIncomingSections().add(first);

    assertThat(ElkLayoutEngine.route(edge))
        .isEqualTo(
            new PolylineRoute(
                List.of(point(0, 0), point(10, 0), point(20, 0), point(30, 0), point(40, 10))));
  }

  @Test
  void disconnectedPolylineSectionsAreRejectedRatherThanJoinedByAnInventedSegment() {
    ElkNode root = ElkGraphUtil.createGraph();
    ElkEdge edge = ElkGraphUtil.createEdge(root);
    ElkEdgeSection first = section(edge, 0, 0, 20, 0, 10, 0);
    ElkEdgeSection disconnected = section(edge, 30, 0, 50, 0, 40, 0);
    first.getOutgoingSections().add(disconnected);
    disconnected.getIncomingSections().add(first);

    assertThat(ElkLayoutEngine.route(edge)).isEqualTo(new PolylineRoute(List.of()));
  }

  private static ElkEdgeSection section(
      ElkEdge edge, double startX, double startY, double endX, double endY, double... bends) {
    ElkEdgeSection section = ElkGraphUtil.createEdgeSection(edge);
    section.setStartLocation(startX, startY);
    section.setEndLocation(endX, endY);
    for (int index = 0; index < bends.length; index += 2) {
      ElkGraphUtil.createBendPoint(section, bends[index], bends[index + 1]);
    }
    return section;
  }

  private static CubicBezierSegment segment(
      double c1x, double c1y, double c2x, double c2y, double endX, double endY) {
    return new CubicBezierSegment(point(c1x, c1y), point(c2x, c2y), point(endX, endY));
  }

  private static Point point(double x, double y) {
    return new Point(x, y);
  }

  private static LayoutRequest request(List<LayoutGroup> groups) {
    return new LayoutRequest(
        ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
        "spline",
        List.of(
            new LayoutNode("source", "Source", "source", 120.0, 60.0),
            new LayoutNode("target", "Target", "target", 120.0, 60.0)),
        List.of(new LayoutEdge("flow", "source", "target", "flow", "flow")),
        groups,
        List.of(),
        new LayoutPreferences(
            LayoutDirection.RIGHT,
            null,
            null,
            new LayoutRoutingPreferences(LayoutRoutingStyle.SPLINE, LayoutEndpointMerging.OFF)));
  }
}

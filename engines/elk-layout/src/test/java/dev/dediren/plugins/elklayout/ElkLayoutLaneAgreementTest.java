package dev.dediren.plugins.elklayout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lane-agreement coverage for {@link ElkLayoutEngine}.
 *
 * <p>{@code ElkLayoutEngine#layout} dispatches to one of four lanes, but there are only two
 * port-planning families and only one of them decides back-edges. A lane that imposes a layer order
 * ELK's cycle breaker cannot undo — a partition band, or a compound-node group whose direction is
 * fixed per group — must decide which edges are back-edges before it pins port sides. Today that
 * decision lives in whichever copy the lane happens to call, so the lanes disagree.
 *
 * <p>These tests drive the public engine surface with <em>one</em> logical cyclic graph,
 * re-expressed only through its {@code groups} list so the dispatch picks a different lane each
 * time, and assert the same lane-independent geometric invariant every time: no routed segment
 * crosses any node's interior. That invariant is what makes a fifth lane — or a fifth copy of port
 * planning — fail loudly instead of diverging silently.
 *
 * <p>Lives in its own class rather than in {@code ElkLayoutEngineTest} (already ~3.9k lines)
 * because the subject here is the agreement <em>between</em> lanes, not any one lane's behaviour.
 */
// lean-audit:dup-intentional — the segment/rectangle geometry below deliberately re-implements
// core's LayoutQuality connector-through-node check. `engines/elk-layout` may depend only on
// `contracts` (ArchUnit-pinned; §2), so core's checker is unreachable from here and an independent
// copy is the only way to corroborate the metric against real ELK output. Same rationale and same
// register entry as the copy in ElkLayoutEngineTest: docs/architecture-guidelines.md §12
// (LA-CODE-DUP-2).
class ElkLayoutLaneAgreementTest {

  /**
   * How far inside a node's own bounds a route may reach before it counts as crossing the body. ELK
   * places ports 1 px inside the node border, so a legitimate route stub starts up to 1 px inside;
   * 1.5 px covers that plus float drift and nothing else. A route that wraps back across a node
   * intrudes by tens of pixels.
   */
  private static final double NODE_BODY_INSET = 1.5;

  /**
   * Minimum length of the intruding sub-segment. A route that merely grazes the inset boundary for
   * a fraction of a pixel is float noise, not a crossing.
   */
  private static final double MIN_INTRUSION_LENGTH = 0.5;

  /** Lanes covered by the invariant below, with the request shape that selects each one. */
  private static List<Arguments> cyclicGraphPerLane() {
    return List.of(
        // No groups at all: layout() falls through both group branches to layoutFlat. layoutFlat
        // imposes no layer order, so ELK's cycle breaker is free to reverse every back-edge itself.
        Arguments.of("flat", cyclicRequest(List.of())),
        // Groups that are all visualOnly and none nested inside another satisfy bandableGroups(),
        // so layout() takes layoutFlatBanded. That lane derives one ELK partition per group (group
        // index becomes the partition index) and then calls layoutFlat verbatim on a group-stripped
        // request — inheriting flat's port planning, which has no back-edge concept, while the
        // partitions pin a layer order the cycle breaker can no longer undo.
        Arguments.of(
            "banded", cyclicRequest(List.of(visualOnlyPipeline(), visualOnlyGovernance()))),
        // A semantic-backed group is not bandable, so layout() takes layoutGrouped: members nest in
        // ELK compound nodes and the grouped* port family fixes each edge's endpoint sides from
        // edgeDirection(). Its isCrossGroupBackEdge() predicate requires the two endpoints to have
        // different owners, so a cycle closing inside a single group is never reversed.
        Arguments.of(
            "grouped", cyclicRequest(List.of(semanticPipeline(), semanticGovernance()))));
  }

  /**
   * The invariant every lane owes, regardless of how it plans ports: an orthogonal route may leave
   * and enter nodes at their ports, but no segment may pass through any node's body — including the
   * bodies of the segment's own source and target. A lane that pins port sides against an ordering
   * it also pinned has to double back across a node to reconnect them, and this is where that shows
   * up in the geometry.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("cyclicGraphPerLane")
  void everyLayoutLaneRoutesACyclicGraphWithoutCrossingNodeBodies(
      String lane, LayoutRequest request) {
    LayoutResult result = new ElkLayoutEngine().layout(request);

    assertThat(nodeBodyCrossings(result))
        .as(
            "the %s lane must route the same cyclic graph without driving any segment through a"
                + " node body",
            lane)
        .isEmpty();
  }

  /**
   * Census guard for the lane set above. The invariant is only lane-independent if every lane is
   * actually subject to it, so adding a fifth {@code layout*} lane must fail here until it is
   * either added to {@link #cyclicGraphPerLane()} or documented as an exclusion, rather than
   * quietly shipping a fifth copy of port planning that no test compares against the others.
   *
   * <p>{@code layoutPacked} is the one documented exclusion: it is selected by {@code
   * layout_preferences.mode = packed} and runs a different ELK algorithm (rectpacking) that does
   * not layer at all, so it never pins an order its own routing has to fight.
   */
  @Test
  void layoutDispatchExposesOnlyTheLanesThisAgreementTestCovers() throws IOException {
    String source =
        Files.readString(
            Path.of("src/main/java/dev/dediren/plugins/elklayout/ElkLayoutEngine.java"));

    Set<String> lanes = new LinkedHashSet<>();
    Matcher matcher =
        Pattern.compile("private static LayoutResult (layout\\w*)\\(").matcher(source);
    while (matcher.find()) {
      lanes.add(matcher.group(1));
    }

    assertThat(lanes)
        .as(
            "a new ElkLayoutEngine layout lane must be added to cyclicGraphPerLane() (or recorded"
                + " here as a documented exclusion, as layoutPacked is) so the node-body invariant"
                + " keeps covering every lane")
        .containsExactlyInAnyOrder(
            "layoutFlat", "layoutFlatBanded", "layoutGrouped", "layoutPacked");
  }

  // --- the one logical cyclic graph -------------------------------------------------------------

  /**
   * Four nodes carrying two nested cycles, re-used verbatim by every lane; only {@code groups}
   * changes.
   *
   * <p>{@code ingest → transform → publish → ingest} closes inside the first group, and {@code
   * publish → audit → ingest} closes across the two groups. One cycle per failure mechanism: the
   * cross-group closure is the one a partition order cannot undo (the banded lane), the intra-group
   * closure is the one {@code isCrossGroupBackEdge} refuses to reverse (the grouped lane). With no
   * groups both are ordinary back-edges for ELK's cycle breaker.
   */
  private static LayoutRequest cyclicRequest(List<LayoutGroup> groups) {
    return new LayoutRequest(
        ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
        "lane-agreement",
        List.of(
            new LayoutNode("ingest", "Ingest", "ingest", 160.0, 80.0),
            new LayoutNode("transform", "Transform", "transform", 160.0, 80.0),
            new LayoutNode("publish", "Publish", "publish", 160.0, 80.0),
            new LayoutNode("audit", "Audit", "audit", 160.0, 80.0)),
        List.of(
            new LayoutEdge("ingest-to-transform", "ingest", "transform", "flow", "e1"),
            new LayoutEdge("transform-to-publish", "transform", "publish", "flow", "e2"),
            // closes the cycle inside the pipeline group
            new LayoutEdge("publish-to-ingest", "publish", "ingest", "flow", "e3"),
            new LayoutEdge("publish-to-audit", "publish", "audit", "flow", "e4"),
            // closes the cycle across the two groups
            new LayoutEdge("audit-to-ingest", "audit", "ingest", "flow", "e5")),
        groups,
        List.of(),
        null);
  }

  private static LayoutGroup visualOnlyPipeline() {
    return new LayoutGroup(
        "pipeline",
        "Pipeline",
        List.of("ingest", "transform", "publish"),
        GroupProvenance.visualOnlyGroup());
  }

  private static LayoutGroup visualOnlyGovernance() {
    return new LayoutGroup(
        "governance", "Governance", List.of("audit"), GroupProvenance.visualOnlyGroup());
  }

  private static LayoutGroup semanticPipeline() {
    return new LayoutGroup(
        "pipeline",
        "Pipeline",
        List.of("ingest", "transform", "publish"),
        GroupProvenance.semanticBacked("pipeline"));
  }

  private static LayoutGroup semanticGovernance() {
    return new LayoutGroup(
        "governance", "Governance", List.of("audit"), GroupProvenance.semanticBacked("governance"));
  }

  // --- the lane-independent geometry check ------------------------------------------------------

  /**
   * Every place a routed segment reaches further than {@link #NODE_BODY_INSET} into a node's
   * bounds, described well enough to debug from the failure message alone.
   *
   * <p>Unlike {@code ElkLayoutEngineTest#connectorThroughNodeCount}, this deliberately does
   * <em>not</em> exempt the segment's own source and target: doubling back across your own
   * endpoint is precisely the shape a lane produces when it pins a port side against an order it
   * also pinned.
   */
  private static List<String> nodeBodyCrossings(LayoutResult result) {
    List<String> crossings = new ArrayList<>();
    for (LaidOutEdge edge : result.edges()) {
      List<Point> points = edge.points();
      for (int index = 0; index < points.size() - 1; index++) {
        Point start = points.get(index);
        Point end = points.get(index + 1);
        for (LaidOutNode node : result.nodes()) {
          double intrusion = intrusionLength(start, end, body(node));
          if (intrusion > MIN_INTRUSION_LENGTH) {
            crossings.add(
                "edge "
                    + edge.id()
                    + " segment "
                    + index
                    + " ("
                    + format(start)
                    + " -> "
                    + format(end)
                    + ") crosses node "
                    + node.id()
                    + " for "
                    + String.format("%.1f", intrusion)
                    + "px");
          }
        }
      }
    }
    return crossings;
  }

  /** A node's body: its bounds pulled in by the port inset on every side. */
  private static Box body(LaidOutNode node) {
    return new Box(
        node.x() + NODE_BODY_INSET,
        node.y() + NODE_BODY_INSET,
        node.x() + node.width() - NODE_BODY_INSET,
        node.y() + node.height() - NODE_BODY_INSET);
  }

  /**
   * Length of the part of segment {@code start -> end} that lies inside {@code box}, by
   * Liang-Barsky parametric clipping. Exact for any segment orientation — no bounding-box
   * approximation, so a
   * route that merely passes beside a node is never counted.
   */
  private static double intrusionLength(Point start, Point end, Box box) {
    if (box.maxX() <= box.minX() || box.maxY() <= box.minY()) {
      return 0.0; // node smaller than two insets: nothing meaningful to be inside of
    }
    double dx = end.x() - start.x();
    double dy = end.y() - start.y();
    double[] direction = {-dx, dx, -dy, dy};
    double[] distance = {
      start.x() - box.minX(), box.maxX() - start.x(),
      start.y() - box.minY(), box.maxY() - start.y()
    };

    double enter = 0.0;
    double exit = 1.0;
    for (int axis = 0; axis < direction.length; axis++) {
      if (direction[axis] == 0.0) {
        if (distance[axis] < 0.0) {
          return 0.0; // parallel to this edge and wholly outside it
        }
        continue;
      }
      double crossing = distance[axis] / direction[axis];
      if (direction[axis] < 0.0) {
        if (crossing > exit) {
          return 0.0;
        }
        enter = Math.max(enter, crossing);
      } else {
        if (crossing < enter) {
          return 0.0;
        }
        exit = Math.min(exit, crossing);
      }
    }
    if (exit <= enter) {
      return 0.0;
    }
    return (exit - enter) * Math.hypot(dx, dy);
  }

  private static String format(Point point) {
    return String.format("%.1f,%.1f", point.x(), point.y());
  }

  /** Axis-aligned box in min/max form, the shape the clipping math wants. */
  private record Box(double minX, double minY, double maxX, double maxY) {}
}

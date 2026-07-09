package dev.dediren.ir.quality;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.Point;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.PlacedNode;
import dev.dediren.ir.RoutedEdge;
import dev.dediren.ir.SourcePointer;
import java.util.List;
import org.junit.jupiter.api.Test;

class SequenceInvariantsTest {

  private static PlacedNode lifeline(String id, double x, double width, SourcePointer origin) {
    return new PlacedNode(id, id, id, x, 0, width, 200, id, "lifeline", origin);
  }

  private static PlacedNode interactionFrame(
      String id, double x, double y, double width, double height, SourcePointer origin) {
    return new PlacedNode(id, id, id, x, y, width, height, id, "interaction", origin);
  }

  private static RoutedEdge message(
      String id, String source, String target, List<Point> points, SourcePointer origin) {
    return new RoutedEdge(id, source, target, id, id, List.of(), points, id, origin);
  }

  // --- messageEndpointsOnLifelineAxis ---

  @Test
  void messageEndpointsOnLifelineAxis_violatesWhenEndpointOffLifelineCenter() {
    // lifelineA center-x = 10, lifelineB center-x = 110
    PlacedNode lifelineA = lifeline("lifelineA", 0, 20, new SourcePointer("/nodes/0"));
    PlacedNode lifelineB = lifeline("lifelineB", 100, 20, new SourcePointer("/nodes/1"));
    SourcePointer edgeOrigin = new SourcePointer("/edges/0");
    // first point x=15 is 5 units off lifelineA's center-x=10, well past the 1.5 tolerance.
    RoutedEdge offAxisMessage =
        message(
            "m1",
            "lifelineA",
            "lifelineB",
            List.of(new Point(15, 50), new Point(110, 50)),
            edgeOrigin);
    LaidOutScene scene =
        new LaidOutScene(
            "view1", List.of(lifelineA, lifelineB), List.of(offAxisMessage), List.of(), List.of());

    List<InvariantViolation> violations = SequenceInvariants.messageEndpointsOnLifelineAxis(scene);

    assertThat(violations).hasSize(1);
    InvariantViolation violation = violations.get(0);
    assertThat(violation.invariant()).isEqualTo("message_endpoints_on_lifeline_axis");
    assertThat(violation.elementId()).isEqualTo("m1");
    assertThat(violation.origin()).isEqualTo(edgeOrigin);
  }

  @Test
  void messageEndpointsOnLifelineAxis_holdsWhenEndpointsMatchLifelineCenters() {
    PlacedNode lifelineA = lifeline("lifelineA", 0, 20, new SourcePointer("/nodes/0"));
    PlacedNode lifelineB = lifeline("lifelineB", 100, 20, new SourcePointer("/nodes/1"));
    RoutedEdge onAxisMessage =
        message(
            "m1",
            "lifelineA",
            "lifelineB",
            List.of(new Point(10, 50), new Point(110, 50)),
            new SourcePointer("/edges/0"));
    LaidOutScene scene =
        new LaidOutScene(
            "view1", List.of(lifelineA, lifelineB), List.of(onAxisMessage), List.of(), List.of());

    assertThat(SequenceInvariants.messageEndpointsOnLifelineAxis(scene)).isEmpty();
  }

  // --- messageYStrictlyIncreasing ---

  @Test
  void messageYStrictlyIncreasing_violatesWhenLaterMessageYDoesNotIncrease() {
    PlacedNode lifelineA = lifeline("lifelineA", 0, 20, new SourcePointer("/nodes/0"));
    PlacedNode lifelineB = lifeline("lifelineB", 100, 20, new SourcePointer("/nodes/1"));
    RoutedEdge m1 =
        message(
            "m1",
            "lifelineA",
            "lifelineB",
            List.of(new Point(10, 50), new Point(110, 50)),
            new SourcePointer("/edges/0"));
    RoutedEdge m2 =
        message(
            "m2",
            "lifelineB",
            "lifelineA",
            List.of(new Point(110, 80), new Point(10, 80)),
            new SourcePointer("/edges/1"));
    // m3's y=70 goes backwards relative to m2's y=80: a decreasing step, not merely equal.
    SourcePointer m3Origin = new SourcePointer("/edges/2");
    RoutedEdge m3 =
        message(
            "m3",
            "lifelineA",
            "lifelineB",
            List.of(new Point(10, 70), new Point(110, 70)),
            m3Origin);
    LaidOutScene scene =
        new LaidOutScene(
            "view1", List.of(lifelineA, lifelineB), List.of(m1, m2, m3), List.of(), List.of());

    List<InvariantViolation> violations = SequenceInvariants.messageYStrictlyIncreasing(scene);

    assertThat(violations).hasSize(1);
    InvariantViolation violation = violations.get(0);
    assertThat(violation.invariant()).isEqualTo("message_y_strictly_increasing");
    assertThat(violation.elementId()).isEqualTo("m3");
    assertThat(violation.origin()).isEqualTo(m3Origin);
  }

  @Test
  void messageYStrictlyIncreasing_holdsWhenEachMessageYIsGreaterThanThePrevious() {
    PlacedNode lifelineA = lifeline("lifelineA", 0, 20, new SourcePointer("/nodes/0"));
    PlacedNode lifelineB = lifeline("lifelineB", 100, 20, new SourcePointer("/nodes/1"));
    RoutedEdge m1 =
        message(
            "m1",
            "lifelineA",
            "lifelineB",
            List.of(new Point(10, 50), new Point(110, 50)),
            new SourcePointer("/edges/0"));
    RoutedEdge m2 =
        message(
            "m2",
            "lifelineB",
            "lifelineA",
            List.of(new Point(110, 80), new Point(10, 80)),
            new SourcePointer("/edges/1"));
    RoutedEdge m3 =
        message(
            "m3",
            "lifelineA",
            "lifelineB",
            List.of(new Point(10, 110), new Point(110, 110)),
            new SourcePointer("/edges/2"));
    LaidOutScene scene =
        new LaidOutScene(
            "view1", List.of(lifelineA, lifelineB), List.of(m1, m2, m3), List.of(), List.of());

    assertThat(SequenceInvariants.messageYStrictlyIncreasing(scene)).isEmpty();
  }

  // --- interactionFrameEnclosesLifelines ---

  @Test
  void interactionFrameEnclosesLifelines_violatesWhenLifelineIsOutsideFrame() {
    PlacedNode frame = interactionFrame("frame1", 0, 0, 200, 300, new SourcePointer("/nodes/0"));
    PlacedNode lifelineA = lifeline("lifelineA", 10, 20, new SourcePointer("/nodes/1"));
    // frame right edge is x=200; lifelineB spans x=250..270, entirely outside the frame.
    SourcePointer lifelineBOrigin = new SourcePointer("/nodes/2");
    PlacedNode lifelineB = lifeline("lifelineB", 250, 20, lifelineBOrigin);
    RoutedEdge m1 =
        message(
            "m1",
            "lifelineA",
            "lifelineB",
            List.of(new Point(20, 50), new Point(260, 50)),
            new SourcePointer("/edges/0"));
    LaidOutScene scene =
        new LaidOutScene(
            "view1", List.of(frame, lifelineA, lifelineB), List.of(m1), List.of(), List.of());

    List<InvariantViolation> violations =
        SequenceInvariants.interactionFrameEnclosesLifelines(scene);

    assertThat(violations)
        .anySatisfy(
            violation -> {
              assertThat(violation.invariant()).isEqualTo("interaction_frame_encloses_lifelines");
              assertThat(violation.elementId()).isEqualTo("lifelineB");
              assertThat(violation.origin()).isEqualTo(lifelineBOrigin);
            });
  }

  @Test
  void interactionFrameEnclosesLifelines_holdsWhenLifelinesAndMessagesAreWithinFrame() {
    PlacedNode frame = interactionFrame("frame1", 0, 0, 200, 300, new SourcePointer("/nodes/0"));
    PlacedNode lifelineA = lifeline("lifelineA", 10, 20, new SourcePointer("/nodes/1"));
    PlacedNode lifelineB = lifeline("lifelineB", 150, 20, new SourcePointer("/nodes/2"));
    RoutedEdge m1 =
        message(
            "m1",
            "lifelineA",
            "lifelineB",
            List.of(new Point(20, 50), new Point(160, 50)),
            new SourcePointer("/edges/0"));
    LaidOutScene scene =
        new LaidOutScene(
            "view1", List.of(frame, lifelineA, lifelineB), List.of(m1), List.of(), List.of());

    assertThat(SequenceInvariants.interactionFrameEnclosesLifelines(scene)).isEmpty();
  }

  @Test
  void interactionFrameEnclosesLifelines_holdsWhenNoInteractionFrameExists() {
    PlacedNode lifelineA = lifeline("lifelineA", 10, 20, new SourcePointer("/nodes/1"));
    LaidOutScene scene =
        new LaidOutScene("view1", List.of(lifelineA), List.of(), List.of(), List.of());

    assertThat(SequenceInvariants.interactionFrameEnclosesLifelines(scene)).isEmpty();
  }
}

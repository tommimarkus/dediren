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
  void messageEndpointsOnLifelineAxis_violatesWhenOnlyTargetEndpointOffLifelineCenter() {
    // lifelineA center-x = 10, lifelineB center-x = 110
    PlacedNode lifelineA = lifeline("lifelineA", 0, 20, new SourcePointer("/nodes/0"));
    PlacedNode lifelineB = lifeline("lifelineB", 100, 20, new SourcePointer("/nodes/1"));
    SourcePointer edgeOrigin = new SourcePointer("/edges/0");
    // first point x=10 sits exactly on lifelineA's center-x=10 (source endpoint is correct);
    // last point x=150 is 40 units off lifelineB's center-x=110 (target endpoint is off-axis).
    RoutedEdge targetOffAxisMessage =
        message(
            "m1",
            "lifelineA",
            "lifelineB",
            List.of(new Point(10, 50), new Point(150, 50)),
            edgeOrigin);
    LaidOutScene scene =
        new LaidOutScene(
            "view1",
            List.of(lifelineA, lifelineB),
            List.of(targetOffAxisMessage),
            List.of(),
            List.of());

    List<InvariantViolation> violations = SequenceInvariants.messageEndpointsOnLifelineAxis(scene);

    assertThat(violations).hasSize(1);
    InvariantViolation violation = violations.get(0);
    assertThat(violation.invariant()).isEqualTo("message_endpoints_on_lifeline_axis");
    assertThat(violation.elementId()).isEqualTo("m1");
    assertThat(violation.origin()).isEqualTo(edgeOrigin);
    assertThat(violation.detail()).contains("last route point");
  }

  @Test
  void messageEndpointsOnLifelineAxis_violatesTwiceWhenBothEndpointsOffLifelineCenter() {
    PlacedNode lifelineA = lifeline("lifelineA", 0, 20, new SourcePointer("/nodes/0"));
    PlacedNode lifelineB = lifeline("lifelineB", 100, 20, new SourcePointer("/nodes/1"));
    SourcePointer edgeOrigin = new SourcePointer("/edges/0");
    // first point x=15 is off lifelineA's center-x=10, and last point x=150 is off lifelineB's
    // center-x=110: the production check tests each endpoint independently with two separate
    // `if` blocks (not else-if), so a both-off edge yields TWO violations sharing the same edge
    // id/origin, one per offending endpoint.
    RoutedEdge bothOffAxisMessage =
        message(
            "m1",
            "lifelineA",
            "lifelineB",
            List.of(new Point(15, 50), new Point(150, 50)),
            edgeOrigin);
    LaidOutScene scene =
        new LaidOutScene(
            "view1",
            List.of(lifelineA, lifelineB),
            List.of(bothOffAxisMessage),
            List.of(),
            List.of());

    List<InvariantViolation> violations = SequenceInvariants.messageEndpointsOnLifelineAxis(scene);

    assertThat(violations).hasSize(2);
    assertThat(violations)
        .allSatisfy(
            violation -> {
              assertThat(violation.invariant()).isEqualTo("message_endpoints_on_lifeline_axis");
              assertThat(violation.elementId()).isEqualTo("m1");
              assertThat(violation.origin()).isEqualTo(edgeOrigin);
            });
    assertThat(violations).anySatisfy(v -> assertThat(v.detail()).contains("first route point"));
    assertThat(violations).anySatisfy(v -> assertThat(v.detail()).contains("last route point"));
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
  void messageYStrictlyIncreasing_violatesWhenConsecutiveMessagesShareTheSameY() {
    PlacedNode lifelineA = lifeline("lifelineA", 0, 20, new SourcePointer("/nodes/0"));
    PlacedNode lifelineB = lifeline("lifelineB", 100, 20, new SourcePointer("/nodes/1"));
    RoutedEdge m1 =
        message(
            "m1",
            "lifelineA",
            "lifelineB",
            List.of(new Point(10, 50), new Point(110, 50)),
            new SourcePointer("/edges/0"));
    // m2's y=50 exactly equals m1's y=50 (not less): guards the `<=` vs `<` boundary at
    // SequenceInvariants.java:97, distinct from the existing strictly-decreasing-y case above.
    SourcePointer m2Origin = new SourcePointer("/edges/1");
    RoutedEdge m2 =
        message(
            "m2",
            "lifelineB",
            "lifelineA",
            List.of(new Point(110, 50), new Point(10, 50)),
            m2Origin);
    LaidOutScene scene =
        new LaidOutScene(
            "view1", List.of(lifelineA, lifelineB), List.of(m1, m2), List.of(), List.of());

    List<InvariantViolation> violations = SequenceInvariants.messageYStrictlyIncreasing(scene);

    assertThat(violations).hasSize(1);
    InvariantViolation violation = violations.get(0);
    assertThat(violation.invariant()).isEqualTo("message_y_strictly_increasing");
    assertThat(violation.elementId()).isEqualTo("m2");
    assertThat(violation.origin()).isEqualTo(m2Origin);
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
  void
      interactionFrameEnclosesLifelines_violatesWhenMessagePointIsOutsideFrameButLifelinesAreEnclosed() {
    PlacedNode frame = interactionFrame("frame1", 0, 0, 200, 300, new SourcePointer("/nodes/0"));
    PlacedNode lifelineA = lifeline("lifelineA", 10, 20, new SourcePointer("/nodes/1"));
    PlacedNode lifelineB = lifeline("lifelineB", 150, 20, new SourcePointer("/nodes/2"));
    // both lifelines are fully inside the frame (frame right edge x=200; lifelineB spans
    // x=150..170), but the message's last route point at x=250 escapes past the frame's right
    // edge, exercising the edge-point loop (SequenceInvariants.java:166-190) on its own, with no
    // lifeline-outside-frame violation to also trigger.
    SourcePointer edgeOrigin = new SourcePointer("/edges/0");
    RoutedEdge m1 =
        message(
            "m1",
            "lifelineA",
            "lifelineB",
            List.of(new Point(20, 50), new Point(250, 50)),
            edgeOrigin);
    LaidOutScene scene =
        new LaidOutScene(
            "view1", List.of(frame, lifelineA, lifelineB), List.of(m1), List.of(), List.of());

    List<InvariantViolation> violations =
        SequenceInvariants.interactionFrameEnclosesLifelines(scene);

    assertThat(violations).hasSize(1);
    InvariantViolation violation = violations.get(0);
    assertThat(violation.invariant()).isEqualTo("interaction_frame_encloses_lifelines");
    assertThat(violation.elementId()).isEqualTo("m1");
    assertThat(violation.origin()).isEqualTo(edgeOrigin);
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

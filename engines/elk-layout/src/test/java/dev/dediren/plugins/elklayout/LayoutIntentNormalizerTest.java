package dev.dediren.plugins.elklayout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.ir.Axis;
import dev.dediren.ir.BandMember;
import dev.dediren.ir.LayoutIntent;
import dev.dediren.ir.LayoutIntent.OrderedBand;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Ports the direct-construction assertions of {@link SequenceLifelineColumnOverlapTest} and {@link
 * ElkLayoutEngineTest#normalizesSequenceMessagesToCleanHorizontalSegments()} onto {@link
 * LayoutIntentNormalizer}, which reads typed {@link LayoutIntent}s instead of stringly {@code
 * uml.sequence.*} constraints. Same geometry, same numbers — only the input source changes.
 */
class LayoutIntentNormalizerTest {

  @Test
  void rebuildsColumnsWhenElkPacksLifelinesCloserThanTheirWidth() {
    // mirror SequenceLifelineColumnOverlapTest fixtures: two lifelines at x=12,x=101 width=140,
    // plus its non-empty message-order (message "m1") so active() is genuinely exercised as true.
    LayoutResult result = overlappingTwoLifelineResult();
    List<LayoutIntent> intents =
        List.of(
            new OrderedBand(Axis.X, List.of(new BandMember("a", 0.0), new BandMember("b", 0.0))),
            new OrderedBand(Axis.Y, List.of(new BandMember("m1", 0.0))));

    LayoutResult normalized =
        LayoutIntentNormalizer.from(intents, Map.of(), Map.of()).normalize(result);

    LaidOutNode a = node(normalized, "a");
    LaidOutNode b = node(normalized, "b");
    assertThat(b.x())
        .as("lifeline columns must not overlap after normalize")
        .isGreaterThanOrEqualTo(a.x() + a.width());
  }

  @Test
  void trustsElkColumnsWhenLifelinesExactlyTouch() {
    // a occupies x=[0,140), b starts exactly at x=140: a.x + a.width == b.x, the touching boundary
    // that LayoutIntentNormalizer#columnsAreNonOverlapping treats as non-overlapping ("Touching
    // edges are allowed"). Unlike rebuildsColumnsWhenElkPacksLifelinesCloserThanTheirWidth above,
    // normalize() must trust ELK's columns here and leave both lifelines at their input x.
    LayoutResult result = exactlyTouchingTwoLifelineResult();
    List<LayoutIntent> intents =
        List.of(
            new OrderedBand(Axis.X, List.of(new BandMember("a", 0.0), new BandMember("b", 0.0))),
            new OrderedBand(Axis.Y, List.of(new BandMember("m1", 0.0))));

    LayoutResult normalized =
        LayoutIntentNormalizer.from(intents, Map.of(), Map.of()).normalize(result);

    LaidOutNode a = node(normalized, "a");
    LaidOutNode b = node(normalized, "b");
    assertThat(a.x()).as("ELK's column trusted: a keeps its input x").isEqualTo(0.0);
    assertThat(b.x()).as("ELK's column trusted: b keeps its input x").isEqualTo(140.0);
  }

  @Test
  void straightensCrossLifelineMessageToStemCenters() {
    // mirror ElkLayoutEngineTest.normalizesSequenceMessagesToCleanHorizontalSegments:
    // lifelines at x=100 and x=520, width 140 -> stem centers 170 and 590
    LayoutResult result = twoLifelineMessageWithBendPoints();
    List<LayoutIntent> intents = twoLifelineIntentsWithOneMessage("msg");

    LayoutResult normalized =
        LayoutIntentNormalizer.from(intents, Map.of(), Map.of()).normalize(result);

    List<Point> points = edge(normalized, "msg").points();
    assertThat(points).hasSize(2);
    assertThat(points.get(0).x()).isEqualTo(170.0);
    assertThat(points.get(1).x()).isEqualTo(590.0);
    assertThat(points.get(0).y()).isEqualTo(points.get(1).y());
  }

  private static LayoutResult overlappingTwoLifelineResult() {
    // ELK placed lifelines at x=12 and x=101 (89 apart) while each head box is 140 wide, so the
    // boxes overlap by 51 -- distinct x, yet overlapping. Reproduces the property-test
    // counterexample from SequenceLifelineColumnOverlapTest.
    return new LayoutResult(
        "layout-result.schema.v2",
        "seq",
        List.of(
            new LaidOutNode("a", "a", "a", 12, 0, 140, 40, "A", "lifeline"),
            new LaidOutNode("b", "b", "b", 101, 0, 140, 40, "B", "lifeline")),
        List.of(
            new LaidOutEdge(
                "m1",
                "a",
                "b",
                "m1",
                "m1",
                List.of(),
                List.of(new Point(82, 60), new Point(171, 60)),
                "m1")),
        List.of(),
        List.of());
  }

  private static LayoutResult exactlyTouchingTwoLifelineResult() {
    // a's box occupies x=[0,140); b starts exactly at x=140, so a.x + a.width == b.x -- the
    // touching (not overlapping) boundary distinct from overlappingTwoLifelineResult() above.
    return new LayoutResult(
        "layout-result.schema.v2",
        "seq",
        List.of(
            new LaidOutNode("a", "a", "a", 0, 0, 140, 40, "A", "lifeline"),
            new LaidOutNode("b", "b", "b", 140, 0, 140, 40, "B", "lifeline")),
        List.of(
            new LaidOutEdge(
                "m1",
                "a",
                "b",
                "m1",
                "m1",
                List.of(),
                List.of(new Point(70, 60), new Point(210, 60)),
                "m1")),
        List.of(),
        List.of());
  }

  @Test
  void selfMessageBecomesStemAnchoredHook() {
    LayoutResult normalized =
        LayoutIntentNormalizer.from(selfMessageIntents(), Map.of(), Map.of())
            .normalize(selfMessageResult());

    // m2 is b->b; stemX(b) = 236 + 140/2 = 306.0
    // slot y for m2 = headBottom(48) + MESSAGE_HEAD_GAP(24) + MESSAGE_Y_STEP(24) = 96.0
    List<Point> hook = edge(normalized, "m2").points();
    assertThat(hook)
        .containsExactly(
            new Point(306.0, 96.0),
            new Point(346.0, 96.0),
            new Point(346.0, 120.0),
            new Point(306.0, 120.0));
    // both endpoints sit on the stem -> satisfies the lifeline-axis invariant
    assertThat(hook.get(0).x()).isEqualTo(306.0);
    assertThat(hook.get(hook.size() - 1).x()).isEqualTo(306.0);
  }

  @Test
  void messageAfterSelfMessageClearsTheHook() {
    LayoutResult normalized =
        LayoutIntentNormalizer.from(selfMessageIntents(), Map.of(), Map.of())
            .normalize(selfMessageResult());

    double m2Top = edge(normalized, "m2").points().get(0).y(); // 96.0
    double m3Y = edge(normalized, "m3").points().get(0).y();
    // m3 must clear the hook's lower leg (m2Top + LOOP_HEIGHT = 120.0), not just MESSAGE_Y_STEP:
    // m3 = m2Top + MESSAGE_Y_STEP(24) + SELF_MESSAGE_LOOP_HEIGHT(24) = 144.0
    assertThat(m3Y).isEqualTo(144.0);
    assertThat(m3Y).isGreaterThanOrEqualTo(m2Top + 24.0);
  }

  private static List<LayoutIntent> selfMessageIntents() {
    // two lifelines a (x=0,width=140 -> stemX=70) and b (x=236,width=140 -> stemX=306), three
    // ordered messages m1 (a->b), m2 (b->b, self), m3 (b->a).
    return List.of(
        new OrderedBand(Axis.X, List.of(new BandMember("a", 0.0), new BandMember("b", 0.0))),
        new OrderedBand(
            Axis.Y,
            List.of(
                new BandMember("m1", 0.0), new BandMember("m2", 0.0), new BandMember("m3", 0.0))));
  }

  private static LayoutResult selfMessageResult() {
    return new LayoutResult(
        ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
        "sequence-view",
        List.of(
            new LaidOutNode("a", "a", "a", 0.0, 0.0, 140.0, 48.0, "A", "lifeline"),
            new LaidOutNode("b", "b", "b", 236.0, 0.0, 140.0, 48.0, "B", "lifeline")),
        List.of(
            new LaidOutEdge(
                "m1",
                "a",
                "b",
                "m1",
                "m1",
                List.of(),
                List.of(new Point(70.0, 60.0), new Point(306.0, 60.0)),
                "m1"),
            new LaidOutEdge(
                "m2",
                "b",
                "b",
                "m2",
                "m2",
                List.of(),
                List.of(new Point(306.0, 90.0), new Point(306.0, 90.0)),
                "m2"),
            new LaidOutEdge(
                "m3",
                "b",
                "a",
                "m3",
                "m3",
                List.of(),
                List.of(new Point(306.0, 120.0), new Point(70.0, 120.0)),
                "m3")),
        List.of(),
        List.of());
  }

  private static LayoutResult twoLifelineMessageWithBendPoints() {
    // mirrors ElkLayoutEngineTest#sequenceLayoutResultWithMessageBendPoints
    return new LayoutResult(
        ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
        "sequence-view",
        List.of(
            new LaidOutNode(
                "service", "service", "service", 520.0, 104.0, 140.0, 48.0, "Order Service"),
            new LaidOutNode(
                "customer", "customer", "customer", 100.0, 100.0, 140.0, 48.0, "Customer")),
        List.of(
            new LaidOutEdge(
                "msg",
                "customer",
                "service",
                "msg",
                "msg",
                List.of(),
                List.of(
                    new Point(999.0, 10.0),
                    new Point(700.0, 20.0),
                    new Point(300.0, 30.0),
                    new Point(-50.0, 40.0)),
                "placeOrder")),
        List.of(),
        List.of());
  }

  private static List<LayoutIntent> twoLifelineIntentsWithOneMessage(String messageId) {
    return List.of(
        new OrderedBand(
            Axis.X, List.of(new BandMember("customer", 0.0), new BandMember("service", 0.0))),
        new OrderedBand(Axis.Y, List.of(new BandMember(messageId, 0.0))));
  }

  private static LaidOutNode node(LayoutResult result, String id) {
    return result.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
  }

  private static LaidOutEdge edge(LayoutResult result, String id) {
    return result.edges().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
  }
}

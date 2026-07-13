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
import dev.dediren.ir.LayoutIntent.AlignmentAxis;
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
    // mirror SequenceLifelineColumnOverlapTest fixtures: two lifelines at x=12,x=101 width=140
    LayoutResult result = overlappingTwoLifelineResult();
    List<LayoutIntent> intents =
        List.of(
            new OrderedBand(Axis.X, List.of(new BandMember("a", 0.0), new BandMember("b", 0.0))),
            new AlignmentAxis(Axis.Y, List.of("a", "b")),
            new OrderedBand(Axis.Y, List.of()));

    LayoutResult normalized =
        LayoutIntentNormalizer.from(intents, Map.of(), Map.of()).normalize(result);

    LaidOutNode a = node(normalized, "a");
    LaidOutNode b = node(normalized, "b");
    assertThat(b.x())
        .as("lifeline columns must not overlap after normalize")
        .isGreaterThanOrEqualTo(a.x() + a.width());
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
        new AlignmentAxis(Axis.Y, List.of("customer", "service")),
        new OrderedBand(Axis.Y, List.of(new BandMember(messageId, 0.0))));
  }

  private static LaidOutNode node(LayoutResult result, String id) {
    return result.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
  }

  private static LaidOutEdge edge(LayoutResult result, String id) {
    return result.edges().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
  }
}

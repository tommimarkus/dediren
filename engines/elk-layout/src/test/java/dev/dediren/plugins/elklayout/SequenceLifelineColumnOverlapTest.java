package dev.dediren.plugins.elklayout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression for the ELK sequence-layout overlap defect a jqwik property test surfaced: when ELK
 * places two lifelines at distinct x but closer than their head-box width, the normalize pass must
 * rebuild non-overlapping columns rather than trust ELK's (distinct but overlapping) spacing.
 */
class SequenceLifelineColumnOverlapTest {

  @Test
  void normalizeRebuildsColumnsWhenElkPlacesLifelinesCloserThanTheirWidth() {
    // ELK placed lifelines at x=12 and x=101 (89 apart) while each head box is 140 wide, so the
    // boxes overlap by 51 — distinct x, yet overlapping. Reproduces the property-test
    // counterexample.
    LayoutRequest request =
        new LayoutRequest(
            "layout-request.schema.v2",
            "seq",
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new LayoutConstraint("seq.ll", "uml.sequence.lifeline-order", List.of("a", "b")),
                new LayoutConstraint("seq.mo", "uml.sequence.message-order", List.of("m1"))),
            null);
    LayoutResult result =
        new LayoutResult(
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

    LayoutResult normalized =
        SequenceLayoutConstraints.from(request, Map.of(), Map.of()).normalize(result);

    LaidOutNode a =
        normalized.nodes().stream().filter(n -> n.id().equals("a")).findFirst().orElseThrow();
    LaidOutNode b =
        normalized.nodes().stream().filter(n -> n.id().equals("b")).findFirst().orElseThrow();
    // Non-overlap (matches core LayoutQuality.rectanglesOverlap: touching edges are allowed):
    // the second column must start at or after the first column's right edge.
    assertThat(b.x())
        .as("lifeline columns must not overlap after normalize")
        .isGreaterThanOrEqualTo(a.x() + a.width());
  }
}

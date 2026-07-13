package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.ir.LayoutIntent.AlignmentAxis;
import dev.dediren.ir.LayoutIntent.OrderedBand;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutIntentCodecTest {

  @Test
  void roundTripsOrderedBandWithPerMemberGapsAndAlignment() {
    List<LayoutIntent> intents =
        List.of(
            new OrderedBand(
                Axis.X, List.of(new BandMember("customer", 0.0), new BandMember("service", 0.0))),
            new AlignmentAxis(Axis.Y, List.of("customer", "service")),
            new OrderedBand(
                Axis.Y,
                List.of(
                    new BandMember("m1", 0.0),
                    new BandMember("m2", 46.0),
                    new BandMember("m3", 68.0))));

    List<LayoutConstraint> wire = LayoutIntentCodec.encode("sequence-view", intents);

    assertThat(wire)
        .extracting(LayoutConstraint::kind)
        .containsExactly("ordered-band:x", "alignment-axis:y", "ordered-band:y");
    assertThat(wire.get(2).subjects()).containsExactly("m1", "m2@46.0", "m3@68.0");
    assertThat(LayoutIntentCodec.decode(wire)).isEqualTo(intents);
  }

  @Test
  void decodeRejectsUnknownKind() {
    assertThatThrownBy(
            () ->
                LayoutIntentCodec.decode(List.of(new LayoutConstraint("x", "mystery", List.of()))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

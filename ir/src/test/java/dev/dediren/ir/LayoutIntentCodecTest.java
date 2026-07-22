package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.ir.LayoutIntent.OrderedBand;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutIntentCodecTest {

  @Test
  void roundTripsOrderedBandWithPerMemberGaps() {
    List<LayoutIntent> intents =
        List.of(
            new OrderedBand(
                Axis.X, List.of(new BandMember("customer", 0.0), new BandMember("service", 0.0))),
            new OrderedBand(
                Axis.Y,
                List.of(
                    new BandMember("m1", 0.0),
                    new BandMember("m2", 46.0),
                    new BandMember("m3", 68.0))));

    List<LayoutConstraint> wire = LayoutIntentCodec.encode("sequence-view", intents);

    assertThat(wire)
        .extracting(LayoutConstraint::kind)
        .containsExactly("ordered-band:x", "ordered-band:y");
    assertThat(wire.get(1).subjects()).containsExactly("m1", "m2@46.0", "m3@68.0");
    assertThat(LayoutIntentCodec.decode(wire)).isEqualTo(intents);
  }

  @Test
  void decodeRejectsUnknownKind() {
    assertThatThrownBy(
            () ->
                LayoutIntentCodec.decode(List.of(new LayoutConstraint("x", "mystery", List.of()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundTripsAnEmptyOrderedBand() {
    // The reachable "sequence view with lifelines but zero messages" shape: LayoutIntentNormalizer
    // treats an OrderedBand(Axis.Y, []) as present-but-inactive, so the codec must still round-trip
    // it faithfully rather than dropping or normalizing it away.
    List<LayoutIntent> intents = List.of(new OrderedBand(Axis.Y, List.of()));

    List<LayoutConstraint> wire = LayoutIntentCodec.encode("sequence-view", intents);

    assertThat(LayoutIntentCodec.decode(wire)).isEqualTo(intents);
  }

  @Test
  void decodeRejectsAnUnknownAxisTag() {
    assertThatThrownBy(
            () ->
                LayoutIntentCodec.decode(
                    List.of(
                        new LayoutConstraint(
                            "sequence-view.ordered-band.z", "ordered-band:z", List.of()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundTripsStemSpan() {
    List<LayoutIntent> intents =
        List.of(
            new OrderedBand(
                Axis.X, List.of(new BandMember("customer", 0.0), new BandMember("service", 0.0))),
            new LayoutIntent.StemSpan("exec-1", "service", "m1", "m4"),
            new LayoutIntent.StemSpan(
                "destroy-1", "worker", "m3", "m3")); // degenerate: a destruction

    List<LayoutConstraint> wire = LayoutIntentCodec.encode("v1", intents);

    assertThat(wire)
        .extracting(LayoutConstraint::kind)
        .containsExactly("ordered-band:x", "stem-span", "stem-span");
    assertThat(wire.get(1).subjects()).containsExactly("exec-1", "service", "m1", "m4");
    assertThat(LayoutIntentCodec.decode(wire)).isEqualTo(intents);
  }

  @Test
  void decodeRejectsAMalformedStemSpan() {
    assertThatThrownBy(
            () ->
                LayoutIntentCodec.decode(
                    List.of(
                        new LayoutConstraint(
                            "v1.stem-span.exec-1",
                            "stem-span",
                            List.of("exec-1", "service", "m1")))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decodeRejectsAMalformedGapValue() {
    // The point of this rejection was the *diagnostic*, not merely the type: the message must name
    // the offending subject and the grammar it broke (a numeric leading gap), not surface a bare
    // NumberFormatException. Pin both so a regression to the opaque message can't slip through.
    assertThatThrownBy(
            () ->
                LayoutIntentCodec.decode(
                    List.of(
                        new LayoutConstraint(
                            "sequence-view.ordered-band.y",
                            "ordered-band:y",
                            List.of("m1@notanumber")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContainingAll("m1@notanumber", "numeric leading gap", "is not a number");
    assertThatThrownBy(
            () ->
                LayoutIntentCodec.decode(
                    List.of(
                        new LayoutConstraint(
                            "sequence-view.ordered-band.y", "ordered-band:y", List.of("m1@")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContainingAll("m1@", "numeric leading gap", "is not a number");
  }
}

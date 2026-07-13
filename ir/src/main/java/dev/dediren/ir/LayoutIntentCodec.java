package dev.dediren.ir;

import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.ir.LayoutIntent.OrderedBand;
import java.util.ArrayList;
import java.util.List;

/**
 * Notation-free wire codec between neutral {@link LayoutIntent} and the record-based {@link
 * LayoutConstraint} carried on {@code SceneGraph}. Knows only the neutral {@code ordered-band:}
 * kind and the {@code @}-gap subject encoding; no notation-specific vocabulary or values live here.
 */
public final class LayoutIntentCodec {

  private static final String ORDERED_BAND_PREFIX = "ordered-band:";

  private LayoutIntentCodec() {}

  public static List<LayoutConstraint> encode(String viewId, List<LayoutIntent> intents) {
    List<LayoutConstraint> wire = new ArrayList<>();
    for (LayoutIntent intent : intents) {
      wire.add(encodeOne(viewId, intent));
    }
    return List.copyOf(wire);
  }

  private static LayoutConstraint encodeOne(String viewId, LayoutIntent intent) {
    return switch (intent) {
      case OrderedBand orderedBand -> {
        String axisTag = axisTag(orderedBand.axis());
        List<String> subjects =
            orderedBand.members().stream().map(LayoutIntentCodec::encodeMember).toList();
        yield new LayoutConstraint(
            viewId + ".ordered-band." + axisTag, ORDERED_BAND_PREFIX + axisTag, subjects);
      }
    };
  }

  private static String encodeMember(BandMember member) {
    return member.leadingGap() == 0.0
        ? member.id()
        : member.id() + "@" + encodeGap(member.leadingGap());
  }

  private static String encodeGap(double gap) {
    return Double.toString(gap);
  }

  public static List<LayoutIntent> decode(List<LayoutConstraint> wire) {
    List<LayoutIntent> intents = new ArrayList<>();
    for (LayoutConstraint constraint : wire) {
      intents.add(decodeOne(constraint));
    }
    return List.copyOf(intents);
  }

  private static LayoutIntent decodeOne(LayoutConstraint constraint) {
    String kind = constraint.kind();
    if (kind.startsWith(ORDERED_BAND_PREFIX)) {
      Axis axis = parseAxis(kind.substring(ORDERED_BAND_PREFIX.length()));
      List<BandMember> members =
          constraint.subjects().stream().map(LayoutIntentCodec::decodeMember).toList();
      return new OrderedBand(axis, members);
    }
    throw new IllegalArgumentException("Unrecognized layout constraint kind: " + kind);
  }

  private static BandMember decodeMember(String subject) {
    int at = subject.lastIndexOf('@');
    if (at < 0) {
      return new BandMember(subject, 0.0);
    }
    String id = subject.substring(0, at);
    double leadingGap = Double.parseDouble(subject.substring(at + 1));
    return new BandMember(id, leadingGap);
  }

  private static Axis parseAxis(String axisTag) {
    return switch (axisTag) {
      case "x" -> Axis.X;
      case "y" -> Axis.Y;
      default -> throw new IllegalArgumentException("Unrecognized axis tag: " + axisTag);
    };
  }

  private static String axisTag(Axis axis) {
    return switch (axis) {
      case X -> "x";
      case Y -> "y";
    };
  }
}

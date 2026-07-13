package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

/**
 * Neutral, notation-free layout intent carried on {@link SceneGraph}. A notation (e.g. {@code
 * semantics-uml}) lowers its sequence rules to these; {@code elk-layout} consumes them. Pruned to
 * the variant actually emitted: the lifeline head band is derived by elk from the lifeline {@code
 * OrderedBand(X)} itself, port-side is re-derived from that same band, and interaction-frame
 * enclosure is driven by the neutral scene {@code role=="interaction"} — so no {@code
 * AlignmentAxis}/{@code PortSideHint}/{@code Encloses} variant is introduced.
 */
public sealed interface LayoutIntent permits LayoutIntent.OrderedBand {

  /**
   * Place {@code members} in order along {@code axis}; {@code leadingGap} reserves space before a
   * member.
   */
  record OrderedBand(Axis axis, List<BandMember> members) implements LayoutIntent {
    public OrderedBand {
      members = listOrEmpty(members);
    }
  }
}

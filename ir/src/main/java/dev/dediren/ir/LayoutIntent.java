package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

/**
 * Neutral, notation-free layout intent carried on {@link SceneGraph}. A notation (e.g. {@code
 * semantics-uml}) lowers its sequence rules to these; {@code elk-layout} consumes them. Pruned to
 * the variants actually emitted: port-side is re-derived by elk from the lifeline {@code
 * OrderedBand(X)}, and interaction-frame enclosure is driven by the neutral scene {@code
 * role=="interaction"} — so no {@code PortSideHint}/{@code Encloses} variant is introduced.
 */
public sealed interface LayoutIntent permits LayoutIntent.OrderedBand, LayoutIntent.AlignmentAxis {

  /**
   * Place {@code members} in order along {@code axis}; {@code leadingGap} reserves space before a
   * member.
   */
  record OrderedBand(Axis axis, List<BandMember> members) implements LayoutIntent {
    public OrderedBand {
      members = listOrEmpty(members);
    }
  }

  /**
   * Align {@code nodeIds} on a shared {@code axis} coordinate (lifeline heads share one top band).
   */
  record AlignmentAxis(Axis axis, List<String> nodeIds) implements LayoutIntent {
    public AlignmentAxis {
      nodeIds = listOrEmpty(nodeIds);
    }
  }
}

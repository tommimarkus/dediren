package dev.dediren.semantics.uml;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

/**
 * Typed form of the {@code uml.sequence.*} layout constraints produced by {@link
 * UmlSequenceConstraints#sequenceConstraints}: lifeline order, message order (by the declared
 * {@code uml.sequence} value, then source declaration order), the first-message anchor for each
 * combined fragment's opening operand ({@link FragmentOpen}) versus every subsequent operand
 * ({@link OperandOpen}), and the two occurrence-specification variants ({@link ExecutionSpan},
 * {@link DestructionAnchor}) that place an activation bar or a destruction mark on its covered
 * lifeline. {@link UmlSequenceConstraints#lower} maps these to the neutral {@code
 * dev.dediren.ir.LayoutIntent} vocabulary that {@code elk-layout} consumes.
 */
public sealed interface SequenceConstraint
    permits SequenceConstraint.LifelineOrder,
        SequenceConstraint.MessageOrder,
        SequenceConstraint.FragmentOpen,
        SequenceConstraint.OperandOpen,
        SequenceConstraint.ExecutionSpan,
        SequenceConstraint.DestructionAnchor {

  /** Lifeline participants in column order. */
  record LifelineOrder(List<String> lifelineIds) implements SequenceConstraint {
    public LifelineOrder {
      lifelineIds = listOrEmpty(lifelineIds);
    }
  }

  /** Messages in vertical order, by declared {@code uml.sequence} then source declaration order. */
  record MessageOrder(List<String> messageIds) implements SequenceConstraint {
    public MessageOrder {
      messageIds = listOrEmpty(messageIds);
    }
  }

  /**
   * The first message of each combined fragment's opening (index-0) operand — the message that
   * needs extra vertical room for the fragment's header band and first guard.
   */
  record FragmentOpen(List<String> messageIds) implements SequenceConstraint {
    public FragmentOpen {
      messageIds = listOrEmpty(messageIds);
    }
  }

  /**
   * The first message of each combined fragment's non-first (index-&gt;=1) operand — the message
   * that needs extra vertical room for the operand separator line and guard.
   */
  record OperandOpen(List<String> messageIds) implements SequenceConstraint {
    public OperandOpen {
      messageIds = listOrEmpty(messageIds);
    }
  }

  /**
   * An activation bar: {@code executionId} sits on {@code coveredLifelineId}'s stem, spanning from
   * {@code startMessageId}'s row to {@code finishMessageId}'s row.
   */
  record ExecutionSpan(
      String executionId, String coveredLifelineId, String startMessageId, String finishMessageId)
      implements SequenceConstraint {}

  /**
   * A destruction mark (the {@code X}): {@code destructionId} sits on {@code coveredLifelineId}'s
   * stem, at the row of {@code anchorMessageId} — the selected delete-message whose target is this
   * node. {@code anchorMessageId} is {@code null} for the orphan case (no message targets this
   * destruction); {@link UmlSequenceConstraints#lower} places the orphan below the last row rather
   * than at the layout origin.
   */
  record DestructionAnchor(String destructionId, String coveredLifelineId, String anchorMessageId)
      implements SequenceConstraint {}
}

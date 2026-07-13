package dev.dediren.semantics.uml;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

/**
 * Typed form of the four {@code uml.sequence.*} layout constraints produced by {@link
 * UmlSequenceConstraints#sequenceConstraints}: lifeline order, message order (by the declared
 * {@code uml.sequence} value, then source declaration order), and the first-message anchor for each
 * combined fragment's opening operand ({@link FragmentOpen}) versus every subsequent operand
 * ({@link OperandOpen}). {@link UmlSequenceConstraints#lower} maps these to the neutral {@code
 * dev.dediren.ir.LayoutIntent} vocabulary that {@code elk-layout} consumes from Plan B P5 onward;
 * the stringly {@code uml.sequence.*} {@code LayoutConstraint} wire form produced by {@link
 * UmlSequenceConstraints#of} is unaffected and remains the live producer until the Task 5 cutover.
 */
public sealed interface SequenceConstraint
    permits SequenceConstraint.LifelineOrder,
        SequenceConstraint.MessageOrder,
        SequenceConstraint.FragmentOpen,
        SequenceConstraint.OperandOpen {

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
}

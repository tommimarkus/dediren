package dev.dediren.semantics.uml;

import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.ir.Axis;
import dev.dediren.ir.BandMember;
import dev.dediren.ir.LayoutIntent;
import dev.dediren.ir.LayoutIntent.OrderedBand;
import dev.dediren.ir.LayoutIntent.StemSpan;
import dev.dediren.semantics.uml.SequenceConstraint.DestructionAnchor;
import dev.dediren.semantics.uml.SequenceConstraint.ExecutionSpan;
import dev.dediren.semantics.uml.SequenceConstraint.FragmentOpen;
import dev.dediren.semantics.uml.SequenceConstraint.LifelineOrder;
import dev.dediren.semantics.uml.SequenceConstraint.MessageOrder;
import dev.dediren.semantics.uml.SequenceConstraint.OperandOpen;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The {@code uml.sequence.*} layout constraints for a UML sequence view: lifeline order, message
 * order (by the declared {@code uml.sequence} value, then source declaration order), the
 * first-message anchor for each combined fragment's opening operand (index 0, {@code
 * fragment-open}) versus every subsequent operand ({@code operand-open}), and the per-node
 * activation-bar ({@code ExecutionSpecification}) / destruction-mark ({@code
 * DestructionOccurrenceSpecification}) anchors. The first four facets were relocated verbatim from
 * the old single generic-graph {@code GenericGraphProjection.projectLayoutConstraints} (plus its
 * {@code operandOrder} / {@code firstMessageOfOperand} / {@code umlMessageSequence} helpers) for
 * Plan B P3; gated on {@link GenericGraphViewKind#UML_SEQUENCE} since every other UML view kind
 * contributes no layout constraints.
 *
 * <p>{@link #sequenceConstraints} scans those source facets into typed {@link SequenceConstraint}s,
 * and {@link #lower} maps them to the neutral {@code dev.dediren.ir.LayoutIntent} vocabulary elk's
 * {@code LayoutIntentNormalizer} consumes. The Plan B P5 cutover made this the single live
 * layout-constraint path and deleted the former stringly {@code uml.sequence.*} {@code
 * LayoutConstraint} producer.
 */
public final class UmlSequenceConstraints {

  // Extra vertical room reserved before a message that opens a combined fragment (header band +
  // first-operand guard) or a non-first operand (separator line + guard). Kept in sync with
  // engines/render FRAGMENT_VERTICAL_PADDING; guarded by render SequenceFragmentAlignmentTest.
  static final double FRAGMENT_OPEN_GAP = 46.0;
  static final double OPERAND_OPEN_GAP = 68.0;

  private UmlSequenceConstraints() {}

  /**
   * The source scans as typed {@link SequenceConstraint}s: empty for non-{@link
   * GenericGraphViewKind#UML_SEQUENCE} views, otherwise {@link SequenceConstraint.LifelineOrder}
   * and {@link SequenceConstraint.MessageOrder} always, {@link SequenceConstraint.FragmentOpen} and
   * {@link SequenceConstraint.OperandOpen} only when the view has combined fragments, and one
   * {@link SequenceConstraint.ExecutionSpan} / {@link SequenceConstraint.DestructionAnchor} per
   * well-formed {@code ExecutionSpecification} / {@code DestructionOccurrenceSpecification} node (a
   * malformed occurrence — {@code covered}/{@code start}/{@code finish} absent or not resolving to
   * a selected lifeline/message id — is skipped rather than emitted, so a bad model can't crash
   * layout).
   */
  static List<SequenceConstraint> sequenceConstraints(
      SourceDocument source, GenericGraphView selectedView) {
    if (selectedView.kind() != GenericGraphViewKind.UML_SEQUENCE) {
      return List.of();
    }
    Scan scan = scan(source, selectedView);

    var constraints = new ArrayList<SequenceConstraint>();
    constraints.add(new LifelineOrder(scan.lifelineIds()));
    constraints.add(new MessageOrder(scan.messageIds()));
    if (!scan.fragmentOpenIds().isEmpty()) {
      constraints.add(new FragmentOpen(scan.fragmentOpenIds()));
    }
    if (!scan.operandOpenIds().isEmpty()) {
      constraints.add(new OperandOpen(scan.operandOpenIds()));
    }
    constraints.addAll(scan.executionSpans());
    constraints.addAll(scan.destructionAnchors());
    return List.copyOf(constraints);
  }

  /**
   * Lowers typed {@link SequenceConstraint}s to the neutral {@link LayoutIntent} vocabulary: {@code
   * LifelineOrder(ids)} becomes a single X-axis {@link OrderedBand} of the lifeline columns — elk's
   * {@code LayoutIntentNormalizer} derives the shared lifeline head band from that same X band, so
   * no separate alignment vocabulary is emitted — and {@code MessageOrder} becomes a single Y-axis
   * {@link OrderedBand} whose members reserve {@link #FRAGMENT_OPEN_GAP} or {@link
   * #OPERAND_OPEN_GAP} before a message that opens a fragment or a non-first operand. A message
   * present in both sets reserves {@link #FRAGMENT_OPEN_GAP} — fragment-open takes precedence over
   * operand-open, the precedence the former elk {@code
   * SequenceLayoutConstraints#normalizedMessageYSlots} applied by checking the fragment-open set
   * first; it now lives here, baked into each band member's leading gap.
   *
   * <p>{@code ExecutionSpan} lowers to {@code StemSpan(executionId, coveredLifelineId,
   * startMessageId, finishMessageId)} — the activation bar spans the covered lifeline's stem from
   * the start message's row to the finish message's row. {@code DestructionAnchor} lowers to the
   * degenerate point span {@code StemSpan(destructionId, coveredLifelineId, anchorMessageId,
   * anchorMessageId)}; for the orphan case ({@code anchorMessageId == null}) it lowers instead to
   * {@code StemSpan(destructionId, coveredLifelineId, "", "")} — by convention, an empty from/to
   * pair means "below the last row", which the {@code elk-layout} placement code interprets.
   */
  static List<LayoutIntent> lower(List<SequenceConstraint> constraints) {
    List<String> lifelineIds = null;
    List<String> messageIds = null;
    List<String> fragmentOpenIds = List.of();
    List<String> operandOpenIds = List.of();
    var stemSpans = new ArrayList<LayoutIntent>();
    for (SequenceConstraint constraint : constraints) {
      switch (constraint) {
        case LifelineOrder lifelineOrder -> lifelineIds = lifelineOrder.lifelineIds();
        case MessageOrder messageOrder -> messageIds = messageOrder.messageIds();
        case FragmentOpen fragmentOpen -> fragmentOpenIds = fragmentOpen.messageIds();
        case OperandOpen operandOpen -> operandOpenIds = operandOpen.messageIds();
        case ExecutionSpan executionSpan ->
            stemSpans.add(
                new StemSpan(
                    executionSpan.executionId(),
                    executionSpan.coveredLifelineId(),
                    executionSpan.startMessageId(),
                    executionSpan.finishMessageId()));
        case DestructionAnchor destructionAnchor -> stemSpans.add(stemSpanFor(destructionAnchor));
      }
    }

    var intents = new ArrayList<LayoutIntent>();
    if (lifelineIds != null) {
      intents.add(
          new OrderedBand(
              Axis.X, lifelineIds.stream().map(id -> new BandMember(id, 0.0)).toList()));
    }
    if (messageIds != null) {
      Set<String> fragmentOpenSet = new HashSet<>(fragmentOpenIds);
      Set<String> operandOpenSet = new HashSet<>(operandOpenIds);
      intents.add(
          new OrderedBand(
              Axis.Y,
              messageIds.stream()
                  .map(id -> new BandMember(id, leadingGapFor(id, fragmentOpenSet, operandOpenSet)))
                  .toList()));
    }
    intents.addAll(stemSpans);
    return List.copyOf(intents);
  }

  private static StemSpan stemSpanFor(DestructionAnchor destructionAnchor) {
    String anchor = destructionAnchor.anchorMessageId();
    if (anchor == null) {
      // Orphan convention: empty from/to means "below the last row" (see the class javadoc and
      // SequenceConstraint.DestructionAnchor).
      return new StemSpan(
          destructionAnchor.destructionId(), destructionAnchor.coveredLifelineId(), "", "");
    }
    return new StemSpan(
        destructionAnchor.destructionId(), destructionAnchor.coveredLifelineId(), anchor, anchor);
  }

  private static double leadingGapFor(
      String messageId, Set<String> fragmentOpenIds, Set<String> operandOpenIds) {
    if (fragmentOpenIds.contains(messageId)) {
      return FRAGMENT_OPEN_GAP;
    }
    if (operandOpenIds.contains(messageId)) {
      return OPERAND_OPEN_GAP;
    }
    return 0.0;
  }

  private static Scan scan(SourceDocument source, GenericGraphView selectedView) {
    var selectedNodeIds = new LinkedHashSet<>(selectedView.nodes());
    var lifelineIds =
        source.nodes().stream()
            .filter(node -> selectedNodeIds.contains(node.id()))
            .filter(node -> node.type().equals("Lifeline"))
            .map(SourceNode::id)
            .toList();

    var sourceRelationshipOrder = new HashMap<String, Integer>();
    for (int index = 0; index < source.relationships().size(); index++) {
      sourceRelationshipOrder.put(source.relationships().get(index).id(), index);
    }
    var selectedRelationshipIds = new LinkedHashSet<>(selectedView.relationships());
    var messageIds =
        source.relationships().stream()
            .filter(relationship -> selectedRelationshipIds.contains(relationship.id()))
            .filter(relationship -> relationship.type().equals("Message"))
            .sorted(
                Comparator.comparing(UmlSequenceConstraints::umlMessageSequence)
                    .thenComparingInt(
                        relationship -> sourceRelationshipOrder.get(relationship.id())))
            .map(SourceRelationship::id)
            .toList();

    var messageIdSet = new HashSet<>(messageIds);
    var nodesById =
        source.nodes().stream()
            .collect(java.util.stream.Collectors.toMap(SourceNode::id, node -> node, (a, b) -> a));
    var fragmentOpenIds = new ArrayList<String>();
    var operandOpenIds = new ArrayList<String>();
    for (SourceNode node : source.nodes()) {
      if (!selectedNodeIds.contains(node.id()) || !"CombinedFragment".equals(node.type())) {
        continue;
      }
      JsonNode uml = node.properties().get("uml");
      if (uml == null) {
        continue;
      }
      var operandIds = new ArrayList<String>();
      for (JsonNode operand : uml.path("operands")) {
        operandIds.add(operand.asText());
      }
      operandIds.sort(Comparator.comparingInt(id -> operandOrder(nodesById.get(id))));
      for (int index = 0; index < operandIds.size(); index++) {
        String firstMessage =
            firstMessageOfOperand(
                nodesById.get(operandIds.get(index)), nodesById, messageIdSet, new HashSet<>());
        if (firstMessage == null) {
          continue;
        }
        if (index == 0) {
          fragmentOpenIds.add(firstMessage);
        } else {
          operandOpenIds.add(firstMessage);
        }
      }
    }

    var lifelineIdSet = new HashSet<>(lifelineIds);
    var executionSpans = new ArrayList<ExecutionSpan>();
    var destructionAnchors = new ArrayList<DestructionAnchor>();
    for (SourceNode node : source.nodes()) {
      if (!selectedNodeIds.contains(node.id())) {
        continue;
      }
      if ("ExecutionSpecification".equals(node.type())) {
        executionSpanFor(node, lifelineIdSet, messageIdSet).ifPresent(executionSpans::add);
      } else if ("DestructionOccurrenceSpecification".equals(node.type())) {
        destructionAnchorFor(node, lifelineIdSet, source, selectedRelationshipIds)
            .ifPresent(destructionAnchors::add);
      }
    }

    // Dedupe: a nested fragment whose first member is another fragment resolves to the same first
    // message via both the outer and inner iterations, so a message id can be collected twice.
    return new Scan(
        lifelineIds,
        messageIds,
        List.copyOf(new LinkedHashSet<>(fragmentOpenIds)),
        List.copyOf(new LinkedHashSet<>(operandOpenIds)),
        List.copyOf(executionSpans),
        List.copyOf(destructionAnchors));
  }

  // A malformed ExecutionSpecification (uml.covered/uml.start/uml.finish absent, or present but
  // not resolving to a selected lifeline/message id) must not crash layout, so it contributes no
  // constraint rather than an ExecutionSpan anchored to nothing.
  private static Optional<ExecutionSpan> executionSpanFor(
      SourceNode node, Set<String> lifelineIds, Set<String> messageIds) {
    JsonNode uml = node.properties().get("uml");
    if (uml == null) {
      return Optional.empty();
    }
    String covered = umlText(uml, "covered");
    String start = umlText(uml, "start");
    String finish = umlText(uml, "finish");
    if (covered == null
        || start == null
        || finish == null
        || !lifelineIds.contains(covered)
        || !messageIds.contains(start)
        || !messageIds.contains(finish)) {
      return Optional.empty();
    }
    return Optional.of(new ExecutionSpan(node.id(), covered, start, finish));
  }

  // A DestructionOccurrenceSpecification whose uml.covered is absent or doesn't resolve to a
  // selected lifeline is skipped for the same reason. A resolvable one with no targeting message
  // is the orphan case: still emitted, with anchorMessageId = null (see DestructionAnchor).
  private static Optional<DestructionAnchor> destructionAnchorFor(
      SourceNode node,
      Set<String> lifelineIds,
      SourceDocument source,
      Set<String> selectedRelationshipIds) {
    JsonNode uml = node.properties().get("uml");
    if (uml == null) {
      return Optional.empty();
    }
    String covered = umlText(uml, "covered");
    if (covered == null || !lifelineIds.contains(covered)) {
      return Optional.empty();
    }
    String anchor =
        source.relationships().stream()
            .filter(relationship -> selectedRelationshipIds.contains(relationship.id()))
            .filter(relationship -> "Message".equals(relationship.type()))
            .filter(relationship -> node.id().equals(relationship.target()))
            .map(SourceRelationship::id)
            .findFirst()
            .orElse(null);
    return Optional.of(new DestructionAnchor(node.id(), covered, anchor));
  }

  private static String umlText(JsonNode uml, String key) {
    JsonNode value = uml.get(key);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  private record Scan(
      List<String> lifelineIds,
      List<String> messageIds,
      List<String> fragmentOpenIds,
      List<String> operandOpenIds,
      List<ExecutionSpan> executionSpans,
      List<DestructionAnchor> destructionAnchors) {}

  private static int operandOrder(SourceNode operand) {
    if (operand == null) {
      return Integer.MAX_VALUE;
    }
    JsonNode uml = operand.properties().get("uml");
    JsonNode order = uml == null ? null : uml.get("order");
    return order != null && order.isNumber() ? order.asInt() : Integer.MAX_VALUE;
  }

  private static String firstMessageOfOperand(
      SourceNode operand,
      Map<String, SourceNode> nodesById,
      Set<String> messageIds,
      Set<String> visiting) {
    if (operand == null) {
      return null;
    }
    JsonNode uml = operand.properties().get("uml");
    if (uml == null) {
      return null;
    }
    for (JsonNode member : uml.path("fragments")) {
      String memberId = member.asText();
      if (messageIds.contains(memberId)) {
        return memberId;
      }
      SourceNode nested = nodesById.get(memberId);
      if (nested != null && "CombinedFragment".equals(nested.type()) && visiting.add(memberId)) {
        JsonNode nestedUml = nested.properties().get("uml");
        if (nestedUml == null) {
          continue;
        }
        var nestedOperands = new ArrayList<String>();
        for (JsonNode operandRef : nestedUml.path("operands")) {
          nestedOperands.add(operandRef.asText());
        }
        nestedOperands.sort(Comparator.comparingInt(id -> operandOrder(nodesById.get(id))));
        for (String nestedOperandId : nestedOperands) {
          String found =
              firstMessageOfOperand(
                  nodesById.get(nestedOperandId), nodesById, messageIds, visiting);
          if (found != null) {
            return found;
          }
        }
      }
    }
    return null;
  }

  private static BigInteger umlMessageSequence(SourceRelationship relationship) {
    JsonNode umlProperties = relationship.properties().get("uml");
    return umlProperties.get("sequence").bigIntegerValue();
  }
}

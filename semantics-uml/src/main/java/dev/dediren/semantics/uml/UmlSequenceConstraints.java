package dev.dediren.semantics.uml;

import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.source.GenericGraphView;
import dev.dediren.contracts.source.GenericGraphViewKind;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The four {@code uml.sequence.*} layout constraints for a UML sequence view: lifeline order,
 * message order (by the declared {@code uml.sequence} value, then source declaration order), and
 * the first-message anchor for each combined fragment's opening operand (index 0, {@code
 * fragment-open}) versus every subsequent operand ({@code operand-open}). Relocated verbatim from
 * the old single generic-graph {@code GenericGraphProjection.projectLayoutConstraints} (plus its
 * {@code operandOrder} / {@code firstMessageOfOperand} / {@code umlMessageSequence} helpers) for
 * Plan B P3; gated on {@link GenericGraphViewKind#UML_SEQUENCE} since every other UML view kind
 * contributes no layout constraints.
 */
public final class UmlSequenceConstraints {
  private UmlSequenceConstraints() {}

  static List<LayoutConstraint> of(SourceDocument source, GenericGraphView selectedView) {
    if (selectedView.kind() != GenericGraphViewKind.UML_SEQUENCE) {
      return List.of();
    }

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

    var constraints = new ArrayList<LayoutConstraint>();
    constraints.add(
        new LayoutConstraint(
            selectedView.id() + ".uml.sequence.lifeline-order",
            "uml.sequence.lifeline-order",
            lifelineIds));
    constraints.add(
        new LayoutConstraint(
            selectedView.id() + ".uml.sequence.message-order",
            "uml.sequence.message-order",
            messageIds));
    // Dedupe: a nested fragment whose first member is another fragment resolves to the same first
    // message via both the outer and inner iterations, so a message id can be collected twice.
    if (!fragmentOpenIds.isEmpty()) {
      constraints.add(
          new LayoutConstraint(
              selectedView.id() + ".uml.sequence.fragment-open",
              "uml.sequence.fragment-open",
              new ArrayList<>(new LinkedHashSet<>(fragmentOpenIds))));
    }
    if (!operandOpenIds.isEmpty()) {
      constraints.add(
          new LayoutConstraint(
              selectedView.id() + ".uml.sequence.operand-open",
              "uml.sequence.operand-open",
              new ArrayList<>(new LinkedHashSet<>(operandOpenIds))));
    }
    return constraints;
  }

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

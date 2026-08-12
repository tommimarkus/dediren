package dev.dediren.plugins.umlxmi.write.interaction;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.*;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.plugins.umlxmi.build.ExportScope;
import dev.dediren.plugins.umlxmi.build.IdentifierMap;
import dev.dediren.plugins.umlxmi.build.XmiExportException;
import dev.dediren.uml.UmlSequenceValidation;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

public final class InteractionWriter {

  private InteractionWriter() {}

  private static final String UNSUPPORTED_SEQUENCE_MESSAGE_ENDPOINT =
      DiagnosticCode.UML_XMI_SEQUENCE_MESSAGE_ENDPOINT_UNSUPPORTED.code();
  private static final String UNSUPPORTED_SEQUENCE_FRAGMENT_OPERATOR =
      DiagnosticCode.UML_XMI_SEQUENCE_FRAGMENT_OPERATOR_UNSUPPORTED.code();
  private static final String MISSING_SEQUENCE_MESSAGE_INTERACTION =
      DiagnosticCode.UML_XMI_SEQUENCE_MESSAGE_INTERACTION_MISSING.code();
  private static final String UNSUPPORTED_SEQUENCE_MESSAGE_INTERACTION =
      DiagnosticCode.UML_XMI_SEQUENCE_MESSAGE_INTERACTION_UNSUPPORTED.code();
  private static final String UNSUPPORTED_SEQUENCE_NODE =
      DiagnosticCode.UML_XMI_SEQUENCE_NODE_UNSUPPORTED.code();
  private static final Set<String> SUPPORTED_SEQUENCE_FRAGMENT_OPERATORS =
      UmlSequenceValidation.combinedFragmentOperators();

  public static void writeInteraction(
      StringBuilder xml,
      IdentifierMap ids,
      SourceNode interaction,
      String interactionId,
      List<SourceNode> sourceNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    xml.append("<packagedElement xmi:type=\"uml:Interaction\" xmi:id=\"")
        .append(attr(interactionId))
        .append("\" name=\"")
        .append(attr(interaction.label()))
        .append("\">");
    List<MessageExport> messages =
        sequenceMessages(
            ids, interaction, sourceNodes, selectedRelationships, nodeIds, relationshipIds);
    List<CombinedFragmentExport> combinedFragments =
        combinedFragments(interaction, sourceNodes, nodeIds);
    var combinedFragmentsById =
        combinedFragments.stream()
            .collect(Collectors.toMap(fragment -> fragment.node().id(), fragment -> fragment));
    var messagesBySourceId = messagesBySourceId(messages);
    Set<String> nestedCombinedFragmentIds = nestedCombinedFragmentIds(combinedFragments);
    Set<String> operandOwnedMessageIds = operandOwnedMessageIds(combinedFragments, messages);
    var messageEndpointIds = new HashSet<String>();
    for (MessageExport message : messages) {
      messageEndpointIds.add(message.sourceNodeId());
      messageEndpointIds.add(message.targetNodeId());
    }
    for (SourceNode node : sourceNodes) {
      String nodeId = nodeIds.get(node.id());
      if (nodeId != null
          && node.type().equals("Lifeline")
          && (interaction.id().equals(umlString(node, "interaction"))
              || messageEndpointIds.contains(nodeId))) {
        xml.append("<lifeline xmi:id=\"")
            .append(attr(nodeId))
            .append("\" name=\"")
            .append(attr(node.label()))
            .append("\"/>");
      }
    }
    writeTopLevelInteractionFragments(
        xml,
        ids,
        combinedFragments,
        combinedFragmentsById,
        messages,
        messagesBySourceId,
        nestedCombinedFragmentIds,
        operandOwnedMessageIds);
    // After the occurrences, so every `start`/`finish` an execution references is already declared.
    writeExecutionSpecifications(xml, interaction, sourceNodes, nodeIds, messagesBySourceId);
    for (MessageExport message : messages) {
      writeSequenceMessage(xml, message);
    }
    xml.append("</packagedElement>");
  }

  public static List<MessageExport> sequenceMessages(
      IdentifierMap ids,
      SourceNode interaction,
      List<SourceNode> sourceNodes,
      List<SourceRelationship> selectedRelationships,
      Map<String, String> nodeIds,
      Map<String, String> relationshipIds) {
    var sourceOrder = new HashMap<String, Integer>();
    for (int index = 0; index < selectedRelationships.size(); index++) {
      sourceOrder.put(selectedRelationships.get(index).id(), index);
    }
    var destructionLifelines = destructionCoveredLifelines(sourceNodes);
    return selectedRelationships.stream()
        .filter(relationship -> relationship.type().equals("Message"))
        .filter(relationship -> interaction.id().equals(umlString(relationship, "interaction")))
        .filter(relationship -> relationshipIds.containsKey(relationship.id()))
        .map(
            relationship -> {
              String sourceNodeId = nodeIds.get(relationship.source());
              // A deletion message targets a DestructionOccurrenceSpecification, which is not a
              // lifeline: the occurrence covers the lifeline that node names, and `covered` must
              // point at that lifeline rather than at the marker itself.
              String targetId =
                  destructionLifelines.getOrDefault(relationship.target(), relationship.target());
              String targetNodeId = nodeIds.get(targetId);
              if (sourceNodeId == null || targetNodeId == null) {
                return null;
              }
              return new MessageExport(
                  relationship,
                  relationshipIds.get(relationship.id()),
                  sourceNodeId,
                  targetNodeId,
                  ids.xmiId(relationship.id() + "-send-event"),
                  ids.xmiId(relationship.id() + "-receive-event"),
                  umlSequence(relationship),
                  sourceOrder.get(relationship.id()),
                  Optional.ofNullable(umlString(relationship, "message_sort")).orElse("synchCall"));
            })
        .filter(message -> message != null)
        .sorted(
            Comparator.comparing(MessageExport::sequence)
                .thenComparingInt(MessageExport::sourceOrder))
        .toList();
  }

  public static List<CombinedFragmentExport> combinedFragments(
      SourceNode interaction, List<SourceNode> sourceNodes, Map<String, String> nodeIds) {
    var sourceOrder = new HashMap<String, Integer>();
    var operandsById = new HashMap<String, OperandExport>();
    for (int index = 0; index < sourceNodes.size(); index++) {
      SourceNode node = sourceNodes.get(index);
      sourceOrder.put(node.id(), index);
      if (nodeIds.containsKey(node.id())
          && node.type().equals("InteractionOperand")
          && interaction.id().equals(umlString(node, "interaction"))) {
        operandsById.put(
            node.id(),
            new OperandExport(
                node,
                nodeIds.get(node.id()),
                umlPositiveInt(node, "order", Integer.MAX_VALUE),
                umlString(node, "guard"),
                umlTextArray(node, "fragments"),
                index));
      }
    }

    var combinedFragments = new java.util.ArrayList<CombinedFragmentExport>();
    for (SourceNode node : sourceNodes) {
      if (!nodeIds.containsKey(node.id())
          || !node.type().equals("CombinedFragment")
          || !interaction.id().equals(umlString(node, "interaction"))) {
        continue;
      }
      List<String> operandIds = umlTextArray(node, "operands");
      var operandListOrder = new HashMap<String, Integer>();
      for (int index = 0; index < operandIds.size(); index++) {
        operandListOrder.put(operandIds.get(index), index);
      }
      List<OperandExport> operands =
          operandIds.stream()
              .map(operandsById::get)
              .filter(operand -> operand != null)
              .sorted(
                  Comparator.comparingInt(
                          (OperandExport operand) ->
                              operandListOrder.getOrDefault(operand.node().id(), Integer.MAX_VALUE))
                      .thenComparingInt(OperandExport::order)
                      .thenComparingInt(OperandExport::sourceOrder))
              .toList();
      List<String> coveredNodeIds =
          umlTextArray(node, "covered").stream()
              .map(nodeIds::get)
              .filter(coveredNodeId -> coveredNodeId != null)
              .toList();
      combinedFragments.add(
          new CombinedFragmentExport(
              node,
              nodeIds.get(node.id()),
              umlString(node, "operator"),
              operands,
              coveredNodeIds,
              sourceOrder.get(node.id())));
    }
    return combinedFragments.stream()
        .sorted(Comparator.comparingInt(CombinedFragmentExport::sourceOrder))
        .toList();
  }

  public static Map<String, MessageExport> messagesBySourceId(List<MessageExport> messages) {
    return messages.stream()
        .collect(Collectors.toMap(message -> message.relationship().id(), message -> message));
  }

  public static Set<String> nestedCombinedFragmentIds(
      List<CombinedFragmentExport> combinedFragments) {
    Set<String> combinedFragmentIds =
        combinedFragments.stream()
            .map(fragment -> fragment.node().id())
            .collect(Collectors.toSet());
    var nestedIds = new HashSet<String>();
    for (CombinedFragmentExport combinedFragment : combinedFragments) {
      for (OperandExport operand : combinedFragment.operands()) {
        for (String fragmentId : operand.fragmentIds()) {
          if (combinedFragmentIds.contains(fragmentId)) {
            nestedIds.add(fragmentId);
          }
        }
      }
    }
    return nestedIds;
  }

  public static Set<String> operandOwnedMessageIds(
      List<CombinedFragmentExport> combinedFragments, List<MessageExport> messages) {
    Set<String> messageIds =
        messages.stream().map(message -> message.relationship().id()).collect(Collectors.toSet());
    var ownedMessageIds = new HashSet<String>();
    for (CombinedFragmentExport combinedFragment : combinedFragments) {
      for (OperandExport operand : combinedFragment.operands()) {
        for (String fragmentId : operand.fragmentIds()) {
          if (messageIds.contains(fragmentId)) {
            ownedMessageIds.add(fragmentId);
          }
        }
      }
    }
    return ownedMessageIds;
  }

  public static void writeTopLevelInteractionFragments(
      StringBuilder xml,
      IdentifierMap ids,
      List<CombinedFragmentExport> combinedFragments,
      Map<String, CombinedFragmentExport> combinedFragmentsById,
      List<MessageExport> messages,
      Map<String, MessageExport> messagesBySourceId,
      Set<String> nestedCombinedFragmentIds,
      Set<String> operandOwnedMessageIds) {
    var fragments = new java.util.ArrayList<TopLevelInteractionFragment>();
    for (CombinedFragmentExport combinedFragment : combinedFragments) {
      if (nestedCombinedFragmentIds.contains(combinedFragment.node().id())) {
        continue;
      }
      fragments.add(
          new TopLevelInteractionFragment(
              combinedFragmentSequence(combinedFragment, combinedFragmentsById, messagesBySourceId),
              combinedFragment.sourceOrder(),
              combinedFragment,
              null));
    }
    for (MessageExport message : messages) {
      if (operandOwnedMessageIds.contains(message.relationship().id())) {
        continue;
      }
      fragments.add(
          new TopLevelInteractionFragment(
              message.sequence(), message.sourceOrder(), null, message));
    }
    fragments.sort(
        Comparator.comparing(TopLevelInteractionFragment::sequence)
            .thenComparingInt(TopLevelInteractionFragment::sourceOrder));
    for (TopLevelInteractionFragment fragment : fragments) {
      if (fragment.combinedFragment() != null) {
        writeCombinedFragment(
            xml, ids, fragment.combinedFragment(), combinedFragmentsById, messagesBySourceId);
      } else if (fragment.message() != null) {
        MessageExport message = fragment.message();
        writeMessageOccurrence(
            xml, message, "send", message.sourceEventId(), message.sourceNodeId());
        writeMessageOccurrence(
            xml, message, "receive", message.receiveEventId(), message.targetNodeId());
      }
    }
  }

  public static BigInteger combinedFragmentSequence(
      CombinedFragmentExport combinedFragment,
      Map<String, CombinedFragmentExport> combinedFragmentsById,
      Map<String, MessageExport> messagesBySourceId) {
    return combinedFragmentSequence(
        combinedFragment, combinedFragmentsById, messagesBySourceId, new HashSet<>());
  }

  public static BigInteger combinedFragmentSequence(
      CombinedFragmentExport combinedFragment,
      Map<String, CombinedFragmentExport> combinedFragmentsById,
      Map<String, MessageExport> messagesBySourceId,
      Set<String> visitedCombinedFragmentIds) {
    if (!visitedCombinedFragmentIds.add(combinedFragment.node().id())) {
      return BigInteger.valueOf(Integer.MAX_VALUE);
    }

    BigInteger firstSequence = null;
    for (OperandExport operand : combinedFragment.operands()) {
      for (String fragmentId : operand.fragmentIds()) {
        MessageExport message = messagesBySourceId.get(fragmentId);
        BigInteger sequence = message == null ? null : message.sequence();
        if (sequence == null) {
          CombinedFragmentExport nestedCombinedFragment = combinedFragmentsById.get(fragmentId);
          sequence =
              nestedCombinedFragment == null
                  ? null
                  : combinedFragmentSequence(
                      nestedCombinedFragment,
                      combinedFragmentsById,
                      messagesBySourceId,
                      visitedCombinedFragmentIds);
        }
        if (sequence != null && (firstSequence == null || sequence.compareTo(firstSequence) < 0)) {
          firstSequence = sequence;
        }
      }
    }
    return firstSequence == null ? BigInteger.valueOf(Integer.MAX_VALUE) : firstSequence;
  }

  public static void writeCombinedFragment(
      StringBuilder xml,
      IdentifierMap ids,
      CombinedFragmentExport combinedFragment,
      Map<String, CombinedFragmentExport> combinedFragmentsById,
      Map<String, MessageExport> messagesBySourceId) {
    xml.append("<fragment xmi:type=\"uml:CombinedFragment\" xmi:id=\"")
        .append(attr(combinedFragment.fragmentId()))
        .append("\" name=\"")
        .append(attr(combinedFragment.node().label()))
        .append("\" interactionOperator=\"")
        .append(attr(combinedFragment.operator()))
        .append("\"");
    if (!combinedFragment.coveredNodeIds().isEmpty()) {
      xml.append(" covered=\"")
          .append(attr(String.join(" ", combinedFragment.coveredNodeIds())))
          .append("\"");
    }
    xml.append(">");
    for (OperandExport operand : combinedFragment.operands()) {
      writeOperand(xml, ids, operand, combinedFragmentsById, messagesBySourceId);
    }
    xml.append("</fragment>");
  }

  public static void writeOperand(
      StringBuilder xml,
      IdentifierMap ids,
      OperandExport operand,
      Map<String, CombinedFragmentExport> combinedFragmentsById,
      Map<String, MessageExport> messagesBySourceId) {
    xml.append("<operand xmi:id=\"")
        .append(attr(operand.operandId()))
        .append("\" name=\"")
        .append(attr(operand.node().label()))
        .append("\">");
    if (operand.guard() != null) {
      String guardId = ids.xmiId(operand.node().id() + "-guard");
      String specificationId = ids.xmiId(operand.node().id() + "-guard-specification");
      xml.append("<guard xmi:type=\"uml:InteractionConstraint\" xmi:id=\"")
          .append(attr(guardId))
          .append("\" name=\"")
          .append(attr(operand.guard()))
          .append("\">")
          .append("<specification xmi:type=\"uml:OpaqueExpression\" xmi:id=\"")
          .append(attr(specificationId))
          .append("\">")
          .append("<body>")
          .append(text(operand.guard()))
          .append("</body>")
          .append("</specification></guard>");
    }
    for (String fragmentId : operand.fragmentIds()) {
      MessageExport message = messagesBySourceId.get(fragmentId);
      if (message != null) {
        writeMessageOccurrence(
            xml, message, "send", message.sourceEventId(), message.sourceNodeId());
        writeMessageOccurrence(
            xml, message, "receive", message.receiveEventId(), message.targetNodeId());
        continue;
      }
      CombinedFragmentExport nestedCombinedFragment = combinedFragmentsById.get(fragmentId);
      if (nestedCombinedFragment != null) {
        writeCombinedFragment(
            xml, ids, nestedCombinedFragment, combinedFragmentsById, messagesBySourceId);
      }
    }
    xml.append("</operand>");
  }

  public static void writeMessageOccurrence(
      StringBuilder xml, MessageExport message, String kind, String eventId, String coveredNodeId) {
    // UML 2.5.1 §17.4: an object-deletion Message must end in a DestructionOccurrenceSpecification
    // (a subclass of MessageOccurrenceSpecification, so it still carries covered/message). The
    // receiveEvent of a deleteMessage therefore uses the destruction subtype.
    String occurrenceType =
        "receive".equals(kind) && "deleteMessage".equals(message.messageSort())
            ? "uml:DestructionOccurrenceSpecification"
            : "uml:MessageOccurrenceSpecification";
    xml.append("<fragment xmi:type=\"")
        .append(occurrenceType)
        .append("\" xmi:id=\"")
        .append(attr(eventId))
        .append("\" name=\"")
        .append(attr(message.relationship().label()))
        .append(" ")
        .append(kind)
        .append("\" covered=\"")
        .append(attr(coveredNodeId))
        .append("\" message=\"")
        .append(attr(message.messageId()))
        .append("\"/>");
  }

  public static void writeSequenceMessage(StringBuilder xml, MessageExport message) {
    xml.append("<message xmi:id=\"")
        .append(attr(message.messageId()))
        .append("\" name=\"")
        .append(attr(message.relationship().label()))
        .append("\" messageSort=\"")
        .append(attr(message.messageSort()))
        .append("\" sendEvent=\"")
        .append(attr(message.sourceEventId()))
        .append("\" receiveEvent=\"")
        .append(attr(message.receiveEventId()))
        .append("\"/>");
  }

  public static void validateSelectedCombinedFragmentOperators(
      ExportRequest request, GenericGraphPluginData pluginData) throws XmiExportException {
    ExportScope scope = ExportScope.fromRequest(request, pluginData);
    for (int index = 0; index < request.source().nodes().size(); index++) {
      SourceNode node = request.source().nodes().get(index);
      if (!scope.nodeIds().contains(node.id()) || !node.type().equals("CombinedFragment")) {
        continue;
      }
      String operator = umlString(node, "operator");
      if (operator != null && !SUPPORTED_SEQUENCE_FRAGMENT_OPERATORS.contains(operator)) {
        throw new XmiExportException(
            UNSUPPORTED_SEQUENCE_FRAGMENT_OPERATOR,
            "UML/XMI sequence export supports CombinedFragment operators only: alt, opt, loop, par",
            "$.nodes[" + index + "].properties.uml.operator");
      }
    }
  }

  public static void validateExportableSequenceScope(ExportRequest request)
      throws XmiExportException {
    ExportScope scope = ExportScope.fromRequest(request);
    var sourceNodesById =
        request.source().nodes().stream().collect(Collectors.toMap(SourceNode::id, node -> node));

    for (int index = 0; index < request.source().relationships().size(); index++) {
      SourceRelationship relationship = request.source().relationships().get(index);
      if (!scope.relationshipIds().contains(relationship.id())
          || !relationship.type().equals("Message")) {
        continue;
      }
      String interactionId = umlString(relationship, "interaction");
      if (interactionId == null) {
        throw new XmiExportException(
            MISSING_SEQUENCE_MESSAGE_INTERACTION,
            "UML/XMI sequence export requires selected Message relationships to define textual properties.uml.interaction",
            "$.relationships[" + index + "].properties.uml.interaction");
      }
      SourceNode interaction = sourceNodesById.get(interactionId);
      if (interaction == null || !interaction.type().equals("Interaction")) {
        throw new XmiExportException(
            UNSUPPORTED_SEQUENCE_MESSAGE_INTERACTION,
            "UML/XMI sequence export requires selected Message properties.uml.interaction to resolve to an Interaction node",
            "$.relationships[" + index + "].properties.uml.interaction");
      }
      SourceNode source = sourceNodesById.get(relationship.source());
      SourceNode target = sourceNodesById.get(relationship.target());
      // §17.12.6.3 / §17.12.22.3: a deletion message ends in a DestructionOccurrenceSpecification,
      // which covers the lifeline being destroyed. Core has always accepted that endpoint;
      // demanding
      // Lifeline -> Lifeline here made a committed, bundle-shipped fixture validate and render and
      // then die on its last stage. The occurrence must still name the lifeline it destroys —
      // otherwise the emitted `covered` would point at the marker node instead of a lifeline.
      boolean destructionTarget =
          target != null
              && target.type().equals("DestructionOccurrenceSpecification")
              && isLifeline(sourceNodesById.get(umlString(target, "covered")));
      if (source != null
          && target != null
          && (!source.type().equals("Lifeline")
              || !(target.type().equals("Lifeline") || destructionTarget))) {
        throw new XmiExportException(
            UNSUPPORTED_SEQUENCE_MESSAGE_ENDPOINT,
            "UML/XMI sequence export supports selected Message endpoints only between Lifeline nodes in this MVP: "
                + source.type()
                + " -> "
                + target.type(),
            "$.relationships[" + index + "]");
      }
    }

    for (int index = 0; index < request.source().nodes().size(); index++) {
      SourceNode node = request.source().nodes().get(index);
      if (scope.nodeIds().contains(node.id()) && unsupportedSequenceNode(node.type())) {
        throw new XmiExportException(
            UNSUPPORTED_SEQUENCE_NODE,
            "UML/XMI sequence export does not support selected "
                + node.type()
                + " nodes in this MVP",
            "$.nodes[" + index + "]");
      }
    }
  }

  private static boolean isLifeline(SourceNode node) {
    return node != null && node.type().equals("Lifeline");
  }

  /** Each DestructionOccurrenceSpecification node id mapped to the lifeline id it covers. */
  private static Map<String, String> destructionCoveredLifelines(List<SourceNode> sourceNodes) {
    var covered = new HashMap<String, String>();
    for (SourceNode node : sourceNodes) {
      if (!node.type().equals("DestructionOccurrenceSpecification")) {
        continue;
      }
      String lifelineId = umlString(node, "covered");
      if (lifelineId != null) {
        covered.put(node.id(), lifelineId);
      }
    }
    return covered;
  }

  public static boolean unsupportedSequenceNode(String type) {
    // A DestructionOccurrenceSpecification is not exported as a node of its own: it names the
    // lifeline it destroys, and writeMessageOccurrence already emits the destruction subtype for
    // the receive event of a deleteMessage. A Gate has no source surface beyond its own node, so
    // it has nothing to attach an actual message to.
    return type.equals("Gate");
  }

  /**
   * Emits the {@code uml:BehaviorExecutionSpecification} fragments for one interaction.
   *
   * <p>§17.12.4 bounds an ExecutionSpecification by two OccurrenceSpecifications and covers the
   * Lifeline it runs on. The source model names the two bounds by message, not by occurrence, so
   * each bound resolves to the occurrence <em>on the covered lifeline</em>: the receive event when
   * the message arrives there, the send event when it leaves. An execution whose covered lifeline
   * or either bound is out of scope is skipped rather than emitted with a dangling reference.
   */
  private static void writeExecutionSpecifications(
      StringBuilder xml,
      SourceNode interaction,
      List<SourceNode> sourceNodes,
      Map<String, String> nodeIds,
      Map<String, MessageExport> messagesBySourceId) {
    for (SourceNode node : sourceNodes) {
      if (!node.type().equals("ExecutionSpecification")
          || !nodeIds.containsKey(node.id())
          || !interaction.id().equals(umlString(node, "interaction"))
              && !isCoveredByInteraction(node, interaction, nodeIds, messagesBySourceId)) {
        continue;
      }
      String lifeline = umlString(node, "covered");
      String lifelineId = lifeline == null ? null : nodeIds.get(lifeline);
      String start = occurrenceOnLifeline(umlString(node, "start"), lifeline, messagesBySourceId);
      String finish = occurrenceOnLifeline(umlString(node, "finish"), lifeline, messagesBySourceId);
      if (lifelineId == null || start == null || finish == null) {
        continue;
      }
      xml.append("<fragment xmi:type=\"uml:BehaviorExecutionSpecification\" xmi:id=\"")
          .append(attr(nodeIds.get(node.id())))
          .append("\" name=\"")
          .append(attr(node.label()))
          .append("\" covered=\"")
          .append(attr(lifelineId))
          .append("\" start=\"")
          .append(attr(start))
          .append("\" finish=\"")
          .append(attr(finish))
          .append("\"/>");
    }
  }

  /** An execution belongs to the interaction whose messages bound it. */
  private static boolean isCoveredByInteraction(
      SourceNode node,
      SourceNode interaction,
      Map<String, String> nodeIds,
      Map<String, MessageExport> messagesBySourceId) {
    MessageExport start = messagesBySourceId.get(umlString(node, "start"));
    return start != null && interaction.id().equals(umlString(start.relationship(), "interaction"));
  }

  /** The occurrence of {@code messageId} that sits on {@code lifelineId}, send or receive. */
  private static String occurrenceOnLifeline(
      String messageId, String lifelineId, Map<String, MessageExport> messagesBySourceId) {
    MessageExport message = messageId == null ? null : messagesBySourceId.get(messageId);
    if (message == null || lifelineId == null) {
      return null;
    }
    if (lifelineId.equals(message.relationship().target())) {
      return message.receiveEventId();
    }
    return lifelineId.equals(message.relationship().source()) ? message.sourceEventId() : null;
  }

  private record CombinedFragmentExport(
      SourceNode node,
      String fragmentId,
      String operator,
      List<OperandExport> operands,
      List<String> coveredNodeIds,
      int sourceOrder) {}

  private record OperandExport(
      SourceNode node,
      String operandId,
      int order,
      String guard,
      List<String> fragmentIds,
      int sourceOrder) {}

  private record TopLevelInteractionFragment(
      BigInteger sequence,
      int sourceOrder,
      CombinedFragmentExport combinedFragment,
      MessageExport message) {}

  private record MessageExport(
      SourceRelationship relationship,
      String messageId,
      String sourceNodeId,
      String targetNodeId,
      String sourceEventId,
      String receiveEventId,
      BigInteger sequence,
      int sourceOrder,
      String messageSort) {}
}

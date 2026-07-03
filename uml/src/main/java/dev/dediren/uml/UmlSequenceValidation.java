package dev.dediren.uml;

import static dev.dediren.uml.UmlProperties.containsTextValue;
import static dev.dediren.uml.UmlProperties.optionalArrayProperty;
import static dev.dediren.uml.UmlProperties.optionalProperty;
import static dev.dediren.uml.UmlProperties.readTextProperty;
import static dev.dediren.uml.UmlProperties.requireNodeType;
import static dev.dediren.uml.UmlProperties.requiredNonEmptyArrayProperty;
import static dev.dediren.uml.UmlProperties.requiredPositiveIntegerProperty;
import static dev.dediren.uml.UmlProperties.requiredTextArrayEntry;
import static dev.dediren.uml.UmlProperties.requiredTextProperty;
import static dev.dediren.uml.UmlProperties.textValueSet;

import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * UML interaction/sequence validation: combined fragments, interaction operands, message
 * sequencing, and fragment coverage/nesting/ownership. Extracted verbatim from {@link Uml} to keep
 * the facade small; behavior, diagnostic codes, and JSON paths are unchanged.
 */
final class UmlSequenceValidation {

  private static final Set<String> MESSAGE_SORTS =
      Set.of("synchCall", "asynchCall", "asynchSignal", "reply", "createMessage", "deleteMessage");
  private static final Set<String> COMBINED_FRAGMENT_OPERATORS =
      Set.of("alt", "opt", "loop", "par");

  private UmlSequenceValidation() {}

  static void validateSelectedCombinedFragmentProperties(
      JsonNode umlProperties, String path, Set<String> selectedNodeIds)
      throws UmlValidationException {
    JsonNode operands = optionalProperty(umlProperties, "operands");
    if (operands != null && operands.isArray()) {
      for (int operandIndex = 0; operandIndex < operands.size(); operandIndex++) {
        String operandId = requiredTextArrayEntry(operands, operandIndex, path + ".operands");
        if (!selectedNodeIds.contains(operandId)) {
          throw new UmlValidationException(
              UmlTypeKind.ELEMENT_PROPERTY, operandId, path + ".operands[" + operandIndex + "]");
        }
      }
    }

    JsonNode covered = optionalProperty(umlProperties, "covered");
    if (covered != null && covered.isArray()) {
      for (int coveredIndex = 0; coveredIndex < covered.size(); coveredIndex++) {
        String lifelineId = requiredTextArrayEntry(covered, coveredIndex, path + ".covered");
        if (!selectedNodeIds.contains(lifelineId)) {
          throw new UmlValidationException(
              UmlTypeKind.ELEMENT_PROPERTY, lifelineId, path + ".covered[" + coveredIndex + "]");
        }
      }
    }
  }

  static void validateSelectedInteractionOperandProperties(
      String nodeId,
      JsonNode umlProperties,
      String path,
      Set<String> selectedNodeIds,
      Set<String> selectedRelationshipIds,
      ValidationContext context)
      throws UmlValidationException {
    String combinedFragment =
        requiredTextProperty(
            umlProperties, "combined_fragment", "InteractionOperand.combined_fragment", path);
    if (!selectedNodeIds.contains(combinedFragment)) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, combinedFragment, path + ".combined_fragment");
    }
    JsonNode ownerOperands =
        optionalProperty(context.nodeUmlProperties().get(combinedFragment), "operands");
    if (ownerOperands == null
        || !ownerOperands.isArray()
        || !containsTextValue(ownerOperands, nodeId)) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, combinedFragment, path + ".combined_fragment");
    }

    JsonNode fragments = optionalProperty(umlProperties, "fragments");
    if (fragments == null || !fragments.isArray()) {
      return;
    }
    for (int fragmentIndex = 0; fragmentIndex < fragments.size(); fragmentIndex++) {
      String fragmentId = requiredTextArrayEntry(fragments, fragmentIndex, path + ".fragments");
      boolean selected =
          isMessageRelationship(fragmentId, context)
              ? selectedRelationshipIds.contains(fragmentId)
              : selectedNodeIds.contains(fragmentId);
      if (!selected) {
        throw new UmlValidationException(
            UmlTypeKind.ELEMENT_PROPERTY, fragmentId, path + ".fragments[" + fragmentIndex + "]");
      }
    }
  }

  static void validateCombinedFragmentProperties(
      String nodeId, JsonNode umlProperties, String path, ValidationContext context)
      throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    String interaction =
        requiredTextProperty(umlProperties, "interaction", "CombinedFragment.interaction", umlPath);
    requireNodeType(interaction, "Interaction", context.nodeTypes(), umlPath + ".interaction");

    String operator =
        requiredTextProperty(umlProperties, "operator", "CombinedFragment.operator", umlPath);
    if (!COMBINED_FRAGMENT_OPERATORS.contains(operator)) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, operator, umlPath + ".operator");
    }

    JsonNode operands =
        requiredNonEmptyArrayProperty(
            umlProperties, "operands", "CombinedFragment.operands", umlPath);
    validateOperandCount(operator, operands, umlPath + ".operands");
    var operandOrders = new HashSet<BigInteger>();
    for (int operandIndex = 0; operandIndex < operands.size(); operandIndex++) {
      String operandId = requiredTextArrayEntry(operands, operandIndex, umlPath + ".operands");
      requireNodeType(
          operandId,
          "InteractionOperand",
          context.nodeTypes(),
          umlPath + ".operands[" + operandIndex + "]");
      JsonNode operandUmlProperties = context.nodeUmlProperties().get(operandId);
      String operandUmlPath = context.nodePaths().get(operandId) + ".properties.uml";
      String owner =
          requiredTextProperty(
              operandUmlProperties,
              "combined_fragment",
              "InteractionOperand.combined_fragment",
              operandUmlPath);
      if (!nodeId.equals(owner)) {
        throw new UmlValidationException(
            UmlTypeKind.ELEMENT_PROPERTY, operandId, umlPath + ".operands[" + operandIndex + "]");
      }
      String operandInteraction =
          requiredTextProperty(
              operandUmlProperties,
              "interaction",
              "InteractionOperand.interaction",
              operandUmlPath);
      if (!interaction.equals(operandInteraction)) {
        throw new UmlValidationException(
            UmlTypeKind.ELEMENT_PROPERTY, operandId, umlPath + ".operands[" + operandIndex + "]");
      }
      BigInteger order =
          requiredPositiveIntegerProperty(
              operandUmlProperties, "order", "InteractionOperand.order", operandUmlPath + ".order");
      if (!operandOrders.add(order) || !order.equals(BigInteger.valueOf(operandIndex + 1L))) {
        throw new UmlValidationException(
            UmlTypeKind.ELEMENT_PROPERTY, operandId, umlPath + ".operands[" + operandIndex + "]");
      }
    }

    JsonNode covered = optionalArrayProperty(umlProperties, "covered", umlPath);
    if (covered != null) {
      for (int coveredIndex = 0; coveredIndex < covered.size(); coveredIndex++) {
        String lifelineId = requiredTextArrayEntry(covered, coveredIndex, umlPath + ".covered");
        requireNodeType(
            lifelineId,
            "Lifeline",
            context.nodeTypes(),
            umlPath + ".covered[" + coveredIndex + "]");
        String lifelineInteraction =
            readTextProperty(context.nodeUmlProperties().get(lifelineId), "interaction");
        if (!interaction.equals(lifelineInteraction)) {
          throw new UmlValidationException(
              UmlTypeKind.ELEMENT_PROPERTY, lifelineId, umlPath + ".covered[" + coveredIndex + "]");
        }
      }
    }
  }

  static void validateInteractionOperandProperties(
      JsonNode umlProperties, String path, ValidationContext context)
      throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    String interaction =
        requiredTextProperty(
            umlProperties, "interaction", "InteractionOperand.interaction", umlPath);
    requireNodeType(interaction, "Interaction", context.nodeTypes(), umlPath + ".interaction");

    String combinedFragment =
        requiredTextProperty(
            umlProperties, "combined_fragment", "InteractionOperand.combined_fragment", umlPath);
    requireNodeType(
        combinedFragment, "CombinedFragment", context.nodeTypes(), umlPath + ".combined_fragment");

    requiredPositiveIntegerProperty(
        umlProperties, "order", "InteractionOperand.order", umlPath + ".order");

    JsonNode fragments =
        requiredNonEmptyArrayProperty(
            umlProperties, "fragments", "InteractionOperand.fragments", umlPath);
    InteractionFragmentInterval previousFragmentInterval = null;
    var ownedMessageIds = new HashSet<String>();
    for (int fragmentIndex = 0; fragmentIndex < fragments.size(); fragmentIndex++) {
      String fragmentId = requiredTextArrayEntry(fragments, fragmentIndex, umlPath + ".fragments");
      String fragmentPath = umlPath + ".fragments[" + fragmentIndex + "]";
      if ("Message".equals(context.relationshipTypes().get(fragmentId))) {
        String messageInteraction =
            readTextProperty(context.relationshipUmlProperties().get(fragmentId), "interaction");
        if (!interaction.equals(messageInteraction)) {
          throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, fragmentId, fragmentPath);
        }
        validateFragmentCoverage(combinedFragment, fragmentId, fragmentPath, context);
      } else if ("CombinedFragment".equals(context.nodeTypes().get(fragmentId))) {
        String fragmentInteraction =
            readTextProperty(context.nodeUmlProperties().get(fragmentId), "interaction");
        if (!interaction.equals(fragmentInteraction)) {
          throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, fragmentId, fragmentPath);
        }
        validateFragmentCoverage(combinedFragment, fragmentId, fragmentPath, context);
      } else {
        throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, fragmentId, fragmentPath);
      }
      InteractionFragmentInterval fragmentInterval =
          interactionFragmentInterval(fragmentId, context, new HashSet<>());
      if (previousFragmentInterval != null
          && fragmentInterval != null
          && (fragmentInterval.firstSequence().compareTo(previousFragmentInterval.lastSequence())
                  <= 0
              || hasUnownedMessageBetween(
                  interaction,
                  previousFragmentInterval.lastSequence(),
                  fragmentInterval.firstSequence(),
                  ownedMessageIds,
                  context))) {
        throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, fragmentId, fragmentPath);
      }
      if (fragmentInterval != null) {
        ownedMessageIds.addAll(fragmentInterval.messageIds());
        previousFragmentInterval = fragmentInterval;
      }
    }

    JsonNode guard = optionalProperty(umlProperties, "guard");
    if (guard != null && !guard.isTextual()) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, guard.toString(), umlPath + ".guard");
    }
  }

  static void validateCombinedFragmentSequenceContiguity(
      List<SourceNode> nodes, ValidationContext context) throws UmlValidationException {
    for (SourceNode node : nodes) {
      if (!"CombinedFragment".equals(context.nodeTypes().get(node.id()))) {
        continue;
      }
      JsonNode umlProperties = context.nodeUmlProperties().get(node.id());
      String interaction = readTextProperty(umlProperties, "interaction");
      if (interaction == null) {
        continue;
      }
      validateCombinedFragmentSequenceContiguity(
          node.id(),
          interaction,
          context.nodePaths().get(node.id()) + ".properties.uml.operands",
          context);
    }
  }

  private static void validateCombinedFragmentSequenceContiguity(
      String nodeId, String interaction, String path, ValidationContext context)
      throws UmlValidationException {
    InteractionFragmentInterval interval =
        interactionFragmentInterval(nodeId, context, new HashSet<>());
    if (interval == null) {
      return;
    }
    if (hasUnownedMessageWithin(
        interaction,
        interval.firstSequence(),
        interval.lastSequence(),
        interval.messageIds(),
        context)) {
      throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, nodeId, path);
    }
  }

  static void validateMessageSequenceUniqueness(List<SourceRelationship> relationships)
      throws UmlValidationException {
    var seenSequencesByInteraction = new HashMap<String, Set<BigInteger>>();
    for (int relationshipIndex = 0; relationshipIndex < relationships.size(); relationshipIndex++) {
      SourceRelationship relationship = relationships.get(relationshipIndex);
      if (!"Message".equals(relationship.type())) {
        continue;
      }
      JsonNode umlProperties = relationship.properties().get("uml");
      String interaction = readTextProperty(umlProperties, "interaction");
      if (interaction == null) {
        continue;
      }
      JsonNode sequence = optionalProperty(umlProperties, "sequence");
      if (sequence == null || !sequence.isIntegralNumber()) {
        continue;
      }
      Set<BigInteger> seenSequences =
          seenSequencesByInteraction.computeIfAbsent(interaction, key -> new HashSet<>());
      if (!seenSequences.add(sequence.bigIntegerValue())) {
        throw new UmlValidationException(
            UmlTypeKind.RELATIONSHIP_PROPERTY,
            "Message.sequence",
            "$.relationships[" + relationshipIndex + "].properties.uml.sequence");
      }
    }
  }

  private static InteractionFragmentInterval interactionFragmentInterval(
      String fragmentId, ValidationContext context, Set<String> visitedCombinedFragments) {
    if ("Message".equals(context.relationshipTypes().get(fragmentId))) {
      BigInteger sequence = messageSequence(fragmentId, context);
      if (sequence == null) {
        return null;
      }
      return new InteractionFragmentInterval(sequence, sequence, Set.of(fragmentId));
    }
    if (!"CombinedFragment".equals(context.nodeTypes().get(fragmentId))
        || !visitedCombinedFragments.add(fragmentId)) {
      return null;
    }

    BigInteger firstSequence = null;
    BigInteger lastSequence = null;
    var messageIds = new HashSet<String>();
    JsonNode operands = optionalProperty(context.nodeUmlProperties().get(fragmentId), "operands");
    if (operands == null || !operands.isArray()) {
      return null;
    }
    for (JsonNode operand : operands) {
      if (!operand.isTextual()) {
        continue;
      }
      JsonNode fragments =
          optionalProperty(context.nodeUmlProperties().get(operand.asText()), "fragments");
      if (fragments == null || !fragments.isArray()) {
        continue;
      }
      for (JsonNode nestedFragment : fragments) {
        if (!nestedFragment.isTextual()) {
          continue;
        }
        InteractionFragmentInterval nestedInterval =
            interactionFragmentInterval(nestedFragment.asText(), context, visitedCombinedFragments);
        if (nestedInterval == null) {
          continue;
        }
        messageIds.addAll(nestedInterval.messageIds());
        if (firstSequence == null || nestedInterval.firstSequence().compareTo(firstSequence) < 0) {
          firstSequence = nestedInterval.firstSequence();
        }
        if (lastSequence == null || nestedInterval.lastSequence().compareTo(lastSequence) > 0) {
          lastSequence = nestedInterval.lastSequence();
        }
      }
    }
    if (firstSequence == null || lastSequence == null) {
      return null;
    }
    return new InteractionFragmentInterval(firstSequence, lastSequence, messageIds);
  }

  private static boolean hasUnownedMessageBetween(
      String interaction,
      BigInteger lowerExclusive,
      BigInteger upperExclusive,
      Set<String> ownedMessageIds,
      ValidationContext context) {
    for (String messageId : context.relationshipTypes().keySet()) {
      if (!"Message".equals(context.relationshipTypes().get(messageId))
          || ownedMessageIds.contains(messageId)
          || !interaction.equals(
              readTextProperty(
                  context.relationshipUmlProperties().get(messageId), "interaction"))) {
        continue;
      }
      BigInteger sequence = messageSequence(messageId, context);
      if (sequence != null
          && sequence.compareTo(lowerExclusive) > 0
          && sequence.compareTo(upperExclusive) < 0) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasUnownedMessageWithin(
      String interaction,
      BigInteger lowerInclusive,
      BigInteger upperInclusive,
      Set<String> ownedMessageIds,
      ValidationContext context) {
    for (String messageId : context.relationshipTypes().keySet()) {
      if (!"Message".equals(context.relationshipTypes().get(messageId))
          || ownedMessageIds.contains(messageId)
          || !interaction.equals(
              readTextProperty(
                  context.relationshipUmlProperties().get(messageId), "interaction"))) {
        continue;
      }
      BigInteger sequence = messageSequence(messageId, context);
      if (sequence != null
          && sequence.compareTo(lowerInclusive) >= 0
          && sequence.compareTo(upperInclusive) <= 0) {
        return true;
      }
    }
    return false;
  }

  private static BigInteger messageSequence(String messageId, ValidationContext context) {
    JsonNode sequence =
        optionalProperty(context.relationshipUmlProperties().get(messageId), "sequence");
    return sequence != null && sequence.isIntegralNumber() ? sequence.bigIntegerValue() : null;
  }

  static void validateMessageProperties(JsonNode umlProperties, String path)
      throws UmlValidationException {
    String umlPath = path + ".properties.uml";
    if (umlProperties == null || !umlProperties.isObject()) {
      throw new UmlValidationException(
          UmlTypeKind.RELATIONSHIP_PROPERTY, "Message.sequence", umlPath + ".sequence");
    }

    JsonNode sequence = umlProperties.get("sequence");
    if (sequence == null) {
      throw new UmlValidationException(
          UmlTypeKind.RELATIONSHIP_PROPERTY, "Message.sequence", umlPath + ".sequence");
    }
    if (!sequence.isIntegralNumber() || sequence.bigIntegerValue().signum() < 1) {
      throw new UmlValidationException(
          UmlTypeKind.RELATIONSHIP_PROPERTY, sequence.toString(), umlPath + ".sequence");
    }

    JsonNode messageSort = umlProperties.get("message_sort");
    if (messageSort != null
        && (!messageSort.isTextual() || !MESSAGE_SORTS.contains(messageSort.asText()))) {
      throw new UmlValidationException(
          UmlTypeKind.RELATIONSHIP_PROPERTY,
          messageSort.isTextual() ? messageSort.asText() : messageSort.toString(),
          umlPath + ".message_sort");
    }
  }

  private static void validateFragmentCoverage(
      String ownerCombinedFragment,
      String fragmentId,
      String fragmentPath,
      ValidationContext context)
      throws UmlValidationException {
    JsonNode ownerCovered =
        optionalProperty(context.nodeUmlProperties().get(ownerCombinedFragment), "covered");
    if (ownerCovered == null || !ownerCovered.isArray()) {
      return;
    }

    Set<String> ownerCoveredIds = textValueSet(ownerCovered);
    if (isMessageRelationship(fragmentId, context)) {
      String source = context.relationshipSources().get(fragmentId);
      String target = context.relationshipTargets().get(fragmentId);
      if (isUncoveredLifeline(source, ownerCoveredIds, context)
          || isUncoveredLifeline(target, ownerCoveredIds, context)) {
        throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, fragmentId, fragmentPath);
      }
      return;
    }

    JsonNode nestedCovered =
        optionalProperty(context.nodeUmlProperties().get(fragmentId), "covered");
    if (nestedCovered == null || !nestedCovered.isArray()) {
      return;
    }
    for (JsonNode lifeline : nestedCovered) {
      if (lifeline.isTextual() && !ownerCoveredIds.contains(lifeline.asText())) {
        throw new UmlValidationException(UmlTypeKind.ELEMENT_PROPERTY, fragmentId, fragmentPath);
      }
    }
  }

  private static boolean isUncoveredLifeline(
      String nodeId, Set<String> coveredLifelines, ValidationContext context) {
    return "Lifeline".equals(context.nodeTypes().get(nodeId)) && !coveredLifelines.contains(nodeId);
  }

  static void validateCombinedFragmentNesting(List<SourceNode> nodes, ValidationContext context)
      throws UmlValidationException {
    for (SourceNode node : nodes) {
      if (!"InteractionOperand".equals(context.nodeTypes().get(node.id()))) {
        continue;
      }
      JsonNode umlProperties = context.nodeUmlProperties().get(node.id());
      String ownerCombinedFragment = readTextProperty(umlProperties, "combined_fragment");
      JsonNode fragments = optionalProperty(umlProperties, "fragments");
      if (ownerCombinedFragment == null || fragments == null || !fragments.isArray()) {
        continue;
      }
      String fragmentsPath = context.nodePaths().get(node.id()) + ".properties.uml.fragments";
      for (int fragmentIndex = 0; fragmentIndex < fragments.size(); fragmentIndex++) {
        JsonNode fragment = fragments.get(fragmentIndex);
        if (!fragment.isTextual()) {
          continue;
        }
        String nestedCombinedFragment = fragment.asText();
        if (!"CombinedFragment".equals(context.nodeTypes().get(nestedCombinedFragment))) {
          continue;
        }
        if (ownerCombinedFragment.equals(nestedCombinedFragment)
            || hasCombinedFragmentPath(
                nestedCombinedFragment, ownerCombinedFragment, context, new HashSet<>())) {
          throw new UmlValidationException(
              UmlTypeKind.ELEMENT_PROPERTY,
              nestedCombinedFragment,
              fragmentsPath + "[" + fragmentIndex + "]");
        }
      }
    }
  }

  private static boolean hasCombinedFragmentPath(
      String currentCombinedFragment,
      String targetCombinedFragment,
      ValidationContext context,
      Set<String> visitedCombinedFragments) {
    if (!visitedCombinedFragments.add(currentCombinedFragment)) {
      return false;
    }
    JsonNode operands =
        optionalProperty(context.nodeUmlProperties().get(currentCombinedFragment), "operands");
    if (operands == null || !operands.isArray()) {
      return false;
    }

    for (JsonNode operand : operands) {
      if (!operand.isTextual()) {
        continue;
      }
      JsonNode operandFragments =
          optionalProperty(context.nodeUmlProperties().get(operand.asText()), "fragments");
      if (operandFragments == null || !operandFragments.isArray()) {
        continue;
      }
      for (JsonNode fragment : operandFragments) {
        if (!fragment.isTextual()) {
          continue;
        }
        String nestedCombinedFragment = fragment.asText();
        if (!"CombinedFragment".equals(context.nodeTypes().get(nestedCombinedFragment))) {
          continue;
        }
        if (targetCombinedFragment.equals(nestedCombinedFragment)
            || hasCombinedFragmentPath(
                nestedCombinedFragment,
                targetCombinedFragment,
                context,
                visitedCombinedFragments)) {
          return true;
        }
      }
    }
    return false;
  }

  static void validateInteractionOperandOwnerSelection(
      List<SourceNode> nodes, ValidationContext context) throws UmlValidationException {
    for (SourceNode node : nodes) {
      if (!"InteractionOperand".equals(context.nodeTypes().get(node.id()))) {
        continue;
      }
      String ownerCombinedFragment =
          readTextProperty(context.nodeUmlProperties().get(node.id()), "combined_fragment");
      if (!"CombinedFragment".equals(context.nodeTypes().get(ownerCombinedFragment))) {
        continue;
      }
      JsonNode ownerOperands =
          optionalProperty(context.nodeUmlProperties().get(ownerCombinedFragment), "operands");
      if (ownerOperands == null || !ownerOperands.isArray()) {
        continue;
      }
      if (!containsTextValue(ownerOperands, node.id())) {
        throw new UmlValidationException(
            UmlTypeKind.ELEMENT_PROPERTY,
            ownerCombinedFragment,
            context.nodePaths().get(node.id()) + ".properties.uml.combined_fragment");
      }
    }
  }

  static void validateInteractionFragmentOwnership(
      List<SourceNode> nodes, ValidationContext context) throws UmlValidationException {
    var ownedFragmentsByInteraction = new HashMap<String, Set<String>>();
    for (SourceNode node : nodes) {
      if (!"InteractionOperand".equals(context.nodeTypes().get(node.id()))) {
        continue;
      }
      JsonNode umlProperties = context.nodeUmlProperties().get(node.id());
      String interaction = readTextProperty(umlProperties, "interaction");
      JsonNode fragments = optionalProperty(umlProperties, "fragments");
      if (interaction == null || fragments == null || !fragments.isArray()) {
        continue;
      }
      Set<String> ownedFragments =
          ownedFragmentsByInteraction.computeIfAbsent(interaction, key -> new HashSet<>());
      String fragmentsPath = context.nodePaths().get(node.id()) + ".properties.uml.fragments";
      for (int fragmentIndex = 0; fragmentIndex < fragments.size(); fragmentIndex++) {
        JsonNode fragment = fragments.get(fragmentIndex);
        if (!fragment.isTextual()) {
          continue;
        }
        String fragmentId = fragment.asText();
        if (!isOwnedFragmentReference(fragmentId, context)) {
          continue;
        }
        if (!ownedFragments.add(fragmentId)) {
          throw new UmlValidationException(
              UmlTypeKind.ELEMENT_PROPERTY, fragmentId, fragmentsPath + "[" + fragmentIndex + "]");
        }
      }
    }
  }

  private static void validateOperandCount(String operator, JsonNode operands, String path)
      throws UmlValidationException {
    boolean supported =
        switch (operator) {
          case "opt", "loop" -> operands.size() == 1;
          case "alt", "par" -> operands.size() >= 2;
          default -> false;
        };
    if (!supported) {
      throw new UmlValidationException(
          UmlTypeKind.ELEMENT_PROPERTY, "CombinedFragment." + operator + ".operands", path);
    }
  }

  private static boolean isMessageRelationship(String id, ValidationContext context) {
    return "Message".equals(context.relationshipTypes().get(id));
  }

  private static boolean isOwnedFragmentReference(String id, ValidationContext context) {
    return isMessageRelationship(id, context)
        || "CombinedFragment".equals(context.nodeTypes().get(id));
  }

  private record InteractionFragmentInterval(
      BigInteger firstSequence, BigInteger lastSequence, Set<String> messageIds) {}
}

package dev.dediren.plugins.render.node.uml;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderMetadataSelector;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;

record UmlSequenceModel(
    List<UmlSequenceModel.SequenceNode> interactions,
    List<UmlSequenceModel.SequenceNode> lifelines,
    List<UmlSequenceModel.SequenceNode> executions,
    List<UmlSequenceModel.SequenceNode> gates,
    List<UmlSequenceModel.SequenceNode> destructions,
    List<UmlSequenceModel.SequenceCombinedFragment> combinedFragments,
    List<UmlSequenceModel.SequenceOperand> operands,
    List<UmlSequenceModel.SequenceMessage> messages) {
  static UmlSequenceModel from(LayoutResult result, RenderMetadata metadata) {
    List<SequenceNode> interactions = new ArrayList<>();
    List<SequenceNode> lifelines = new ArrayList<>();
    List<SequenceNode> executions = new ArrayList<>();
    List<SequenceNode> gates = new ArrayList<>();
    List<SequenceNode> destructions = new ArrayList<>();

    for (LaidOutNode node : result.nodes()) {
      RenderMetadataSelector selector = metadata.nodes().get(node.id());
      if (selector == null) {
        continue;
      }
      SequenceNode sequenceNode = new SequenceNode(node, selector);
      switch (selector.type()) {
        case "Interaction" -> interactions.add(sequenceNode);
        case "Lifeline" -> lifelines.add(sequenceNode);
        case "ExecutionSpecification" -> executions.add(sequenceNode);
        case "Gate" -> gates.add(sequenceNode);
        case "DestructionOccurrenceSpecification" -> destructions.add(sequenceNode);
        default -> {}
      }
    }

    List<SequenceCombinedFragment> combinedFragments = new ArrayList<>();
    List<SequenceOperand> operands = new ArrayList<>();
    for (var entry : metadata.nodes().entrySet()) {
      RenderMetadataSelector selector = entry.getValue();
      JsonNode properties = selector.properties();
      switch (selector.type()) {
        case "CombinedFragment" ->
            combinedFragments.add(
                new SequenceCombinedFragment(
                    entry.getKey(),
                    selector,
                    propertyText(properties, "interaction"),
                    propertyText(properties, "operator"),
                    textArray(properties, "operands"),
                    textArray(properties, "covered")));
        case "InteractionOperand" ->
            operands.add(
                new SequenceOperand(
                    entry.getKey(),
                    selector,
                    propertyText(properties, "interaction"),
                    propertyText(properties, "combined_fragment"),
                    positiveInt(properties, "order"),
                    propertyText(properties, "guard"),
                    textArray(properties, "fragments")));
        default -> {}
      }
    }

    List<SequenceMessage> messages = new ArrayList<>();
    for (int index = 0; index < result.edges().size(); index++) {
      LaidOutEdge edge = result.edges().get(index);
      RenderMetadataSelector selector = metadata.edges().get(edge.id());
      if (selector == null || !"Message".equals(selector.type())) {
        continue;
      }
      BigInteger fallbackSequence = BigInteger.valueOf(index + 1L);
      messages.add(
          new SequenceMessage(
              edge,
              selector,
              sequence(selector.properties(), fallbackSequence),
              messageSort(selector.properties()),
              index));
    }
    messages.sort(
        Comparator.comparing(SequenceMessage::sequence)
            .thenComparingInt(SequenceMessage::sourceOrder));

    return new UmlSequenceModel(
        interactions,
        lifelines,
        executions,
        gates,
        destructions,
        combinedFragments,
        operands,
        messages);
  }

  private static BigInteger sequence(JsonNode properties, BigInteger fallback) {
    JsonNode value = properties == null ? null : properties.get("sequence");
    return value != null && value.isIntegralNumber() ? value.bigIntegerValue() : fallback;
  }

  private static String messageSort(JsonNode properties) {
    JsonNode value = properties == null ? null : properties.get("message_sort");
    return value != null && value.isTextual() ? value.asText() : "synchCall";
  }

  private static String propertyText(JsonNode properties, String name) {
    JsonNode value = properties == null ? null : properties.get(name);
    return value != null && value.isTextual() ? value.asText() : null;
  }

  private static List<String> textArray(JsonNode properties, String name) {
    JsonNode value = properties == null ? null : properties.get(name);
    if (value == null || !value.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (JsonNode item : value) {
      if (item.isTextual()) {
        values.add(item.asText());
      }
    }
    return values;
  }

  private static int positiveInt(JsonNode properties, String name) {
    JsonNode value = properties == null ? null : properties.get(name);
    if (value != null && value.isIntegralNumber() && value.bigIntegerValue().signum() >= 1) {
      return value.canConvertToInt() ? value.intValue() : Integer.MAX_VALUE;
    }
    return Integer.MAX_VALUE;
  }

  record SequenceNode(LaidOutNode node, RenderMetadataSelector selector) {}

  record SequenceCombinedFragment(
      String id,
      RenderMetadataSelector selector,
      String interactionId,
      String operator,
      List<String> operandIds,
      List<String> coveredLifelineIds) {}

  record SequenceOperand(
      String id,
      RenderMetadataSelector selector,
      String interactionId,
      String combinedFragmentId,
      int order,
      String guard,
      List<String> fragmentIds) {}

  record SequenceMessage(
      LaidOutEdge edge,
      RenderMetadataSelector selector,
      BigInteger sequence,
      String messageSort,
      int sourceOrder) {}
}

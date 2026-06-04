package dev.dediren.plugins.svgrender;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderMetadataSelector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

record UmlSequenceModel(
        List<UmlSequenceModel.SequenceNode> interactions,
        List<UmlSequenceModel.SequenceNode> lifelines,
        List<UmlSequenceModel.SequenceNode> executions,
        List<UmlSequenceModel.SequenceNode> gates,
        List<UmlSequenceModel.SequenceNode> destructions,
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
                default -> {
                }
            }
        }

        List<SequenceMessage> messages = new ArrayList<>();
        for (int index = 0; index < result.edges().size(); index++) {
            LaidOutEdge edge = result.edges().get(index);
            RenderMetadataSelector selector = metadata.edges().get(edge.id());
            if (selector == null || !"Message".equals(selector.type())) {
                continue;
            }
            long fallbackSequence = index + 1L;
            messages.add(new SequenceMessage(
                    edge,
                    selector,
                    sequence(selector.properties(), fallbackSequence),
                    messageSort(selector.properties()),
                    index));
        }
        messages.sort(Comparator.comparingLong(SequenceMessage::sequence)
                .thenComparingInt(SequenceMessage::sourceOrder));

        return new UmlSequenceModel(interactions, lifelines, executions, gates, destructions, messages);
    }

    private static long sequence(JsonNode properties, long fallback) {
        JsonNode value = properties == null ? null : properties.get("sequence");
        return value != null && value.canConvertToLong() ? value.asLong() : fallback;
    }

    private static String messageSort(JsonNode properties) {
        JsonNode value = properties == null ? null : properties.get("message_sort");
        return value != null && value.isTextual() ? value.asText() : "synchCall";
    }

    record SequenceNode(LaidOutNode node, RenderMetadataSelector selector) {
    }

    record SequenceMessage(
            LaidOutEdge edge,
            RenderMetadataSelector selector,
            long sequence,
            String messageSort,
            int sourceOrder) {
    }
}

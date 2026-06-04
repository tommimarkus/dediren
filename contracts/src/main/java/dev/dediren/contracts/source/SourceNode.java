package dev.dediren.contracts.source;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record SourceNode(
        String id,
        String type,
        String label,
        Map<String, JsonNode> properties) {
    public SourceNode {
        properties = mapOrEmpty(properties);
    }
}

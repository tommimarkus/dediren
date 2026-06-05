package dev.dediren.contracts.layout;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record LaidOutEdge(
        String id,
        String source,
        String target,
        String sourceId,
        String projectionId,
        List<String> routingHints,
        List<Point> points,
        String label) {
    public LaidOutEdge {
        routingHints = listOrEmpty(routingHints);
        points = listOrEmpty(points);
    }
}

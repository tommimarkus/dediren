package dev.dediren.contracts.layout;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record LaidOutGroup(
        String id,
        String sourceId,
        String projectionId,
        GroupProvenance provenance,
        double x,
        double y,
        double width,
        double height,
        List<String> members,
        String label) {
    public LaidOutGroup {
        members = listOrEmpty(members);
    }
}

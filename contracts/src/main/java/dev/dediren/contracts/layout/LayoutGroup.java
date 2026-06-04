package dev.dediren.contracts.layout;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record LayoutGroup(
        String id,
        String label,
        List<String> members,
        GroupProvenance provenance) {
    public LayoutGroup {
        members = listOrEmpty(members);
    }
}

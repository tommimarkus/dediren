package dev.dediren.contracts.layout;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record LayoutConstraint(String id, String kind, List<String> subjects) {
  public LayoutConstraint {
    subjects = listOrEmpty(subjects);
  }
}

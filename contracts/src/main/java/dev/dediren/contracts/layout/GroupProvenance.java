package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonInclude;

public record GroupProvenance(
    @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean visualOnly,
    SemanticBackedGroupProvenance semanticBacked) {
  public static GroupProvenance visualOnlyGroup() {
    return new GroupProvenance(true, null);
  }

  public static GroupProvenance semanticBacked(String sourceId) {
    return new GroupProvenance(false, new SemanticBackedGroupProvenance(sourceId));
  }

  public String semanticSourceId() {
    return semanticBacked == null ? null : semanticBacked.sourceId();
  }
}

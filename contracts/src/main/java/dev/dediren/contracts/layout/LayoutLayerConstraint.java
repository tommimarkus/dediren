package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutLayerConstraint {
  @JsonProperty("none")
  NONE,

  @JsonProperty("first")
  FIRST,

  @JsonProperty("first-separate")
  FIRST_SEPARATE,

  @JsonProperty("last")
  LAST,

  @JsonProperty("last-separate")
  LAST_SEPARATE
}

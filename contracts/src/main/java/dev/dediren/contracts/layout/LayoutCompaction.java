package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutCompaction {
  @JsonProperty("off")
  OFF,

  @JsonProperty("left")
  LEFT,

  @JsonProperty("right")
  RIGHT,

  @JsonProperty("balanced")
  BALANCED
}

package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutDirection {
  @JsonProperty("right")
  RIGHT,

  @JsonProperty("left")
  LEFT,

  @JsonProperty("down")
  DOWN,

  @JsonProperty("up")
  UP
}

package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutThoroughness {
  @JsonProperty("low")
  LOW,

  @JsonProperty("normal")
  NORMAL,

  @JsonProperty("high")
  HIGH
}

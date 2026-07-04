package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutCrossingStrategy {
  @JsonProperty("layer-sweep")
  LAYER_SWEEP,

  @JsonProperty("none")
  NONE
}

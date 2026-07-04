package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutCycleBreaking {
  @JsonProperty("greedy")
  GREEDY,

  @JsonProperty("depth-first")
  DEPTH_FIRST,

  @JsonProperty("model-order")
  MODEL_ORDER
}

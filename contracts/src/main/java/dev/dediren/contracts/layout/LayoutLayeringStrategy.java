package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutLayeringStrategy {
  @JsonProperty("network-simplex")
  NETWORK_SIMPLEX,

  @JsonProperty("longest-path")
  LONGEST_PATH,

  @JsonProperty("coffman-graham")
  COFFMAN_GRAHAM,

  @JsonProperty("min-width")
  MIN_WIDTH,

  @JsonProperty("stretch-width")
  STRETCH_WIDTH,

  @JsonProperty("breadth-first")
  BREADTH_FIRST,

  @JsonProperty("depth-first")
  DEPTH_FIRST
}

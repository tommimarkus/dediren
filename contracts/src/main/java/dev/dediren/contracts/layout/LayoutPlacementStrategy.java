package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutPlacementStrategy {
  @JsonProperty("brandes-koepf")
  BRANDES_KOEPF,

  @JsonProperty("network-simplex")
  NETWORK_SIMPLEX,

  @JsonProperty("linear-segments")
  LINEAR_SEGMENTS,

  @JsonProperty("simple")
  SIMPLE
}

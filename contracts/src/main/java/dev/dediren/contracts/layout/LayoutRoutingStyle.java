package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutRoutingStyle {
  @JsonProperty("orthogonal")
  ORTHOGONAL,

  @JsonProperty("polyline")
  POLYLINE,

  @JsonProperty("spline")
  SPLINE
}

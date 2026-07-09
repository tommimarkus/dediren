package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Gradient kind for a node or group fill. */
public enum SvgGradientType {
  @JsonProperty("linear")
  LINEAR,

  @JsonProperty("radial")
  RADIAL
}

package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum SvgEdgeLineStyle {
  @JsonProperty("solid")
  SOLID,

  @JsonProperty("dashed")
  DASHED,

  @JsonProperty("dotted")
  DOTTED
}

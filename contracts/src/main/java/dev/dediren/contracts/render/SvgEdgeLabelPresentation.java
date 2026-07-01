package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum SvgEdgeLabelPresentation {
  @JsonProperty("outline")
  OUTLINE,

  @JsonProperty("background")
  BACKGROUND
}

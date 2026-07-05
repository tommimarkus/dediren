package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutComponentsSpacing {
  @JsonProperty("compact")
  COMPACT,

  @JsonProperty("readable")
  READABLE,

  @JsonProperty("spacious")
  SPACIOUS
}

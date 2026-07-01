package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutWrapping {
  @JsonProperty("auto")
  AUTO,

  @JsonProperty("off")
  OFF,

  @JsonProperty("multi-edge")
  MULTI_EDGE
}

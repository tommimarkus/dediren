package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Horizontal text anchor for a node or group label. */
public enum SvgLabelAlign {
  @JsonProperty("start")
  START,

  @JsonProperty("middle")
  MIDDLE,

  @JsonProperty("end")
  END
}

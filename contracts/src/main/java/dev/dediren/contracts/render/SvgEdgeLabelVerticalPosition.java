package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum SvgEdgeLabelVerticalPosition {
  @JsonProperty("near_start")
  NEAR_START,
  @JsonProperty("center")
  CENTER,
  @JsonProperty("near_end")
  NEAR_END
}

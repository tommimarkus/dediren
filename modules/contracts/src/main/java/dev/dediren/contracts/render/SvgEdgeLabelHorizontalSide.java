package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum SvgEdgeLabelHorizontalSide {
    @JsonProperty("auto")
    AUTO,
    @JsonProperty("above")
    ABOVE,
    @JsonProperty("below")
    BELOW
}

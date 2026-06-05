package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum SvgEdgeMarkerEnd {
    @JsonProperty("filled_arrow")
    FILLED_ARROW,
    @JsonProperty("open_arrow")
    OPEN_ARROW,
    @JsonProperty("hollow_triangle")
    HOLLOW_TRIANGLE,
    @JsonProperty("filled_diamond")
    FILLED_DIAMOND,
    @JsonProperty("hollow_diamond")
    HOLLOW_DIAMOND,
    @JsonProperty("filled_circle")
    FILLED_CIRCLE,
    @JsonProperty("hollow_circle")
    HOLLOW_CIRCLE,
    @JsonProperty("none")
    NONE
}

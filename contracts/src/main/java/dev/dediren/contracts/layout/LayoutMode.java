package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutMode {
    @JsonProperty("auto")
    AUTO,

    @JsonProperty("flow")
    FLOW,

    @JsonProperty("packed")
    PACKED
}

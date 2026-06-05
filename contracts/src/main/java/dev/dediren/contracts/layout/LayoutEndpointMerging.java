package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutEndpointMerging {
    @JsonProperty("off")
    OFF,

    @JsonProperty("local")
    LOCAL,

    @JsonProperty("auto")
    AUTO
}

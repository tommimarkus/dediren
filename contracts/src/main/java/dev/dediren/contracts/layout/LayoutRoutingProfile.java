package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutRoutingProfile {
    @JsonProperty("compact")
    COMPACT,

    @JsonProperty("readable")
    READABLE,

    @JsonProperty("spacious")
    SPACIOUS
}

package dev.dediren.contracts.source;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum GenericGraphViewGroupRole {
    @JsonProperty("semantic-boundary")
    SEMANTIC_BOUNDARY,

    @JsonProperty("layout-only")
    LAYOUT_ONLY
}

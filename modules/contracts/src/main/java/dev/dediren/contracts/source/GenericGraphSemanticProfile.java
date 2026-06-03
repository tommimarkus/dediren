package dev.dediren.contracts.source;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum GenericGraphSemanticProfile {
    @JsonProperty("generic-graph")
    GENERIC_GRAPH,

    @JsonProperty("archimate")
    ARCHIMATE,

    @JsonProperty("uml")
    UML
}

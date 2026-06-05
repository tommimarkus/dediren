package dev.dediren.contracts.source;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum GenericGraphViewKind {
    @JsonProperty("generic")
    GENERIC,

    @JsonProperty("archimate")
    ARCHIMATE,

    @JsonProperty("uml-class")
    UML_CLASS,

    @JsonProperty("uml-data")
    UML_DATA,

    @JsonProperty("uml-activity")
    UML_ACTIVITY,

    @JsonProperty("uml-sequence")
    UML_SEQUENCE,

    @JsonProperty("uml-state-machine")
    UML_STATE_MACHINE,

    @JsonProperty("uml-component")
    UML_COMPONENT,

    @JsonProperty("uml-use-case")
    UML_USE_CASE
}

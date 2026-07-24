package dev.dediren.contracts.pkg;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The export lane an entry targets. The wire string is the contract. */
public enum PackageExportLane {
  @JsonProperty("archimate-oef")
  ARCHIMATE_OEF,

  @JsonProperty("uml-xmi")
  UML_XMI
}

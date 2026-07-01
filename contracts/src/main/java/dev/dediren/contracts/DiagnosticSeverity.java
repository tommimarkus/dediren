package dev.dediren.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum DiagnosticSeverity {
  @JsonProperty("info")
  INFO,

  @JsonProperty("warning")
  WARNING,

  @JsonProperty("error")
  ERROR
}

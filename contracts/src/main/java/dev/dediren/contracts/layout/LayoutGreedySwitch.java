package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum LayoutGreedySwitch {
  @JsonProperty("off")
  OFF,

  @JsonProperty("one-sided")
  ONE_SIDED,

  @JsonProperty("two-sided")
  TWO_SIDED
}

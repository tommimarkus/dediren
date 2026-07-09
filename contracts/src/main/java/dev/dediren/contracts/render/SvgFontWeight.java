package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Font weight for the global font or a per-element label. */
public enum SvgFontWeight {
  @JsonProperty("normal")
  NORMAL,

  @JsonProperty("bold")
  BOLD
}

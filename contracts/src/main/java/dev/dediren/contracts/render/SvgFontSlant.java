package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Font posture (the CSS {@code font-style}) for the global font or a per-element label. */
public enum SvgFontSlant {
  @JsonProperty("normal")
  NORMAL,

  @JsonProperty("italic")
  ITALIC
}

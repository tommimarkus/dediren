package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Character set the ASCII/text render engine draws with. */
public enum TextRenderCharset {
  @JsonProperty("unicode")
  UNICODE,

  @JsonProperty("ascii")
  ASCII
}

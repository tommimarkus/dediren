package dev.dediren.contracts.render;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Free-form node geometry for notations that do not mandate a shape (generic graphs). Honoured only
 * when a node has no ArchiMate/UML {@link SvgNodeDecorator}; the render policy is the sole
 * authoring surface. {@code ROUNDED_RECTANGLE} matches the historical default.
 */
public enum SvgNodeShape {
  @JsonProperty("rectangle")
  RECTANGLE,

  @JsonProperty("rounded_rectangle")
  ROUNDED_RECTANGLE,

  @JsonProperty("ellipse")
  ELLIPSE,

  @JsonProperty("circle")
  CIRCLE,

  @JsonProperty("diamond")
  DIAMOND,

  @JsonProperty("hexagon")
  HEXAGON,

  @JsonProperty("parallelogram")
  PARALLELOGRAM,

  @JsonProperty("stadium")
  STADIUM,

  @JsonProperty("cylinder")
  CYLINDER,

  @JsonProperty("triangle")
  TRIANGLE
}

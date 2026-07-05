package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Layout algorithm selector. Only {@link #LAYERED} is currently accepted by the public schemas and
 * the plugin boundary; the remaining constants are forward-ready vocabulary so the algorithm
 * compatibility gate can be validated ahead of exposing alternate algorithms.
 */
public enum LayoutAlgorithm {
  @JsonProperty("layered")
  LAYERED,

  @JsonProperty("tree")
  TREE,

  @JsonProperty("radial")
  RADIAL,

  @JsonProperty("force")
  FORCE,

  @JsonProperty("stress")
  STRESS,

  @JsonProperty("packed")
  PACKED
}

package dev.dediren.contracts.layout;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Typed routed-edge geometry at the public layout-result boundary. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PolylineRoute.class, name = "polyline"),
  @JsonSubTypes.Type(value = CubicBezierRoute.class, name = "cubic_bezier")
})
public sealed interface EdgeRoute permits PolylineRoute, CubicBezierRoute {}

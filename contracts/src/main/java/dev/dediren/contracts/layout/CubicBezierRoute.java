package dev.dediren.contracts.layout;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

/** A piecewise cubic Bezier route with one explicit start and ordered segments. */
public record CubicBezierRoute(Point start, List<CubicBezierSegment> segments)
    implements EdgeRoute {
  public CubicBezierRoute {
    segments = listOrEmpty(segments);
  }
}

package dev.dediren.ir.quality;

/**
 * Geometry tolerances shared by the layout-quality checks in {@code core} and the sequence
 * invariants in {@code ir}.
 *
 * <p>These two check the same convention from different sides — a sequence message endpoint anchors
 * to its lifeline's axis (the participant head-box centre-x), and a route endpoint anchors to its
 * node's perimeter — so they must accept the same slack. They used to declare the slack twice, as
 * {@code LayoutQuality#ROUTE_ENDPOINT_TOLERANCE} and {@code
 * SequenceInvariants#LIFELINE_AXIS_TOLERANCE}, kept equal by a comment. If one had ever changed,
 * the perimeter check and the sequence invariant could have disagreed about the same endpoint
 * inside a single envelope: one accepting a route the other reported as a violation.
 *
 * <p>{@code core} already compile-depends on {@code ir}, so one declaration serves both.
 */
public final class LayoutTolerances {

  private LayoutTolerances() {}

  /** Layout units of slack allowed between a route endpoint and the anchor it must sit on. */
  public static final double ROUTE_ENDPOINT_TOLERANCE = 1.5;
}

package dev.dediren.plugins.drawio.mx;

import java.util.List;

/**
 * One {@code <mxGeometry as="geometry">}: the cell's own box, plus an edge's waypoints and floating
 * endpoints.
 *
 * <p>Import does not use this geometry — Dediren re-lays every page out with ELK rather than
 * trusting draw.io's placement — but the reader's job is a faithful model of the file, and the
 * export lane and any future geometry-preserving mode both need it. Dropping it here would be a
 * decision taken in the wrong place.
 *
 * <p>Coordinates are primitive for the reason given on {@link MxPoint}. {@code relative} is
 * mxGraph's {@code relative="1"}, which changes how {@code x}/{@code y} are interpreted; the reader
 * records the flag and does not interpret it.
 *
 * <p>{@code sourcePoint} and {@code targetPoint} are null when absent. {@code points} is never null
 * — an edge with no waypoints has an empty list, which is the same thing in the format.
 */
public record MxGeometry(
    double x,
    double y,
    double width,
    double height,
    boolean relative,
    MxPoint sourcePoint,
    MxPoint targetPoint,
    List<MxPoint> points) {

  public MxGeometry {
    // List.copyOf inline rather than ContractCollections.listOrEmpty (which is exactly this):
    // SpotBugs models the JDK factory and not the helper, so inlining removes an EI_EXPOSE_REP
    // suppression instead of adding one.
    points = points == null ? List.of() : List.copyOf(points);
  }
}

package dev.dediren.plugins.drawio.mx;

/**
 * One {@code <mxPoint>}: an edge waypoint, or a floating source/target endpoint on an edge whose
 * {@code source}/{@code target} names no cell.
 *
 * <p>Primitive coordinates, not boxed: mxGraph's own {@code mxPoint} defaults an omitted {@code x}
 * or {@code y} to {@code 0}, so "absent" and "zero" are the same value in the format rather than a
 * distinction this model would be flattening away.
 */
public record MxPoint(double x, double y) {}

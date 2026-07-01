package dev.dediren.plugins.render.svg;

import dev.dediren.contracts.render.RenderPolicy;

public final class SvgBounds {
  private double minX;
  private double minY;
  private double maxX;
  private double maxY;

  private SvgBounds(double minX, double minY, double maxX, double maxY) {
    this.minX = minX;
    this.minY = minY;
    this.maxX = maxX;
    this.maxY = maxY;
  }

  public static SvgBounds empty() {
    return new SvgBounds(
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.NEGATIVE_INFINITY);
  }

  public boolean isEmpty() {
    return !Double.isFinite(minX)
        || !Double.isFinite(minY)
        || !Double.isFinite(maxX)
        || !Double.isFinite(maxY);
  }

  public void includeRect(double x, double y, double width, double height) {
    includePoint(x, y);
    includePoint(x + width, y + height);
  }

  public void includePoint(double x, double y) {
    minX = Math.min(minX, x);
    minY = Math.min(minY, y);
    maxX = Math.max(maxX, x);
    maxY = Math.max(maxY, y);
  }

  public SvgBounds padded(RenderPolicy policy) {
    return new SvgBounds(
        minX - policy.margin().left(),
        minY - policy.margin().top(),
        maxX + policy.margin().right(),
        maxY + policy.margin().bottom());
  }

  public double width() {
    return maxX - minX;
  }

  public double height() {
    return maxY - minY;
  }

  public double minX() {
    return minX;
  }

  public double minY() {
    return minY;
  }

  public double maxX() {
    return maxX;
  }

  public double maxY() {
    return maxY;
  }
}

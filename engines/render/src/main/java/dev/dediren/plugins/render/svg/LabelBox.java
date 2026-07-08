package dev.dediren.plugins.render.svg;

public record LabelBox(double minX, double minY, double maxX, double maxY) {
  public boolean overlaps(LabelBox other) {
    return minX < other.maxX && maxX > other.minX && minY < other.maxY && maxY > other.minY;
  }

  public LabelBox expanded(double horizontalPadding, double verticalPadding) {
    return new LabelBox(
        minX - horizontalPadding,
        minY - verticalPadding,
        maxX + horizontalPadding,
        maxY + verticalPadding);
  }

  public double width() {
    return maxX - minX;
  }

  public double height() {
    return maxY - minY;
  }
}

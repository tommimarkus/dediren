package dev.dediren.plugins.asciirender;

/**
 * The two character sets the ASCII render engine can emit box-drawing lines, arrowheads, and
 * truncation markers in.
 */
enum GlyphSet {
  UNICODE,
  ASCII;

  private static final int NORTH = 1;
  private static final int EAST = 2;
  private static final int SOUTH = 4;
  private static final int WEST = 8;

  /**
   * Returns the character for a line-bitmask cell (bits N=1, E=2, S=4, W=8). UNICODE draws the
   * matching box-drawing shape; ASCII reduces every mask to {@code -}, {@code |}, or {@code +}
   * depending on whether it touches only the horizontal axis, only the vertical axis, or both.
   */
  char glyph(int bitmask) {
    boolean n = (bitmask & NORTH) != 0;
    boolean e = (bitmask & EAST) != 0;
    boolean s = (bitmask & SOUTH) != 0;
    boolean w = (bitmask & WEST) != 0;
    if (this == ASCII) {
      boolean horizontal = e || w;
      boolean vertical = n || s;
      if (horizontal && vertical) {
        return '+';
      }
      return horizontal ? '-' : '|';
    }
    return switch (bitmask) {
      case EAST, WEST, EAST | WEST -> '─';
      case NORTH, SOUTH, NORTH | SOUTH -> '│';
      case EAST | SOUTH -> '┌';
      case WEST | SOUTH -> '┐';
      case NORTH | EAST -> '└';
      case NORTH | WEST -> '┘';
      case NORTH | SOUTH | EAST -> '├';
      case NORTH | SOUTH | WEST -> '┤';
      case EAST | WEST | SOUTH -> '┬';
      case EAST | WEST | NORTH -> '┴';
      case NORTH | SOUTH | EAST | WEST -> '┼';
      default -> ' ';
    };
  }

  /** Returns the arrowhead pointing the direction the edge enters its target. */
  char arrow(int directionBit) {
    if (this == ASCII) {
      return switch (directionBit) {
        case NORTH -> '^';
        case EAST -> '>';
        case SOUTH -> 'v';
        case WEST -> '<';
        default -> throw new IllegalArgumentException("Unknown direction bit: " + directionBit);
      };
    }
    return switch (directionBit) {
      case NORTH -> '▲';
      case EAST -> '▶';
      case SOUTH -> '▼';
      case WEST -> '◀';
      default -> throw new IllegalArgumentException("Unknown direction bit: " + directionBit);
    };
  }

  /** Returns the marker used when a label or line is truncated to fit the grid. */
  char truncationMarker() {
    return this == ASCII ? '~' : '…';
  }
}

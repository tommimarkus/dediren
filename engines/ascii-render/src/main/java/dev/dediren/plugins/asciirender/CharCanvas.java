package dev.dediren.plugins.asciirender;

/**
 * A fixed-size character grid that lines, literal characters, and text runs are drawn onto, then
 * emitted as a right-trimmed string via a {@link GlyphSet}.
 */
final class CharCanvas {

  private static final int NORTH = 1;
  private static final int EAST = 2;
  private static final int SOUTH = 4;
  private static final int WEST = 8;

  /** A cell is empty (no entry), a line bitmask (positive int), or a literal (negative marker). */
  private final Integer[][] bitmask;

  private final Character[][] literal;
  private final int width;
  private final int height;

  CharCanvas(int width, int height) {
    this.width = width;
    this.height = height;
    this.bitmask = new Integer[height][width];
    this.literal = new Character[height][width];
  }

  void hline(int row, int colFrom, int colTo) {
    int lo = Math.min(colFrom, colTo);
    int hi = Math.max(colFrom, colTo);
    for (int col = lo; col <= hi; col++) {
      int bits;
      if (col == lo && col == hi) {
        bits = EAST | WEST;
      } else if (col == lo) {
        bits = EAST;
      } else if (col == hi) {
        bits = WEST;
      } else {
        bits = EAST | WEST;
      }
      orLineBits(row, col, bits);
    }
  }

  void vline(int col, int rowFrom, int rowTo) {
    int lo = Math.min(rowFrom, rowTo);
    int hi = Math.max(rowFrom, rowTo);
    for (int row = lo; row <= hi; row++) {
      int bits;
      if (row == lo && row == hi) {
        bits = NORTH | SOUTH;
      } else if (row == lo) {
        bits = SOUTH;
      } else if (row == hi) {
        bits = NORTH;
      } else {
        bits = NORTH | SOUTH;
      }
      orLineBits(row, col, bits);
    }
  }

  private void orLineBits(int row, int col, int bits) {
    if (!inBounds(row, col) || literal[row][col] != null) {
      // Literals win: a line bit into a literal cell leaves the literal untouched.
      return;
    }
    bitmask[row][col] = (bitmask[row][col] == null ? 0 : bitmask[row][col]) | bits;
  }

  void text(int row, int col, String s) {
    for (int i = 0; i < s.length(); i++) {
      literal(row, col + i, s.charAt(i));
    }
  }

  void literal(int row, int col, char ch) {
    if (!inBounds(row, col)) {
      return;
    }
    // The emitted text is destined for terminals and MCP clients that print it verbatim, and
    // labels are untrusted model text (DOT import and hand-authored JSON admit raw control
    // bytes), so this single sink neutralizes escape/control sequences the way the SVG lane's
    // XML escaping does for its output.
    literal[row][col] = Character.isISOControl(ch) ? ' ' : ch;
    bitmask[row][col] = null;
  }

  void clearRect(int rowFrom, int colFrom, int rowTo, int colTo) {
    int rowLo = Math.min(rowFrom, rowTo);
    int rowHi = Math.max(rowFrom, rowTo);
    int colLo = Math.min(colFrom, colTo);
    int colHi = Math.max(colFrom, colTo);
    for (int row = rowLo; row <= rowHi; row++) {
      for (int col = colLo; col <= colHi; col++) {
        if (inBounds(row, col)) {
          bitmask[row][col] = null;
          literal[row][col] = null;
        }
      }
    }
  }

  String emit(GlyphSet glyphs) {
    StringBuilder out = new StringBuilder();
    for (int row = 0; row < height; row++) {
      StringBuilder line = new StringBuilder();
      for (int col = 0; col < width; col++) {
        if (literal[row][col] != null) {
          line.append((char) literal[row][col]);
        } else if (bitmask[row][col] != null) {
          line.append(glyphs.glyph(bitmask[row][col]));
        } else {
          line.append(' ');
        }
      }
      int end = line.length();
      while (end > 0 && line.charAt(end - 1) == ' ') {
        end--;
      }
      out.append(line, 0, end).append('\n');
    }
    return out.toString();
  }

  /**
   * Whether {@code (row, col)} is on the canvas. Used by edge-label placement to detect clipping.
   */
  boolean isInBounds(int row, int col) {
    return inBounds(row, col);
  }

  /** Whether {@code (row, col)} already holds a literal. Used by edge-label placement collision. */
  boolean isLiteralAt(int row, int col) {
    return inBounds(row, col) && literal[row][col] != null;
  }

  private boolean inBounds(int row, int col) {
    return row >= 0 && row < height && col >= 0 && col < width;
  }
}

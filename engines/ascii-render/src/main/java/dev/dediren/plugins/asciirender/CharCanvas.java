package dev.dediren.plugins.asciirender;

import dev.dediren.ir.RoutedEdge;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
  private final Set<String>[][] edgeOwners;
  private final Set<String> obscuredEdges = new LinkedHashSet<>();
  private final List<ProjectedEdge> projectedEdges = new ArrayList<>();
  private final int width;
  private final int height;

  CharCanvas(int width, int height) {
    this.width = width;
    this.height = height;
    this.bitmask = new Integer[height][width];
    this.literal = new Character[height][width];
    @SuppressWarnings("unchecked")
    Set<String>[][] owners = (Set<String>[][]) new Set<?>[height][width];
    this.edgeOwners = owners;
  }

  void hline(int row, int colFrom, int colTo) {
    hline(row, colFrom, colTo, null);
  }

  boolean hline(int row, int colFrom, int colTo, String owner) {
    return hline(row, colFrom, colTo, owner, Map.of());
  }

  boolean hline(
      int row, int colFrom, int colTo, String owner, Map<String, Set<String>> permittedOwners) {
    boolean collision = false;
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
      collision |=
          orLineBits(
              row, col, bits, owner, permittedOwners.getOrDefault(cellKey(row, col), Set.of()));
    }
    return collision;
  }

  void vline(int col, int rowFrom, int rowTo) {
    vline(col, rowFrom, rowTo, null);
  }

  boolean vline(int col, int rowFrom, int rowTo, String owner) {
    return vline(col, rowFrom, rowTo, owner, Map.of());
  }

  boolean vline(
      int col, int rowFrom, int rowTo, String owner, Map<String, Set<String>> permittedOwners) {
    boolean collision = false;
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
      collision |=
          orLineBits(
              row, col, bits, owner, permittedOwners.getOrDefault(cellKey(row, col), Set.of()));
    }
    return collision;
  }

  private boolean orLineBits(
      int row, int col, int bits, String owner, Set<String> permittedOwners) {
    if (!inBounds(row, col) || literal[row][col] != null) {
      // Literals win: a line bit into a literal cell leaves the literal untouched.
      return owner != null;
    }
    boolean collision =
        owner != null
            && edgeOwners[row][col] != null
            && edgeOwners[row][col].stream()
                .anyMatch(
                    existing -> !existing.equals(owner) && !permittedOwners.contains(existing));
    if (owner != null) {
      if (edgeOwners[row][col] == null) {
        edgeOwners[row][col] = new LinkedHashSet<>();
      }
      edgeOwners[row][col].add(owner);
    }
    bitmask[row][col] = (bitmask[row][col] == null ? 0 : bitmask[row][col]) | bits;
    return collision;
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
          recordObscuredEdge(row, col);
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

  Set<String> consumeObscuredEdges() {
    Set<String> result = Set.copyOf(obscuredEdges);
    obscuredEdges.clear();
    return result;
  }

  /**
   * Returns the earlier owners this edge may overlap at each cell. A junction hint is only enough
   * when both routes name the same actual terminal and their expanded projected paths share a
   * prefix or suffix that subsequently diverges.
   */
  Map<String, Set<String>> permittedSharedOwners(RoutedEdge candidate, List<int[]> cells) {
    Map<String, Set<String>> permitted = new LinkedHashMap<>();
    for (ProjectedEdge earlier : projectedEdges) {
      if (hasSharedSourceIntent(candidate, earlier.edge())) {
        addSharedPrefix(permitted, earlier.cells(), cells, earlier.edge().id());
      }
      if (hasSharedTargetIntent(candidate, earlier.edge())) {
        addSharedSuffix(permitted, earlier.cells(), cells, earlier.edge().id());
      }
    }
    return permitted;
  }

  void recordProjectedEdge(RoutedEdge edge, List<int[]> cells) {
    projectedEdges.add(
        new ProjectedEdge(edge, cells.stream().map(cell -> new Cell(cell[0], cell[1])).toList()));
  }

  private static boolean hasSharedSourceIntent(RoutedEdge left, RoutedEdge right) {
    return left.source().equals(right.source())
        && left.routingHints().contains("shared_source_junction")
        && right.routingHints().contains("shared_source_junction");
  }

  private static boolean hasSharedTargetIntent(RoutedEdge left, RoutedEdge right) {
    return left.target().equals(right.target())
        && left.routingHints().contains("shared_target_junction")
        && right.routingHints().contains("shared_target_junction");
  }

  private static void addSharedPrefix(
      Map<String, Set<String>> permitted, List<Cell> earlier, List<int[]> candidate, String owner) {
    int shared = 0;
    while (shared < earlier.size()
        && shared < candidate.size()
        && earlier
            .get(shared)
            .equals(new Cell(candidate.get(shared)[0], candidate.get(shared)[1]))) {
      shared++;
    }
    if (shared == 0 || shared == earlier.size() || shared == candidate.size()) {
      return;
    }
    for (int index = 0; index < shared; index++) {
      permitted
          .computeIfAbsent(
              cellKey(earlier.get(index).row(), earlier.get(index).col()),
              ignored -> new LinkedHashSet<>())
          .add(owner);
    }
  }

  private static void addSharedSuffix(
      Map<String, Set<String>> permitted, List<Cell> earlier, List<int[]> candidate, String owner) {
    int shared = 0;
    while (shared < earlier.size()
        && shared < candidate.size()
        && earlier
            .get(earlier.size() - 1 - shared)
            .equals(
                new Cell(
                    candidate.get(candidate.size() - 1 - shared)[0],
                    candidate.get(candidate.size() - 1 - shared)[1]))) {
      shared++;
    }
    if (shared == 0 || shared == earlier.size() || shared == candidate.size()) {
      return;
    }
    for (int index = 0; index < shared; index++) {
      Cell cell = earlier.get(earlier.size() - 1 - index);
      permitted
          .computeIfAbsent(cellKey(cell.row(), cell.col()), ignored -> new LinkedHashSet<>())
          .add(owner);
    }
  }

  private void recordObscuredEdge(int row, int col) {
    if (edgeOwners[row][col] != null) {
      obscuredEdges.addAll(edgeOwners[row][col]);
      edgeOwners[row][col] = null;
    }
  }

  private boolean inBounds(int row, int col) {
    return row >= 0 && row < height && col >= 0 && col < width;
  }

  private static String cellKey(int row, int col) {
    return row + ":" + col;
  }

  private record Cell(int row, int col) {}

  private record ProjectedEdge(RoutedEdge edge, List<Cell> cells) {}
}

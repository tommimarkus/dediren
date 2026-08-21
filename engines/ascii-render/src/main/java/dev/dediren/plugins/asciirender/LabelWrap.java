package dev.dediren.plugins.asciirender;

import java.util.ArrayList;
import java.util.List;

/** Greedy word-wrap for node/group labels rendered onto a character grid. */
final class LabelWrap {

  private LabelWrap() {}

  /**
   * Wraps {@code label} to lines no longer than {@code maxCols}, greedily packing whitespace-
   * separated words and hard-breaking a word that alone exceeds {@code maxCols}. Runs of whitespace
   * collapse to a single space before wrapping. A null or blank label wraps to an empty list.
   */
  static List<String> wrap(String label, int maxCols) {
    List<String> lines = new ArrayList<>();
    if (label == null) {
      return lines;
    }
    String collapsed = label.trim().replaceAll("\\s+", " ");
    if (collapsed.isEmpty()) {
      return lines;
    }
    StringBuilder current = new StringBuilder();
    for (String word : collapsed.split(" ")) {
      while (word.length() > maxCols) {
        if (current.length() > 0) {
          lines.add(current.toString());
          current = new StringBuilder();
        }
        lines.add(word.substring(0, maxCols));
        word = word.substring(maxCols);
      }
      if (current.length() == 0) {
        current.append(word);
      } else if (current.length() + 1 + word.length() <= maxCols) {
        current.append(' ').append(word);
      } else {
        lines.add(current.toString());
        current = new StringBuilder(word);
      }
    }
    if (current.length() > 0) {
      lines.add(current.toString());
    }
    return lines;
  }
}

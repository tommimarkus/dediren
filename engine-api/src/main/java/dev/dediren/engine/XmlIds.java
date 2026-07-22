package dev.dediren.engine;

import java.util.Set;

/**
 * Identifier slugging and collision handling for XML-emitting engines. One implementation,
 * referenced from both export engines (which may not depend on each other), so the id shape of the
 * two artifact families cannot drift apart silently.
 */
public final class XmlIds {
  private XmlIds() {}

  /**
   * Lower-cases ASCII letters/digits and collapses every other run to a single dash; a value with
   * nothing usable becomes {@code "item"}.
   */
  public static String slug(String value) {
    StringBuilder result = new StringBuilder();
    boolean previousDash = false;
    for (char character : value.toCharArray()) {
      if (Character.isLetterOrDigit(character) && character < 128) {
        result.append(Character.toLowerCase(character));
        previousDash = false;
      } else if (!previousDash) {
        result.append("-");
        previousDash = true;
      }
    }
    String trimmed = result.toString().replaceAll("^-+|-+$", "");
    return trimmed.isEmpty() ? "item" : trimmed;
  }

  /**
   * Claims {@code base} in {@code used}, or the first {@code base-N} (N starting at 2) that is
   * free, and returns the claimed id.
   */
  public static String unique(Set<String> used, String base) {
    if (used.add(base)) {
      return base;
    }
    int suffix = 2;
    while (true) {
      String candidate = base + "-" + suffix;
      if (used.add(candidate)) {
        return candidate;
      }
      suffix++;
    }
  }
}

package dev.dediren.plugins.umlxmi.build;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.slug;

import java.util.HashSet;
import java.util.Set;

public final class IdentifierMap {
  private final Set<String> used = new HashSet<>();

  public IdentifierMap(String reserved) {
    used.add(reserved);
  }

  public String xmiId(String value) {
    String base = "id-" + slug(value);
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

package dev.dediren.plugins.umlxmi.build;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.slug;

import dev.dediren.engine.XmlIds;
import java.util.HashSet;
import java.util.Set;

public final class IdentifierMap {
  private final Set<String> used = new HashSet<>();

  public IdentifierMap(String reserved) {
    used.add(reserved);
  }

  public String xmiId(String value) {
    return XmlIds.unique(used, "id-" + slug(value));
  }
}

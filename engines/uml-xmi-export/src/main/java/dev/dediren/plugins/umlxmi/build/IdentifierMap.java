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

  /**
   * Claims {@code ownerId + suffix} in the same shared id space as {@link #xmiId} (or the first
   * free {@code -N} variant on collision), for a child id derived from an already-minted owner id.
   * Deriving without claiming would let an independently minted {@code xmi:id} (for example an
   * attribute literally named {@code qty-lower} next to {@code qty}) duplicate the derived string;
   * when the derived id is free it is returned unchanged.
   */
  public String derivedId(String ownerId, String suffix) {
    return XmlIds.unique(used, ownerId + suffix);
  }
}

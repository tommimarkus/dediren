package dev.dediren.semantics.graph;

import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;

/**
 * Resolves the typed {@link GenericGraphSemanticProfile} a source model declares. Relocated from
 * the old {@code GenericGraphSemanticProfiles}, but returning the typed profile instead of a wire
 * string: routing is now typed and the router keys its {@code NotationSemantics} map by this enum.
 * The stringly {@code profileName} switch survives only as {@link #wireName} for the
 * render-metadata wire field.
 */
public final class SemanticProfiles {
  private SemanticProfiles() {}

  /**
   * The profile a source declares, defaulting to {@link GenericGraphSemanticProfile#GENERIC_GRAPH}
   * when the source omits one.
   */
  public static GenericGraphSemanticProfile sourceSemanticProfile(
      GenericGraphPluginData pluginData) {
    return pluginData.semanticProfile() != null
        ? pluginData.semanticProfile()
        : GenericGraphSemanticProfile.GENERIC_GRAPH;
  }

  /** The wire name for a profile, matching its {@code @JsonProperty}. */
  static String wireName(GenericGraphSemanticProfile profile) {
    return switch (profile) {
      case GENERIC_GRAPH -> "generic-graph";
      case ARCHIMATE -> "archimate";
      case UML -> "uml";
    };
  }
}

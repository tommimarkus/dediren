package dev.dediren.plugins.genericgraph;

import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;

final class GenericGraphSemanticProfiles {
    private GenericGraphSemanticProfiles() {
    }

    static String sourceSemanticProfile(GenericGraphPluginData pluginData) {
        if (pluginData.semanticProfile() != null) {
            return profileName(pluginData.semanticProfile());
        }
        return "generic-graph";
    }

    private static String profileName(GenericGraphSemanticProfile profile) {
        return switch (profile) {
            case GENERIC_GRAPH -> "generic-graph";
            case ARCHIMATE -> "archimate";
            case UML -> "uml";
        };
    }
}

package dev.dediren.plugins.genericgraph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenericGraphSemanticProfilesTest {
    @Test
    void explicitSemanticProfileIsReturned() {
        GenericGraphPluginData pluginData = new GenericGraphPluginData(
                GenericGraphSemanticProfile.UML,
                List.of());

        assertThat(GenericGraphSemanticProfiles.sourceSemanticProfile(pluginData)).isEqualTo("uml");
    }

    @Test
    void missingSemanticProfileDefaultsToGenericGraph() {
        GenericGraphPluginData pluginData = new GenericGraphPluginData(null, List.of());

        assertThat(GenericGraphSemanticProfiles.sourceSemanticProfile(pluginData)).isEqualTo("generic-graph");
    }
}

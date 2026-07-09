package dev.dediren.semantics.graph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.source.GenericGraphPluginData;
import dev.dediren.contracts.source.GenericGraphSemanticProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Relocated from the old {@code GenericGraphSemanticProfilesTest}. {@link
 * SemanticProfiles#sourceSemanticProfile} now returns the typed {@link GenericGraphSemanticProfile}
 * instead of a wire string, so these assert on the enum directly.
 */
class SemanticProfilesTest {
  @Test
  void explicitSemanticProfileIsReturned() {
    GenericGraphPluginData pluginData =
        new GenericGraphPluginData(GenericGraphSemanticProfile.UML, List.of());

    assertThat(SemanticProfiles.sourceSemanticProfile(pluginData))
        .isEqualTo(GenericGraphSemanticProfile.UML);
  }

  @Test
  void missingSemanticProfileDefaultsToGenericGraph() {
    GenericGraphPluginData pluginData = new GenericGraphPluginData(null, List.of());

    assertThat(SemanticProfiles.sourceSemanticProfile(pluginData))
        .isEqualTo(GenericGraphSemanticProfile.GENERIC_GRAPH);
  }
}

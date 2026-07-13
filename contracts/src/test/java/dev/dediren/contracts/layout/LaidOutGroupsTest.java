package dev.dediren.contracts.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The provenance rule that decides which source element a laid-out group stands for. Both export
 * engines interpret it, so it is owned here rather than copied into each of them.
 */
class LaidOutGroupsTest {

  @Test
  void aGroupWithoutProvenanceStandsForItsOwnSourceId() {
    assertThat(LaidOutGroups.semanticSourceId(group(null, "grp-1"))).isEqualTo("grp-1");
  }

  @Test
  void aVisualOnlyGroupStandsForNoSourceElement() {
    GroupProvenance provenance = GroupProvenance.visualOnlyGroup();

    assertThat(LaidOutGroups.semanticSourceId(group(provenance, "grp-1"))).isNull();
  }

  @Test
  void aSemanticProvenanceSourceIdWinsOverTheGroupsOwnSourceId() {
    GroupProvenance provenance = GroupProvenance.semanticBacked("grouping-7");

    assertThat(LaidOutGroups.semanticSourceId(group(provenance, "grp-1"))).isEqualTo("grouping-7");
  }

  @Test
  void provenanceWithoutASemanticSourceIdFallsBackToTheGroupsOwnSourceId() {
    GroupProvenance provenance = new GroupProvenance(false, null);

    assertThat(LaidOutGroups.semanticSourceId(group(provenance, "grp-1"))).isEqualTo("grp-1");
  }

  private static LaidOutGroup group(GroupProvenance provenance, String sourceId) {
    return new LaidOutGroup("g1", sourceId, "view-1", provenance, 0, 0, 10, 10, List.of(), "label");
  }
}

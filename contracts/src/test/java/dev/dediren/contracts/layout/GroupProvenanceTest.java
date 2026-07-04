package dev.dediren.contracts.layout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GroupProvenanceTest {
  @Test
  void visualOnlyGroupCarriesNoSemanticSource() {
    GroupProvenance provenance = GroupProvenance.visualOnlyGroup();

    assertThat(provenance.visualOnly()).isTrue();
    assertThat(provenance.semanticBacked()).isNull();
    assertThat(provenance.semanticSourceId()).isNull();
  }

  @Test
  void semanticBackedGroupExposesItsSourceId() {
    GroupProvenance provenance = GroupProvenance.semanticBacked("system-group");

    assertThat(provenance.visualOnly()).isFalse();
    assertThat(provenance.semanticBacked()).isNotNull();
    assertThat(provenance.semanticSourceId()).isEqualTo("system-group");
  }
}

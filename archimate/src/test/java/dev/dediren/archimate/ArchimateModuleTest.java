package dev.dediren.archimate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArchimateModuleTest {
  @Test
  void moduleLoads() {
    assertThat(ArchimateModule.moduleName()).isEqualTo("archimate");
  }
}

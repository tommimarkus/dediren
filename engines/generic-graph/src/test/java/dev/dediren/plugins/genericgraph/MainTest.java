package dev.dediren.plugins.genericgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MainTest {
  @Test
  void moduleLoads() {
    assertThat(Main.moduleName()).isEqualTo("generic-graph");
  }
}

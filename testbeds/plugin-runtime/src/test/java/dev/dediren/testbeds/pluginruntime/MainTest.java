package dev.dediren.testbeds.pluginruntime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MainTest {
  @Test
  void moduleLoads() {
    assertThat(Main.moduleName()).isEqualTo("plugin-runtime-testbed");
  }
}

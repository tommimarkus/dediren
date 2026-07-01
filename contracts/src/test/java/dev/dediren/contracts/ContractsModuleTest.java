package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractsModuleTest {
  @Test
  void moduleLoads() {
    assertThat(ContractsModule.moduleName()).isEqualTo("contracts");
  }
}

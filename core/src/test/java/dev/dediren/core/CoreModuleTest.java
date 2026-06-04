package dev.dediren.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoreModuleTest {
    @Test
    void moduleLoads() {
        assertThat(CoreModule.moduleName()).isEqualTo("core");
    }
}

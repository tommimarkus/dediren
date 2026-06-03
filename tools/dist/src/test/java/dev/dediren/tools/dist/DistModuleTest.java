package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DistModuleTest {
    @Test
    void moduleLoads() {
        assertThat(DistModule.moduleName()).isEqualTo("dist");
    }
}

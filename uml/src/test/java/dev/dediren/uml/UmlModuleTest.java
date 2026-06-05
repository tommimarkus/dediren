package dev.dediren.uml;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UmlModuleTest {
    @Test
    void moduleLoads() {
        assertThat(UmlModule.moduleName()).isEqualTo("uml");
    }
}

package dev.dediren.schemacache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SchemaCacheModuleTest {
    @Test
    void moduleLoads() {
        assertThat(SchemaCacheModule.moduleName()).isEqualTo("schema-cache");
    }
}

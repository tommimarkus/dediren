package dev.dediren.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestSupportTest {
    @Test
    void resolvesWorkspaceRoot() {
        assertThat(TestSupport.workspaceRoot()).isAbsolute();
    }
}

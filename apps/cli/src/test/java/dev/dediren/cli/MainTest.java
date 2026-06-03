package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void moduleLoads() {
        assertThat(Main.moduleName()).isEqualTo("cli");
    }

    @Test
    void versionCommandReportsGradleProductVersion() {
        CliResult result = Main.executeForTesting(new String[]{"--version"}, "");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("dediren 0.18.1");
    }
}

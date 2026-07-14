package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpCommandTest {

  @Test
  void mcpIsAdvertisedInTheTopLevelHelp() {
    CliResult result = Main.executeForTesting(new String[] {"--help"}, "");

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("mcp");
  }

  @Test
  void mcpHelpDocumentsRootAndReadOnly() {
    CliResult result = Main.executeForTesting(new String[] {"mcp", "--help"}, "");

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("--root");
    assertThat(result.stdout()).contains("--read-only");
  }
}

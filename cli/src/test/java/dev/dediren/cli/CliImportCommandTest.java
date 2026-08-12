package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliImportCommandTest {
  @TempDir Path temp;

  @Test
  void importReadsAConfinedFileOrBoundedStdinAndPrintsTheSameEnvelope() throws Exception {
    Path source = temp.resolve("diagram.mmd");
    String mermaid = "flowchart RL\nA[One] --> B[Two]\n";
    Files.writeString(source, mermaid);

    CliResult file = Main.executeForTesting(new String[] {"import", "--plugin", "mermaid", "--input", source.toString()}, "");
    CliResult stdin = Main.executeForTesting(new String[] {"import", "--plugin", "mermaid"}, mermaid);

    assertThat(file.exitCode()).isZero();
    assertThat(stdin.exitCode()).isZero();
    assertThat(JsonSupport.objectMapper().readTree(file.stdout()).at("/data/plugins/generic-graph/views/0/layout_preferences/direction").asText()).isEqualTo("left");
    assertThat(stdin.stdout()).isEqualTo(file.stdout());
  }

  @Test
  void importRejectsMalformedInputWithThePublishedExitCodeAndLocation() throws Exception {
    CliResult result = Main.executeForTesting(new String[] {"import", "--plugin", "mermaid"}, "flowchart TD\nA -->\n");

    assertThat(result.exitCode()).isEqualTo(2);
    assertThat(JsonSupport.objectMapper().readTree(result.stdout()).at("/diagnostics/0/code").asText()).isEqualTo("DEDIREN_MERMAID_SYNTAX_INVALID");
    assertThat(JsonSupport.objectMapper().readTree(result.stdout()).at("/diagnostics/0/path").asText()).isEqualTo("line 2, column 6");
  }
}

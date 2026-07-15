package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the JaCoCo aggregate to the shipped artifact list. The aggregate silently under-reported
 * twice (ir/engine-api until 2026-07-13, mcp-server until 2026-07-15); with this pin, adding a
 * shipped module without adding it to the coverage profile fails the build instead of shrinking the
 * denominator.
 */
class CoverageAggregateTest {

  @Test
  void coverageAggregateCoversEveryFirstPartyArtifact() throws IOException {
    String pom =
        Files.readString(repoRoot().resolve("coverage-report/pom.xml"), StandardCharsets.UTF_8);

    List<String> missing =
        DistTool.FIRST_PARTY_ARTIFACTS.stream()
            .filter(artifact -> !pom.contains("<artifactId>" + artifact + "</artifactId>"))
            .toList();

    assertThat(missing)
        .as("coverage-report's coverage profile must aggregate every shipped first-party module")
        .isEmpty();
  }

  private static Path repoRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

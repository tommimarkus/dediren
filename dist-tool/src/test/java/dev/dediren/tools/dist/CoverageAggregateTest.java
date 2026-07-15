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
 * denominator. The check is scoped to the live (uncommented) coverage profile region, so an
 * XML-commented-out dependency or an incidental mention elsewhere in the file cannot satisfy it.
 */
class CoverageAggregateTest {

  @Test
  void coverageAggregateCoversEveryFirstPartyArtifact() throws IOException {
    String pom =
        Files.readString(repoRoot().resolve("coverage-report/pom.xml"), StandardCharsets.UTF_8);

    String coverageProfile = liveCoverageProfileRegion(pom);

    List<String> missing =
        DistTool.FIRST_PARTY_ARTIFACTS.stream()
            .filter(
                artifact -> !coverageProfile.contains("<artifactId>" + artifact + "</artifactId>"))
            .toList();

    assertThat(missing)
        .as("coverage-report's coverage profile must aggregate every shipped first-party module")
        .isEmpty();
  }

  /**
   * Extracts the {@code <profile><id>coverage</id>...</profile>} region of {@code pom} and strips
   * XML comments from it, so a dependency that is only present in a commented-out block cannot
   * satisfy the containment check.
   */
  private static String liveCoverageProfileRegion(String pom) {
    int start = pom.indexOf("<id>coverage</id>");
    assertThat(start)
        .as("coverage-report/pom.xml must contain a <id>coverage</id> profile marker")
        .isGreaterThanOrEqualTo(0);

    int end = pom.indexOf("</profile>", start);
    assertThat(end)
        .as("coverage-report/pom.xml must close the coverage profile with a </profile> tag")
        .isGreaterThanOrEqualTo(0);

    String region = pom.substring(start, end);
    return region.replaceAll("(?s)<!--.*?-->", "");
  }

  private static Path repoRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

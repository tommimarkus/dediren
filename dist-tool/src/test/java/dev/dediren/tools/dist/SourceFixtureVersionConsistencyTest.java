package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Guards the shipped source fixtures against version drift: every {@code
 * required_plugins[].version} entry under {@code fixtures/source} must match the product version.
 * Converts the CLAUDE.md release-policy "stale-version search over ... fixtures/source" sweep into
 * an automated check — the bundle packages these fixtures next to agent-usage examples that cite
 * the product version, and consecutive release bumps have shipped with the manual sweep missed.
 */
class SourceFixtureVersionConsistencyTest {

  @Test
  void sourceFixtureRequiredPluginVersionsMatchProductVersion() throws IOException {
    Path repoRoot = repoRoot();
    String expected = productVersion(repoRoot);

    List<Path> fixtures;
    try (var entries = Files.list(repoRoot.resolve("fixtures/source"))) {
      fixtures =
          entries.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
    }

    List<String> mismatched = new ArrayList<>();
    int checked = 0;
    for (Path fixture : fixtures) {
      JsonNode model =
          JsonSupport.objectMapper().readTree(Files.readString(fixture, StandardCharsets.UTF_8));
      for (JsonNode plugin : model.path("required_plugins")) {
        JsonNode version = plugin.path("version");
        if (version.isMissingNode()) {
          continue;
        }
        checked++;
        if (!expected.equals(version.asText())) {
          mismatched.add(
              fixture.getFileName()
                  + ": required_plugins['"
                  + plugin.path("id").asText()
                  + "'] = "
                  + version.asText());
        }
      }
    }

    // Zero scanned entries means the scan itself broke (moved directory, renamed field); that must
    // fail rather than let the guard pass vacuously forever.
    assertThat(checked)
        .as("fixtures/source scan found no required_plugins[].version entries")
        .isPositive();
    assertThat(mismatched)
        .as(
            "fixtures/source required_plugins[].version entries must match the product version "
                + expected
                + " (the release-policy fixture sweep, automated)")
        .isEmpty();
  }

  private static String productVersion(Path repoRoot) throws IOException {
    String pom = Files.readString(repoRoot.resolve("pom.xml"), StandardCharsets.UTF_8);
    Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(pom);
    if (!matcher.find()) {
      throw new IllegalStateException("no <version> element in root pom.xml");
    }
    return matcher.group(1).trim();
  }

  private static Path repoRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

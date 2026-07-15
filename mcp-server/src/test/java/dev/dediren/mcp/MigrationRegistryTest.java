package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.KnownSchemaVersions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the version registry against the migration prose in both directions.
 *
 * <p>A superseded version with no upgrade steps is a dead end for whoever holds that file, and
 * upgrade steps for a version the gate does not recognize are unreachable. Neither can ship.
 */
class MigrationRegistryTest {

  private static final Pattern STEP_HEADING = Pattern.compile("### (\\S+) → (\\S+)");

  @Test
  void everySupersededVersionHasUpgradeSteps() {
    String migration = GuideCatalog.section("migration");
    List<String> undocumented = new ArrayList<>();

    for (KnownSchemaVersions.Family family : KnownSchemaVersions.ALL) {
      for (String prior : family.priorVersions()) {
        if (!migration.contains("### " + prior + " → ")) {
          undocumented.add(prior);
        }
      }
    }

    assertThat(undocumented)
        .as(
            "every superseded version in KnownSchemaVersions needs a '### <from> → <to>'"
                + " subsection under '## Migration' in docs/agent-usage.md — the gate points"
                + " people there, so a missing entry is a dead end")
        .isEmpty();
  }

  @Test
  void everyUpgradeStepDescribesAVersionTheGateRecognizes() {
    List<String> known = new ArrayList<>();
    for (KnownSchemaVersions.Family family : KnownSchemaVersions.ALL) {
      known.addAll(family.priorVersions());
    }

    Matcher matcher = STEP_HEADING.matcher(GuideCatalog.section("migration"));
    List<String> orphaned = new ArrayList<>();
    while (matcher.find()) {
      if (!known.contains(matcher.group(1))) {
        orphaned.add(matcher.group(1));
      }
    }

    assertThat(orphaned)
        .as(
            "every '### <from> → <to>' subsection must describe a version listed in"
                + " KnownSchemaVersions — otherwise the gate never sends anyone to it")
        .isEmpty();
  }

  @Test
  void theMigrationTopicIsReachable() {
    assertThat(GuideCatalog.topics()).contains("migration");
    assertThat(GuideCatalog.section("migration")).doesNotContain("unknown topic");
  }

  @Test
  void everyUpgradeStepPointsAtTheVersionThatSupersededIt() {
    Map<String, String> successor = new HashMap<>();
    for (KnownSchemaVersions.Family family : KnownSchemaVersions.ALL) {
      List<String> versions = family.versions();
      for (int i = 0; i < versions.size() - 1; i++) {
        successor.put(versions.get(i), versions.get(i + 1));
      }
    }

    Matcher matcher = STEP_HEADING.matcher(GuideCatalog.section("migration"));
    List<String> wrong = new ArrayList<>();
    while (matcher.find()) {
      String expected = successor.get(matcher.group(1));
      if (expected != null && !expected.equals(matcher.group(2))) {
        wrong.add(matcher.group(1) + " → " + matcher.group(2) + " (expected → " + expected + ")");
      }
    }

    assertThat(wrong)
        .as(
            "each '### <from> → <to>' heading must name the version that directly"
                + " superseded <from> — a typo'd <to> sends the reader to a version"
                + " that never followed it")
        .isEmpty();
  }
}

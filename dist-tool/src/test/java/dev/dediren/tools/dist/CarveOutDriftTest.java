package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.testsupport.TestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the cross-document duplication that {@code .lean-audit.toml} declares intentional.
 *
 * <p>Each {@code [[carve_out]]} pair deliberately states one contract for two audiences, kept in
 * sync "by review, not by citation". Review is not a mechanism: the lean-audit engine never even
 * reads the counterpart files (its guard globs cover only {@code CLAUDE.md}, {@code AGENTS.md}, and
 * {@code README.md}), so a declared carve-out is a record of intent, not a control. This test is
 * the control — it turns each declared pair into a build failure when the two sides drift apart.
 *
 * <p>The third test closes the loop: declaring a new carve-out without adding a guard here fails,
 * so the registry cannot silently grow unenforced entries.
 */
class CarveOutDriftTest {
  private static final Pattern DIAGNOSTIC = Pattern.compile("DEDIREN_[A-Z_]+");
  private static final Pattern CARVE_OUT_PATH = Pattern.compile("^\\s*[ab]\\s*=\\s*\"([^\"]+)\"");

  /** Carve-out pairs this class guards, as repo-relative path pairs sorted within each pair. */
  private static final Set<String> GUARDED_PAIRS =
      Set.of("CLAUDE.md|docs/features/engine-runtime.md", "CLAUDE.md|docs/threat-model.md");

  @Test
  void engineContractCodesStaySyncedWithTheAgentQuickReference() throws IOException {
    Path root = TestSupport.workspaceRoot();
    String featurePage =
        section(root.resolve("docs/features/engine-runtime.md"), "Engine Contract");
    String quickRef = section(root.resolve("CLAUDE.md"), "Engine Runtime Rules");

    Set<String> onFeaturePage = tokens(featurePage);
    Set<String> missingFromQuickRef = new TreeSet<>(onFeaturePage);
    missingFromQuickRef.removeAll(tokens(quickRef));

    // One-way containment is the real invariant: CLAUDE.md legitimately carries codes the feature
    // page does not (DEDIREN_LOG_LEVEL is a logging env var, not an engine-contract code), so
    // asserting set equality would fail on a difference that is correct by design.
    assertThat(onFeaturePage)
        .as("sanity: the feature page must name engine codes at all")
        .isNotEmpty();
    assertThat(missingFromQuickRef)
        .as(
            "docs/features/engine-runtime.md '## Engine Contract' names diagnostic codes absent"
                + " from CLAUDE.md '## Engine Runtime Rules'. These two sections are a declared"
                + " .lean-audit.toml carve-out and must state the same engine contract; a code"
                + " renamed or added on one side only is exactly the drift the carve-out assumes"
                + " review would catch")
        .isEmpty();
  }

  @Test
  void threatBoundaryTriggersStaySyncedWithFilesThatMoveTogether() throws IOException {
    Path root = TestSupport.workspaceRoot();
    List<String> triggers = trustBoundaryTriggers(root.resolve("docs/threat-model.md"));
    String filesThatMoveTogether = section(root.resolve("CLAUDE.md"), "Files That Move Together");
    String haystack = normalize(filesThatMoveTogether);

    // The threat model is canonical for what the trust boundaries are; CLAUDE.md restates them as a
    // co-change trigger list. Deriving from the threat model keeps this test from becoming a third
    // copy of the list that could itself drift.
    assertThat(triggers)
        .as("sanity: the Maintenance Rule must enumerate trust boundaries")
        .hasSizeGreaterThanOrEqualTo(4);

    Set<String> missing = new TreeSet<>();
    for (String trigger : triggers) {
      if (!haystack.contains(trigger)) {
        missing.add(trigger);
      }
    }

    assertThat(missing)
        .as(
            "docs/threat-model.md '## Maintenance Rule' lists trust boundaries that CLAUDE.md"
                + " '## Files That Move Together' does not name as co-change triggers. Both sides"
                + " are a declared .lean-audit.toml carve-out stating one reciprocal maintenance"
                + " contract; adding a trust boundary to one side only silently drops the other"
                + " side's obligation")
        .isEmpty();
  }

  @Test
  void everyDeclaredCarveOutHasAGuardInThisClass() throws IOException {
    Path root = TestSupport.workspaceRoot();
    Set<String> declared = declaredCarveOutPairs(root.resolve(".lean-audit.toml"));

    assertThat(declared)
        .as(
            ".lean-audit.toml declares a carve-out pair with no drift guard in CarveOutDriftTest."
                + " A carve-out exempts a pair from duplication detection, so each one needs a"
                + " guard here or the exemption removes the only check without adding another")
        .isEqualTo(GUARDED_PAIRS);
  }

  /** Body of a {@code ## Heading} section, up to the next {@code ##} heading or end of file. */
  private static String section(Path file, String heading) throws IOException {
    String text = Files.readString(file, StandardCharsets.UTF_8);
    Matcher start =
        Pattern.compile("^##\\s+" + Pattern.quote(heading) + "\\s*$", Pattern.MULTILINE)
            .matcher(text);
    if (!start.find()) {
      throw new IllegalStateException("no '## " + heading + "' section in " + file);
    }
    Matcher next = Pattern.compile("^##\\s+", Pattern.MULTILINE).matcher(text);
    int end = next.find(start.end()) ? next.start() : text.length();
    return text.substring(start.end(), end);
  }

  private static Set<String> tokens(String body) {
    Set<String> found = new TreeSet<>();
    Matcher matcher = DIAGNOSTIC.matcher(body);
    while (matcher.find()) {
      found.add(matcher.group());
    }
    return found;
  }

  /**
   * Trust-boundary phrases the Maintenance Rule enumerates after its "it describes:" colon, reduced
   * to the distinctive trailing words so the two sides need not use identical wording ("the
   * single-JVM engine runtime" and "Engine runtime ... changes" both reduce to "engine runtime").
   */
  private static List<String> trustBoundaryTriggers(Path threatModel) throws IOException {
    String rule = section(threatModel, "Maintenance Rule");
    int colon = rule.indexOf("it describes:");
    if (colon < 0) {
      throw new IllegalStateException(
          "Maintenance Rule no longer introduces its list with"
              + " 'it describes:' — update this parser rather than deleting the guard");
    }
    String list = rule.substring(colon + "it describes:".length());
    int stop = list.indexOf('.');
    if (stop >= 0) {
      list = list.substring(0, stop);
    }
    return java.util.Arrays.stream(list.split(",| or "))
        .map(CarveOutDriftTest::distinctiveTail)
        .filter(s -> !s.isBlank())
        .toList();
  }

  /**
   * Last two words of a phrase, normalized and de-pluralized (e.g. "release workflows" -> "release
   * workflow").
   */
  private static String distinctiveTail(String phrase) {
    String[] words = normalize(phrase).split("\\s+");
    if (words.length == 0) {
      return "";
    }
    String last = words[words.length - 1];
    if (last.endsWith("s") && !last.endsWith("ss")) {
      last = last.substring(0, last.length() - 1);
    }
    return words.length >= 2 ? words[words.length - 2] + " " + last : last;
  }

  private static String normalize(String text) {
    return text.replaceAll("[`*]", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
  }

  private static Set<String> declaredCarveOutPairs(Path registry) throws IOException {
    Set<String> pairs = new TreeSet<>();
    List<String> current = new java.util.ArrayList<>();
    for (String line : Files.readAllLines(registry, StandardCharsets.UTF_8)) {
      if (line.trim().equals("[[carve_out]]")) {
        current.clear();
        continue;
      }
      Matcher matcher = CARVE_OUT_PATH.matcher(line);
      if (matcher.find()) {
        // Globs are written with a **/ prefix so they also match shadow-prefixed scans; the guard
        // list here is in repo-relative terms.
        current.add(matcher.group(1).replaceFirst("^\\*\\*/", ""));
        if (current.size() == 2) {
          List<String> sorted = new java.util.ArrayList<>(current);
          java.util.Collections.sort(sorted);
          pairs.add(String.join("|", sorted));
          current.clear();
        }
      }
    }
    return pairs;
  }
}

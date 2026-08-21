package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.dediren.contracts.analysis.StatusResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.analysis.ProvenanceCheck;
import dev.dediren.testsupport.TestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Always-on drift gate for {@code docs/architecture/dediren.dediren}, Dediren's dogfood self-model
 * that feeds the README hero image and the Pages site. No test pinned it before this one, so it
 * has gone stale silently twice: the {@code drawio} lane (commit 36db762) had to add the missing
 * module *and* discovered the README hero counts had already drifted since {@code dot-import}, and
 * {@code ascii-render} (v2026.08.8) is stale right now. This replaces the manual sweep with three
 * checks: the published artifacts are not stale, every reactor module is modelled, and the README
 * hero counts match what the model itself says.
 */
class SelfModelFreshnessTest {

  private static final Path PACKAGE_ROOT =
      TestSupport.workspaceRoot().resolve("docs/architecture/dediren.dediren");

  // Test-scope scaffolding never on a production compile path, plus the build-only (-Pcoverage)
  // aggregator that ships nothing: neither belongs in a diagram of Dediren's own module graph.
  private static final List<String> EXCLUDED_MODULES = List.of("test-support", "coverage-report");

  @Test
  void publishedArtifactsAreNotStale() throws IOException {
    StatusResult status = ProvenanceCheck.status(PACKAGE_ROOT, null);

    // Vacuity guard: zero scanned artifacts means the scan itself broke (moved directory, renamed
    // suffix), which must fail loudly rather than let this gate pass by finding nothing.
    assertThat(status.artifacts())
        .as("docs/architecture/dediren.dediren scan found no stamped artifacts")
        .isNotEmpty();

    List<String> stale =
        status.artifacts().stream()
            .filter(artifact -> !ProvenanceCheck.CURRENT.equals(artifact.status()))
            .map(artifact -> artifact.path() + " = " + artifact.status())
            .toList();
    assertThat(stale)
        .as(
            "stale/unstamped self-model artifacts; rebuild with "
                + "'./mvnw -pl dist-tool -am verify -Pdist-build' then "
                + "'\"$BUNDLE/bin/dediren\" build --package"
                + " docs/architecture/dediren.dediren/package.json'")
        .isEmpty();
  }

  @Test
  void everyReactorModuleIsModelled() throws IOException {
    Path repoRoot = TestSupport.workspaceRoot();
    List<String> modules = reactorModuleIds(repoRoot);

    // Vacuity guard: zero scanned reactor modules means the <module> regex broke, not that the
    // reactor is genuinely empty.
    assertThat(modules).as("root pom.xml scan found no <module> entries").isNotEmpty();

    JsonNode model =
        JsonSupport.objectMapper()
            .readTree(
                Files.readString(PACKAGE_ROOT.resolve("model.json"), StandardCharsets.UTF_8));
    List<String> nodeIds = new ArrayList<>();
    for (JsonNode node : model.path("nodes")) {
      nodeIds.add(node.path("id").asText());
    }

    // Vacuity guard: zero modelled nodes means the model.json shape assumption (top-level "nodes")
    // broke, not that the model is genuinely empty.
    assertThat(nodeIds)
        .as("docs/architecture/dediren.dediren/model.json has no nodes")
        .isNotEmpty();

    List<String> missing = modules.stream().filter(id -> !nodeIds.contains(id)).toList();
    assertThat(missing)
        .as(
            "reactor modules missing from docs/architecture/dediren.dediren/model.json; add each as"
                + " a node and regenerate the package")
        .isEmpty();
  }

  @Test
  void readmeHeroCountsMatchModel() throws IOException {
    JsonNode model =
        JsonSupport.objectMapper()
            .readTree(
                Files.readString(PACKAGE_ROOT.resolve("model.json"), StandardCharsets.UTF_8));
    JsonNode view = model.path("plugins").path("generic-graph").path("views").get(0);

    int moduleCount = view.path("nodes").size();

    JsonNode tier2 = null;
    for (JsonNode group : view.path("groups")) {
      if ("grp-tier2".equals(group.path("id").asText())) {
        tier2 = group;
        break;
      }
    }
    assertThat(tier2)
        .as("module-architecture view has no group with id grp-tier2")
        .isNotNull();
    int engineCount = tier2.path("members").size() - 1; // minus "core" itself

    String moduleWord = numberWord(moduleCount);
    String engineWord = numberWord(engineCount);

    String readme =
        Files.readString(TestSupport.workspaceRoot().resolve("README.md"), StandardCharsets.UTF_8);

    assertAll(
        "README.md hero counts must match the module-architecture view ("
            + moduleCount
            + " modules, "
            + engineCount
            + " engines)",
        () ->
            assertThat(readme)
                .as("hero alt text should read '" + moduleWord + " Maven modules'")
                .contains(moduleWord + " Maven modules"),
        () ->
            assertThat(readme)
                .as(
                    "hero alt text should read 'the orchestration core and "
                        + engineWord
                        + " engines'")
                .contains("the orchestration core and " + engineWord + " engines"),
        () ->
            assertThat(readme)
                .as(
                    "prose below the hero should read 'orchestration `core` and the "
                        + engineWord
                        + " engines'")
                .contains("orchestration `core` and the " + engineWord + " engines"));
  }

  private static List<String> reactorModuleIds(Path repoRoot) throws IOException {
    String pom = Files.readString(repoRoot.resolve("pom.xml"), StandardCharsets.UTF_8);
    Matcher matcher = Pattern.compile("<module>([^<]+)</module>").matcher(pom);
    List<String> modules = new ArrayList<>();
    while (matcher.find()) {
      String entry = matcher.group(1).trim();
      // Module ids follow the directory name, not the path: "engines/ascii-render" becomes
      // "ascii-render".
      int slash = entry.lastIndexOf('/');
      String id = slash < 0 ? entry : entry.substring(slash + 1);
      if (!EXCLUDED_MODULES.contains(id)) {
        modules.add(id);
      }
    }
    return modules;
  }

  // Bounded number-word table: extend it (never guess or fail silently) if a derived count lands
  // outside this range.
  private static final Map<Integer, String> NUMBER_WORDS =
      Map.ofEntries(
          Map.entry(1, "one"),
          Map.entry(2, "two"),
          Map.entry(3, "three"),
          Map.entry(4, "four"),
          Map.entry(5, "five"),
          Map.entry(6, "six"),
          Map.entry(7, "seven"),
          Map.entry(8, "eight"),
          Map.entry(9, "nine"),
          Map.entry(10, "ten"),
          Map.entry(11, "eleven"),
          Map.entry(12, "twelve"),
          Map.entry(13, "thirteen"),
          Map.entry(14, "fourteen"),
          Map.entry(15, "fifteen"),
          Map.entry(16, "sixteen"),
          Map.entry(17, "seventeen"),
          Map.entry(18, "eighteen"),
          Map.entry(19, "nineteen"),
          Map.entry(20, "twenty"),
          Map.entry(21, "twenty-one"),
          Map.entry(22, "twenty-two"),
          Map.entry(23, "twenty-three"),
          Map.entry(24, "twenty-four"),
          Map.entry(25, "twenty-five"),
          Map.entry(26, "twenty-six"),
          Map.entry(27, "twenty-seven"),
          Map.entry(28, "twenty-eight"),
          Map.entry(29, "twenty-nine"),
          Map.entry(30, "thirty"));

  private static String numberWord(int count) {
    String word = NUMBER_WORDS.get(count);
    if (word == null) {
      throw new AssertionError(
          "derived count "
              + count
              + " has no English number-word in the bounded table; extend NUMBER_WORDS rather than"
              + " let this pass vacuously");
    }
    return word;
  }
}

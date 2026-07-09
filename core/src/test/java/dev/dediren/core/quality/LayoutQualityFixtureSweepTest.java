package dev.dediren.core.quality;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// Real-engine quality gate (Plan B P2, Task 10/11): every checked-in layout-result fixture is
// either real generic-graph + elk-layout engine output (regenerated via
// scripts/regen-layout-fixtures.sh) or the one hand-authored quality oracle
// (uml-sequence-validatable.json) that is itself constructed to be known-good. Neither kind may
// carry a HARD-error diagnostic (malformed geometry, a route detached from its node, a junction
// off its incident route, a degenerate self-loop) and every warning-eligible structural quality
// metric must stay at its known-accepted value — zero everywhere the real engine currently
// produces zero. edge_crossing_count is the one metric that is informational only (crossings can
// be unavoidable in non-planar graphs, see LayoutQuality#validateLayout) and is pinned per fixture
// below instead of forced to zero, so a fixture that legitimately has crossings stays honest
// rather than silently passing or masking a regression elsewhere.
class LayoutQualityFixtureSweepTest {

  // Every fixture defaults to zero crossings; only fixtures with a real, expected nonzero count
  // are listed here. A fixture regeneration that shifts this count is a legitimate layout change
  // to review and re-pin, not a silent pass.
  private static final Map<String, Integer> EXPECTED_EDGE_CROSSING_COUNTS =
      Map.of("uml-complex-class.json", 2);

  @ParameterizedTest
  @MethodSource("layoutResultFixtures")
  void realEngineFixturesHaveNoHardErrorsAndKnownQuality(Path fixture) throws IOException {
    String fixtureName = fixture.getFileName().toString();
    LayoutResult result =
        JsonSupport.objectMapper().readValue(Files.readString(fixture), LayoutResult.class);

    assertThat(LayoutQuality.validateLayoutDiagnostics(result))
        .as("hard-error diagnostic in %s", fixtureName)
        .isEmpty();

    LayoutQualityReport report = LayoutQuality.validateLayout(result);
    assertThat(report.status()).as("quality status for %s", fixtureName).isEqualTo("ok");
    assertThat(report.overlapCount()).as("overlap count in %s", fixtureName).isZero();
    assertThat(report.connectorThroughNodeCount())
        .as("connector-through-node count in %s", fixtureName)
        .isZero();
    assertThat(report.invalidRouteCount()).as("invalid route count in %s", fixtureName).isZero();
    assertThat(report.routeDetourCount()).as("route detour count in %s", fixtureName).isZero();
    assertThat(report.routeCloseParallelCount())
        .as("close-parallel route count in %s", fixtureName)
        .isZero();
    assertThat(report.groupBoundaryIssueCount())
        .as("group boundary issue count in %s", fixtureName)
        .isZero();
    assertThat(report.groupLabelBandIssueCount())
        .as("group-label-band issue count in %s", fixtureName)
        .isZero();
    assertThat(report.labelSpaceIssueCount())
        .as("label-space issue count in %s", fixtureName)
        .isZero();
    assertThat(report.edgeLabelDissociationCount())
        .as("edge-label-dissociation count in %s", fixtureName)
        .isZero();
    assertThat(report.warningCount()).as("warning count in %s", fixtureName).isZero();
    assertThat(report.edgeCrossingCount())
        .as("edge crossing count in %s", fixtureName)
        .isEqualTo(EXPECTED_EDGE_CROSSING_COUNTS.getOrDefault(fixtureName, 0));
  }

  static Stream<Path> layoutResultFixtures() throws IOException {
    Path dir = workspaceRoot().resolve("fixtures/layout-result");
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(path -> path.toString().endsWith(".json")).sorted().toList().stream();
    }
  }

  private static Path workspaceRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}

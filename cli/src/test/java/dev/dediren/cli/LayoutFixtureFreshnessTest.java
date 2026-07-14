package dev.dediren.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.testsupport.TestSupport;
import java.nio.file.Files;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Always-on drift gate: every checked-in layout-result fixture must be byte-identical to what the
 * real project->layout pipeline produces today. The opt-in LayoutFixtureRegenerator writes these
 * bytes; this gate keeps them honest between regenerations (the stale-golden failure mode this repo
 * has paid for before). Determinism: bundled Liberation Sans + pinned ELK.
 */
class LayoutFixtureFreshnessTest {

  static Stream<LayoutFixtureRegenerator.FixtureMapping> mappings() {
    return LayoutFixtureRegenerator.mappings().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mappings")
  void checkedInFixtureMatchesRealEngineOutput(LayoutFixtureRegenerator.FixtureMapping mapping)
      throws Exception {
    String checkedIn =
        Files.readString(
            TestSupport.workspaceRoot()
                .resolve("fixtures/layout-result")
                .resolve(mapping.fixtureName()));
    assertThat(LayoutFixtureRegenerator.regeneratedJson(mapping))
        .describedAs(
            "run scripts/regen-layout-fixtures.sh if this drift is an intended geometry change")
        .isEqualTo(checkedIn);
  }
}

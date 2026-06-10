package dev.dediren.core.quality;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// False-positive gate for the new quality checks: every checked-in layout-result fixture is a
// known-good layout of some diagram kind, so the label-space, label-band, and junction checks
// must stay silent on all of them (issue #13 regression class).
class LayoutQualityFixtureSweepTest {

    @ParameterizedTest
    @MethodSource("layoutResultFixtures")
    void newQualityChecksStaySilentOnKnownGoodLayouts(Path fixture) throws IOException {
        LayoutResult result = JsonSupport.objectMapper()
                .readValue(Files.readString(fixture), LayoutResult.class);

        LayoutQualityReport report = LayoutQuality.validateLayout(result);

        assertThat(report.labelSpaceIssueCount())
                .as("label-space false positive in %s", fixture.getFileName())
                .isZero();
        assertThat(report.groupLabelBandIssueCount())
                .as("group-label-band false positive in %s", fixture.getFileName())
                .isZero();
        assertThat(LayoutQuality.validateLayoutDiagnostics(result))
                .filteredOn(diagnostic -> diagnostic.code()
                        .equals("DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE"))
                .as("junction false positive in %s", fixture.getFileName())
                .isEmpty();
    }

    static Stream<Path> layoutResultFixtures() throws IOException {
        Path dir = workspaceRoot().resolve("fixtures/layout-result");
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    private static Path workspaceRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("schemas/model.schema.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from user.dir");
    }
}

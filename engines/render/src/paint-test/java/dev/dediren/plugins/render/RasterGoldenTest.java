package dev.dediren.plugins.render;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import dev.dediren.contracts.json.JsonSupport;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@EnabledIfSystemProperty(named = "dediren.render.paint.enabled", matches = "true")
class RasterGoldenTest {

  private static final String REGENERATE = "dediren.render.paint.regenerate-goldens";

  /**
   * The calibration pair: a static SVG this repository owns and the pixels the environment that
   * minted the baselines produced from it.
   *
   * <p>Regeneration used to require a container digest, passed in an environment variable. That was
   * a <em>proxy</em> for the question that actually matters — does this environment rasterize like
   * the one that produced the committed goldens — and it answered it badly in both directions: an
   * environment drifted inside the right image passed, a byte-identical environment outside it
   * failed, and the variable could simply be set by anyone who wanted past the check. Determinism
   * here comes from things that travel with the repository anyway: Playwright downloads a pinned
   * Chromium, and the font is embedded in the page as a data URI, so the host contributes neither
   * the browser nor the glyphs.
   *
   * <p>So the check measures the thing directly instead. The input is deliberately <em>not</em>
   * produced by the render engine: a rendered fixture would move whenever the renderer changed and
   * would stop being a probe of the environment alone.
   */
  private static final Path CALIBRATION_DIRECTORY =
      BrowserTestSupport.WORKSPACE_ROOT.resolve(
          "engines/render/src/paint-test/resources/raster-calibration");

  private static final Path CALIBRATION_SVG = CALIBRATION_DIRECTORY.resolve("calibration.svg");
  private static final Path CALIBRATION_PNG = CALIBRATION_DIRECTORY.resolve("calibration.png");
  private static final Path GOLDEN_DIRECTORY =
      BrowserTestSupport.WORKSPACE_ROOT.resolve(
          "engines/render/src/paint-test/resources/raster-golden");
  private static final Path MANIFEST = GOLDEN_DIRECTORY.resolve("manifest.json");

  @ParameterizedTest(name = "{0}")
  @MethodSource("goldens")
  void renderedPixelsMatchThePinnedChromiumGolden(
      String name, String layout, String policy, String metadata, @TempDir Path temporaryDirectory)
      throws Exception {
    String svg = RenderTestSupport.renderFixtures(layout, policy, metadata);
    Path actualPath = temporaryDirectory.resolve(name + ".png");
    Path repeatedPath = temporaryDirectory.resolve(name + "-repeat.png");

    String browserVersion = rasterize(svg, actualPath);
    assertThat(rasterize(svg, repeatedPath)).isEqualTo(browserVersion);
    assertThat(Files.readAllBytes(repeatedPath))
        .as("consecutive Chromium paint output for %s", name)
        .isEqualTo(Files.readAllBytes(actualPath));
    BufferedImage actual = readPng(actualPath);
    Path golden = GOLDEN_DIRECTORY.resolve(name + ".png");

    if (Boolean.getBoolean(REGENERATE)) {
      requirePinnedTooling(BrowserTestSupport.playwrightVersion(), browserVersion);
      requireCalibratedRasterizer(temporaryDirectory);
      Files.createDirectories(golden.getParent());
      Files.copy(actualPath, golden, StandardCopyOption.REPLACE_EXISTING);
      updateManifest(name, actual, golden);
      return;
    }

    if (!Files.exists(golden)) {
      fail(
          "No raster golden for scenario '%s'. Generate it only in the pinned paint environment"
              + " with ./scripts/test-render-paint.sh -Dtest=RasterGoldenTest"
              + " -Ddediren.render.paint.regenerate-goldens=true and review the tracked PNG diff.",
          name);
    }

    RasterDiff.assertMatches(name, readPng(golden), actual, BrowserTestSupport.OUTPUT_ROOT);
  }

  @Test
  void manifestPinsToolFontComparatorAndEveryBaseline() throws Exception {
    assertThat(MANIFEST).isRegularFile();
    JsonNode manifest = JsonSupport.objectMapper().readTree(Files.readString(MANIFEST, UTF_8));

    assertThat(manifest.at("/schema").asText())
        .isEqualTo("dediren-render-paint-raster-manifest-v3");
    assertThat(manifest.at("/renderer/name").asText())
        .isEqualTo("Chromium headless shell via Playwright Java");
    assertThat(manifest.at("/renderer/playwright/version").asText())
        .isEqualTo(BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION);
    assertThat(manifest.at("/renderer/chromium/version").asText())
        .isEqualTo(BrowserTestSupport.PINNED_CHROMIUM_VERSION);
    assertThat(manifest.at("/renderer/chromium/revision").asText())
        .isEqualTo(BrowserTestSupport.PINNED_CHROMIUM_REVISION);
    assertThat(manifest.at("/renderer/chromium/shippedWithDediren").asBoolean()).isFalse();
    assertThat(manifest.at("/renderer/chromium/committed").asBoolean()).isFalse();
    assertThat(manifest.at("/renderer/chromium/nativeExecutable").asBoolean()).isTrue();
    assertThat(manifest.at("/renderer/geometryAuthority").asText())
        .contains("Chromium SVG DOM geometry", "pixel masks");
    assertThat(manifest.at("/calibration/svg/path").asText())
        .isEqualTo(BrowserTestSupport.WORKSPACE_ROOT.relativize(CALIBRATION_SVG).toString());
    assertThat(manifest.at("/calibration/svg/sha256").asText()).isEqualTo(sha256(CALIBRATION_SVG));
    assertThat(manifest.at("/calibration/baseline/path").asText())
        .isEqualTo(BrowserTestSupport.WORKSPACE_ROOT.relativize(CALIBRATION_PNG).toString());
    assertThat(manifest.at("/calibration/baseline/sha256").asText())
        .isEqualTo(sha256(CALIBRATION_PNG));
    assertThat(manifest.at("/renderer/rasterEnvironment/deviceScaleFactor").asInt()).isEqualTo(1);
    assertThat(manifest.at("/renderer/rasterEnvironment/viewport").asText())
        .contains("intrinsic SVG", "32 transparent CSS pixels", "64 user-unit safety border");
    assertThat(manifest.at("/renderer/rasterEnvironment/network").asText())
        .isEqualTo(BrowserTestSupport.NETWORK_POLICY);
    assertThat(manifest.at("/renderer/rasterEnvironment/lifecycle").asText())
        .isEqualTo(BrowserTestSupport.LIFECYCLE_POLICY);
    assertThat(manifest.at("/renderer/rasterEnvironment/telemetry").asText())
        .isEqualTo(BrowserTestSupport.TELEMETRY_POLICY);
    assertThat(manifest.at("/comparator/perChannelThreshold").asInt())
        .isEqualTo(RasterDiff.CHANNEL_THRESHOLD);
    assertThat(manifest.at("/font/file/sha256").asText())
        .isEqualTo(sha256(BrowserTestSupport.FONT_PATH));
    Path license =
        BrowserTestSupport.WORKSPACE_ROOT.resolve(manifest.at("/font/license/file/path").asText());
    assertThat(manifest.at("/font/license/file/sha256").asText()).isEqualTo(sha256(license));
    assertThat(manifest.at("/font/license/spdxExpression").asText()).isEqualTo("OFL-1.1-RFN");
    assertThat(manifest.at("/generation/thirdPartyLogosOrEmbeddedFixtureAssets").asBoolean())
        .isFalse();

    JsonNode scenarios = manifest.get("scenarios");
    assertThat(scenarios).isNotNull();
    assertThat(scenarios.size()).isEqualTo(4);
    ArrayList<String> scenarioNames = new ArrayList<>();
    for (JsonNode scenario : scenarios) {
      scenarioNames.add(scenario.get("name").asText());
      Path baseline = BrowserTestSupport.WORKSPACE_ROOT.resolve(scenario.get("baseline").asText());
      assertThat(baseline).isRegularFile();
      assertThat(scenario.get("sha256").asText()).isEqualTo(sha256(baseline));
      BufferedImage image = readPng(baseline);
      assertThat(scenario.at("/dimensions/width").asInt()).isEqualTo(image.getWidth());
      assertThat(scenario.at("/dimensions/height").asInt()).isEqualTo(image.getHeight());
    }
    assertThat(scenarioNames)
        .containsExactly(
            "pipeline-rich-light",
            "pipeline-rich-dark",
            "archimate-decorators",
            "uml-sequence-fragments");
  }

  @Test
  void regenerationRejectsToolingThatDoesNotMatchThePins() {
    requirePinnedTooling(
        BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION, BrowserTestSupport.PINNED_CHROMIUM_VERSION);

    assertThatThrownBy(
            () -> requirePinnedTooling("1.61.1", BrowserTestSupport.PINNED_CHROMIUM_VERSION))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION);
    assertThatThrownBy(
            () ->
                requirePinnedTooling(BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION, "149.0.7827.56"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(BrowserTestSupport.PINNED_CHROMIUM_VERSION);
  }

  @Test
  void thisEnvironmentReproducesTheCalibrationBaseline(@TempDir Path temporaryDirectory)
      throws Exception {
    // The positive half of the regeneration gate, asserted on every paint-lane run rather than
    // only when regenerating: if this host stops agreeing with the calibration pixels, the goldens
    // it would mint are already suspect and the lane should say so immediately.
    requireCalibratedRasterizer(temporaryDirectory);
  }

  @Test
  void regenerationIsRefusedWhenTheRasterizerDisagreesWithTheCalibration(
      @TempDir Path temporaryDirectory) throws Exception {
    // The negative half. A guard nobody has watched fail is a guard nobody knows works, so this
    // perturbs the baseline past the comparator's per-channel threshold and requires the refusal.
    BufferedImage perturbed = readPng(CALIBRATION_PNG);
    for (int x = 0; x < Math.min(24, perturbed.getWidth()); x++) {
      for (int y = 0; y < Math.min(24, perturbed.getHeight()); y++) {
        perturbed.setRGB(x, y, perturbed.getRGB(x, y) ^ 0x00808080);
      }
    }
    Path wrongBaseline = temporaryDirectory.resolve("calibration.png");
    Files.copy(CALIBRATION_SVG, temporaryDirectory.resolve("calibration.svg"));
    ImageIO.write(perturbed, "png", wrongBaseline.toFile());

    Path actual = temporaryDirectory.resolve("actual.png");
    rasterize(Files.readString(CALIBRATION_SVG, UTF_8), actual);
    RasterDiff.Result result = RasterDiff.compare(readPng(wrongBaseline), readPng(actual));

    assertThat(result.matches())
        .as("a baseline perturbed past the channel threshold must not compare equal")
        .isFalse();
    assertThat(result.describe()).contains("changed");
  }

  private static BufferedImage readPng(Path path) throws Exception {
    BufferedImage image = ImageIO.read(path.toFile());
    assertThat(image).as("decodable PNG at %s", path).isNotNull();
    return image;
  }

  private static String rasterize(String svg, Path output) throws Exception {
    try (var built = BrowserTestSupport.build(svg)) {
      BrowserTestSupport.rasterize(built, output);
      return built.browserVersion();
    }
  }

  private static void updateManifest(String name, BufferedImage image, Path baseline)
      throws Exception {
    assertThat(MANIFEST)
        .as("The tracked manifest must exist before regenerating raster goldens")
        .isRegularFile();
    ObjectNode manifest =
        (ObjectNode) JsonSupport.objectMapper().readTree(Files.readString(MANIFEST, UTF_8));
    ObjectNode matchingScenario = null;
    for (JsonNode scenario : manifest.get("scenarios")) {
      if (name.equals(scenario.get("name").asText())) {
        matchingScenario = (ObjectNode) scenario;
        break;
      }
    }
    assertThat(matchingScenario).as("manifest scenario named %s", name).isNotNull();
    ObjectNode dimensions = (ObjectNode) matchingScenario.get("dimensions");
    dimensions.put("width", image.getWidth());
    dimensions.put("height", image.getHeight());
    matchingScenario.put("sha256", sha256(baseline));
    Files.writeString(
        MANIFEST,
        JsonSupport.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(manifest)
            + System.lineSeparator(),
        UTF_8);
  }

  private static String sha256(Path path) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  private static void requirePinnedTooling(String playwrightVersion, String browserVersion) {
    if (!BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION.equals(playwrightVersion)
        || !BrowserTestSupport.PINNED_CHROMIUM_VERSION.equals(browserVersion)) {
      throw new IllegalStateException(
          "Raster-golden regeneration requires Playwright="
              + BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION
              + ", observed Playwright="
              + playwrightVersion
              + "; required Chromium="
              + BrowserTestSupport.PINNED_CHROMIUM_VERSION
              + ", observed Chromium="
              + browserVersion);
    }
  }

  /**
   * Refuses to regenerate unless this environment reproduces the committed calibration pixels.
   *
   * <p>Judged by the same {@link RasterDiff} rules the goldens themselves are judged by, so an
   * environment allowed to mint baselines is exactly an environment that agrees with the existing
   * ones. A mismatch means the pixels this run would write are not the pixels the corpus is built
   * from — regenerating anyway would silently rebase every golden onto an unreproducible machine.
   */
  private static void requireCalibratedRasterizer(Path temporaryDirectory) throws Exception {
    if (!Files.isRegularFile(CALIBRATION_SVG) || !Files.isRegularFile(CALIBRATION_PNG)) {
      throw new IllegalStateException(
          "Raster-golden regeneration requires the calibration pair at "
              + CALIBRATION_DIRECTORY
              + "; without it there is no evidence this environment renders like the one that"
              + " produced the committed goldens");
    }
    Path actual = temporaryDirectory.resolve("calibration-actual.png");
    rasterize(Files.readString(CALIBRATION_SVG, UTF_8), actual);
    RasterDiff.Result result = RasterDiff.compare(readPng(CALIBRATION_PNG), readPng(actual));
    if (!result.matches()) {
      throw new IllegalStateException(
          "Raster-golden regeneration refused: this environment does not reproduce the calibration"
              + " baseline at "
              + CALIBRATION_PNG
              + " ("
              + result.describe()
              + "). Regenerating would rebase every golden onto pixels no other environment can"
              + " reproduce. Investigate the difference before regenerating.");
    }
  }

  static Stream<Arguments> goldens() {
    return Stream.of(
        scenario("pipeline-rich-light", "pipeline-rich", "default-svg", null),
        scenario("pipeline-rich-dark", "pipeline-rich", "dark-svg", null),
        scenario("archimate-decorators", "archimate-oef-basic", "archimate-svg", "archimate-basic"),
        scenario(
            "uml-sequence-fragments",
            "uml-sequence-fragments",
            "uml-svg",
            "uml-sequence-fragments"));
  }

  private static Arguments scenario(String name, String layout, String policy, String metadata) {
    return Arguments.of(
        name,
        "fixtures/layout-result/" + layout + ".json",
        "fixtures/render-policy/" + policy + ".json",
        metadata == null ? null : "fixtures/render-metadata/" + metadata + ".json");
  }
}

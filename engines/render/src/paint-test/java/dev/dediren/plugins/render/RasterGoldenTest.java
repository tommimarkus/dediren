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
  private static final String CONTAINER_DIGEST_ENV = "DEDIREN_RENDER_PAINT_CONTAINER_DIGEST";
  private static final String CANONICAL_JAVA_VENDOR = "Eclipse Adoptium";
  private static final String CANONICAL_JAVA_RUNTIME_VERSION = "21.0.10+7-LTS";
  private static final String CANONICAL_CONTAINER_DIGEST =
      "sha256:a23837c4bb84165c2156f64411ae79d7e42ef91c9d8f960691d06c5d43a684f4";
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
      requireCanonicalRegenerationEnvironment(
          System.getProperty("java.vendor"),
          System.getProperty("java.runtime.version"),
          BrowserTestSupport.playwrightVersion(),
          browserVersion,
          System.getenv(CONTAINER_DIGEST_ENV));
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
        .isEqualTo("dediren-render-paint-raster-manifest-v2");
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
    assertThat(manifest.at("/renderer/container/digest").asText())
        .isEqualTo(CANONICAL_CONTAINER_DIGEST);
    assertThat(manifest.at("/renderer/geometryAuthority").asText())
        .contains("Chromium SVG DOM geometry", "pixel masks");
    assertThat(manifest.at("/renderer/rasterEnvironment/javaVendor").asText())
        .isEqualTo(CANONICAL_JAVA_VENDOR);
    assertThat(manifest.at("/renderer/rasterEnvironment/javaRuntimeVersion").asText())
        .isEqualTo(CANONICAL_JAVA_RUNTIME_VERSION);
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
  void regenerationRejectsAnyEnvironmentThatDoesNotMatchEveryPin() {
    requireCanonicalRegenerationEnvironment(
        CANONICAL_JAVA_VENDOR,
        CANONICAL_JAVA_RUNTIME_VERSION,
        BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION,
        BrowserTestSupport.PINNED_CHROMIUM_VERSION,
        CANONICAL_CONTAINER_DIGEST);

    assertThatThrownBy(
            () ->
                requireCanonicalRegenerationEnvironment(
                    CANONICAL_JAVA_VENDOR,
                    "21.0.10+8-LTS",
                    BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION,
                    BrowserTestSupport.PINNED_CHROMIUM_VERSION,
                    CANONICAL_CONTAINER_DIGEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(CANONICAL_JAVA_RUNTIME_VERSION);
    assertThatThrownBy(
            () ->
                requireCanonicalRegenerationEnvironment(
                    "Another Java vendor",
                    CANONICAL_JAVA_RUNTIME_VERSION,
                    BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION,
                    BrowserTestSupport.PINNED_CHROMIUM_VERSION,
                    CANONICAL_CONTAINER_DIGEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(CANONICAL_JAVA_VENDOR);
    assertThatThrownBy(
            () ->
                requireCanonicalRegenerationEnvironment(
                    CANONICAL_JAVA_VENDOR,
                    CANONICAL_JAVA_RUNTIME_VERSION,
                    "1.61.1",
                    BrowserTestSupport.PINNED_CHROMIUM_VERSION,
                    CANONICAL_CONTAINER_DIGEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION);
    assertThatThrownBy(
            () ->
                requireCanonicalRegenerationEnvironment(
                    CANONICAL_JAVA_VENDOR,
                    CANONICAL_JAVA_RUNTIME_VERSION,
                    BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION,
                    "149.0.7827.56",
                    CANONICAL_CONTAINER_DIGEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(BrowserTestSupport.PINNED_CHROMIUM_VERSION);
    assertThatThrownBy(
            () ->
                requireCanonicalRegenerationEnvironment(
                    CANONICAL_JAVA_VENDOR,
                    CANONICAL_JAVA_RUNTIME_VERSION,
                    BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION,
                    BrowserTestSupport.PINNED_CHROMIUM_VERSION,
                    "sha256:wrong"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(CANONICAL_CONTAINER_DIGEST);
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

  private static void requireCanonicalRegenerationEnvironment(
      String vendor,
      String runtimeVersion,
      String playwrightVersion,
      String browserVersion,
      String containerDigest) {
    if (!CANONICAL_JAVA_VENDOR.equals(vendor)
        || !CANONICAL_JAVA_RUNTIME_VERSION.equals(runtimeVersion)
        || !BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION.equals(playwrightVersion)
        || !BrowserTestSupport.PINNED_CHROMIUM_VERSION.equals(browserVersion)
        || !CANONICAL_CONTAINER_DIGEST.equals(containerDigest)) {
      throw new IllegalStateException(
          "Raster-golden regeneration requires java.vendor="
              + CANONICAL_JAVA_VENDOR
              + " and java.runtime.version="
              + CANONICAL_JAVA_RUNTIME_VERSION
              + "; observed java.vendor="
              + vendor
              + " and java.runtime.version="
              + runtimeVersion
              + "; required Playwright="
              + BrowserTestSupport.PINNED_PLAYWRIGHT_VERSION
              + ", observed Playwright="
              + playwrightVersion
              + "; required Chromium="
              + BrowserTestSupport.PINNED_CHROMIUM_VERSION
              + ", observed Chromium="
              + browserVersion
              + "; required container digest="
              + CANONICAL_CONTAINER_DIGEST
              + ", observed container digest="
              + containerDigest);
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

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
  private static final String CANONICAL_JAVA_VENDOR = "Eclipse Adoptium";
  private static final String CANONICAL_JAVA_RUNTIME_VERSION = "21.0.10+7-LTS";
  private static final Path GOLDEN_DIRECTORY =
      BatikTestSupport.WORKSPACE_ROOT.resolve(
          "engines/render/src/paint-test/resources/raster-golden");
  private static final Path MANIFEST = GOLDEN_DIRECTORY.resolve("manifest.json");

  @ParameterizedTest(name = "{0}")
  @MethodSource("goldens")
  void renderedPixelsMatchThePinnedJavaGolden(
      String name, String layout, String policy, String metadata, @TempDir Path temporaryDirectory)
      throws Exception {
    String svg = RenderTestSupport.renderFixtures(layout, policy, metadata);
    Path actualPath = temporaryDirectory.resolve(name + ".png");
    Path repeatedPath = temporaryDirectory.resolve(name + "-repeat.png");

    rasterize(svg, actualPath);
    rasterize(svg, repeatedPath);
    assertThat(Files.readAllBytes(repeatedPath))
        .as("consecutive Java paint output for %s", name)
        .isEqualTo(Files.readAllBytes(actualPath));
    BufferedImage actual = readPng(actualPath);
    Path golden = GOLDEN_DIRECTORY.resolve(name + ".png");

    if (Boolean.getBoolean(REGENERATE)) {
      requireCanonicalRegenerationEnvironment(
          System.getProperty("java.vendor"), System.getProperty("java.runtime.version"));
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

    RasterDiff.assertMatches(name, readPng(golden), actual, BatikTestSupport.OUTPUT_ROOT);
  }

  @Test
  void manifestPinsToolFontComparatorAndEveryBaseline() throws Exception {
    assertThat(MANIFEST).isRegularFile();
    JsonNode manifest = JsonSupport.objectMapper().readTree(Files.readString(MANIFEST, UTF_8));

    assertThat(manifest.at("/renderer/name").asText()).isEqualTo("Apache Batik GVT + JDK ImageIO");
    assertThat(manifest.at("/renderer/version").asText())
        .isEqualTo(BatikTestSupport.PINNED_BATIK_VERSION);
    assertThat(manifest.at("/renderer/release").asText())
        .isEqualTo("https://xmlgraphics.apache.org/batik/download.cgi");
    assertThat(manifest.at("/renderer/license").asText()).isEqualTo("Apache-2.0");
    assertThat(manifest.at("/renderer/maven/artifact").asText())
        .isEqualTo("org.apache.xmlgraphics:batik-bridge:1.19");
    assertThat(manifest.at("/renderer/maven/scope").asText()).isEqualTo("test-profile-only");
    assertThat(manifest.at("/renderer/shippedWithDediren").asBoolean()).isFalse();
    assertThat(manifest.at("/renderer/nativeExecutable").asBoolean()).isFalse();
    assertThat(manifest.at("/renderer/textGeometryAuthority").asText())
        .isEqualTo("JDK bundled-font oracle, not Batik");
    assertThat(manifest.at("/renderer/rasterEnvironment/javaVendor").asText())
        .isEqualTo(CANONICAL_JAVA_VENDOR);
    assertThat(manifest.at("/renderer/rasterEnvironment/javaRuntimeVersion").asText())
        .isEqualTo(CANONICAL_JAVA_RUNTIME_VERSION);
    assertThat(manifest.at("/comparator/perChannelThreshold").asInt())
        .isEqualTo(RasterDiff.CHANNEL_THRESHOLD);
    assertThat(manifest.at("/font/file/sha256").asText())
        .isEqualTo(sha256(BatikTestSupport.FONT_PATH));
    Path license =
        BatikTestSupport.WORKSPACE_ROOT.resolve(manifest.at("/font/license/file/path").asText());
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
      Path baseline = BatikTestSupport.WORKSPACE_ROOT.resolve(scenario.get("baseline").asText());
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
  void regenerationRejectsAnyJavaRuntimeOtherThanTheManifestPin() {
    requireCanonicalRegenerationEnvironment(CANONICAL_JAVA_VENDOR, CANONICAL_JAVA_RUNTIME_VERSION);

    assertThatThrownBy(
            () -> requireCanonicalRegenerationEnvironment(CANONICAL_JAVA_VENDOR, "21.0.10+8-LTS"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(CANONICAL_JAVA_RUNTIME_VERSION);
    assertThatThrownBy(
            () ->
                requireCanonicalRegenerationEnvironment(
                    "Another Java vendor", CANONICAL_JAVA_RUNTIME_VERSION))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(CANONICAL_JAVA_VENDOR);
  }

  private static BufferedImage readPng(Path path) throws Exception {
    BufferedImage image = ImageIO.read(path.toFile());
    assertThat(image).as("decodable PNG at %s", path).isNotNull();
    return image;
  }

  private static void rasterize(String svg, Path output) throws Exception {
    try (var built = BatikTestSupport.build(svg)) {
      BatikTestSupport.rasterize(built, output);
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
      String vendor, String runtimeVersion) {
    if (!CANONICAL_JAVA_VENDOR.equals(vendor)
        || !CANONICAL_JAVA_RUNTIME_VERSION.equals(runtimeVersion)) {
      throw new IllegalStateException(
          "Raster-golden regeneration requires java.vendor="
              + CANONICAL_JAVA_VENDOR
              + " and java.runtime.version="
              + CANONICAL_JAVA_RUNTIME_VERSION
              + "; observed java.vendor="
              + vendor
              + " and java.runtime.version="
              + runtimeVersion);
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

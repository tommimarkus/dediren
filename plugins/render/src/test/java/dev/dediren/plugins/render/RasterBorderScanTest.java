package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pixel-level crop check on the actual Batik raster, complementing the geometry-level viewBox
 * containment in {@link SvgAudit}. The renderer always insets content by the policy margin and
 * fills the whole viewBox with the background, so the outer border of the PNG must be a uniform
 * background frame. Non-background pixels on the border mean content bled to the edge — a too-tight
 * viewBox or a background rect that no longer covers the canvas (R5/R9). The border lies in the
 * empty margin, so this is font-substitution independent. Comparison is on RGB only: Batik varies
 * the alpha of the outermost pixels via boundary anti-aliasing, but content bleed shows up as a
 * different colour.
 */
class RasterBorderScanTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("rasterScenarios")
  void rasterBorderIsUniformBackground(String name, String layout, String policy, String metadata)
      throws Exception {
    BufferedImage image = rasterize(layout, policy, metadata);
    int width = image.getWidth();
    int height = image.getHeight();
    // RGB of the corner; the SVG background rect fills the viewBox, so the corner is the
    // background.
    int background = image.getRGB(0, 0) & 0xFFFFFF;

    List<String> bleed = new ArrayList<>();
    for (int x = 0; x < width; x++) {
      collectIfNotBackground(image, x, 0, background, bleed);
      collectIfNotBackground(image, x, height - 1, background, bleed);
    }
    for (int y = 0; y < height; y++) {
      collectIfNotBackground(image, 0, y, background, bleed);
      collectIfNotBackground(image, width - 1, y, background, bleed);
    }

    assertThat(bleed)
        .as(
            "content bleeds to the raster border (clipped at the diagram edge); size=%dx%d bg=%08X",
            width, height, background)
        .isEmpty();
  }

  static Stream<Arguments> rasterScenarios() {
    return Stream.of(
        Arguments.of(
            "basic",
            "fixtures/layout-result/basic.json",
            "fixtures/render-policy/default-svg.json",
            null),
        Arguments.of(
            "pipeline-rich",
            "fixtures/layout-result/pipeline-rich.json",
            "fixtures/render-policy/default-svg.json",
            null),
        Arguments.of(
            "uml-basic",
            "fixtures/layout-result/uml-basic.json",
            "fixtures/render-policy/uml-svg.json",
            "fixtures/render-metadata/uml-basic.json"));
  }

  private static void collectIfNotBackground(
      BufferedImage image, int x, int y, int background, List<String> bleed) {
    int rgb = image.getRGB(x, y) & 0xFFFFFF;
    if (rgb != background && bleed.size() < 16) {
      bleed.add(String.format("(%d,%d)=%06X", x, y, rgb));
    }
  }

  private static BufferedImage rasterize(String layout, String policy, String metadata)
      throws Exception {
    ObjectNode input = RenderTestSupport.fixtureInput(layout, policy, metadata);
    ((ObjectNode) input.at("/policy")).putObject("raster").put("scale", 1);

    PluginResult result =
        Main.executeForTesting(
            new String[] {"render"}, JsonSupport.objectMapper().writeValueAsString(input));
    JsonNode envelope = JsonSupport.objectMapper().readTree(result.stdout());
    assertThat(envelope.at("/status").asText()).describedAs(result.stdout()).isEqualTo("ok");

    for (JsonNode artifact : envelope.at("/data/artifacts")) {
      if ("png".equals(artifact.at("/artifact_kind").asText())) {
        byte[] png = Base64.getDecoder().decode(artifact.at("/content").asText());
        return ImageIO.read(new ByteArrayInputStream(png));
      }
    }
    throw new AssertionError("render produced no png artifact");
  }
}

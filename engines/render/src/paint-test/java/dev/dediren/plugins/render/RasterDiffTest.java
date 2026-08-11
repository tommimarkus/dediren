package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "dediren.render.paint.enabled", matches = "true")
class RasterDiffTest {

  @Test
  void ignoresDifferencesAtThePerChannelThreshold() {
    BufferedImage expected = image(2, 2, new Color(20, 30, 40, 50));
    BufferedImage actual = image(2, 2, new Color(28, 22, 48, 42));

    RasterDiff.Result result = RasterDiff.compare(expected, actual);

    assertThat(result.matches()).isTrue();
    assertThat(result.changedPixelCount()).isZero();
    assertThat(result.regions()).isEmpty();
  }

  @Test
  void reportsEveryPixelWithAnyChannelBeyondTheThreshold() {
    BufferedImage expected = image(3, 2, Color.BLACK);
    BufferedImage actual = image(3, 2, Color.BLACK);
    actual.setRGB(0, 0, new Color(9, 0, 0).getRGB());
    actual.setRGB(1, 0, new Color(0, 0, 9).getRGB());
    actual.setRGB(2, 1, new Color(0, 0, 0, 246).getRGB());

    RasterDiff.Result result = RasterDiff.compare(expected, actual);

    assertThat(result.matches()).isFalse();
    assertThat(result.changedPixelCount()).isEqualTo(3);
    assertThat(result.regions())
        .containsExactlyInAnyOrder(
            new RasterDiff.Region(0, 0, 2, 1, 2), new RasterDiff.Region(2, 1, 1, 1, 1));
  }

  @Test
  void rejectsDifferentDimensionsWithoutComparingPixels() {
    RasterDiff.Result result =
        RasterDiff.compare(image(2, 2, Color.WHITE), image(3, 2, Color.WHITE));

    assertThat(result.matches()).isFalse();
    assertThat(result.dimensionsMatch()).isFalse();
    assertThat(result.changedPixelCount()).isZero();
    assertThat(result.regions()).isEmpty();
  }

  @Test
  void writesActualMaskAndOverlayWhenTheAssertionFails(@TempDir Path output) throws Exception {
    BufferedImage expected = image(3, 3, Color.WHITE);
    BufferedImage actual = image(3, 3, Color.WHITE);
    actual.setRGB(1, 1, Color.BLACK.getRGB());

    assertThatThrownBy(() -> RasterDiff.assertMatches("bad/name", expected, actual, output))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("1 changed pixel")
        .hasMessageContaining("x=1, y=1, width=1, height=1, pixels=1");

    Path actualPath = output.resolve("bad-name-actual.png");
    Path maskPath = output.resolve("bad-name-mask.png");
    Path overlayPath = output.resolve("bad-name-overlay.png");
    assertThat(actualPath).isRegularFile();
    assertThat(maskPath).isRegularFile();
    assertThat(overlayPath).isRegularFile();
    assertThat(ImageIO.read(actualPath.toFile()).getRGB(1, 1)).isEqualTo(Color.BLACK.getRGB());
    assertThat(ImageIO.read(maskPath.toFile()).getRGB(1, 1)).isEqualTo(Color.MAGENTA.getRGB());
    assertThat(Files.size(overlayPath)).isPositive();
  }

  private static BufferedImage image(int width, int height, Color color) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        image.setRGB(x, y, color.getRGB());
      }
    }
    return image;
  }
}

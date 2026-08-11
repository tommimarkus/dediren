package dev.dediren.plugins.render;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageIO;

/** Deterministic ImageIO raster comparator used only by the opt-in paint-test source set. */
final class RasterDiff {

  static final int CHANNEL_THRESHOLD = 8;
  private static final int MASK_COLOR = Color.MAGENTA.getRGB();
  private static final int[] NEIGHBOR_X = {-1, 1, 0, 0};
  private static final int[] NEIGHBOR_Y = {0, 0, -1, 1};

  private RasterDiff() {}

  static Result compare(BufferedImage expected, BufferedImage actual) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(actual, "actual");

    boolean dimensionsMatch =
        expected.getWidth() == actual.getWidth() && expected.getHeight() == actual.getHeight();
    if (!dimensionsMatch) {
      return new Result(
          false,
          expected.getWidth(),
          expected.getHeight(),
          actual.getWidth(),
          actual.getHeight(),
          0,
          List.of(),
          new boolean[0][0]);
    }

    int width = expected.getWidth();
    int height = expected.getHeight();
    boolean[][] changed = new boolean[height][width];
    int changedPixelCount = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (pixelDiffers(expected.getRGB(x, y), actual.getRGB(x, y))) {
          changed[y][x] = true;
          changedPixelCount++;
        }
      }
    }

    return new Result(
        true, width, height, width, height, changedPixelCount, connectedRegions(changed), changed);
  }

  static void assertMatches(
      String artifactName, BufferedImage expected, BufferedImage actual, Path outputDirectory)
      throws IOException {
    Result result = compare(expected, actual);
    if (result.matches()) {
      return;
    }

    Files.createDirectories(outputDirectory);
    String basename = sanitize(artifactName);
    writePng(actual, outputDirectory.resolve(basename + "-actual.png"));
    writePng(mask(result), outputDirectory.resolve(basename + "-mask.png"));
    writePng(overlay(actual, result), outputDirectory.resolve(basename + "-overlay.png"));
    throw new AssertionError(result.describe());
  }

  private static boolean pixelDiffers(int expected, int actual) {
    return channelDifference(expected >>> 24, actual >>> 24) > CHANNEL_THRESHOLD
        || channelDifference(expected >>> 16, actual >>> 16) > CHANNEL_THRESHOLD
        || channelDifference(expected >>> 8, actual >>> 8) > CHANNEL_THRESHOLD
        || channelDifference(expected, actual) > CHANNEL_THRESHOLD;
  }

  private static int channelDifference(int left, int right) {
    return Math.abs((left & 0xff) - (right & 0xff));
  }

  private static List<Region> connectedRegions(boolean[][] changed) {
    if (changed.length == 0) {
      return List.of();
    }
    int height = changed.length;
    int width = changed[0].length;
    boolean[][] visited = new boolean[height][width];
    ArrayList<Region> regions = new ArrayList<>();
    ArrayDeque<Pixel> pending = new ArrayDeque<>();

    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (!changed[y][x] || visited[y][x]) {
          continue;
        }

        int minimumX = x;
        int maximumX = x;
        int minimumY = y;
        int maximumY = y;
        int count = 0;
        visited[y][x] = true;
        pending.add(new Pixel(x, y));

        while (!pending.isEmpty()) {
          Pixel pixel = pending.removeFirst();
          count++;
          minimumX = Math.min(minimumX, pixel.x());
          maximumX = Math.max(maximumX, pixel.x());
          minimumY = Math.min(minimumY, pixel.y());
          maximumY = Math.max(maximumY, pixel.y());

          for (int index = 0; index < NEIGHBOR_X.length; index++) {
            int neighborX = pixel.x() + NEIGHBOR_X[index];
            int neighborY = pixel.y() + NEIGHBOR_Y[index];
            if (neighborX >= 0
                && neighborX < width
                && neighborY >= 0
                && neighborY < height
                && changed[neighborY][neighborX]
                && !visited[neighborY][neighborX]) {
              visited[neighborY][neighborX] = true;
              pending.addLast(new Pixel(neighborX, neighborY));
            }
          }
        }

        regions.add(
            new Region(
                minimumX, minimumY, maximumX - minimumX + 1, maximumY - minimumY + 1, count));
      }
    }
    return List.copyOf(regions);
  }

  private static BufferedImage mask(Result result) {
    int width = Math.max(result.expectedWidth(), result.actualWidth());
    int height = Math.max(result.expectedHeight(), result.actualHeight());
    BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    if (!result.dimensionsMatch()) {
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          if (x >= Math.min(result.expectedWidth(), result.actualWidth())
              || y >= Math.min(result.expectedHeight(), result.actualHeight())) {
            mask.setRGB(x, y, MASK_COLOR);
          }
        }
      }
      return mask;
    }

    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (result.changed()[y][x]) {
          mask.setRGB(x, y, MASK_COLOR);
        }
      }
    }
    return mask;
  }

  private static BufferedImage overlay(BufferedImage actual, Result result) {
    int width = Math.max(result.expectedWidth(), result.actualWidth());
    int height = Math.max(result.expectedHeight(), result.actualHeight());
    BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < actual.getHeight(); y++) {
      for (int x = 0; x < actual.getWidth(); x++) {
        overlay.setRGB(x, y, actual.getRGB(x, y));
      }
    }
    BufferedImage mask = mask(result);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (mask.getRGB(x, y) == MASK_COLOR) {
          int source = overlay.getRGB(x, y);
          int red = (((source >>> 16) & 0xff) + 255) / 2;
          int green = ((source >>> 8) & 0xff) / 2;
          int blue = ((source & 0xff) + 255) / 2;
          overlay.setRGB(x, y, new Color(red, green, blue).getRGB());
        }
      }
    }
    return overlay;
  }

  private static String sanitize(String artifactName) {
    String sanitized = artifactName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
    return sanitized.isBlank() ? "raster-diff" : sanitized;
  }

  private static void writePng(BufferedImage image, Path path) throws IOException {
    if (!ImageIO.write(image, "png", path.toFile())) {
      throw new IOException("ImageIO has no PNG writer for " + path);
    }
  }

  record Region(int x, int y, int width, int height, int pixelCount) {
    @Override
    public String toString() {
      return "x=%d, y=%d, width=%d, height=%d, pixels=%d"
          .formatted(x, y, width, height, pixelCount);
    }
  }

  record Result(
      boolean dimensionsMatch,
      int expectedWidth,
      int expectedHeight,
      int actualWidth,
      int actualHeight,
      int changedPixelCount,
      List<Region> regions,
      boolean[][] changed) {

    boolean matches() {
      return dimensionsMatch && changedPixelCount == 0;
    }

    String describe() {
      if (!dimensionsMatch) {
        return "raster dimensions differ: expected %dx%d, actual %dx%d"
            .formatted(expectedWidth, expectedHeight, actualWidth, actualHeight);
      }
      String pixels = changedPixelCount == 1 ? "pixel" : "pixels";
      return "%d changed %s above per-channel threshold %d; regions: %s"
          .formatted(changedPixelCount, pixels, CHANNEL_THRESHOLD, regions);
    }
  }

  private record Pixel(int x, int y) {}
}

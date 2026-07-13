package dev.dediren.plugins.render.svg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link Geometry#labelBox} to the per-glyph metric rather than a flat per-character constant.
 *
 * <p>Edge-label boxes drive overlap avoidance and diagram bounds. They used to be sized with {@code
 * length * fontSize * 0.56} — the flat estimator {@link Svg}'s own comment condemns for
 * over-stating narrow glyphs by ~40-50% (issue #25) and under-measuring CJK by ~40% (issue #39).
 * Both defects were fixed for node labels and released; edge labels kept the flat copy, so the same
 * bug class survived where nothing measured it. These tests fail if the flat constant ever returns.
 */
class LabelBoxMeasurementTest {

  private static final double FONT_SIZE = 12.0;

  @Test
  void aCjkLabelIsMeasuredWiderThanAnAsciiLabelOfTheSameLength() {
    // Full-width glyphs occupy the em square; the flat constant charged them the same 0.56 em as
    // "iiii", so the box came out ~40% too narrow and labels could be placed overlapping.
    LabelBox cjk = Geometry.labelBox(0, 0, "start", "日本語訳", FONT_SIZE);
    LabelBox ascii = Geometry.labelBox(0, 0, "start", "iiii", FONT_SIZE);

    assertThat(cjk.width()).isGreaterThan(ascii.width() * 2.0);
  }

  @Test
  void narrowAndWideAsciiGlyphsAreNoLongerChargedTheSameWidth() {
    // The flat estimator made these identical, since it only counted characters.
    LabelBox narrow = Geometry.labelBox(0, 0, "start", "lliiii", FONT_SIZE);
    LabelBox wide = Geometry.labelBox(0, 0, "start", "WWMMmm", FONT_SIZE);

    assertThat(wide.width()).isGreaterThan(narrow.width() * 2.0);
  }

  @Test
  void theLabelBoxWidthIsTheEstimatedTextWidth() {
    String text = "sends payment request";

    LabelBox box = Geometry.labelBox(0, 0, "start", text, FONT_SIZE);

    assertThat(box.width()).isEqualTo(Svg.estimateTextWidth(text, FONT_SIZE));
  }

  @Test
  void anAbsentLabelHasNoWidth() {
    assertThat(Geometry.labelBox(0, 0, "start", null, FONT_SIZE).width()).isZero();
  }
}

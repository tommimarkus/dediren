package dev.dediren.plugins.render.svg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Svg#estimateTextWidth}, the per-glyph width metric behind label wrapping,
 * font fitting, overlap boxes, and the {@code textLength} width pin.
 *
 * <p>The expected widths below are NOT read back from the production advance table — they are the
 * natural rendered widths of each string computed independently from the published Adobe
 * Helvetica/Arial AFM advance widths (units of 1/1000 em), which Inter and Arial both track within
 * a few percent. This makes these specification tests, not echoes of the implementation: a
 * transcription error or mis-scaling of the table breaks them. See issue #39.
 */
class SvgTextWidthTest {

  // At fontSize 1000 the returned width equals the summed advance in AFM units (1/1000 em),
  // so an expected em-width of N corresponds directly to N AFM units.
  private static final double EM = 1000.0;

  @Test
  void estimatesNarrowGlyphStringNearItsNaturalAfmWidth() {
    // "Illinois" (issue #39 repro): I278 + l222 + l222 + i222 + n556 + o556 + i222 + s500 = 2778.
    assertThat(Svg.estimateTextWidth("Illinois", EM)).isCloseTo(2778.0, withinPercentage(4.0));

    // "Fulfillment Clerk" (issue #39 headline): F611 u556 l222 f278 i222 l222 l222 m833 e556 n556
    // t278 + space278 + C722 l222 e556 r333 k500 = 7167 AFM units ≈ 7.167 em.
    assertThat(Svg.estimateTextWidth("Fulfillment Clerk", EM))
        .isCloseTo(7167.0, withinPercentage(4.0));

    // "Auth": A667 + u556 + t278 + h556 = 2057.
    assertThat(Svg.estimateTextWidth("Auth", EM)).isCloseTo(2057.0, withinPercentage(4.0));
  }

  @Test
  void estimatesWideGlyphStringNearItsNaturalAfmWidth() {
    // Wide-glyph partition: W944 + W944 + M833 + M833 = 3554.
    assertThat(Svg.estimateTextWidth("WWMM", EM)).isCloseTo(3554.0, withinPercentage(4.0));
  }

  @Test
  void narrowGlyphLabelIsMuchNarrowerThanTheRetiredFlatMetric() {
    // The retired #25 metric was a flat 0.62 em/char. A narrow-glyph label must now measure
    // materially less than that flat width so its emitted textLength no longer over-stretches.
    String narrow = "Fulfillment Clerk";
    double flat = narrow.length() * 0.62 * EM; // 17 * 0.62 = 10.54 em
    assertThat(Svg.estimateTextWidth(narrow, EM)).isLessThan(flat * 0.75);
  }

  @Test
  void wideGlyphLabelIsWiderThanTheRetiredFlatMetric() {
    // The flat 0.62 em/char metric UNDER-stated wide glyphs; the per-glyph table must now measure
    // an all-wide string as wider than the flat width (the other half of the #39 correction).
    String wide = "WWMM";
    double flat = wide.length() * 0.62 * EM; // 4 * 0.62 = 2.48 em
    assertThat(Svg.estimateTextWidth(wide, EM)).isGreaterThan(flat);
  }

  @Test
  void scalesLinearlyWithFontSize() {
    // The metric is an em-fraction times fontSize: doubling the font size doubles the width.
    double atFourteen = Svg.estimateTextWidth("Fulfillment Clerk", 14.0);
    double atTwentyEight = Svg.estimateTextWidth("Fulfillment Clerk", 28.0);
    assertThat(atFourteen).isCloseTo(7.167 * 14.0, Offset.offset(0.3));
    assertThat(atTwentyEight).isCloseTo(2.0 * atFourteen, Offset.offset(0.0001));
  }

  @Test
  void narrowNonAsciiCodePointsFallBackToTheDefaultAdvance() {
    // Narrow non-ASCII (accented Latin, currency) is not full-width: each uses DEFAULT_ADVANCE_EM
    // (0.600 em). "é" (U+00E9) and "€" (U+20AC) → a two-glyph string is ~1.2 em wide.
    assertThat(Svg.estimateTextWidth("é€", EM)).isCloseTo(1200.0, Offset.offset(0.5));
  }

  @Test
  void fullWidthCjkGlyphsMeasureAtOneEm() {
    // CJK ideographs occupy the full em square (~1.0 em advance), not the 0.6 em non-ASCII
    // fallback,
    // which under-measured them by ~40% and let textLength squeeze the rendered label. "注文管理"
    // (four ideographs) is ~4.0 em wide, not 2.4.
    assertThat(Svg.estimateTextWidth("注文管理", EM)).isCloseTo(4000.0, withinPercentage(1.0));
  }

  @Test
  void kanaHangulAndFullWidthFormsMeasureAtOneEm() {
    assertThat(Svg.estimateTextWidth("あ", EM)).isCloseTo(1000.0, withinPercentage(1.0)); // Hiragana
    assertThat(Svg.estimateTextWidth("カ", EM)).isCloseTo(1000.0, withinPercentage(1.0)); // Katakana
    assertThat(Svg.estimateTextWidth("한", EM)).isCloseTo(1000.0, withinPercentage(1.0)); // Hangul
    assertThat(Svg.estimateTextWidth("Ａ", EM))
        .as("fullwidth Latin A (U+FF21) is wide, unlike ASCII A")
        .isCloseTo(1000.0, withinPercentage(1.0));
  }

  @Test
  void returnsZeroForNullOrEmpty() {
    assertThat(Svg.estimateTextWidth(null, 14.0)).isZero();
    assertThat(Svg.estimateTextWidth("", 14.0)).isZero();
  }
}

package dev.dediren.plugins.asciirender;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link GlyphSet}'s line-shape, arrow, and truncation glyph tables. */
class GlyphSetTest {

  private static final int N = 1;
  private static final int E = 2;
  private static final int S = 4;
  private static final int W = 8;

  @Test
  void unicodeRendersTheFullElevenShapeTable() {
    assertThat(GlyphSet.UNICODE.glyph(E | W)).isEqualTo('─');
    assertThat(GlyphSet.UNICODE.glyph(N | S)).isEqualTo('│');
    assertThat(GlyphSet.UNICODE.glyph(E | S)).isEqualTo('┌');
    assertThat(GlyphSet.UNICODE.glyph(W | S)).isEqualTo('┐');
    assertThat(GlyphSet.UNICODE.glyph(N | E)).isEqualTo('└');
    assertThat(GlyphSet.UNICODE.glyph(N | W)).isEqualTo('┘');
    assertThat(GlyphSet.UNICODE.glyph(N | S | E)).isEqualTo('├');
    assertThat(GlyphSet.UNICODE.glyph(N | S | W)).isEqualTo('┤');
    assertThat(GlyphSet.UNICODE.glyph(E | W | S)).isEqualTo('┬');
    assertThat(GlyphSet.UNICODE.glyph(E | W | N)).isEqualTo('┴');
    assertThat(GlyphSet.UNICODE.glyph(N | S | E | W)).isEqualTo('┼');
  }

  @Test
  void unicodeSingleBitMasksRenderAsStraightLines() {
    assertThat(GlyphSet.UNICODE.glyph(E)).isEqualTo('─');
    assertThat(GlyphSet.UNICODE.glyph(W)).isEqualTo('─');
    assertThat(GlyphSet.UNICODE.glyph(N)).isEqualTo('│');
    assertThat(GlyphSet.UNICODE.glyph(S)).isEqualTo('│');
  }

  @Test
  void asciiReducesToDashPipePlus() {
    assertThat(GlyphSet.ASCII.glyph(E | W)).isEqualTo('-');
    assertThat(GlyphSet.ASCII.glyph(E)).isEqualTo('-');
    assertThat(GlyphSet.ASCII.glyph(W)).isEqualTo('-');
    assertThat(GlyphSet.ASCII.glyph(N | S)).isEqualTo('|');
    assertThat(GlyphSet.ASCII.glyph(N)).isEqualTo('|');
    assertThat(GlyphSet.ASCII.glyph(S)).isEqualTo('|');
    assertThat(GlyphSet.ASCII.glyph(E | S)).isEqualTo('+');
    assertThat(GlyphSet.ASCII.glyph(N | S | E | W)).isEqualTo('+');
    assertThat(GlyphSet.ASCII.glyph(N | S | E)).isEqualTo('+');
    assertThat(GlyphSet.ASCII.glyph(E | W | S)).isEqualTo('+');
  }

  @Test
  void unicodeArrowsPointInTheDirectionTheEdgeEntersItsTarget() {
    assertThat(GlyphSet.UNICODE.arrow(N)).isEqualTo('▲');
    assertThat(GlyphSet.UNICODE.arrow(E)).isEqualTo('▶');
    assertThat(GlyphSet.UNICODE.arrow(S)).isEqualTo('▼');
    assertThat(GlyphSet.UNICODE.arrow(W)).isEqualTo('◀');
  }

  @Test
  void asciiArrowsUseCaretAngleBracketTable() {
    assertThat(GlyphSet.ASCII.arrow(N)).isEqualTo('^');
    assertThat(GlyphSet.ASCII.arrow(E)).isEqualTo('>');
    assertThat(GlyphSet.ASCII.arrow(S)).isEqualTo('v');
    assertThat(GlyphSet.ASCII.arrow(W)).isEqualTo('<');
  }

  @Test
  void truncationMarkersDifferByGlyphSet() {
    assertThat(GlyphSet.UNICODE.truncationMarker()).isEqualTo('…');
    assertThat(GlyphSet.ASCII.truncationMarker()).isEqualTo('~');
  }
}

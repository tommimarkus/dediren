package dev.dediren.plugins.asciirender;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link CharCanvas}'s line drawing, literals, and emission. */
class CharCanvasTest {

  @Test
  void hlineAndVlineCrossingYieldsFullMaskAtTheJunction() {
    CharCanvas canvas = new CharCanvas(5, 5);
    canvas.hline(2, 0, 4);
    canvas.vline(2, 0, 4);
    assertThat(canvas.emit(GlyphSet.UNICODE).split("\n")[2].charAt(2)).isEqualTo('┼');
  }

  @Test
  void hlineEndpointsGetOnlyTheInwardBit() {
    CharCanvas canvas = new CharCanvas(5, 1);
    canvas.hline(0, 0, 4);
    String line = canvas.emit(GlyphSet.UNICODE).split("\n")[0];
    // Left endpoint: only E bit -> renders as horizontal bar (single-bit renders as '-').
    assertThat(line.charAt(0)).isEqualTo('─');
    assertThat(line.charAt(4)).isEqualTo('─');
    assertThat(line.charAt(2)).isEqualTo('─');
  }

  @Test
  void hlineTopLeftCornerJoinsWithVlineDownward() {
    CharCanvas canvas = new CharCanvas(5, 5);
    canvas.hline(0, 0, 4);
    canvas.vline(0, 0, 4);
    // Top-left corner: E|S -> the box-drawing down-and-right corner.
    assertThat(canvas.emit(GlyphSet.UNICODE).split("\n")[0].charAt(0)).isEqualTo('┌');
  }

  @Test
  void literalsWinOverLines() {
    CharCanvas canvas = new CharCanvas(3, 1);
    canvas.literal(0, 1, 'X');
    canvas.hline(0, 0, 2);
    assertThat(canvas.emit(GlyphSet.UNICODE).split("\n")[0].charAt(1)).isEqualTo('X');
  }

  @Test
  void textWritesLiteralsLeftToRightAndClipsAtCanvasEdge() {
    CharCanvas canvas = new CharCanvas(3, 1);
    canvas.text(0, 1, "hello");
    String line = canvas.emit(GlyphSet.UNICODE);
    assertThat(line).startsWith(" h");
  }

  @Test
  void clearRectEmptiesCells() {
    CharCanvas canvas = new CharCanvas(3, 3);
    canvas.hline(1, 0, 2);
    canvas.clearRect(1, 0, 1, 2);
    String[] lines = canvas.emit(GlyphSet.UNICODE).split("\n", -1);
    assertThat(lines[1]).isEmpty();
  }

  @Test
  void emitRightTrimsEachLine() {
    CharCanvas canvas = new CharCanvas(5, 1);
    canvas.literal(0, 0, 'x');
    assertThat(canvas.emit(GlyphSet.UNICODE)).isEqualTo("x\n");
  }

  @Test
  void emitEndsWithSingleTrailingNewline() {
    CharCanvas canvas = new CharCanvas(2, 2);
    canvas.literal(1, 0, 'x');
    String out = canvas.emit(GlyphSet.UNICODE);
    assertThat(out).endsWith("x\n");
    assertThat(out).doesNotEndWith("\n\n");
  }

  @Test
  void emitRendersEmptyCellsAsSpaces() {
    CharCanvas canvas = new CharCanvas(3, 1);
    canvas.literal(0, 0, 'a');
    canvas.literal(0, 2, 'c');
    assertThat(canvas.emit(GlyphSet.UNICODE)).isEqualTo("a c\n");
  }
}

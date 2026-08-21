package dev.dediren.plugins.asciirender;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LabelWrap}'s greedy word-wrap. */
class LabelWrapTest {

  @Test
  void wrapsOnSingleSpacesGreedily() {
    List<String> lines = LabelWrap.wrap("alpha beta gamma", 11);
    assertThat(lines).containsExactly("alpha beta", "gamma");
  }

  @Test
  void fitsEverythingOnOneLineWhenShortEnough() {
    List<String> lines = LabelWrap.wrap("short label", 32);
    assertThat(lines).containsExactly("short label");
  }

  @Test
  void hardBreaksAWordLongerThanMaxCols() {
    List<String> lines = LabelWrap.wrap("supercalifragilistic", 6);
    assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(6));
    assertThat(String.join("", lines)).isEqualTo("supercalifragilistic");
  }

  @Test
  void nullLabelReturnsEmptyList() {
    assertThat(LabelWrap.wrap(null, 10)).isEmpty();
  }

  @Test
  void blankLabelReturnsEmptyList() {
    assertThat(LabelWrap.wrap("   ", 10)).isEmpty();
  }

  @Test
  void collapsesRunsOfWhitespaceToSingleSpaceBeforeWrapping() {
    List<String> lines = LabelWrap.wrap("alpha   beta\tgamma", 32);
    assertThat(lines).containsExactly("alpha beta gamma");
  }

  @Test
  void neverReturnsLinesLongerThanMaxCols() {
    List<String> lines =
        LabelWrap.wrap("this is a somewhat long label that needs wrapping across lines", 10);
    assertThat(lines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(10));
  }
}

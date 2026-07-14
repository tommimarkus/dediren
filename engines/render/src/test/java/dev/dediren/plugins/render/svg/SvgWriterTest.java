package dev.dediren.plugins.render.svg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins that {@link SvgWriter#attr} and {@link SvgWriter#text} scrub XML 1.0 invalid characters (via
 * {@link dev.dediren.engine.XmlText}) so a contract-valid label containing a C0 control character
 * can never yield an ill-formed SVG artifact.
 */
class SvgWriterTest {

  @Test
  void attrAndTextScrubXmlInvalidCharacters() {
    String svg = new SvgWriter().start("t").attr("l", "a\u0007").text("b\u0000").end().finish();

    assertThat(svg).contains("�");
    long replacementCount = svg.chars().filter(c -> c == '�').count();
    assertThat(replacementCount).isEqualTo(2);
    assertThat(svg).doesNotContain("\u0007").doesNotContain("\u0000");
  }

  @Test
  void textDefaultsNullToEmptyString() {
    String svg = new SvgWriter().start("t").text(null).end().finish();

    assertThat(svg).isEqualTo("<t></t>");
  }
}

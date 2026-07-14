package dev.dediren.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XmlTextTest {
  @Test
  void replacesXmlInvalidCharactersWithReplacementChar() {
    assertThat(XmlText.scrub("a\u0000b\u0007c")).isEqualTo("a�b�c");
    assertThat(XmlText.scrub("lone-high\uD800end")).isEqualTo("lone-high�end");
    assertThat(XmlText.scrub("ffff:\uFFFF")).isEqualTo("ffff:�");
    assertThat(XmlText.scrub("a\uFFFEb")).isEqualTo("a�b");
  }

  @Test
  void replacesLoneSurrogatesAtStringBoundaries() {
    // Pins the validAt short-circuits: a high surrogate as the true last character must not read
    // past the end (index + 1 == length), and a low surrogate at index 0 must not read before the
    // start (index == 0). Both are invalid and scrub to U+FFFD without throwing.
    assertThat(XmlText.scrub("end\uD800")).isEqualTo("end�");
    assertThat(XmlText.scrub("\uDC00start")).isEqualTo("�start");
  }

  @Test
  void preservesEverythingXmlCanRepresent() {
    String clean = "tab\t lf\n cr\r text ünïcode 😀";
    assertThat(XmlText.scrub(clean)).isSameAs(clean);
    assertThat(XmlText.scrub(null)).isNull();
  }
}

package dev.dediren.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XmlTextTest {
  @Test
  void replacesXmlInvalidCharactersWithReplacementChar() {
    assertThat(XmlText.scrub("a\u0000b\u0007c")).isEqualTo("a�b�c");
    assertThat(XmlText.scrub("lone-high\uD800end")).isEqualTo("lone-high�end");
    assertThat(XmlText.scrub("ffff:\uFFFF")).isEqualTo("ffff:�");
  }

  @Test
  void preservesEverythingXmlCanRepresent() {
    String clean = "tab\t lf\n cr\r text ünïcode 😀";
    assertThat(XmlText.scrub(clean)).isSameAs(clean);
    assertThat(XmlText.scrub(null)).isNull();
  }
}

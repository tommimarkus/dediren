package dev.dediren.plugins.umlxmi.build;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins that {@link XmiHelpers#attr} and {@link XmiHelpers#text} scrub XML 1.0 invalid characters
 * (via {@link dev.dediren.engine.XmlText}) before escaping, so a contract-valid label containing a
 * C0 control character (model.schema constrains ids, not labels) can never yield ill-formed XMI.
 */
class XmiHelpersTest {

  @Test
  void textScrubsXmlInvalidCharactersBeforeEscaping() {
    assertThat(XmiHelpers.text("a\u0007b")).isEqualTo("a�b");
  }

  @Test
  void attrScrubsXmlInvalidCharactersBeforeEscaping() {
    assertThat(XmiHelpers.attr("a\u0007b")).isEqualTo("a�b");
  }

  @Test
  void attrStillEscapesReservedCharactersAndDefaultsNullToEmpty() {
    assertThat(XmiHelpers.attr("a & b < c > d \" e")).isEqualTo("a &amp; b &lt; c &gt; d &quot; e");
    assertThat(XmiHelpers.attr(null)).isEqualTo("");
  }

  @Test
  void textStillEscapesReservedCharactersAndDefaultsNullToEmpty() {
    assertThat(XmiHelpers.text("a & b < c > d")).isEqualTo("a &amp; b &lt; c &gt; d");
    assertThat(XmiHelpers.text(null)).isEqualTo("");
  }
}

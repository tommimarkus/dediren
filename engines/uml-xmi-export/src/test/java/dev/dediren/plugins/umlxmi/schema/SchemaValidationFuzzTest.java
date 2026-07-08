package dev.dediren.plugins.umlxmi.schema;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.dediren.plugins.umlxmi.build.XmiValidationException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Fuzz-regression target for the local XMI DOM-validation path (audit finding F7).
 *
 * <p>Generated UML/XMI XML is validated in two stages; this target exercises only the first, {@link
 * SchemaValidation#validateXmiDocumentAndIds(String)} — the hardened, DOCTYPE-disallowing DOM parse
 * plus root/{@code xmi:version}/{@code xmi:id} checks — WITHOUT launching the external {@code
 * xmllint} subprocess. The invariant is that the only {@link Throwable} allowed to escape is {@link
 * XmiValidationException}: the method wraps every parser failure (malformed XML, forbidden DOCTYPE,
 * decoding errors) into that type, so a raw {@code SAXParseException}, {@link StackOverflowError},
 * {@link NullPointerException}, ... escaping is the finding we want.
 *
 * <p>With {@code JAZZER_FUZZ} unset these run in deterministic regression mode over the checked-in
 * seed corpus under {@code SchemaValidationFuzzTestInputs/<methodName>/}. To run the
 * coverage-guided exploratory fuzzer locally (needs the Jazzer native agent; ~60s):
 *
 * <pre>{@code
 * JAZZER_FUZZ=1 ./mvnw -pl engines/uml-xmi-export -am test \
 *     -Dtest=SchemaValidationFuzzTest -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 */
class SchemaValidationFuzzTest {
  private static final String XMI_NS = "http://www.omg.org/spec/XMI/20131001";

  @FuzzTest
  void validateOnlyThrowsXmiValidationException(byte[] data) {
    String xml = new String(data, StandardCharsets.UTF_8);
    try {
      SchemaValidation.validateXmiDocumentAndIds(xml);
    } catch (XmiValidationException expected) {
      // Documented validation failure — the only Throwable the invariant permits to escape.
    }
  }

  /**
   * Pins the hardening guarantee the fuzz invariant leans on: the DOCTYPE-disallowing parser's raw
   * failure must be translated into an {@link XmiValidationException} rather than leaking. Mirrors
   * the {@code doctype.xmi} seed in the regression corpus.
   */
  @Test
  void doctypeBearingInputIsRejectedAsXmlInvalid() {
    String content = "<!DOCTYPE xmi:XMI><xmi:XMI xmlns:xmi=\"" + XMI_NS + "\"><entry/></xmi:XMI>";

    assertThatThrownBy(() -> SchemaValidation.validateXmiDocumentAndIds(content))
        .isInstanceOf(XmiValidationException.class)
        .hasMessageContaining("not well-formed")
        .extracting(error -> ((XmiValidationException) error).code())
        .isEqualTo("DEDIREN_XMI_XML_INVALID");
  }
}

package dev.dediren.plugins.umlxmi.schema;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.plugins.umlxmi.build.XmiValidationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the published {@code DEDIREN_XMI_XML_INVALID} / {@code DEDIREN_XMI_SCHEMA_INVALID} failure
 * partitions of the XMI validation seam directly. The envelope mapping for {@link
 * XmiValidationException} is proven by the plugin-level {@code MainTest} error tests; these tests
 * pin which code each validation failure carries.
 */
class SchemaValidationTest {
  private static final String XMI_NS = "http://www.omg.org/spec/XMI/20131001";

  @TempDir Path tempDir;

  @Test
  void malformedXmlIsReportedAsXmlInvalid() {
    assertThatThrownBy(() -> SchemaValidation.validateXmiToAvailableStandards("<xmi:XMI", Map.of()))
        .isInstanceOf(XmiValidationException.class)
        .hasMessageContaining("not well-formed")
        .extracting(error -> ((XmiValidationException) error).code())
        .isEqualTo("DEDIREN_XMI_XML_INVALID");
  }

  @Test
  void nonXmiRootIsReportedAsSchemaInvalid() {
    String content = "<Model xmlns=\"http://www.omg.org/spec/UML/20161101\"/>";

    assertThatThrownBy(() -> SchemaValidation.validateXmiToAvailableStandards(content, Map.of()))
        .isInstanceOf(XmiValidationException.class)
        .hasMessageContaining("root must be xmi:XMI")
        .extracting(error -> ((XmiValidationException) error).code())
        .isEqualTo("DEDIREN_XMI_SCHEMA_INVALID");
  }

  @Test
  void xmiVersionAttributeIsReportedAsSchemaInvalid() {
    String content = "<xmi:XMI xmlns:xmi=\"" + XMI_NS + "\" xmi:version=\"20131001\"/>";

    assertThatThrownBy(() -> SchemaValidation.validateXmiToAvailableStandards(content, Map.of()))
        .isInstanceOf(XmiValidationException.class)
        .hasMessageContaining("xmi:version")
        .extracting(error -> ((XmiValidationException) error).code())
        .isEqualTo("DEDIREN_XMI_SCHEMA_INVALID");
  }

  @Test
  void documentRejectedBySuppliedXsdIsReportedAsSchemaInvalid() throws Exception {
    // A strict stand-in XMI.xsd whose XMI element allows no children: any child element in the
    // XMI namespace fails xmllint validation, which must surface as the schema-invalid code (the
    // tolerated unavailable-UML-schema case only covers UML-namespace declaration gaps).
    Path schemaPath = tempDir.resolve("XMI.xsd");
    Files.writeString(
        schemaPath,
        """
                <?xml version="1.0" encoding="UTF-8"?>
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                            targetNamespace="http://www.omg.org/spec/XMI/20131001"
                            xmlns="http://www.omg.org/spec/XMI/20131001"
                            elementFormDefault="qualified">
                  <xsd:element name="XMI">
                    <xsd:complexType/>
                  </xsd:element>
                  <xsd:element name="Documentation">
                    <xsd:complexType/>
                  </xsd:element>
                </xsd:schema>
                """,
        StandardCharsets.UTF_8);
    String content = "<xmi:XMI xmlns:xmi=\"" + XMI_NS + "\"><xmi:Documentation/></xmi:XMI>";

    assertThatThrownBy(
            () ->
                SchemaValidation.validateXmiToAvailableStandards(
                    content, Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString())))
        .isInstanceOf(XmiValidationException.class)
        .hasMessageContaining("does not validate against OMG XMI.xsd")
        .extracting(error -> ((XmiValidationException) error).code())
        .isEqualTo("DEDIREN_XMI_SCHEMA_INVALID");
  }
}

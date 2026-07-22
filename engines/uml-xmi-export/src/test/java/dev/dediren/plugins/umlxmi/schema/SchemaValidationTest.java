package dev.dediren.plugins.umlxmi.schema;

import static org.assertj.core.api.Assertions.assertThatCode;
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
  private static final String UML_NS = "http://www.omg.org/spec/UML/20161101";

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
  void aDocumentWhoseOnlyErrorsAreTheUnavailableUmlSchemaIsAccepted() throws Exception {
    // The success path of every real UML/XMI export. OMG publishes no UML XSD, so validating
    // against XMI.xsd alone can never resolve the uml:* children: the in-JVM validator reports
    // them and the export is rescued only by recognising that every reported error is the
    // missing-UML-schema gap. That recognition matches the JDK validator's literal wording, so it
    // is pinned here through the real validation path — if a Xerces message change silently broke
    // it, every UML export would flip to a false DEDIREN_XMI_SCHEMA_INVALID. A stand-in XMI.xsd
    // with a strict wildcard reproduces the exact condition offline, without downloading the real
    // OMG schema.
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
                    <xsd:complexType>
                      <xsd:sequence>
                        <xsd:any namespace="##other" processContents="strict"
                                 minOccurs="0" maxOccurs="unbounded"/>
                      </xsd:sequence>
                    </xsd:complexType>
                  </xsd:element>
                </xsd:schema>
                """,
        StandardCharsets.UTF_8);
    String content =
        "<xmi:XMI xmlns:xmi=\""
            + XMI_NS
            + "\"><uml:Model xmlns:uml=\""
            + UML_NS
            + "\" xmi:id=\"m1\"/></xmi:XMI>";

    assertThatCode(
            () ->
                SchemaValidation.validateXmiToAvailableStandards(
                    content, Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString())))
        .doesNotThrowAnyException();
  }

  @Test
  void documentRejectedBySuppliedXsdIsReportedAsSchemaInvalid() throws Exception {
    // A strict stand-in XMI.xsd whose XMI element allows no children: any child element in the
    // XMI namespace fails schema validation, which must surface as the schema-invalid code (the
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

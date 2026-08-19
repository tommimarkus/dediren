package dev.dediren.plugins.drawio.mx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.engine.EngineException;
import dev.dediren.plugins.drawio.DrawioLimits;
import dev.dediren.testsupport.TestSupport;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behaviour of the hardened mxfile reader: what it accepts, what it refuses, and how it says so. */
class MxReaderTest {

  // ---------------------------------------------------------------- happy paths

  @Test
  void readsAPlainSinglePage() throws Exception {
    MxFile file =
        MxReader.read(
            """
            <mxfile host="app.diagrams.net">
              <diagram id="p1" name="Page-1">
                <mxGraphModel dx="100" dy="200">
                  <root>
                    <mxCell id="0" />
                    <mxCell id="1" parent="0" />
                    <mxCell id="a" value="Alpha" style="rounded=1;fillColor=#dae8fc;" vertex="1" parent="1">
                      <mxGeometry x="10" y="20" width="120" height="60" as="geometry" />
                    </mxCell>
                  </root>
                </mxGraphModel>
              </diagram>
            </mxfile>
            """);

    assertThat(file.diagrams()).hasSize(1);
    MxDiagram page = file.diagrams().get(0);
    assertThat(page.id()).isEqualTo("p1");
    assertThat(page.name()).isEqualTo("Page-1");
    assertThat(page.compressed()).isFalse();
    assertThat(page.cells()).extracting(MxCell::id).containsExactly("0", "1", "a");

    MxCell alpha = page.cells().get(2);
    assertThat(alpha.value()).isEqualTo("Alpha");
    // The style string is kept verbatim: it is the mapper's only evidence of shape and layer.
    assertThat(alpha.style()).isEqualTo("rounded=1;fillColor=#dae8fc;");
    assertThat(alpha.vertex()).isTrue();
    assertThat(alpha.edge()).isFalse();
    assertThat(alpha.visible()).isTrue();
    assertThat(alpha.parent()).isEqualTo("1");
    assertThat(alpha.geometry())
        .isEqualTo(new MxGeometry(10, 20, 120, 60, false, null, null, List.of()));
  }

  @Test
  void readsACompressedSinglePage() throws Exception {
    String inner =
        """
        <mxGraphModel dx="100" dy="200">
          <root>
            <mxCell id="0" />
            <mxCell id="1" parent="0" />
            <mxCell id="a" value="Order + Invoice" style="rounded=0;" vertex="1" parent="1">
              <mxGeometry x="0" y="0" width="80" height="40" as="geometry" />
            </mxCell>
          </root>
        </mxGraphModel>
        """;

    MxFile file =
        MxReader.read(
            "<mxfile><diagram id=\"p1\" name=\"Packed\">"
                + MxCompression.encode(inner)
                + "</diagram></mxfile>");

    MxDiagram page = file.diagrams().get(0);
    assertThat(page.compressed()).isTrue();
    assertThat(page.cells()).extracting(MxCell::id).containsExactly("0", "1", "a");
    // A literal '+' survives: the decoder is decodeURIComponent, not URLDecoder.
    assertThat(page.cells().get(2).value()).isEqualTo("Order + Invoice");
  }

  @Test
  void detectsCompressionStructurallyAndIgnoresTheCompressedAttribute() throws Exception {
    // Recent draw.io omits `compressed=` entirely, and nothing stops a file from carrying a wrong
    // one. Both pages below lie about their own encoding; both must still read correctly.
    String packed = MxCompression.encode("<mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel>");
    MxFile file =
        MxReader.read(
            "<mxfile>"
                + "<diagram id=\"lying-plain\" name=\"Plain\" compressed=\"true\">"
                + "<mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel>"
                + "</diagram>"
                + "<diagram id=\"lying-packed\" name=\"Packed\" compressed=\"false\">"
                + packed
                + "</diagram>"
                + "</mxfile>");

    assertThat(file.diagrams()).extracting(MxDiagram::compressed).containsExactly(false, true);
    assertThat(file.diagrams()).allSatisfy(page -> assertThat(page.cells()).hasSize(1));
  }

  @Test
  void readsMultiplePagesInDocumentOrderMixingBothEncodings() throws Exception {
    String packed = MxCompression.encode("<mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel>");
    MxFile file =
        MxReader.read(
            "<mxfile>"
                + "<diagram id=\"a\" name=\"First\"><mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel></diagram>"
                + "<diagram id=\"b\" name=\"Second\">"
                + packed
                + "</diagram>"
                + "<diagram id=\"c\" name=\"Third\"><mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel></diagram>"
                + "</mxfile>");

    assertThat(file.diagrams()).extracting(MxDiagram::name).containsExactly("First", "Second", "Third");
  }

  @Test
  void readsABareMxGraphModelAsOneUnnamedPage() throws Exception {
    // Extras > Edit Diagram yields exactly this, so pasted editor content has to work.
    MxFile file =
        MxReader.read(
            """
            <mxGraphModel dx="1" dy="2">
              <root>
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
              </root>
            </mxGraphModel>
            """);

    assertThat(file.diagrams()).hasSize(1);
    MxDiagram page = file.diagrams().get(0);
    assertThat(page.id()).isNull();
    assertThat(page.name()).isNull();
    assertThat(page.compressed()).isFalse();
    assertThat(page.cells()).extracting(MxCell::id).containsExactly("0", "1");
  }

  @Test
  void readsObjectWrappedCellsWithTheirCustomAttributes() throws Exception {
    MxFile file =
        MxReader.read(
            """
            <mxGraphModel>
              <root>
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <object id="svc" label="Ledger" dedirenType="application-component" dedirenId="svc.ledger" tooltip="notes">
                  <mxCell style="rounded=0;" vertex="1" parent="1">
                    <mxGeometry x="1" y="2" width="3" height="4" as="geometry" />
                  </mxCell>
                </object>
              </root>
            </mxGraphModel>
            """);

    MxCell cell = file.diagrams().get(0).cells().get(2);
    // Identity and label move to the wrapper in this format; the reader resolves that once, here,
    // so no consumer can forget and silently lose the label or the edge endpoint.
    assertThat(cell.id()).isEqualTo("svc");
    assertThat(cell.value()).isEqualTo("Ledger");
    assertThat(cell.style()).isEqualTo("rounded=0;");
    assertThat(cell.vertex()).isTrue();
    assertThat(cell.parent()).isEqualTo("1");
    assertThat(cell.wrapped()).isTrue();
    assertThat(cell.object().elementName()).isEqualTo("object");
    // The wrapper's attribute map stays complete and unfiltered, id and label included.
    assertThat(cell.object().attributes())
        .containsExactly(
            org.assertj.core.api.Assertions.entry("id", "svc"),
            org.assertj.core.api.Assertions.entry("label", "Ledger"),
            org.assertj.core.api.Assertions.entry("dedirenType", "application-component"),
            org.assertj.core.api.Assertions.entry("dedirenId", "svc.ledger"),
            org.assertj.core.api.Assertions.entry("tooltip", "notes"));
  }

  @Test
  void readsUserObjectWrappedCells() throws Exception {
    // draw.io writes <UserObject> rather than <object> for some cells; the construct is the same,
    // and dropping it would silently discard whole shapes.
    MxFile file =
        MxReader.read(
            """
            <mxGraphModel>
              <root>
                <mxCell id="0" />
                <UserObject id="u" label="Linked" link="https://example.invalid/">
                  <mxCell style="rounded=1;" vertex="1" parent="0" />
                </UserObject>
              </root>
            </mxGraphModel>
            """);

    MxCell cell = file.diagrams().get(0).cells().get(1);
    assertThat(cell.id()).isEqualTo("u");
    assertThat(cell.value()).isEqualTo("Linked");
    assertThat(cell.object().elementName()).isEqualTo("UserObject");
    assertThat(cell.object().attributes()).containsEntry("link", "https://example.invalid/");
  }

  @Test
  void readsEdgesWithWaypointsAndFloatingEndpoints() throws Exception {
    MxFile file =
        MxReader.read(
            """
            <mxGraphModel>
              <root>
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="e" style="edgeStyle=orthogonalEdgeStyle;" edge="1" parent="1" source="a" target="b">
                  <mxGeometry relative="1" as="geometry">
                    <mxPoint x="5" y="6" as="sourcePoint" />
                    <mxPoint x="7" y="8" as="targetPoint" />
                    <Array as="points">
                      <mxPoint x="10" y="20" />
                      <mxPoint x="30" y="40" />
                    </Array>
                  </mxGeometry>
                </mxCell>
              </root>
            </mxGraphModel>
            """);

    MxCell edge = file.diagrams().get(0).cells().get(2);
    assertThat(edge.edge()).isTrue();
    assertThat(edge.source()).isEqualTo("a");
    assertThat(edge.target()).isEqualTo("b");
    assertThat(edge.geometry().relative()).isTrue();
    assertThat(edge.geometry().sourcePoint()).isEqualTo(new MxPoint(5, 6));
    assertThat(edge.geometry().targetPoint()).isEqualTo(new MxPoint(7, 8));
    assertThat(edge.geometry().points())
        .containsExactly(new MxPoint(10, 20), new MxPoint(30, 40));
  }

  @Test
  void readsNestedContainersAsAParentChain() throws Exception {
    // Containment in this format is the `parent` attribute, not XML nesting: every cell is a flat
    // sibling under <root>.
    MxFile file =
        MxReader.read(
            """
            <mxGraphModel>
              <root>
                <mxCell id="0" />
                <mxCell id="1" parent="0" />
                <mxCell id="outer" value="Outer" vertex="1" parent="1" />
                <mxCell id="middle" value="Middle" vertex="1" parent="outer" />
                <mxCell id="inner" value="Inner" vertex="1" parent="middle" />
              </root>
            </mxGraphModel>
            """);

    assertThat(file.diagrams().get(0).cells())
        .extracting(MxCell::id, MxCell::parent)
        .containsExactly(
            org.assertj.core.api.Assertions.tuple("0", null),
            org.assertj.core.api.Assertions.tuple("1", "0"),
            org.assertj.core.api.Assertions.tuple("outer", "1"),
            org.assertj.core.api.Assertions.tuple("middle", "outer"),
            org.assertj.core.api.Assertions.tuple("inner", "middle"));
  }

  @Test
  void readsTheHandAuthoredTwoPageFixture() throws Exception {
    MxFile file = MxReader.read(fixture("fixtures/drawio/reader-plain-two-page.drawio"));

    assertThat(file.diagrams()).extracting(MxDiagram::name).containsExactly("Architecture", "Notes");

    MxDiagram architecture = file.diagrams().get(0);
    assertThat(architecture.cells())
        .extracting(MxCell::id)
        .containsExactly("0", "1", "zone", "gateway", "ledger", "calls", "floating", "hidden-note");
    assertThat(cell(architecture, "ledger").object().attributes())
        .containsEntry("dedirenType", "application-component");
    assertThat(cell(architecture, "calls").geometry().points()).hasSize(3);
    assertThat(cell(architecture, "floating").geometry().sourcePoint())
        .isEqualTo(new MxPoint(80, 360));
    assertThat(cell(architecture, "hidden-note").visible()).isFalse();

    // Built-in entity references still resolve: SUPPORT_DTD=false suppresses only DTD-declared
    // ones, and draw.io labels are full of &amp; and &lt;br&gt;.
    assertThat(cell(file.diagrams().get(1), "caption").value())
        .isEqualTo("Balance & settlement <br>second line");
  }

  // ---------------------------------------------------------------- document shape

  @Test
  void refusesADocumentWhoseRootIsNeitherMxfileNorMxGraphModel() {
    assertRefused(
        () -> MxReader.read("<svg xmlns=\"http://www.w3.org/2000/svg\"><g/></svg>"),
        DiagnosticCode.DRAWIO_UNSUPPORTED_DOCUMENT);
  }

  @Test
  void refusesAnMxfileWithNoDiagram() {
    assertRefused(
        () -> MxReader.read("<mxfile host=\"app.diagrams.net\"></mxfile>"),
        DiagnosticCode.DRAWIO_UNSUPPORTED_DOCUMENT);
  }

  @Test
  void refusesEmptyInputAsSyntaxRatherThanAnUnsupportedDocument() {
    // There is no root element to judge, so this is malformed XML, not an unrecognized format.
    assertRefused(() -> MxReader.read("   "), DiagnosticCode.DRAWIO_SYNTAX_INVALID);
  }

  @Test
  void refusesADiagramHoldingAnUnexpectedChildElement() {
    assertRefused(
        () -> MxReader.read("<mxfile><diagram id=\"p\" name=\"P\"><svg/></diagram></mxfile>"),
        DiagnosticCode.DRAWIO_SYNTAX_INVALID);
  }

  @Test
  void refusesADecompressedPayloadThatIsNotAnMxGraphModel() {
    String packed = MxCompression.encode("<svg><g/></svg>");
    Diagnostic refusal =
        refusalOf(
            () -> MxReader.read("<mxfile><diagram id=\"p\" name=\"P\">" + packed + "</diagram></mxfile>"));

    assertThat(refusal.code()).isEqualTo(DiagnosticCode.DRAWIO_SYNTAX_INVALID.code());
    assertThat(refusal.path()).startsWith("page 1 (P, decompressed), ");
  }

  // ---------------------------------------------------------------- diagnostic paths

  @Test
  void reportsAPlainPageFailureAgainstTheOuterDocumentCoordinates() {
    Diagnostic refusal =
        refusalOf(
            () ->
                MxReader.read(
                    "<mxfile>\n"
                        + "  <diagram id=\"p1\" name=\"First\"><mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel></diagram>\n"
                        + "  <diagram id=\"p2\" name=\"Architecture\">\n"
                        + "    <mxGraphModel><root><mxCell id=\"0\" </root></mxGraphModel>\n"
                        + "  </diagram>\n"
                        + "</mxfile>\n"));

    assertThat(refusal.code()).isEqualTo(DiagnosticCode.DRAWIO_SYNTAX_INVALID.code());
    assertThat(refusal.path()).matches("page 2 \\(Architecture\\), line \\d+, column \\d+");
    // The break is on the fourth line of the file the user is holding.
    assertThat(refusal.path()).startsWith("page 2 (Architecture), line 4,");
  }

  @Test
  void reportsADecompressedPageFailureAgainstThePayloadCoordinates() {
    // The distinction is the whole point: the outer file puts this page on line 3, while the break
    // is on line 4 of the decoded payload. A reader that reported the outer line would send a user
    // chasing a line that is not where the fault is.
    String inner =
        "<mxGraphModel>\n"
            + "  <root>\n"
            + "    <mxCell id=\"0\" />\n"
            + "    <mxCell id=\"1\" parent=\"0\" <\n"
            + "  </root>\n"
            + "</mxGraphModel>\n";
    String outer =
        "<mxfile>\n"
            + "  <diagram id=\"p1\" name=\"First\"><mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel></diagram>\n"
            + "  <diagram id=\"p2\" name=\"Architecture\">"
            + MxCompression.encode(inner)
            + "</diagram>\n"
            + "</mxfile>\n";

    Diagnostic refusal = refusalOf(() -> MxReader.read(outer));

    assertThat(refusal.code()).isEqualTo(DiagnosticCode.DRAWIO_SYNTAX_INVALID.code());
    assertThat(refusal.path())
        .matches("page 2 \\(Architecture, decompressed\\), line \\d+, column \\d+");
    assertThat(refusal.path()).startsWith("page 2 (Architecture, decompressed), line 4,");
  }

  @Test
  void omitsTheNameFromThePathWhenThePageHasNone() {
    Diagnostic refusal =
        refusalOf(() -> MxReader.read("<mxfile><diagram id=\"p\"><mxGraphModel><root <</root></mxGraphModel></diagram></mxfile>"));

    assertThat(refusal.path()).matches("page 1, line \\d+, column \\d+");
  }

  @Test
  void producesNoPartialFileWhenALaterPageFails() {
    // Atomic, like the DOT importer: the caller gets a document or an exception, never a prefix.
    assertThatThrownBy(
            () ->
                MxReader.read(
                    "<mxfile>"
                        + "<diagram id=\"good\" name=\"Good\"><mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel></diagram>"
                        + "<diagram id=\"bad\" name=\"Bad\"><mxGraphModel><root <</root></mxGraphModel></diagram>"
                        + "</mxfile>"))
        .isInstanceOf(EngineException.class);
  }

  // ---------------------------------------------------------------- XML hardening

  @Test
  void refusesADoctypeThatReferencesAnUndeclaredEntity() {
    // SecureXml's javadoc is explicit that SUPPORT_DTD=false does NOT reject a DOCTYPE on the JDK
    // StAX implementation: the declaration is parsed and reported as an inert DTD event. Refusal
    // happens one step later, when the document *references* an entity the DTD would have declared.
    // That is the behaviour pinned here, and by acceptsADoctypeThatReferencesNothing below.
    assertRefused(
        () ->
            MxReader.read(
                "<!DOCTYPE mxGraphModel [<!ENTITY pwn SYSTEM \"file:///etc/passwd\">]>\n"
                    + "<mxGraphModel><root><mxCell id=\"0\">&pwn;</mxCell></root></mxGraphModel>"),
        DiagnosticCode.DRAWIO_SYNTAX_INVALID);
  }

  @Test
  void acceptsADoctypeThatReferencesNothing() throws Exception {
    // Deliberate, and the direct consequence of the javadoc note above: a bare DOCTYPE is inert,
    // and this reader does not add a DTD-event check of its own. If that posture should change,
    // it changes here and in docs/threat-model.md, not by accident.
    MxFile file =
        MxReader.read(
            "<!DOCTYPE mxGraphModel>\n"
                + "<mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel>");

    assertThat(file.diagrams().get(0).cells()).extracting(MxCell::id).containsExactly("0");
  }

  @Test
  void refusesAnEntityReferenceInsideACompressedPageExactlyAsInAPlainOne() {
    // The decompressed payload is a second, fully independent attacker-controlled XML document.
    // Parsing it with a default factory would defeat every control the outer parse applies, so the
    // same payload must be refused with the same code whichever way it arrives.
    String payload =
        "<!DOCTYPE mxGraphModel [<!ENTITY pwn \"PWNED\">]>\n"
            + "<mxGraphModel><root><mxCell id=\"0\">&pwn;</mxCell></root></mxGraphModel>";

    Diagnostic plain = refusalOf(() -> MxReader.read(payload));
    Diagnostic compressed =
        refusalOf(
            () ->
                MxReader.read(
                    "<mxfile><diagram id=\"p\" name=\"P\">"
                        + MxCompression.encode(payload)
                        + "</diagram></mxfile>"));

    assertThat(plain.code()).isEqualTo(DiagnosticCode.DRAWIO_SYNTAX_INVALID.code());
    assertThat(compressed.code()).isEqualTo(plain.code());
    assertThat(compressed.path()).startsWith("page 1 (P, decompressed), ");
  }

  @Test
  void aDefaultFactoryWouldHaveAcceptedThatSameInnerPayload() throws Exception {
    // The mutation proof for the test above. Without it, "the compressed XXE is refused" could pass
    // for a reason that has nothing to do with the inner factory. A default XMLInputFactory
    // resolves the entity and hands back the payload; the hardened one refuses it. So the refusal
    // above is caused by the inner parse using SecureXml, and swapping in a default factory there
    // would be observable.
    String payload =
        "<!DOCTYPE mxGraphModel [<!ENTITY pwn \"PWNED\">]>\n"
            + "<mxGraphModel><root><mxCell id=\"0\">&pwn;</mxCell></root></mxGraphModel>";

    assertThat(textOf(XMLInputFactory.newFactory(), payload)).contains("PWNED");
    assertThatThrownBy(() -> textOf(dev.dediren.engine.SecureXml.inputFactory(), payload))
        .isInstanceOf(XMLStreamException.class);
  }

  @Test
  void doesNotReadALocalFileThroughAnEntityInsideACompressedPage(@TempDir Path tempDir)
      throws Exception {
    // The real XXE, not a stand-in: a default parser exfiltrates this file's contents into the
    // document. Asserting the secret never appears anywhere is what makes the test about
    // exfiltration rather than about an exception type.
    Path secret = tempDir.resolve("secret.txt");
    Files.writeString(secret, "TOP-SECRET-LEDGER-KEY", StandardCharsets.UTF_8);
    String payload =
        "<!DOCTYPE mxGraphModel [<!ENTITY pwn SYSTEM \""
            + secret.toUri()
            + "\">]>\n"
            + "<mxGraphModel><root><mxCell id=\"0\">&pwn;</mxCell></root></mxGraphModel>";

    Diagnostic refusal =
        refusalOf(
            () ->
                MxReader.read(
                    "<mxfile><diagram id=\"p\" name=\"P\">"
                        + MxCompression.encode(payload)
                        + "</diagram></mxfile>"));

    assertThat(refusal.code()).isEqualTo(DiagnosticCode.DRAWIO_SYNTAX_INVALID.code());
    assertThat(refusal.message()).doesNotContain("TOP-SECRET-LEDGER-KEY");
    assertThat(refusal.path()).doesNotContain("TOP-SECRET-LEDGER-KEY");
  }

  // ---------------------------------------------------------------- ceilings

  @Test
  void acceptsExactlyMaxPages() throws Exception {
    assertThat(MxReader.read(pages(DrawioLimits.MAX_PAGES, "")).diagrams())
        .hasSize(DrawioLimits.MAX_PAGES);
  }

  @Test
  void refusesThePageAboveTheCeilingWhileStillParsing() {
    // The trailing garbage is the mid-parse proof: it is a syntax error that a reader materialising
    // the whole document before counting would have hit first. Reporting the page ceiling instead
    // is only possible if the count aborts the walk at the 257th <diagram>.
    assertRefused(
        () -> MxReader.read(pages(DrawioLimits.MAX_PAGES + 1, "<this is not xml")),
        DiagnosticCode.DRAWIO_PAGE_LIMIT_EXCEEDED);
  }

  @Test
  void acceptsExactlyMaxCells() throws Exception {
    MxFile file = MxReader.read(cells(DrawioLimits.MAX_CELLS, ""));

    assertThat(file.diagrams().get(0).cells()).hasSize(DrawioLimits.MAX_CELLS);
  }

  @Test
  void refusesTheCellAboveTheCeilingWhileStillParsing() {
    assertRefused(
        () -> MxReader.read(cells(DrawioLimits.MAX_CELLS + 1, "<this is not xml")),
        DiagnosticCode.DRAWIO_CELL_LIMIT_EXCEEDED);
  }

  @Test
  void countsCellsAcrossEveryPageRatherThanPerPage() {
    // Otherwise a 256-page file would buy 256 times the ceiling, exactly the hole the decompression
    // budget is a running total to avoid.
    StringBuilder document = new StringBuilder("<mxfile>");
    int perPage = (DrawioLimits.MAX_CELLS / 2) + 1;
    for (int page = 0; page < 2; page++) {
      document.append("<diagram id=\"p").append(page).append("\" name=\"P\"><mxGraphModel><root>");
      for (int cell = 0; cell < perPage; cell++) {
        document.append("<mxCell id=\"p").append(page).append('c').append(cell).append("\"/>");
      }
      document.append("</root></mxGraphModel></diagram>");
    }
    document.append("</mxfile>");

    assertRefused(
        () -> MxReader.read(document.toString()), DiagnosticCode.DRAWIO_CELL_LIMIT_EXCEEDED);
  }

  @Test
  void acceptsAParentChainExactlyAtTheNestingCeiling() throws Exception {
    assertThat(MxReader.read(parentChain(DrawioLimits.MAX_NESTING)).diagrams().get(0).cells())
        .hasSize(DrawioLimits.MAX_NESTING + 1);
  }

  @Test
  void refusesAParentChainOneLevelAboveTheCeiling() {
    assertRefused(
        () -> MxReader.read(parentChain(DrawioLimits.MAX_NESTING + 1)),
        DiagnosticCode.DRAWIO_NESTING_LIMIT_EXCEEDED);
  }

  @Test
  void acceptsATokenExactlyAtTheCeiling() throws Exception {
    String atCeiling = "a".repeat(DrawioLimits.MAX_TOKEN_BYTES);

    MxFile file =
        MxReader.read(
            "<mxGraphModel><root><mxCell id=\"0\" value=\"" + atCeiling + "\"/></root></mxGraphModel>");

    assertThat(file.diagrams().get(0).cells().get(0).value()).hasSize(DrawioLimits.MAX_TOKEN_BYTES);
  }

  @Test
  void refusesALabelAboveTheTokenCeiling() {
    String overCeiling = "a".repeat(DrawioLimits.MAX_TOKEN_BYTES + 1);

    assertRefused(
        () ->
            MxReader.read(
                "<mxGraphModel><root><mxCell id=\"0\" value=\"" + overCeiling + "\"/></root></mxGraphModel>"),
        DiagnosticCode.DRAWIO_TOKEN_LIMIT_EXCEEDED);
  }

  @Test
  void refusesAStyleAboveTheTokenCeiling() {
    // Not just the label: style is the longest attribute in real files and the obvious carrier.
    String overCeiling = "shape=x;".repeat((DrawioLimits.MAX_TOKEN_BYTES / 8) + 1);

    assertRefused(
        () ->
            MxReader.read(
                "<mxGraphModel><root><mxCell id=\"0\" style=\"" + overCeiling + "\"/></root></mxGraphModel>"),
        DiagnosticCode.DRAWIO_TOKEN_LIMIT_EXCEEDED);
  }

  @Test
  void refusesAnOverCeilingAttributeOnAnElementItSkipsOver() {
    // Regression. The reader skips elements it does not model, and the token check used to live at
    // the call sites that recognize an element, so an over-ceiling attribute was refused under
    // <mxfile> and accepted under <mxCell>. A ceiling that depends on where the payload sits is not
    // a ceiling.
    String overCeiling = "a".repeat(DrawioLimits.MAX_TOKEN_BYTES + 1);

    assertRefused(
        () ->
            MxReader.read(
                "<mxGraphModel><root><mxCell id=\"0\"><whatever note=\""
                    + overCeiling
                    + "\"/></mxCell></root></mxGraphModel>"),
        DiagnosticCode.DRAWIO_TOKEN_LIMIT_EXCEEDED);
  }

  @Test
  void doesNotMistakeALabelOffsetForAWaypoint() throws Exception {
    // <mxPoint as="offset"> positions a label; treating it as a bend would invent a route the file
    // does not describe. Only <Array as="points"> holds waypoints.
    MxFile file =
        MxReader.read(
            """
            <mxGraphModel>
              <root>
                <mxCell id="e" edge="1">
                  <mxGeometry relative="1" as="geometry">
                    <mxPoint x="99" y="99" as="offset" />
                  </mxGeometry>
                </mxCell>
              </root>
            </mxGraphModel>
            """);

    assertThat(file.diagrams().get(0).cells().get(0).geometry().points()).isEmpty();
  }

  @Test
  void measuresTheTokenCeilingInUtf8BytesNotCharacters() {
    // Two bytes each, so half the ceiling in characters is exactly the ceiling in bytes; one more
    // character is over it. A char-counting check would accept this.
    String overCeiling = "é".repeat((DrawioLimits.MAX_TOKEN_BYTES / 2) + 1);

    assertRefused(
        () ->
            MxReader.read(
                "<mxGraphModel><root><mxCell id=\"0\" value=\"" + overCeiling + "\"/></root></mxGraphModel>"),
        DiagnosticCode.DRAWIO_TOKEN_LIMIT_EXCEEDED);
  }

  // ---------------------------------------------------------------- parent chains

  @Test
  void terminatesAndReportsOnACyclicParentChain() {
    Diagnostic refusal =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                refusalOf(
                    () ->
                        MxReader.read(
                            """
                            <mxGraphModel>
                              <root>
                                <mxCell id="a" parent="c" />
                                <mxCell id="b" parent="a" />
                                <mxCell id="c" parent="b" />
                              </root>
                            </mxGraphModel>
                            """)));

    assertThat(refusal.code()).isEqualTo(DiagnosticCode.DRAWIO_SYNTAX_INVALID.code());
    assertThat(refusal.message()).contains("cycle");
  }

  @Test
  void terminatesAndReportsOnACellThatIsItsOwnParent() {
    Diagnostic refusal =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                refusalOf(
                    () ->
                        MxReader.read(
                            "<mxGraphModel><root><mxCell id=\"a\" parent=\"a\"/></root></mxGraphModel>")));

    assertThat(refusal.code()).isEqualTo(DiagnosticCode.DRAWIO_SYNTAX_INVALID.code());
  }

  @Test
  void refusesAParentNamingNoCellOnThatPage() {
    Diagnostic refusal =
        refusalOf(
            () ->
                MxReader.read(
                    "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"a\" parent=\"ghost\"/></root></mxGraphModel>"));

    assertThat(refusal.code()).isEqualTo(DiagnosticCode.DRAWIO_SYNTAX_INVALID.code());
    assertThat(refusal.message()).contains("ghost");
  }

  @Test
  void scopesParentResolutionToItsOwnPage() {
    // Pages are independent graphs. A parent that only exists on another page is dangling, not a
    // cross-page reference.
    assertRefused(
        () ->
            MxReader.read(
                "<mxfile>"
                    + "<diagram id=\"a\" name=\"A\"><mxGraphModel><root><mxCell id=\"host\"/></root></mxGraphModel></diagram>"
                    + "<diagram id=\"b\" name=\"B\"><mxGraphModel><root><mxCell id=\"x\" parent=\"host\"/></root></mxGraphModel></diagram>"
                    + "</mxfile>"),
        DiagnosticCode.DRAWIO_SYNTAX_INVALID);
  }

  @Test
  void refusesDuplicateCellIdsOnOnePage() {
    // A repeated id makes the parent graph ambiguous, so it cannot be resolved rather than guessed.
    assertRefused(
        () ->
            MxReader.read(
                "<mxGraphModel><root><mxCell id=\"a\"/><mxCell id=\"a\"/></root></mxGraphModel>"),
        DiagnosticCode.DRAWIO_SYNTAX_INVALID);
  }

  @Test
  void refusesACellWithNoIdentity() {
    assertRefused(
        () -> MxReader.read("<mxGraphModel><root><mxCell vertex=\"1\"/></root></mxGraphModel>"),
        DiagnosticCode.DRAWIO_SYNTAX_INVALID);
  }

  @Test
  void refusesNonNumericGeometry() {
    assertRefused(
        () ->
            MxReader.read(
                "<mxGraphModel><root><mxCell id=\"a\"><mxGeometry x=\"left\" as=\"geometry\"/></mxCell></root></mxGraphModel>"),
        DiagnosticCode.DRAWIO_SYNTAX_INVALID);
  }

  // ---------------------------------------------------------------- helpers

  private static MxCell cell(MxDiagram page, String id) {
    return page.cells().stream()
        .filter(candidate -> candidate.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no cell " + id));
  }

  private static String fixture(String relativePath) throws IOException {
    return Files.readString(TestSupport.workspaceRoot().resolve(relativePath));
  }

  /** {@code count} minimal plain pages, with {@code trailing} appended before the closing tag. */
  private static String pages(int count, String trailing) {
    StringBuilder document = new StringBuilder("<mxfile>");
    for (int page = 0; page < count; page++) {
      document
          .append("<diagram id=\"p")
          .append(page)
          .append("\" name=\"P\"><mxGraphModel><root><mxCell id=\"0\"/></root></mxGraphModel></diagram>");
    }
    return document.append(trailing).append("</mxfile>").toString();
  }

  /** One page holding {@code count} sibling cells, with {@code trailing} before the closing tags. */
  private static String cells(int count, String trailing) {
    StringBuilder document = new StringBuilder("<mxGraphModel><root>");
    for (int cell = 0; cell < count; cell++) {
      document.append("<mxCell id=\"c").append(cell).append("\"/>");
    }
    return document.append(trailing).append("</root></mxGraphModel>").toString();
  }

  /** A root cell plus {@code depth} cells chained through {@code parent}, deepest last. */
  private static String parentChain(int depth) {
    StringBuilder document = new StringBuilder("<mxGraphModel><root><mxCell id=\"r\"/>");
    for (int level = 1; level <= depth; level++) {
      document
          .append("<mxCell id=\"c")
          .append(level)
          .append("\" parent=\"")
          .append(level == 1 ? "r" : "c" + (level - 1))
          .append("\"/>");
    }
    return document.append("</root></mxGraphModel>").toString();
  }

  /** All element text a factory yields for a document, so the mutation proof can see the payload. */
  private static String textOf(XMLInputFactory factory, String xml) throws XMLStreamException {
    XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
    StringBuilder text = new StringBuilder();
    while (reader.hasNext()) {
      if (reader.next() == XMLStreamConstants.CHARACTERS) {
        text.append(reader.getText());
      }
    }
    return text.toString();
  }

  private static void assertRefused(ThrowingRead read, DiagnosticCode expected) {
    assertThat(refusalOf(read).code()).isEqualTo(expected.code());
  }

  private static Diagnostic refusalOf(ThrowingRead read) {
    try {
      MxFile unexpected = read.run();
      throw new AssertionError("expected a refusal, got " + unexpected);
    } catch (EngineException refusal) {
      assertThat(refusal.diagnostics()).hasSize(1);
      return refusal.diagnostics().get(0);
    }
  }

  @FunctionalInterface
  private interface ThrowingRead {
    MxFile run() throws EngineException;
  }
}

package dev.dediren.plugins.drawio.mx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the mxfile writer: what it emits, how it escapes, and that {@link MxReader} reads
 * back exactly what was written.
 *
 * <p>The reader is the oracle throughout. It is the cheapest available proxy for "draw.io can open
 * this" — nothing in this build can launch the editor — and asserting the writer against it keeps
 * the two halves of the {@code mx} package from drifting.
 */
class MxWriterTest {

  private static MxCell root() {
    return new MxCell("0", null, null, null, false, false, null, null, true, null, null);
  }

  private static MxCell layer() {
    return new MxCell("1", "0", null, null, false, false, null, null, true, null, null);
  }

  private static MxFile onePage(MxCell... cells) {
    return new MxFile(List.of(new MxDiagram("page-1", "Main", false, List.of(cells))));
  }

  // ---------------------------------------------------------------- document shape

  @Test
  void emitsAnUncompressedMxfileTheReaderAcceptsUnchanged() throws Exception {
    MxCell vertex =
        new MxCell(
            "a",
            "1",
            "Alpha",
            "rounded=0;whiteSpace=wrap;html=1;",
            true,
            false,
            null,
            null,
            true,
            new MxGeometry(12, 24, 160, 80, false, null, null, List.of()),
            null);

    String xml = MxWriter.write(onePage(root(), layer(), vertex));

    // Uncompressed is the whole point: draw.io reads plain XML natively and the artifact stays
    // diffable. A compressed page would be character data inside <diagram> instead.
    assertThat(xml).contains("<mxGraphModel>").doesNotContain("compressed=");

    MxFile reread = MxReader.read(xml);
    assertThat(reread.diagrams()).hasSize(1);
    MxDiagram page = reread.diagrams().get(0);
    assertThat(page.id()).isEqualTo("page-1");
    assertThat(page.name()).isEqualTo("Main");
    assertThat(page.compressed()).isFalse();
    assertThat(page.cells()).extracting(MxCell::id).containsExactly("0", "1", "a");

    MxCell alpha = page.cells().get(2);
    assertThat(alpha.value()).isEqualTo("Alpha");
    assertThat(alpha.style()).isEqualTo("rounded=0;whiteSpace=wrap;html=1;");
    assertThat(alpha.vertex()).isTrue();
    assertThat(alpha.parent()).isEqualTo("1");
    assertThat(alpha.geometry().x()).isEqualTo(12);
    assertThat(alpha.geometry().y()).isEqualTo(24);
    assertThat(alpha.geometry().width()).isEqualTo(160);
    assertThat(alpha.geometry().height()).isEqualTo(80);
  }

  // ---------------------------------------------------------------- escaping

  @Test
  void escapesStructurallyRatherThanTrustingTheCallerToPreEscape() throws Exception {
    // A label is arbitrary user text and a style is ;/=-delimited data, so both are exactly the
    // places a string-concatenating emitter produces a file draw.io cannot parse.
    String hostileLabel = "A & B <tag> \"quoted\" 'single'";
    MxCell vertex =
        new MxCell(
            "a",
            "1",
            hostileLabel,
            "shape=note;fillColor=#ffffff;html=1;",
            true,
            false,
            null,
            null,
            true,
            null,
            null);

    String xml = MxWriter.write(onePage(root(), layer(), vertex));

    assertThat(xml).contains("&amp;").contains("&lt;tag&gt;").contains("&quot;quoted&quot;");
    assertThat(MxReader.read(xml).diagrams().get(0).cells().get(2).value()).isEqualTo(hostileLabel);
  }

  @Test
  void scrubsCharactersXmlCannotRepresentBeforeTheyReachTheDocument() throws Exception {
    // U+0001 is a C0 control XML 1.0 cannot represent at all. A contract-valid label may carry
    // one, and it must not be able to produce an artifact draw.io refuses to open.
    String withControlCharacter = "before" + (char) 1 + "after";
    MxCell vertex =
        new MxCell("a", "1", withControlCharacter, null, true, false, null, null, true, null, null);

    String xml = MxWriter.write(onePage(root(), layer(), vertex));

    assertThat(xml).doesNotContain(String.valueOf((char) 1));
    assertThat(MxReader.read(xml).diagrams().get(0).cells().get(2).value())
        .isEqualTo("before�after");
  }

  // ---------------------------------------------------------------- wrappers

  @Test
  void reEmitsAWrapperUnderItsOwnElementNameWithEveryCustomAttribute() throws Exception {
    MxObject wrapper =
        new MxObject(
            "UserObject",
            new java.util.LinkedHashMap<>(
                Map.of("id", "a", "label", "Alpha", "dedirenType", "ApplicationComponent")));
    MxCell wrapped =
        new MxCell("a", "1", "Alpha", "html=1;", true, false, null, null, true, null, wrapper);

    String xml = MxWriter.write(onePage(root(), layer(), wrapped));

    // draw.io reads <object> and <UserObject> differently; the wrapper's own element name is
    // re-emitted verbatim rather than normalized.
    assertThat(xml).contains("<UserObject").doesNotContain("<object");

    MxCell reread = MxReader.read(xml).diagrams().get(0).cells().get(2);
    assertThat(reread.wrapped()).isTrue();
    assertThat(reread.object().elementName()).isEqualTo("UserObject");
    assertThat(reread.object().attributes())
        .containsEntry("dedirenType", "ApplicationComponent")
        .containsEntry("label", "Alpha")
        .containsEntry("id", "a");
    assertThat(reread.id()).isEqualTo("a");
    assertThat(reread.value()).isEqualTo("Alpha");
  }

  @Test
  void suppliesAWrappersIdentityWhenItsAttributeMapDoesNotCarryIt() throws Exception {
    // The reader normalizes identity onto MxCell; a wrapper assembled by hand may carry only the
    // custom attributes, and the writer must not silently emit a cell nothing can reference.
    MxObject wrapper =
        new MxObject("object", new java.util.LinkedHashMap<>(Map.of("dedirenId", "alpha")));
    MxCell wrapped =
        new MxCell("a", "1", "Alpha", null, true, false, null, null, true, null, wrapper);

    MxCell reread =
        MxReader.read(MxWriter.write(onePage(root(), layer(), wrapped)))
            .diagrams()
            .get(0)
            .cells()
            .get(2);

    assertThat(reread.id()).isEqualTo("a");
    assertThat(reread.value()).isEqualTo("Alpha");
  }

  // ---------------------------------------------------------------- geometry

  @Test
  void preservesEdgeWaypointsAndFloatingEndpointsExactly() throws Exception {
    MxGeometry geometry =
        new MxGeometry(
            0,
            0,
            0,
            0,
            true,
            new MxPoint(10, 20),
            new MxPoint(300, 20),
            List.of(new MxPoint(370, 141), new MxPoint(610, 141.5)));
    MxCell edge =
        new MxCell(
            "e", "1", "calls", "edgeStyle=none;", false, true, "a", "b", true, geometry, null);

    MxGeometry reread =
        MxReader.read(MxWriter.write(onePage(root(), layer(), edge)))
            .diagrams()
            .get(0)
            .cells()
            .get(2)
            .geometry();

    assertThat(reread.relative()).isTrue();
    assertThat(reread.sourcePoint()).isEqualTo(new MxPoint(10, 20));
    assertThat(reread.targetPoint()).isEqualTo(new MxPoint(300, 20));
    assertThat(reread.points()).containsExactly(new MxPoint(370, 141), new MxPoint(610, 141.5));
  }

  @Test
  void writesWholeCoordinatesWithoutADecimalPointAndFractionalOnesExactly() {
    MxCell vertex =
        new MxCell(
            "a",
            "1",
            null,
            null,
            true,
            false,
            null,
            null,
            true,
            new MxGeometry(12, 215.83333333333334, 160, 80, false, null, null, List.of()),
            null);

    String xml = MxWriter.write(onePage(root(), layer(), vertex));

    // Exactness matters more than brevity: these coordinates come straight from the layout result
    // and rounding them here would make the export disagree with every other artifact stage.
    assertThat(xml).contains("x=\"12\"").contains("y=\"215.83333333333334\"");
    assertThat(xml).doesNotContain("12.0");
  }

  @Test
  void omitsBoxCoordinatesForAZeroSizedGeometry() {
    // An edge (and the hidden metadata cell) carry a geometry with no box; emitting x/y/width/
    // height="0" for them is what makes a hand-read .drawio file hard to review.
    MxCell edge =
        new MxCell(
            "e",
            "1",
            null,
            null,
            false,
            true,
            "a",
            "b",
            true,
            new MxGeometry(0, 0, 0, 0, true, null, null, List.of()),
            null);

    String xml = MxWriter.write(onePage(root(), layer(), edge));

    assertThat(xml).contains("<mxGeometry relative=\"1\" as=\"geometry\"");
    assertThat(xml).doesNotContain("width=\"0\"").doesNotContain("height=\"0\"");
  }

  @Test
  void marksAHiddenCellSoTheEditorDoesNotDrawIt() throws Exception {
    MxCell hidden =
        new MxCell("m", "1", null, "text;html=1;", true, false, null, null, false, null, null);

    String xml = MxWriter.write(onePage(root(), layer(), hidden));

    assertThat(xml).contains("visible=\"0\"");
    assertThat(MxReader.read(xml).diagrams().get(0).cells().get(2).visible()).isFalse();
  }

  // ---------------------------------------------------------------- pages

  @Test
  void writesEveryPageInDocumentOrder() throws Exception {
    MxFile file =
        new MxFile(
            List.of(
                new MxDiagram("p1", "First", false, List.of(root(), layer())),
                new MxDiagram("p2", "Second", false, List.of(root(), layer()))));

    MxFile reread = MxReader.read(MxWriter.write(file));

    assertThat(reread.diagrams()).extracting(MxDiagram::name).containsExactly("First", "Second");
  }
}

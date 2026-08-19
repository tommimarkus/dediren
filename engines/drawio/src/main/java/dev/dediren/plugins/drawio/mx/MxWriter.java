package dev.dediren.plugins.drawio.mx;

import dev.dediren.engine.XmlText;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Serialises an {@link MxFile} back to draw.io XML, backed by the JDK StAX {@link XMLStreamWriter}
 * (no external dependency — {@code java.xml}), the same way the render engine's {@code SvgWriter}
 * is.
 *
 * <h2>Why structural escaping, not string concatenation</h2>
 *
 * <p>Escaping matters more here than it does in SVG. An {@code <object label="…">} carries an
 * arbitrary user label, and {@code style="…"} carries {@code ;}/{@code =}-delimited data whose own
 * delimiters look nothing like XML's — so a string-composing emitter has two independent ways to
 * produce a file draw.io cannot open, and the second one is invisible in review. Letting the writer
 * escape makes well-formedness a structural property of the emitter rather than an invariant every
 * call site has to remember. Every value additionally goes through {@link XmlText#scrub} first, so
 * a contract-valid label carrying a C0 control or a lone surrogate cannot yield an ill-formed
 * artifact.
 *
 * <h2>Always uncompressed</h2>
 *
 * <p>Every page is written as plain {@code <mxGraphModel>} XML regardless of {@link
 * MxDiagram#compressed()}, which is provenance about how a page <em>arrived</em> and not an
 * instruction about how it leaves. draw.io reads plain XML natively; keeping it plain is what makes
 * the artifact reviewable in a diff and comparable by {@code dediren verify}/{@code diff}.
 * Compression is an import-only concern.
 *
 * <h2>Two shaping rules worth knowing before reading a golden</h2>
 *
 * <p><strong>Coordinates are exact, never rounded.</strong> A whole number is written without a
 * decimal point ({@code x="12"}, not {@code x="12.0"}); anything else keeps Java's shortest
 * round-tripping form ({@code y="215.83333333333334"}). These coordinates come straight from the
 * layout result, and rounding them here would silently put the draw.io export at odds with every
 * other artifact stage rendered from the same geometry.
 *
 * <p><strong>A zero-sized geometry writes no box.</strong> Edges and the hidden metadata cell carry
 * a geometry with no extent, and spelling {@code x="0" y="0" width="0" height="0"} out for them is
 * most of what makes a hand-read {@code .drawio} hard to review. Omitting them is lossless: {@link
 * MxReader} defaults an absent coordinate to zero, exactly as mxGraph itself does.
 */
public final class MxWriter {

  private static final XMLOutputFactory FACTORY = XMLOutputFactory.newInstance();

  /** draw.io records the application that wrote the file; this one is dediren's export lane. */
  private static final String HOST = "dediren";

  private final StringWriter out = new StringWriter();
  private final XMLStreamWriter writer;

  /** Depth of the element currently being written; the root sits at depth 0. */
  private int depth;

  /** {@code hasElementChild.get(d)} — whether the element open at depth {@code d} has children. */
  private final List<Boolean> hasElementChild = new ArrayList<>();

  private MxWriter() {
    writer = run(() -> FACTORY.createXMLStreamWriter(out));
  }

  /** Serialises one draw.io document. */
  public static String write(MxFile file) {
    MxWriter mx = new MxWriter();
    mx.writeFile(file);
    return mx.finish();
  }

  // ---------------------------------------------------------------- document structure

  private void writeFile(MxFile file) {
    start("mxfile").attr("host", HOST);
    for (MxDiagram diagram : file.diagrams()) {
      writeDiagram(diagram);
    }
    end();
  }

  private void writeDiagram(MxDiagram diagram) {
    start("diagram").attrIf("id", diagram.id()).attrIf("name", diagram.name());
    // No compressed= attribute: recent draw.io omits it, MxReader detects compression
    // structurally, and this writer never compresses.
    start("mxGraphModel");
    start("root");
    for (MxCell cell : diagram.cells()) {
      writeCell(cell);
    }
    end();
    end();
    end();
  }

  // ---------------------------------------------------------------- cells

  private void writeCell(MxCell cell) {
    if (cell.object() == null) {
      writeBareCell(cell);
      return;
    }
    // The wrapper owns identity and label when a cell is wrapped (see MxCell): the inner mxCell
    // carries neither, which is exactly the shape MxReader resolves back onto MxCell.
    MxObject wrapper = cell.object();
    start(wrapper.elementName());
    for (Map.Entry<String, String> attribute : wrapper.attributes().entrySet()) {
      attr(attribute.getKey(), attribute.getValue());
    }
    // A wrapper assembled by hand may carry only its custom attributes; supplying the identity the
    // reader normalized onto the cell keeps the writer a true inverse of the reader rather than
    // emitting a cell nothing can reference.
    if (!wrapper.attributes().containsKey("label") && cell.value() != null) {
      attr("label", cell.value());
    }
    if (!wrapper.attributes().containsKey("id")) {
      attr("id", cell.id());
    }
    writeInnerCell(cell, false);
    end();
  }

  private void writeBareCell(MxCell cell) {
    writeInnerCell(cell, true);
  }

  /** Writes the {@code <mxCell>} itself; {@code withIdentity} is false inside a wrapper. */
  private void writeInnerCell(MxCell cell, boolean withIdentity) {
    boolean hasGeometry = cell.geometry() != null;
    if (hasGeometry) {
      start("mxCell");
    } else {
      empty("mxCell");
    }
    if (withIdentity) {
      attr("id", cell.id());
      attrIf("value", cell.value());
    }
    attrIf("style", cell.style());
    if (cell.vertex()) {
      attr("vertex", "1");
    }
    if (cell.edge()) {
      attr("edge", "1");
    }
    attrIf("parent", cell.parent());
    attrIf("source", cell.source());
    attrIf("target", cell.target());
    if (!cell.visible()) {
      // mxGraph defaults to visible, so only the false case is written.
      attr("visible", "0");
    }
    if (hasGeometry) {
      writeGeometry(cell.geometry());
      end();
    }
  }

  private void writeGeometry(MxGeometry geometry) {
    boolean boxed = geometry.width() != 0 || geometry.height() != 0;
    boolean hasChildren =
        geometry.sourcePoint() != null || geometry.targetPoint() != null || !geometry.points().isEmpty();
    if (hasChildren) {
      start("mxGeometry");
    } else {
      empty("mxGeometry");
    }
    if (boxed) {
      attr("x", number(geometry.x()));
      attr("y", number(geometry.y()));
      attr("width", number(geometry.width()));
      attr("height", number(geometry.height()));
    }
    if (geometry.relative()) {
      attr("relative", "1");
    }
    attr("as", "geometry");
    if (!hasChildren) {
      return;
    }
    writePoint("mxPoint", geometry.sourcePoint(), "sourcePoint");
    writePoint("mxPoint", geometry.targetPoint(), "targetPoint");
    if (!geometry.points().isEmpty()) {
      start("Array").attr("as", "points");
      for (MxPoint point : geometry.points()) {
        writePoint("mxPoint", point, null);
      }
      end();
    }
    end();
  }

  private void writePoint(String element, MxPoint point, String as) {
    if (point == null) {
      return;
    }
    empty(element).attr("x", number(point.x())).attr("y", number(point.y()));
    if (as != null) {
      attr("as", as);
    }
  }

  // ---------------------------------------------------------------- numbers

  /**
   * The exact value, in the shortest form that reads back identically: whole numbers lose the
   * {@code .0} Java's own {@code Double.toString} would add, everything else keeps it verbatim.
   */
  private static String number(double value) {
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      // (long) of a negative zero is 0, which is what draw.io would read anyway.
      return Long.toString((long) value);
    }
    return Double.toString(value);
  }

  // ---------------------------------------------------------------- StAX plumbing

  private MxWriter start(String name) {
    beforeElement();
    run(() -> writer.writeStartElement(name));
    depth++;
    if (hasElementChild.size() < depth) {
      hasElementChild.add(false);
    } else {
      hasElementChild.set(depth - 1, false);
    }
    return this;
  }

  private MxWriter empty(String name) {
    beforeElement();
    run(() -> writer.writeEmptyElement(name));
    return this;
  }

  private MxWriter end() {
    depth--;
    if (hasElementChild.get(depth)) {
      // Unlike an opening tag, a closing tag is indented even at depth 0: the root's own end tag
      // still belongs on its own line.
      indent(depth);
    }
    run(writer::writeEndElement);
    return this;
  }

  private MxWriter attr(String name, String value) {
    run(() -> writer.writeAttribute(name, XmlText.scrub(value)));
    return this;
  }

  private MxWriter attrIf(String name, String value) {
    if (value != null) {
      attr(name, value);
    }
    return this;
  }

  /**
   * Indents the element about to be written and records it on its parent, so a parent whose only
   * content is attributes still closes on its own line.
   */
  private void beforeElement() {
    if (depth > 0) {
      hasElementChild.set(depth - 1, true);
    }
    breakLine(depth);
  }

  /** A line break and this level's indentation; the document's very first tag gets neither. */
  private void breakLine(int level) {
    if (level == 0) {
      return;
    }
    indent(level);
  }

  private void indent(int level) {
    String indent = "\n" + "  ".repeat(level);
    run(() -> writer.writeCharacters(indent));
  }

  private String finish() {
    run(writer::flush);
    // A trailing newline keeps the artifact a well-behaved text file for diff and review tooling.
    return out.toString() + "\n";
  }

  private void run(StreamOp op) {
    try {
      op.run();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("draw.io XML stream writing failed", e);
    }
  }

  private <T> T run(StreamSupplier<T> op) {
    try {
      return op.get();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("draw.io XML stream writing failed", e);
    }
  }

  private interface StreamOp {
    void run() throws XMLStreamException;
  }

  private interface StreamSupplier<T> {
    T get() throws XMLStreamException;
  }
}

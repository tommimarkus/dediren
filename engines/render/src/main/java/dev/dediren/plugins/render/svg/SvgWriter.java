package dev.dediren.plugins.render.svg;

import java.io.StringWriter;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Structured SVG emitter backed by the JDK StAX {@link XMLStreamWriter} (no external dependency —
 * {@code java.xml}). Replaces hand-built {@code String.format}/{@code StringBuilder} emission: XML
 * escaping and well-formedness become structural guarantees of the writer rather than a manual
 * invariant. The writer is namespace-unaware, so {@code xmlns} is written as an ordinary attribute,
 * and attributes are emitted in call order — both required to keep output byte-identical to the
 * previous string emitter.
 *
 * <p>The JDK writer escapes exactly as the retired {@code Svg.attr}/{@code Svg.text} helpers did
 * ({@code & < > "} in attribute values; {@code & < >} in text, leaving {@code "} raw), emits
 * self-closing empty elements with no space before {@code />}, and writes no XML prolog — verified
 * against the render golden fixtures.
 *
 * <p>Numeric formatting stays the caller's job: pass already-formatted strings (via {@link
 * Svg#styleNumber} or an explicit {@code %.1f}) to {@link #attr}, exactly matching the prior format
 * strings.
 */
public final class SvgWriter {

  // Public across the render engine's packages so document assemblers (SvgDocument and the
  // sequence renderer) can create one and per-notation leaf builders (node/edge shape, label,
  // decorator, icon emitters) can emit into the shared writer.
  private static final XMLOutputFactory FACTORY = XMLOutputFactory.newInstance();

  private final StringWriter out = new StringWriter();
  private final XMLStreamWriter writer;

  public SvgWriter() {
    writer = run(() -> FACTORY.createXMLStreamWriter(out));
  }

  /** Opens an element; must be balanced by {@link #end}. */
  public SvgWriter start(String name) {
    run(() -> writer.writeStartElement(name));
    return this;
  }

  /** Writes a self-closing empty element (no {@link #end} follows). */
  public SvgWriter empty(String name) {
    run(() -> writer.writeEmptyElement(name));
    return this;
  }

  /** Closes the element opened by the matching {@link #start}. */
  public SvgWriter end() {
    run(writer::writeEndElement);
    return this;
  }

  /** Writes an attribute on the current start/empty element. The value is escaped by the writer. */
  public SvgWriter attr(String name, String value) {
    run(() -> writer.writeAttribute(name, value));
    return this;
  }

  /** Writes the attribute only when the value is non-null (optional presentation attributes). */
  public SvgWriter attrIf(String name, String value) {
    if (value != null) {
      attr(name, value);
    }
    return this;
  }

  /** Writes escaped text content. */
  public SvgWriter text(String value) {
    run(() -> writer.writeCharacters(value == null ? "" : value));
    return this;
  }

  /**
   * Writes a pre-formed, already-correctly-escaped fragment verbatim (forcing the pending start tag
   * closed first). For content that is already in final XML form and must not be re-escaped by the
   * writer — notably the ArchiMate/UML guillemet stereotypes emitted as numeric character
   * references ({@code &#171;…&#187;}) and labels the caller has pre-escaped. Not a general emit
   * path: element/attribute/text output goes through the typed methods above so escaping stays a
   * structural guarantee.
   */
  public SvgWriter raw(String fragment) {
    run(
        () -> {
          writer.writeCharacters("");
          writer.flush();
          out.write(fragment);
        });
    return this;
  }

  /** Flushes and returns the accumulated SVG document text. */
  public String finish() {
    run(writer::flush);
    return out.toString();
  }

  private void run(StreamOp op) {
    try {
      op.run();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("SVG stream writing failed", e);
    }
  }

  private <T> T run(StreamSupplier<T> op) {
    try {
      return op.get();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("SVG stream writing failed", e);
    }
  }

  private interface StreamOp {
    void run() throws XMLStreamException;
  }

  private interface StreamSupplier<T> {
    T get() throws XMLStreamException;
  }
}

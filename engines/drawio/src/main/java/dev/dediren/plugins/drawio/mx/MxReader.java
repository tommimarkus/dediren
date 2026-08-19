package dev.dediren.plugins.drawio.mx;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.SecureXml;
import dev.dediren.plugins.drawio.DrawioLimits;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Streaming reader for a draw.io document, producing the faithful {@link MxFile} model. Mapping
 * that model onto a Dediren source document is a separate step; nothing here resolves types, builds
 * groups, or knows what an ArchiMate layer is.
 *
 * <h2>Two accepted roots</h2>
 *
 * <p>{@code <mxfile>} with at least one {@code <diagram>}, and a bare {@code <mxGraphModel>} —
 * which is what draw.io's Extras ▸ Edit Diagram shows and therefore what a user pastes. The bare
 * form becomes one unnamed page so consumers see a single shape. Anything else, including an {@code
 * <mxfile>} holding no page at all, is {@link DiagnosticCode#DRAWIO_UNSUPPORTED_DOCUMENT}.
 *
 * <h2>Compression is detected structurally</h2>
 *
 * <p>A page is compressed if its {@code <diagram>} holds character data rather than an {@code
 * <mxGraphModel>} child. The {@code compressed=} attribute is never consulted: recent draw.io omits
 * it, so trusting it would refuse ordinary current files, and nothing stops a hostile file from
 * setting it to the opposite of the truth.
 *
 * <p><strong>The decompressed payload is a second, fully independent attacker-controlled XML
 * document, and gets its own {@link SecureXml#inputFactory()}.</strong> Parsing it with a default
 * factory would leave every control the outer parse applies switched off for the half of the format
 * that is easiest to hide something in — the half a reviewer cannot read. This is the single
 * easiest thing in the reader to get wrong, and {@code MxReaderTest} pins it with a test that also
 * demonstrates a default factory accepting the same payload.
 *
 * <p>Diagnostic paths carry the page, and say when coordinates are inside a decoded payload —
 * {@code "page 3 (Architecture, decompressed), line 12, column 5"}. The distinction is not
 * cosmetic: the two line numbers count lines in different documents, and a user sent to the wrong
 * one is looking at a line that has nothing to do with the fault. Coordinates are the JDK StAX
 * reader's, which reports the position it had reached — the end of the offending token, not its
 * start.
 *
 * <h2>Ceilings</h2>
 *
 * <p>Pages, cells, and per-attribute bytes are counted as the walk proceeds and abort it in place,
 * which is the reason for streaming rather than materialising a tree first. Cells are counted
 * across the whole document, not per page, for the reason {@link DrawioLimits} gives for the
 * decompression budget: a per-page cell ceiling would let a 256-page file buy 256 times the
 * allowance.
 *
 * <p><strong>Nesting is the exception, and knowingly so.</strong> Containment in this format is the
 * {@code parent} attribute, and a parent may be declared after its child, so a cell's depth is not
 * knowable at the moment the cell is read. Depth is therefore resolved in one pass at the end of
 * each page, over a cell set the cell ceiling has already bounded. Failures are atomic: the caller
 * receives a complete {@link MxFile} or an exception, never a prefix.
 */
public final class MxReader {

  private static final String NO_LOCATION_LINE_COLUMN = "1, column 1";

  /** Longest attacker-supplied fragment echoed into a diagnostic message. */
  private static final int ECHO_LIMIT = 80;

  private final MxDeflate deflate = MxDeflate.forDocument();
  private int pages;
  private int cells;
  private int currentPage;
  private String currentPageName;
  private boolean insideDecompressedPayload;

  private MxReader() {}

  /** Parses one draw.io document. */
  public static MxFile read(String source) throws EngineException {
    Objects.requireNonNull(source, "source");
    return new MxReader().readDocument(source);
  }

  private MxFile readDocument(String source) throws EngineException {
    XMLStreamReader reader = open(source);
    try {
      String root = advanceToRootElement(reader);
      if ("mxfile".equals(root)) {
        return new MxFile(readMxfile(reader));
      }
      if ("mxGraphModel".equals(root)) {
        // The bare Edit Diagram form: one implicit, unnamed, uncompressed page.
        pages = 1;
        currentPage = 1;
        return new MxFile(List.of(new MxDiagram(null, null, false, readGraphModel(reader))));
      }
      throw DrawioLimits.limit(
          DiagnosticCode.DRAWIO_UNSUPPORTED_DOCUMENT,
          "the document root is neither <mxfile> nor <mxGraphModel>");
    } finally {
      closeQuietly(reader);
    }
  }

  // ---------------------------------------------------------------- document structure

  private List<MxDiagram> readMxfile(XMLStreamReader reader) throws EngineException {
    List<MxDiagram> diagrams = new ArrayList<>();
    boolean open = true;
    while (open) {
      int event = next(reader);
      switch (event) {
        case XMLStreamConstants.START_ELEMENT -> {
          checkAttributeBytes(reader);
          if ("diagram".equals(reader.getLocalName())) {
            diagrams.add(readDiagram(reader));
          } else {
            // A future draw.io may add siblings of <diagram>; none of them can hold cells.
            skipElement(reader);
          }
        }
        case XMLStreamConstants.END_ELEMENT, XMLStreamConstants.END_DOCUMENT -> open = false;
        default -> {
          // Text, comments and processing instructions between pages carry nothing.
        }
      }
    }
    if (diagrams.isEmpty()) {
      throw DrawioLimits.limit(
          DiagnosticCode.DRAWIO_UNSUPPORTED_DOCUMENT, "the <mxfile> holds no <diagram> page");
    }
    return diagrams;
  }

  private MxDiagram readDiagram(XMLStreamReader reader) throws EngineException {
    pages++;
    if (pages > DrawioLimits.MAX_PAGES) {
      throw DrawioLimits.limit(
          DiagnosticCode.DRAWIO_PAGE_LIMIT_EXCEEDED,
          "the document holds more than " + DrawioLimits.MAX_PAGES + " pages");
    }
    currentPage = pages;
    String id = attribute(reader, "id");
    currentPageName = attribute(reader, "name");
    String name = currentPageName;

    StringBuilder body = new StringBuilder();
    List<MxCell> inlineCells = null;
    boolean open = true;
    while (open) {
      int event = next(reader);
      switch (event) {
        case XMLStreamConstants.START_ELEMENT -> {
          if (inlineCells == null && "mxGraphModel".equals(reader.getLocalName())) {
            inlineCells = readGraphModel(reader);
          } else {
            throw syntax(
                "a <diagram> may hold plain <mxGraphModel> XML or compressed text, not <"
                    + echo(reader.getLocalName())
                    + ">",
                reader);
          }
        }
        // Not token-checked: a compressed body is legitimately megabytes, and MxDeflate is what
        // bounds it.
        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
            body.append(reader.getText());
        case XMLStreamConstants.END_ELEMENT, XMLStreamConstants.END_DOCUMENT -> open = false;
        default -> {
          // A comment inside the body splits the character data; IS_COALESCING does not bridge it,
          // which is why the text above is accumulated rather than taken from one event.
        }
      }
    }

    if (inlineCells != null) {
      return new MxDiagram(id, name, false, inlineCells);
    }
    String payload = body.toString();
    if (payload.isBlank()) {
      // draw.io writes empty pages; an empty page is not a malformed one.
      return new MxDiagram(id, name, false, List.of());
    }
    return new MxDiagram(id, name, true, readCompressedPage(payload));
  }

  /**
   * Decodes a page body and parses it through a second, independently hardened reader. See the
   * class javadoc: this is the control that a default factory here would silently remove.
   */
  private List<MxCell> readCompressedPage(String payload) throws EngineException {
    String decoded = deflate.decodeDiagramBody(payload);
    insideDecompressedPayload = true;
    XMLStreamReader inner = open(decoded);
    try {
      String root = advanceToRootElement(inner);
      if (!"mxGraphModel".equals(root)) {
        throw syntax(
            "the decompressed page is rooted at <" + echo(root) + ">, not <mxGraphModel>", inner);
      }
      return readGraphModel(inner);
    } finally {
      closeQuietly(inner);
      insideDecompressedPayload = false;
    }
  }

  // ---------------------------------------------------------------- one page's cells

  /** Reads one {@code <mxGraphModel>} body; the reader is positioned on its start tag. */
  private List<MxCell> readGraphModel(XMLStreamReader reader) throws EngineException {
    checkAttributeBytes(reader);
    List<MxCell> pageCells = new ArrayList<>();
    Map<String, MxCell> byId = new LinkedHashMap<>();
    Map<String, int[]> locations = new HashMap<>();
    boolean open = true;
    while (open) {
      int event = next(reader);
      switch (event) {
        case XMLStreamConstants.START_ELEMENT -> {
          checkAttributeBytes(reader);
          if ("root".equals(reader.getLocalName())) {
            readRoot(reader, pageCells, byId, locations);
          } else {
            skipElement(reader);
          }
        }
        case XMLStreamConstants.END_ELEMENT, XMLStreamConstants.END_DOCUMENT -> open = false;
        default -> {
          // Whitespace between elements.
        }
      }
    }
    resolveParentChains(pageCells, byId, locations);
    return pageCells;
  }

  private void readRoot(
      XMLStreamReader reader,
      List<MxCell> pageCells,
      Map<String, MxCell> byId,
      Map<String, int[]> locations)
      throws EngineException {
    boolean open = true;
    while (open) {
      int event = next(reader);
      switch (event) {
        case XMLStreamConstants.START_ELEMENT -> {
          int[] location = locationOf(reader);
          String element = reader.getLocalName();
          MxCell cell;
          if ("mxCell".equals(element)) {
            cell = readCell(reader, null, location);
          } else if ("object".equals(element) || "UserObject".equals(element)) {
            cell = readWrappedCell(reader, location);
          } else {
            skipElement(reader);
            continue;
          }
          if (byId.putIfAbsent(cell.id(), cell) != null) {
            throw syntax("cell id '" + echo(cell.id()) + "' appears twice on this page", location);
          }
          locations.put(cell.id(), location);
          pageCells.add(cell);
        }
        case XMLStreamConstants.END_ELEMENT, XMLStreamConstants.END_DOCUMENT -> open = false;
        default -> {
          // Whitespace between cells.
        }
      }
    }
  }

  /**
   * Reads an {@code <object>}/{@code <UserObject>} wrapper and the {@code <mxCell>} inside it. The
   * wrapper's whole attribute map is kept; {@link MxCell} takes the effective identity and label
   * from it, per {@link MxCell}'s contract.
   */
  private MxCell readWrappedCell(XMLStreamReader reader, int[] location) throws EngineException {
    checkAttributeBytes(reader);
    String elementName = reader.getLocalName();
    Map<String, String> attributes = new LinkedHashMap<>();
    for (int index = 0; index < reader.getAttributeCount(); index++) {
      attributes.put(reader.getAttributeLocalName(index), reader.getAttributeValue(index));
    }
    MxObject wrapper = new MxObject(elementName, attributes);

    MxCell inner = null;
    boolean open = true;
    while (open) {
      int event = next(reader);
      switch (event) {
        case XMLStreamConstants.START_ELEMENT -> {
          if (inner == null && "mxCell".equals(reader.getLocalName())) {
            inner = readCell(reader, wrapper, locationOf(reader));
          } else {
            skipElement(reader);
          }
        }
        case XMLStreamConstants.END_ELEMENT, XMLStreamConstants.END_DOCUMENT -> open = false;
        default -> {
          // Whitespace inside the wrapper.
        }
      }
    }
    if (inner == null) {
      throw syntax("<" + echo(elementName) + "> wrapper holds no <mxCell>", location);
    }
    return inner;
  }

  private MxCell readCell(XMLStreamReader reader, MxObject wrapper, int[] location)
      throws EngineException {
    checkAttributeBytes(reader);
    cells++;
    if (cells > DrawioLimits.MAX_CELLS) {
      throw DrawioLimits.limit(
          DiagnosticCode.DRAWIO_CELL_LIMIT_EXCEEDED,
          "the document holds more than " + DrawioLimits.MAX_CELLS + " cells");
    }

    String id = attribute(reader, "id");
    String value = attribute(reader, "value");
    if (wrapper != null) {
      // Identity is structural, so it falls back to the inner cell rather than failing the page
      // over a wrapper quirk. The label does not: inventing one from a place draw.io never reads
      // would make the model say something the file does not.
      id = wrapper.attributes().getOrDefault("id", id);
      value = wrapper.attributes().get("label");
    }
    if (id == null) {
      throw syntax("a cell carries no id, so nothing can reference it", location);
    }

    String parent = attribute(reader, "parent");
    String style = attribute(reader, "style");
    boolean vertex = "1".equals(attribute(reader, "vertex"));
    boolean edge = "1".equals(attribute(reader, "edge"));
    String source = attribute(reader, "source");
    String target = attribute(reader, "target");
    boolean visible = !"0".equals(attribute(reader, "visible"));

    MxGeometry geometry = null;
    boolean open = true;
    while (open) {
      int event = next(reader);
      switch (event) {
        case XMLStreamConstants.START_ELEMENT -> {
          if (geometry == null && "mxGeometry".equals(reader.getLocalName())) {
            geometry = readGeometry(reader);
          } else {
            skipElement(reader);
          }
        }
        case XMLStreamConstants.END_ELEMENT, XMLStreamConstants.END_DOCUMENT -> open = false;
        default -> {
          // mxCell has no meaningful text content.
        }
      }
    }
    return new MxCell(
        id, parent, value, style, vertex, edge, source, target, visible, geometry, wrapper);
  }

  private MxGeometry readGeometry(XMLStreamReader reader) throws EngineException {
    checkAttributeBytes(reader);
    double x = coordinate(reader, "x");
    double y = coordinate(reader, "y");
    double width = coordinate(reader, "width");
    double height = coordinate(reader, "height");
    boolean relative = "1".equals(attribute(reader, "relative"));

    MxPoint sourcePoint = null;
    MxPoint targetPoint = null;
    List<MxPoint> points = new ArrayList<>();
    boolean open = true;
    while (open) {
      int event = next(reader);
      switch (event) {
        case XMLStreamConstants.START_ELEMENT -> {
          checkAttributeBytes(reader);
          String element = reader.getLocalName();
          String as = attribute(reader, "as");
          if ("mxPoint".equals(element) && "sourcePoint".equals(as)) {
            sourcePoint = readPoint(reader);
          } else if ("mxPoint".equals(element) && "targetPoint".equals(as)) {
            targetPoint = readPoint(reader);
          } else if ("Array".equals(element) && "points".equals(as)) {
            readPointArray(reader, points);
          } else {
            // Deliberately not a waypoint: an <mxPoint as="offset"> is a label offset, and a
            // <mxRectangle as="alternateBounds"> is a collapsed size. Treating either as a bend
            // would invent a route the file does not describe.
            skipElement(reader);
          }
        }
        case XMLStreamConstants.END_ELEMENT, XMLStreamConstants.END_DOCUMENT -> open = false;
        default -> {
          // Whitespace inside the geometry.
        }
      }
    }
    return new MxGeometry(x, y, width, height, relative, sourcePoint, targetPoint, points);
  }

  private void readPointArray(XMLStreamReader reader, List<MxPoint> points) throws EngineException {
    boolean open = true;
    while (open) {
      int event = next(reader);
      switch (event) {
        case XMLStreamConstants.START_ELEMENT -> {
          checkAttributeBytes(reader);
          if ("mxPoint".equals(reader.getLocalName())) {
            points.add(readPoint(reader));
          } else {
            skipElement(reader);
          }
        }
        case XMLStreamConstants.END_ELEMENT, XMLStreamConstants.END_DOCUMENT -> open = false;
        default -> {
          // Whitespace between waypoints.
        }
      }
    }
  }

  /** Reads one point and consumes its element; the reader is positioned on its start tag. */
  private MxPoint readPoint(XMLStreamReader reader) throws EngineException {
    MxPoint point = new MxPoint(coordinate(reader, "x"), coordinate(reader, "y"));
    skipElement(reader);
    return point;
  }

  // ---------------------------------------------------------------- parent chains

  /**
   * Resolves every cell's depth for one page, refusing a chain that cycles, names a cell this page
   * does not hold, or runs deeper than {@link DrawioLimits#MAX_NESTING}.
   *
   * <p>Depths are memoized, so the pass is linear in the page's cells however the file orders them:
   * a document written deepest-first grows the walk, and one written root-first resolves each cell
   * in a single step. The walk is iterative and carries the ids currently on it, so a cycle is
   * detected on the step that closes it instead of being ridden until a depth counter runs out.
   */
  private void resolveParentChains(
      List<MxCell> pageCells, Map<String, MxCell> byId, Map<String, int[]> locations)
      throws EngineException {
    Map<String, Integer> depths = new HashMap<>();
    List<String> chain = new ArrayList<>();
    Set<String> onChain = new HashSet<>();
    for (MxCell start : pageCells) {
      if (depths.containsKey(start.id())) {
        continue;
      }
      chain.clear();
      onChain.clear();
      String current = start.id();
      Integer resolvedBase = null;
      while (resolvedBase == null) {
        Integer known = depths.get(current);
        if (known != null) {
          resolvedBase = known;
          break;
        }
        if (!onChain.add(current)) {
          throw syntax(
              "the parent chain forms a cycle through cell '" + echo(current) + "'",
              locations.get(current));
        }
        chain.add(current);
        if (chain.size() > DrawioLimits.MAX_NESTING + 1) {
          throw nestingExceeded();
        }
        String parent = byId.get(current).parent();
        if (parent == null || parent.isEmpty()) {
          break;
        }
        if (!byId.containsKey(parent)) {
          throw syntax(
              "cell '"
                  + echo(current)
                  + "' names parent '"
                  + echo(parent)
                  + "', which is not a cell on this page",
              locations.get(current));
        }
        current = parent;
      }

      int index = chain.size() - 1;
      int depth;
      if (resolvedBase == null) {
        // The walk stopped on a parentless cell, which is the chain's own root.
        depths.put(chain.get(index), 0);
        depth = 0;
        index--;
      } else {
        depth = resolvedBase;
      }
      for (; index >= 0; index--) {
        depth++;
        if (depth > DrawioLimits.MAX_NESTING) {
          throw nestingExceeded();
        }
        depths.put(chain.get(index), depth);
      }
    }
  }

  private static EngineException nestingExceeded() {
    return DrawioLimits.limit(
        DiagnosticCode.DRAWIO_NESTING_LIMIT_EXCEEDED,
        "a parent chain is nested more than " + DrawioLimits.MAX_NESTING + " levels deep");
  }

  // ---------------------------------------------------------------- StAX plumbing

  private XMLStreamReader open(String xml) throws EngineException {
    try {
      return SecureXml.inputFactory().createXMLStreamReader(new StringReader(xml));
    } catch (XMLStreamException malformed) {
      throw malformedXml();
    }
  }

  private String advanceToRootElement(XMLStreamReader reader) throws EngineException {
    while (true) {
      int event = next(reader);
      if (event == XMLStreamConstants.START_ELEMENT) {
        checkAttributeBytes(reader);
        return reader.getLocalName();
      }
      if (event == XMLStreamConstants.END_DOCUMENT) {
        throw malformedXml();
      }
    }
  }

  private int next(XMLStreamReader reader) throws EngineException {
    try {
      return reader.next();
    } catch (XMLStreamException malformed) {
      // The JDK's message quotes the document, including any system identifier, so it is not
      // echoed; the path carries where to look, which is what a repair needs.
      throw syntax("the diagram XML is not well-formed", locationOf(malformed));
    }
  }

  /**
   * Consumes the element the reader is positioned on, including any nested content.
   *
   * <p>The skipped element's own attributes are counted first. Probing showed why that line has to
   * be here rather than at each call site: with the check only on entry to the elements the reader
   * recognizes, an over-ceiling attribute on an unknown child of {@code <mxCell>} was accepted
   * while the same attribute on an unknown child of {@code <mxfile>} was refused. A ceiling that
   * depends on where in the tree the attacker puts the payload is not a ceiling.
   */
  private void skipElement(XMLStreamReader reader) throws EngineException {
    checkAttributeBytes(reader);
    int depth = 1;
    while (depth > 0) {
      int event = next(reader);
      if (event == XMLStreamConstants.START_ELEMENT) {
        checkAttributeBytes(reader);
        depth++;
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        depth--;
      } else if (event == XMLStreamConstants.END_DOCUMENT) {
        return;
      }
    }
  }

  private static void closeQuietly(XMLStreamReader reader) {
    try {
      reader.close();
    } catch (XMLStreamException ignored) {
      // Closing a reader over an in-memory string releases nothing a failure could leak.
    }
  }

  private static String attribute(XMLStreamReader reader, String name) {
    return reader.getAttributeValue(null, name);
  }

  private static void checkAttributeBytes(XMLStreamReader reader) throws EngineException {
    for (int index = 0; index < reader.getAttributeCount(); index++) {
      DrawioLimits.checkTokenBytes(reader.getAttributeValue(index));
    }
  }

  private double coordinate(XMLStreamReader reader, String name) throws EngineException {
    String raw = attribute(reader, name);
    if (raw == null) {
      // mxGraph's own geometry defaults an omitted coordinate to zero.
      return 0;
    }
    double parsed;
    try {
      parsed = Double.parseDouble(raw.trim());
    } catch (NumberFormatException notANumber) {
      throw syntax(
          "geometry attribute '" + name + "' is not a number: '" + echo(raw) + "'", reader);
    }
    if (!Double.isFinite(parsed)) {
      // NaN and Infinity parse happily and then poison every downstream comparison.
      throw syntax("geometry attribute '" + name + "' is not a finite number", reader);
    }
    return parsed;
  }

  // ---------------------------------------------------------------- diagnostics

  private EngineException malformedXml() {
    return syntax("the diagram XML is not well-formed", (int[]) null);
  }

  private EngineException syntax(String message, XMLStreamReader reader) {
    return syntax(message, locationOf(reader));
  }

  private EngineException syntax(String message, int[] location) {
    return EngineException.structuralFailure(
        DiagnosticCode.DRAWIO_SYNTAX_INVALID.code(), message, path(location));
  }

  /**
   * {@code "page 3 (Architecture, decompressed), line 12, column 5"}, dropping each part that does
   * not apply: the page when nothing has been entered yet, the name when the page has none, and the
   * decompressed marker for a plain page.
   */
  private String path(int[] location) {
    String lineAndColumn =
        location == null ? NO_LOCATION_LINE_COLUMN : location[0] + ", column " + location[1];
    if (currentPage <= 0) {
      return "line " + lineAndColumn;
    }
    StringBuilder path = new StringBuilder("page ").append(currentPage);
    boolean named = currentPageName != null && !currentPageName.isBlank();
    if (named || insideDecompressedPayload) {
      path.append(" (");
      if (named) {
        path.append(echo(currentPageName));
        if (insideDecompressedPayload) {
          path.append(", ");
        }
      }
      if (insideDecompressedPayload) {
        path.append("decompressed");
      }
      path.append(')');
    }
    return path.append(", line ").append(lineAndColumn).toString();
  }

  private static int[] locationOf(XMLStreamReader reader) {
    return reader.getLocation() == null
        ? null
        : new int[] {reader.getLocation().getLineNumber(), reader.getLocation().getColumnNumber()};
  }

  private static int[] locationOf(XMLStreamException malformed) {
    return malformed.getLocation() == null
        ? null
        : new int[] {
          malformed.getLocation().getLineNumber(), malformed.getLocation().getColumnNumber()
        };
  }

  /** Bounds an attacker-supplied fragment before it reaches a published diagnostic. */
  private static String echo(String value) {
    if (value == null) {
      return "";
    }
    return value.length() <= ECHO_LIMIT ? value : value.substring(0, ECHO_LIMIT) + "…";
  }
}

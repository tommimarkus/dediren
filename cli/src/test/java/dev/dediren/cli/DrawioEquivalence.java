package dev.dediren.cli;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * The declared equivalence relation {@code ≈} for a draw.io round trip, and nothing else.
 *
 * <h2>Why a relation rather than a comparison</h2>
 *
 * <p>A draw.io import is deliberately lossy, so {@code drawio_in.equals(drawio_out)} is not a
 * stricter version of the right assertion — it is a different, wrong one that can only be made
 * to pass by weakening it until it says nothing. This class instead names the facts that are
 * <em>contractually required</em> to survive, so a round-trip test asserts all of them at once and a
 * reader can audit the list. Everything absent from {@link Structure} is excluded on purpose, with
 * its reason recorded below.
 *
 * <h2>What survives, and is therefore compared</h2>
 *
 * <ul>
 *   <li><strong>Node identity and label.</strong> The set of element ids and each one's label.
 *       Identity is {@code dedirenId} where the file carries one and the mxCell id otherwise — the
 *       importer's own two identity paths.
 *   <li><strong>Edge topology.</strong> The set of {@code (id, source, target)} triples keyed by
 *       element id, plus each edge's label. Endpoints are read from {@code dedirenSource}/{@code
 *       dedirenTarget} when present and from the mxCell {@code source}/{@code target} references
 *       otherwise, which is the importer's own precedence.
 *   <li><strong>Group membership and nesting.</strong> Every element records its <em>container</em>,
 *       and a group is itself an element with a container. Comparing containers rather than member
 *       lists is what makes a group inside a group a compared fact: losing one level of
 *       nesting moves its members' container and fails the comparison.
 *   <li><strong>Element type and group role</strong>, as {@code dedirenType} / {@code
 *       dedirenGroupRole} / {@code dedirenSemanticSourceId}. These exist only when the input was
 *       itself exported by Dediren, so they are compared through {@link #withTypedIdentity} on the
 *       Dediren-authored round trip and dropped by {@link #structureOnly} on the foreign one, where
 *       their absence is asserted separately as documented degradation rather than skipped.
 * </ul>
 *
 * <h2>What is excluded, and why each exclusion is not a fudge</h2>
 *
 * <ul>
 *   <li><strong>Geometry</strong> ({@code mxGeometry} x/y/width/height and edge waypoints).
 *       {@code schemas/model.schema.json} {@code $defs.sourceNode} is {@code additionalProperties:
 *       false} and declares no coordinates, so an imported model cannot carry them and every import
 *       lane re-lays the page out with ELK. Different coordinates in {@code drawio_out} are the
 *       contract working, not a defect.
 *   <li><strong>Presentation style</strong> (the mxCell {@code style} string). Discarded on import
 *       and reported as {@code DEDIREN_DRAWIO_HINT_IGNORED}; the styles in {@code drawio_out} are
 *       the exporter's own, computed from the model, and owe the input nothing.
 *   <li><strong>mxCell ids.</strong> draw.io reassigns them freely while a user edits, which is
 *       the stated reason the identity contract rides {@code dedirenId}. They serve as identity only on
 *       the foreign path, where nothing better exists.
 *   <li><strong>Layers.</strong> Flattened on import by design — a layer is z-order, not
 *       containment. The container is therefore the nearest <em>vertex</em> ancestor, and structural
 *       cells are walked through here exactly as the importer walks them.
 *   <li><strong>Hidden cells.</strong> Skipped on import by design, so they cannot come back.
 *       The per-page {@code dediren.view} metadata cell is hidden for the same reason and is metadata,
 *       not an element.
 *   <li><strong>Dangling edges.</strong> Dropped with a warning by design, so an input carrying one
 *       is not a round-trip input at all.
 *   <li><strong>Page name and view label.</strong> The export policy's {@code diagram_name} names
 *       the emitted page, so a foreign page name is replaced rather than preserved (verified: a page
 *       named {@code Platform} returns as the policy's {@code Main}).
 *   <li><strong>Document order.</strong> Compared as sets and maps: the exporter writes groups
 *       before nodes so a parent precedes its children, and ELK chooses its own ordering.
 * </ul>
 */
final class DrawioEquivalence {

  private DrawioEquivalence() {}

  /** The three {@code <br>} spellings the importer normalizes to a newline, matched identically. */
  private static final Pattern SAFE_BR = Pattern.compile("(?i)<br(?:/| /)?>");

  private static final String VIEW_TYPE = "dediren.view";

  /** One compared vertex — node or group — and every fact about it that must survive. */
  record VertexFacts(
      String label, String container, String type, String groupRole, String semanticSourceId) {

    VertexFacts structureOnly() {
      return new VertexFacts(label, container, null, null, null);
    }
  }

  /** One compared connector, keyed by element id. */
  record EdgeFacts(String id, String source, String target, String type, String label) {

    EdgeFacts structureOnly() {
      return new EdgeFacts(id, source, target, null, label);
    }
  }

  /**
   * The compared projection of one {@code .drawio} document: two documents are equivalent under
   * this relation exactly when their {@link Structure} values are equal.
   */
  record Structure(
      Map<String, VertexFacts> nodes, Map<String, VertexFacts> groups, Set<EdgeFacts> edges) {

    /**
     * The relation minus the Dediren identity vocabulary, for the foreign round trip: a
     * hand-authored input declares no type, group role, or semantic source, so comparing them
     * against a Dediren-authored output would compare {@code null} to the degradation instead of
     * asserting the degradation. The foreign test asserts that degradation positively and directly.
     */
    Structure structureOnly() {
      Map<String, VertexFacts> plainNodes = new LinkedHashMap<>();
      nodes.forEach((id, facts) -> plainNodes.put(id, facts.structureOnly()));
      Map<String, VertexFacts> plainGroups = new LinkedHashMap<>();
      groups.forEach((id, facts) -> plainGroups.put(id, facts.structureOnly()));
      Set<EdgeFacts> plainEdges = new LinkedHashSet<>();
      edges.forEach(edge -> plainEdges.add(edge.structureOnly()));
      return new Structure(plainNodes, plainGroups, plainEdges);
    }
  }

  /** Projects one {@code .drawio} document onto the compared facts, identity vocabulary included. */
  static Structure withTypedIdentity(String drawio) throws Exception {
    Document document = parse(drawio);

    Map<String, Element> cellById = new LinkedHashMap<>();
    Map<String, Element> wrapperByCellId = new LinkedHashMap<>();
    List<Element> cells = elements(document, "mxCell");

    for (Element cell : cells) {
      Element wrapper = objectWrapper(cell);
      String id = wrapper != null ? wrapper.getAttribute("id") : cell.getAttribute("id");
      cellById.put(id, cell);
      if (wrapper != null) {
        wrapperByCellId.put(id, wrapper);
      }
    }

    // Identity first: an edge endpoint and a container both name a cell that may be declared later
    // in the file, so nothing can be resolved during a single pass.
    Map<String, String> identityByCellId = new LinkedHashMap<>();
    Set<String> containerCellIds = new LinkedHashSet<>();
    for (Element cell : cells) {
      String cellId = cellId(cell);
      if (skipped(cell, cellId, cellById, wrapperByCellId)) {
        continue;
      }
      identityByCellId.put(cellId, identity(cellId, wrapperByCellId));
      String parent = cell.getAttribute("parent");
      if (isVertex(cellById.get(parent))) {
        containerCellIds.add(parent);
      }
    }

    Map<String, VertexFacts> nodes = new LinkedHashMap<>();
    Map<String, VertexFacts> groups = new LinkedHashMap<>();
    Set<EdgeFacts> edges = new LinkedHashSet<>();

    for (Element cell : cells) {
      String cellId = cellId(cell);
      if (skipped(cell, cellId, cellById, wrapperByCellId)) {
        continue;
      }
      Element wrapper = wrapperByCellId.get(cellId);
      String identity = identityByCellId.get(cellId);
      String label = label(cell, wrapper);
      String type = attribute(wrapper, "dedirenType");

      if (isVertex(cell)) {
        VertexFacts facts =
            new VertexFacts(
                label,
                containerIdentity(cell, cellById, identityByCellId),
                type,
                attribute(wrapper, "dedirenGroupRole"),
                attribute(wrapper, "dedirenSemanticSourceId"));
        (containerCellIds.contains(cellId) ? groups : nodes).put(identity, facts);
        continue;
      }

      edges.add(
          new EdgeFacts(
              identity,
              endpoint(cell, wrapper, "dedirenSource", "source", identityByCellId),
              endpoint(cell, wrapper, "dedirenTarget", "target", identityByCellId),
              type,
              label));
    }
    return new Structure(nodes, groups, edges);
  }

  /** {@link #withTypedIdentity} with the Dediren identity vocabulary dropped. */
  static Structure structureOnly(String drawio) throws Exception {
    return withTypedIdentity(drawio).structureOnly();
  }

  /**
   * Why two {@code .drawio} documents are not byte-identical, in words.
   *
   * <p>The fixed-point assertion below is byte equality, which is the right bar and the worst
   * failure message: "expected 40kB, got 40kB" gets a test deleted rather than diagnosed. This
   * names the first structural difference this relation can see, and falls back to the first
   * differing line when the difference is one the relation excludes on purpose — geometry, style,
   * or attribute order. Returns {@code ""} when the two are byte-identical.
   */
  static String explainDifference(String left, String right) throws Exception {
    if (left.equals(right)) {
      return "";
    }
    Structure a = withTypedIdentity(left);
    Structure b = withTypedIdentity(right);
    List<String> found = new ArrayList<>();
    describeVertices("node", a.nodes(), b.nodes(), found);
    describeVertices("group", a.groups(), b.groups(), found);
    Set<EdgeFacts> onlyLeft = new LinkedHashSet<>(a.edges());
    onlyLeft.removeAll(b.edges());
    Set<EdgeFacts> onlyRight = new LinkedHashSet<>(b.edges());
    onlyRight.removeAll(a.edges());
    onlyLeft.forEach(edge -> found.add("edge only in the first document: " + edge));
    onlyRight.forEach(edge -> found.add("edge only in the second document: " + edge));
    if (!found.isEmpty()) {
      return "structural difference: " + String.join("; ", found.subList(0, Math.min(4, found.size())));
    }
    return "structurally equal, so the difference is one the relation excludes (geometry, style,"
        + " or document order): "
        + firstDifferingLine(left, right);
  }

  private static void describeVertices(
      String kind, Map<String, VertexFacts> left, Map<String, VertexFacts> right,
      List<String> found) {
    left.forEach(
        (id, facts) -> {
          VertexFacts other = right.get(id);
          if (other == null) {
            found.add(kind + " '" + id + "' only in the first document");
          } else if (!facts.equals(other)) {
            found.add(kind + " '" + id + "': " + facts + " vs " + other);
          }
        });
    right.keySet().stream()
        .filter(id -> !left.containsKey(id))
        .forEach(id -> found.add(kind + " '" + id + "' only in the second document"));
  }

  private static String firstDifferingLine(String left, String right) {
    String[] a = left.split("\n", -1);
    String[] b = right.split("\n", -1);
    for (int line = 0; line < Math.max(a.length, b.length); line++) {
      String one = line < a.length ? a[line] : "<end of document>";
      String two = line < b.length ? b[line] : "<end of document>";
      if (!one.equals(two)) {
        return "line " + (line + 1) + "\n  first:  " + one.strip() + "\n  second: " + two.strip();
      }
    }
    return "no differing line, so the documents differ only in their trailing bytes";
  }

  // ------------------------------------------------------------------ projection helpers

  /**
   * Skipped: mxGraph's two structural cells, the {@code dediren.view} metadata cell, and any cell
   * that is hidden or inherits invisibility from an ancestor — the importer's own inherited
   * visibility rule.
   */
  private static boolean skipped(
      Element cell,
      String cellId,
      Map<String, Element> cellById,
      Map<String, Element> wrapperByCellId) {
    if (!isVertex(cell) && !isEdge(cell)) {
      return true;
    }
    if (VIEW_TYPE.equals(attribute(wrapperByCellId.get(cellId), "dedirenType"))) {
      return true;
    }
    Element current = cell;
    while (current != null) {
      if ("0".equals(current.getAttribute("visible"))) {
        return true;
      }
      String parent = current.getAttribute("parent");
      current = parent.isEmpty() ? null : cellById.get(parent);
    }
    return false;
  }

  /** The nearest vertex ancestor's identity, walking through layers and the structural root. */
  private static String containerIdentity(
      Element cell, Map<String, Element> cellById, Map<String, String> identityByCellId) {
    String parent = cell.getAttribute("parent");
    while (!parent.isEmpty()) {
      Element candidate = cellById.get(parent);
      if (candidate == null || isEdge(candidate)) {
        return null;
      }
      if (isVertex(candidate)) {
        return identityByCellId.get(cellId(candidate));
      }
      parent = candidate.getAttribute("parent");
    }
    return null;
  }

  /** {@code dedirenId} where the file carries one; the mxCell id otherwise. */
  private static String identity(String cellId, Map<String, Element> wrapperByCellId) {
    String declared = attribute(wrapperByCellId.get(cellId), "dedirenId");
    return declared == null ? cellId : declared;
  }

  private static String endpoint(
      Element cell,
      Element wrapper,
      String dedirenAttribute,
      String cellAttribute,
      Map<String, String> identityByCellId) {
    String declared = attribute(wrapper, dedirenAttribute);
    if (declared != null) {
      return declared;
    }
    String reference = cell.getAttribute(cellAttribute);
    return reference.isEmpty() ? null : identityByCellId.get(reference);
  }

  /**
   * The cell's own value wins, then the wrapper's {@code label}: mxGraph moves a labelled cell's
   * value onto its {@code <object>} wrapper. {@code <br>} is normalized to a newline exactly as the
   * importer normalizes it, so a label written as markup and a label decoded from it agree — and a
   * writer that mangles either half stops agreeing.
   */
  private static String label(Element cell, Element wrapper) {
    String value = cell.getAttribute("value");
    if (value.isEmpty() && wrapper != null) {
      value = wrapper.getAttribute("label");
    }
    return SAFE_BR.matcher(value).replaceAll("\n");
  }

  /** mxGraph moves a cell's id onto its {@code <object>} wrapper once the cell gains user data. */
  private static String cellId(Element cell) {
    Element wrapper = objectWrapper(cell);
    return wrapper != null ? wrapper.getAttribute("id") : cell.getAttribute("id");
  }

  private static Element objectWrapper(Element cell) {
    Node parent = cell.getParentNode();
    if (parent instanceof Element element && "object".equals(element.getTagName())) {
      return element;
    }
    return null;
  }

  private static boolean isVertex(Element cell) {
    return cell != null && "1".equals(cell.getAttribute("vertex"));
  }

  private static boolean isEdge(Element cell) {
    return cell != null && "1".equals(cell.getAttribute("edge"));
  }

  private static String attribute(Element element, String name) {
    return element == null || !element.hasAttribute(name) ? null : element.getAttribute(name);
  }

  private static List<Element> elements(Document document, String tag) {
    var found = document.getElementsByTagName(tag);
    List<Element> elements = new ArrayList<>();
    for (int index = 0; index < found.getLength(); index++) {
      elements.add((Element) found.item(index));
    }
    return elements;
  }

  private static Document parse(String drawio) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory.newDocumentBuilder().parse(new InputSource(new StringReader(drawio)));
  }
}

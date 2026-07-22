package dev.dediren.plugins.umlxmi.write.diagram;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.attr;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.plugins.umlxmi.build.IdentifierMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits an OMG UML Diagram Interchange (UMLDI) diagram for one laid-out view. It serializes the
 * ELK-computed geometry as a second derived syntax for the same layout result — not authored
 * geometry and not a post-layout rewrite — so it respects the ELK-first rule: each {@link
 * LaidOutNode} becomes a {@code umldi:UMLShape} carrying a {@code dc:Bounds}, each {@link
 * LaidOutEdge} a {@code umldi:UMLEdge} carrying {@code di:waypoint}s, both referencing the already
 * emitted model element's {@code xmi:id}. Mirrors the ArchiMate-OEF {@code writeViewBody} / {@code
 * writeConnectionGeometry} geometry-emission pattern.
 *
 * <p><strong>Provisional dialect.</strong> The OMG DD/DI namespace URIs and element vocabulary
 * below are pinned by spec knowledge, not by real-tool render verification (that probe is deferred;
 * see the UMLDI plan). They are isolated as named constants so a later Eclipse Papyrus / Sparx EA
 * import probe can correct them in one place, and a GMF-notation companion dialect can be added
 * separately. Shape/edge/diagram {@code xmi:id}s are minted from the caller's shared {@link
 * IdentifierMap} so they stay globally unique across the whole {@code xmi:XMI} document.
 */
public final class DiagramWriter {
  private DiagramWriter() {}

  /** OMG Diagram Definition (DD) shared packages plus UML Diagram Interchange. Provisional. */
  public static final String UMLDI_NS = "http://www.omg.org/spec/UML/20161101/UMLDI";

  public static final String DI_NS = "http://www.omg.org/spec/DD/20100524/DI";
  public static final String DC_NS = "http://www.omg.org/spec/DD/20100524/DC";

  /** The diagram element's own identity within the aggregate document. */
  public record DiagramIdentity(String identifier, String name) {}

  /**
   * Appends one {@code umldi:UMLDiagram} for {@code layout}. {@code elementXmiIds} / {@code
   * relationshipXmiIds} map a source id to the already-emitted model element's {@code xmi:id}. A
   * node whose source element is not in scope (for example a purely visual grouping) is skipped,
   * and an edge is skipped when either endpoint shape or its relationship is absent — the diagram
   * never dangles a reference to a model element or shape the document does not contain.
   */
  public static void writeUmlDiagram(
      StringBuilder xml,
      LayoutResult layout,
      DiagramIdentity identity,
      IdentifierMap ids,
      Map<String, String> elementXmiIds,
      Map<String, String> relationshipXmiIds) {
    Map<String, String> shapeIds = new HashMap<>();
    for (LaidOutNode node : layout.nodes()) {
      if (elementXmiIds.containsKey(node.sourceId())) {
        shapeIds.put(node.id(), ids.xmiId("di-shape-" + layout.viewId() + "-" + node.id()));
      }
    }

    xml.append("<umldi:UMLDiagram xmi:id=\"")
        .append(attr(identity.identifier()))
        .append("\" name=\"")
        .append(attr(identity.name()))
        .append("\">");
    for (LaidOutNode node : layout.nodes()) {
      String shapeId = shapeIds.get(node.id());
      String modelElement = elementXmiIds.get(node.sourceId());
      if (shapeId == null || modelElement == null) {
        continue;
      }
      xml.append("<umldi:UMLShape xmi:id=\"")
          .append(attr(shapeId))
          .append("\" modelElement=\"")
          .append(attr(modelElement))
          .append("\">");
      xml.append("<dc:Bounds x=\"")
          .append(formatNumber(node.x()))
          .append("\" y=\"")
          .append(formatNumber(node.y()))
          .append("\" width=\"")
          .append(formatNumber(node.width()))
          .append("\" height=\"")
          .append(formatNumber(node.height()))
          .append("\"/>");
      xml.append("</umldi:UMLShape>");
    }
    for (LaidOutEdge edge : layout.edges()) {
      String edgeModelElement = relationshipXmiIds.get(edge.sourceId());
      String sourceShape = shapeIds.get(edge.source());
      String targetShape = shapeIds.get(edge.target());
      if (edgeModelElement == null || sourceShape == null || targetShape == null) {
        continue;
      }
      xml.append("<umldi:UMLEdge xmi:id=\"")
          .append(attr(ids.xmiId("di-edge-" + layout.viewId() + "-" + edge.id())))
          .append("\" modelElement=\"")
          .append(attr(edgeModelElement))
          .append("\" source=\"")
          .append(attr(sourceShape))
          .append("\" target=\"")
          .append(attr(targetShape))
          .append("\">");
      writeWaypoints(xml, edge.points());
      xml.append("</umldi:UMLEdge>");
    }
    xml.append("</umldi:UMLDiagram>");
  }

  private static void writeWaypoints(StringBuilder xml, List<Point> points) {
    for (Point point : points) {
      xml.append("<di:waypoint x=\"")
          .append(formatNumber(point.x()))
          .append("\" y=\"")
          .append(formatNumber(point.y()))
          .append("\"/>");
    }
  }

  /**
   * Integer-valued coordinates serialize without a decimal tail, matching the OEF geometry writer.
   */
  static String formatNumber(double value) {
    if (Double.isFinite(value) && value == Math.rint(value)) {
      return Long.toString((long) value);
    }
    return Double.toString(value);
  }
}

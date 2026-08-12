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
 * <p><strong>Provisional dialect — in one sense only.</strong> The namespace URIs and element
 * vocabulary below are verified against the OMG's published schemas (see the next paragraph); what
 * is unverified is whether a real UML tool <em>renders</em> the result, which is the deferred
 * Eclipse Papyrus / Sparx EA import probe. They stay isolated as named constants so that probe can
 * correct them in one place if it ever contradicts the schemas, and so a GMF-notation companion
 * dialect can be added separately. Shape/edge/diagram {@code xmi:id}s are minted from the caller's
 * shared {@link IdentifierMap} so they stay globally unique across the whole {@code xmi:XMI}
 * document.
 *
 * <p><strong>The {@code dc:}/{@code di:} naming and namespace dates are settled and must not be
 * "modernised".</strong> The 2026-08-12 conformance register raised two hypotheses here ({@code
 * UML-XMI-14}, {@code UML-XMI-15}); both were checked against the OMG's published schemas and both
 * are wrong. Recorded because acting on either would break interoperability:
 *
 * <ul>
 *   <li>The apparent mixed convention — {@code dc:Bounds} capitalised like a type, {@code
 *       di:waypoint} lowercase like a property — is exactly what the DD schemas specify. {@code
 *       DC.xsd} declares a <em>global</em> element {@code <xsd:element name="Bounds"
 *       type="dc:Bounds"/>}, named after its type; {@code waypoint} is a <em>local</em> element
 *       inside {@code Edge}, typed {@code dc:Point}, and {@code DI.xsd} sets {@code
 *       elementFormDefault="qualified"}, so it carries the {@code di:} prefix. Both spellings are
 *       correct, for different reasons.
 *   <li>The {@code 20100524} date is the {@code targetNamespace} of those serialization schemas,
 *       not a stale DD 1.0 reference. DD 1.1 does exist and stamps its <em>metamodel</em> XMI files
 *       {@code 20131001}, but that stamp never became an XML namespace: every deployed DD-based
 *       dialect, BPMN DI included, serializes into {@code .../DD/20100524/}. Changing these
 *       constants to {@code 20131001} would produce documents no DD-aware tool can read.
 * </ul>
 *
 * <p>What remains genuinely unverified is whether a UML tool <em>renders</em> the result — the
 * Papyrus / Sparx EA import probe in §12. That is a different question from whether the dialect is
 * spelled correctly, which it is.
 */
public final class DiagramWriter {
  private DiagramWriter() {}

  /**
   * OMG Diagram Definition (DD) shared packages plus UML Diagram Interchange.
   *
   * <p>The {@code 20100524} date is deliberate and is pinned by {@code
   * DiagramWriterConformanceTest} — it is the {@code targetNamespace} of the DD serialization
   * schemas, not a stale reference to DD 1.0. See the class javadoc before changing it.
   */
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

    // UML 2.5.1 Annex B is normative and IS the UMLDI metamodel. umldi:UMLDiagram is an ABSTRACT
    // class there (B.7.13); a conforming importer cannot instantiate it. UMLClassDiagram is the
    // concrete kind for the class family, which is the only family this lane emits DI for.
    //
    // isFrame defaults to true, and B's no-frame-no-heading invariant then makes a heading:UMLLabel
    // mandatory -- one is never emitted, so every diagram violated it. Declaring isFrame="false"
    // satisfies the invariant honestly rather than inventing a heading the model does not have.
    xml.append("<umldi:UMLClassDiagram xmi:id=\"")
        .append(attr(identity.identifier()))
        .append("\" name=\"")
        .append(attr(identity.name()))
        .append("\" isFrame=\"false\">");
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
    xml.append("</umldi:UMLClassDiagram>");
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

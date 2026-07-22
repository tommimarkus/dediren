package dev.dediren.plugins.umlxmi.write.diagram;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.XMI_NS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.plugins.umlxmi.build.IdentifierMap;
import dev.dediren.plugins.umlxmi.schema.SchemaValidation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Phase-0 executable conformance gate for the OMG UMLDI {@link DiagramWriter}: it proves the
 * emitted diagram is well-formed, its {@code xmi:id}s are unique, every shape/edge references a
 * model element and endpoint shapes that exist in the document, and the geometry equals the layout
 * it derives from. It does <strong>not</strong> assert that the DI renders in a real UML tool —
 * that render probe is a deferred follow-up (see the UMLDI plan), so the dialect stays provisional.
 */
final class DiagramWriterConformanceTest {

  private static final Map<String, String> ELEMENT_IDS =
      Map.of("class-order", "id-class-order", "class-order-line", "id-class-order-line");
  private static final Map<String, String> RELATIONSHIP_IDS =
      Map.of("order-has-lines", "id-order-has-lines", "order-status-dependency", "id-order-status");

  private static final List<Point> ORDER_HAS_LINES_POINTS =
      List.of(
          new Point(185.5, 165.0),
          new Point(185.5, 213.0),
          new Point(518.0, 213.0),
          new Point(518.0, 261.0));

  private LayoutResult sampleLayout() {
    var order =
        new LaidOutNode(
            "class-order", "class-order", "class-order", 44.0, 44.0, 220.0, 120.0, "Order");
    var orderLine =
        new LaidOutNode(
            "class-order-line",
            "class-order-line",
            "class-order-line",
            408.0,
            262.0,
            220.0,
            120.0,
            "OrderLine");
    // Visual-only node: no entry in ELEMENT_IDS, so it must be skipped (no shape emitted).
    var visualOnly =
        new LaidOutNode("visual-note", "visual-note", "visual-note", 0.0, 0.0, 100.0, 40.0, "Note");
    var hasLines =
        new LaidOutEdge(
            "order-has-lines",
            "class-order",
            "class-order-line",
            "order-has-lines",
            "order-has-lines",
            List.of(),
            ORDER_HAS_LINES_POINTS,
            "lines");
    // Edge to the skipped visual node: no target shape, so it must be skipped.
    var danglingEdge =
        new LaidOutEdge(
            "dangling",
            "class-order",
            "visual-note",
            "order-status-dependency",
            "order-status-dependency",
            List.of(),
            List.of(new Point(1.0, 2.0)),
            "uses");
    return new LayoutResult(
        "layout-result.schema.v2",
        "class-view",
        List.of(order, orderLine, visualOnly),
        List.of(hasLines, danglingEdge),
        List.of(),
        List.of());
  }

  private Document renderDiagram() throws Exception {
    var xml = new StringBuilder();
    xml.append("<xmi:XMI xmlns:xmi=\"")
        .append(XMI_NS)
        .append("\" xmlns:umldi=\"")
        .append(DiagramWriter.UMLDI_NS)
        .append("\" xmlns:di=\"")
        .append(DiagramWriter.DI_NS)
        .append("\" xmlns:dc=\"")
        .append(DiagramWriter.DC_NS)
        .append("\">");
    DiagramWriter.writeUmlDiagram(
        xml,
        sampleLayout(),
        new DiagramWriter.DiagramIdentity("id-diagram-class-view", "class-view"),
        new IdentifierMap("id-model"),
        ELEMENT_IDS,
        RELATIONSHIP_IDS);
    xml.append("</xmi:XMI>");
    // Well-formedness: the hardened, namespace-aware builder parses the document or the test fails.
    return SchemaValidation.secureXmiDocumentBuilderFactory()
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.toString().getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void emitsOneShapePerModelBackedNodeAndSkipsVisualOnly() throws Exception {
    NodeList shapes = renderDiagram().getElementsByTagNameNS(DiagramWriter.UMLDI_NS, "UMLShape");
    Set<String> modelElements = new HashSet<>();
    for (int i = 0; i < shapes.getLength(); i++) {
      modelElements.add(((Element) shapes.item(i)).getAttribute("modelElement"));
    }
    // Two model-backed nodes become shapes; the visual-only node is skipped.
    assertThat(shapes.getLength()).isEqualTo(2);
    assertThat(modelElements).containsExactlyInAnyOrder("id-class-order", "id-class-order-line");
  }

  @Test
  void skipsEdgesWithoutBothEndpointShapes() throws Exception {
    NodeList edges = renderDiagram().getElementsByTagNameNS(DiagramWriter.UMLDI_NS, "UMLEdge");
    assertThat(edges.getLength()).isEqualTo(1);
    Element edge = (Element) edges.item(0);
    assertThat(edge.getAttribute("modelElement")).isEqualTo("id-order-has-lines");
  }

  @Test
  void shapeBoundsEqualTheLaidOutNodeGeometry() throws Exception {
    Element order = shapeFor(renderDiagram(), "id-class-order");
    Element bounds = (Element) order.getElementsByTagNameNS(DiagramWriter.DC_NS, "Bounds").item(0);
    assertThat(bounds.getAttribute("x")).isEqualTo("44");
    assertThat(bounds.getAttribute("y")).isEqualTo("44");
    assertThat(bounds.getAttribute("width")).isEqualTo("220");
    assertThat(bounds.getAttribute("height")).isEqualTo("120");
  }

  @Test
  void edgeWaypointsEqualTheLaidOutEdgeGeometry() throws Exception {
    NodeList edges = renderDiagram().getElementsByTagNameNS(DiagramWriter.UMLDI_NS, "UMLEdge");
    NodeList waypoints =
        ((Element) edges.item(0)).getElementsByTagNameNS(DiagramWriter.DI_NS, "waypoint");
    assertThat(waypoints.getLength()).isEqualTo(ORDER_HAS_LINES_POINTS.size());
    for (int i = 0; i < waypoints.getLength(); i++) {
      Element waypoint = (Element) waypoints.item(i);
      Point expected = ORDER_HAS_LINES_POINTS.get(i);
      assertThat(Double.parseDouble(waypoint.getAttribute("x"))).isEqualTo(expected.x());
      assertThat(Double.parseDouble(waypoint.getAttribute("y"))).isEqualTo(expected.y());
    }
  }

  @Test
  void everyXmiIdIsUnique() throws Exception {
    NodeList all = renderDiagram().getElementsByTagName("*");
    var seen = new HashSet<String>();
    for (int i = 0; i < all.getLength(); i++) {
      String id = ((Element) all.item(i)).getAttributeNS(XMI_NS, "id");
      if (!id.isEmpty()) {
        assertThat(seen.add(id)).as("duplicate xmi:id %s", id).isTrue();
      }
    }
  }

  @Test
  void edgeEndpointsReferenceShapesThatExistInTheDocument() throws Exception {
    Document document = renderDiagram();
    Set<String> shapeIds = new HashSet<>();
    NodeList shapes = document.getElementsByTagNameNS(DiagramWriter.UMLDI_NS, "UMLShape");
    for (int i = 0; i < shapes.getLength(); i++) {
      shapeIds.add(((Element) shapes.item(i)).getAttributeNS(XMI_NS, "id"));
    }
    NodeList edges = document.getElementsByTagNameNS(DiagramWriter.UMLDI_NS, "UMLEdge");
    for (int i = 0; i < edges.getLength(); i++) {
      Element edge = (Element) edges.item(i);
      assertThat(shapeIds).contains(edge.getAttribute("source"), edge.getAttribute("target"));
    }
  }

  private static Element shapeFor(Document document, String modelElement) {
    NodeList shapes = document.getElementsByTagNameNS(DiagramWriter.UMLDI_NS, "UMLShape");
    for (int i = 0; i < shapes.getLength(); i++) {
      Element shape = (Element) shapes.item(i);
      if (modelElement.equals(shape.getAttribute("modelElement"))) {
        return shape;
      }
    }
    throw new AssertionError("no UMLShape for modelElement " + modelElement);
  }
}

package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.plugins.render.svg.Svg;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.font.FontRenderContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Parses an emitted SVG back and audits it against the render-layer invariants that the
 * layout-geometry quality signals ({@code LayoutQuality}) structurally cannot see: well-formedness,
 * id uniqueness, {@code url(#…)} reference resolution, finite coordinates, viewBox containment, and
 * the correctness of the {@code textLength} width pins. Every {@code LayoutQuality} signal inspects
 * the abstract {@code LayoutResult} the renderer laid itself out from; nothing ever reads the
 * emitted SVG back and checks it against reality. This helper closes that gap.
 *
 * <p>JDK XML + Java2D only — no new dependency. Test-scoped.
 */
final class SvgAudit {
  private SvgAudit() {}

  private static final Pattern URL_REF = Pattern.compile("url\\(#([^)\\s]+)\\)");
  private static final FontRenderContext FONT_RENDER_CONTEXT =
      new FontRenderContext(null, true, true);

  // The oracle for assertTextWidthPinsMatchRealFont: a bundled, Arial-metric-compatible font
  // (Liberation Sans, SIL OFL 1.1) measured at a 1000-unit em. Bundling it — instead of asking the
  // JVM for the logical Font.SANS_SERIF — is what makes this check hermetic: the logical font
  // resolves to whatever physical sans-serif the host installs (DejaVu on one runner, something
  // wider on another), so the same pin measured "close enough" on the author's box but 16-18% wide
  // on a stock ubuntu CI runner. Liberation Sans is metric-compatible with Arial, the family the
  // renderer's AFM advance table (Svg#estimateTextWidth) is built on, so the measured/pinned ratio
  // now sits at ~1.0 on every machine and the tolerance can be tight.
  private static final Font MEASURING_FONT = loadMeasuringFont();

  private static Font loadMeasuringFont() {
    try (InputStream ttf =
        SvgAudit.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
      if (ttf == null) {
        throw new IllegalStateException(
            "bundled measuring font /fonts/LiberationSans-Regular.ttf missing from test resources");
      }
      return Font.createFont(Font.TRUETYPE_FONT, ttf).deriveFont(1000f);
    } catch (FontFormatException e) {
      throw new IllegalStateException("bundled measuring font is not a valid TrueType font", e);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read bundled measuring font", e);
    }
  }

  // Attributes whose value is a single number and must therefore be finite.
  private static final Set<String> NUMERIC_ATTRS =
      Set.of(
          "x",
          "y",
          "width",
          "height",
          "cx",
          "cy",
          "r",
          "rx",
          "ry",
          "x1",
          "y1",
          "x2",
          "y2",
          "refX",
          "refY",
          "font-size",
          "textLength");

  static Document parse(String svg) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      // The audit consumes trusted plugin output, but must never itself be an XXE vector.
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      return builder.parse(new InputSource(new StringReader(svg)));
    } catch (Exception failure) {
      throw new AssertionError(
          "emitted SVG is not well-formed XML: " + failure.getMessage(), failure);
    }
  }

  /** Everything deterministic and environment-independent. */
  static void auditStructure(String svg) {
    Document document = parse(svg);
    assertUniqueIds(document);
    assertReferencesResolve(document);
    assertFiniteGeometry(document);
    assertGeometryWithinViewBox(document, 1.0);
    assertTextWidthPinsSelfConsistent(document, 0.03);
  }

  static void assertUniqueIds(Document document) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (Element element : elements(document)) {
      String id = element.getAttribute("id");
      if (!id.isEmpty()) {
        counts.merge(id, 1, Integer::sum);
      }
    }
    List<String> duplicates =
        counts.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    assertThat(duplicates)
        .as("duplicate SVG element ids (a duplicate marker id makes url(#…) resolution ambiguous)")
        .isEmpty();
  }

  static void assertReferencesResolve(Document document) {
    Set<String> ids = new HashSet<>();
    for (Element element : elements(document)) {
      String id = element.getAttribute("id");
      if (!id.isEmpty()) {
        ids.add(id);
      }
    }
    List<String> dangling = new ArrayList<>();
    for (Element element : elements(document)) {
      NamedNodeMap attributes = element.getAttributes();
      for (int index = 0; index < attributes.getLength(); index++) {
        Matcher matcher = URL_REF.matcher(attributes.item(index).getNodeValue());
        while (matcher.find()) {
          if (!ids.contains(matcher.group(1))) {
            dangling.add(
                element.getTagName()
                    + " "
                    + attributes.item(index).getNodeName()
                    + "=url(#"
                    + matcher.group(1)
                    + ")");
          }
        }
      }
    }
    assertThat(dangling)
        .as("url(#…) references (e.g. marker-end) with no matching element id")
        .isEmpty();
  }

  static void assertFiniteGeometry(Document document) {
    List<String> bad = new ArrayList<>();
    for (Element element : elements(document)) {
      NamedNodeMap attributes = element.getAttributes();
      for (int index = 0; index < attributes.getLength(); index++) {
        Node attribute = attributes.item(index);
        if (!NUMERIC_ATTRS.contains(attribute.getNodeName())) {
          continue;
        }
        String value = attribute.getNodeValue();
        try {
          if (!Double.isFinite(Double.parseDouble(value))) {
            bad.add(element.getTagName() + "@" + attribute.getNodeName() + "=" + value);
          }
        } catch (NumberFormatException malformed) {
          bad.add(element.getTagName() + "@" + attribute.getNodeName() + "=" + value);
        }
      }
    }
    assertThat(bad).as("non-finite or malformed numeric geometry attributes").isEmpty();
  }

  static void assertGeometryWithinViewBox(Document document, double tolerance) {
    double[] viewBox = viewBox(document);
    List<String> escapes = new ArrayList<>();
    for (Element element : elements(document)) {
      if (hasAncestor(element, "marker")) {
        // Marker children are drawn in the marker's own coordinate space, not the document viewBox.
        continue;
      }
      double[] box = boundingBox(element);
      if (box == null) {
        continue;
      }
      if (box[0] < viewBox[0] - tolerance
          || box[1] < viewBox[1] - tolerance
          || box[2] > viewBox[2] + tolerance
          || box[3] > viewBox[3] + tolerance) {
        escapes.add(
            element.getTagName()
                + " ["
                + format(box)
                + "] outside viewBox ["
                + format(viewBox)
                + "]");
      }
    }
    assertThat(escapes)
        .as("shapes painted outside the emitted viewBox (clipped at the diagram edge)")
        .isEmpty();
  }

  /**
   * The {@code textLength} width pin must still equal the renderer's own {@code estimateTextWidth}
   * for the string it pins, to within the rounding the emitter applies to font-size. Guards against
   * the pin drifting away from the width the layout reserved (which re-introduces
   * overflow/squeeze).
   */
  static void assertTextWidthPinsSelfConsistent(Document document, double relativeTolerance) {
    List<String> drift = new ArrayList<>();
    for (Element element : elements(document)) {
      String pinned = element.getAttribute("textLength");
      if (pinned.isEmpty()) {
        continue;
      }
      double declared = Double.parseDouble(pinned);
      double estimate = Svg.estimateTextWidth(directText(element), effectiveFontSize(element));
      if (declared <= 0 || Math.abs(declared - estimate) / declared > relativeTolerance) {
        drift.add(element.getTagName() + " textLength=" + declared + " estimate=" + estimate);
      }
    }
    assertThat(drift)
        .as("textLength pins that no longer match the renderer's own width estimate")
        .isEmpty();
  }

  /**
   * The pinned {@code textLength} must be close to what a real font engine measures for the same
   * string, or {@code lengthAdjust="spacing"} visibly squeezes or letterspaces the glyphs. Measured
   * with the bundled {@link #MEASURING_FONT} (Liberation Sans, Arial-metric-compatible), so the
   * result is identical on every machine. Because that font shares the metric family the estimate's
   * AFM table is built on, ASCII pins land within a fraction of a percent of {@code 1.0} and the
   * band is snug — but it keeps teeth: a wide glyph the estimate scores at the 0.6em fallback but
   * the font renders at a full em (e.g. an em dash or {@code ™}) still overshoots the ceiling, and
   * a grossly over-reserved pin still undershoots the floor. The floor stays loose enough to
   * tolerate that 0.6em approximation for narrow non-ASCII Latin the font can display.
   */
  static void assertTextWidthPinsMatchRealFont(Document document, double low, double high) {
    List<String> off = new ArrayList<>();
    for (Element element : elements(document)) {
      String pinned = element.getAttribute("textLength");
      if (pinned.isEmpty()) {
        continue;
      }
      double declared = Double.parseDouble(pinned);
      String string = directText(element);
      if (declared <= 0 || string.isBlank()) {
        continue;
      }
      if (MEASURING_FONT.canDisplayUpTo(string) != -1) {
        // The measuring font lacks a glyph for this string (e.g. CJK — Liberation Sans is Latin):
        // its fallback advance is not the width a real viewer renders, so this oracle cannot judge
        // it. The full-em-square estimate for those code points is verified deterministically in
        // SvgTextWidthTest instead.
        continue;
      }
      double real =
          MEASURING_FONT.getStringBounds(string, FONT_RENDER_CONTEXT).getWidth()
              / 1000.0
              * effectiveFontSize(element);
      double ratio = real / declared;
      if (ratio < low || ratio > high) {
        off.add("'" + string + "' real/textLength=" + String.format(Locale.ROOT, "%.3f", ratio));
      }
    }
    assertThat(off)
        .as(
            "pinned text width diverging from real font metrics (ratio outside [%s, %s])",
            low, high)
        .isEmpty();
  }

  /**
   * The standard renderer paints groups, then edges, then nodes, so nodes cover edges (never the
   * reverse) and node labels are never hidden. Guards that document-order contract against a
   * refactor that reorders the paint layers. Not applicable to the UML sequence renderer, which has
   * its own element structure.
   */
  static void assertPaintOrderGroupsEdgesNodes(Document document) {
    Element viewport = viewport(document);
    int previousRank = 0;
    List<String> violations = new ArrayList<>();
    for (Element group : childElementsByTag(viewport, "g")) {
      int rank = paintRank(group);
      if (rank < 0) {
        continue;
      }
      if (rank < previousRank) {
        violations.add(idOf(group) + " paints after a later layer");
      }
      previousRank = rank;
    }
    assertThat(violations)
        .as("paint order must be groups, then edges, then nodes so nodes cover edges")
        .isEmpty();
  }

  /**
   * Every node label must clear a WCAG relative-contrast floor against its own shape fill, or it is
   * unreadable — the classic failure of a mis-set theme (light text on light fill, or the inverse
   * in a dark theme). Only hex fills are judged; non-hex/absent fills (e.g. figure shapes) are
   * skipped.
   */
  static void assertNodeLabelContrast(Document document, double minimumRatio) {
    List<String> lowContrast = new ArrayList<>();
    for (Element group : elements(document)) {
      String nodeId = group.getAttribute("data-dediren-node-id");
      if (nodeId.isEmpty()) {
        continue;
      }
      Element shape = firstDescendantWithAttribute(group, "data-dediren-node-shape");
      Element text = firstDescendantByTag(group, "text");
      if (shape == null || text == null) {
        continue;
      }
      double[] fill = parseHexColor(shape.getAttribute("fill"));
      double[] label = parseHexColor(text.getAttribute("fill"));
      if (fill == null || label == null) {
        continue;
      }
      double ratio = contrastRatio(label, fill);
      if (ratio < minimumRatio) {
        lowContrast.add(
            nodeId
                + " label "
                + text.getAttribute("fill")
                + " on "
                + shape.getAttribute("fill")
                + " = "
                + String.format(Locale.ROOT, "%.2f", ratio));
      }
    }
    assertThat(lowContrast)
        .as("node labels below WCAG contrast %s against their shape fill", minimumRatio)
        .isEmpty();
  }

  private static Element viewport(Document document) {
    for (Element element : elements(document)) {
      if (element.getTagName().equals("g") && !element.getAttribute("font-family").isEmpty()) {
        return element;
      }
    }
    throw new AssertionError("no viewport <g font-family=…> found");
  }

  private static int paintRank(Element group) {
    if (!group.getAttribute("data-dediren-group-id").isEmpty()) {
      return 0;
    }
    if (!group.getAttribute("data-dediren-edge-id").isEmpty()) {
      return 1;
    }
    if (!group.getAttribute("data-dediren-node-id").isEmpty()) {
      return 2;
    }
    return -1;
  }

  private static String idOf(Element group) {
    for (String attribute :
        List.of("data-dediren-group-id", "data-dediren-edge-id", "data-dediren-node-id")) {
      String value = group.getAttribute(attribute);
      if (!value.isEmpty()) {
        return attribute + "=" + value;
      }
    }
    return "<g>";
  }

  private static List<Element> childElementsByTag(Element parent, String tagName) {
    List<Element> children = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int index = 0; index < nodes.getLength(); index++) {
      if (nodes.item(index) instanceof Element child && child.getTagName().equals(tagName)) {
        children.add(child);
      }
    }
    return children;
  }

  private static Element firstDescendantWithAttribute(Element parent, String attribute) {
    NodeList all = parent.getElementsByTagName("*");
    for (int index = 0; index < all.getLength(); index++) {
      Element element = (Element) all.item(index);
      if (!element.getAttribute(attribute).isEmpty()) {
        return element;
      }
    }
    return null;
  }

  private static Element firstDescendantByTag(Element parent, String tagName) {
    NodeList nodes = parent.getElementsByTagName(tagName);
    return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
  }

  private static double[] parseHexColor(String value) {
    if (value == null || !value.matches("#[0-9a-fA-F]{6}")) {
      return null;
    }
    return new double[] {
      Integer.parseInt(value.substring(1, 3), 16),
      Integer.parseInt(value.substring(3, 5), 16),
      Integer.parseInt(value.substring(5, 7), 16)
    };
  }

  private static double contrastRatio(double[] foreground, double[] background) {
    double lighter = Math.max(relativeLuminance(foreground), relativeLuminance(background));
    double darker = Math.min(relativeLuminance(foreground), relativeLuminance(background));
    return (lighter + 0.05) / (darker + 0.05);
  }

  private static double relativeLuminance(double[] rgb) {
    double[] linear = new double[3];
    for (int index = 0; index < 3; index++) {
      double channel = rgb[index] / 255.0;
      linear[index] =
          channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
  }

  private static List<Element> elements(Document document) {
    NodeList all = document.getElementsByTagName("*");
    List<Element> elements = new ArrayList<>(all.getLength());
    for (int index = 0; index < all.getLength(); index++) {
      elements.add((Element) all.item(index));
    }
    return elements;
  }

  private static double[] viewBox(Document document) {
    String[] parts = document.getDocumentElement().getAttribute("viewBox").trim().split("\\s+");
    if (parts.length != 4) {
      throw new AssertionError(
          "root <svg> has no 4-value viewBox: got '" + String.join(" ", parts) + "'");
    }
    double minX = Double.parseDouble(parts[0]);
    double minY = Double.parseDouble(parts[1]);
    double width = Double.parseDouble(parts[2]);
    double height = Double.parseDouble(parts[3]);
    return new double[] {minX, minY, minX + width, minY + height};
  }

  private static double[] boundingBox(Element element) {
    return switch (element.getTagName()) {
      case "rect" ->
          box(
              number(element, "x"),
              number(element, "y"),
              number(element, "x") + number(element, "width"),
              number(element, "y") + number(element, "height"));
      case "circle" ->
          box(
              number(element, "cx") - number(element, "r"),
              number(element, "cy") - number(element, "r"),
              number(element, "cx") + number(element, "r"),
              number(element, "cy") + number(element, "r"));
      case "ellipse" ->
          box(
              number(element, "cx") - number(element, "rx"),
              number(element, "cy") - number(element, "ry"),
              number(element, "cx") + number(element, "rx"),
              number(element, "cy") + number(element, "ry"));
      case "line" ->
          box(
              Math.min(number(element, "x1"), number(element, "x2")),
              Math.min(number(element, "y1"), number(element, "y2")),
              Math.max(number(element, "x1"), number(element, "x2")),
              Math.max(number(element, "y1"), number(element, "y2")));
      default -> null;
    };
  }

  private static double[] box(double minX, double minY, double maxX, double maxY) {
    return new double[] {minX, minY, maxX, maxY};
  }

  private static double number(Element element, String attribute) {
    String value = element.getAttribute(attribute);
    return value.isEmpty() ? Double.NaN : Double.parseDouble(value);
  }

  private static boolean hasAncestor(Element element, String tagName) {
    for (Node node = element.getParentNode(); node != null; node = node.getParentNode()) {
      if (node instanceof Element ancestor && ancestor.getTagName().equals(tagName)) {
        return true;
      }
    }
    return false;
  }

  private static double effectiveFontSize(Element element) {
    for (Node node = element; node instanceof Element current; node = current.getParentNode()) {
      String fontSize = current.getAttribute("font-size");
      if (!fontSize.isEmpty()) {
        return Double.parseDouble(fontSize);
      }
    }
    return 14.0;
  }

  private static String directText(Element element) {
    String text = element.getTextContent();
    return text == null ? "" : text;
  }

  private static String format(double[] box) {
    return String.format(Locale.ROOT, "%.1f %.1f %.1f %.1f", box[0], box[1], box[2], box[3]);
  }
}

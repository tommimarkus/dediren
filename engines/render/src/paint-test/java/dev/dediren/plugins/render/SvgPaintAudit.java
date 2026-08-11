package dev.dediren.plugins.render;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Test-local layered SVG paint oracle. Batik owns non-text decorated paint; JDK text layout with
 * the bundled Liberation Sans owns text because Batik 1.19 ignores {@code
 * dominant-baseline=middle}.
 */
final class SvgPaintAudit {

  enum ThemeOwnership {
    BUILT_IN,
    USER_SUPPLIED
  }

  private enum SemanticKind {
    GROUP,
    EDGE,
    NODE
  }

  private static final double VIEWBOX_TOLERANCE = 0.51;
  private static final double AUDIT_VIEWPORT_PADDING = 64;
  private static final int MASK_ALPHA_THRESHOLD = 16;
  private static final Pattern TRANSFORM_COMMAND = Pattern.compile("([a-zA-Z]+)\\s*\\(([^)]*)\\)");
  private static final Pattern NUMBER =
      Pattern.compile("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?");
  private static final Set<String> FINITE_ATTRIBUTES =
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
          "stroke-width",
          "font-size",
          "textLength");
  private static final Set<String> PAINT_ELEMENTS =
      Set.of("rect", "circle", "ellipse", "path", "line", "polyline", "polygon");
  private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null, true, true);
  private static final Font TEXT_FONT = loadTextFont();
  private static final double TEXT_X_HEIGHT_EM = loadTextXHeightEm();

  private SvgPaintAudit() {}

  static Report audit(String svg) throws Exception {
    return audit(svg, ThemeOwnership.BUILT_IN);
  }

  static Report audit(String svg, ThemeOwnership themeOwnership) throws Exception {
    Document document = parse(svg);
    AuditState state = new AuditState(document, themeOwnership);
    state.discover();
    state.auditFiniteGeometry();
    if (state.hasNonFiniteGeometry()) {
      return state.report();
    }

    expandAuditViewport(document.getDocumentElement(), state.viewBox);
    try (BatikTestSupport.BuiltSvg built = BatikTestSupport.build(serialize(document))) {
      state.attachBatik(built);
      state.measurePaint();
      state.auditViewBoxAndPositivePaint();
      state.auditLabelOverflow();
      state.auditNodeOverlaps();
      state.auditGroupLabels();
      state.auditEdgeLabels();
      state.auditRoutes();
      state.auditContrastBaselines();
      return state.report();
    }
  }

  record Bounds(double x, double y, double width, double height) {
    double maxX() {
      return x + width;
    }

    double maxY() {
      return y + height;
    }

    boolean hasPositiveArea() {
      return Double.isFinite(x)
          && Double.isFinite(y)
          && Double.isFinite(width)
          && Double.isFinite(height)
          && width > 0
          && height > 0;
    }

    boolean contains(Bounds other, double tolerance) {
      return other.x >= x - tolerance
          && other.y >= y - tolerance
          && other.maxX() <= maxX() + tolerance
          && other.maxY() <= maxY() + tolerance;
    }

    boolean contains(double pointX, double pointY, double tolerance) {
      return pointX >= x - tolerance
          && pointX <= maxX() + tolerance
          && pointY >= y - tolerance
          && pointY <= maxY() + tolerance;
    }

    Bounds intersection(Bounds other) {
      double minimumX = Math.max(x, other.x);
      double minimumY = Math.max(y, other.y);
      double maximumX = Math.min(maxX(), other.maxX());
      double maximumY = Math.min(maxY(), other.maxY());
      if (maximumX <= minimumX || maximumY <= minimumY) {
        return null;
      }
      return new Bounds(minimumX, minimumY, maximumX - minimumX, maximumY - minimumY);
    }

    static Bounds from(Rectangle2D rectangle) {
      return rectangle == null
          ? null
          : new Bounds(
              rectangle.getX(), rectangle.getY(), rectangle.getWidth(), rectangle.getHeight());
    }

    static Bounds union(Collection<Bounds> bounds) {
      Rectangle2D union = null;
      for (Bounds bound : bounds) {
        if (bound == null) {
          continue;
        }
        Rectangle2D rectangle = new Rectangle2D.Double(bound.x, bound.y, bound.width, bound.height);
        if (union == null) {
          union = rectangle;
        } else {
          union.add(rectangle);
        }
      }
      return from(union);
    }

    @Override
    public String toString() {
      return String.format(Locale.ROOT, "x=%.2f,y=%.2f,w=%.2f,h=%.2f", x, y, width, height);
    }
  }

  record Violation(
      String code,
      List<String> semanticIds,
      Bounds transformedBounds,
      String observed,
      String expected) {
    Violation {
      semanticIds = List.copyOf(semanticIds);
    }
  }

  record Report(
      List<Violation> violations,
      List<Violation> advisories,
      Map<String, Bounds> semanticBounds,
      Map<String, Bounds> geometryBounds) {
    Report {
      violations = List.copyOf(violations);
      advisories = List.copyOf(advisories);
      semanticBounds = Collections.unmodifiableMap(new LinkedHashMap<>(semanticBounds));
      geometryBounds = Collections.unmodifiableMap(new LinkedHashMap<>(geometryBounds));
    }
  }

  private static final class AuditState {
    private final Document document;
    private final ThemeOwnership themeOwnership;
    private final double[] pageBackground;
    private final List<SemanticPaint> semantics = new ArrayList<>();
    private final Map<Element, SemanticPaint> semanticByElement = new IdentityHashMap<>();
    private final List<Violation> violations = new ArrayList<>();
    private final List<Violation> advisories = new ArrayList<>();
    private final Map<String, Bounds> semanticBounds = new LinkedHashMap<>();
    private final Map<String, Bounds> geometryBounds = new LinkedHashMap<>();
    private final Set<String> findingKeys = new LinkedHashSet<>();
    private final Map<SemanticPaint, BufferedImage> masks = new IdentityHashMap<>();
    private final Map<String, BufferedImage> routeMasks = new LinkedHashMap<>();
    private BatikTestSupport.BuiltSvg built;
    private Bounds viewBox;
    private int auditId;
    private boolean nonFiniteGeometry;

    private AuditState(Document document, ThemeOwnership themeOwnership) {
      this.document = document;
      this.themeOwnership = themeOwnership;
      this.pageBackground = pageBackground(document);
      this.viewBox = parseViewBox(document.getDocumentElement());
    }

    private void discover() {
      for (Element element : elements(document)) {
        SemanticPaint semantic = semantic(element);
        if (semantic != null) {
          semantics.add(semantic);
          semanticByElement.put(element, semantic);
        }
      }
      semantics.sort(Comparator.comparing(SemanticPaint::key));

      for (Element element : elements(document)) {
        SemanticPaint owner = closestSemantic(element);
        if (owner == null) {
          continue;
        }
        String tag = localName(element);
        if ("text".equals(tag)) {
          owner.textElements.add(element);
        } else if (PAINT_ELEMENTS.contains(tag) && !insideDefinition(element)) {
          String id = element.getAttribute("id");
          if (id.isBlank()) {
            id = "dediren-paint-audit-" + (++auditId);
            element.setAttribute("id", id);
          }
          owner.paintIds.add(id);
          if (owner.kind == SemanticKind.EDGE && ("path".equals(tag) || "line".equals(tag))) {
            owner.routeIds.add(id);
            owner.routeElements.add(element);
          }
        }
        if (!insideDefinition(element) && authoredGeometryIsDegenerate(element)) {
          SemanticPaint semantic = closestSemantic(element);
          addViolation(
              "non_positive_paint",
              List.of(semantic == null ? "svg" : semantic.key),
              new Bounds(
                  number(element, "x", number(element, "cx", 0)),
                  number(element, "y", number(element, "cy", 0)),
                  number(element, "width", 0),
                  number(element, "height", 0)),
              "authored shape has non-positive geometry",
              "rendered geometry must have positive width and height");
        }
      }
    }

    private boolean authoredGeometryIsDegenerate(Element element) {
      return switch (localName(element)) {
        case "rect" -> number(element, "width", 0) <= 0 || number(element, "height", 0) <= 0;
        case "circle" -> number(element, "r", 0) <= 0;
        case "ellipse" -> number(element, "rx", 0) <= 0 || number(element, "ry", 0) <= 0;
        case "line" ->
            number(element, "x1", 0) == number(element, "x2", 0)
                && number(element, "y1", 0) == number(element, "y2", 0);
        default -> false;
      };
    }

    private SemanticPaint semantic(Element element) {
      String group = element.getAttribute("data-dediren-group-id");
      if (!group.isBlank()) {
        return new SemanticPaint("group:" + group, SemanticKind.GROUP, element);
      }
      String edge = element.getAttribute("data-dediren-edge-id");
      if (!edge.isBlank()) {
        return new SemanticPaint("edge:" + edge, SemanticKind.EDGE, element);
      }
      String node = element.getAttribute("data-dediren-node-id");
      if (!node.isBlank()) {
        return new SemanticPaint("node:" + node, SemanticKind.NODE, element);
      }
      return null;
    }

    private SemanticPaint closestSemantic(Element element) {
      for (Node current = element;
          current instanceof Element ancestor;
          current = current.getParentNode()) {
        SemanticPaint semantic = semanticByElement.get(ancestor);
        if (semantic != null) {
          return semantic;
        }
      }
      return null;
    }

    private void auditFiniteGeometry() {
      for (Element element : elements(document)) {
        for (String attribute : FINITE_ATTRIBUTES) {
          if (!element.hasAttribute(attribute)) {
            continue;
          }
          String value = element.getAttribute(attribute).trim();
          for (String token : value.split("[ ,]+")) {
            if (token.isBlank()) {
              continue;
            }
            try {
              String number = token.endsWith("%") ? token.substring(0, token.length() - 1) : token;
              if (!Double.isFinite(Double.parseDouble(number))) {
                addNonFinite(element, attribute, value);
              }
            } catch (NumberFormatException malformed) {
              addNonFinite(element, attribute, value);
            }
          }
        }
      }
    }

    private void addNonFinite(Element element, String attribute, String value) {
      nonFiniteGeometry = true;
      SemanticPaint semantic = closestSemantic(element);
      String id = semantic == null ? "svg" : semantic.key;
      addViolation(
          "non_finite_geometry",
          List.of(id),
          new Bounds(0, 0, 0, 0),
          attribute + "=" + value,
          "all rendered geometry must be finite");
    }

    private boolean hasNonFiniteGeometry() {
      return nonFiniteGeometry;
    }

    private void attachBatik(BatikTestSupport.BuiltSvg built) {
      this.built = built;
    }

    private void measurePaint() {
      for (SemanticPaint semantic : semantics) {
        for (String id : semantic.paintIds) {
          try {
            Bounds bound = Bounds.from(built.transformedNonTextPaintBounds(id));
            if (bound != null) {
              semantic.nonTextBounds.add(bound);
            }
          } catch (IllegalArgumentException noPaint) {
            // The semantic positive-area rule below reports omitted/degenerate paint with context.
          }
          try {
            Bounds geometry = Bounds.from(built.transformedNonTextPrimitiveBounds(id));
            if (geometry != null) {
              semantic.nonTextGeometryBounds.put(id, geometry);
            }
          } catch (IllegalArgumentException noGeometry) {
            // Degenerate geometry is reported by the semantic positive-area rule.
          }
        }
        for (Element text : semantic.textElements) {
          semantic.textPaint.addAll(measureText(text, semantic.key, advisories, findingKeys));
        }
        ArrayList<Bounds> all = new ArrayList<>(semantic.nonTextBounds);
        semantic.textPaint.stream()
            .filter(TextPaint::measurable)
            .map(TextPaint::bounds)
            .forEach(all::add);
        semantic.bounds = Bounds.union(all);
        if (semantic.bounds != null) {
          semanticBounds.put(semantic.key, semantic.bounds);
        }
        ArrayList<Bounds> geometry = new ArrayList<>();
        if (semantic.kind == SemanticKind.EDGE) {
          semantic.routeIds.stream()
              .map(semantic.nonTextGeometryBounds::get)
              .filter(java.util.Objects::nonNull)
              .forEach(geometry::add);
        } else {
          geometry.addAll(semantic.nonTextGeometryBounds.values());
          semantic.textPaint.stream()
              .filter(TextPaint::measurable)
              .map(TextPaint::bounds)
              .forEach(geometry::add);
        }
        Bounds geometryBound = Bounds.union(geometry);
        if (geometryBound != null) {
          geometryBounds.put(semantic.key, geometryBound);
        }
      }
    }

    private void auditViewBoxAndPositivePaint() {
      for (SemanticPaint semantic : semantics) {
        if (semantic.bounds == null || !semantic.bounds.hasPositiveArea()) {
          addViolation(
              "non_positive_paint",
              List.of(semantic.key),
              semantic.bounds == null ? new Bounds(0, 0, 0, 0) : semantic.bounds,
              "no finite positive-area decorated paint",
              "semantic paint must have positive width and height");
          continue;
        }
        if (!viewBox.contains(semantic.bounds, VIEWBOX_TOLERANCE)) {
          addViolation(
              "viewbox_escape",
              List.of(semantic.key),
              semantic.bounds,
              semantic.bounds + " escapes " + viewBox,
              "decorated paint, including stroke, marker, filter and label paint, must remain"
                  + " inside the viewBox");
        }
      }
    }

    private void auditLabelOverflow() {
      for (SemanticPaint node : byKind(SemanticKind.NODE)) {
        if (node.actorExternalLabel() || node.sequenceStructure()) {
          continue;
        }
        Bounds shape = Bounds.union(node.nonTextBounds);
        if (shape == null) {
          continue;
        }
        for (TextPaint label : node.textPaint) {
          if (label.measurable()
              && !node.externalLabelNotation()
              && !shape.contains(label.bounds, 1.5)) {
            addViolation(
                "node_label_overflow",
                List.of(node.key),
                label.bounds,
                "label " + label.bounds + " is outside shape paint " + shape,
                "in-shape labels must remain inside their node paint");
          }
        }
      }
    }

    private void auditNodeOverlaps() {
      List<SemanticPaint> nodes = byKind(SemanticKind.NODE);
      for (int leftIndex = 0; leftIndex < nodes.size(); leftIndex++) {
        SemanticPaint left = nodes.get(leftIndex);
        for (int rightIndex = leftIndex + 1; rightIndex < nodes.size(); rightIndex++) {
          SemanticPaint right = nodes.get(rightIndex);
          if (intentionalSequenceContainment(left, right)
              || left.bounds == null
              || right.bounds == null
              || left.bounds.intersection(right.bounds) == null) {
            continue;
          }
          Bounds overlap = nodePaintIntersection(left, right);
          if (overlap != null) {
            addViolation(
                "node_paint_overlap",
                List.of(left.key, right.key),
                overlap,
                "both nodes paint the same pixels",
                "unrelated nodes must not have overlapping decorated paint");
          }
        }
      }
    }

    private void auditGroupLabels() {
      for (SemanticPaint group : byKind(SemanticKind.GROUP)) {
        for (TextPaint label : group.textPaint) {
          if (!label.measurable()) {
            continue;
          }
          for (SemanticPaint node : byKind(SemanticKind.NODE)) {
            if (node.sequenceStructure()
                || node.bounds == null
                || label.bounds.intersection(node.bounds) == null) {
              continue;
            }
            ArrayList<Bounds> paintedOverlaps = new ArrayList<>();
            paintedOverlaps.add(textMaskIntersection(label, mask(node), built.viewport()));
            node.textPaint.stream()
                .filter(TextPaint::measurable)
                .map(nodeLabel -> textIntersection(label, nodeLabel))
                .forEach(paintedOverlaps::add);
            Bounds overlap = Bounds.union(paintedOverlaps);
            if (overlap != null) {
              addViolation(
                  "group_label_member_collision",
                  List.of(group.key, node.key),
                  overlap,
                  "group label paints over member node",
                  "group header labels must not collide with member paint");
            }
          }
        }
      }
    }

    private void auditEdgeLabels() {
      List<SemanticPaint> edges = byKind(SemanticKind.EDGE);
      List<SemanticPaint> nodes = byKind(SemanticKind.NODE);
      for (SemanticPaint edge : edges) {
        for (int leftIndex = 0; leftIndex < edge.textPaint.size(); leftIndex++) {
          TextPaint left = edge.textPaint.get(leftIndex);
          for (int rightIndex = leftIndex + 1; rightIndex < edge.textPaint.size(); rightIndex++) {
            TextPaint right = edge.textPaint.get(rightIndex);
            if (!left.measurable() || !right.measurable() || duplicateHaloPair(left, right)) {
              continue;
            }
            Bounds overlap = textIntersection(left, right);
            if (overlap != null) {
              addViolation(
                  "edge_label_label_collision",
                  List.of(edge.key),
                  overlap,
                  "same-edge labels share painted glyph area",
                  "only a duplicate halo and foreground label pair may overlap");
            }
          }
        }
        for (TextPaint label : edge.textPaint) {
          if (!label.measurable()) {
            continue;
          }
          for (SemanticPaint node : nodes) {
            if (node.sequenceStructure()
                || node.bounds == null
                || label.bounds.intersection(node.bounds) == null) {
              continue;
            }
            Bounds overlap = textMaskIntersection(label, mask(node), built.viewport());
            if (overlap != null) {
              addViolation(
                  "edge_label_node_collision",
                  List.of(edge.key, node.key),
                  overlap,
                  "edge label paints over node",
                  "edge labels must remain clear of node paint");
            }
          }
        }
      }

      for (int leftIndex = 0; leftIndex < edges.size(); leftIndex++) {
        SemanticPaint left = edges.get(leftIndex);
        for (int rightIndex = leftIndex + 1; rightIndex < edges.size(); rightIndex++) {
          SemanticPaint right = edges.get(rightIndex);
          for (TextPaint leftLabel : left.textPaint) {
            for (TextPaint rightLabel : right.textPaint) {
              if (!leftLabel.measurable() || !rightLabel.measurable()) {
                continue;
              }
              Bounds overlap = textIntersection(leftLabel, rightLabel);
              if (overlap != null) {
                addViolation(
                    "edge_label_label_collision",
                    List.of(left.key, right.key),
                    overlap,
                    "edge labels share painted glyph area",
                    "labels belonging to different edges must not overlap");
              }
            }
          }
        }
      }
    }

    private boolean intentionalSequenceContainment(SemanticPaint left, SemanticPaint right) {
      return interactionContainsMember(left, right) || interactionContainsMember(right, left);
    }

    private boolean interactionContainsMember(
        SemanticPaint interaction, SemanticPaint possibleMember) {
      return interaction.sequenceInteraction()
          && possibleMember.sequenceInteractionMember()
          && interaction.bounds != null
          && possibleMember.bounds != null
          && interaction.bounds.contains(possibleMember.bounds, 1.5);
    }

    private boolean duplicateHaloPair(TextPaint left, TextPaint right) {
      if (!left.text.equals(right.text)
          || !sameGeometry(left.geometryBounds, right.geometryBounds)) {
        return false;
      }
      return (haloText(left.element) && foregroundText(right.element))
          || (haloText(right.element) && foregroundText(left.element));
    }

    private boolean sameGeometry(Bounds left, Bounds right) {
      double tolerance = 0.01;
      return left != null
          && right != null
          && Math.abs(left.x - right.x) <= tolerance
          && Math.abs(left.y - right.y) <= tolerance
          && Math.abs(left.width - right.width) <= tolerance
          && Math.abs(left.height - right.height) <= tolerance;
    }

    private boolean haloText(Element element) {
      return "none".equals(effective(element, "fill", "black"))
          && !"none".equals(effective(element, "stroke", "none"));
    }

    private boolean foregroundText(Element element) {
      return !"none".equals(effective(element, "fill", "black"));
    }

    private void auditRoutes() {
      List<TextPaint> labels =
          semantics.stream().flatMap(semantic -> semantic.textPaint.stream()).toList();
      for (SemanticPaint edge : byKind(SemanticKind.EDGE)) {
        BufferedImage route = routeMask(edge);
        Bounds routeBounds = Bounds.union(edge.routeBounds(built));
        if (routeBounds == null) {
          continue;
        }
        for (SemanticPaint node : byKind(SemanticKind.NODE)) {
          if (node.sequenceStructure()
              || node.bounds == null
              || routeBounds.intersection(node.bounds) == null
              || edge.endpointNode(node)) {
            continue;
          }
          Bounds overlap =
              maskIntersection(
                  route, mask(node), routeBounds.intersection(node.bounds), built.viewport());
          if (overlap != null && !boundaryContactOnly(overlap, node)) {
            addViolation(
                "edge_route_node_collision",
                List.of(edge.key, node.key),
                overlap,
                "edge route paints through a non-endpoint node",
                "routes may meet endpoint boundaries but must avoid other node paint");
          }
        }
        for (TextPaint label : labels) {
          if (!label.measurable()
              || routeBounds.intersection(label.bounds) == null
              || label.semanticKey.equals(edge.key)) {
            continue;
          }
          Bounds overlap = textMaskIntersection(label, route, built.viewport());
          if (overlap != null) {
            addViolation(
                "edge_route_label_collision",
                List.of(edge.key, label.semanticKey),
                overlap,
                "route paints through label glyphs",
                "edge routes must avoid labels");
          }
        }
      }
    }

    private void auditContrastBaselines() {
      for (SemanticPaint node : byKind(SemanticKind.NODE)) {
        Element shape = firstNodeFillElement(node.element);
        if (shape == null) {
          continue;
        }
        String shapeFill = effective(shape, "fill", "#000000");
        double[] shapeColor = parseColor(shapeFill);
        if (shapeColor == null) {
          addAdvisory(
              "not_measurable",
              List.of(node.key),
              node.boundsOrZero(),
              "node fill composition is " + shapeFill,
              "contrast baseline requires a flat compositable node fill");
          continue;
        }
        double shapeOpacity =
            effectiveOpacity(shape, "fill-opacity") * effectiveOpacity(shape, "opacity");
        double[] background = composite(shapeColor, shapeOpacity, pageBackground);
        for (TextPaint text : node.textPaint) {
          Element textElement = text.element;
          String textFill = effective(textElement, "fill", "#000000");
          double[] foreground = parseColor(textFill);
          if (foreground == null) {
            addAdvisory(
                "not_measurable",
                List.of(node.key),
                text.boundsOrZero(),
                "label fill composition is " + textFill,
                "contrast baseline requires a flat compositable label fill");
            continue;
          }
          double textOpacity =
              effectiveOpacity(textElement, "fill-opacity")
                  * effectiveOpacity(textElement, "opacity");
          double[] compositedForeground = composite(foreground, textOpacity, background);
          double ratio = contrastRatio(compositedForeground, background);
          double minimum = text.largeText ? 3.0 : 4.5;
          if (ratio + 0.0001 < minimum) {
            Violation finding =
                new Violation(
                    "contrast_baseline",
                    List.of(node.key),
                    text.boundsOrZero(),
                    String.format(Locale.ROOT, "contrast baseline ratio %.2f", ratio),
                    String.format(Locale.ROOT, "contrast baseline ratio at least %.2f", minimum));
            addFinding(finding, themeOwnership == ThemeOwnership.USER_SUPPLIED);
          }
        }
      }
    }

    private BufferedImage mask(SemanticPaint semantic) {
      return masks.computeIfAbsent(
          semantic, ignored -> BatikTestSupport.rasterizeNonTextNodes(built, semantic.paintIds));
    }

    private BufferedImage routeMask(SemanticPaint edge) {
      return routeMasks.computeIfAbsent(
          edge.key, ignored -> BatikTestSupport.rasterizeNonTextNodes(built, edge.routeIds));
    }

    private Bounds nodePaintIntersection(SemanticPaint left, SemanticPaint right) {
      ArrayList<Bounds> overlaps = new ArrayList<>();
      Bounds candidate = left.bounds.intersection(right.bounds);
      overlaps.add(maskIntersection(mask(left), mask(right), candidate, built.viewport()));
      left.textPaint.stream()
          .filter(TextPaint::measurable)
          .map(text -> textMaskIntersection(text, mask(right), built.viewport()))
          .forEach(overlaps::add);
      right.textPaint.stream()
          .filter(TextPaint::measurable)
          .map(text -> textMaskIntersection(text, mask(left), built.viewport()))
          .forEach(overlaps::add);
      for (TextPaint leftText : left.textPaint) {
        if (!leftText.measurable()) {
          continue;
        }
        right.textPaint.stream()
            .filter(TextPaint::measurable)
            .map(rightText -> textIntersection(leftText, rightText))
            .forEach(overlaps::add);
      }
      return Bounds.union(overlaps);
    }

    private boolean boundaryContactOnly(Bounds overlap, SemanticPaint node) {
      Bounds shape = Bounds.union(node.nonTextBounds);
      if (shape == null) {
        return false;
      }
      double boundaryBand = 3.0;
      return overlap.maxX() <= shape.x + boundaryBand
          || overlap.x >= shape.maxX() - boundaryBand
          || overlap.maxY() <= shape.y + boundaryBand
          || overlap.y >= shape.maxY() - boundaryBand;
    }

    private List<SemanticPaint> byKind(SemanticKind kind) {
      return semantics.stream().filter(semantic -> semantic.kind == kind).toList();
    }

    private void addViolation(
        String code, List<String> semanticIds, Bounds bounds, String observed, String expected) {
      addFinding(new Violation(code, semanticIds, bounds, observed, expected), false);
    }

    private void addAdvisory(
        String code, List<String> semanticIds, Bounds bounds, String observed, String expected) {
      addFinding(new Violation(code, semanticIds, bounds, observed, expected), true);
    }

    private void addFinding(Violation finding, boolean advisory) {
      String key = finding.code + "|" + String.join("|", finding.semanticIds);
      if (!findingKeys.add((advisory ? "A|" : "V|") + key)) {
        return;
      }
      (advisory ? advisories : violations).add(finding);
    }

    private Report report() {
      return new Report(violations, advisories, semanticBounds, geometryBounds);
    }
  }

  private static final class SemanticPaint {
    private final String key;
    private final SemanticKind kind;
    private final Element element;
    private final List<String> paintIds = new ArrayList<>();
    private final List<String> routeIds = new ArrayList<>();
    private final List<Element> routeElements = new ArrayList<>();
    private final List<Element> textElements = new ArrayList<>();
    private final List<TextPaint> textPaint = new ArrayList<>();
    private final List<Bounds> nonTextBounds = new ArrayList<>();
    private final Map<String, Bounds> nonTextGeometryBounds = new LinkedHashMap<>();
    private Bounds bounds;

    private SemanticPaint(String key, SemanticKind kind, Element element) {
      this.key = key;
      this.kind = kind;
      this.element = element;
    }

    private String key() {
      return key;
    }

    private boolean actorExternalLabel() {
      return hasDescendantAttribute(element, "data-dediren-node-shape", "uml_actor")
          || hasDescendantAttribute(element, "data-dediren-node-decorator", "uml_actor");
    }

    private boolean externalLabelNotation() {
      // These compact notation glyphs deliberately place their semantic label outside, or wider
      // than, the symbol rather than treating it as an in-shape node label.
      return actorExternalLabel()
          || hasDescendantAttribute(element, "data-dediren-node-shape", "uml_decision_node")
          || hasDescendantAttribute(element, "data-dediren-node-shape", "uml_port");
    }

    private boolean sequenceStructure() {
      return !element.getAttribute("data-dediren-sequence-interaction").isBlank()
          || !element.getAttribute("data-dediren-sequence-lifeline").isBlank()
          || !element.getAttribute("data-dediren-sequence-combined-fragment").isBlank()
          || !element.getAttribute("data-dediren-sequence-execution").isBlank()
          || "Interaction".equals(element.getAttribute("data-dediren-node-type"))
          || "Lifeline".equals(element.getAttribute("data-dediren-node-type"));
    }

    private boolean sequenceInteraction() {
      return !element.getAttribute("data-dediren-sequence-interaction").isBlank()
          || "Interaction".equals(element.getAttribute("data-dediren-node-type"));
    }

    private boolean sequenceInteractionMember() {
      return sequenceStructure()
          || "ExecutionSpecification".equals(element.getAttribute("data-dediren-node-type"));
    }

    private List<Bounds> routeBounds(BatikTestSupport.BuiltSvg built) {
      ArrayList<Bounds> bounds = new ArrayList<>();
      for (String id : routeIds) {
        Bounds bound = Bounds.from(built.transformedNonTextPaintBounds(id));
        if (bound != null) {
          bounds.add(bound);
        }
      }
      return bounds;
    }

    private boolean endpointNode(SemanticPaint node) {
      if (node.bounds == null || routeElements.isEmpty()) {
        return false;
      }
      Point start = endpoint(routeElements.getFirst(), true);
      Point end = endpoint(routeElements.getLast(), false);
      return (start != null && node.bounds.contains(start.x, start.y, 2.5))
          || (end != null && node.bounds.contains(end.x, end.y, 2.5));
    }

    private Bounds boundsOrZero() {
      return bounds == null ? new Bounds(0, 0, 0, 0) : bounds;
    }
  }

  private record TextPaint(
      String semanticKey,
      Element element,
      String text,
      Bounds bounds,
      Bounds geometryBounds,
      Shape shape,
      boolean measurable,
      boolean largeText) {
    private Bounds boundsOrZero() {
      return bounds == null ? new Bounds(0, 0, 0, 0) : bounds;
    }
  }

  private record Point(double x, double y) {}

  private static List<TextPaint> measureText(
      Element text, String semanticKey, List<Violation> advisories, Set<String> findingKeys) {
    ArrayList<Element> runs = new ArrayList<>();
    NodeList tspans = text.getElementsByTagName("tspan");
    if (tspans.getLength() == 0) {
      runs.add(text);
    } else {
      for (int index = 0; index < tspans.getLength(); index++) {
        runs.add((Element) tspans.item(index));
      }
    }

    double baseX = number(text, "x", 0);
    double baseY = number(text, "y", 0);
    double currentY = baseY;
    ArrayList<TextPaint> paints = new ArrayList<>();
    for (Element run : runs) {
      String string = directText(run);
      if (string.isBlank()) {
        continue;
      }
      double x = number(run, "x", baseX);
      if (run.hasAttribute("y")) {
        currentY = number(run, "y", currentY);
      }
      currentY += number(run, "dy", 0);
      double size = Double.parseDouble(effective(run, "font-size", "14"));
      int weight = parseWeight(effective(run, "font-weight", "400"));
      boolean large = size >= 24.0 || (size >= 18.66 && weight >= 700);
      if (!BatikTestSupport.canDisplay(string)) {
        String key = "A|font_missing|" + semanticKey;
        if (findingKeys.add(key)) {
          advisories.add(
              new Violation(
                  "font_missing",
                  List.of(semanticKey),
                  new Bounds(x, currentY, 0, 0),
                  "bundled Liberation Sans cannot display all glyphs in '" + string + "'",
                  "text geometry is advisory when the pinned font lacks glyph coverage"));
        }
        paints.add(new TextPaint(semanticKey, run, string, null, null, null, false, large));
        continue;
      }

      Font font = TEXT_FONT.deriveFont(weight >= 700 ? Font.BOLD : Font.PLAIN, (float) size);
      TextLayout layout = new TextLayout(string, font, FONT_CONTEXT);
      double naturalAdvance = Math.max(layout.getAdvance(), 0.0001);
      double desiredAdvance = number(run, "textLength", number(text, "textLength", naturalAdvance));
      Shape outline = adjustedTextOutline(run, text, string, font, layout, desiredAdvance);
      Shape decoratedOutline = decoratedTextOutline(run, text, outline);
      String filter = effective(run, "filter", effective(text, "filter", "none"));
      if (!"none".equals(filter)) {
        String key = "A|not_measurable|text-filter|" + semanticKey;
        if (findingKeys.add(key)) {
          advisories.add(
              new Violation(
                  "not_measurable",
                  List.of(semanticKey),
                  new Bounds(x, currentY, 0, 0),
                  "text filter composition is " + filter,
                  "filtered text geometry requires an independent measurable composition"));
        }
      }
      String anchor = effective(run, "text-anchor", effective(text, "text-anchor", "start"));
      double horizontal =
          switch (anchor) {
            case "middle" -> x - desiredAdvance / 2.0;
            case "end" -> x - desiredAdvance;
            default -> x;
          };
      String baseline =
          effective(run, "dominant-baseline", effective(text, "dominant-baseline", "auto"));
      // SVG's middle baseline is the x-middle baseline, not the visual ink-box center. With the
      // exact pinned font, the alphabetic baseline therefore sits half an x-height below y.
      double xHeight = size * TEXT_X_HEIGHT_EM;
      double vertical = "middle".equals(baseline) ? currentY + xHeight / 2.0 : currentY;
      AffineTransform positioning = AffineTransform.getTranslateInstance(horizontal, vertical);
      Shape positionedGeometry = positioning.createTransformedShape(outline);
      Shape positionedPaint =
          AffineTransform.getTranslateInstance(horizontal, vertical)
              .createTransformedShape(decoratedOutline);
      AffineTransform transform = ancestorTransform(run);
      Shape transformedGeometry = transform.createTransformedShape(positionedGeometry);
      Shape transformedPaint = transform.createTransformedShape(positionedPaint);
      Bounds bounds = Bounds.from(transformedPaint.getBounds2D());
      Bounds geometryBounds = Bounds.from(transformedGeometry.getBounds2D());
      paints.add(
          new TextPaint(
              semanticKey, run, string, bounds, geometryBounds, transformedPaint, true, large));
    }
    return paints;
  }

  private static Shape adjustedTextOutline(
      Element run,
      Element text,
      String string,
      Font font,
      TextLayout layout,
      double desiredAdvance) {
    boolean hasTextLength = run.hasAttribute("textLength") || text.hasAttribute("textLength");
    if (!hasTextLength) {
      return layout.getOutline(null);
    }

    double naturalAdvance = Math.max(layout.getAdvance(), 0.0001);
    String lengthAdjust =
        effective(run, "lengthAdjust", effective(text, "lengthAdjust", "spacing"));
    if ("spacingAndGlyphs".equals(lengthAdjust)) {
      return AffineTransform.getScaleInstance(desiredAdvance / naturalAdvance, 1)
          .createTransformedShape(layout.getOutline(null));
    }

    char[] characters = string.toCharArray();
    int layoutFlags =
        "rtl".equals(effective(run, "direction", effective(text, "direction", "ltr")))
            ? Font.LAYOUT_RIGHT_TO_LEFT
            : Font.LAYOUT_LEFT_TO_RIGHT;
    GlyphVector glyphs =
        font.layoutGlyphVector(FONT_CONTEXT, characters, 0, characters.length, layoutFlags);
    int glyphCount = glyphs.getNumGlyphs();
    if (glyphCount < 2) {
      return glyphs.getOutline();
    }
    double glyphAdvance = glyphs.getGlyphPosition(glyphCount).getX();
    double extraSpacing = (desiredAdvance - glyphAdvance) / (glyphCount - 1);
    Area adjusted = new Area();
    for (int index = 0; index < glyphCount; index++) {
      adjusted.add(new Area(glyphs.getGlyphOutline(index, (float) (index * extraSpacing), 0)));
    }
    return adjusted;
  }

  private static Shape decoratedTextOutline(Element run, Element text, Shape outline) {
    Area paint = new Area();
    String fill = effective(run, "fill", effective(text, "fill", "black"));
    if (!"none".equals(fill) && effectiveOpacity(run, "fill-opacity") > 0) {
      paint.add(new Area(outline));
    }
    String stroke = effective(run, "stroke", effective(text, "stroke", "none"));
    double strokeWidth =
        Double.parseDouble(effective(run, "stroke-width", effective(text, "stroke-width", "1")));
    if (!"none".equals(stroke) && strokeWidth > 0 && effectiveOpacity(run, "stroke-opacity") > 0) {
      paint.add(new Area(new BasicStroke((float) strokeWidth).createStrokedShape(outline)));
    }
    return paint;
  }

  private static Bounds maskIntersection(
      BufferedImage left,
      BufferedImage right,
      Bounds candidate,
      BatikTestSupport.Viewport viewport) {
    if (candidate == null) {
      return null;
    }
    int minimumX = Math.max(0, (int) Math.floor(viewport.imageX(candidate.x)));
    int minimumY = Math.max(0, (int) Math.floor(viewport.imageY(candidate.y)));
    int maximumX =
        Math.min(
            Math.min(left.getWidth(), right.getWidth()),
            (int) Math.ceil(viewport.imageX(candidate.maxX())));
    int maximumY =
        Math.min(
            Math.min(left.getHeight(), right.getHeight()),
            (int) Math.ceil(viewport.imageY(candidate.maxY())));
    int foundMinX = Integer.MAX_VALUE;
    int foundMinY = Integer.MAX_VALUE;
    int foundMaxX = Integer.MIN_VALUE;
    int foundMaxY = Integer.MIN_VALUE;
    for (int y = minimumY; y < maximumY; y++) {
      for (int x = minimumX; x < maximumX; x++) {
        if (alpha(left, x, y) > MASK_ALPHA_THRESHOLD && alpha(right, x, y) > MASK_ALPHA_THRESHOLD) {
          foundMinX = Math.min(foundMinX, x);
          foundMinY = Math.min(foundMinY, y);
          foundMaxX = Math.max(foundMaxX, x);
          foundMaxY = Math.max(foundMaxY, y);
        }
      }
    }
    return foundMinX == Integer.MAX_VALUE
        ? null
        : imageBounds(viewport, foundMinX, foundMinY, foundMaxX, foundMaxY);
  }

  private static Bounds textMaskIntersection(
      TextPaint text, BufferedImage mask, BatikTestSupport.Viewport viewport) {
    if (!text.measurable || text.bounds == null) {
      return null;
    }
    int minimumX = Math.max(0, (int) Math.floor(viewport.imageX(text.bounds.x)));
    int minimumY = Math.max(0, (int) Math.floor(viewport.imageY(text.bounds.y)));
    int maximumX = Math.min(mask.getWidth(), (int) Math.ceil(viewport.imageX(text.bounds.maxX())));
    int maximumY = Math.min(mask.getHeight(), (int) Math.ceil(viewport.imageY(text.bounds.maxY())));
    int foundMinX = Integer.MAX_VALUE;
    int foundMinY = Integer.MAX_VALUE;
    int foundMaxX = Integer.MIN_VALUE;
    int foundMaxY = Integer.MIN_VALUE;
    for (int y = minimumY; y < maximumY; y++) {
      for (int x = minimumX; x < maximumX; x++) {
        double userX = viewport.userX(x + 0.5);
        double userY = viewport.userY(y + 0.5);
        if (alpha(mask, x, y) > MASK_ALPHA_THRESHOLD && text.shape.contains(userX, userY)) {
          foundMinX = Math.min(foundMinX, x);
          foundMinY = Math.min(foundMinY, y);
          foundMaxX = Math.max(foundMaxX, x);
          foundMaxY = Math.max(foundMaxY, y);
        }
      }
    }
    return foundMinX == Integer.MAX_VALUE
        ? null
        : imageBounds(viewport, foundMinX, foundMinY, foundMaxX, foundMaxY);
  }

  private static Bounds imageBounds(
      BatikTestSupport.Viewport viewport, int minimumX, int minimumY, int maximumX, int maximumY) {
    double x = viewport.userX(minimumX);
    double y = viewport.userY(minimumY);
    return new Bounds(x, y, viewport.userX(maximumX + 1.0) - x, viewport.userY(maximumY + 1.0) - y);
  }

  private static Bounds textIntersection(TextPaint left, TextPaint right) {
    if (left.bounds.intersection(right.bounds) == null) {
      return null;
    }
    Area intersection = new Area(left.shape);
    intersection.intersect(new Area(right.shape));
    return intersection.isEmpty() ? null : Bounds.from(intersection.getBounds2D());
  }

  private static int alpha(BufferedImage image, int x, int y) {
    return image.getRGB(x, y) >>> 24;
  }

  private static Point endpoint(Element route, boolean first) {
    String tag = localName(route);
    Point point;
    if ("line".equals(tag)) {
      point =
          first
              ? new Point(number(route, "x1", 0), number(route, "y1", 0))
              : new Point(number(route, "x2", 0), number(route, "y2", 0));
    } else {
      ArrayList<Double> values = new ArrayList<>();
      Matcher matcher = NUMBER.matcher(route.getAttribute("d"));
      while (matcher.find()) {
        values.add(Double.parseDouble(matcher.group()));
      }
      if (values.size() < 2) {
        return null;
      }
      int index = first ? 0 : values.size() - 2;
      point = new Point(values.get(index), values.get(index + 1));
    }
    var transformed =
        ancestorTransform(route)
            .transform(new java.awt.geom.Point2D.Double(point.x, point.y), null);
    return new Point(transformed.getX(), transformed.getY());
  }

  private static Document parse(String svg) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
  }

  private static void expandAuditViewport(Element root, Bounds original) {
    double offsetX = AUDIT_VIEWPORT_PADDING - original.x;
    double offsetY = AUDIT_VIEWPORT_PADDING - original.y;
    Element wrapper = root.getOwnerDocument().createElementNS("http://www.w3.org/2000/svg", "g");
    wrapper.setAttribute("data-dediren-paint-audit-viewport", "true");
    wrapper.setAttribute(
        "transform", String.format(Locale.ROOT, "translate(%.6f %.6f)", offsetX, offsetY));
    while (root.hasChildNodes()) {
      wrapper.appendChild(root.getFirstChild());
    }
    root.appendChild(wrapper);
    root.setAttribute(
        "viewBox",
        String.format(
            Locale.ROOT,
            "%.6f %.6f %.6f %.6f",
            0.0,
            0.0,
            original.width + 2 * AUDIT_VIEWPORT_PADDING,
            original.height + 2 * AUDIT_VIEWPORT_PADDING));
    root.setAttribute(
        "width",
        String.format(
            Locale.ROOT,
            "%.6f",
            number(root, "width", original.width) + 2 * AUDIT_VIEWPORT_PADDING));
    root.setAttribute(
        "height",
        String.format(
            Locale.ROOT,
            "%.6f",
            number(root, "height", original.height) + 2 * AUDIT_VIEWPORT_PADDING));
    root.setAttribute("overflow", "visible");
    root.setAttribute(
        "data-dediren-paint-audit-offset-x", String.format(Locale.ROOT, "%.6f", offsetX));
    root.setAttribute(
        "data-dediren-paint-audit-offset-y", String.format(Locale.ROOT, "%.6f", offsetY));
  }

  private static String serialize(Document document) throws Exception {
    TransformerFactory factory = TransformerFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    var transformer = factory.newTransformer();
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
    StringWriter output = new StringWriter();
    transformer.transform(new DOMSource(document), new StreamResult(output));
    return output.toString();
  }

  private static List<Element> elements(Document document) {
    NodeList nodes = document.getElementsByTagName("*");
    ArrayList<Element> elements = new ArrayList<>(nodes.getLength());
    for (int index = 0; index < nodes.getLength(); index++) {
      elements.add((Element) nodes.item(index));
    }
    return elements;
  }

  private static boolean insideDefinition(Element element) {
    for (Node node = element.getParentNode();
        node instanceof Element ancestor;
        node = node.getParentNode()) {
      String name = localName(ancestor);
      if ("defs".equals(name) || "marker".equals(name) || "filter".equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static String localName(Element element) {
    return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
  }

  private static Bounds parseViewBox(Element root) {
    String[] values = root.getAttribute("viewBox").trim().split("[ ,]+");
    if (values.length != 4) {
      throw new IllegalArgumentException("SVG needs a four-number viewBox");
    }
    return new Bounds(
        Double.parseDouble(values[0]),
        Double.parseDouble(values[1]),
        Double.parseDouble(values[2]),
        Double.parseDouble(values[3]));
  }

  private static double number(Element element, String attribute, double fallback) {
    if (!element.hasAttribute(attribute) || element.getAttribute(attribute).isBlank()) {
      return fallback;
    }
    String first = element.getAttribute(attribute).trim().split("[ ,]+", 2)[0];
    return Double.parseDouble(first);
  }

  private static String effective(Element element, String attribute, String fallback) {
    for (Node node = element; node instanceof Element current; node = current.getParentNode()) {
      if (current.hasAttribute(attribute) && !current.getAttribute(attribute).isBlank()) {
        return current.getAttribute(attribute).trim();
      }
      String style = current.getAttribute("style");
      for (String declaration : style.split(";")) {
        String[] pair = declaration.split(":", 2);
        if (pair.length == 2 && pair[0].trim().equals(attribute)) {
          return pair[1].trim();
        }
      }
    }
    return fallback;
  }

  private static double effectiveOpacity(Element element, String attribute) {
    try {
      return Math.clamp(Double.parseDouble(effective(element, attribute, "1")), 0, 1);
    } catch (NumberFormatException invalid) {
      return 1;
    }
  }

  private static AffineTransform ancestorTransform(Element element) {
    ArrayList<Element> ancestors = new ArrayList<>();
    for (Node node = element; node instanceof Element current; node = current.getParentNode()) {
      ancestors.add(current);
    }
    Collections.reverse(ancestors);
    AffineTransform transform = new AffineTransform();
    for (Element ancestor : ancestors) {
      if ("true".equals(ancestor.getAttribute("data-dediren-paint-audit-viewport"))) {
        continue;
      }
      transform.concatenate(parseTransform(ancestor.getAttribute("transform")));
    }
    return transform;
  }

  private static AffineTransform parseTransform(String value) {
    AffineTransform transform = new AffineTransform();
    Matcher matcher = TRANSFORM_COMMAND.matcher(value == null ? "" : value);
    while (matcher.find()) {
      ArrayList<Double> values = new ArrayList<>();
      Matcher number = NUMBER.matcher(matcher.group(2));
      while (number.find()) {
        values.add(Double.parseDouble(number.group()));
      }
      switch (matcher.group(1)) {
        case "translate" ->
            transform.translate(values.getFirst(), values.size() > 1 ? values.get(1) : 0);
        case "scale" ->
            transform.scale(
                values.getFirst(), values.size() > 1 ? values.get(1) : values.getFirst());
        case "matrix" -> {
          if (values.size() == 6) {
            transform.concatenate(
                new AffineTransform(
                    values.get(0),
                    values.get(1),
                    values.get(2),
                    values.get(3),
                    values.get(4),
                    values.get(5)));
          }
        }
        default -> {
          // Dediren emits no rotate/skew transforms; unsupported test transforms remain unchanged.
        }
      }
    }
    return transform;
  }

  private static String directText(Element element) {
    StringBuilder text = new StringBuilder();
    NodeList children = element.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child.getNodeType() == Node.TEXT_NODE) {
        text.append(child.getNodeValue());
      }
    }
    return text.toString().trim();
  }

  private static int parseWeight(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "bold", "bolder" -> 700;
      case "normal", "lighter" -> 400;
      default -> {
        try {
          yield Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
          yield 400;
        }
      }
    };
  }

  private static Font loadTextFont() {
    try (InputStream input = Files.newInputStream(BatikTestSupport.FONT_PATH)) {
      return Font.createFont(Font.TRUETYPE_FONT, input);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "could not load bundled Liberation Sans text oracle", failure);
    }
  }

  private static double loadTextXHeightEm() {
    try {
      byte[] font = Files.readAllBytes(BatikTestSupport.FONT_PATH);
      ByteBuffer table = ByteBuffer.wrap(font).order(ByteOrder.BIG_ENDIAN);
      int tableCount = Short.toUnsignedInt(table.getShort(4));
      int headOffset = -1;
      int os2Offset = -1;
      for (int index = 0; index < tableCount; index++) {
        int record = 12 + index * 16;
        String tag = new String(font, record, 4, StandardCharsets.US_ASCII);
        int offset = table.getInt(record + 8);
        if ("head".equals(tag)) {
          headOffset = offset;
        } else if ("OS/2".equals(tag)) {
          os2Offset = offset;
        }
      }
      if (headOffset < 0 || os2Offset < 0 || Short.toUnsignedInt(table.getShort(os2Offset)) < 2) {
        throw new IllegalStateException("bundled font lacks the OpenType x-height metric");
      }
      int unitsPerEm = Short.toUnsignedInt(table.getShort(headOffset + 18));
      int xHeight = table.getShort(os2Offset + 86);
      if (unitsPerEm <= 0 || xHeight <= 0) {
        throw new IllegalStateException("bundled font has an invalid OpenType x-height metric");
      }
      return (double) xHeight / unitsPerEm;
    } catch (Exception failure) {
      throw new IllegalStateException("could not read bundled Liberation Sans x-height", failure);
    }
  }

  private static boolean hasDescendantAttribute(Element element, String attribute, String value) {
    if (value.equals(element.getAttribute(attribute))) {
      return true;
    }
    NodeList descendants = element.getElementsByTagName("*");
    for (int index = 0; index < descendants.getLength(); index++) {
      if (value.equals(((Element) descendants.item(index)).getAttribute(attribute))) {
        return true;
      }
    }
    return false;
  }

  private static Element firstNodeFillElement(Element node) {
    NodeList descendants = node.getElementsByTagName("*");
    for (int index = 0; index < descendants.getLength(); index++) {
      Element element = (Element) descendants.item(index);
      if (!element.getAttribute("data-dediren-node-shape").isBlank()) {
        if (!effective(element, "fill", "").isBlank()) {
          return element;
        }
        NodeList shapeParts = element.getElementsByTagName("*");
        for (int part = 0; part < shapeParts.getLength(); part++) {
          Element candidate = (Element) shapeParts.item(part);
          if (!effective(candidate, "fill", "").isBlank()) {
            return candidate;
          }
        }
      }
    }
    return null;
  }

  private static double[] pageBackground(Document document) {
    Element root = document.getDocumentElement();
    NodeList children = root.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (children.item(index) instanceof Element element && "rect".equals(localName(element))) {
        double[] color = parseColor(effective(element, "fill", "#ffffff"));
        if (color != null) {
          return color;
        }
      }
    }
    return new double[] {255, 255, 255};
  }

  private static double[] parseColor(String value) {
    if (value == null) {
      return null;
    }
    if (value.matches("#[0-9a-fA-F]{6}")) {
      return new double[] {
        Integer.parseInt(value.substring(1, 3), 16),
        Integer.parseInt(value.substring(3, 5), 16),
        Integer.parseInt(value.substring(5, 7), 16)
      };
    }
    if (value.matches("#[0-9a-fA-F]{3}")) {
      return new double[] {
        Integer.parseInt(value.substring(1, 2) + value.substring(1, 2), 16),
        Integer.parseInt(value.substring(2, 3) + value.substring(2, 3), 16),
        Integer.parseInt(value.substring(3, 4) + value.substring(3, 4), 16)
      };
    }
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "black" -> new double[] {0, 0, 0};
      case "white" -> new double[] {255, 255, 255};
      default -> null;
    };
  }

  private static double[] composite(double[] foreground, double alpha, double[] background) {
    return new double[] {
      foreground[0] * alpha + background[0] * (1 - alpha),
      foreground[1] * alpha + background[1] * (1 - alpha),
      foreground[2] * alpha + background[2] * (1 - alpha)
    };
  }

  private static double contrastRatio(double[] foreground, double[] background) {
    double lighter = Math.max(luminance(foreground), luminance(background));
    double darker = Math.min(luminance(foreground), luminance(background));
    return (lighter + 0.05) / (darker + 0.05);
  }

  private static double luminance(double[] color) {
    double red = linear(color[0]);
    double green = linear(color[1]);
    double blue = linear(color[2]);
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
  }

  private static double linear(double channel) {
    double normalized = channel / 255.0;
    return normalized <= 0.04045 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
  }
}

package dev.dediren.testsupport;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

/** Assertions for the standards-derived SVG 2 subset exercised by Dediren's generators. */
public final class Svg2SubsetAssertions {
  private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";
  private static final String XML_NAMESPACE = XMLConstants.XML_NS_URI;
  private static final String XMLNS_NAMESPACE = XMLConstants.XMLNS_ATTRIBUTE_NS_URI;

  private static final String NUMBER_SOURCE =
      "[+-]?(?:(?:\\d+(?:\\.\\d*)?)|(?:\\.\\d+))(?:[eE][+-]?\\d+)?";
  private static final Pattern NUMBER = Pattern.compile("^(?:" + NUMBER_SOURCE + ")$");
  private static final Pattern NUMBER_TOKEN = Pattern.compile(NUMBER_SOURCE);
  private static final Pattern ID = Pattern.compile("[A-Za-z_][A-Za-z0-9._-]*");
  private static final Pattern DATA_ATTRIBUTE = Pattern.compile("data-[a-z0-9][a-z0-9._-]*");
  private static final Pattern HEX_COLOR =
      Pattern.compile("#[0-9a-fA-F]{3,4}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}");
  private static final Pattern URL_REFERENCE =
      Pattern.compile("url\\(#([A-Za-z_][A-Za-z0-9._-]*)\\)");
  private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{1,8}(?:-[A-Za-z0-9]{1,8})*");

  private static final Set<String> ROOT_CHILDREN =
      Set.of(
          "metadata",
          "title",
          "desc",
          "g",
          "marker",
          "linearGradient",
          "radialGradient",
          "path",
          "rect",
          "circle",
          "ellipse",
          "line",
          "polyline",
          "polygon",
          "text");
  private static final Set<String> GROUP_CHILDREN =
      Set.of(
          "g",
          "marker",
          "linearGradient",
          "radialGradient",
          "path",
          "rect",
          "circle",
          "ellipse",
          "line",
          "polyline",
          "polygon",
          "text");
  private static final Set<String> MARKER_CHILDREN =
      Set.of("g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon");

  private static final Map<String, Set<String>> ALLOWED_CHILDREN =
      Map.ofEntries(
          Map.entry("svg", ROOT_CHILDREN),
          Map.entry("metadata", Set.of()),
          Map.entry("title", Set.of()),
          Map.entry("desc", Set.of()),
          Map.entry("g", GROUP_CHILDREN),
          Map.entry("marker", MARKER_CHILDREN),
          Map.entry("linearGradient", Set.of("stop")),
          Map.entry("radialGradient", Set.of("stop")),
          Map.entry("stop", Set.of()),
          Map.entry("path", Set.of()),
          Map.entry("rect", Set.of()),
          Map.entry("circle", Set.of()),
          Map.entry("ellipse", Set.of()),
          Map.entry("line", Set.of()),
          Map.entry("polyline", Set.of()),
          Map.entry("polygon", Set.of()),
          Map.entry("text", Set.of("tspan")),
          Map.entry("tspan", Set.of()));

  private static final Set<String> GROUP_PRESENTATION =
      Set.of(
          "font-family",
          "font-size",
          "font-weight",
          "font-style",
          "fill-opacity",
          "stroke-opacity",
          "stroke-dasharray");
  private static final Set<String> SHAPE_PRESENTATION =
      Set.of(
          "fill",
          "fill-opacity",
          "stroke",
          "stroke-width",
          "stroke-opacity",
          "stroke-dasharray",
          "stroke-linecap",
          "stroke-linejoin",
          "marker-start",
          "marker-end");
  private static final Set<String> TEXT_PRESENTATION =
      Set.of(
          "x",
          "y",
          "text-anchor",
          "dominant-baseline",
          "fill",
          "fill-opacity",
          "stroke",
          "stroke-width",
          "font-family",
          "font-size",
          "font-weight",
          "font-style",
          "textLength",
          "lengthAdjust");

  private static final Map<String, Set<String>> ALLOWED_ATTRIBUTES =
      Map.ofEntries(
          Map.entry("svg", Set.of("role", "width", "height", "viewBox", "direction", "xml:lang")),
          Map.entry("metadata", Set.of()),
          Map.entry("title", Set.of()),
          Map.entry("desc", Set.of()),
          Map.entry("g", GROUP_PRESENTATION),
          Map.entry("marker", Set.of("markerWidth", "markerHeight", "refX", "refY", "orient")),
          Map.entry("linearGradient", Set.of("x1", "y1", "x2", "y2")),
          Map.entry("radialGradient", Set.of()),
          Map.entry("stop", Set.of("offset", "stop-color", "stop-opacity")),
          Map.entry("path", with(SHAPE_PRESENTATION, "d")),
          Map.entry("rect", with(SHAPE_PRESENTATION, "x", "y", "width", "height", "rx", "ry")),
          Map.entry("circle", with(SHAPE_PRESENTATION, "cx", "cy", "r")),
          Map.entry("ellipse", with(SHAPE_PRESENTATION, "cx", "cy", "rx", "ry")),
          Map.entry("line", with(SHAPE_PRESENTATION, "x1", "y1", "x2", "y2")),
          Map.entry("polyline", with(SHAPE_PRESENTATION, "points")),
          Map.entry("polygon", with(SHAPE_PRESENTATION, "points")),
          Map.entry("text", TEXT_PRESENTATION),
          Map.entry("tspan", Set.of("x", "dy", "textLength", "lengthAdjust")));

  private static final Set<String> CSS_COLOR_KEYWORDS =
      Set.of(
          "aliceblue",
          "antiquewhite",
          "aqua",
          "aquamarine",
          "azure",
          "beige",
          "bisque",
          "black",
          "blanchedalmond",
          "blue",
          "blueviolet",
          "brown",
          "burlywood",
          "cadetblue",
          "chartreuse",
          "chocolate",
          "coral",
          "cornflowerblue",
          "cornsilk",
          "crimson",
          "cyan",
          "darkblue",
          "darkcyan",
          "darkgoldenrod",
          "darkgray",
          "darkgreen",
          "darkgrey",
          "darkkhaki",
          "darkmagenta",
          "darkolivegreen",
          "darkorange",
          "darkorchid",
          "darkred",
          "darksalmon",
          "darkseagreen",
          "darkslateblue",
          "darkslategray",
          "darkslategrey",
          "darkturquoise",
          "darkviolet",
          "deeppink",
          "deepskyblue",
          "dimgray",
          "dimgrey",
          "dodgerblue",
          "firebrick",
          "floralwhite",
          "forestgreen",
          "fuchsia",
          "gainsboro",
          "ghostwhite",
          "gold",
          "goldenrod",
          "gray",
          "green",
          "greenyellow",
          "grey",
          "honeydew",
          "hotpink",
          "indianred",
          "indigo",
          "ivory",
          "khaki",
          "lavender",
          "lavenderblush",
          "lawngreen",
          "lemonchiffon",
          "lightblue",
          "lightcoral",
          "lightcyan",
          "lightgoldenrodyellow",
          "lightgray",
          "lightgreen",
          "lightgrey",
          "lightpink",
          "lightsalmon",
          "lightseagreen",
          "lightskyblue",
          "lightslategray",
          "lightslategrey",
          "lightsteelblue",
          "lightyellow",
          "lime",
          "limegreen",
          "linen",
          "magenta",
          "maroon",
          "mediumaquamarine",
          "mediumblue",
          "mediumorchid",
          "mediumpurple",
          "mediumseagreen",
          "mediumslateblue",
          "mediumspringgreen",
          "mediumturquoise",
          "mediumvioletred",
          "midnightblue",
          "mintcream",
          "mistyrose",
          "moccasin",
          "navajowhite",
          "navy",
          "oldlace",
          "olive",
          "olivedrab",
          "orange",
          "orangered",
          "orchid",
          "palegoldenrod",
          "palegreen",
          "paleturquoise",
          "palevioletred",
          "papayawhip",
          "peachpuff",
          "peru",
          "pink",
          "plum",
          "powderblue",
          "purple",
          "rebeccapurple",
          "red",
          "rosybrown",
          "royalblue",
          "saddlebrown",
          "salmon",
          "sandybrown",
          "seagreen",
          "seashell",
          "sienna",
          "silver",
          "skyblue",
          "slateblue",
          "slategray",
          "slategrey",
          "snow",
          "springgreen",
          "steelblue",
          "tan",
          "teal",
          "thistle",
          "tomato",
          "transparent",
          "turquoise",
          "violet",
          "wheat",
          "white",
          "whitesmoke",
          "yellow",
          "yellowgreen",
          "currentcolor");

  private Svg2SubsetAssertions() {}

  /**
   * Parses {@code svg} securely and throws {@link AssertionError} on the first subset violation.
   */
  public static void assertConforms(String svg) {
    if (svg == null) {
      fail("SVG input is null");
    }
    Document document = parse(svg);
    Element root = document.getDocumentElement();
    if (root == null || !"svg".equals(root.getLocalName())) {
      fail("document root must be element <svg>");
    }
    if (!SVG_NAMESPACE.equals(root.getNamespaceURI())) {
      fail("root <svg> must use the SVG namespace " + SVG_NAMESPACE);
    }
    if (!SVG_NAMESPACE.equals(root.lookupNamespaceURI(null))) {
      fail("root <svg> must declare the default SVG namespace " + SVG_NAMESPACE);
    }

    Validation validation = new Validation();
    validation.element(root, null);
    validation.references();
  }

  private static Document parse(String svg) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(
          new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) throws SAXParseException {
              throw exception;
            }

            @Override
            public void error(SAXParseException exception) throws SAXParseException {
              throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXParseException {
              throw exception;
            }
          });
      return builder.parse(new InputSource(new StringReader(svg)));
    } catch (Exception error) {
      throw new AssertionError("SVG is not well-formed secure XML: " + error.getMessage(), error);
    }
  }

  private static Set<String> with(Set<String> base, String... additions) {
    Set<String> result = new HashSet<>(base);
    result.addAll(List.of(additions));
    return Set.copyOf(result);
  }

  private static void fail(String message) {
    throw new AssertionError("SVG 2 subset violation: " + message);
  }

  private static final class Validation {
    private final Map<String, String> ids = new HashMap<>();
    private final List<Reference> pendingReferences = new ArrayList<>();

    void element(Element element, String parentName) {
      String name = element.getLocalName();
      if (!SVG_NAMESPACE.equals(element.getNamespaceURI())) {
        fail("element <" + element.getTagName() + "> must use the SVG namespace");
      }
      if (!ALLOWED_CHILDREN.containsKey(name)) {
        fail("unknown element <" + name + ">");
      }
      if (parentName != null && !ALLOWED_CHILDREN.get(parentName).contains(name)) {
        fail("element <" + name + "> is not allowed inside <" + parentName + ">");
      }

      attributes(element, name);
      children(element, name);
    }

    private void attributes(Element element, String name) {
      NamedNodeMap attributes = element.getAttributes();
      for (int index = 0; index < attributes.getLength(); index++) {
        Attr attribute = (Attr) attributes.item(index);
        String attributeName = attribute.getName();
        String namespace = attribute.getNamespaceURI();
        if (XMLNS_NAMESPACE.equals(namespace)) {
          if (element.getParentNode() instanceof Document
              && "xmlns".equals(attributeName)
              && SVG_NAMESPACE.equals(attribute.getValue())) {
            continue;
          }
          fail("unsupported namespace declaration " + attributeName);
        }
        if (XML_NAMESPACE.equals(namespace)) {
          if (!("svg".equals(name) && "xml:lang".equals(attributeName))) {
            fail("attribute " + attributeName + " is not allowed on <" + name + ">");
          }
        } else if (namespace != null) {
          fail("attribute " + attributeName + " must not use a foreign namespace");
        }

        if ("id".equals(attributeName)) {
          id(element, attribute.getValue());
          continue;
        }
        if (attributeName.startsWith("data-")) {
          if (!DATA_ATTRIBUTE.matcher(attributeName).matches()) {
            fail("invalid lowercase data-* attribute " + attributeName);
          }
          continue;
        }
        if (!ALLOWED_ATTRIBUTES.get(name).contains(attributeName)) {
          fail("attribute " + name + "@" + attributeName + " is not allowed");
        }
        value(name, attributeName, attribute.getValue());
      }
    }

    private void id(Element element, String value) {
      if (!ID.matcher(value).matches()) {
        fail("invalid XML-safe id on <" + element.getLocalName() + ">: " + value);
      }
      String previous = ids.putIfAbsent(value, element.getLocalName());
      if (previous != null) {
        fail(
            "duplicate id "
                + value
                + " on <"
                + previous
                + "> and <"
                + element.getLocalName()
                + ">");
      }
    }

    private void children(Element element, String name) {
      NodeList children = element.getChildNodes();
      for (int index = 0; index < children.getLength(); index++) {
        Node child = children.item(index);
        if (child instanceof Element childElement) {
          element(childElement, name);
        } else if ((child.getNodeType() == Node.TEXT_NODE
                || child.getNodeType() == Node.CDATA_SECTION_NODE)
            && !child.getNodeValue().isBlank()
            && !Set.of("metadata", "title", "desc", "text", "tspan").contains(name)) {
          fail("non-whitespace text is not allowed inside <" + name + ">");
        } else if (child.getNodeType() != Node.TEXT_NODE
            && child.getNodeType() != Node.CDATA_SECTION_NODE
            && child.getNodeType() != Node.COMMENT_NODE) {
          fail("node type " + child.getNodeType() + " is not allowed inside <" + name + ">");
        }
      }
    }

    private void value(String element, String attribute, String value) {
      switch (attribute) {
        case "x", "y", "cx", "cy", "x1", "y1", "x2", "y2", "refX", "refY", "dy" ->
            finiteNumber(element, attribute, value);
        case "width", "height", "r", "rx", "ry", "stroke-width", "font-size", "textLength" ->
            nonNegativeNumber(element, attribute, value);
        case "markerWidth", "markerHeight" -> positiveNumber(element, attribute, value);
        case "fill-opacity", "stroke-opacity", "stop-opacity", "offset" ->
            range(element, attribute, value, 0.0, 1.0);
        case "viewBox" -> viewBox(value);
        case "stroke-dasharray" -> dashArray(element, value);
        case "points" -> points(element, value);
        case "d" -> path(value);
        case "fill", "stroke" -> paint(element, attribute, value, true);
        case "stop-color" -> paint(element, attribute, value, false);
        case "marker-start", "marker-end" -> markerReference(element, attribute, value);
        case "text-anchor" -> enumeration(element, attribute, value, "start", "middle", "end");
        case "dominant-baseline" -> enumeration(element, attribute, value, "middle");
        case "stroke-linecap" -> enumeration(element, attribute, value, "butt", "round", "square");
        case "stroke-linejoin" -> enumeration(element, attribute, value, "miter", "round", "bevel");
        case "font-style" -> enumeration(element, attribute, value, "normal", "italic", "oblique");
        case "font-weight" -> fontWeight(element, value);
        case "lengthAdjust" -> enumeration(element, attribute, value, "spacing");
        case "orient" -> orient(element, value);
        case "direction" -> enumeration(element, attribute, value, "ltr", "rtl");
        case "role" -> enumeration(element, attribute, value, "img");
        case "xml:lang" -> {
          if (!LANGUAGE.matcher(value).matches()) {
            fail("invalid svg@xml:lang value " + value);
          }
        }
        case "font-family" -> {
          if (value.isBlank()) {
            fail("empty " + element + "@font-family");
          }
        }
        default -> {
          // All remaining attributes are unrestricted strings in the exercised subset.
        }
      }
    }

    private void finiteNumber(String element, String attribute, String value) {
      if (!NUMBER.matcher(value).matches()) {
        fail("invalid number at " + element + "@" + attribute + ": " + value);
      }
      double parsed = Double.parseDouble(value);
      if (!Double.isFinite(parsed)) {
        fail("non-finite number at " + element + "@" + attribute + ": " + value);
      }
    }

    private void nonNegativeNumber(String element, String attribute, String value) {
      finiteNumber(element, attribute, value);
      if (Double.parseDouble(value) < 0.0) {
        fail(element + "@" + attribute + " must be non-negative");
      }
    }

    private void positiveNumber(String element, String attribute, String value) {
      finiteNumber(element, attribute, value);
      if (Double.parseDouble(value) <= 0.0) {
        fail(element + "@" + attribute + " must be positive");
      }
    }

    private void range(
        String element, String attribute, String value, double minimum, double maximum) {
      finiteNumber(element, attribute, value);
      double parsed = Double.parseDouble(value);
      if (parsed < minimum || parsed > maximum) {
        fail(element + "@" + attribute + " must be in [" + minimum + ", " + maximum + "]");
      }
    }

    private void viewBox(String value) {
      List<String> numbers = numberList("svg@viewBox", value, 4);
      if (Double.parseDouble(numbers.get(2)) <= 0.0 || Double.parseDouble(numbers.get(3)) <= 0.0) {
        fail("svg@viewBox width and height must be positive");
      }
    }

    private void dashArray(String element, String value) {
      if ("none".equals(value)) {
        return;
      }
      List<String> numbers = numberList(element + "@stroke-dasharray", value, -1);
      if (numbers.isEmpty()) {
        fail(element + "@stroke-dasharray must contain positive numbers");
      }
      for (String number : numbers) {
        if (Double.parseDouble(number) <= 0.0) {
          fail(element + "@stroke-dasharray entries must be positive");
        }
      }
    }

    private void points(String element, String value) {
      List<String> numbers = numberList(element + "@points", value, -1);
      if (numbers.size() < 4 || numbers.size() % 2 != 0) {
        fail(element + "@points must contain two or more coordinate pairs");
      }
    }

    private List<String> numberList(String location, String value, int requiredCount) {
      String trimmed = value.trim();
      if (trimmed.isEmpty()
          || trimmed.startsWith(",")
          || trimmed.endsWith(",")
          || Pattern.compile(",\\s*,").matcher(trimmed).find()) {
        fail("invalid number list at " + location + ": " + value);
      }
      List<String> result = List.of(trimmed.split("(?:\\s*,\\s*|\\s+)", -1));
      for (String number : result) {
        finiteNumber(location, "value", number);
      }
      if (requiredCount >= 0 && result.size() != requiredCount) {
        fail(location + " must contain exactly " + requiredCount + " numbers");
      }
      return result;
    }

    private void paint(String element, String attribute, String value, boolean allowNone) {
      if (allowNone && "none".equalsIgnoreCase(value)) {
        return;
      }
      Matcher reference = URL_REFERENCE.matcher(value);
      if (reference.matches()) {
        pendingReferences.add(
            new Reference(element + "@" + attribute, reference.group(1), "gradient"));
        return;
      }
      String normalized = value.toLowerCase(Locale.ROOT);
      if (HEX_COLOR.matcher(value).matches()
          || CSS_COLOR_KEYWORDS.contains(normalized)
          || functionalColor(normalized)) {
        return;
      }
      fail("invalid paint at " + element + "@" + attribute + ": " + value);
    }

    private boolean functionalColor(String value) {
      boolean rgba = value.startsWith("rgba(") && value.endsWith(")");
      boolean rgb = value.startsWith("rgb(") && value.endsWith(")");
      if (!rgb && !rgba) {
        return false;
      }
      String body = value.substring(value.indexOf('(') + 1, value.length() - 1);
      String[] components = body.split(",", -1);
      if (components.length != (rgba ? 4 : 3)) {
        return false;
      }
      for (int index = 0; index < 3; index++) {
        String component = components[index].trim();
        if (!component.matches("\\d{1,3}%?")) {
          return false;
        }
        int amount = Integer.parseInt(component.replace("%", ""));
        if (amount > (component.endsWith("%") ? 100 : 255)) {
          return false;
        }
      }
      if (rgba) {
        String alpha = components[3].trim();
        if (alpha.endsWith("%")) {
          String percentage = alpha.substring(0, alpha.length() - 1);
          return NUMBER.matcher(percentage).matches()
              && Double.parseDouble(percentage) >= 0.0
              && Double.parseDouble(percentage) <= 100.0;
        }
        return NUMBER.matcher(alpha).matches()
            && Double.parseDouble(alpha) >= 0.0
            && Double.parseDouble(alpha) <= 1.0;
      }
      return true;
    }

    private void markerReference(String element, String attribute, String value) {
      if ("none".equals(value)) {
        return;
      }
      Matcher reference = URL_REFERENCE.matcher(value);
      if (!reference.matches()) {
        fail("invalid same-document marker reference at " + element + "@" + attribute);
      }
      pendingReferences.add(new Reference(element + "@" + attribute, reference.group(1), "marker"));
    }

    private void enumeration(
        String element, String attribute, String value, String... allowedValues) {
      if (!Set.of(allowedValues).contains(value)) {
        fail("invalid " + element + "@" + attribute + " value " + value);
      }
    }

    private void fontWeight(String element, String value) {
      if (Set.of("normal", "bold", "bolder", "lighter").contains(value)) {
        return;
      }
      try {
        int numeric = Integer.parseInt(value);
        if (numeric >= 100 && numeric <= 900 && numeric % 100 == 0) {
          return;
        }
      } catch (NumberFormatException ignored) {
        // The failure below owns the public diagnostic.
      }
      fail("invalid " + element + "@font-weight value " + value);
    }

    private void orient(String element, String value) {
      if ("auto".equals(value) || "auto-start-reverse".equals(value)) {
        return;
      }
      finiteNumber(element, "orient", value);
    }

    private void path(String data) {
      List<PathToken> tokens = pathTokens(data);
      if (tokens.isEmpty()
          || tokens.getFirst().kind() != PathTokenKind.COMMAND
          || tokens.getFirst().text().charAt(0) != 'M') {
        fail("path@d must begin with M");
      }
      Character command = null;
      int index = 0;
      while (index < tokens.size()) {
        PathToken token = tokens.get(index);
        if (token.kind() == PathTokenKind.COMMAND) {
          command = token.text().charAt(0);
          index++;
          if (command == 'Z') {
            command = null;
            continue;
          }
        }
        if (command == null) {
          fail("path@d has parameters without a command");
        }
        int start = index;
        while (index < tokens.size() && tokens.get(index).kind() == PathTokenKind.NUMBER) {
          index++;
        }
        int count = index - start;
        int arity = pathArity(command);
        if (count == 0 || count % arity != 0) {
          fail("path@d command " + command + " requires parameter groups of " + arity);
        }
        if (command == 'A') {
          for (int offset = start; offset < index; offset += arity) {
            if (Double.parseDouble(tokens.get(offset).text()) < 0.0
                || Double.parseDouble(tokens.get(offset + 1).text()) < 0.0) {
              fail("path@d arc radii must be non-negative");
            }
            if (!Set.of("0", "1").contains(tokens.get(offset + 3).text())
                || !Set.of("0", "1").contains(tokens.get(offset + 4).text())) {
              fail("path@d arc flags must be 0 or 1");
            }
          }
        }
      }
    }

    private List<PathToken> pathTokens(String data) {
      List<PathToken> tokens = new ArrayList<>();
      int index = 0;
      boolean commaPending = false;
      while (index < data.length()) {
        char character = data.charAt(index);
        if (Character.isWhitespace(character)) {
          index++;
          continue;
        }
        if (character == ',') {
          if (tokens.isEmpty() || tokens.getLast().kind() != PathTokenKind.NUMBER || commaPending) {
            fail("path@d contains a misplaced comma");
          }
          commaPending = true;
          index++;
          continue;
        }
        if (Character.isLetter(character)) {
          if (commaPending || "MLHVQCAZ".indexOf(character) < 0) {
            fail("path@d contains unsupported or misplaced command " + character);
          }
          tokens.add(new PathToken(PathTokenKind.COMMAND, Character.toString(character)));
          index++;
          continue;
        }
        Matcher number = NUMBER_TOKEN.matcher(data);
        number.region(index, data.length());
        if (!number.lookingAt()) {
          fail("path@d contains malformed syntax near " + data.substring(index));
        }
        tokens.add(new PathToken(PathTokenKind.NUMBER, number.group()));
        commaPending = false;
        index = number.end();
      }
      if (commaPending) {
        fail("path@d must not end with a comma");
      }
      return tokens;
    }

    private int pathArity(char command) {
      return switch (command) {
        case 'M', 'L' -> 2;
        case 'H', 'V' -> 1;
        case 'C' -> 6;
        case 'Q' -> 4;
        case 'A' -> 7;
        default -> throw new AssertionError("unexpected path command " + command);
      };
    }

    void references() {
      for (Reference reference : pendingReferences) {
        String target = ids.get(reference.id());
        if (target == null) {
          fail(reference.location() + " references missing id " + reference.id());
        }
        if ("gradient".equals(reference.expectedKind())
            && !Set.of("linearGradient", "radialGradient").contains(target)) {
          fail(
              reference.location()
                  + " paint reference must target a gradient, not <"
                  + target
                  + ">");
        }
        if ("marker".equals(reference.expectedKind()) && !"marker".equals(target)) {
          fail(
              reference.location()
                  + " marker reference must target <marker>, not <"
                  + target
                  + ">");
        }
      }
    }
  }

  private enum PathTokenKind {
    COMMAND,
    NUMBER
  }

  private record PathToken(PathTokenKind kind, String text) {}

  private record Reference(String location, String id, String expectedKind) {}
}

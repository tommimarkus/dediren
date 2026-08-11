package dev.dediren.plugins.render;

import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import javax.imageio.ImageIO;
import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.ExternalResourceSecurity;
import org.apache.batik.bridge.FontFace;
import org.apache.batik.bridge.FontFamilyResolver;
import org.apache.batik.bridge.GVTBuilder;
import org.apache.batik.bridge.NoLoadExternalResourceSecurity;
import org.apache.batik.bridge.NoLoadScriptSecurity;
import org.apache.batik.bridge.ScriptSecurity;
import org.apache.batik.bridge.UserAgentAdapter;
import org.apache.batik.ext.awt.RenderingHintsKeyExt;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.batik.gvt.font.AWTFontFamily;
import org.apache.batik.gvt.font.GVTFontFace;
import org.apache.batik.gvt.font.GVTFontFamily;
import org.apache.batik.util.ParsedURL;
import org.apache.batik.util.XMLResourceDescriptor;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.svg.SVGDocument;

/** Deterministic, test-only Batik boundary for static SVG paint inspection. */
final class BatikTestSupport {

  static final String PINNED_BATIK_VERSION = "1.19";
  static final Path WORKSPACE_ROOT = requiredPathProperty("dediren.workspace.root");
  static final Path OUTPUT_ROOT = WORKSPACE_ROOT.resolve(".test-output/render-paint");
  static final Path FONT_PATH =
      WORKSPACE_ROOT.resolve("engines/render/src/test/resources/fonts/LiberationSans-Regular.ttf");

  private static final Font BUNDLED_FONT = loadBundledFont();
  private static final GVTFontFamily BUNDLED_FAMILY =
      new AWTFontFamily(new GVTFontFace(BUNDLED_FONT.getFamily()), BUNDLED_FONT);
  private static final FontFamilyResolver FONT_RESOLVER = new BundledFontResolver();

  private BatikTestSupport() {}

  static String version() {
    String version = BridgeContext.class.getPackage().getImplementationVersion();
    return version == null ? PINNED_BATIK_VERSION : version;
  }

  static String fontFamily() {
    return BUNDLED_FONT.getFamily();
  }

  static boolean canDisplay(String text) {
    return BUNDLED_FONT.canDisplayUpTo(text) == -1;
  }

  static BuiltSvg build(String svg) throws IOException {
    if (svg.contains("<!DOCTYPE")) {
      throw new IllegalArgumentException("paint-test SVG must not contain a document type");
    }

    SAXSVGDocumentFactory factory =
        new SAXSVGDocumentFactory(XMLResourceDescriptor.getXMLParserClassName(), false);
    SVGDocument document =
        factory.createSVGDocument("file:/dediren-render-paint.svg", new StringReader(svg));
    rejectDynamicElements(document);
    UserAgentAdapter userAgent = new StaticPaintUserAgent();
    BridgeContext context = new BridgeContext(userAgent);
    // Batik retains the DOM-to-GVT bindings needed by the oracle only while building a dynamic
    // context. The input is nevertheless static: animation and script elements are rejected, no
    // update manager is started, and the context is disposed with the BuiltSvg.
    context.setDynamicState(BridgeContext.DYNAMIC);
    GraphicsNode root = new GVTBuilder().build(context, document);
    return new BuiltSvg(document, context, root);
  }

  static void rasterize(BuiltSvg svg, Path png) throws IOException {
    Element root = svg.document().getDocumentElement();
    int width = positiveDimension(root.getAttribute("width"), "width");
    int height = positiveDimension(root.getAttribute("height"), "height");
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    var graphics = image.createGraphics();
    try {
      configureGraphics(image, graphics);
      svg.root().paint(graphics);
    } finally {
      graphics.dispose();
    }
    Files.createDirectories(png.toAbsolutePath().normalize().getParent());
    if (!ImageIO.write(image, "png", png.toFile())) {
      throw new IOException("the JDK has no PNG ImageIO writer");
    }
  }

  static BufferedImage rasterizeNonTextNodes(BuiltSvg svg, Collection<String> ids) {
    Element root = svg.document().getDocumentElement();
    int width = positiveDimension(root.getAttribute("width"), "width");
    int height = positiveDimension(root.getAttribute("height"), "height");
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    var graphics = image.createGraphics();
    try {
      configureGraphics(image, graphics);
      for (String id : ids) {
        GraphicsNode node = svg.nonTextGraphicsNode(id);
        AffineTransform original = graphics.getTransform();
        graphics.transform(svg.parentTransform(node));
        node.paint(graphics);
        graphics.setTransform(original);
      }
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private static void configureGraphics(BufferedImage image, java.awt.Graphics2D graphics) {
    graphics.setRenderingHint(RenderingHintsKeyExt.KEY_BUFFERED_IMAGE, new WeakReference<>(image));
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    graphics.setRenderingHint(
        RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    graphics.setRenderingHint(
        RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
    graphics.setRenderingHint(
        RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
    graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
  }

  private static int positiveDimension(String value, String name) {
    double dimension = Double.parseDouble(value);
    if (!Double.isFinite(dimension) || dimension <= 0 || dimension > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("invalid SVG " + name + ": " + value);
    }
    return (int) Math.ceil(dimension);
  }

  private static void rejectDynamicElements(SVGDocument document) {
    NodeList all = document.getElementsByTagName("*");
    for (int index = 0; index < all.getLength(); index++) {
      String localName = all.item(index).getLocalName();
      if ("script".equals(localName)
          || "set".equals(localName)
          || "discard".equals(localName)
          || (localName != null && localName.startsWith("animate"))) {
        throw new IllegalArgumentException(
            "paint-test SVG must be static; prohibited element: " + localName);
      }
    }
  }

  private static Font loadBundledFont() {
    if (!Files.isReadable(FONT_PATH)) {
      throw new IllegalStateException("bundled Liberation Sans is not readable: " + FONT_PATH);
    }
    try (InputStream input = Files.newInputStream(FONT_PATH)) {
      return Font.createFont(Font.TRUETYPE_FONT, input);
    } catch (Exception error) {
      throw new IllegalStateException("could not load bundled Liberation Sans", error);
    }
  }

  private static Path requiredPathProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("required system property is missing: " + name);
    }
    return Path.of(value).toAbsolutePath().normalize();
  }

  record BuiltSvg(SVGDocument document, BridgeContext context, GraphicsNode root)
      implements AutoCloseable {

    Viewport viewport() {
      Element rootElement = document.getDocumentElement();
      String[] values = rootElement.getAttribute("viewBox").trim().split("[ ,]+");
      if (values.length != 4) {
        throw new IllegalArgumentException("SVG needs a four-number viewBox");
      }
      return new Viewport(
          Double.parseDouble(values[0]),
          Double.parseDouble(values[1]),
          Double.parseDouble(values[2]),
          Double.parseDouble(values[3]),
          positiveDimension(rootElement.getAttribute("width"), "width"),
          positiveDimension(rootElement.getAttribute("height"), "height"),
          optionalNumber(rootElement, "data-dediren-paint-audit-offset-x"),
          optionalNumber(rootElement, "data-dediren-paint-audit-offset-y"));
    }

    private static double optionalNumber(Element element, String attribute) {
      return element.hasAttribute(attribute)
          ? Double.parseDouble(element.getAttribute(attribute))
          : 0;
    }

    GraphicsNode graphicsNode(String id) {
      Element element = element(id);
      GraphicsNode graphicsNode = context.getGraphicsNode(element);
      if (graphicsNode == null) {
        throw new IllegalArgumentException("element has no painted graphics node: " + id);
      }
      return graphicsNode;
    }

    Rectangle2D transformedNonTextPaintBounds(String id) {
      GraphicsNode node = nonTextGraphicsNode(id);
      Rectangle2D bounds = node.getBounds();
      return bounds == null
          ? null
          : viewport()
              .toUserBounds(node.getGlobalTransform().createTransformedShape(bounds).getBounds2D());
    }

    Rectangle2D transformedNonTextPrimitiveBounds(String id) {
      GraphicsNode node = nonTextGraphicsNode(id);
      Rectangle2D bounds = node.getPrimitiveBounds();
      return bounds == null
          ? null
          : viewport()
              .toUserBounds(node.getGlobalTransform().createTransformedShape(bounds).getBounds2D());
    }

    private AffineTransform parentTransform(GraphicsNode node) {
      return node.getParent() == null
          ? new AffineTransform()
          : node.getParent().getGlobalTransform();
    }

    private GraphicsNode nonTextGraphicsNode(String id) {
      Element element = element(id);
      if ("text".equals(element.getLocalName())) {
        throw new IllegalArgumentException(
            "Batik ignores dominant-baseline; text bounds belong to the JDK text oracle: " + id);
      }
      return graphicsNode(id);
    }

    private Element element(String id) {
      NodeList all = document.getElementsByTagName("*");
      for (int index = 0; index < all.getLength(); index++) {
        Node candidate = all.item(index);
        if (candidate instanceof Element element && id.equals(element.getAttribute("id"))) {
          return element;
        }
      }
      throw new IllegalArgumentException("SVG has no element with id: " + id);
    }

    @Override
    public void close() {
      context.dispose();
    }
  }

  record Viewport(
      double minX,
      double minY,
      double width,
      double height,
      int pixelWidth,
      int pixelHeight,
      double semanticOffsetX,
      double semanticOffsetY) {

    private double scale() {
      return Math.min(pixelWidth / width, pixelHeight / height);
    }

    private double offsetX() {
      return (pixelWidth - width * scale()) / 2.0 - minX * scale();
    }

    private double offsetY() {
      return (pixelHeight - height * scale()) / 2.0 - minY * scale();
    }

    double imageX(double userX) {
      return (userX + semanticOffsetX) * scale() + offsetX();
    }

    double imageY(double userY) {
      return (userY + semanticOffsetY) * scale() + offsetY();
    }

    double userX(double imageX) {
      return (imageX - offsetX()) / scale() - semanticOffsetX;
    }

    double userY(double imageY) {
      return (imageY - offsetY()) / scale() - semanticOffsetY;
    }

    Rectangle2D toUserBounds(Rectangle2D imageBounds) {
      if (imageBounds == null) {
        return null;
      }
      return new Rectangle2D.Double(
          userX(imageBounds.getX()),
          userY(imageBounds.getY()),
          imageBounds.getWidth() / scale(),
          imageBounds.getHeight() / scale());
    }
  }

  private static final class StaticPaintUserAgent extends UserAgentAdapter {

    @Override
    public String getDefaultFontFamily() {
      return BUNDLED_FONT.getFamily();
    }

    @Override
    public FontFamilyResolver getFontFamilyResolver() {
      return FONT_RESOLVER;
    }

    @Override
    public ScriptSecurity getScriptSecurity(
        String scriptType, ParsedURL scriptUrl, ParsedURL documentUrl) {
      return new NoLoadScriptSecurity(scriptType);
    }

    @Override
    public ExternalResourceSecurity getExternalResourceSecurity(
        ParsedURL resourceUrl, ParsedURL documentUrl) {
      return new NoLoadExternalResourceSecurity();
    }
  }

  private static final class BundledFontResolver implements FontFamilyResolver {

    @Override
    public GVTFontFamily resolve(String familyName) {
      return BUNDLED_FAMILY;
    }

    @Override
    public GVTFontFamily resolve(String familyName, FontFace fontFace) {
      return BUNDLED_FAMILY;
    }

    @Override
    public GVTFontFamily loadFont(InputStream input, FontFace fontFace) {
      return BUNDLED_FAMILY;
    }

    @Override
    public GVTFontFamily getDefault() {
      return BUNDLED_FAMILY;
    }

    @Override
    public GVTFontFamily getFamilyThatCanDisplay(char character) {
      return BUNDLED_FONT.canDisplay(character) ? BUNDLED_FAMILY : null;
    }
  }
}

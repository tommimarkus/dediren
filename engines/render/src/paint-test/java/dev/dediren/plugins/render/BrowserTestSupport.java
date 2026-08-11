package dev.dediren.plugins.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.ReducedMotion;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Deterministic, test-only Chromium boundary for static SVG paint inspection. */
final class BrowserTestSupport {

  static final String PINNED_PLAYWRIGHT_VERSION = "1.61.0";
  static final String PINNED_CHROMIUM_VERSION = "149.0.7827.55";
  static final String PINNED_CHROMIUM_REVISION = "1228";
  static final int PADDING = 32;
  static final Path WORKSPACE_ROOT = requiredPathProperty("dediren.workspace.root");
  static final Path OUTPUT_ROOT = WORKSPACE_ROOT.resolve(".test-output/render-paint");
  static final Path FONT_PATH =
      WORKSPACE_ROOT.resolve("engines/render/src/test/resources/fonts/LiberationSans-Regular.ttf");
  static final Path BROWSER_CACHE_PATH =
      requiredCachePathProperty("dediren.render.paint.browser-cache");

  static final List<String> CHROMIUM_LAUNCH_ARGS =
      List.of(
          "--disable-background-networking",
          "--disable-breakpad",
          "--disable-client-side-phishing-detection",
          "--disable-component-update",
          "--disable-crash-reporter",
          "--disable-default-apps",
          "--disable-domain-reliability",
          "--disable-extensions",
          "--disable-features=MediaRouter,OptimizationHints,Translate",
          "--disable-sync",
          "--disable-translate",
          "--no-default-browser-check",
          "--no-first-run");
  static final String LIFECYCLE_POLICY =
      "one non-persistent browser, context, and page per BrowserSvg; close() disposes all three";
  static final String NETWORK_POLICY =
      "offline context, blocked service workers, and catch-all route abort with no external URLs in admitted SVG";
  static final String TELEMETRY_POLICY =
      "background networking, component updates, domain reliability, sync, crash reporting, translation, media routing, optimization hints, extensions, and default apps disabled";

  private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";
  private static final String FONT_FAMILY = "Dediren Liberation Sans";
  private static final Pattern CSS_URL = Pattern.compile("(?i)url\\(\\s*(['\\\"]?)(.*?)\\1\\s*\\)");
  private static final Pattern EXTERNAL_SCHEME =
      Pattern.compile("(?i)(?:^|[\\s'\\\"])(?:https?|ftp|file|javascript|data):|^\\s*//");
  private static final Pattern DIMENSION =
      Pattern.compile("[+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:px)?", Pattern.CASE_INSENSITIVE);
  private static final Font BUNDLED_FONT = loadBundledFont();
  private static final String FONT_DATA = loadFontData();

  private BrowserTestSupport() {}

  static String playwrightVersion() {
    String version = Playwright.class.getPackage().getImplementationVersion();
    return version == null ? PINNED_PLAYWRIGHT_VERSION : version;
  }

  static boolean canDisplay(String text) {
    return BUNDLED_FONT.canDisplayUpTo(text) == -1;
  }

  static void validateStaticSvg(String svg) {
    parseAndValidate(svg);
  }

  static BrowserSvg build(String svg) {
    PreparedSvg prepared = prepare(svg);
    BrowserSession session = openBrowser(prepared.viewport());
    try {
      Page page = session.context().newPage();
      attachNetworkGuards(session.context(), page, session.blockedRequests(), session.responses());
      page.setContent(inlineDocument(prepared.svg()));
      boolean fontReady =
          Boolean.TRUE.equals(
              page.evaluate(
                  "async () => { const faces = await document.fonts.load('16px \\\""
                      + FONT_FAMILY
                      + "\\\"', 'Paint tree'); await document.fonts.ready; return faces.length > 0 && document.fonts.check('16px \\\""
                      + FONT_FAMILY
                      + "\\\"', 'Paint tree'); }"));
      if (!fontReady) {
        throw new IllegalStateException("bundled Liberation Sans did not become ready in Chromium");
      }
      BrowserSvg built =
          new BrowserSvg(
              prepared,
              session.playwright(),
              session.browser(),
              session.context(),
              page,
              session.blockedRequests(),
              session.responses(),
              fontReady);
      built.requirePinnedBrowser();
      return built;
    } catch (RuntimeException failure) {
      session.close();
      throw failure;
    }
  }

  static void rasterize(BrowserSvg svg, Path png) throws IOException {
    Files.createDirectories(png.toAbsolutePath().normalize().getParent());
    Files.write(png, svg.screenshot());
  }

  static BufferedImage rasterizeNodes(BrowserSvg svg, Collection<String> ids) {
    return readPng(svg.screenshotNodes(ids));
  }

  static BufferedImage rasterizeAsImage(String svg) {
    PreparedSvg prepared = prepare(svg);
    BrowserSession session = openBrowser(prepared.viewport());
    try {
      Page page = session.context().newPage();
      attachNetworkGuards(session.context(), page, session.blockedRequests(), session.responses());
      page.setContent(imageDocument());
      page.evaluate(
          "async svg => { const image=document.querySelector('img'); const url=URL.createObjectURL(new Blob([svg],{type:'image/svg+xml'})); image.src=url; await image.decode(); await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))); URL.revokeObjectURL(url); }",
          prepared.svg());
      requireVersion(session.browser());
      return readPng(screenshot(page));
    } finally {
      session.close();
    }
  }

  private static PreparedSvg prepare(String svg) {
    Document document = parseAndValidate(svg);
    Element root = document.getDocumentElement();
    Viewport viewport = viewport(root);
    Element definitions = firstDirectChild(root, "defs");
    if (definitions == null) {
      definitions = document.createElementNS(SVG_NAMESPACE, "defs");
      root.insertBefore(definitions, root.getFirstChild());
    }
    Element style = document.createElementNS(SVG_NAMESPACE, "style");
    style.setAttribute("data-dediren-browser-paint-policy", "true");
    style.setTextContent(
        "@font-face{font-family:'"
            + FONT_FAMILY
            + "';src:url(data:font/ttf;base64,"
            + FONT_DATA
            + ") format('truetype');font-style:normal;font-weight:100 900;}"
            + "text,text *{font-family:'"
            + FONT_FAMILY
            + "' !important;font-synthesis:none !important;}"
            + "*,*::before,*::after{animation:none !important;transition:none !important;caret-color:transparent !important;}");
    definitions.insertBefore(style, definitions.getFirstChild());
    return new PreparedSvg(serialize(document), viewport);
  }

  private static Document parseAndValidate(String svg) {
    if (svg == null || svg.isBlank()) {
      throw new IllegalArgumentException("paint-test SVG must not be blank");
    }
    if (svg.toLowerCase(Locale.ROOT).contains("<!doctype")) {
      throw new IllegalArgumentException("paint-test SVG must not contain a document type");
    }
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      Document document =
          factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
      Element root = document.getDocumentElement();
      if (root == null
          || !"svg".equals(root.getLocalName())
          || !SVG_NAMESPACE.equals(root.getNamespaceURI())) {
        throw new IllegalArgumentException("paint-test input must have an SVG root element");
      }
      inspectNode(document);
      return document;
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalArgumentException("paint-test SVG must be secure, well-formed XML", failure);
    }
  }

  private static void inspectNode(Node node) {
    if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
      throw new IllegalArgumentException("paint-test SVG must not contain processing instructions");
    }
    if (node instanceof Element element) {
      String name = element.getLocalName().toLowerCase(Locale.ROOT);
      if (isActiveElement(name)) {
        throw new IllegalArgumentException(
            "paint-test SVG must be static; prohibited element: " + name);
      }
      NamedNodeMap attributes = element.getAttributes();
      for (int index = 0; index < attributes.getLength(); index++) {
        Attr attribute = (Attr) attributes.item(index);
        inspectAttribute(element, attribute);
      }
      if ("style".equals(name)) {
        inspectReference("style element", element.getTextContent(), false);
      }
    }
    NodeList children = node.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      inspectNode(children.item(index));
    }
  }

  private static boolean isActiveElement(String name) {
    return name.equals("script")
        || name.equals("set")
        || name.equals("discard")
        || name.startsWith("animate")
        || name.equals("foreignobject")
        || name.equals("iframe")
        || name.equals("object")
        || name.equals("embed")
        || name.equals("audio")
        || name.equals("video")
        || name.equals("link");
  }

  private static void inspectAttribute(Element element, Attr attribute) {
    String name = attribute.getName().toLowerCase(Locale.ROOT);
    String localName = attribute.getLocalName().toLowerCase(Locale.ROOT);
    if (name.equals("xmlns") || name.startsWith("xmlns:")) {
      return;
    }
    if (localName.startsWith("on")) {
      throw new IllegalArgumentException(
          "paint-test SVG must not contain an event handler: " + attribute.getName());
    }
    boolean href = localName.equals("href");
    inspectReference(
        "attribute " + attribute.getName() + " on " + element.getLocalName(),
        attribute.getValue(),
        href);
  }

  private static void inspectReference(String source, String value, boolean href) {
    String trimmed = value.trim();
    if (href && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
      throw new IllegalArgumentException("paint-test SVG contains an external URL in " + source);
    }
    if (trimmed.toLowerCase(Locale.ROOT).contains("@import")
        || EXTERNAL_SCHEME.matcher(trimmed).find()) {
      throw new IllegalArgumentException("paint-test SVG contains an external URL in " + source);
    }
    Matcher urls = CSS_URL.matcher(trimmed);
    while (urls.find()) {
      if (!urls.group(2).trim().startsWith("#")) {
        throw new IllegalArgumentException("paint-test SVG contains an external URL in " + source);
      }
    }
  }

  private static Viewport viewport(Element root) {
    int width = positiveDimension(root.getAttribute("width"), "width");
    int height = positiveDimension(root.getAttribute("height"), "height");
    String[] values = root.getAttribute("viewBox").trim().split("[ ,]+");
    if (values.length != 4) {
      throw new IllegalArgumentException("SVG needs a four-number viewBox");
    }
    double minX = finiteNumber(values[0], "viewBox min-x");
    double minY = finiteNumber(values[1], "viewBox min-y");
    double viewWidth = positiveNumber(values[2], "viewBox width");
    double viewHeight = positiveNumber(values[3], "viewBox height");
    return new Viewport(
        minX,
        minY,
        viewWidth,
        viewHeight,
        width,
        height,
        optionalNumber(root, "data-dediren-paint-audit-offset-x"),
        optionalNumber(root, "data-dediren-paint-audit-offset-y"),
        PADDING);
  }

  private static int positiveDimension(String value, String name) {
    String trimmed = value.trim();
    if (!DIMENSION.matcher(trimmed).matches()) {
      throw new IllegalArgumentException("invalid SVG " + name + ": " + value);
    }
    String number =
        trimmed.toLowerCase(Locale.ROOT).endsWith("px")
            ? trimmed.substring(0, trimmed.length() - 2)
            : trimmed;
    double dimension = positiveNumber(number, name);
    if (dimension > 32700 - 2 * PADDING) {
      throw new IllegalArgumentException("SVG " + name + " exceeds the Chromium viewport limit");
    }
    return (int) Math.ceil(dimension);
  }

  private static double finiteNumber(String value, String name) {
    double number = Double.parseDouble(value);
    if (!Double.isFinite(number)) {
      throw new IllegalArgumentException("invalid SVG " + name + ": " + value);
    }
    return number;
  }

  private static double positiveNumber(String value, String name) {
    double number = finiteNumber(value, name);
    if (number <= 0) {
      throw new IllegalArgumentException("invalid SVG " + name + ": " + value);
    }
    return number;
  }

  private static double optionalNumber(Element element, String attribute) {
    return element.hasAttribute(attribute)
        ? finiteNumber(element.getAttribute(attribute), attribute)
        : 0;
  }

  private static BrowserSession openBrowser(Viewport viewport) {
    requireInstalledBrowser();
    Map<String, String> environment = new HashMap<>(System.getenv());
    environment.put("PLAYWRIGHT_BROWSERS_PATH", BROWSER_CACHE_PATH.toString());
    // Playwright Java otherwise installs every default browser while extracting its driver. The
    // installer lane owns the sole, headless-shell-only download.
    environment.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
    environment.put("TZ", "UTC");
    Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(environment));
    try {
      Browser browser =
          playwright
              .chromium()
              .launch(
                  new BrowserType.LaunchOptions().setHeadless(true).setArgs(CHROMIUM_LAUNCH_ARGS));
      try {
        BrowserContext context =
            browser.newContext(
                new Browser.NewContextOptions()
                    .setAcceptDownloads(false)
                    .setColorScheme(ColorScheme.LIGHT)
                    .setDeviceScaleFactor(1.0)
                    .setHasTouch(false)
                    .setIsMobile(false)
                    .setJavaScriptEnabled(true)
                    .setLocale("en-US")
                    .setOffline(true)
                    .setPermissions(List.of())
                    .setReducedMotion(ReducedMotion.REDUCE)
                    .setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                    .setTimezoneId("UTC")
                    .setViewportSize(viewport.screenshotWidth(), viewport.screenshotHeight()));
        return new BrowserSession(
            playwright,
            browser,
            context,
            new CopyOnWriteArrayList<>(),
            new CopyOnWriteArrayList<>());
      } catch (RuntimeException failure) {
        browser.close();
        throw failure;
      }
    } catch (RuntimeException failure) {
      playwright.close();
      throw failure;
    }
  }

  private static void attachNetworkGuards(
      BrowserContext context, Page page, List<String> blocked, List<String> responses) {
    context.route(
        "**/*",
        route -> {
          blocked.add(route.request().url());
          route.abort();
        });
    page.onResponse(response -> responses.add(response.url()));
  }

  private static byte[] screenshot(Page page) {
    return page.screenshot(
        new Page.ScreenshotOptions()
            .setAnimations(ScreenshotAnimations.DISABLED)
            .setFullPage(false)
            .setOmitBackground(true));
  }

  private static BufferedImage readPng(byte[] png) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
      if (image == null) {
        throw new IllegalStateException("Chromium screenshot was not a PNG image");
      }
      return image;
    } catch (IOException failure) {
      throw new IllegalStateException("could not read Chromium PNG screenshot", failure);
    }
  }

  private static String inlineDocument(String svg) {
    return "<!doctype html><html><head><meta charset=\"utf-8\"><style>html,body{margin:0;background:transparent;overflow:hidden}#audit{padding:"
        + PADDING
        + "px;width:max-content;height:max-content;line-height:0}svg{display:block;overflow:visible}</style></head><body><div id=\"audit\">"
        + svg
        + "</div></body></html>";
  }

  private static String imageDocument() {
    return "<!doctype html><html><head><meta charset=\"utf-8\"><style>html,body{margin:0;background:transparent;overflow:hidden}#audit{padding:"
        + PADDING
        + "px;width:max-content;height:max-content;line-height:0}img{display:block}</style></head><body><div id=\"audit\"><img></div></body></html>";
  }

  private static Element firstDirectChild(Element root, String localName) {
    NodeList children = root.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child instanceof Element element && localName.equals(element.getLocalName())) {
        return element;
      }
    }
    return null;
  }

  private static String serialize(Document document) {
    try {
      TransformerFactory factory = TransformerFactory.newInstance();
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      var transformer = factory.newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      StringWriter output = new StringWriter();
      transformer.transform(new DOMSource(document), new StreamResult(output));
      return output.toString();
    } catch (Exception failure) {
      throw new IllegalStateException("could not serialize admitted paint-test SVG", failure);
    }
  }

  private static Font loadBundledFont() {
    if (!Files.isReadable(FONT_PATH)) {
      throw new IllegalStateException("bundled Liberation Sans is not readable: " + FONT_PATH);
    }
    try (InputStream input = Files.newInputStream(FONT_PATH)) {
      return Font.createFont(Font.TRUETYPE_FONT, input);
    } catch (Exception failure) {
      throw new IllegalStateException("could not load bundled Liberation Sans", failure);
    }
  }

  private static String loadFontData() {
    try {
      return Base64.getEncoder().encodeToString(Files.readAllBytes(FONT_PATH));
    } catch (IOException failure) {
      throw new IllegalStateException("could not encode bundled Liberation Sans", failure);
    }
  }

  private static Path requiredPathProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("required system property is missing: " + name);
    }
    return Path.of(value).toAbsolutePath().normalize();
  }

  private static Path requiredCachePathProperty(String name) {
    Path path = requiredPathProperty(name);
    Path expected = WORKSPACE_ROOT.resolve(".cache/playwright").toAbsolutePath().normalize();
    if (!path.equals(expected)) {
      throw new IllegalStateException(
          "paint-test browser cache must be repository-local at " + expected + ", not " + path);
    }
    return path;
  }

  private static void requireInstalledBrowser() {
    Path revision =
        BROWSER_CACHE_PATH.resolve("chromium_headless_shell-" + PINNED_CHROMIUM_REVISION);
    if (!Files.isDirectory(revision)) {
      throw new IllegalStateException(
          "pinned Chromium headless shell is not installed at "
              + revision
              + "; run BrowserInstallerTest first");
    }
  }

  private static void requireVersion(Browser browser) {
    if (!PINNED_CHROMIUM_VERSION.equals(browser.version())) {
      throw new IllegalStateException(
          "expected Chromium " + PINNED_CHROMIUM_VERSION + " but launched " + browser.version());
    }
  }

  record BrowserBounds(double minX, double minY, double width, double height) {
    double maxX() {
      return minX + width;
    }

    double maxY() {
      return minY + height;
    }

    boolean hasArea() {
      return width > 0 && height > 0;
    }
  }

  record BrowserPoint(double x, double y) {}

  record ComputedStyle(
      String fill,
      String fillOpacity,
      String opacity,
      String stroke,
      String strokeWidth,
      String filter,
      String fontSize,
      String fontWeight,
      String fontFamily,
      String color) {}

  record Viewport(
      double minX,
      double minY,
      double width,
      double height,
      int pixelWidth,
      int pixelHeight,
      double semanticOffsetX,
      double semanticOffsetY,
      int padding) {

    int screenshotWidth() {
      return pixelWidth + 2 * padding;
    }

    int screenshotHeight() {
      return pixelHeight + 2 * padding;
    }

    double scale() {
      return Math.min(pixelWidth / width, pixelHeight / height);
    }

    private double offsetX() {
      return padding + (pixelWidth - width * scale()) / 2.0 - minX * scale();
    }

    private double offsetY() {
      return padding + (pixelHeight - height * scale()) / 2.0 - minY * scale();
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

    BrowserBounds toUserBounds(BrowserBounds imageBounds) {
      if (imageBounds == null) {
        return null;
      }
      return new BrowserBounds(
          userX(imageBounds.minX()),
          userY(imageBounds.minY()),
          imageBounds.width() / scale(),
          imageBounds.height() / scale());
    }
  }

  static final class BrowserSvg implements AutoCloseable {
    private final PreparedSvg prepared;
    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final Page page;
    private final List<String> blockedRequests;
    private final List<String> responses;
    private final boolean fontReady;
    private boolean closed;

    private BrowserSvg(
        PreparedSvg prepared,
        Playwright playwright,
        Browser browser,
        BrowserContext context,
        Page page,
        List<String> blockedRequests,
        List<String> responses,
        boolean fontReady) {
      this.prepared = prepared;
      this.playwright = playwright;
      this.browser = browser;
      this.context = context;
      this.page = page;
      this.blockedRequests = blockedRequests;
      this.responses = responses;
      this.fontReady = fontReady;
    }

    Viewport viewport() {
      return prepared.viewport();
    }

    String browserVersion() {
      return browser.version();
    }

    boolean fontReady() {
      return fontReady;
    }

    String computedFontFamily(String id) {
      return computedStyle(id).fontFamily();
    }

    ComputedStyle computedStyle(String id) {
      Map<String, Object> style =
          map(
              page.evaluate(
                  "id => { const element = document.getElementById(id); if (!element) throw new Error('SVG has no element with id: ' + id); const s = getComputedStyle(element); return {fill:s.fill,fillOpacity:s.fillOpacity,opacity:s.opacity,stroke:s.stroke,strokeWidth:s.strokeWidth,filter:s.filter,fontSize:s.fontSize,fontWeight:s.fontWeight,fontFamily:s.fontFamily,color:s.color}; }",
                  id));
      return new ComputedStyle(
          string(style, "fill"),
          string(style, "fillOpacity"),
          string(style, "opacity"),
          string(style, "stroke"),
          string(style, "strokeWidth"),
          string(style, "filter"),
          string(style, "fontSize"),
          string(style, "fontWeight"),
          string(style, "fontFamily"),
          string(style, "color"));
    }

    BrowserBounds geometryBounds(String id) {
      BrowserBounds raw =
          bounds(
              page.evaluate(
                  "id => { const e=document.getElementById(id); const root=document.querySelector('svg'); if (!(e instanceof SVGGraphicsElement)) throw new Error('element has no browser geometry: '+id); const b=e.getBBox(); const m=root.getCTM().inverse().multiply(e.getCTM()); const points=[[b.x,b.y],[b.x+b.width,b.y],[b.x+b.width,b.y+b.height],[b.x,b.y+b.height]].map(([x,y])=>new DOMPoint(x,y).matrixTransform(m)); const xs=points.map(p=>p.x), ys=points.map(p=>p.y); return {minX:Math.min(...xs),minY:Math.min(...ys),width:Math.max(...xs)-Math.min(...xs),height:Math.max(...ys)-Math.min(...ys)}; }",
                  id));
      return new BrowserBounds(
          raw.minX() - viewport().semanticOffsetX(),
          raw.minY() - viewport().semanticOffsetY(),
          raw.width(),
          raw.height());
    }

    BrowserBounds paintedBounds(String id) {
      BufferedImage mask = rasterizeNodes(this, List.of(id));
      BrowserBounds pixels = alphaBounds(mask);
      return pixels == null ? null : viewport().toUserBounds(pixels);
    }

    BrowserBounds paintBounds(String id) {
      return paintedBounds(id);
    }

    BrowserPoint transformPoint(String id, double x, double y) {
      Map<String, Object> point =
          map(
              page.evaluate(
                  "arg => { const e=document.getElementById(arg.id); const root=document.querySelector('svg'); if (!(e instanceof SVGGraphicsElement)) throw new Error('element has no browser transform: '+arg.id); const m=root.getCTM().inverse().multiply(e.getCTM()); const p=new DOMPoint(arg.x,arg.y).matrixTransform(m); return {x:p.x,y:p.y}; }",
                  Map.of("id", id, "x", x, "y", y)));
      return new BrowserPoint(
          number(point, "x") - viewport().semanticOffsetX(),
          number(point, "y") - viewport().semanticOffsetY());
    }

    byte[] screenshot() {
      ensureOpen();
      return BrowserTestSupport.screenshot(page);
    }

    BufferedImage screenshotImage() {
      return readPng(screenshot());
    }

    Object evaluate(String javascript, Object argument) {
      ensureOpen();
      return page.evaluate(javascript, argument);
    }

    boolean probeExternalRequestIsBlocked(String url) {
      ensureOpen();
      int before = blockedRequests.size();
      boolean rejected =
          Boolean.TRUE.equals(
              page.evaluate(
                  "async url => { try { await fetch(url); return false; } catch (error) { return true; } }",
                  url));
      return rejected
          && blockedRequests.size() == before + 1
          && url.equals(blockedRequests.get(blockedRequests.size() - 1))
          && responses.stream().noneMatch(url::equals);
    }

    List<String> blockedNetworkRequests() {
      return List.copyOf(blockedRequests);
    }

    List<String> escapedNetworkResponses() {
      return List.copyOf(responses);
    }

    private byte[] screenshotNodes(Collection<String> ids) {
      if (ids.isEmpty()) {
        return transparentScreenshot(viewport().screenshotWidth(), viewport().screenshotHeight());
      }
      List<String> stableIds = ids.stream().distinct().sorted().toList();
      page.evaluate(
          "ids => { const root=document.querySelector('svg'); const selected=ids.map(id=>{const e=document.getElementById(id);if(!e)throw new Error('SVG has no element with id: '+id);return e;}); window.__dedirenHidden=[]; for(const e of root.querySelectorAll('*')){if(e.closest('defs'))continue; const keep=selected.some(s=>s===e||s.contains(e)||e.contains(s)); if(!keep){window.__dedirenHidden.push([e,e.style.getPropertyValue('visibility'),e.style.getPropertyPriority('visibility')]);e.style.setProperty('visibility','hidden','important');}} }",
          stableIds);
      try {
        return screenshot();
      } finally {
        page.evaluate(
            "() => { for(const [e,value,priority] of (window.__dedirenHidden||[])){if(value)e.style.setProperty('visibility',value,priority);else e.style.removeProperty('visibility');} delete window.__dedirenHidden; }");
      }
    }

    private void requirePinnedBrowser() {
      requireVersion(browser);
    }

    private void ensureOpen() {
      if (closed) {
        throw new IllegalStateException("browser SVG is already closed");
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        page.close();
      } finally {
        try {
          context.close();
        } finally {
          try {
            browser.close();
          } finally {
            playwright.close();
          }
        }
      }
    }
  }

  private static BrowserBounds alphaBounds(BufferedImage image) {
    int minX = image.getWidth();
    int minY = image.getHeight();
    int maxX = -1;
    int maxY = -1;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if (((image.getRGB(x, y) >>> 24) & 0xff) != 0) {
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          maxX = Math.max(maxX, x);
          maxY = Math.max(maxY, y);
        }
      }
    }
    return maxX < 0 ? null : new BrowserBounds(minX, minY, maxX - minX + 1, maxY - minY + 1);
  }

  private static byte[] transparentScreenshot(int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    try (var output = new java.io.ByteArrayOutputStream()) {
      if (!ImageIO.write(image, "png", output)) {
        throw new IllegalStateException("the JDK has no PNG ImageIO writer");
      }
      return output.toByteArray();
    } catch (IOException failure) {
      throw new IllegalStateException("could not create transparent paint mask", failure);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private static String string(Map<String, Object> map, String key) {
    return String.valueOf(map.get(key));
  }

  private static double number(Map<String, Object> map, String key) {
    return ((Number) map.get(key)).doubleValue();
  }

  private static BrowserBounds bounds(Object value) {
    Map<String, Object> map = map(value);
    return new BrowserBounds(
        number(map, "minX"), number(map, "minY"), number(map, "width"), number(map, "height"));
  }

  private record PreparedSvg(String svg, Viewport viewport) {}

  private record BrowserSession(
      Playwright playwright,
      Browser browser,
      BrowserContext context,
      List<String> blockedRequests,
      List<String> responses)
      implements AutoCloseable {
    @Override
    public void close() {
      try {
        context.close();
      } finally {
        try {
          browser.close();
        } finally {
          playwright.close();
        }
      }
    }
  }
}

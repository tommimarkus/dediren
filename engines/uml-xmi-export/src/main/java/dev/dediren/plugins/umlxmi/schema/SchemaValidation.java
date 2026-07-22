package dev.dediren.plugins.umlxmi.schema;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.XMI_NS;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.isXmlId;

import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.plugins.umlxmi.build.XmiValidationException;
import dev.dediren.schemacache.InJvmXmlValidator;
import dev.dediren.schemacache.SchemaCacheException;
import dev.dediren.schemacache.SchemaCacheModule;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;

public final class SchemaValidation {

  private SchemaValidation() {}

  private static final String OMG_XMI_SCHEMA_URL = "https://www.omg.org/spec/XMI/20131001/XMI.xsd";
  // Pinned SHA-256 of the OMG XMI schema, verified after every runtime download (audit finding F2).
  // Source: https://www.omg.org/spec/XMI/20131001/XMI.xsd — retrieved 2026-07-04.
  private static final String OMG_XMI_SCHEMA_SHA256 =
      "7b228718d35a15a8e2fb99f078d36eee4b23b7a2cd405e7ac81b9d27bf2fab5d";
  public static final String XMI_SCHEMA_PATH_ENV = "DEDIREN_XMI_SCHEMA_PATH";
  // The proxy half is the shared SchemaCacheModule.PROXY_REMEDIATION (issue #35); only the
  // offline-placement tail is this engine's own. Appended to DEDIREN_XMI_SCHEMA_UNAVAILABLE so an
  // agent can recover from stdout JSON alone.
  private static final String XMI_SCHEMA_DOWNLOAD_REMEDIATION =
      SchemaCacheModule.PROXY_REMEDIATION
          + " To skip the download, pre-fetch the OMG XMI.xsd and"
          + " set DEDIREN_XMI_SCHEMA_PATH to its absolute file path.";
  // The offline lane never downloads, so its failures get placement advice, not proxy advice.
  private static final String XMI_SCHEMA_OFFLINE_REMEDIATION =
      "Replace the file at DEDIREN_XMI_SCHEMA_PATH with a usable OMG XMI.xsd, or a driver schema"
          + " whose imports sit beside it in the same directory.";

  /**
   * Legacy two-argument seam retained for the same-package validation tests; resolves schema/cache
   * env paths against the JVM cwd. The engine path threads an explicit {@code productRoot} through
   * the three-argument form instead.
   */
  public static Diagnostic validateXmiToAvailableStandards(String content, Map<String, String> env)
      throws XmiValidationException {
    return validateXmiToAvailableStandards(content, env, Path.of("").toAbsolutePath());
  }

  /**
   * Validates and returns the shared conformance report ({@code info}): exactly which standards
   * schema the artifact was validated against, its provenance, and whether UML-namespace content
   * rode the known no-normative-UML-XSD gap.
   */
  public static Diagnostic validateXmiToAvailableStandards(
      String content, Map<String, String> env, Path productRoot) throws XmiValidationException {
    validateXmiDocumentAndIds(content);
    return validateOmgXmiSchema(content, env, productRoot);
  }

  /**
   * Local DOM validation seam (package-private for tests): parses {@code content} with the hardened
   * DOCTYPE-disallowing builder and checks the XMI root, forbidden {@code xmi:version}, and {@code
   * xmi:id} shape/uniqueness before the schema-validation step. The fuzz-regression target {@code
   * SchemaValidationFuzzTest} (audit finding F7) exercises this path directly to prove the only
   * Throwable that escapes is {@link XmiValidationException}.
   */
  static void validateXmiDocumentAndIds(String content) throws XmiValidationException {
    try {
      var factory = secureXmiDocumentBuilderFactory();
      var document =
          factory
              .newDocumentBuilder()
              .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
      Element root = document.getDocumentElement();
      if (!XMI_NS.equals(root.getNamespaceURI()) || !"XMI".equals(root.getLocalName())) {
        throw new XmiValidationException(
            DiagnosticCode.XMI_SCHEMA_INVALID.code(),
            "generated UML/XMI XML root must be xmi:XMI in the OMG XMI namespace");
      }
      if (root.hasAttributeNS(XMI_NS, "version")) {
        throw new XmiValidationException(
            DiagnosticCode.XMI_SCHEMA_INVALID.code(),
            "generated UML/XMI XML uses xmi:version, which OMG XMI.xsd does not allow");
      }
      Set<String> ids = new HashSet<>();
      var elements = document.getElementsByTagName("*");
      for (int i = 0; i < elements.getLength(); i++) {
        Element element = (Element) elements.item(i);
        String id = element.getAttributeNS(XMI_NS, "id");
        if (id.isEmpty()) {
          continue;
        }
        if (!isXmlId(id)) {
          throw new XmiValidationException(
              DiagnosticCode.XMI_ID_INVALID.code(),
              "generated UML/XMI XML contains invalid xmi:id " + id);
        }
        if (!ids.add(id)) {
          throw new XmiValidationException(
              DiagnosticCode.XMI_ID_INVALID.code(),
              "generated UML/XMI XML contains duplicate xmi:id " + id);
        }
      }
    } catch (XmiValidationException error) {
      throw error;
    } catch (Exception error) {
      throw new XmiValidationException(
          DiagnosticCode.XMI_XML_INVALID.code(),
          "generated UML/XMI XML is not well-formed: " + error.getMessage());
    }
  }

  private static Diagnostic validateOmgXmiSchema(
      String content, Map<String, String> env, Path productRoot) throws XmiValidationException {
    XmiSchemaSource source = resolveOmgXmiSchemaPath(env, productRoot);
    // In-JVM validation: no xmllint subprocess in the trust path. A driver schema's imports
    // (XMI.xsd plus a UML XSD) resolve local-only from the schema file's own directory; a broken
    // schema set fails as a structured SCHEMA_UNAVAILABLE naming the unresolved reference and the
    // remediation for its own lane.
    InJvmXmlValidator.Outcome outcome;
    try {
      outcome = InJvmXmlValidator.validate(source.path(), content);
    } catch (SchemaCacheException error) {
      // advice() picks by failure class: placement/proxy remediation only fits a missing or broken
      // schema set; a saturated or timed-out validator says "retry" instead.
      throw new XmiValidationException(
          DiagnosticCode.XMI_SCHEMA_UNAVAILABLE.code(),
          error.getMessage() + " " + error.advice(source.unavailableRemediation()));
    }
    if (outcome.valid()) {
      return conformance(source.conformanceMessage() + "; the schema set accepted the UML content");
    }
    if (xmiSchemaErrorsAreOnlyUnavailableUmlSchema(outcome.details())) {
      return conformance(
          source.conformanceMessage()
              + "; UML-namespace content accepted — no normative OMG UML 2.5.1 XSD is published");
    }
    throw new XmiValidationException(
        DiagnosticCode.XMI_SCHEMA_INVALID.code(),
        "generated UML/XMI XML does not validate against OMG XMI.xsd: " + outcome.details());
  }

  private static Diagnostic conformance(String message) {
    return new Diagnostic(
        DiagnosticCode.EXPORT_SCHEMA_CONFORMANCE.code(),
        DiagnosticSeverity.INFO,
        message,
        "content");
  }

  /**
   * Where the schema came from decides which remediation a later failure names and how the
   * conformance report describes the schema's provenance.
   */
  private record XmiSchemaSource(
      Path path, String unavailableRemediation, String conformanceMessage) {}

  private static XmiSchemaSource resolveOmgXmiSchemaPath(Map<String, String> env, Path productRoot)
      throws XmiValidationException {
    // Decision 9: a relative schema/cache env path resolves against the product root, not the JVM
    // cwd, so an in-memory build path can supply the product root explicitly. An absolute value is
    // returned unchanged by Path.resolve, so the process path (product root == child cwd) is
    // byte-identical.
    Map<String, String> schemaEnv = productRootRelativeEnv(env, productRoot);
    Optional<Path> configured = SchemaCacheModule.nonEmptyEnvPath(schemaEnv, XMI_SCHEMA_PATH_ENV);
    if (configured.isPresent()) {
      if (SchemaCacheModule.isNonEmptyFile(configured.get())) {
        return new XmiSchemaSource(
            configured.get(),
            XMI_SCHEMA_OFFLINE_REMEDIATION,
            "validated in-JVM against the schema at the user-supplied DEDIREN_XMI_SCHEMA_PATH");
      }
      throw new XmiValidationException(
          DiagnosticCode.XMI_SCHEMA_UNAVAILABLE.code(),
          "OMG XMI schema file "
              + configured.get()
              + " is missing or empty; provide the official XMI.xsd or unset "
              + XMI_SCHEMA_PATH_ENV
              + " to allow cache download");
    }
    Path schemaPath;
    try {
      schemaPath =
          SchemaCacheModule.schemaCacheBaseDir(
                  schemaEnv, SchemaCacheModule.SCHEMA_CACHE_DIR_ENV, XMI_SCHEMA_PATH_ENV)
              .resolve("omg")
              .resolve("xmi")
              .resolve("2.5.1")
              .resolve("XMI.xsd");
      SchemaCacheModule.ensureCachedSchemaFile(
          schemaPath,
          URI.create(OMG_XMI_SCHEMA_URL),
          "OMG XMI schema",
          OMG_XMI_SCHEMA_SHA256,
          SchemaCacheModule.curlFetcher(SchemaCacheModule.SCHEMA_FETCHER));
    } catch (SchemaCacheException error) {
      throw new XmiValidationException(
          DiagnosticCode.XMI_SCHEMA_UNAVAILABLE.code(),
          error.getMessage() + " " + XMI_SCHEMA_DOWNLOAD_REMEDIATION);
    }
    return new XmiSchemaSource(
        schemaPath,
        XMI_SCHEMA_DOWNLOAD_REMEDIATION,
        "validated in-JVM against the pinned OMG XMI 2.5.1 XMI.xsd (SHA-256-verified download)");
  }

  /** Resolves this engine's schema/cache env paths against the product root (Decision 9). */
  public static Map<String, String> productRootRelativeEnv(
      Map<String, String> env, Path productRoot) {
    return SchemaCacheModule.productRootRelativeEnv(
        env, productRoot, XMI_SCHEMA_PATH_ENV, SchemaCacheModule.SCHEMA_CACHE_DIR_ENV);
  }

  /**
   * True when every reported problem is the one known, tolerated gap: elements in the UML namespace
   * that OMG XMI.xsd alone cannot declare, because no normative OMG UML XSD is published. The
   * generated document always binds prefix {@code uml} to {@value
   * dev.dediren.plugins.umlxmi.build.XmiHelpers#UML_NS}, so the Xerces messages name elements as
   * {@code 'uml:...'} — the two shapes below are the JDK validator's strict-wildcard
   * (cvc-complex-type.2.4.c) and no-declaration (cvc-elt.1.a) wordings, verified against the real
   * OMG XMI.xsd (spike 2026-07-22).
   */
  private static boolean xmiSchemaErrorsAreOnlyUnavailableUmlSchema(String details) {
    boolean sawUmlSchemaGap = false;
    for (String rawLine : details.lines().toList()) {
      String line = rawLine.trim();
      if (line.isEmpty()) {
        continue;
      }
      if (line.contains("no declaration can be found for element 'uml:")
          || line.contains("Cannot find the declaration of element 'uml:")) {
        sawUmlSchemaGap = true;
        continue;
      }
      return false;
    }
    return sawUmlSchemaGap;
  }

  public static DocumentBuilderFactory secureXmiDocumentBuilderFactory()
      throws ParserConfigurationException {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory;
  }
}

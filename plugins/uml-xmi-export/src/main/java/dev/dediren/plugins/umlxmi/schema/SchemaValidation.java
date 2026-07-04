package dev.dediren.plugins.umlxmi.schema;

import static dev.dediren.plugins.umlxmi.build.XmiHelpers.UML_NS;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.XMI_NS;
import static dev.dediren.plugins.umlxmi.build.XmiHelpers.isXmlId;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.plugins.umlxmi.build.XmiValidationException;
import dev.dediren.schemacache.SchemaCacheException;
import dev.dediren.schemacache.SchemaCacheModule;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
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

  public static final String XMI_SCHEMA_VALIDATOR = "xmllint";
  private static final String OMG_XMI_SCHEMA_URL = "https://www.omg.org/spec/XMI/20131001/XMI.xsd";
  // Pinned SHA-256 of the OMG XMI schema, verified after every runtime download (audit finding F2).
  // Source: https://www.omg.org/spec/XMI/20131001/XMI.xsd — retrieved 2026-07-04.
  private static final String OMG_XMI_SCHEMA_SHA256 =
      "7b228718d35a15a8e2fb99f078d36eee4b23b7a2cd405e7ac81b9d27bf2fab5d";
  public static final String XMI_SCHEMA_PATH_ENV = "DEDIREN_XMI_SCHEMA_PATH";
  public static final String SCHEMA_CACHE_DIR_ENV = "DEDIREN_SCHEMA_CACHE_DIR";
  public static final String SCHEMA_FETCHER = "curl";
  // Names both self-serve remediations for a failed schema download (issue #35): expose proxy
  // configuration to the plugin child, or skip the download by supplying XMI.xsd offline. Appended
  // to DEDIREN_XMI_SCHEMA_UNAVAILABLE so an agent can recover from stdout JSON alone.
  private static final String XMI_SCHEMA_DOWNLOAD_REMEDIATION =
      "To download through an HTTP proxy, expose HTTP_PROXY, HTTPS_PROXY, and NO_PROXY (or their"
          + " lowercase forms) to this plugin. To skip the download, pre-fetch the OMG XMI.xsd and"
          + " set DEDIREN_XMI_SCHEMA_PATH to its absolute file path.";

  public static boolean commandAvailable(String command) {
    try {
      return new ProcessBuilder(command, "--version").start().waitFor() == 0;
    } catch (IOException | InterruptedException error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  public static void validateXmiToAvailableStandards(String content, Map<String, String> env)
      throws XmiValidationException {
    validateXmiDocumentAndIds(content);
    validateOmgXmiSchema(content, env);
  }

  /**
   * Local DOM validation seam (package-private for tests): parses {@code content} with the hardened
   * DOCTYPE-disallowing builder and checks the XMI root, forbidden {@code xmi:version}, and {@code
   * xmi:id} shape/uniqueness without launching the external {@code xmllint} subprocess. The
   * fuzz-regression target {@code SchemaValidationFuzzTest} (audit finding F7) exercises this path
   * directly to prove the only Throwable that escapes is {@link XmiValidationException}.
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
            "DEDIREN_XMI_SCHEMA_INVALID",
            "generated UML/XMI XML root must be xmi:XMI in the OMG XMI namespace");
      }
      if (root.hasAttributeNS(XMI_NS, "version")) {
        throw new XmiValidationException(
            "DEDIREN_XMI_SCHEMA_INVALID",
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
              "DEDIREN_XMI_ID_INVALID", "generated UML/XMI XML contains invalid xmi:id " + id);
        }
        if (!ids.add(id)) {
          throw new XmiValidationException(
              "DEDIREN_XMI_ID_INVALID", "generated UML/XMI XML contains duplicate xmi:id " + id);
        }
      }
    } catch (XmiValidationException error) {
      throw error;
    } catch (Exception error) {
      throw new XmiValidationException(
          "DEDIREN_XMI_XML_INVALID",
          "generated UML/XMI XML is not well-formed: " + error.getMessage());
    }
  }

  private static void validateOmgXmiSchema(String content, Map<String, String> env)
      throws XmiValidationException {
    Path schemaPath = resolveOmgXmiSchemaPath(env);
    String validator = xmiSchemaValidator(env);
    Process process;
    try {
      process =
          new ProcessBuilder(
                  validator, "--nonet", "--noout", "--schema", schemaPath.toString(), "-")
              .start();
    } catch (IOException error) {
      throw new XmiValidationException(
          DiagnosticCode.XMI_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "failed to run OMG XMI schema validator " + validator + ": " + error.getMessage());
    }
    try (OutputStream stdin = process.getOutputStream()) {
      stdin.write(content.getBytes(StandardCharsets.UTF_8));
    } catch (IOException error) {
      throw new XmiValidationException(
          DiagnosticCode.XMI_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "failed to write UML/XMI XML to OMG XMI schema validator "
              + validator
              + ": "
              + error.getMessage());
    }
    try {
      byte[] stdout = process.getInputStream().readAllBytes();
      byte[] stderr = process.getErrorStream().readAllBytes();
      int exitCode = process.waitFor();
      if (exitCode == 0) {
        return;
      }
      String details = SchemaCacheModule.commandOutputDetails(validator, exitCode, stdout, stderr);
      if (xmiSchemaErrorsAreOnlyUnavailableUmlSchema(details)) {
        return;
      }
      throw new XmiValidationException(
          "DEDIREN_XMI_SCHEMA_INVALID",
          "generated UML/XMI XML does not validate against OMG XMI.xsd: " + details);
    } catch (IOException error) {
      throw new XmiValidationException(
          DiagnosticCode.XMI_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "failed to read OMG XMI schema validator output: " + error.getMessage());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new XmiValidationException(
          DiagnosticCode.XMI_SCHEMA_VALIDATOR_UNAVAILABLE.code(),
          "OMG XMI schema validator interrupted");
    }
  }

  private static String xmiSchemaValidator(Map<String, String> env) {
    String configured = env.get("DEDIREN_XMI_SCHEMA_VALIDATOR");
    return configured == null || configured.isBlank() ? XMI_SCHEMA_VALIDATOR : configured;
  }

  private static Path resolveOmgXmiSchemaPath(Map<String, String> env)
      throws XmiValidationException {
    Optional<Path> configured = SchemaCacheModule.nonEmptyEnvPath(env, XMI_SCHEMA_PATH_ENV);
    if (configured.isPresent()) {
      if (SchemaCacheModule.isNonEmptyFile(configured.get())) {
        return configured.get();
      }
      throw new XmiValidationException(
          "DEDIREN_XMI_SCHEMA_UNAVAILABLE",
          "OMG XMI schema file "
              + configured.get()
              + " is missing or empty; provide the official XMI.xsd or unset "
              + XMI_SCHEMA_PATH_ENV
              + " to allow cache download");
    }
    Path schemaPath;
    try {
      schemaPath =
          SchemaCacheModule.schemaCacheBaseDir(env, SCHEMA_CACHE_DIR_ENV, XMI_SCHEMA_PATH_ENV)
              .resolve("omg")
              .resolve("xmi")
              .resolve("2.5.1")
              .resolve("XMI.xsd");
      SchemaCacheModule.ensureCachedSchemaFile(
          schemaPath,
          URI.create(OMG_XMI_SCHEMA_URL),
          "OMG XMI schema",
          OMG_XMI_SCHEMA_SHA256,
          SchemaCacheModule.curlFetcher(SCHEMA_FETCHER));
    } catch (SchemaCacheException error) {
      throw new XmiValidationException(
          "DEDIREN_XMI_SCHEMA_UNAVAILABLE",
          error.getMessage() + " " + XMI_SCHEMA_DOWNLOAD_REMEDIATION);
    }
    return schemaPath;
  }

  private static boolean xmiSchemaErrorsAreOnlyUnavailableUmlSchema(String details) {
    boolean sawUmlSchemaGap = false;
    for (String rawLine : details.lines().toList()) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.endsWith("fails to validate")) {
        continue;
      }
      if (line.contains(UML_NS)
          && line.contains("No matching global element declaration available")) {
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

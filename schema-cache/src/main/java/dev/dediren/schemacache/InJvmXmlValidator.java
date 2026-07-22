package dev.dediren.schemacache;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * In-JVM XML Schema validation via {@code javax.xml.validation} — no subprocess, no external binary
 * in the trust path. Schema imports and includes resolve <em>local-only</em>: a relative reference
 * resolves as a path under the schema file's directory, an absolute URL (for example the W3C {@code
 * xml.xsd} the ArchiMate XSDs import) is served by file name from that directory, and anything the
 * directory cannot supply is reported by name in the compile failure — nothing is ever fetched at
 * validation time, so the lane is hermetic by construction.
 *
 * <p>The outcome mirrors {@link XmlSchemaValidator.Outcome}: document-validity problems collect
 * into an invalid outcome the caller maps to its notation's published diagnostic code, while a
 * broken validator lane — schema-set problems (missing or broken XSD, unresolved import), validator
 * configuration failures, or a run exceeding {@link XmlSchemaValidator#DEFAULT_TIMEOUT} — throws
 * {@link SchemaCacheException} for the caller's unavailable lane, matching the subprocess runner's
 * ran-and-invalid versus could-not-run split.
 */
public final class InJvmXmlValidator {
  // debug/trace only, by architecture rule. An invalid document is an Outcome the caller maps to a
  // published diagnostic code; it must not also be announced on stderr. See ArchitectureRulesTest.
  private static final Logger LOG = LoggerFactory.getLogger(InJvmXmlValidator.class);

  // A timed-out in-JVM compile/validate cannot be force-killed the way the subprocess lane's
  // destroyForcibly kills xmllint; the bounded future cancels (interrupts) and abandons the worker,
  // which is a daemon thread so a wedged validation never blocks process exit.
  private static final ExecutorService WORKERS =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "in-jvm-xml-validator");
            thread.setDaemon(true);
            return thread;
          });

  // Compiled grammars are immutable and thread-safe by JAXP contract, and recompiling the pinned
  // set dominates the per-call cost, so one Schema is shared per (path, size, mtime) — an edited
  // offline-directory schema recompiles, an unchanged one is compiled once per process.
  private static final ConcurrentHashMap<SchemaKey, Schema> COMPILED = new ConcurrentHashMap<>();

  private record SchemaKey(Path path, long size, long lastModifiedMillis) {}

  private InJvmXmlValidator() {}

  public static XmlSchemaValidator.Outcome validate(Path schemaPath, String content)
      throws SchemaCacheException {
    return validate(schemaPath, content, XmlSchemaValidator.DEFAULT_TIMEOUT);
  }

  static XmlSchemaValidator.Outcome validate(Path schemaPath, String content, Duration timeout)
      throws SchemaCacheException {
    // Which schema was compiled is the first question when a validation result surprises you.
    LOG.debug("in-JVM schema validation: schema={}", schemaPath);
    return runBounded(() -> doValidate(schemaPath, content), timeout, schemaPath);
  }

  static <T> T runBounded(Callable<T> work, Duration timeout, Path schemaPath)
      throws SchemaCacheException {
    Future<T> future = WORKERS.submit(work);
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException error) {
      future.cancel(true);
      throw new SchemaCacheException(
          "in-JVM schema validation against "
              + schemaPath
              + " did not complete within "
              + timeout.toSeconds()
              + "s");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new SchemaCacheException("in-JVM schema validation was interrupted", error);
    } catch (ExecutionException error) {
      if (error.getCause() instanceof SchemaCacheException cause) {
        throw cause;
      }
      throw new SchemaCacheException(
          "in-JVM schema validation failed unexpectedly: " + error.getCause(), error.getCause());
    }
  }

  private static XmlSchemaValidator.Outcome doValidate(Path schemaPath, String content)
      throws SchemaCacheException {
    Schema schema = compiledSchema(schemaPath);
    var errors = new ArrayList<String>();
    Validator validator;
    try {
      validator = schema.newValidator();
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      validator.setErrorHandler(collecting(errors));
    } catch (SAXException error) {
      // Configuration failures (an alternate JAXP provider rejecting the JAXP-1.5 lockdown
      // properties) are a broken validator, not an invalid document.
      throw new SchemaCacheException(
          "in-JVM schema validator could not be configured: " + message(error), error);
    }
    try {
      validator.validate(new StreamSource(new StringReader(content)));
    } catch (SAXException error) {
      // A fatal error reaches this catch only after the collecting handler already recorded it —
      // record the exception itself only when the handler saw nothing.
      if (errors.isEmpty()) {
        errors.add(message(error));
      }
    } catch (IOException error) {
      throw new SchemaCacheException(
          "in-JVM schema validation could not read the document: " + error.getMessage(), error);
    }
    return new XmlSchemaValidator.Outcome(
        errors.isEmpty(), errors.isEmpty() ? 0 : 1, String.join("\n", errors));
  }

  private static Schema compiledSchema(Path schemaPath) throws SchemaCacheException {
    SchemaKey key = keyFor(schemaPath);
    Schema cached = COMPILED.get(key);
    if (cached != null) {
      return cached;
    }
    Schema compiled = compile(schemaPath);
    COMPILED.put(key, compiled);
    return compiled;
  }

  static int compiledCacheSize() {
    return COMPILED.size();
  }

  private static SchemaKey keyFor(Path schemaPath) throws SchemaCacheException {
    try {
      return new SchemaKey(
          schemaPath.toAbsolutePath().normalize(),
          Files.size(schemaPath),
          Files.getLastModifiedTime(schemaPath).toMillis());
    } catch (IOException error) {
      throw new SchemaCacheException(
          "XML schema at " + schemaPath + " is not readable: " + error.getMessage(), error);
    }
  }

  private static Schema compile(Path schemaPath) throws SchemaCacheException {
    Set<String> unresolved = new LinkedHashSet<>();
    try {
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setResourceResolver(localOnly(schemaPath.getParent(), unresolved));
      return factory.newSchema(new StreamSource(schemaPath.toFile()));
    } catch (SAXException error) {
      String problems =
          unresolved.isEmpty()
              ? ""
              : " (unresolved schema references: " + String.join(", ", unresolved) + ")";
      throw new SchemaCacheException(
          "XML schema set at "
              + schemaPath.getParent()
              + " did not compile: "
              + message(error)
              + problems,
          error);
    }
  }

  /**
   * Serves referenced schema files from {@code directory} only. A relative reference resolves as a
   * path under the directory (subdirectories included); an absolute URL is served by file name from
   * the directory, where the pinned download places its local copy. Anything else stays unresolved
   * and is reported by name. The normalize/startsWith check is the enforcement that a reference —
   * including {@code ..} segments or platform separators — cannot escape the directory.
   */
  private static LSResourceResolver localOnly(Path directory, Set<String> unresolved) {
    return (type, namespaceUri, publicId, systemId, baseUri) -> {
      if (systemId == null || directory == null) {
        return null;
      }
      Path local = resolveWithin(directory, systemId);
      if (local == null || !SchemaCacheModule.isNonEmptyFile(local)) {
        unresolved.add(systemId);
        return null;
      }
      byte[] bytes;
      try {
        bytes = Files.readAllBytes(local);
      } catch (IOException error) {
        unresolved.add(systemId + " (unreadable: " + error.getMessage() + ")");
        return null;
      }
      return new LocalInput(publicId, local.toUri().toString(), baseUri, bytes);
    };
  }

  private static Path resolveWithin(Path directory, String systemId) {
    String reference = systemId;
    if (isAbsoluteUri(systemId)) {
      reference = systemId.substring(systemId.lastIndexOf('/') + 1);
      if (reference.isEmpty()) {
        return null;
      }
    }
    Path local;
    try {
      local = directory.resolve(reference).normalize();
    } catch (InvalidPathException error) {
      return null;
    }
    return local.startsWith(directory.normalize()) ? local : null;
  }

  private static boolean isAbsoluteUri(String systemId) {
    try {
      return new URI(systemId).isAbsolute();
    } catch (URISyntaxException error) {
      return false;
    }
  }

  private static String message(SAXException exception) {
    return Objects.requireNonNullElse(exception.getMessage(), exception.toString());
  }

  private static ErrorHandler collecting(List<String> errors) {
    return new ErrorHandler() {
      @Override
      public void warning(SAXParseException exception) {}

      @Override
      public void error(SAXParseException exception) {
        errors.add(describe(exception));
      }

      @Override
      public void fatalError(SAXParseException exception) {
        errors.add(describe(exception));
      }
    };
  }

  private static String describe(SAXParseException exception) {
    return "line "
        + exception.getLineNumber()
        + ": "
        + Objects.requireNonNullElse(exception.getMessage(), exception.toString());
  }

  private record LocalInput(String publicId, String systemId, String baseUri, byte[] bytes)
      implements LSInput {
    @Override
    public java.io.Reader getCharacterStream() {
      return null;
    }

    @Override
    public void setCharacterStream(java.io.Reader characterStream) {}

    @Override
    public java.io.InputStream getByteStream() {
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public void setByteStream(java.io.InputStream byteStream) {}

    @Override
    public String getStringData() {
      return null;
    }

    @Override
    public void setStringData(String stringData) {}

    @Override
    public String getSystemId() {
      return systemId;
    }

    @Override
    public void setSystemId(String systemId) {}

    @Override
    public String getPublicId() {
      return publicId;
    }

    @Override
    public void setPublicId(String publicId) {}

    @Override
    public String getBaseURI() {
      return baseUri;
    }

    @Override
    public void setBaseURI(String baseUri) {}

    @Override
    public String getEncoding() {
      return null;
    }

    @Override
    public void setEncoding(String encoding) {}

    @Override
    public boolean getCertifiedText() {
      return false;
    }

    @Override
    public void setCertifiedText(boolean certifiedText) {}
  }
}

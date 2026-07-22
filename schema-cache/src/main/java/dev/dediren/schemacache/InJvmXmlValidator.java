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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p>The outcome is deliberately code-free: document-validity problems collect into an invalid
 * {@link Outcome} the caller maps to its notation's published diagnostic code, while a broken
 * validator lane — schema-set problems (missing or broken XSD, unresolved import), validator
 * configuration failures, or a run exceeding {@link #DEFAULT_TIMEOUT} — throws {@link
 * SchemaCacheException} for the caller's unavailable lane, keeping ran-and-invalid distinct from
 * could-not-run.
 */
public final class InJvmXmlValidator {
  // debug/trace only, by architecture rule. An invalid document is an Outcome the caller maps to a
  // published diagnostic code; it must not also be announced on stderr. See ArchitectureRulesTest.
  private static final Logger LOG = LoggerFactory.getLogger(InJvmXmlValidator.class);

  // A timed-out in-JVM compile/validate cannot be force-killed the way the subprocess lane's
  // destroyForcibly kills xmllint; the bounded future cancels (interrupts) and abandons the worker,
  // which is a daemon thread so a wedged validation never blocks process exit. Because such a
  // worker cannot be reclaimed (Xerces does not observe the interrupt), the VALIDATION_GATE below
  // bounds how many can pile up.
  private static final ExecutorService WORKERS =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "in-jvm-xml-validator");
            thread.setDaemon(true);
            return thread;
          });

  // Bounds concurrent validations so abandoned (wedged) workers cannot accumulate without limit: at
  // most this many run at once, and a submission that would exceed it fails fast as an unavailable
  // lane rather than spawning another unreclaimable thread. The cap tracks core count within a
  // modest floor/ceiling — comfortably above this product's real concurrency (validations complete
  // in milliseconds), while capping leaked wedged threads to a bounded ceiling. Accepted tradeoff:
  // if that many workers ever wedge permanently, the lane stays unavailable (a saturation cliff)
  // rather than leaking unboundedly.
  static final int MAX_CONCURRENT_VALIDATIONS =
      Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 16));

  private static final Semaphore VALIDATION_GATE = new Semaphore(MAX_CONCURRENT_VALIDATIONS);

  // Compiled grammars are immutable and thread-safe by JAXP contract, and recompiling the pinned
  // set dominates the per-call cost, so one Schema is cached per top-file path and served only
  // while every file that shaped it still matches its compile-time (size, mtime) stamp. The stamp
  // set covers the top schema AND the imports/includes it pulled in — all confined to the schema
  // directory — so a changed import recompiles even when the top file is untouched. Keying on the
  // path (not a content stamp) overwrites in place on any change, so the map stays bounded by the
  // handful of distinct schema paths this product validates against, not by the edit history.
  private static final ConcurrentHashMap<Path, CachedSchema> COMPILED = new ConcurrentHashMap<>();

  // Defensive ceiling only: the product validates against a few distinct schema paths (the OEF and
  // XMI sets plus any offline overrides), so the map is naturally tiny. The cap bounds a
  // pathological caller that cycles through unbounded distinct schema paths in one process.
  private static final int MAX_CACHED_SCHEMAS = 64;

  // Test seam: counts real compiles so a suite can prove reuse (unchanged input does not recompile)
  // and invalidation (a changed dependency does), which the path-keyed map size alone cannot show.
  private static final AtomicLong COMPILE_COUNT = new AtomicLong();

  /** A file's freshness stamp under the cache's (size, mtime) change-detection heuristic. */
  private record FileStamp(Path path, long size, long lastModifiedMillis) {
    static FileStamp of(Path path) throws IOException {
      return new FileStamp(path, Files.size(path), Files.getLastModifiedTime(path).toMillis());
    }

    boolean matchesDisk() {
      try {
        return Files.size(path) == size
            && Files.getLastModifiedTime(path).toMillis() == lastModifiedMillis;
      } catch (IOException changedOrGone) {
        // A dependency that vanished or became unreadable counts as changed: recompiling then
        // surfaces the real structured failure instead of serving a grammar built from it.
        return false;
      }
    }
  }

  /** A compiled grammar paired with the freshness stamps of every file that shaped it. */
  private record CachedSchema(Schema schema, List<FileStamp> dependencies) {
    boolean isFresh() {
      for (FileStamp dependency : dependencies) {
        if (!dependency.matchesDisk()) {
          return false;
        }
      }
      return true;
    }
  }

  private record CompileResult(Schema schema, List<FileStamp> stamps) {}

  private InJvmXmlValidator() {}

  /** Wall-clock ceiling for one compile+validate run before it is reported as unavailable. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  /**
   * The result of one validation. {@code details} carries the collected line-numbered problems for
   * an invalid document and is empty for a valid one.
   */
  public record Outcome(boolean valid, String details) {}

  public static Outcome validate(Path schemaPath, String content) throws SchemaCacheException {
    return validate(schemaPath, content, DEFAULT_TIMEOUT);
  }

  static Outcome validate(Path schemaPath, String content, Duration timeout)
      throws SchemaCacheException {
    // Which schema was compiled is the first question when a validation result surprises you.
    LOG.debug("in-JVM schema validation: schema={}", schemaPath);
    return runBounded(() -> doValidate(schemaPath, content), timeout, schemaPath);
  }

  static <T> T runBounded(Callable<T> work, Duration timeout, Path schemaPath)
      throws SchemaCacheException {
    if (!VALIDATION_GATE.tryAcquire()) {
      throw new SchemaCacheException(
          SchemaCacheException.Kind.SATURATED,
          "in-JVM schema validation against "
              + schemaPath
              + " could not start: the validator is at capacity ("
              + MAX_CONCURRENT_VALIDATIONS
              + " concurrent validations in flight; a prior validation may be wedged past its"
              + " timeout)");
    }
    // The permit is released by the worker itself (see releasingPermit), not here: a worker the
    // cancel below cannot interrupt keeps holding its permit until it truly finishes, which is what
    // makes the gate bound wedged workers rather than merely serialize timeouts.
    Future<T> future = WORKERS.submit(releasingPermit(work));
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException error) {
      future.cancel(true);
      throw new SchemaCacheException(
          SchemaCacheException.Kind.TIMEOUT,
          "in-JVM schema validation against "
              + schemaPath
              + " did not complete within "
              + timeout.toSeconds()
              + "s");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new SchemaCacheException(
          SchemaCacheException.Kind.TIMEOUT, "in-JVM schema validation was interrupted", error);
    } catch (ExecutionException error) {
      if (error.getCause() instanceof SchemaCacheException cause) {
        throw cause;
      }
      throw new SchemaCacheException(
          SchemaCacheException.Kind.CONFIG,
          "in-JVM schema validation failed unexpectedly: " + error.getCause(),
          error.getCause());
    }
  }

  /** Wraps the work so the concurrency permit is released when the worker actually finishes. */
  private static <T> Callable<T> releasingPermit(Callable<T> work) {
    return () -> {
      try {
        return work.call();
      } finally {
        VALIDATION_GATE.release();
      }
    };
  }

  private static Outcome doValidate(Path schemaPath, String content) throws SchemaCacheException {
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
          SchemaCacheException.Kind.CONFIG,
          "in-JVM schema validator could not be configured: " + message(error),
          error);
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
          SchemaCacheException.Kind.CONFIG,
          "in-JVM schema validation could not read the document: " + error.getMessage(),
          error);
    }
    return new Outcome(errors.isEmpty(), String.join("\n", errors));
  }

  private static Schema compiledSchema(Path schemaPath) throws SchemaCacheException {
    Path key = schemaPath.toAbsolutePath().normalize();
    CachedSchema cached = COMPILED.get(key);
    if (cached != null && cached.isFresh()) {
      return cached.schema();
    }
    CompileResult compiled = compile(schemaPath);
    COMPILE_COUNT.incrementAndGet();
    cacheCompiled(key, compiled);
    return compiled.schema();
  }

  /**
   * Stores the compiled grammar with the freshness stamps captured when each file was <em>read</em>
   * (top schema before compile started, every import before its bytes were served to the compiler).
   * Stamping at read time — never after the compile — means a file rewritten mid-compile carries
   * its pre-rewrite stamp, so the worst case is one extra recompile on the next call, never a stale
   * grammar served as fresh.
   */
  private static void cacheCompiled(Path key, CompileResult compiled) {
    if (COMPILED.size() >= MAX_CACHED_SCHEMAS && !COMPILED.containsKey(key)) {
      evictOne();
    }
    COMPILED.put(key, new CachedSchema(compiled.schema(), compiled.stamps()));
  }

  private static void evictOne() {
    var iterator = COMPILED.keySet().iterator();
    if (iterator.hasNext()) {
      COMPILED.remove(iterator.next());
    }
  }

  static int compiledCacheSize() {
    return COMPILED.size();
  }

  static long compiledCount() {
    return COMPILE_COUNT.get();
  }

  static int availableValidationPermits() {
    return VALIDATION_GATE.availablePermits();
  }

  private static CompileResult compile(Path schemaPath) throws SchemaCacheException {
    Set<String> unresolved = new LinkedHashSet<>();
    Set<FileStamp> resolved = new LinkedHashSet<>();
    // The top file's stamp is captured before the compiler consumes its bytes (imports are stamped
    // the same way inside localOnly): a stamp taken before the read can only be conservative — a
    // rewrite in the window makes the cached entry look stale and recompile, never look fresh.
    FileStamp topStamp;
    try {
      topStamp = FileStamp.of(schemaPath.toAbsolutePath().normalize());
    } catch (IOException unreadable) {
      throw new SchemaCacheException(
          SchemaCacheException.Kind.SCHEMA_SET,
          "XML schema at " + schemaPath + " could not be read: " + unreadable.getMessage(),
          unreadable);
    }
    try {
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setResourceResolver(localOnly(schemaPath.getParent(), unresolved, resolved));
      Schema schema = factory.newSchema(new StreamSource(schemaPath.toFile()));
      var stamps = new ArrayList<FileStamp>(resolved.size() + 1);
      stamps.add(topStamp);
      stamps.addAll(resolved);
      return new CompileResult(schema, List.copyOf(stamps));
    } catch (SAXException error) {
      String problems =
          unresolved.isEmpty()
              ? ""
              : " (unresolved schema references: " + String.join(", ", unresolved) + ")";
      throw new SchemaCacheException(
          SchemaCacheException.Kind.SCHEMA_SET,
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
  private static LSResourceResolver localOnly(
      Path directory, Set<String> unresolved, Set<FileStamp> resolved) {
    return (type, namespaceUri, publicId, systemId, baseUri) -> {
      if (systemId == null || directory == null) {
        return null;
      }
      Path local = resolveWithin(directory, systemId);
      if (local == null || !SchemaCacheModule.isNonEmptyFile(local)) {
        unresolved.add(systemId);
        return null;
      }
      // The resolved set is the cache's dependency list: every served file that shaped the
      // grammar, stamped BEFORE its bytes are read so a mid-compile rewrite can only trigger an
      // extra recompile later, never a stale serve (see cacheCompiled).
      FileStamp stamp;
      byte[] bytes;
      try {
        stamp = FileStamp.of(local.toAbsolutePath().normalize());
        bytes = Files.readAllBytes(local);
      } catch (IOException error) {
        unresolved.add(systemId + " (unreadable: " + error.getMessage() + ")");
        return null;
      }
      resolved.add(stamp);
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

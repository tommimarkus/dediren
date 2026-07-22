package dev.dediren.schemacache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InJvmXmlValidatorTest {
  @TempDir Path dir;

  private static final String MAIN_SCHEMA =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
        targetNamespace="urn:dediren:test" elementFormDefault="qualified">
        <xs:element name="root">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="item" type="xs:string" minOccurs="0" maxOccurs="unbounded"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>
      </xs:schema>
      """;

  private static final String VALID_DOCUMENT =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <root xmlns="urn:dediren:test"><item>x</item></root>
      """;

  private Path writeSchema(String name, String content) throws Exception {
    Path path = dir.resolve(name);
    Files.createDirectories(path.getParent() == null ? dir : path.getParent());
    Files.writeString(path, content);
    return path;
  }

  @Test
  void validDocumentPasses() throws Exception {
    Path schema = writeSchema("main.xsd", MAIN_SCHEMA);

    InJvmXmlValidator.Outcome outcome = InJvmXmlValidator.validate(schema, VALID_DOCUMENT);

    assertThat(outcome.valid()).isTrue();
    assertThat(outcome.details()).isEmpty();
  }

  @Test
  void invalidDocumentCollectsLineNumberedErrors() throws Exception {
    Path schema = writeSchema("main.xsd", MAIN_SCHEMA);
    String invalid = "<root xmlns=\"urn:dediren:test\"><bogus/></root>";

    InJvmXmlValidator.Outcome outcome = InJvmXmlValidator.validate(schema, invalid);

    assertThat(outcome.valid()).isFalse();
    assertThat(outcome.details()).startsWith("line ").contains("bogus");
  }

  @Test
  void malformedDocumentIsRecordedExactlyOnce() throws Exception {
    // The parser rethrows a fatal error after the collecting handler has already recorded it; the
    // outcome must carry the failure once, not once per reporting path.
    Path schema = writeSchema("main.xsd", MAIN_SCHEMA);

    InJvmXmlValidator.Outcome outcome = InJvmXmlValidator.validate(schema, "<root");

    assertThat(outcome.valid()).isFalse();
    assertThat(outcome.details().lines()).hasSize(1);
  }

  @Test
  void missingImportFailsStructuredNamingTheReference() throws Exception {
    Path schema =
        writeSchema(
            "main.xsd",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
              targetNamespace="urn:dediren:test" elementFormDefault="qualified">
              <xs:import namespace="http://www.w3.org/XML/1998/namespace"
                schemaLocation="xml.xsd"/>
              <xs:element name="root" type="xs:string"/>
            </xs:schema>
            """);

    assertThatThrownBy(() -> InJvmXmlValidator.validate(schema, VALID_DOCUMENT))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining("did not compile")
        .hasMessageContaining("unresolved schema references: xml.xsd");
  }

  @Test
  void relativeSubdirectoryReferenceResolves() throws Exception {
    // A hand-supplied set may keep helper schemas in subdirectories; a relative schemaLocation
    // resolves as a path under the schema directory, not by bare file name.
    writeSchema(
        "sub/helper.xsd",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
          targetNamespace="urn:dediren:helper">
          <xs:attribute name="marker" type="xs:string"/>
        </xs:schema>
        """);
    Path schema =
        writeSchema(
            "main.xsd",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
              targetNamespace="urn:dediren:test" elementFormDefault="qualified">
              <xs:import namespace="urn:dediren:helper" schemaLocation="sub/helper.xsd"/>
              <xs:element name="root" type="xs:string"/>
            </xs:schema>
            """);

    InJvmXmlValidator.Outcome outcome =
        InJvmXmlValidator.validate(schema, "<root xmlns=\"urn:dediren:test\">ok</root>");

    assertThat(outcome.valid()).isTrue();
  }

  @Test
  void absoluteUrlReferenceServesTheLocalCopyByFileName() throws Exception {
    // The official ArchiMate XSDs import the xml namespace via an absolute w3.org URL; the
    // resolver serves the pinned local copy by file name instead of fetching.
    writeSchema(
        "xml.xsd",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
          targetNamespace="http://www.w3.org/XML/1998/namespace">
          <xs:attribute name="lang" type="xs:string"/>
        </xs:schema>
        """);
    Path schema =
        writeSchema(
            "main.xsd",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
              targetNamespace="urn:dediren:test" elementFormDefault="qualified">
              <xs:import namespace="http://www.w3.org/XML/1998/namespace"
                schemaLocation="https://www.w3.org/2001/xml.xsd"/>
              <xs:element name="root" type="xs:string"/>
            </xs:schema>
            """);

    InJvmXmlValidator.Outcome outcome =
        InJvmXmlValidator.validate(schema, "<root xmlns=\"urn:dediren:test\">ok</root>");

    assertThat(outcome.valid()).isTrue();
  }

  @Test
  void traversalReferenceStaysUnresolvedEvenWhenTheTargetExists() throws Exception {
    // Confinement: a reference escaping the schema directory is never served, even if the file is
    // really there — the normalize/startsWith check is the enforcement, not a restatement.
    Files.writeString(
        dir.resolve("outside.xsd"), "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>");
    Path inner = Files.createDirectories(dir.resolve("inner"));
    Path schema = inner.resolve("main.xsd");
    Files.writeString(
        schema,
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
          targetNamespace="urn:dediren:test" elementFormDefault="qualified">
          <xs:import namespace="urn:dediren:outside" schemaLocation="../outside.xsd"/>
          <xs:element name="root" type="xs:string"/>
        </xs:schema>
        """);

    assertThatThrownBy(() -> InJvmXmlValidator.validate(schema, VALID_DOCUMENT))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining("unresolved schema references: ../outside.xsd");
  }

  @Test
  void emptyReferencedFileIsReportedAsUnresolvedNotAsAParseArtifact() throws Exception {
    // A zero-byte xml.xsd (interrupted copy into a hand-populated directory) must surface as the
    // unresolved-reference path, not as a bare "premature end of file" parse artifact.
    writeSchema("xml.xsd", "");
    Path schema =
        writeSchema(
            "main.xsd",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
              targetNamespace="urn:dediren:test" elementFormDefault="qualified">
              <xs:import namespace="http://www.w3.org/XML/1998/namespace"
                schemaLocation="xml.xsd"/>
              <xs:element name="root" type="xs:string"/>
            </xs:schema>
            """);

    assertThatThrownBy(() -> InJvmXmlValidator.validate(schema, VALID_DOCUMENT))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining("unresolved schema references: xml.xsd");
  }

  @Test
  void missingSchemaFileIsStructured() {
    assertThatThrownBy(() -> InJvmXmlValidator.validate(dir.resolve("absent.xsd"), VALID_DOCUMENT))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining("absent.xsd");
  }

  @Test
  void compiledSchemaIsReusedUntilTheFileChanges() throws Exception {
    Path schema = writeSchema("memo.xsd", MAIN_SCHEMA);
    int sizeBefore = InJvmXmlValidator.compiledCacheSize();
    long compilesBefore = InJvmXmlValidator.compiledCount();

    InJvmXmlValidator.validate(schema, VALID_DOCUMENT);
    InJvmXmlValidator.validate(schema, VALID_DOCUMENT);
    assertThat(InJvmXmlValidator.validate(schema, VALID_DOCUMENT).valid()).isTrue();
    // One compile, then reused: repeated validation of the unchanged file does not recompile and
    // adds a single entry.
    assertThat(InJvmXmlValidator.compiledCount()).isEqualTo(compilesBefore + 1);
    assertThat(InJvmXmlValidator.compiledCacheSize()).isEqualTo(sizeBefore + 1);

    // A stricter, smaller grammar (the size stamp alone catches the edit) recompiles instead of
    // serving the stale one, and overwrites the path-keyed entry in place rather than growing.
    Files.writeString(
        schema,
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
          targetNamespace="urn:dediren:test" elementFormDefault="qualified">
          <xs:element name="root" type="xs:string"/>
        </xs:schema>
        """);
    Files.setLastModifiedTime(
        schema,
        java.nio.file.attribute.FileTime.fromMillis(
            Files.getLastModifiedTime(schema).toMillis() + 2_000));

    // VALID_DOCUMENT carries an <item> child the now string-typed root no longer allows.
    assertThat(InJvmXmlValidator.validate(schema, VALID_DOCUMENT).valid()).isFalse();
    assertThat(InJvmXmlValidator.compiledCount()).isEqualTo(compilesBefore + 2);
    assertThat(InJvmXmlValidator.compiledCacheSize()).isEqualTo(sizeBefore + 1);
  }

  @Test
  void compiledSchemaRecompilesWhenAnImportedSiblingChanges() throws Exception {
    // F1: the cached grammar embeds imported siblings, so a changed import must invalidate the
    // cache even though the top schema file is untouched.
    writeSchema(
        "sub/helper.xsd",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
          targetNamespace="urn:dediren:helper">
          <xs:simpleType name="value"><xs:restriction base="xs:string"/></xs:simpleType>
        </xs:schema>
        """);
    Path schema =
        writeSchema(
            "main.xsd",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:h="urn:dediren:helper"
              targetNamespace="urn:dediren:test" elementFormDefault="qualified">
              <xs:import namespace="urn:dediren:helper" schemaLocation="sub/helper.xsd"/>
              <xs:element name="root" type="h:value"/>
            </xs:schema>
            """);
    String document = "<root xmlns=\"urn:dediren:test\">abc</root>";
    int sizeBefore = InJvmXmlValidator.compiledCacheSize();
    long compilesBefore = InJvmXmlValidator.compiledCount();

    assertThat(InJvmXmlValidator.validate(schema, document).valid()).isTrue();
    assertThat(InJvmXmlValidator.validate(schema, document).valid()).isTrue();
    // Compiled once for the whole set, then the unchanged import is reused.
    assertThat(InJvmXmlValidator.compiledCount()).isEqualTo(compilesBefore + 1);

    // The imported sibling now restricts the value to an int; main.xsd is untouched.
    Path helper = dir.resolve("sub/helper.xsd");
    Files.writeString(
        helper,
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
          targetNamespace="urn:dediren:helper">
          <xs:simpleType name="value"><xs:restriction base="xs:int"/></xs:simpleType>
        </xs:schema>
        """);
    Files.setLastModifiedTime(
        helper,
        java.nio.file.attribute.FileTime.fromMillis(
            Files.getLastModifiedTime(helper).toMillis() + 2_000));

    // The recompiled grammar rejects the now-out-of-range document; the path-keyed entry was
    // overwritten in place rather than growing the cache.
    assertThat(InJvmXmlValidator.validate(schema, document).valid()).isFalse();
    assertThat(InJvmXmlValidator.compiledCount()).isEqualTo(compilesBefore + 2);
    assertThat(InJvmXmlValidator.compiledCacheSize()).isEqualTo(sizeBefore + 1);
  }

  @Test
  void boundedRunTimesOutAsStructuredUnavailable() {
    assertThatThrownBy(
            () ->
                InJvmXmlValidator.runBounded(
                    () -> {
                      Thread.sleep(5_000);
                      return null;
                    },
                    Duration.ofMillis(50),
                    dir.resolve("slow.xsd")))
        .isInstanceOf(SchemaCacheException.class)
        .hasMessageContaining("did not complete within");
  }

  @Test
  void runBoundedFailsFastWhenEveryValidationPermitIsHeld() throws Exception {
    // Fill every permit with a worker that ignores the cancel's interrupt, exactly as a wedged
    // Xerces validation would, then assert the next submission is rejected instead of spawning
    // another unreclaimable thread.
    int cap = InJvmXmlValidator.MAX_CONCURRENT_VALIDATIONS;
    Semaphore blocker = new Semaphore(0);
    Callable<Void> wedge =
        () -> {
          blocker.acquireUninterruptibly();
          return null;
        };
    Path schema = dir.resolve("wedged.xsd");
    try {
      for (int i = 0; i < cap; i++) {
        assertThatThrownBy(() -> InJvmXmlValidator.runBounded(wedge, Duration.ofMillis(50), schema))
            .isInstanceOf(SchemaCacheException.class)
            .hasMessageContaining("did not complete within");
      }
      assertThatThrownBy(
              () -> InJvmXmlValidator.runBounded(() -> null, Duration.ofSeconds(30), schema))
          .isInstanceOf(SchemaCacheException.class)
          .hasMessageContaining("at capacity");
    } finally {
      blocker.release(cap);
    }
    // The freed workers hand every permit back — no leak on the timeout path — so a later
    // validation starts from a full gate.
    for (int i = 0; i < 500 && InJvmXmlValidator.availableValidationPermits() < cap; i++) {
      Thread.sleep(10);
    }
    assertThat(InJvmXmlValidator.availableValidationPermits()).isEqualTo(cap);
  }
}

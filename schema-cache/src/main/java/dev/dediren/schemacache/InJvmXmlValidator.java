package dev.dediren.schemacache;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * In-JVM XML Schema validation via {@code javax.xml.validation} — no subprocess, no external binary
 * in the trust path. Schema imports and includes resolve <em>local-only</em>: a referenced file
 * (for example the W3C {@code xml.xsd} the ArchiMate XSDs import) is served from the schema file's
 * own directory or the compile fails — nothing is ever fetched at validation time, so the lane is
 * hermetic by construction.
 *
 * <p>The outcome mirrors {@link XmlSchemaValidator.Outcome}: document-validity problems collect
 * into an invalid outcome the caller maps to its notation's published diagnostic code, while a
 * schema-set problem (missing or broken XSD, unresolved import) throws {@link SchemaCacheException}
 * for the caller's unavailable lane.
 */
public final class InJvmXmlValidator {

  private InJvmXmlValidator() {}

  public static XmlSchemaValidator.Outcome validate(Path schemaPath, String content)
      throws SchemaCacheException {
    Schema schema = compile(schemaPath);
    var errors = new ArrayList<String>();
    try {
      Validator validator = schema.newValidator();
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      validator.setErrorHandler(collecting(errors));
      validator.validate(new StreamSource(new StringReader(content)));
    } catch (SAXException error) {
      errors.add(error.getMessage());
    } catch (IOException error) {
      throw new SchemaCacheException(
          "in-JVM schema validation could not read the document: " + error.getMessage(), error);
    }
    return new XmlSchemaValidator.Outcome(
        errors.isEmpty(), errors.isEmpty() ? 0 : 1, String.join("\n", errors));
  }

  private static Schema compile(Path schemaPath) throws SchemaCacheException {
    try {
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setResourceResolver(localOnly(schemaPath.getParent()));
      return factory.newSchema(new StreamSource(schemaPath.toFile()));
    } catch (SAXException error) {
      throw new SchemaCacheException(
          "XML schema set at "
              + schemaPath.getParent()
              + " did not compile: "
              + error.getMessage()
              + " (a schema import such as xml.xsd may be missing from the directory)",
          error);
    }
  }

  /** Serves referenced schema files from {@code directory} only; anything else stays unresolved. */
  private static LSResourceResolver localOnly(Path directory) {
    return (type, namespaceUri, publicId, systemId, baseUri) -> {
      if (systemId == null || directory == null) {
        return null;
      }
      String fileName = systemId.substring(systemId.lastIndexOf('/') + 1);
      Path local = directory.resolve(fileName);
      // The resolved name is a bare file name, so it cannot traverse out of the directory; the
      // normalize/startsWith check states that invariant defensively.
      if (!local.normalize().startsWith(directory.normalize()) || !Files.isRegularFile(local)) {
        return null;
      }
      byte[] bytes;
      try {
        bytes = Files.readAllBytes(local);
      } catch (IOException error) {
        return null;
      }
      return new LocalInput(publicId, local.toUri().toString(), baseUri, bytes);
    };
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

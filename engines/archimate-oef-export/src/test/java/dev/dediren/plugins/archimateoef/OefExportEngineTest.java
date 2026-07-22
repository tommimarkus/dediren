package dev.dediren.plugins.archimateoef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pins the {@link OefExportEngine} seam's envelope serialization: {@code
 * exportEnvelopeRoundTripsThroughHarness} wraps the engine's result in a command envelope through
 * the test-only {@link Main} harness and unwraps its {@code data}, asserting it JSON-equals the
 * value the engine returned directly. Post-cutover that harness delegates to this same engine, so
 * the guarantee is envelope wrap/unwrap round-trip stability, not the cross-process parity the
 * retired plugin process boundary once provided. The remaining cases pin that published post-parse
 * diagnostics throw {@link EngineException} with the same code and exit code, that unparseable
 * input surfaces as a raw (non-enveloped) parse failure through the engine's parse entry point, and
 * that relative schema env paths resolve against the supplied product root, not the JVM cwd.
 */
class OefExportEngineTest {
  @TempDir Path tempDir;

  private final OefExportEngine engine = new OefExportEngine();

  @Test
  void idIsArchimateOef() {
    assertThat(engine.id()).isEqualTo("archimate-oef");
  }

  @Test
  void exportWithShippedDefaultPolicyIdentityWarnsPlaceholder() throws Exception {
    // The shipped default policy hard-codes fixture identity and export succeeds with it
    // unchanged — the tripwire turns that silent wrong-identity ship into a decidable warning.
    ExportRequest request = engine.parseRequest(exportInput());

    EngineResult<ExportResult> result =
        engine.export(request, envWithOefSchemas(), Path.of("").toAbsolutePath());

    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_EXPORT_IDENTITY_PLACEHOLDER");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARNING);
              assertThat(diagnostic.message()).contains("id-dediren-oef-basic-model");
            });
  }

  @Test
  void exportWithRealModelIdentifierDoesNotWarnPlaceholder() throws Exception {
    JsonNode inputJson = exportInputJson();
    ((ObjectNode) inputJson.get("policy")).put("model_identifier", "id-acme-payments-model");
    byte[] input =
        JsonSupport.objectMapper().writeValueAsString(inputJson).getBytes(StandardCharsets.UTF_8);

    ExportRequest request = engine.parseRequest(input);
    EngineResult<ExportResult> result =
        engine.export(request, envWithOefSchemas(), Path.of("").toAbsolutePath());

    assertThat(result.diagnostics())
        .noneMatch(diagnostic -> diagnostic.code().equals("DEDIREN_EXPORT_IDENTITY_PLACEHOLDER"));
  }

  @Test
  void placeholderTripwireTracksTheShippedDefaultPolicy() throws Exception {
    // If the shipped default policy's identity ever changes, the engine's placeholder constant
    // must move with it or the tripwire goes blind.
    JsonNode shipped = fixtureJson("fixtures/export-policy/default-oef.json");

    assertThat(OefExportEngine.PLACEHOLDER_MODEL_IDENTIFIER)
        .isEqualTo(shipped.get("model_identifier").asText());
  }

  @Test
  void exportEnvelopeRoundTripsThroughHarness() throws Exception {
    byte[] input = exportInput();
    Map<String, String> env = envWithOefSchemas();

    ExportRequest request = engine.parseRequest(input);
    EngineResult<?> result = engine.export(request, env, Path.of("").toAbsolutePath());

    assertThat(engineTree(result.value())).isEqualTo(processData(input, env));
  }

  @Test
  void exportedContentScrubsXmlInvalidLabelCharacters() throws Exception {
    // A contract-valid label (model.schema constrains ids, not labels) may carry a C0 control
    // character such as BEL; the emitted OEF must still be well-formed XML, with the control
    // character replaced by U+FFFD rather than passed through raw (issue: shared XmlText scrub).
    JsonNode inputJson = exportInputJson();
    ((ObjectNode) inputJson.get("source").get("nodes").get(0))
        .put("label", "Orders\u0007Component");
    byte[] input =
        JsonSupport.objectMapper().writeValueAsString(inputJson).getBytes(StandardCharsets.UTF_8);

    ExportRequest request = engine.parseRequest(input);
    EngineResult<ExportResult> result =
        engine.export(request, envWithOefSchemas(), Path.of("").toAbsolutePath());
    String content = result.value().content();

    assertThat(parseXml(content)).isNotNull();
    assertThat(content).contains("�");
    assertThat(content).doesNotContain("\u0007");
  }

  @Test
  void exportRejectsInvalidPolicyWithPolicyInvalidCode() throws Exception {
    JsonNode inputJson = exportInputJson();
    ((ObjectNode) inputJson.get("policy")).remove("model_identifier");
    byte[] input =
        JsonSupport.objectMapper().writeValueAsString(inputJson).getBytes(StandardCharsets.UTF_8);

    ExportRequest request = engine.parseRequest(input);
    EngineException failure =
        assertThrows(
            EngineException.class,
            () -> engine.export(request, envWithOefSchemas(), Path.of("").toAbsolutePath()));

    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.diagnostics().get(0).code()).isEqualTo("DEDIREN_OEF_POLICY_INVALID");
  }

  @Test
  void parseRequestRejectsUnparseableInput() {
    // The OEF export publishes no parse-failure envelope: unparseable stdin surfaces as today's raw
    // (non-enveloped) failure, so the parse entry point throws rather than returning a diagnostic.
    assertThatThrownBy(() -> engine.parseRequest("not-json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(Exception.class);
  }

  @Test
  void relativeSchemaDirResolvesAgainstProductRootNotJvmCwd() {
    Path productRoot = Path.of("/x/y");

    Map<String, String> resolved =
        OefExportEngine.productRootRelativeEnv(
            Map.of("DEDIREN_OEF_SCHEMA_DIR", "schemas-oef"), productRoot, "DEDIREN_OEF_SCHEMA_DIR");

    assertThat(resolved.get("DEDIREN_OEF_SCHEMA_DIR"))
        .isEqualTo(productRoot.resolve("schemas-oef").toString());
  }

  @Test
  void absoluteSchemaDirIsUnchangedByProductRootResolution() {
    Path productRoot = Path.of("/x/y");
    String absolute = tempDir.resolve("oef-schemas").toString();

    Map<String, String> resolved =
        OefExportEngine.productRootRelativeEnv(
            Map.of("DEDIREN_OEF_SCHEMA_DIR", absolute), productRoot, "DEDIREN_OEF_SCHEMA_DIR");

    assertThat(resolved.get("DEDIREN_OEF_SCHEMA_DIR")).isEqualTo(absolute);
  }

  @Test
  void exportModelComposesEveryViewWithItsOwnIdentity() throws Exception {
    // Module-level twin of the CLI-level whole-model test: two views ride one document, the
    // policy `views` override wins for the view it names, the other gets the source-derived
    // default (`id-view-<id>` + source label), and a view unknown to the source falls back to
    // its id as the name. No OEF_VIEWS_OMITTED — nothing is omitted from the aggregate.
    var mapper = JsonSupport.objectMapper();
    var source =
        mapper.treeToValue(
            fixtureJson("fixtures/source/valid-archimate-oef.json"),
            dev.dediren.contracts.source.SourceDocument.class);
    var layout =
        mapper.treeToValue(
            fixtureJson("fixtures/layout-result/archimate-oef-basic.json"),
            dev.dediren.contracts.layout.LayoutResult.class);
    ObjectNode policy = (ObjectNode) fixtureJson("fixtures/export-policy/default-oef.json");
    ObjectNode views = policy.putObject("views");
    views.putObject("second").put("view_identifier", "id-second-override");

    java.util.Optional<EngineResult<ExportResult>> result =
        engine.exportModel(
            new dev.dediren.engine.ModelExportRequest(
                source,
                java.util.List.of(
                    new dev.dediren.engine.ModelExportRequest.ViewLayout("main", layout),
                    new dev.dediren.engine.ModelExportRequest.ViewLayout("second", layout)),
                policy),
            envWithOefSchemas(),
            Path.of("").toAbsolutePath());

    assertThat(result).isPresent();
    String content = result.get().value().content();
    assertThat(content)
        .contains("identifier=\"id-view-main\"")
        .contains("<name xml:lang=\"en\">Main</name>");
    assertThat(content)
        .contains("identifier=\"id-second-override\"")
        .contains("<name xml:lang=\"en\">second</name>");
    assertThat(result.get().diagnostics())
        .noneMatch(diagnostic -> diagnostic.code().equals("DEDIREN_OEF_VIEWS_OMITTED"));
  }

  @Test
  void exportModelWithNoViewsIsEmpty() throws Exception {
    var source =
        JsonSupport.objectMapper()
            .treeToValue(
                fixtureJson("fixtures/source/valid-archimate-oef.json"),
                dev.dediren.contracts.source.SourceDocument.class);

    assertThat(
            engine.exportModel(
                new dev.dediren.engine.ModelExportRequest(
                    source,
                    java.util.List.of(),
                    fixtureJson("fixtures/export-policy/default-oef.json")),
                envWithOefSchemas(),
                Path.of("").toAbsolutePath()))
        .isEmpty();
  }

  private static JsonNode engineTree(Object value) {
    return JsonSupport.objectMapper()
        .readTree(JsonSupport.objectMapper().writeValueAsString(value));
  }

  private static JsonNode processData(byte[] input, Map<String, String> env) throws Exception {
    PluginResult result =
        Main.executeForTesting(
            new String[] {"export"}, new String(input, StandardCharsets.UTF_8), env);
    assertThat(result.exitCode()).describedAs(result.stderr()).isZero();
    return JsonSupport.objectMapper().readTree(result.stdout()).get("data");
  }

  private byte[] exportInput() throws Exception {
    return JsonSupport.objectMapper()
        .writeValueAsString(exportInputJson())
        .getBytes(StandardCharsets.UTF_8);
  }

  private JsonNode exportInputJson() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", fixtureJson("fixtures/source/valid-archimate-oef.json"));
    input.set("layout_result", fixtureJson("fixtures/layout-result/archimate-oef-basic.json"));
    input.set("policy", fixtureJson("fixtures/export-policy/default-oef.json"));
    return input;
  }

  private Map<String, String> envWithOefSchemas() throws Exception {
    Path schemaDir = tempDir.resolve("oef-schemas");
    Files.createDirectories(schemaDir);
    String schema =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
          targetNamespace="http://www.opengroup.org/xsd/archimate/3.0/"
          xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
          elementFormDefault="qualified"
          attributeFormDefault="unqualified">
          <xs:element name="model">
            <xs:complexType>
              <xs:sequence>
                <xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/>
              </xs:sequence>
              <xs:attribute name="identifier" type="xs:ID" use="required"/>
              <xs:anyAttribute namespace="##any" processContents="lax"/>
            </xs:complexType>
          </xs:element>
          <xs:complexType name="ApplicationComponent" mixed="true">
            <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
            <xs:anyAttribute namespace="##any" processContents="lax"/>
          </xs:complexType>
          <xs:complexType name="ApplicationService" mixed="true">
            <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
            <xs:anyAttribute namespace="##any" processContents="lax"/>
          </xs:complexType>
          <xs:complexType name="Grouping" mixed="true">
            <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
            <xs:anyAttribute namespace="##any" processContents="lax"/>
          </xs:complexType>
          <xs:complexType name="Realization" mixed="true">
            <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
            <xs:anyAttribute namespace="##any" processContents="lax"/>
          </xs:complexType>
          <xs:complexType name="Element" mixed="true">
            <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
            <xs:anyAttribute namespace="##any" processContents="lax"/>
          </xs:complexType>
          <xs:complexType name="Relationship" mixed="true">
            <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
            <xs:anyAttribute namespace="##any" processContents="lax"/>
          </xs:complexType>
          <xs:complexType name="Diagram" mixed="true">
            <xs:sequence><xs:any minOccurs="0" maxOccurs="unbounded" processContents="lax"/></xs:sequence>
            <xs:anyAttribute namespace="##any" processContents="lax"/>
          </xs:complexType>
        </xs:schema>
        """;
    for (String fileName :
        new String[] {"archimate3_Model.xsd", "archimate3_View.xsd", "archimate3_Diagram.xsd"}) {
      Files.writeString(schemaDir.resolve(fileName), schema, StandardCharsets.UTF_8);
    }
    return Map.of("DEDIREN_OEF_SCHEMA_DIR", schemaDir.toString());
  }

  private static Document parseXml(String content) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    return factory.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
  }

  private static JsonNode fixtureJson(String path) throws Exception {
    return JsonSupport.objectMapper().readTree(Files.readString(workspaceRoot().resolve(path)));
  }

  private static Path workspaceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("schemas/model.schema.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from user.dir");
  }
}

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
import tools.jackson.databind.node.ArrayNode;
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
  void singleViewLaneHonorsAnExplicitPolicyViewsOverride() throws Exception {
    // Closes the Phase-1 limitation for the standalone <view-id>/oef.xml lane: an explicit
    // views[viewId] entry now resolves per-view identity here too, matching the whole-model lane.
    // The export fixture's laid-out view is "main"; a policy that omits views is unchanged (proven
    // byte-identical by the shipped-default golden tests, which carry no views entry).
    JsonNode inputJson = exportInputJson();
    ObjectNode override =
        ((ObjectNode) inputJson.get("policy")).putObject("views").putObject("main");
    override.put("view_identifier", "id-main-override");
    override.put("view_name", "Main Override");
    byte[] input =
        JsonSupport.objectMapper().writeValueAsString(inputJson).getBytes(StandardCharsets.UTF_8);

    ExportRequest request = engine.parseRequest(input);
    EngineResult<ExportResult> result =
        engine.export(request, envWithOefSchemas(), Path.of("").toAbsolutePath());

    assertThat(result.value().content()).contains("<view identifier=\"id-main-override\"");
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
    // its id as the name. No OEF_VIEWS_OMITTED — the supplied views cover every declared view.
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
  void exportModelWithASubsetOfDeclaredViewsDeclaresTheOmission() throws Exception {
    // Both build drivers can hand exportModel fewer views than the source declares (cli --views,
    // and the package lane excludes views whose layout failed), so the aggregate document can carry
    // fewer diagrams than declared; the omission gets the same info disclosure as the single-view
    // lane (issue #34) instead of shipping silently.
    var mapper = JsonSupport.objectMapper();
    ObjectNode sourceJson = (ObjectNode) fixtureJson("fixtures/source/valid-archimate-oef.json");
    ((tools.jackson.databind.node.ArrayNode) sourceJson.at("/plugins/generic-graph/views"))
        .addObject()
        .put("id", "detail")
        .put("label", "Detail");
    var source = mapper.treeToValue(sourceJson, dev.dediren.contracts.source.SourceDocument.class);
    var layout =
        mapper.treeToValue(
            fixtureJson("fixtures/layout-result/archimate-oef-basic.json"),
            dev.dediren.contracts.layout.LayoutResult.class);

    java.util.Optional<EngineResult<ExportResult>> result =
        engine.exportModel(
            new dev.dediren.engine.ModelExportRequest(
                source,
                java.util.List.of(
                    new dev.dediren.engine.ModelExportRequest.ViewLayout("main", layout)),
                fixtureJson("fixtures/export-policy/default-oef.json")),
            envWithOefSchemas(),
            Path.of("").toAbsolutePath());

    assertThat(result).isPresent();
    assertThat(result.get().diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_OEF_VIEWS_OMITTED");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.INFO);
              assertThat(diagnostic.path()).isEqualTo("source.plugins.generic-graph.views");
              assertThat(diagnostic.message())
                  .contains(
                      "1 of 2 ArchiMate views",
                      "omitted: detail",
                      "supplied laid-out views (main)");
            });
  }

  @Test
  void exportModelSupplyingEveryDeclaredViewEmitsNoViewCoverageDiagnostic() throws Exception {
    var mapper = JsonSupport.objectMapper();
    ObjectNode sourceJson = (ObjectNode) fixtureJson("fixtures/source/valid-archimate-oef.json");
    ((tools.jackson.databind.node.ArrayNode) sourceJson.at("/plugins/generic-graph/views"))
        .addObject()
        .put("id", "second")
        .put("label", "Second");
    var source = mapper.treeToValue(sourceJson, dev.dediren.contracts.source.SourceDocument.class);
    var layout =
        mapper.treeToValue(
            fixtureJson("fixtures/layout-result/archimate-oef-basic.json"),
            dev.dediren.contracts.layout.LayoutResult.class);

    java.util.Optional<EngineResult<ExportResult>> result =
        engine.exportModel(
            new dev.dediren.engine.ModelExportRequest(
                source,
                java.util.List.of(
                    new dev.dediren.engine.ModelExportRequest.ViewLayout("main", layout),
                    new dev.dediren.engine.ModelExportRequest.ViewLayout("second", layout)),
                fixtureJson("fixtures/export-policy/default-oef.json")),
            envWithOefSchemas(),
            Path.of("").toAbsolutePath());

    assertThat(result).isPresent();
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

  @Test
  void emptyRelationshipsOmitTheWrapperInsteadOfEmittingAnEmptyOne() throws Exception {
    // AM-OEF-1: the exchange XSD types RelationshipsType with minOccurs="1" on its child but makes
    // the <relationships> wrapper itself optional in ModelType, so a relationship-free model — a
    // capability map, a component inventory — must omit the wrapper. model.schema.json sets no
    // minItems, so this input is valid under dediren's own published contract.
    JsonNode inputJson = exportInputJson();
    ((ArrayNode) inputJson.get("source").get("relationships")).removeAll();
    ((ArrayNode) inputJson.at("/source/plugins/generic-graph/views/0/relationships")).removeAll();
    ((ArrayNode) inputJson.get("layout_result").get("edges")).removeAll();

    String content = exportContent(inputJson);

    assertThat(content).doesNotContain("<relationships>", "<relationships/>");
    assertThat(content).contains("<elements>");
  }

  @Test
  void emptyElementsOmitTheWrapperInsteadOfEmittingAnEmptyOne() throws Exception {
    // AM-OEF-2: same for ElementsType. A node-less model is contract-valid and must not be
    // rejected by the exchange schema on a wrapper dediren chose to emit.
    JsonNode inputJson = exportInputJson();
    ((ArrayNode) inputJson.get("source").get("nodes")).removeAll();
    ((ArrayNode) inputJson.get("source").get("relationships")).removeAll();
    ((ArrayNode) inputJson.at("/source/plugins/generic-graph/views/0/nodes")).removeAll();
    ((ArrayNode) inputJson.at("/source/plugins/generic-graph/views/0/relationships")).removeAll();
    ((ArrayNode) inputJson.get("layout_result").get("nodes")).removeAll();
    ((ArrayNode) inputJson.get("layout_result").get("edges")).removeAll();

    String content = exportContent(inputJson);

    assertThat(content).doesNotContain("<elements>", "<elements/>", "<relationships>");
    assertThat(content).contains("<model ");
  }

  @Test
  void negativeNodeCoordinatesAreClampedToZeroAndDisclosed() throws Exception {
    // AM-OEF-3: LocationGroup types x/y as xs:nonNegativeInteger, but layout-result.schema.json
    // types them as plain numbers with no minimum — and dediren's own ELK layout can produce a
    // negative coordinate, so this is unrepairable by editing source JSON. Clamp, and say so:
    // a silent move would be exactly the "declared, not silently dropped" falsehood DOC-9 names.
    JsonNode inputJson = exportInputJson();
    ((ObjectNode) inputJson.at("/layout_result/nodes/0")).put("x", -14.0);

    EngineResult<ExportResult> result = exportResult(inputJson);

    assertThat(result.value().content()).contains("x=\"0\"");
    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_OEF_GEOMETRY_CLAMPED");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARNING);
              assertThat(diagnostic.message()).contains("-14");
              assertThat(diagnostic.path()).isEqualTo("$.layout_result.nodes[0].x");
            });
  }

  @Test
  void subPixelSizesAreClampedToOneAndDisclosed() throws Exception {
    // AM-OEF-4: SizeGroup types w/h as xs:positiveInteger; layout-result.schema.json allows
    // exclusiveMinimum 0, so 0.4 is contract-valid and rounds to 0 — one sub-pixel node would
    // otherwise kill the whole export.
    JsonNode inputJson = exportInputJson();
    ((ObjectNode) inputJson.at("/layout_result/nodes/0")).put("width", 0.4);

    EngineResult<ExportResult> result = exportResult(inputJson);

    assertThat(result.value().content()).contains("w=\"1\"");
    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_OEF_GEOMETRY_CLAMPED");
              assertThat(diagnostic.path()).isEqualTo("$.layout_result.nodes[0].width");
            });
  }

  @Test
  void negativeRouteGeometryIsClampedToZeroAndDisclosed() throws Exception {
    // AM-OEF-5: LocationType reuses LocationGroup, so bendpoints and attachments carry the same
    // nonNegativeInteger constraint — an edge route leaving the positive quadrant breaks export
    // even when every node is in range.
    JsonNode inputJson = exportInputJson();
    ((ObjectNode) inputJson.at("/layout_result/edges/0/points/0")).put("y", -3.0);

    EngineResult<ExportResult> result = exportResult(inputJson);

    assertThat(result.value().content()).contains("<sourceAttachment x=\"173\" y=\"0\"/>");
    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_OEF_GEOMETRY_CLAMPED");
              assertThat(diagnostic.path()).isEqualTo("$.layout_result.edges[0].points[0].y");
            });
  }

  @Test
  void inRangeGeometryIsNeverReportedAsClamped() throws Exception {
    // The clamp must stay quiet on the ordinary case, or the disclosure is noise.
    EngineResult<ExportResult> result = exportResult(exportInputJson());

    assertThat(result.diagnostics())
        .noneMatch(diagnostic -> diagnostic.code().equals("DEDIREN_OEF_GEOMETRY_CLAMPED"));
  }

  private EngineResult<ExportResult> exportResult(JsonNode inputJson) throws Exception {
    ExportRequest request =
        engine.parseRequest(
            JsonSupport.objectMapper()
                .writeValueAsString(inputJson)
                .getBytes(StandardCharsets.UTF_8));
    return engine.export(request, envWithOefSchemas(), Path.of("").toAbsolutePath());
  }

  private String exportContent(JsonNode inputJson) throws Exception {
    return exportResult(inputJson).value().content();
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

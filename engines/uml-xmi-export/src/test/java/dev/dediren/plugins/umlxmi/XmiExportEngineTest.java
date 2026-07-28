package dev.dediren.plugins.umlxmi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.plugins.umlxmi.schema.SchemaValidation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pins the {@link XmiExportEngine} seam's envelope serialization: {@code
 * exportEnvelopeRoundTripsThroughHarness} wraps the engine's result in a command envelope through
 * the test-only {@link Main} harness and unwraps its {@code data}, asserting it JSON-equals the
 * value the engine returned directly. Post-cutover that harness delegates to this same engine, so
 * the guarantee is envelope wrap/unwrap round-trip stability, not the cross-process parity the
 * retired plugin process boundary once provided. The remaining cases pin that published post-parse
 * diagnostics throw {@link EngineException} with the same code and exit code, that unparseable
 * input surfaces as a raw (non-enveloped) parse failure through the engine's parse entry point, and
 * that relative schema env paths resolve against the supplied product root, not the JVM cwd.
 */
class XmiExportEngineTest {
  @TempDir Path tempDir;

  private final XmiExportEngine engine = new XmiExportEngine();

  @Test
  void idIsUmlXmi() {
    assertThat(engine.id()).isEqualTo("uml-xmi");
  }

  @Test
  void exportWithShippedDefaultPolicyIdentityWarnsPlaceholder() throws Exception {
    // The shipped default policy hard-codes fixture identity and export succeeds with it
    // unchanged — the tripwire turns that silent wrong-identity ship into a decidable warning.
    ExportRequest request = engine.parseRequest(exportInput());

    EngineResult<?> result =
        engine.export(request, envWithXmiSchema(), Path.of("").toAbsolutePath());

    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_EXPORT_IDENTITY_PLACEHOLDER");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.WARNING);
              assertThat(diagnostic.message()).contains("id-dediren-uml-basic-model");
            });
  }

  @Test
  void exportWithRealModelIdentifierDoesNotWarnPlaceholder() throws Exception {
    JsonNode inputJson = exportInputJson();
    ((ObjectNode) inputJson.get("policy")).put("model_identifier", "id-acme-payments-model");
    byte[] input =
        JsonSupport.objectMapper().writeValueAsString(inputJson).getBytes(StandardCharsets.UTF_8);

    ExportRequest request = engine.parseRequest(input);
    EngineResult<?> result =
        engine.export(request, envWithXmiSchema(), Path.of("").toAbsolutePath());

    assertThat(result.diagnostics())
        .noneMatch(diagnostic -> diagnostic.code().equals("DEDIREN_EXPORT_IDENTITY_PLACEHOLDER"));
  }

  @Test
  void placeholderTripwireTracksTheShippedDefaultPolicy() throws Exception {
    // If the shipped default policy's identity ever changes, the engine's placeholder constant
    // must move with it or the tripwire goes blind.
    JsonNode shipped = fixtureJson("fixtures/export-policy/default-uml-xmi.json");

    assertThat(XmiExportEngine.PLACEHOLDER_MODEL_IDENTIFIER)
        .isEqualTo(shipped.get("model_identifier").asText());
  }

  @Test
  void exportEnvelopeRoundTripsThroughHarness() throws Exception {
    byte[] input = exportInput();
    Map<String, String> env = envWithXmiSchema();

    ExportRequest request = engine.parseRequest(input);
    EngineResult<?> result = engine.export(request, env, Path.of("").toAbsolutePath());

    assertThat(engineTree(result.value())).isEqualTo(processData(input, env));
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
            () -> engine.export(request, envWithXmiSchema(), Path.of("").toAbsolutePath()));

    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.diagnostics().get(0).code()).isEqualTo("DEDIREN_UML_XMI_POLICY_INVALID");
  }

  @Test
  void parseRequestRejectsUnparseableInput() {
    // The UML/XMI export publishes no parse-failure envelope: unparseable stdin surfaces as today's
    // raw (non-enveloped) failure, so the parse entry point throws rather than returning an
    // envelope.
    assertThatThrownBy(() -> engine.parseRequest("not-json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(Exception.class);
  }

  @Test
  void relativeSchemaPathResolvesAgainstProductRootNotJvmCwd() {
    Path productRoot = Path.of("/x/y");

    Map<String, String> resolved =
        SchemaValidation.productRootRelativeEnv(
            Map.of("DEDIREN_XMI_SCHEMA_PATH", "schemas-xmi/XMI.xsd"), productRoot);

    assertThat(resolved.get("DEDIREN_XMI_SCHEMA_PATH"))
        .isEqualTo(productRoot.resolve("schemas-xmi/XMI.xsd").toString());
  }

  @Test
  void absoluteSchemaPathIsUnchangedByProductRootResolution() {
    Path productRoot = Path.of("/x/y");
    String absolute = tempDir.resolve("XMI.xsd").toString();

    Map<String, String> resolved =
        SchemaValidation.productRootRelativeEnv(
            Map.of("DEDIREN_XMI_SCHEMA_PATH", absolute), productRoot);

    assertThat(resolved.get("DEDIREN_XMI_SCHEMA_PATH")).isEqualTo(absolute);
  }

  @Test
  void activityViewSpanningTwoActivitiesEmitsEachEdgeUnderExactlyOneActivity() throws Exception {
    // Schema-legal input: nothing restricts a uml-activity view to one Activity. Each edge must
    // ride under the Activity BOTH its endpoints declare membership in (properties.uml.activity);
    // an unfiltered edge loop repeated every resolvable relationship — with its shared minted
    // xmi:id — under every Activity, hard-failing the export on the in-engine duplicate-id gate.
    ObjectNode source = (ObjectNode) fixtureJson("fixtures/source/valid-uml-basic.json");
    ArrayNode nodes = (ArrayNode) source.at("/nodes");
    nodes.addObject().put("id", "activity-secondary").put("type", "Activity").put("label", "Retry");
    addActivityMemberNode(nodes, "initial-secondary", "InitialNode");
    addActivityMemberNode(nodes, "final-secondary", "ActivityFinalNode");
    ((ArrayNode) source.at("/relationships"))
        .addObject()
        .put("id", "flow-secondary")
        .put("type", "ControlFlow")
        .put("source", "initial-secondary")
        .put("target", "final-secondary")
        .put("label", "");
    ObjectNode activityView = (ObjectNode) source.at("/plugins/generic-graph/views/2");
    ((ArrayNode) activityView.get("nodes")).add("initial-secondary").add("final-secondary");
    ((ArrayNode) activityView.get("relationships")).add("flow-secondary");
    ObjectNode layout = (ObjectNode) fixtureJson("fixtures/layout-result/uml-activity.json");
    addLaidOutNode(layout, "initial-secondary", 12.0, 200.0);
    addLaidOutNode(layout, "final-secondary", 142.0, 200.0);
    addLaidOutEdge(layout, "flow-secondary", "initial-secondary", "final-secondary");
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", source);
    input.set("layout_result", layout);
    input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));
    byte[] bytes =
        JsonSupport.objectMapper().writeValueAsString(input).getBytes(StandardCharsets.UTF_8);

    ExportRequest request = engine.parseRequest(bytes);
    EngineResult<ExportResult> result =
        engine.export(request, envWithXmiSchema(), Path.of("").toAbsolutePath());

    String content = result.value().content();
    String submitOrder = packagedElementAt(content, "id-activity-submit-order");
    String secondary = packagedElementAt(content, "id-activity-secondary");
    assertThat(submitOrder)
        .contains("id-flow-start-enter", "id-flow-submit-final")
        .doesNotContain("id-flow-secondary");
    assertThat(secondary).contains("id-flow-secondary").doesNotContain("id-flow-start-enter");
    assertThat(content).containsOnlyOnce("xmi:id=\"id-flow-secondary\"");
  }

  @Test
  void transitionWithAnOutOfScopeEndpointIsSkippedWithoutEmptyEndpointRefs() throws Exception {
    // XmlText.attr(null) is "": without the sibling containsKey guard, a Transition whose endpoint
    // vertex the layout does not select shipped source=""/target="" silently. The skip surfaces
    // through the in-view coverage lane instead of vanishing.
    ObjectNode layout =
        (ObjectNode) fixtureJson("fixtures/layout-result/uml-state-machine-basic.json");
    ArrayNode laidOutNodes = (ArrayNode) layout.get("nodes");
    for (int i = 0; i < laidOutNodes.size(); i++) {
      if (laidOutNodes.get(i).at("/source_id").asText().equals("rejected")) {
        laidOutNodes.remove(i);
        break;
      }
    }
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", fixtureJson("fixtures/source/valid-uml-state-machine-basic.json"));
    input.set("layout_result", layout);
    input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));
    byte[] bytes =
        JsonSupport.objectMapper().writeValueAsString(input).getBytes(StandardCharsets.UTF_8);

    ExportRequest request = engine.parseRequest(bytes);
    EngineResult<ExportResult> result =
        engine.export(request, envWithXmiSchema(), Path.of("").toAbsolutePath());

    assertThat(result.value().content())
        .contains("xmi:id=\"id-t-approve\"")
        .doesNotContain("id-t-reject")
        .doesNotContain("source=\"\"")
        .doesNotContain("target=\"\"");
    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic ->
                assertThat(diagnostic.code()).isEqualTo("DEDIREN_XMI_RELATIONSHIPS_OMITTED"));
  }

  @Test
  void attributeNamedLikeASiblingsDerivedMultiplicityIdKeepsIdsDistinct() throws Exception {
    // Attribute "qty" derives multiplicity child id id-class-order-qty-lower; a sibling attribute
    // literally named "qty-lower" then mints that exact string unless the derived ids are claimed
    // in the shared id space — a duplicate xmi:id hard-fails the export on schema-legal input.
    JsonNode inputJson = exportInputJson();
    ArrayNode attributes = (ArrayNode) inputJson.at("/source/nodes/1/properties/uml/attributes");
    attributes
        .addObject()
        .put("name", "qty")
        .put("type", "Integer")
        .put("visibility", "public")
        .put("multiplicity", "1");
    attributes
        .addObject()
        .put("name", "qty-lower")
        .put("type", "Integer")
        .put("visibility", "public")
        .put("multiplicity", "1");
    byte[] input =
        JsonSupport.objectMapper().writeValueAsString(inputJson).getBytes(StandardCharsets.UTF_8);

    ExportRequest request = engine.parseRequest(input);
    EngineResult<ExportResult> result =
        engine.export(request, envWithXmiSchema(), Path.of("").toAbsolutePath());

    String content = result.value().content();
    assertThat(content).containsOnlyOnce("xmi:id=\"id-class-order-qty-lower\"");
    assertThat(content).contains("xmi:id=\"id-class-order-qty-lower-2\"");
  }

  @Test
  void duplicateClassifierLabelsAreDeclaredAsTypeNameAmbiguity() throws Exception {
    // Name-based type resolution is forced by the source contract; the defect was the SILENCE of
    // the first-wins binding. class-order-line takes class-order's label, so type references by
    // 'Order' bind to the first claimant and the shadowing must be declared, not silent.
    JsonNode inputJson = exportInputJson();
    ((ObjectNode) inputJson.at("/source/nodes/3")).put("label", "Order");
    byte[] input =
        JsonSupport.objectMapper().writeValueAsString(inputJson).getBytes(StandardCharsets.UTF_8);

    ExportRequest request = engine.parseRequest(input);
    EngineResult<ExportResult> result =
        engine.export(request, envWithXmiSchema(), Path.of("").toAbsolutePath());

    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_XMI_TYPE_NAME_AMBIGUOUS");
              assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.INFO);
              assertThat(diagnostic.message())
                  .contains("'Order'")
                  .contains("bind to node 'class-order'")
                  .contains("ignored: class-order-line");
            });
  }

  @Test
  void uniqueClassifierLabelsEmitNoTypeNameAmbiguityDiagnostic() throws Exception {
    ExportRequest request = engine.parseRequest(exportInput());

    EngineResult<?> result =
        engine.export(request, envWithXmiSchema(), Path.of("").toAbsolutePath());

    assertThat(result.diagnostics())
        .noneMatch(diagnostic -> diagnostic.code().equals("DEDIREN_XMI_TYPE_NAME_AMBIGUOUS"));
  }

  /** The activity fixture's member-node shape: in the view, declaring this test's new activity. */
  private static void addActivityMemberNode(ArrayNode nodes, String id, String type) {
    ObjectNode node = nodes.addObject().put("id", id).put("type", type).put("label", "");
    node.putObject("properties").putObject("uml").put("activity", "activity-secondary");
  }

  private static void addLaidOutNode(ObjectNode layout, String id, double x, double y) {
    ObjectNode node = ((ArrayNode) layout.get("nodes")).addObject();
    node.put("id", id).put("source_id", id).put("projection_id", id);
    node.put("x", x).put("y", y).put("width", 32.0).put("height", 32.0);
    node.put("label", "").put("source_pointer", "/nodes/10");
  }

  private static void addLaidOutEdge(ObjectNode layout, String id, String source, String target) {
    ObjectNode edge = ((ArrayNode) layout.get("edges")).addObject();
    edge.put("id", id).put("source", source).put("target", target);
    edge.put("source_id", id).put("projection_id", id);
    edge.putArray("routing_hints");
    ArrayNode points = edge.putArray("points");
    points.addObject().put("x", 45.0).put("y", 216.0);
    points.addObject().put("x", 141.0).put("y", 216.0);
    edge.put("label", "").put("source_pointer", "/relationships/6");
  }

  /** The packagedElement substring from its {@code xmi:id} to its closing tag (no nesting). */
  private static String packagedElementAt(String content, String xmiId) {
    int start = content.indexOf("xmi:id=\"" + xmiId + "\"");
    assertThat(start).describedAs("element %s must be present", xmiId).isNotNegative();
    return content.substring(start, content.indexOf("</packagedElement>", start));
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
    input.set("source", fixtureJson("fixtures/source/valid-uml-basic.json"));
    input.set("layout_result", fixtureJson("fixtures/layout-result/uml-basic.json"));
    input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));
    return input;
  }

  private Map<String, String> envWithXmiSchema() throws Exception {
    Path schemaPath = tempDir.resolve("XMI.xsd");
    Files.writeString(
        schemaPath,
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                    targetNamespace="http://www.omg.org/spec/XMI/20131001"
                    xmlns="http://www.omg.org/spec/XMI/20131001"
                    elementFormDefault="qualified">
          <xsd:element name="XMI">
            <xsd:complexType>
              <xsd:choice minOccurs="0" maxOccurs="unbounded">
                <xsd:any processContents="lax"/>
              </xsd:choice>
              <xsd:anyAttribute processContents="lax"/>
            </xsd:complexType>
          </xsd:element>
        </xsd:schema>
        """,
        StandardCharsets.UTF_8);
    return Map.of("DEDIREN_XMI_SCHEMA_PATH", schemaPath.toString());
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

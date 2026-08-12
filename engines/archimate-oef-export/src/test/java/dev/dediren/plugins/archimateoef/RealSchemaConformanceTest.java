package dev.dediren.plugins.archimateoef;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Opt-in real-standards lane (architecture-guidelines §12): validates the emitter against the REAL,
 * pinned Open Group ArchiMate 3.1 XSD set via the engine's own SHA-256-verified cache download —
 * not the permissive stubs the default suites use. Run with {@code -Ddediren.real-schemas=true};
 * the first run needs network (or a warm cache directory), later runs are offline. The cache
 * location defaults to {@code ~/.cache/dediren-real-schemas} and can be overridden with {@code
 * -Ddediren.real-schemas.cache=<dir>}.
 *
 * <p>Coverage here is deliberately structural, not just happy-path: the stub schemas accept
 * anything, so every constraint the real XSD set enforces and dediren's own JSON contracts do not —
 * the required-child wrappers, the {@code nonNegativeInteger}/{@code positiveInteger} geometry
 * ranges, the grouping content model — can only be asserted in this lane.
 */
@EnabledIfSystemProperty(named = "dediren.real-schemas", matches = "true")
class RealSchemaConformanceTest {

  private final OefExportEngine engine = new OefExportEngine();

  @Test
  void fixtureExportValidatesAgainstThePinnedRealOpenGroupSchemaSet() throws Exception {
    ExportRequest request =
        engine.parseRequest(
            JsonSupport.objectMapper()
                .writeValueAsString(exportInput())
                .getBytes(StandardCharsets.UTF_8));

    EngineResult<ExportResult> result =
        engine.export(request, realSchemaCacheEnv(), Path.of("").toAbsolutePath());

    assertThat(result.value().content()).contains("<model");
    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_EXPORT_SCHEMA_CONFORMANCE");
              assertThat(diagnostic.message()).contains("pinned Open Group");
            });
  }

  @Test
  void relationshipFreeModelValidatesAgainstTheRealSchemaSet() throws Exception {
    // AM-OEF-1 against the real XSD, not the permissive stub the default suites use: emitting an
    // empty <relationships> failed here with "content of element 'relationships' is not complete".
    ObjectNode input = exportInput();
    ((ArrayNode) input.get("source").get("relationships")).removeAll();
    ((ArrayNode) input.at("/source/plugins/generic-graph/views/0/relationships")).removeAll();
    ((ArrayNode) input.get("layout_result").get("edges")).removeAll();

    assertThat(exportedContent(input)).doesNotContain("<relationships>");
  }

  @Test
  void elementFreeModelValidatesAgainstTheRealSchemaSet() throws Exception {
    // AM-OEF-2. ElementsType carries the same minOccurs="1" child constraint.
    ObjectNode input = exportInput();
    ((ArrayNode) input.get("source").get("nodes")).removeAll();
    ((ArrayNode) input.get("source").get("relationships")).removeAll();
    ((ArrayNode) input.at("/source/plugins/generic-graph/views/0/nodes")).removeAll();
    ((ArrayNode) input.at("/source/plugins/generic-graph/views/0/relationships")).removeAll();
    ((ArrayNode) input.get("layout_result").get("nodes")).removeAll();
    ((ArrayNode) input.get("layout_result").get("edges")).removeAll();

    assertThat(exportedContent(input)).doesNotContain("<elements>");
  }

  @Test
  void outOfRangeGeometryValidatesAgainstTheRealSchemaSetAfterClamping() throws Exception {
    // AM-OEF-3/4/5 together. LocationGroup is xs:nonNegativeInteger and SizeGroup is
    // xs:positiveInteger; all three values below are valid under layout-result.schema.json and
    // fatal here before the clamp. This is the assertion the stub schema cannot make.
    ObjectNode input = exportInput();
    ((ObjectNode) input.at("/layout_result/nodes/0")).put("x", -14.0).put("width", 0.4);
    ((ObjectNode) input.at("/layout_result/edges/0/points/0")).put("y", -3.0);

    assertThat(exportedContent(input)).contains("x=\"0\"", "w=\"1\"", "y=\"0\"");
  }

  @Test
  void groupedViewValidatesAgainstTheRealSchemaSet() throws Exception {
    // The happy-path fixture carries no group, so nothing pinned the emitted grouping shape
    // against the real Container/Element content model until now — and nesting members inside a
    // grouping's <node> is only legal because Element extends Container, which this asserts.
    ObjectNode input = exportInput();
    ((ArrayNode) input.get("source").get("nodes"))
        .addObject()
        .put("id", "customer-domain")
        .put("type", "Grouping")
        .put("label", "Customer Domain")
        .set("properties", JsonSupport.objectMapper().createObjectNode());
    ((ArrayNode) input.at("/source/plugins/generic-graph/views/0/nodes")).add("customer-domain");
    ((ObjectNode) input.get("layout_result")).set("groups", groupsWithMembers());

    assertThat(exportedContent(input))
        .contains(
            "elementRef=\"id-el-customer-domain\" x=\"0\" y=\"0\" w=\"520\" h=\"180\"><node ");
  }

  @Test
  void typedAndNonScalarPropertiesValidateAgainstTheRealSchemaSet() throws Exception {
    // DataType is an NMTOKEN enumeration, so a wrong `type` on a propertyDefinition is a hard
    // schema failure here — and the happy-path fixture carries no properties at all.
    ObjectNode input = exportInput();
    ObjectNode properties = (ObjectNode) input.at("/source/nodes/0/properties");
    properties.put("critical", true);
    properties.put("replicas", 3);
    properties.put("owner", "platform-team");
    properties.putArray("tags").add("core").add("payments");

    assertThat(exportedContent(input))
        .contains("type=\"boolean\"", "type=\"number\"", "type=\"string\"");
  }

  private ArrayNode groupsWithMembers() {
    ArrayNode groups = JsonSupport.objectMapper().createArrayNode();
    ObjectNode group = groups.addObject();
    group
        .put("id", "customer-domain-group")
        .put("source_id", "customer-domain")
        .put("projection_id", "customer-domain-group")
        .put("x", 0.0)
        .put("y", 0.0)
        .put("width", 520.0)
        .put("height", 180.0)
        .put("label", "Customer Domain");
    group.putObject("provenance").putObject("semantic_backed").put("source_id", "customer-domain");
    group.putArray("members").add("orders-component").add("orders-service");
    return groups;
  }

  private String exportedContent(ObjectNode input) throws Exception {
    ExportRequest request =
        engine.parseRequest(
            JsonSupport.objectMapper().writeValueAsString(input).getBytes(StandardCharsets.UTF_8));
    return engine
        .export(request, realSchemaCacheEnv(), Path.of("").toAbsolutePath())
        .value()
        .content();
  }

  private static ObjectNode exportInput() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", fixtureJson("fixtures/source/valid-archimate-oef.json"));
    input.set("layout_result", fixtureJson("fixtures/layout-result/archimate-oef-basic.json"));
    input.set("policy", fixtureJson("fixtures/export-policy/default-oef.json"));
    return input;
  }

  static Map<String, String> realSchemaCacheEnv() {
    String cache =
        System.getProperty(
            "dediren.real-schemas.cache",
            Path.of(System.getProperty("user.home"), ".cache", "dediren-real-schemas").toString());
    return Map.of("DEDIREN_SCHEMA_CACHE_DIR", cache);
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

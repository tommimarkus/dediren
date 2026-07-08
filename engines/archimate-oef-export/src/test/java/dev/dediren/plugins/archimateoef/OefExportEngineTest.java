package dev.dediren.plugins.archimateoef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
  void exportEnvelopeRoundTripsThroughHarness() throws Exception {
    byte[] input = exportInput();
    Map<String, String> env = envWithOefSchemas();

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
